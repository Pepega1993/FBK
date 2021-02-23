package moe.kabii.discord.event.message

import discord4j.common.util.Snowflake
import discord4j.core.`object`.Embed
import discord4j.core.`object`.entity.channel.GuildMessageChannel
import discord4j.core.`object`.reaction.ReactionEmoji
import discord4j.core.event.domain.message.ReactionAddEvent
import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.hasPermissions
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.MessageInfo
import moe.kabii.discord.conversation.ReactionInfo
import moe.kabii.discord.conversation.ReactionListener
import moe.kabii.discord.event.EventListener
import moe.kabii.discord.translation.Translator
import moe.kabii.discord.util.MagicNumbers
import moe.kabii.discord.util.fbkColor
import moe.kabii.structure.extensions.createJumpLink
import moe.kabii.structure.extensions.orNull
import moe.kabii.structure.extensions.userAddress
import moe.kabii.util.EmojiCharacters
import org.apache.commons.lang3.StringUtils

object TranslationReactionListener : EventListener<ReactionAddEvent>(ReactionAddEvent::class) {

    override suspend fun handle(event: ReactionAddEvent) {
        val user  = event.user.awaitSingle()
        if(user.isBot) return

        val config = event.guildId.map { id -> GuildConfigurations.getOrCreateGuild(id.asLong()) }.orNull()
        if(config?.guildSettings?.reactionTranslations == false) return // functionality can be disabled in guilds

        if(event.emoji.asUnicodeEmoji().orNull()?.raw?.equals(EmojiCharacters.translation) != true) return

        val message = event.message.awaitSingle()
        val service = Translator.getService()

        val channel = event.channel.awaitSingle()
        if(config != null) {
            val member = event.member.orNull() ?: return
            if(!member.hasPermissions(channel as GuildMessageChannel, Permission.SEND_MESSAGES)) return
        }

        // form a flat representation of any contents in this discord message
        // pull contents of embeds in their client display order
        val contents = sequence {
            if (message.embeds.isEmpty()) {
                yield(message.content)
            } else {
                message.embeds
                    .forEach { embed ->
                        yield(embed.title.orNull())
                        yield(embed.description.orNull())
                    }
            }
        }.filterNotNull().joinToString("\n")
        if(contents.isBlank()) return

        val defaultLang = config?.translator?.defaultTargetLanguage?.run(service.supportedLanguages::get) ?: service.defaultLanguage()
        val translation = service.translateText(from = null, to = defaultLang, rawText = contents)
        val jumpLink = message.createJumpLink()
        channel.createEmbed { embed ->
            fbkColor(embed)
            embed.setAuthor("Translation requested by ${user.userAddress()}", jumpLink, user.avatarUrl)

            val text = if(translation.originalLanguage != translation.targetLanguage) StringUtils.abbreviate(translation.translatedText, MagicNumbers.Embed.DESC)
            else "<No translation performed>"
            embed.setDescription(text)

            embed.setFooter("Translator: ${service.fullName}\nTranslation: ${translation.originalLanguage.tag} -> ${translation.targetLanguage.tag}", null)
        }.awaitSingle()

        /*ReactionListener(
            MessageInfo.of(notice),
            listOf(ReactionInfo(EmojiCharacters.cancel, "cancel")),
            user.id.asLong(),
            event.client
        ) { _, _, _ ->
            notice.delete().subscribe()
            true
        }.create(notice, true)*/
    }
}