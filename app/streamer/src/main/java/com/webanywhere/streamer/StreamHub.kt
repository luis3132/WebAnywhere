package com.webanywhere.streamer

import com.webanywhere.streamer.engine.StreamerEngine
import com.webanywhere.streamer.stream.MjpegHub
import com.webanywhere.streamer.stream.SegmentRing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * The one piece of shared state between the capture engine, the HTTP server and
 * the UI.
 *
 * A process-wide singleton is the honest shape here: there is exactly one
 * screen, one encoder and one server per running app, and threading a
 * dependency graph through a foreground service to reach that conclusion would
 * be ceremony without benefit.
 */
object StreamHub {

    class Track(retainedSegments: Int, maxBytes: Int = Int.MAX_VALUE) {
        val ring = SegmentRing(retainedSegments, maxBytes)

        /** `ftyp` + `moov`, published once the encoder reports its format. */
        @Volatile
        var initSegment: ByteArray? = null

        /** RFC 6381 string the client needs for `MediaSource.isTypeSupported`. */
        @Volatile
        var codec: String? = null

        val ready: Boolean get() = initSegment != null

        fun resize(segments: Int, maxBytes: Int) = ring.resize(segments, maxBytes)

        fun clear() {
            initSegment = null
            codec = null
            ring.clear()
        }
    }

    /**
     * The retained window, which is also the deadline.
     *
     * Anything evicted cannot be asked for: the request gets a 410 that sends
     * the client to the live edge. That is intended rather than a limitation —
     * this is live video, and delivering a frame late only pushes every later
     * frame later too.
     *
     * These are only starting values. The real bounds are set by the engine
     * once the buffer setting and the negotiated bitrate are known, because
     * those are what decide how much history a joining client needs and what it
     * costs in bytes. The byte cap is the bound that actually matters: at an
     * uncapped bitrate a segment can be ten times its usual size, and counting
     * segments alone would quietly turn into tens of megabytes.
     */
    val video = Track(retainedSegments = 24, maxBytes = 8 * 1024 * 1024)

    val audio = Track(retainedSegments = 24, maxBytes = 1024 * 1024)

    val mjpeg = MjpegHub()

    private val _config = MutableStateFlow(StreamConfig())

    /** Exposed as a flow so the settings UI recomposes when it changes. */
    val configFlow: StateFlow<StreamConfig> = _config

    var config: StreamConfig
        get() = _config.value
        set(value) { _config.value = value }

    @Volatile
    var engine: StreamerEngine? = null

    /**
     * Bumped whenever the video changes shape — today that means the phone was
     * rotated, which forces a new encoder at a new resolution.
     *
     * A running MSE session cannot absorb that: its initialisation segment
     * describes the old dimensions, and the sequence numbering restarts. The
     * client polls this and rebuilds when it moves. Making the client ask,
     * rather than pushing at it, keeps the whole mechanism inside the one HTTP
     * request it was already making.
     */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation

    fun bumpGeneration() = _generation.update { it + 1 }

    // ------------------------------------------------------------ live stats

    data class Stats(
        val running: Boolean = false,
        val urls: List<String> = emptyList(),
        val fmp4Clients: Int = 0,
        val mjpegClients: Int = 0,
        val videoSegments: Long = 0,
        val jpegFrames: Long = 0,
        val droppedJpegFrames: Long = 0,
        val captureWidth: Int = 0,
        val captureHeight: Int = 0,
        /** What the user asked for. */
        val requestedFps: Int = 0,
        /** What the encoder agreed to, which may be lower. */
        val targetFps: Int = 0,
        /** What is actually coming out, averaged over the last second. */
        val measuredFps: Double = 0.0,
        val measuredKbps: Int = 0,
        val videoBitrateKbps: Int = 0,
        val audioAvailable: Boolean = false,
        val lastError: String? = null,
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    fun updateStats(block: (Stats) -> Stats) = _stats.update(block)

    // ---------------------------------------------------- connected clients

    data class ClientInfo(
        val host: String,
        val profile: String,
        val userAgent: String,
        val connectedAtMs: Long,
    )

    private val _clients = ConcurrentHashMap<String, ClientInfo>()
    private val _clientList = MutableStateFlow<List<ClientInfo>>(emptyList())
    val clients: StateFlow<List<ClientInfo>> = _clientList

    fun clientConnected(info: ClientInfo) {
        _clients[info.host + "|" + info.profile] = info
        _clientList.value = _clients.values.sortedBy { it.connectedAtMs }
    }

    fun clientDisconnected(host: String, profile: String) {
        _clients.remove("$host|$profile")
        _clientList.value = _clients.values.sortedBy { it.connectedAtMs }
    }

    // ------------------------------------------------------- probe reports

    /**
     * What the target browser told us about itself. This is the entire point of
     * Phase 0: the encoder settings are chosen from these answers, not guessed.
     */
    data class ProbeReport(
        val host: String,
        val userAgent: String,
        val hasMediaSource: Boolean,
        val hasWebSocket: Boolean,
        val nativeHls: String,
        val supportedCodecs: List<String>,
        val screen: String,
        val receivedAtMs: Long,
    )

    private val _probes = MutableStateFlow<List<ProbeReport>>(emptyList())
    val probes: StateFlow<List<ProbeReport>> = _probes

    fun addProbe(report: ProbeReport) {
        _probes.update { (listOf(report) + it).take(10) }
    }

    fun reset() {
        video.clear()
        audio.clear()
        mjpeg.reset()
        _clients.clear()
        _clientList.value = emptyList()
    }
}
