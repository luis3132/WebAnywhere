package com.webanywhere.streamer.encode

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Resolved encoder settings: what the hardware will actually accept, not what
 * we asked for.
 *
 * @param level an [CodecProfileLevel] constant, or null to let the encoder
 *   decide. Declaring a level the content exceeds is worse than declaring none.
 */
data class VideoTarget(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val level: Int?,
)

/**
 * Negotiates resolution and frame rate against the device's H.264 encoder.
 *
 * This exists because of a trap that is easy to walk into: hardcoding
 * `AVCLevel31` caps the stream at 108 000 macroblocks per second, which is
 * 720p30 to the macroblock. Under that setting 1080p or 60 fps are not slow,
 * they are forbidden, and the failure is silent — the encoder just clamps or
 * rejects the configuration. So the level is derived from the content instead
 * of asserted, and every combination is checked against
 * [MediaCodecInfo.VideoCapabilities] before it reaches [MediaCodec.configure].
 *
 * When the request does not fit, **frame rate is preserved and resolution is
 * given up**. For mirroring a screen onto a car display, motion smoothness is
 * far more noticeable than pixel count, and H.264 compresses screen content —
 * large flat areas, few real changes between frames — extremely well.
 */
object VideoCaps {

    private const val TAG = "VideoCaps"

    private const val MIN_BITRATE = 1_500_000

    /** Used only when the encoder refuses to describe itself. */
    private const val FALLBACK_BITS_PER_PIXEL = 0.35

    /** Resolution is sacrificed in these steps before frame rate is touched. */
    private val SCALE_STEPS = listOf(1.0, 0.85, 0.75, 0.6, 0.5)

    /**
     * @param bitsPerPixel null means "whatever the encoder says it can do" —
     *   the bitrate is then taken straight from its advertised maximum.
     */
    fun resolve(
        width: Int,
        height: Int,
        fps: Int,
        requestedBitrate: Int?,
        bitsPerPixel: Double?,
    ): VideoTarget {
        val caps = videoCapabilities()
            ?: return VideoTarget(
                width = even(width),
                height = even(height),
                fps = fps,
                bitrate = requestedBitrate ?: autoBitrate(width, height, fps, bitsPerPixel, null),
                level = null,
            )

        // Frame rate first: every resolution is tried at the requested rate
        // before the rate itself is reduced.
        for (targetFps in listOf(fps, 30).distinct()) {
            for (scale in SCALE_STEPS) {
                val w = align((width * scale).roundToInt(), caps.widthAlignment)
                val h = align((height * scale).roundToInt(), caps.heightAlignment)
                if (w <= 0 || h <= 0) continue

                val supported = runCatching {
                    caps.areSizeAndRateSupported(w, h, targetFps.toDouble())
                }.getOrDefault(false)

                if (supported) {
                    if (w != width || h != height || targetFps != fps) {
                        Log.i(TAG, "requested ${width}x$height@$fps, encoder accepts ${w}x$h@$targetFps")
                    }
                    val desired =
                        requestedBitrate ?: autoBitrate(w, h, targetFps, bitsPerPixel, caps)
                    val negotiated = negotiate(w, h, targetFps, desired, caps)
                    return VideoTarget(
                        width = w,
                        height = h,
                        fps = targetFps,
                        bitrate = negotiated.bitrate,
                        level = negotiated.level,
                    )
                }
            }
        }

        Log.w(TAG, "no supported size for ${width}x$height@$fps, falling back")
        return VideoTarget(
            width = even(width),
            height = even(height),
            fps = 30,
            bitrate = requestedBitrate ?: autoBitrate(width, height, 30, bitsPerPixel, null),
            level = null,
        )
    }

    private fun videoCapabilities(): MediaCodecInfo.VideoCapabilities? = encoderInfo()
        ?.runCatching { getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }
        ?.getOrNull()

