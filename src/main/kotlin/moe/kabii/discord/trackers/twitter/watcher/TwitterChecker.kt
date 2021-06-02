package moe.kabii.discord.trackers.twitter.watcher

import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.channel.MessageChannel
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.rest.http.client.ClientException
import discord4j.rest.util.Color
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.time.delay
import moe.kabii.LOG
import moe.kabii.data.TwitterFeedCache
import moe.kabii.data.mongodb.GuildConfigurations
import moe.kabii.data.mongodb.guilds.FeatureChannel
import moe.kabii.data.mongodb.guilds.TwitterSettings
import moe.kabii.data.relational.twitter.TwitterFeed
import moe.kabii.data.relational.twitter.TwitterTarget
import moe.kabii.discord.trackers.ServiceRequestCooldownSpec
import moe.kabii.discord.trackers.TrackerUtil
import moe.kabii.discord.trackers.twitter.TwitterDateTimeUpdateException
import moe.kabii.discord.trackers.twitter.TwitterParser
import moe.kabii.discord.trackers.twitter.TwitterRateLimitReachedException
import moe.kabii.discord.trackers.twitter.json.TwitterMediaType
import moe.kabii.discord.trackers.twitter.json.TwitterTweet
import moe.kabii.discord.trackers.twitter.json.TwitterUser
import moe.kabii.discord.translation.Translator
import moe.kabii.discord.util.fbkColor
import moe.kabii.net.NettyFileServer
import moe.kabii.util.constants.MagicNumbers
import moe.kabii.util.extensions.WithinExposedContext
import moe.kabii.util.extensions.applicationLoop
import moe.kabii.util.extensions.snowflake
import moe.kabii.util.extensions.stackTraceString
import org.apache.commons.lang3.StringUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Duration
import java.time.Instant
import kotlin.math.max

class TwitterChecker(val discord: GatewayDiscordClient, val cooldowns: ServiceRequestCooldownSpec) : Runnable {

    override fun run() {
        applicationLoop {
            val start = Instant.now()

            newSuspendedTransaction {
                try {
                    // get all tracked twitter feeds
                    val feeds = TwitterFeed.all()

                    // feeds who are completely inactive and since_id has fallen out of the valid range
                    val requireUpdate = mutableListOf<TwitterFeed>()
                    var maxId = 0L

                    var first = true
                    feeds.forEach { feed ->
                        if(!first) {
                            delay(Duration.ofMillis(cooldowns.callDelay))
                        } else first = false

                        val targets = getActiveTargets(feed)?.ifEmpty { null }
                            ?: return@forEach // feed untrack entirely or no target channels are currently enabled

                        TwitterFeedCache.cache.getOrPut(feed.userId) { TwitterFeedCache.FeedCacheState(feed.lastPulledTweet ?: 0) }

                        // determine if any targets want RT or quote tweets
                        var pullRetweets = false
                        var pullQuotes = false

                        targets.forEach { target ->
                            val features = GuildConfigurations.findFeatures(target)
                            val twitter = features?.twitterSettings ?: TwitterSettings()

                            if(twitter.displayRetweet) pullRetweets = true
                            if(twitter.displayQuote) pullQuotes = true
                        }

                        val limits = TwitterParser.TwitterQueryLimits(
                            sinceId = feed.lastPulledTweet,
                            includeRT = pullRetweets,
                            includeQuote = pullQuotes
                        )
                        val recent = try {
                            TwitterParser.getRecentTweets(feed.userId, limits)
                        } catch(sinceId: TwitterDateTimeUpdateException) {
                            LOG.info("Twitter feed '${feed.userId}' is far out of date and the Tweets since_id query was rejected")
                            requireUpdate.add(feed)
                            null
                        } catch(rate: TwitterRateLimitReachedException) {
                            val reset = rate.reset
                            LOG.warn("Twitter rate limit reached: sleeping ${reset.seconds} seconds")
                            delay(reset)
                            null
                        } catch(e: Exception) {
                            LOG.warn("TwitterChecker: Error in Twitter call: ${e.message}")
                            LOG.debug(e.stackTraceString)
                            delay(Duration.ofMillis(100L))
                            null
                        }
                        recent ?: return@forEach
                        val (user, tweets) = recent
                        feed.lastKnownUsername = user.username

                        val latest = tweets.maxOf { tweet ->
                            // if tweet is after last posted tweet and within 2 hours (arbitrary - to prevent spam when initially tracking) - send discord notifs
                            val age = Duration.between(tweet.createdAt, Instant.now())

                            // if already handled or too old, skip, but do not pull tweet ID again
                            if(feed.lastPulledTweet ?: 0 >= tweet.id || age > Duration.ofHours(2)) return@maxOf tweet.id

                            notifyTweet(user, tweet, targets)
                        }
                        if(latest > feed.lastPulledTweet ?: 0L) {
                            newSuspendedTransaction {
                                feed.lastPulledTweet = latest
                            }
                        }
                        if(latest > maxId) maxId = latest
                    }

                    requireUpdate.forEach { feed ->
                        newSuspendedTransaction {
                            feed.lastPulledTweet = maxId
                        }
                    }
                } catch(e: Exception) {
                    LOG.info("Uncaught exception in ${Thread.currentThread().name} :: ${e.message}")
                    LOG.debug(e.stackTraceString)
                }
            }
            val runDuration = Duration.between(start, Instant.now())
            val delay = cooldowns.minimumRepeatTime - runDuration.toMillis()
            delay(Duration.ofMillis(max(delay, 0L)))
        }
    }

