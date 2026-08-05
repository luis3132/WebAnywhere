package com.webanywhere.streamer

/** Which delivery profile a client is using. See PLAN.md for the ladder. */
enum class Profile { FMP4, MJPEG }

/**
 * Resolution budget. The capture is scaled to fit inside this while keeping the
 * screen's aspect ratio, so these are ceilings, not literal output sizes.
 */
enum class Quality(val label: String, val maxWidth: Int, val maxHeight: Int) {
    HD("720p", 1280, 720),
    FULL_HD("1080p", 1920, 1080),
    /** Effectively "whatever the screen is", clamped later by the encoder. */
    NATIVE("Máxima", 3840, 2160),
}

/**
 * Segment length, which is the dominant term in end-to-end latency because a
 * segment cannot be sent until it is closed.
 *
 * Shorter is not free: each segment is one HTTP request per track, so 100 ms
 * means roughly twenty requests a second with audio on. A modern browser does
 * not notice; an old head unit WebView might, and it is not something the user
 * could change from inside the car.
 */
enum class Latency(val label: String, val segmentMs: Int) {
    /** Measured to stutter on a real head unit. Kept for fast local browsers. */
    INSTANT("100 ms", 100),
    BALANCED("250 ms", 250),
    /** For WebViews that cannot keep up with the request rate. */
    RELAXED("500 ms", 500),
}

/**
 * How much media the player keeps ahead of the play head.
 *
 * This is the knob that decides smooth-versus-immediate, and it is a genuine
 * trade rather than a tuning detail: the buffer *is* the delay. Two seconds of
 * buffer means what you see is two seconds old.
 *
 * It buys the one thing segment length cannot. A short segment reduces how long
 * a frame waits to be *sent*; only a buffer absorbs a frame that arrives late.
 * A car WebView decoding 1080p on a weak chip misses its deadline often enough
 * that with no cushion the picture judders — which is worse to watch than a
 * delay you stop noticing after a few seconds.
 *
 * The player holds this by speeding up or slowing down very slightly, never by
 * jumping: see the drift control in `player.html`.
 */
enum class Buffer(val label: String, val ms: Int) {
    /**
     * The short end. These only mean what they say when the segments are short
     * enough to fit inside them — see [StreamConfig.effectiveBufferMs], which
     * raises anything that asks for less media than the player needs to keep
     * going.
     */
    MS150("150 ms", 150),
    MS300("300 ms", 300),
    MS650("650 ms", 650),

    /** Close to live. Needs a browser that comfortably keeps up. */
    TIGHT("1 s", 1_000),
    SMOOTH("2 s", 2_000),
    SAFE("3 s", 3_000),
    /** For a head unit that stutters at everything else. */
    MAX("5 s", 5_000),
}

/**
 * How many bits to spend per pixel per frame.
 *
 * These are far above what a streaming service would use, on purpose: this runs
 * over a LAN, where bandwidth is nearly free and the enemy is latency, not the
 * data cap. Screen content also punishes a starved bitrate very visibly —
 * sharp text is the worst case for H.264, and smearing it is exactly what a
 * conservative bitrate produces.
 */
enum class ImageQuality(val label: String, val bitsPerPixel: Double?) {
    /** Safe on a 2.4 GHz hotspot, which is all some head units speak. */
    BALANCED("Normal", 0.10),
    HIGH("Alta", 0.20),
    /** ~44 Mbps at 1080p60. */
    MAX("Máxima", 0.35),

    /** No ceiling of ours: whatever the hardware encoder advertises. */
    UNCAPPED("Sin límite", null),
}

/**
 * Tunables for the capture and encode pipelines.
 *
 * Defaults target the stated goal — high frame rate over a local Wi-Fi link to
 * one or two clients — rather than bandwidth thrift. On a LAN, bytes are cheap
 * and latency is not.
 */
