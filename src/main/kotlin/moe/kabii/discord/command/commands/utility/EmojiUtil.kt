package moe.kabii.discord.command.commands.utility

import discord4j.core.`object`.reaction.ReactionEmoji
import io.ktor.util.toCharArray
import moe.kabii.discord.command.Command
import moe.kabii.discord.command.CommandContainer
import moe.kabii.util.EmojiCharacters
import java.lang.StringBuilder

object EmojiUtil : CommandContainer {
    object ToRegionalIndicator : Command("emojify", "regional", "letters", "emojiletters", "emojiletter", "regionalindicator", "emojitext", "textemoji", "textemojis", "textmoji", "textmojis") {

        private val regionalIndicators = arrayOf('\uDDE6', '\uDDE7', '\uDDE8', '\uDDE9', '\uDDEA', '\uDDEB', '\uDDEC', '\uDDED', '\uDDEE', '\uDDEF', '\uDDF0', '\uDDF1', '\uDDF2', '\uDDF3', '\uDDF4', '\uDDF5', '\uDDF6', '\uDDF7', '\uDDF8', '\uDDF9', '\uDDFA', '\uDDFB', '\uDDFC', '\uDDFD', '\uDDFE', '\uDDFF')
        private val emoji = Regex("<:[a-zA-Z0-9_]+:[0-9]{17,18}>")

        init {
            discord {
                // convert all possible chars into regional indicator emoji
                if(noCmd.isEmpty()) {
                    usage("No text provided to convert.", "emojify <text>").block()
                    return@discord
                }
                var previous = false
                val converted = noCmd.map {char ->
                    val lower = char.toLowerCase()
                    val spacer = if(previous) {
                        previous = false// reset this no matter what, we only need to apply spacer if two regional emoji characters are back to back
                        " "
                    } else ""
                    when (lower) {
                        in 'a'..'z' -> {
                            val regionalChar = EmojiCharacters.regionalChar
                            previous = true
                            val letter = regionalIndicators[lower - 'a']
                            "$spacer$regionalChar$letter"
                        }
                        ' ' -> "   "
                        else -> char.toString()
                    }
                }.joinToString("")
                chan.createMessage(converted).block()
            }
        }
    }
}