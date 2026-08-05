package com.webanywhere.streamer.server

import android.content.Context
import android.util.Log
import com.webanywhere.streamer.Profile
import com.webanywhere.streamer.StreamConfig
import com.webanywhere.streamer.StreamHub
import com.webanywhere.streamer.stream.SegmentRing
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * The HTTP server that is both the data plane and the user interface.
 *
 * Everything the car needs comes from this one origin on this one port: the
 * player page, the capability probe, and the media itself. Nothing is installed
 * on the head unit and nothing is published to the internet — the driver types
 * an IP and gets a working player.
 *
 * Route map mirrors PLAN.md.
 */
class StreamServer(
    private val context: Context,
    private val config: StreamConfig,
) {

    private var server: EmbeddedServer<*, *>? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assetCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * fMP4 clients make stateless segment requests, so there is no connection
     * whose close we can observe. Instead each request refreshes a short lease;
     * when every lease expires, the encoder is told nobody is watching.
     */
    private val fmp4Leases = LeaseTracker(Profile.FMP4, LEASE_TTL_MS, scope)

    fun start() {
        check(server == null) { "already started" }

        server = embeddedServer(CIO, port = config.port, host = "0.0.0.0") {
            routing {
                get("/") { call.respondAsset("player.html", ContentType.Text.Html) }
                get("/probe") { call.respondAsset("probe.html", ContentType.Text.Html) }
                get("/favicon.ico") { call.respondText("", status = HttpStatusCode.NoContent) }

                post("/report") { handleReport() }
                get("/status") { call.respondJson(statusJson()) }

                // ---- Profile A: fMP4 for MSE, the main route ---------------
                get("/v/live.json") { serveLiveJson(StreamHub.video, Profile.FMP4) }
                get("/v/init.mp4") { serveInit(StreamHub.video, Profile.FMP4) }
                get("/v/seg/{n}") { serveSegment(StreamHub.video, Profile.FMP4) }

                get("/a/live.json") { serveLiveJson(StreamHub.audio, null) }
                get("/a/init.mp4") { serveInit(StreamHub.audio, null) }
                get("/a/seg/{n}") { serveSegment(StreamHub.audio, null) }

                // ---- Profile A': HLS over the very same segments -----------
                get("/live.m3u8") { serveHlsPlaylist() }

                // ---- Profile B: MJPEG, the one that works everywhere -------
                get("/mjpeg") { serveMjpeg() }
            }
        }.also { it.start(wait = false) }

        Log.i(TAG, "server listening on port ${config.port}")
    }

    fun stop() {
        fmp4Leases.releaseAll()
        scope.cancel()
        server?.stop(500, 1_500)
        server = null
    }

    // ------------------------------------------------------------- handlers

    private suspend fun RoutingContext.serveLiveJson(track: StreamHub.Track, profile: Profile?) {
        profile?.let { fmp4Leases.touch(call.remoteHost(), it, call.userAgent()) }
        awaitReady(track)

        val ring = track.ring
        val config = StreamHub.config
        call.respondJson(
            buildString {
                append('{')
                append("\"ready\":").append(track.ready)
                append(",\"codec\":").append(track.codec.jsonOrNull())
                append(",\"latest\":").append(ring.latest.value)
                append(",\"oldest\":").append(ring.oldest)
                // Where a fresh client must start: far enough behind the live
                // edge that its buffer is full on the first frame, and on a key
                // frame, since segments are cut on a duration target now and
                // most of them begin mid-GOP.
                append(",\"joinAt\":").append(joinPoint(ring, config.bufferUs))
                // How much cushion the player should hold. Sent by the server so
                // the setting lives in one place — the phone — instead of being
                // hardcoded in a page nobody can edit from inside a car.
                // The effective value, not the requested one: a buffer smaller
                // than a segment cannot be held, and sending it would have the
                // player chasing a target it can never reach.
                append(",\"bufferMs\":").append(config.effectiveBufferMs)
                // Lets the client turn its buffer into a segment count, so it
                // can tell "deliberately behind" from "falling behind".
                append(",\"segmentMs\":").append(config.latency.segmentMs)
                append(",\"generation\":").append(StreamHub.generation.value)
                append(",\"segments\":").append(ring.size)
                append('}')
            },
        )
    }

    private fun joinPoint(ring: SegmentRing, bufferUs: Long): Long {
        val join = ring.joinPointFor(bufferUs)
        if (join >= 0) return join
        return if (ring.latestSync >= 0) ring.latestSync else ring.latest.value
    }

    private suspend fun RoutingContext.serveInit(track: StreamHub.Track, profile: Profile?) {
        profile?.let { fmp4Leases.touch(call.remoteHost(), it, call.userAgent()) }
        awaitReady(track)

        val init = track.initSegment
        if (init == null) {
            call.respondText("encoder not ready", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        call.noStore()
        call.respondBytes(init, MP4)
    }

    private suspend fun RoutingContext.serveSegment(track: StreamHub.Track, profile: Profile?) {
        profile?.let { fmp4Leases.touch(call.remoteHost(), it, call.userAgent()) }

        val sequence = call.parameters["n"]?.removeSuffix(".m4s")?.toLongOrNull()
        if (sequence == null) {
            call.respondText("bad segment number", status = HttpStatusCode.BadRequest)
            return
        }

        when (val result = track.ring.await(sequence, SEGMENT_WAIT_MS)) {
            is SegmentRing.Result.Ready -> {
                // Segments are immutable once produced, so caching is safe and
                // spares the link a retransmit if the client re-requests one.
                call.response.header(HttpHeaders.CacheControl, "public, max-age=300")
                // How far behind the client is, at the cost of no extra round
                // trip. Without this it would have to poll live.json to find
                // out, and by the time it knew it would be further behind.
                call.response.header(LIVE_LATEST, track.ring.latest.value.toString())
                call.response.header(
                    LIVE_JOIN,
                    joinPoint(track.ring, StreamHub.config.bufferUs).toString(),
                )
                call.respondBytes(result.segment.data, MP4)
            }

            // 410 tells the client it fell too far behind to catch up. Its only
            // sane move is to jump to the live edge, not keep chasing a window
            // that is receding faster than it downloads.
            SegmentRing.Result.Gone ->
                call.respondText("segment evicted", status = HttpStatusCode.Gone)

            SegmentRing.Result.Timeout ->
                call.respondText("not produced yet", status = HttpStatusCode.NotFound)
        }
    }

    private suspend fun RoutingContext.serveHlsPlaylist() {
        fmp4Leases.touch(call.remoteHost(), Profile.FMP4, call.userAgent())
        awaitReady(StreamHub.video)

        // A playlist has to begin on a key frame, or the first thing the player
        // decodes is a fragment with nothing to reference.
        val segments = StreamHub.video.ring.snapshot()
            .dropWhile { !it.isSync }
        if (segments.isEmpty()) {
            call.respondText("no segments yet", status = HttpStatusCode.ServiceUnavailable)
            return
        }

        val targetSeconds = ceil(segments.maxOf { it.durationUs } / 1_000_000.0).toInt()
        val playlist = buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(targetSeconds.coerceAtLeast(1)).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:").append(segments.first().sequence).append('\n')
            append("#EXT-X-MAP:URI=\"/v/init.mp4\"\n")
            for (segment in segments) {
                // Locale.ROOT is not optional: a comma decimal separator makes
                // the playlist unparseable, and this app runs in es-CO.
                append("#EXTINF:")
                append(String.format(Locale.ROOT, "%.3f", segment.durationUs / 1_000_000.0))
                append(",\n")
                append("/v/seg/").append(segment.sequence).append(".m4s\n")
            }
        }
        call.noStore()
        call.respondText(playlist, ContentType("application", "vnd.apple.mpegurl"))
    }

    private suspend fun RoutingContext.serveMjpeg() {
        val host = call.remoteHost()
        val engine = StreamHub.engine
        engine?.acquire(Profile.MJPEG)
        StreamHub.clientConnected(
            StreamHub.ClientInfo(
                host = host,
                profile = "MJPEG",
                userAgent = call.userAgent(),
                connectedAtMs = System.currentTimeMillis(),
            ),
        )

        val subscriber = StreamHub.mjpeg.subscribe()
        try {
            call.noStore()
            call.respondBytesWriter(
                contentType = ContentType("multipart", "x-mixed-replace")
                    .withParameter("boundary", BOUNDARY),
            ) {
                while (!isClosedForWrite) {
                    val frame = subscriber.next()
                    val header = (
                        "--$BOUNDARY\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frame.size}\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)

                    writeByteArray(header)
                    writeByteArray(frame)
                    writeByteArray(CRLF)
                    flush()
                }
            }
        } catch (e: Exception) {
            // A client closing the tab surfaces as a write failure or a closed
            // channel. Entirely expected; not worth an error log.
            Log.d(TAG, "mjpeg client $host gone: ${e.message}")
        } finally {
            StreamHub.mjpeg.unsubscribe(subscriber)
            StreamHub.clientDisconnected(host, "MJPEG")
            engine?.release(Profile.MJPEG)
        }
    }

    private suspend fun RoutingContext.handleReport() {
        val params = runCatching { call.receiveParameters() }.getOrNull()

        StreamHub.addProbe(
            StreamHub.ProbeReport(
                host = call.remoteHost(),
                userAgent = params?.get("ua") ?: call.userAgent(),
                hasMediaSource = params?.get("mse") == "1",
                hasWebSocket = params?.get("ws") == "1",
                nativeHls = params?.get("hls").orEmpty(),
                supportedCodecs = params?.get("codecs").orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                screen = params?.get("screen").orEmpty(),
                receivedAtMs = System.currentTimeMillis(),
            ),
        )
        call.respondText("ok")
    }

    // -------------------------------------------------------------- helpers

    private suspend fun awaitReady(track: StreamHub.Track) {
        // A cold-started encoder needs a beat to emit its parameter sets.
        // Waiting here beats making an ES5 client implement a retry loop.
        var waited = 0L
        while (!track.ready && waited < READY_WAIT_MS) {
            delay(50)
            waited += 50
        }
    }

    private fun statusJson(): String {
        val stats = StreamHub.stats.value
        val clients = StreamHub.clients.value
        return buildString {
            append('{')
            append("\"running\":").append(stats.running)
            // The client watches this to notice a rotation: when it moves, the
            // encoder was rebuilt at a new resolution and the running MSE
            // session no longer describes what is being sent.
            append(",\"generation\":").append(StreamHub.generation.value)
            append(",\"capture\":\"").append(stats.captureWidth).append('x')
            append(stats.captureHeight).append('"')
            // What the encoder is really producing, so the telemetry panel in
            // the car can tell "the phone is not generating frames" from "the
            // frames are not arriving". Those look identical from the client
            // and need opposite fixes.
            // Rounded through Int arithmetic rather than String.format: this
            // JSON must use a dot for decimals, and a Spanish locale would
            // format it with a comma and produce a parse error in the browser.
            append(",\"fps\":").append((stats.measuredFps * 10).toInt() / 10.0)
            append(",\"kbps\":").append(stats.measuredKbps)
            append(",\"targetFps\":").append(stats.targetFps)
            append(",\"bitrateKbps\":").append(stats.videoBitrateKbps)
            append(",\"fmp4Clients\":").append(stats.fmp4Clients)
            append(",\"mjpegClients\":").append(stats.mjpegClients)
            append(",\"videoSegments\":").append(stats.videoSegments)
            append(",\"jpegFrames\":").append(stats.jpegFrames)
            append(",\"droppedJpegFrames\":").append(stats.droppedJpegFrames)
            append(",\"audio\":").append(stats.audioAvailable)
            append(",\"videoReady\":").append(StreamHub.video.ready)
            append(",\"videoCodec\":").append(StreamHub.video.codec.jsonOrNull())
            append(",\"audioCodec\":").append(StreamHub.audio.codec.jsonOrNull())
            append(",\"clients\":[")
            clients.forEachIndexed { index, client ->
                if (index > 0) append(',')
                append("{\"host\":").append(client.host.json())
                append(",\"profile\":").append(client.profile.json())
                append(",\"ua\":").append(client.userAgent.json())
                append('}')
            }
            append("]}")
        }
    }

    private suspend fun ApplicationCall.respondAsset(name: String, contentType: ContentType) {
        val bytes = assetCache.getOrPut(name) {
            runCatching { context.assets.open(name).use { it.readBytes() } }
                .getOrElse { ByteArray(0) }
        }
        if (bytes.isEmpty()) {
            respondText("asset $name missing", status = HttpStatusCode.InternalServerError)
        } else {
            noStore()
            respondBytes(bytes, contentType)
        }
    }

    private suspend fun ApplicationCall.respondJson(body: String) {
        noStore()
        respondText(body, ContentType.Application.Json)
    }

    private fun ApplicationCall.noStore() =
        response.header(HttpHeaders.CacheControl, "no-store")

    private fun ApplicationCall.remoteHost(): String = request.local.remoteHost

    private fun ApplicationCall.userAgent(): String =
        request.headers[HttpHeaders.UserAgent].orEmpty()

    /**
     * Tracks which hosts are actively pulling segments, so the engine can shut
     * the pipeline down once the last one stops asking.
     */
    private class LeaseTracker(
        private val profile: Profile,
        private val ttlMs: Long,
        scope: CoroutineScope,
    ) {
        private val leases = ConcurrentHashMap<String, Long>()

        init {
            scope.launch {
                while (isActive) {
                    delay(2_000)
                    expire()
                }
            }
        }

        fun touch(host: String, requested: Profile, userAgent: String) {
            if (requested != profile) return
            val now = System.currentTimeMillis()
            if (leases.put(host, now) == null) {
                StreamHub.engine?.acquire(profile)
                StreamHub.clientConnected(
                    StreamHub.ClientInfo(host, profile.name, userAgent, now),
                )
            }
        }

        fun releaseAll() {
            val hosts = leases.keys.toList()
            leases.clear()
            hosts.forEach {
                StreamHub.engine?.release(profile)
                StreamHub.clientDisconnected(it, profile.name)
            }
        }

        private fun expire() {
            val now = System.currentTimeMillis()
            for ((host, seen) in leases) {
                if (now - seen > ttlMs && leases.remove(host) != null) {
                    StreamHub.engine?.release(profile)
                    StreamHub.clientDisconnected(host, profile.name)
                }
            }
        }
    }

    private companion object {
        const val TAG = "StreamServer"
        const val BOUNDARY = "wastreamframe"

        val MP4 = ContentType("video", "mp4")
        val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

        /** How long a segment request parks waiting for the encoder to produce it. */
        const val SEGMENT_WAIT_MS = 10_000L

        /** Live edge, piggybacked on every segment so the client never polls. */
        const val LIVE_LATEST = "X-Live-Latest"
        const val LIVE_JOIN = "X-Live-Join"
        const val READY_WAIT_MS = 6_000L

        /** A client quiet for this long is assumed gone. */
        const val LEASE_TTL_MS = 12_000L
    }
}

private fun String?.jsonOrNull(): String = this?.json() ?: "null"

private fun String.json(): String = buildString(length + 2) {
    append('"')
    for (c in this@json) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format(Locale.ROOT, "\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}
