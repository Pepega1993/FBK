package moe.kabii.discord.trackers.videos.twitcasting.webhook

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import moe.kabii.LOG
import moe.kabii.MOSHI
import moe.kabii.data.flat.Keys
import moe.kabii.data.relational.streams.TrackedStreams
import moe.kabii.discord.trackers.videos.twitcasting.json.TwitcastingMovieResponse
import moe.kabii.discord.trackers.videos.twitcasting.watcher.TwitcastChecker
import moe.kabii.rusty.Err
import moe.kabii.rusty.Ok
import moe.kabii.util.extensions.fromJsonSafe
import moe.kabii.util.extensions.propagateTransaction

class TwitcastWebhookServer(val checker: TwitcastChecker) {

    private val port = Keys.config[Keys.Twitcasting.webhookPort]
    private val signature = Keys.config[Keys.Twitcasting.signature]

    private val incomingAdapter = MOSHI.adapter(TwitcastingMovieResponse::class.java)
    // twitcasting special blend of websub
    val server = embeddedServer(Netty, port = this.port) {
        routing {

            trace { trace ->
                LOG.debug(trace.buildText())
            }

            post {
                call.response.status(HttpStatusCode.OK)

                val body = call.receiveStream().bufferedReader().readText()
                LOG.info("POST:$port - to ${call.request.origin.uri} - from ${call.request.origin.remoteHost}")
                LOG.debug("POST :: $body")

                val movie = when(val json = incomingAdapter.fromJsonSafe(body)) {
                    is Ok -> json.value
                    is Err -> {
                        LOG.debug("Other object received in POST: $body")
                        return@post
                    }
                }

                if(movie?.signature != signature) {
                    LOG.warn("TwitCasting POST with invalid signature: $movie")
                } else LOG.debug("TwitCasting POST passed validation: ${movie.signature}")

                propagateTransaction {
                    val channel = TrackedStreams.StreamChannel.getChannel(TrackedStreams.DBSite.TWITCASTING, movie.broadcaster.userId)
                    if(channel != null) {
                        checker.getActiveTargets(channel)?.run { checker.checkMovie(channel, movie, this) }
                    }
                }
            }
        }
    }
}