package moe.kabii.discord.event.user

import discord4j.core.`object`.entity.Member
import discord4j.core.`object`.entity.Role
import discord4j.core.`object`.entity.User
import moe.kabii.structure.tryBlock
import org.apache.commons.lang3.time.DurationFormatUtils
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant

enum class UserEvent { JOIN, PART }

class UserEventFormatter(val user: User) {
    fun paramMatcher(origin: String, param: String) = """&$param(?:="([^"]*)")?""".toRegex().find(origin)

    fun commonFields(unformatted: String, member: Member?): String {
        var formatted = unformatted.replace("&name", user.username)
            .replace("&mention", user.mention)
            .replace("&id", user.id.asString())
            .replace("&discrim", user.discriminator)

        val guild = member?.run { guild.tryBlock().orNull() }
        val membersMatcher = paramMatcher(formatted, "members")
        if(membersMatcher != null) {
            guild?.memberCount?.ifPresent { members ->
                formatted = formatted.replace(membersMatcher.value, members.toString())
            }
        }
        return formatted
    }

    private fun plural(quantity: Long) = if(quantity != 1L) "s" else ""

    fun formatJoin(unformatted: String, invite: String?): String {
        var format = commonFields(unformatted, member = null)
        // group 1: if present then we have custom unknown invite text
        // &invite
        // &invite="Unknown Invite"
        val matchInvite = paramMatcher(format, "invite")
        if(matchInvite != null) {
            val invite = invite ?: matchInvite.groups[1]?.value ?: "Unknown"
            format = format.replace(matchInvite.value, "**$invite**")
        }
        val matchNewAcc = paramMatcher(format, "new")
        if(matchNewAcc != null) {
            val creation = user.id.timestamp
            val daysParam = matchNewAcc.groups[1]?.value?.toIntOrNull()
            val daysWarning = daysParam ?: 7 // default warning: accounts made in last week
            val existence = Duration.between(creation, Instant.now())
            format = if(existence.toDays() < daysWarning) {
                val age = if(existence.toDays() < 1) {
                    if(existence.toHours() < 1) {
                        val minutes = existence.toMinutes()
                        "$minutes minute${plural(minutes)}"
                    } else {
                        val hours = existence.toHours()
                        "$hours hour${plural(hours)}"
                    }
                } else {
                    val days = existence.toDays()
                    "$days day${plural(days)}"
                }
                format.replace(matchNewAcc.value, " (Account created $age ago)")
            } else format.replace(matchNewAcc.value, "")
        }
        return format
    }

    fun formatPart(unformatted: String, member: Member?): String {
        var format = commonFields(unformatted, member)

        val roles by lazy {
            member?.run {
                roles.collectList().block()
            }
        }

        val matchRoleList = paramMatcher(format, "roles")
        if (matchRoleList != null) {
            format = if (roles != null) { // this was a part from while the bot/api was offline, we can't provide any of this info
                format.replace(matchRoleList.value, roles!!.joinToString(", ", transform = Role::getName))
            } else {
                format.replace(matchRoleList.value, "")
            }
        }
        // *joinDate -> defaults
        // *joinDate="dd MM yyyy HH:mm:ss"
        val matchJoinDate = paramMatcher(format, "joinDate")
        if (matchJoinDate != null) {
            format = if (member != null) {
                val dateFormatParam = matchJoinDate.groups[1]
                val joinDateFormat = if (dateFormatParam != null) {
                    SimpleDateFormat(dateFormatParam.value)
                } else {
                    // default format
                    SimpleDateFormat("dd MMMM yyyy'@'HH:mm")
                }
                format.replace(matchJoinDate.value, joinDateFormat.format(member.joinTime))
            } else {
                format.replace(matchJoinDate.value, "")
            }
        }

        val matchDuration = paramMatcher(format, "joinDuration")
        if (matchDuration != null) {
            format = if (member != null) {
                val durationFormatParam = matchDuration.groups[1]
                val duration = Duration.between(member.joinTime, Instant.now())
                val durationFormat = durationFormatParam?.value ?: "dddd'd'HH'h'"
                val durationValue = DurationFormatUtils.formatDuration(duration.toMillis(), durationFormat, false)
                format.replace(matchDuration.value, durationValue)
            } else {
                format.replace(matchDuration.value, "")
            }
        }
        return format
    }
}