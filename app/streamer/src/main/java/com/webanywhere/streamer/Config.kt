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
     * Key frame cadence. Sets how long a *joining* client waits, not the
     * latency of one already watching — those were the same thing until
     * segments stopped being tied to key frames.
     */
    val keyFrameIntervalSec: Int = 1,

    /**
     * Media segment length, and the dominant term in end-to-end latency: a
     * segment cannot be sent until it is closed. 100 ms puts the fMP4 path in
     * the same league as the MJPEG one, at the cost of ~10 requests per second
     * per track — nothing on a LAN.
     */
    val segmentTargetMs: Int = 100,

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
    val segmentTargetUs: Long get() = segmentTargetMs * 1_000L
}
