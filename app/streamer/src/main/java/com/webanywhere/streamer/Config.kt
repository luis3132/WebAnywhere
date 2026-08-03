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
    INSTANT("100 ms", 100),
    BALANCED("250 ms", 250),
    /** For WebViews that cannot keep up with the request rate. */
    RELAXED("500 ms", 500),
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

    val latency: Latency = Latency.INSTANT,

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
}
