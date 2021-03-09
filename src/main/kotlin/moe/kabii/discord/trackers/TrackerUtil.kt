package moe.kabii.discord.trackers

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.Guild
import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.NewsChannel
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactive.awaitSingleOrNull
import moe.kabii.LOG
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.GuildSettings
import moe.kabii.discord.util.errorColor
import moe.kabii.structure.extensions.orNull
import moe.kabii.structure.extensions.snowflake
import moe.kabii.structure.extensions.stackTraceString
import kotlin.reflect.KMutableProperty1

object TrackerUtil {
    suspend fun checkAndPublish(message: Message) {
        val guildId = message.guildId.orNull()?.asLong() ?: return
        val settings = GuildConfigurations.getOrCreateGuild(guildId).guildSettings
        checkAndPublish(message, settings)
    }

    suspend fun checkAndPublish(message: Message, settings: GuildSettings?) {
        try {
            if (settings?.publishTrackerMessages ?: return) {
                message.channel
                    .ofType(NewsChannel::class.java)
                    .awaitSingleOrNull() ?: return
                message.publish()
                    .thenReturn(Unit)
                    .awaitSingle()
            }
        } catch(e: Exception) {
            // do not throw exceptions from this method
            LOG.trace("Error publishing Tracker message: ${e.stackTraceString}")
        }
    }

    suspend fun permissionDenied(discord: GatewayDiscordClient, guildId: Long?, channelId: Long, guildDelete: KMutableProperty1<FeatureChannel, Boolean>, pmDelete: () -> Unit) {
        if(guildId != null) {
            // disable feature (keeping targets/config alive for future)
            val config = GuildConfigurations.getOrCreateGuild(guildId)
            val features = config.options.featureChannels[channelId] ?: return
            guildDelete.set(features, false)
            config.save()

            val feature = guildDelete.name.replace("Channel", "", ignoreCase = true)
            try {

                if(GuildConfigurations.guildConfigurations[guildId] == null) return // removed from guild
                discord.getGuildById(guildId.snowflake)
                    .flatMap(Guild::getOwner)
                    .flatMap(Member::getPrivateChannel)
                    .flatMap { pm ->
                        pm.createEmbed { spec ->
                            errorColor(spec)
                            spec.setDescription("I tried to send a **$feature** tracker message but I am missing permissions to send embed messages in <#$channelId>. The **$feature** feature has been automatically disabled.\nOnce permissions are corrected, you can run **${config.prefix}feature $feature enable** in <#$channelId> to re-enable this tracker.")
                        }
                    }.awaitSingle()

            } catch(e: Exception) {
                LOG.warn("Unable to send notification to $guildId owner regarding feature disabled. Disabling feature $feature silently: ${e.message}")
                LOG.debug(e.stackTraceString)
            }

        } else {
            // delete target, we do not keep configs for dms
            try {
                pmDelete()
            } catch(e: Exception) {
                LOG.error("SEVERE: SQL error in #permissionDenied: ${e.message}")
                LOG.error(e.stackTraceString)
            }
        }
    }


    suspend fun permissionDenied(channel: MessageChannel, guildDelete: KMutableProperty1<FeatureChannel, Boolean>, pmDelete: () -> Unit) {
        val guildChan = channel as? GuildMessageChannel
        permissionDenied(channel.client, guildChan?.guildId?.asLong(), channel.id.asLong(), guildDelete, pmDelete)
    }
}