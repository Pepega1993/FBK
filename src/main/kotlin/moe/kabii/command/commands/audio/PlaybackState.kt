package moe.kabii.command.commands.audio

import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.hasPermissions
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.discord.audio.AudioManager
import moe.kabii.discord.audio.QueueData

object PlaybackState : AudioCommandContainer {
    object PausePlayback : Command("pause", "pausequeue") {
        override val wikiPath = "Music-Player#queue-manipulation"

        init {
            discord {
                channelFeatureVerify(FeatureChannel::musicChannel)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                audio.player.isPaused = true
                embed("Audio playback is now paused. You can resume playback with the **resume** command.").awaitSingle()
            }
        }
    }

    object ResumePlayback : Command("resume", "unpause", "resumequeue", "unpausequeue") {
        override val wikiPath = "Music-Player#queue-manipulation"

        init {
            discord {
                channelFeatureVerify(FeatureChannel::musicChannel)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                audio.player.volume
                audio.player.isPaused = false
                embed("Audio playback resumed.").awaitSingle()
            }
        }
    }

    object PlaybackVolume : Command("volume", "setvolume", "vol") {
        override val wikiPath = "Music-Player#audio-manipulationfilters"

        init {
            discord {
                channelFeatureVerify(FeatureChannel::musicChannel)
                val audio = AudioManager.getGuildAudio(target.id.asLong())
                val track = audio.player.playingTrack
                if(track == null) {
                    val default = config.musicBot.startingVolume
                    error("There is no track currently playing. New tracks will start at **$default%** volume.").awaitSingle()
                    return@discord
                }
                if(args.isEmpty()) {
                    usage("The current playback volume is **${audio.player.volume}%**.", "$alias <new volume>").awaitSingle()
                    return@discord
                }
                if(!canFSkip(this, track)) {
                    error("You must be the DJ (track requester) or be a channel moderator to adjust the playback volume for this track.").awaitSingle()
                    return@discord
                }
                val data = track.userData as QueueData
                val targetVolume = args[0].removeSuffix("%").toIntOrNull()
                val maximum = config.musicBot.volumeLimit
                when {
                    targetVolume == null || targetVolume < 1 -> {
                        error("**${args[0]}** is not a valid volume value.").awaitSingle()
                        return@discord
                    }
                    targetVolume > maximum -> {
                        val tip = if(member.hasPermissions(Permission.MANAGE_CHANNELS)) " This can be overridden with the **musicbot** configure command." else ""
                        error("The volume limit is set at **$maximum**.$tip").awaitSingle()
                        return@discord
                    }
                }
                val distort = if(targetVolume!! > 100) " Setting the volume over 100% will begin to cause audio distortion. " else ""
                val oldVolume = audio.player.volume
                audio.player.volume = targetVolume
                data.volume = targetVolume
                config.save()
                embed("Changing the playback volume in **${target.name}**: $oldVolume -> **$targetVolume**.$distort").awaitSingle()
            }
        }
    }
}