    @WithinExposedContext
    suspend fun notifyTweet(user: TwitterUser, tweet: TwitterTweet, targets: List<TwitterTarget>): Long {
        // send discord notifs - check if any channels request
        targets.forEach target@{ target ->
            try {
                // post a notif to this target
                val channel = discord.getChannelById(target.discordChannel.channelID.snowflake)
                    .ofType(MessageChannel::class.java)
                    .awaitSingle()

                val features = GuildConfigurations.findFeatures(target)
                val twitter = features?.twitterSettings ?: TwitterSettings()

                if(!tweet.notifyOption.get(twitter)) return@target

                val referenceUser = tweet.references.firstOrNull()?.author
                val action = when {
                    tweet.retweet -> "retweeted \uD83D\uDD01"
                    tweet.reply -> "replied to a Tweet from **@${referenceUser?.username}** \uD83D\uDCAC"
                    tweet.quote -> "quoted a Tweet from **@${referenceUser?.username}** \uD83D\uDDE8"
                    else -> "posted a new Tweet"
                }

                if(tweet.sensitive == true && target.discordChannel.guild != null) {
                    // filter potentially nsfw tweets in guilds
                    val guildChan = channel as? TextChannel // will fail for news channels as they can not be marked nsfw
                    if(guildChan?.isNsfw != true) {
                        channel.createEmbed { embed ->
                            fbkColor(embed)
                            embed.setDescription("[**@${user.username}**](${user.url}) $action which may contain sensitive content.")
                        }.awaitSingle()
                        return@target
                    }
                }

                val translation = if(twitter.autoTranslate && tweet.text.isNotBlank()) {
                    try {
                        val baseService = Translator.defaultService
                        val defaultLang = GuildConfigurations
                            .getOrCreateGuild(target.discordChannel.guild!!.guildID)
                            .translator.defaultTargetLanguage
                            .run(baseService.supportedLanguages::get) ?: baseService.defaultLanguage()
                        val translator = Translator.getService(tweet.text, defaultLang.tag)
                        val translation = translator.translate(from = null, to = defaultLang, text = tweet.text)
                        if(translation.originalLanguage != translation.targetLanguage && translation.translatedText.isNotBlank()) translation
                        else null
                    } catch(e: Exception) {
                        LOG.warn("Tweet translation failed: ${e.message} :: ${e.stackTraceString}")
                        null
                    }
                } else null

                val notif = channel.createMessage { spec ->
                    // todo channel setting for custom message ?
                    spec.setContent("**@${user.username}** $action: https://twitter.com/${user.username}/status/${tweet.id}")

                    spec.setEmbed { embed ->
                        val color = if(user.id == 1255017971363090432L) 16703383 else 1942002
                        embed.setColor(Color.of(color))
                        val author = (if(tweet.retweet) referenceUser else user) ?: user
                        embed.setAuthor("${author.name} (@${author.username})", author.url, author.profileImage)

                        embed.setDescription(tweet.text)

                        val tlDetail = if(translation != null) {
                            embed.addField("**Tweet Translation**", StringUtils.abbreviate(translation.translatedText, MagicNumbers.Embed.FIELD.VALUE), false)
                            "Translator: ${translation.service.fullName}, ${translation.originalLanguage.tag} -> ${translation.targetLanguage.tag}\n"
                        } else ""

                        val attachment = tweet.attachments.firstOrNull()
                        val size = tweet.attachments.size
                        val isVid = attachment?.type == TwitterMediaType.VID
                        val attachInfo = when {
                            isVid -> "(Open on Twitter to view video)\n"
                            size > 1 -> "(Open on Twitter to view $size images)\n"
                            else -> ""
                        }

                        val thumbnail = if(attachment != null) {
                            if((size > 1 || isVid)  && attachment.url != null) {
                                TwitterThumbnailGenerator.attachInfoTag(attachment.url, spec, size, isVid)
                            } else attachment.url
                        } else tweet.entities?.urls?.firstOrNull()?.images?.firstOrNull()?.url // use image from embedded twitter link, if it exists (discord uses these in the vanilla Twitter embed)
                        thumbnail?.run(embed::setImage)

                        embed.setFooter("$attachInfo${tlDetail}Twitter", NettyFileServer.twitterLogo)
                        embed.setTimestamp(tweet.createdAt)
                    }
                }.awaitSingle()

                TrackerUtil.checkAndPublish(notif)
            } catch (e: Exception) {
                if (e is ClientException && e.status.code() == 403) {
                    TrackerUtil.permissionDenied(discord, target.discordChannel.guild?.guildID, target.discordChannel.channelID, FeatureChannel::twitterTargetChannel, target::delete)
                    LOG.warn("Unable to send Tweet to channel '${target.discordChannel.channelID}'. Disabling feature in channel. TwitterChecker.java")
                } else {
                    LOG.warn("Error sending Tweet to channel: ${e.message}")
                    LOG.debug(e.stackTraceString)
                }
            }
        }
        TwitterFeedCache.cache[user.id]?.seenTweets?.add(tweet.id)
        return tweet.id // return tweet id for 'max' calculation to find the newest tweet that was returned
    }

    @WithinExposedContext
    private suspend fun getActiveTargets(feed: TwitterFeed): List<TwitterTarget>? {
        val existingTargets = feed.targets.toList()
            .filter { target ->
                // untrack target if discord channel is deleted
                if (target.discordChannel.guild != null) {
                    try {
                        discord.getChannelById(target.discordChannel.channelID.snowflake).awaitSingle()
                    } catch (e: Exception) {
                        if (e is ClientException && e.status.code() == 404) {
                            LOG.info("Untracking Twitter feed '${feed.userId}' in ${target.discordChannel.channelID} as the channel seems to be deleted.")
                            target.delete()
                        }
                        return@filter false
                    }
                }
                true
            }
        return if (existingTargets.isNotEmpty()) {
            existingTargets.filter { target ->
                // ignore, but do not untrack targets with feature disabled
                val guildId = target.discordChannel.guild?.guildID ?: return@filter true
                GuildConfigurations.findFeatures(target)?.twitterTargetChannel == true
            }
        } else {
            feed.delete()
            LOG.info("Untracking Twitter feed ${feed.userId} as it has no targets.")
            null
        }
    }
}