    /** Hardware first: a software AVC encoder cannot hold 1080p60 on a phone. */
    private fun encoderInfo(): MediaCodecInfo? {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull() ?: return null

        val candidates = infos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        return candidates.firstOrNull { it.isHardwareAcceleratedCompat() } ?: candidates.firstOrNull()
    }

    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            // Pre-Q there is no flag; the software encoders are named by convention.
            !name.startsWith("OMX.google.", true) && !name.startsWith("c2.android.", true)
        }

    private fun autoBitrate(
        width: Int,
        height: Int,
        fps: Int,
        bitsPerPixel: Double?,
        caps: MediaCodecInfo.VideoCapabilities?,
    ): Int {
        if (bitsPerPixel == null) {
            // "As much as it can take": the encoder's own advertised ceiling.
            val max = runCatching { caps?.bitrateRange?.upper }.getOrNull()
            if (max != null && max > MIN_BITRATE) return max
        }
        val bpp = bitsPerPixel ?: FALLBACK_BITS_PER_PIXEL
        return (width.toDouble() * height * fps * bpp).toInt().coerceAtLeast(MIN_BITRATE)
    }

    private fun clampBitrate(bitrate: Int, caps: MediaCodecInfo.VideoCapabilities): Int =
        runCatching { caps.bitrateRange.clamp(bitrate) }.getOrDefault(bitrate)

    private class Negotiated(val bitrate: Int, val level: Int?)

    /**
     * Picks a level that accommodates the bitrate. The bitrate is never reduced
     * to fit a level: the only ceiling is the encoder's own advertised range.
     *
     * A level caps bitrate as well as picture size, so when the requested rate
     * is above every level this encoder can declare, the level is simply left
     * unset and the encoder writes whatever it likes into the SPS. Declaring
     * level 4.0 while sending 30 Mbps would be a stream that describes itself
     * incorrectly, and a strict decoder may refuse it.
     */
    private fun negotiate(
        width: Int,
        height: Int,
        fps: Int,
        desiredBitrate: Int,
        caps: MediaCodecInfo.VideoCapabilities,
    ): Negotiated {
        val bitrate = clampBitrate(desiredBitrate.coerceAtLeast(MIN_BITRATE), caps)
        val usable = usableLevels()

        val mbs = macroblocks(width, height)
        val perSecond = mbs.toLong() * fps
        fun pictureFits(level: Level) =
            mbs <= level.maxMacroblocks && perSecond <= level.maxMacroblocksPerSecond

        usable.firstOrNull { pictureFits(it) && bitrate / 1000 <= it.maxKbps }
            ?.let { return Negotiated(bitrate, it.constant) }

        Log.i(
            TAG,
            "${bitrate / 1000} kbps is above every declarable level; " +
                "keeping the bitrate and letting the encoder choose the level",
        )
        return Negotiated(bitrate, null)
    }

    /** Levels the encoder actually advertises for Baseline. */
    private fun usableLevels(): List<Level> {
        val profileLevels = encoderInfo()
            ?.runCatching { getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).profileLevels }
            ?.getOrNull()
            ?: return LEVELS

        val baselineMax = profileLevels
            .filter {
                it.profile == CodecProfileLevel.AVCProfileBaseline ||
                    it.profile == CodecProfileLevel.AVCProfileConstrainedBaseline
            }
            .maxOfOrNull { it.level }
            ?: return LEVELS

        // The constants increase with the level, so this comparison is valid.
        return LEVELS.filter { it.constant <= baselineMax }.ifEmpty { LEVELS }
    }

    /**
     * H.264 level limits (Table A-1). `maxKbps` is the part that is easy to
     * forget: a level caps not only the picture size and rate but the bitrate
     * too, and a decoder is entitled to reject a stream that declares level 4.0
     * and then sends 30 Mbps.
     */
    private class Level(
        val maxMacroblocks: Int,
        val maxMacroblocksPerSecond: Long,
        val maxKbps: Int,
        val constant: Int,
    )

    private val LEVELS = listOf(
        Level(1_620, 40_500L, 10_000, CodecProfileLevel.AVCLevel3),
        Level(3_600, 108_000L, 14_000, CodecProfileLevel.AVCLevel31),
        Level(5_120, 216_000L, 20_000, CodecProfileLevel.AVCLevel32),
        Level(8_192, 245_760L, 20_000, CodecProfileLevel.AVCLevel4),
        Level(8_704, 522_240L, 50_000, CodecProfileLevel.AVCLevel42),
        Level(22_080, 589_824L, 135_000, CodecProfileLevel.AVCLevel5),
        Level(36_864, 983_040L, 240_000, CodecProfileLevel.AVCLevel51),
    )

    private fun macroblocks(width: Int, height: Int): Int =
        ceil(width / 16.0).toInt() * ceil(height / 16.0).toInt()


    private fun align(value: Int, alignment: Int): Int {
        val step = if (alignment > 0) alignment else 2
        return (value / step) * step
    }

    private fun even(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)
}
