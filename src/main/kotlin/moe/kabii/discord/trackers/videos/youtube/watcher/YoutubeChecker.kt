package moe.kabii.discord.trackers.videos.youtube.watcher

import discord4j.core.GatewayDiscordClient
import kotlinx.coroutines.*
import moe.kabii.LOG
import moe.kabii.data.relational.streams.youtube.*
import moe.kabii.discord.tasks.DiscordTaskPool
import moe.kabii.discord.trackers.videos.StreamErr
import moe.kabii.discord.trackers.videos.youtube.YoutubeParser
import moe.kabii.discord.trackers.videos.youtube.YoutubeVideoInfo
import moe.kabii.discord.trackers.videos.youtube.subscriber.YoutubeSubscriptionManager
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.structure.WithinExposedContext
import moe.kabii.structure.extensions.jodaDateTime
import moe.kabii.structure.extensions.loop
import moe.kabii.structure.extensions.propagateTransaction
import moe.kabii.structure.extensions.stackTraceString
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.joda.time.DateTime
import java.lang.Runnable
import java.time.Duration
import java.time.Instant
import kotlin.math.max

sealed class YoutubeCall(val video: YoutubeVideo) {
    class Live(val live: YoutubeLiveEvent) : YoutubeCall(live.ytVideo)
    class Scheduled(val scheduled: YoutubeScheduledEvent) : YoutubeCall(scheduled.ytVideo)
    class New(val new: YoutubeVideo) : YoutubeCall(new)
}

class YoutubeChecker(subscriptions: YoutubeSubscriptionManager, discord: GatewayDiscordClient): Runnable, YoutubeNotifier(subscriptions, discord) {
    override fun run() {
        loop {
            val start = Instant.now()
            propagateTransaction {
                try {
                    // youtube api has daily quota limits - we only hit /videos/ API and thus can chunk all of our calls
                    // gather all youtube IDs that need to be checked in the API

                    // create lookup map to associate video id with the original 'type' as it will be lost when passed to the youtube API
                    // <video id, target list>
                    val targetLookup = mutableMapOf<String, YoutubeCall>()

                    // 1: collect all videos we have as 'currently live'
                    val dbLiveVideos = YoutubeLiveEvent.all()
                    dbLiveVideos.forEach { live ->
                        val callReason = YoutubeCall.Live(live)
                        targetLookup[callReason.video.videoId] = callReason
                    }

                    // 2: collect all 'scheduled' videos with 'expire' update timer due
                    val currentTime = DateTime.now()
                    val dbScheduledVideos =
                        YoutubeScheduledEvent.find {
                            YoutubeScheduledEvents.dataExpiration lessEq currentTime
                        }
                    dbScheduledVideos.forEach { scheduled ->
                        val callReason = YoutubeCall.Scheduled(scheduled)
                        targetLookup[callReason.video.videoId] = callReason
                    }

                    // 3: collect all videos that are 'new' and we have no data on
                    val dbNewVideos = YoutubeVideo.find {
                        YoutubeVideos.lastAPICall eq null
                    }
                    dbNewVideos.forEach { new ->
                        val callReason = YoutubeCall.New(new)
                        targetLookup[callReason.video.videoId] = callReason
                    }

                    // main IO call, process as we go
                    targetLookup.keys
                        .asSequence()
                        .chunked(50)
                        .flatMap { chunk ->
                            LOG.debug("yt api call: $chunk")
                            YoutubeParser.getVideos(chunk).entries
                        }.map { (videoId, ytVideo) ->
                            taskScope.launch {
                                newSuspendedTransaction {
                                    try {
                                        val callReason = targetLookup.getValue(videoId)

                                        val ytVideoInfo = when (ytVideo) {
                                            is Ok -> {
                                                // if youtube call succeeded, reflect this in db
                                                with(callReason.video) {
                                                    lastAPICall = DateTime.now()
                                                    lastTitle = ytVideo.value.title
                                                }
                                                ytVideo.value
                                            }
                                            is Err -> {
                                                when (ytVideo.value) {
                                                    // do not process video if this was an IO issue on our end
                                                    is StreamErr.IO -> return@newSuspendedTransaction
                                                    is StreamErr.NotFound -> null
                                                }
                                            }
                                        }

                                        // call specific handlers for each type of content
                                        when (callReason) {
                                            is YoutubeCall.Live -> currentLiveCheck(callReason, ytVideoInfo)
                                            is YoutubeCall.Scheduled -> upcomingCheck(callReason, ytVideoInfo)
                                            is YoutubeCall.New -> newVideoCheck(callReason, ytVideoInfo)
                                        }
                                    } catch (e: Exception) {
                                        LOG.warn("Error processing YouTube video: $videoId: $ytVideo :: ${e.message}")
                                        LOG.debug(e.stackTraceString)
                                    }
                                }
                            }
                        }.forEach { job -> job.join() }

                    // clean up videos db
//                    val old = DateTime.now().minusWeeks(1)
//                    YoutubeVideos.deleteWhere {
//                        YoutubeVideos.lastAPICall lessEq old
//                    }
                } catch (e: Exception) {
                    LOG.warn("Uncaught exception in YoutubeChecker :: ${e.message}")
                    LOG.debug(e.stackTraceString)
                }
            }
            val runDuration = Duration.between(start, Instant.now())
            val delay = 60_000L - runDuration.toMillis()
            delay(max(delay, 0L))
        }
    }