data class StreamConfig(
    val port: Int = 8080,

    // --- fMP4 / H.264 profile ---
    val quality: Quality = Quality.FULL_HD,
    val videoFps: Int = 60,
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    /** Null derives it from resolution, frame rate and [imageQuality]. */
    val videoBitrate: Int? = null,
    /**
     * Key frame cadence, and therefore how close to the live edge a client can
     * *land* when it joins or resynchronises — it has to start on a key frame,
     * so the newest one is the freshest possible entry point.
     *
     * 500 ms rather than the usual second because falling behind is answered by
     * jumping to the live edge, and a jump that lands a second in the past is
     * not a recovery. Extra key frames cost bits, which on a LAN is the cheap
     * side of the trade.
     */
    val keyFrameIntervalMs: Int = 500,

    /**
     * 250 ms rather than the 100 ms the pipeline can technically do: on a real
     * head unit, 100 ms segments made the picture judder. Twenty requests a
     * second is a lot of work for an old WebView, and a stuttering picture at
     * 100 ms is worse than a smooth one at 250 ms. The shorter setting is still
     * offered for browsers that can take it.
     */
    val latency: Latency = Latency.BALANCED,

    val buffer: Buffer = Buffer.SMOOTH,

    // --- MJPEG profile ---
    /** Deliberately smaller: JPEG has no inter-frame compression to lean on. */
    val mjpegWidth: Int = 854,
    val mjpegHeight: Int = 480,
    val mjpegFps: Int = 25,
    val mjpegQuality: Int = 60,

    // --- audio ---
    val audioEnabled: Boolean = true,
    val audioSampleRate: Int = 44_100,
    val audioChannels: Int = 2,
    val audioBitrate: Int = 128_000,
) {
    val videoWidth: Int get() = quality.maxWidth
    val videoHeight: Int get() = quality.maxHeight

    /** Microsecond target used by the segmenter. */
    val segmentTargetUs: Long get() = latency.segmentMs * 1_000L

    /**
     * The smallest buffer that is not a promise to drop media.
     *
     * A player cannot hold less than the segment it is currently playing, so a
     * buffer below one segment is not a small cushion — it is a gap. Asking for
     * 50 ms with 100 ms segments means 50 ms of picture and sound that nothing
     * ever holds, and they are simply lost.
     *
     * One and a half segments is the floor: a whole segment to play from, plus
     * half of one as slack, so the next has time to arrive before the current
     * one runs out.
     */
    val minBufferMs: Int get() = latency.segmentMs * 3 / 2

    /**
     * What the player is actually told to hold. Raised above the requested
     * [buffer] whenever the chosen latency makes that request impossible.
     */
    val effectiveBufferMs: Int get() = maxOf(buffer.ms, minBufferMs)

    /** True when [latency] is forcing a bigger buffer than was asked for. */
    val bufferRaised: Boolean get() = effectiveBufferMs > buffer.ms

    val bufferUs: Long get() = effectiveBufferMs * 1_000L

    /**
     * How many segments the server must retain for a joining client to be able
     * to fill [buffer] immediately.
     *
     * Twice the buffer, plus a few: a client is served from somewhere inside
     * this window, not at its edge, so retaining exactly one buffer's worth
     * would mean the oldest segment expires the moment it is needed.
     */
    val retainedSegments: Int
        get() = (effectiveBufferMs / latency.segmentMs) * 2 + 4

    /**
     * Changes the latency, carrying the buffer along if it no longer fits.
     *
     * Longer segments raise the floor, which can strand the current buffer
     * below it. Leaving it there would show a selected option that is no longer
     * selectable, so the choice moves up to the smallest one that still works.
     */
    fun withLatency(latency: Latency): StreamConfig {
        val moved = copy(latency = latency)
        if (!moved.bufferRaised) return moved

        val smallestValid = Buffer.entries.firstOrNull { it.ms >= moved.minBufferMs }
            ?: Buffer.entries.last()
        return moved.copy(buffer = smallestValid)
    }
}
