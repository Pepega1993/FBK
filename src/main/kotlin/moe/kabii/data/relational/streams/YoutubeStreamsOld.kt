package moe.kabii.data.relational.streams

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SizedIterable
import org.jetbrains.exposed.sql.select

// representing a single livestream event on YT - with a video ID
object DBYoutubeStreams {
    object YoutubeStreamsOld : IntIdTable() {
        // only one 'stream' per channel at a time - this can be unique constraint
        val streamChannel = reference("assoc_stream_channel_id", TrackedStreams.StreamChannels, ReferenceOption.CASCADE).uniqueIndex()
        val youtubeVideoId = varchar("youtube_id", 11)
        val lastTitle = text("last_title")
        val lastThumbnail = text("thumbnail_url")
        val lastChannelName = text("last_channel_name")
        val lastAvatar = text("last_user_avatar").nullable()

        val peakViewers = integer("peak_viewers")
        val uptimeTicks = integer("uptime_ticks")
        val averageViewers = integer("average_viewers")
    }

    class YoutubeStream(id: EntityID<Int>) : IntEntity(id) {
        var streamChannel by TrackedStreams.StreamChannel referencedOn YoutubeStreamsOld.streamChannel
        var youtubeVideoId by YoutubeStreamsOld.youtubeVideoId
        var lastTitle by YoutubeStreamsOld.lastTitle
        var lastThumbnail by YoutubeStreamsOld.lastThumbnail
        var lastChannelName by YoutubeStreamsOld.lastChannelName
        var lastAvatar by YoutubeStreamsOld.lastAvatar

        var peakViewers by YoutubeStreamsOld.peakViewers
        var uptimeTicks by YoutubeStreamsOld.uptimeTicks
        var averageViewers by YoutubeStreamsOld.averageViewers

        companion object : IntEntityClass<YoutubeStream>(YoutubeStreamsOld) {

            fun findStream(channelId: String): SizedIterable<YoutubeStream> {
                return YoutubeStream.wrapRows(
                    YoutubeStreamsOld
                        .innerJoin(TrackedStreams.StreamChannels)
                        .select {
                            TrackedStreams.StreamChannels.siteChannelID eq channelId
                        }
                )
            }
        }

        fun currentViewers(current: Int) {
            if(current > peakViewers) peakViewers = current
            averageViewers += (current - averageViewers) / ++uptimeTicks
        }
    }
}
