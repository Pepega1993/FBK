package moe.kabii.discord.event.guild

import discord4j.core.event.domain.channel.TextChannelDeleteEvent
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.relational.discord.DiscordObjects
import moe.kabii.discord.event.EventListener
import org.jetbrains.exposed.sql.transactions.transaction

object ChannelDeletionListener : EventListener<TextChannelDeleteEvent>(TextChannelDeleteEvent::class) {
    override suspend fun handle(event: TextChannelDeleteEvent) {
        val chan = event.channel.id.asLong()
        val config = GuildConfigurations.guildConfigurations[event.channel.guildId.asLong()] ?: return

        // remove channel from config if feature channel
        val features = config.options.featureChannels
        if(features.containsKey(chan)) {
            features.remove(chan)
            config.save()
        }

        // delete message logs (if this server has an edited messages log)
        if(config.logChannels().any()) {
            transaction {
                DiscordObjects.Channel
                    .find { DiscordObjects.Channels.channelID eq chan }
                    .singleOrNull()?.delete()
            }
        }
    }
}