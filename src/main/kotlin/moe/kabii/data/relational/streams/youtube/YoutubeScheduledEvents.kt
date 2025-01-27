package moe.kabii.data.relational.streams.youtube

import moe.kabii.LOG
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.util.extensions.stackTraceString
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.jodatime.datetime

object YoutubeScheduledEvents : LongIdTable() {
    val ytVideo = reference("yt_video", YoutubeVideos, ReferenceOption.CASCADE).uniqueIndex()
    val scheduledStart = datetime("scheduled_start_time")
    val dataExpiration = datetime("data_expiration_time")
}

class YoutubeScheduledEvent(id: EntityID<Long>) : LongEntity(id) {
    var ytVideo by YoutubeVideo referencedOn YoutubeScheduledEvents.ytVideo
    var scheduledStart by YoutubeScheduledEvents.scheduledStart
    var dataExpiration by YoutubeScheduledEvents.dataExpiration

    companion object : LongEntityClass<YoutubeScheduledEvent>(YoutubeScheduledEvents) {
        fun getScheduled(video: YoutubeVideo): YoutubeScheduledEvent? = find {
            YoutubeScheduledEvents.ytVideo eq video.id
        }.firstOrNull()
    }
}

object YoutubeScheduledNotifications : IntIdTable() {
    val ytScheduled = reference("yt_scheduled_event", YoutubeScheduledEvents, ReferenceOption.CASCADE)
    val target = reference("discord_target", TrackedStreams.Targets, ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(ytScheduled, target)
}

class YoutubeScheduledNotification(id: EntityID<Int>) : IntEntity(id) {
    var ytScheduled by YoutubeScheduledEvent referencedOn YoutubeScheduledNotifications.ytScheduled
    var target by TrackedStreams.Target referencedOn YoutubeScheduledNotifications.target

    companion object : IntEntityClass<YoutubeScheduledNotification>(YoutubeScheduledNotifications) {
        operator fun get(scheduled: YoutubeScheduledEvent, target: TrackedStreams.Target) = find {
            YoutubeScheduledNotifications.ytScheduled eq scheduled.id and
                    (YoutubeScheduledNotifications.target eq target.id)
        }

        fun create(scheduled: YoutubeScheduledEvent, target: TrackedStreams.Target) {
            try {
                new {
                    this.target = target
                    this.ytScheduled = scheduled
                }
            } catch(e: Exception) {
                LOG.error("Unable to create YoutubeScheduledNotification in DB: ${e.message}")
                LOG.trace(e.stackTraceString)
            }
        }
    }
}