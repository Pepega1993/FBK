package moe.kabii.discord.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioTrack
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.`object`.reaction.ReactionEmoji
import kotlinx.coroutines.runBlocking
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.command.commands.audio.AudioCommandContainer
import moe.kabii.command.errorColor
import moe.kabii.command.fbkColor
import moe.kabii.discord.util.BotUtil
import moe.kabii.structure.snowflake
import moe.kabii.structure.tryBlock
import moe.kabii.util.EmojiCharacters
import moe.kabii.util.YoutubeUtil
import reactor.kotlin.core.publisher.toFlux

object AudioEventHandler : AudioEventAdapter() {
    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        val data = track.userData as QueueData
        player.volume = data.volume

        // apply this track's audio filters. always reset filter factory to empty if there are no filters applied
        player.setFilterFactory(data.audioFilters.export())

        val originChan = data.discord.getChannelById(data.originChannel)
            .ofType(TextChannel::class.java)

        val guildID = data.audio.guild
        val config = GuildConfigurations.getOrCreateGuild(guildID).musicBot
        // guild option to skip song if queuer has left voice channel.
        if(config.skipIfAbsent) {
            val vc = data.discord.getGuildById(guildID.snowflake)
                .flatMap(BotUtil::getBotVoiceChannel)
                .tryBlock().orNull()
            if(vc != null) {
                val userPresent = vc.voiceStates
                    .filter { state -> state.userId == data.author }
                    .hasElements().tryBlock().orNull()
                if(userPresent == false) {  // abandon this if it errors, but the bot should definitely be in a voice channel if this is reached
                    originChan.flatMap { chan ->
                        chan.createEmbed { embed ->
                            val title = AudioCommandContainer.trackString(track, includeAuthor = false)
                            val author = data.author_name
                            fbkColor(embed)
                            embed.setDescription("Skipping **$title** because **$author** left the channel.")
                        }
                    }.subscribe()
                    track.stop()
                    return
                }
            }
        }
        // post message when song starts playing if it was a user action
        if(!data.apply) {
            originChan
                .flatMap { chan ->
                    val paused = if(player.isPaused) "\n\n**The bot is currently paused.**" else ""
                    chan.createEmbed { embed ->
                        val title = AudioCommandContainer.trackString(track)
                        fbkColor(embed)
                        val now = if(track.position > 0) "Resuming" else "Now playing"
                        embed.setDescription("$now **$title**. $paused")
                        if(track is YoutubeAudioTrack) embed.setThumbnail(YoutubeUtil.thumbnailUrl(track.identifier))
                    }
                }.map { np ->
                    QueueData.BotMessage.NPEmbed(np.channelId, np.id)
                }.subscribe { np -> data.associatedMessages.add(np) }
        } else {
            data.apply = false
            val filters = data.audioFilters.asString()
            originChan.flatMap { chan ->
                chan.createEmbed { embed ->
                    fbkColor(embed)
                    embed.setDescription("Applying filters:\n\n$filters")
                }
            }.subscribe()
        }
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        val data = track.userData as QueueData
        when(endReason) {
            AudioTrackEndReason.FINISHED, AudioTrackEndReason.LOAD_FAILED, AudioTrackEndReason.STOPPED -> {
                if (data.audio.ending) return
                if(endReason == AudioTrackEndReason.STOPPED && data.apply) { // restarting playback to apply audio filters
                    val new = track.makeClone().apply {
                        position = track.position
                        userData = data
                    }
                    player.playTrack(new)
                    return
                }

                // if queue is set to loop, add this track to the end of the queue
                if(data.audio.looping) {
                    val newTrack = track.makeClone()
                    newTrack.userData = data.apply {
                        votes.clear()
                        silent = true
                    }
                    runBlocking { data.audio.forceAdd(newTrack) }
                }

                data.audio.editQueueSync { // need to save queue even if there is no next track
                    if (data.audio.queue.isNotEmpty()) {
                        val next = removeAt(0)
                        player.playTrack(next)
                    }
                }

                // delete old messages per guild settings
                val guildID = data.audio.guild
                val config = GuildConfigurations.getOrCreateGuild(guildID)
                val (commandMsg, otherMsg) = data.associatedMessages.partition { it is QueueData.BotMessage.UserPlayCommand }
                commandMsg.firstOrNull()?.let { msg ->
                    data.discord.getMessageById(msg.channelID, msg.messageID)
                        .flatMap { discordMsg ->
                            // delete or reactor to user command depending on server settings
                            if(msg.enabledFor(config)) {
                                discordMsg.delete("Old user music bot command")
                            } else {
                                discordMsg.addReaction(ReactionEmoji.unicode(EmojiCharacters.checkBox))
                            }
                        }.subscribe()
                }

                // other messages, defer to server settings
                otherMsg.toFlux()
                    .filter { msg -> msg.enabledFor(config) }
                    .flatMap { msg -> data.discord.getMessageById(msg.channelID, msg.messageID) }
                    .flatMap { message -> message.delete("Old music bot command") }
                    .subscribe()

                data.associatedMessages.clear()
            }
            else -> return
        }
    }

    override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
        val data = track.userData as QueueData
        data.discord.getChannelById(data.originChannel)
            .ofType(TextChannel::class.java)
            .flatMap { chan ->
                chan.createEmbed { embed ->
                    val title = AudioCommandContainer.trackString(track)
                    errorColor(embed)
                    embed.setTitle("An error occured during audio playback")
                    embed.addField("Track", title, false)
                    embed.addField("Error", exception.message ?: "broken", false)
                }
            }.subscribe()
    }

    // if a track emits no frames for 10 seconds we will skip it
    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) =
        onTrackEnd(player, track, AudioTrackEndReason.LOAD_FAILED)
}