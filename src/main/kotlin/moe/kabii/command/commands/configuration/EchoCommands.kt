package moe.kabii.command.commands.configuration

import discord4j.rest.util.Permission
import kotlinx.coroutines.reactive.awaitSingle
import moe.kabii.command.Command
import moe.kabii.command.CommandContainer
import moe.kabii.command.verify
import moe.kabii.data.mongodb.GuildConfiguration
import moe.kabii.data.mongodb.guilds.EchoCommand
import moe.kabii.structure.extensions.reply

object EchoCommands : CommandContainer {
    private suspend fun addCommand(config: GuildConfiguration, args: List<String>, noCmd: String, restrict: Boolean = false): String {
        val command = args[0].toLowerCase()
        val response = noCmd.substring(command.length + 1)
        val echo = EchoCommand(command, response, restrict)

        val restricted = if (restrict) "Restricted c" else "C"
        val reply =
                if (config.echoCommands.insertIsUpdated(echo))
                    "${restricted}ommand \"$command\" has been updated."
                else "${restricted}ommand \"$command\" has been added."
        config.save()
        return reply
    }

    object Add : Command("addcommand", "add-command", "command-add", "commandadd", "newcommand", "editcommand", "command-edit", "edit-command") {
        override val wikiPath = "Echo-Commands#creating-a-command-with-addcommand"

        init {
            discord {
                member.verify(Permission.MANAGE_MESSAGES)
                if (args.size >= 2) {
                    val add = addCommand(config, args, noCmd)
                    embed(add).awaitSingle()
                } else {
                    usage("Add or edit a text command. Example:", "addcommand yt My channel: https://youtube.com/mychannel").awaitSingle()
                }
            }
            twitch {
                if (isMod && args.size >= 2 && guild != null) {
                    val add = addCommand(guild, args, noCmd)
                    event.reply(add)
                }
            }
        }
    }

    object Mod : Command("modcommand", "mod-command", "command-mod", "commandmod", "editmodcommand") {
        override val wikiPath: String? = null // undocumented, removal of twitch features pending

        init {
            discord {
                member.verify(Permission.MANAGE_MESSAGES)
                if (args.size >= 2) {
                    val add = addCommand(config, args, noCmd, restrict = true)
                    embed(add).awaitSingle()
                } else {
                    usage("Add a moderator-only command. Example:", "modcommand yt My channel: https://youtube.com/mychannel").awaitSingle()
                }
            }
            twitch {
                if (isMod && args.size >= 2 && guild != null) {
                    val add = addCommand(guild, args, noCmd, restrict = true)
                    event.reply(add)
                }
            }
        }
    }

    object Remove : Command("removecommand", "delcommand", "remcommand", "deletecommand", "remove-command") {
        override val wikiPath = "Echo-Commands#removing-a-command-with-removecommand"

        private suspend fun removeCommand(config: GuildConfiguration, command: String): String {
            val reply =
                    if (config.echoCommands.removeByName(command))
                        "Command \"$command\" removed."
                    else "Command \"$command\" does not exist."
            config.save()
            return reply
        }

        init {
            discord {
                member.verify(Permission.MANAGE_MESSAGES)
                if (args.isNotEmpty()) {
                    val remove = removeCommand(config, args[0])
                    embed(remove).awaitSingle()
                } else {
                    usage("Remove a text command. To see the commands created for **${target.name}**, use the **listcommands** command.", "removecommand <command name>").awaitSingle()
                }
            }
            twitch {
                if (isMod && args.size > 0 && guild != null) {
                    val remove = removeCommand(guild, args[0])
                    event.reply(remove)
                }
            }
        }
    }

    object ListCommands : Command("echocommands", "list-echocommands", "echocommandlist") {
        override val wikiPath = "Echo-Commands#listing-existing-commands-with-echocommands"

        init {
            discord {
                member.verify(Permission.MANAGE_MESSAGES)
                // list existing echo commands
                val commands = config.echoCommands.commands
                if(commands.isEmpty()) {
                    error("There are no [echo commands](https://github.com/kabiiQ/FBK/wiki/Echo-Commands) for **${target.name}**.")
                } else {
                    val commandList = config.echoCommands.commands.joinToString(", ", transform = EchoCommand::command)
                    embed {
                        setTitle("Echo commands for ${target.name}")
                        setDescription(commandList)
                    }
                }.awaitSingle()
            }
        }
    }
}