package moe.kabii.data.relational

import moe.kabii.data.flat.Keys
import moe.kabii.data.relational.anime.TrackedMediaLists
import moe.kabii.data.relational.discord.DiscordObjects
import moe.kabii.data.relational.discord.MessageHistory
import moe.kabii.data.relational.discord.Reminders
import moe.kabii.data.relational.discord.UserLog
import moe.kabii.data.relational.ps2.PS2Internal
import moe.kabii.data.relational.ps2.PS2Tracks
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.data.relational.streams.WebSubSubscriptions
import moe.kabii.data.relational.streams.twitcasting.Twitcasts
import moe.kabii.data.relational.streams.twitch.DBTwitchStreams
import moe.kabii.data.relational.streams.twitch.TwitchEventSubscriptions
import moe.kabii.data.relational.streams.youtube.*
import moe.kabii.data.relational.streams.youtube.ytchat.LinkedYoutubeAccounts
import moe.kabii.data.relational.streams.youtube.ytchat.MembershipConfigurations
import moe.kabii.data.relational.streams.youtube.ytchat.YoutubeMembers
import moe.kabii.data.relational.twitter.TwitterFeeds
import moe.kabii.data.relational.twitter.TwitterTargets
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

internal object PostgresConnection {
    val postgres = Database.connect(
        Keys.config[Keys.Postgres.connectionString],
        driver = "org.postgresql.Driver"
    ).apply { useNestedTransactions = true }

    init {
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                DiscordObjects.Users,
                DiscordObjects.Channels,
                TrackedMediaLists.MediaLists,
                TrackedMediaLists.ListTargets,
                MessageHistory.Messages,
                Reminders,
                TrackedStreams.StreamChannels,
                TrackedStreams.Targets,
                TrackedStreams.Mentions,
                TwitchEventSubscriptions,
                DBTwitchStreams.TwitchStreams,
                DBTwitchStreams.Notifications,
                WebSubSubscriptions,
                YoutubeVideos,
                YoutubeScheduledEvents,
                YoutubeScheduledNotifications,
                YoutubeLiveEvents,
                YoutubeNotifications,
                YoutubeVideoTracks,
                Twitcasts.Movies,
                Twitcasts.TwitNotifs,
                UserLog.GuildRelationships,
                TwitterFeeds,
                TwitterTargets,
                PS2Tracks.TrackTargets,
                PS2Internal.Characters,
                PS2Internal.Outfits,
                YoutubeMembers,
                LinkedYoutubeAccounts,
                MembershipConfigurations
            )
        }
    }
}