    @WithinExposedContext
    private suspend fun currentLiveCheck(call: YoutubeCall.Live, ytVideo: YoutubeVideoInfo?) {
        val dbLive = call.live

        // if this function is called, video is marked as live in DB. check current state
        if(ytVideo?.live == true) {
            // stream is still live, update information
            val viewers = ytVideo.liveInfo?.concurrent
            if(viewers != null) {
                dbLive.updateViewers(viewers)
            } // else case seems to happen with membership streams that weren't initially private ? or similar cases

            // iterate all targets and make sure they have a notification - if a stream is tracked in a different server/channel while live, it would not be posted
            filteredTargets(call.video.ytChannel, dbLive::shouldPostLiveNotice).forEach { target ->
                // verify target already has a notification
                if(YoutubeNotification.getExisting(target, call.video).empty()) {
                    try {
                        createLiveNotification(call.video, ytVideo, target, new = false)
                    } catch(e: Exception) {
                        // catch and consume all exceptions here - if one target fails, we don't want this to affect the other targets in potentially different discord servers
                        LOG.warn("Error while creating live notification for channel: ${ytVideo.channel} :: ${e.message}")
                        LOG.debug(e.stackTraceString)
                    }
                }
            }
        } else {
            // stream has ended (live = false or video deleted)
            streamEnd(ytVideo, dbLive)
        }
    }

    @WithinExposedContext
    private suspend fun upcomingCheck(call: YoutubeCall.Scheduled, ytVideo: YoutubeVideoInfo?) {
        val dbEvent = call.scheduled
        when {
            // scheduled video is not accessible for us
            // leave 'video' in db in case it is re-published, we don't need to notify again
            ytVideo == null -> dbEvent.delete()
            ytVideo.live -> {
                // scheduled stream has started
                streamStart(ytVideo, call.video)
                dbEvent.delete()
            }
            ytVideo.upcoming -> {
                // event still exists and is not live yet
                val scheduled = ytVideo.liveInfo?.scheduledStart
                if(scheduled != null) {
                    dbEvent.scheduledStart = scheduled.jodaDateTime

                    // set next update time to 1/2 time until stream start
                    val untilStart = Duration.between(Instant.now(), scheduled)
                    val updateInterval =  untilStart.toMillis() / 2
                    val nextUpdate = DateTime.now().plus(updateInterval)
                    dbEvent.dataExpiration = nextUpdate

                    // send out 'upcoming' notifications
                    streamUpcoming(dbEvent, ytVideo, scheduled)

                } else {
                    LOG.warn("YouTube returned SCHEDULED stream with no start time: $ytVideo")
                }
            }
            else -> {
                // video exists, never went live ?
                dbEvent.delete()
            }
        }
    }

    private suspend fun newVideoCheck(call: YoutubeCall.New, ytVideo: YoutubeVideoInfo?) {
        if(ytVideo == null) {
            // if we don't have information on this video, and youtube provides no information, remove it.
            call.new.delete()
            return
        }
        val dbVideo = call.video
        propagateTransaction {
            when {
                ytVideo.upcoming -> {
                    val scheduled = checkNotNull(ytVideo.liveInfo?.scheduledStart) { "YouTube provided UPCOMING video with no start time" }
                    // assign video 'scheduled' status
                    val dbScheduled = propagateTransaction {
                        val dbScheduled = YoutubeScheduledEvent.getScheduled(dbVideo)
                            ?: YoutubeScheduledEvent.new {
                                this.ytVideo = dbVideo
                                this.scheduledStart = scheduled.jodaDateTime
                                this.dataExpiration = DateTime.now() // todo move calculation to function ?
                            }
                        dbVideo.scheduledEvent = dbScheduled
                        dbScheduled
                    }

                    // send 'upcoming' and/or 'creation' messages to appropriate targets
                    streamUpcoming(dbScheduled, ytVideo, scheduled)
                    streamCreated(dbVideo, ytVideo)
                }
                ytVideo.live -> {
                    streamStart(ytVideo, dbVideo)
                }
                else -> {
                    // regular video upload
                    videoUploaded(dbVideo, ytVideo)
                }
            }
        }
    }
}