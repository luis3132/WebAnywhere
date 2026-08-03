package com.webanywhere.streamer.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.webanywhere.streamer.mux.AnnexB
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder fed by a [Surface].
 *
 * The surface is the whole point: `MediaProjection` renders straight into the
 * encoder's input surface, so frames go GPU → encoder without ever being copied
 * into app memory. Routing screen capture through an `ImageReader` instead —
 * the obvious-looking approach — costs a full-frame copy plus a colour convert
 * per frame and is what turns 60 fps into 12.
 *
 * Encoder settings target compatibility with old decoders over compression
 * efficiency: Baseline profile means no B-frames, which means no frame
 * reordering, which means PTS equals DTS and the muxer never needs composition
 * offsets.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val keyFrameIntervalSec: Int,
    /** Resolved by [VideoCaps]; null lets the encoder choose. */
    private val level: Int? = null,
    private val onFormat: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onSample: (avcc: ByteArray, ptsUs: Long, isSync: Boolean) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var formatDelivered = false

    /** Where the capture pipeline should render. Valid only between start and stop. */
    var inputSurface: Surface? = null
        private set

    fun start() {
        check(!running) { "already started" }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        configure(encoder)
        inputSurface = encoder.createInputSurface()
        encoder.start()

        codec = encoder
        running = true
        formatDelivered = false

        drainThread = Thread({ drainLoop(encoder) }, "video-encoder-drain").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        drainThread?.join(1_000)
        drainThread = null

        codec?.runCatching {
            stop()
            release()
        }
        codec = null

        inputSurface?.release()
        inputSurface = null
    }

    /**
     * Asks the encoder for an immediate key frame. Called when a client
     * connects, so it does not have to wait up to a full GOP for a segment it
     * can actually start decoding from.
     */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    /** Bitrate is the one knob that can be turned without a restart. */
    fun setBitrate(bitsPerSecond: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)
            })
        }
    }

    private fun configure(encoder: MediaCodec) {
        try {
            encoder.configure(buildFormat(strict = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject an explicit profile/level or CBR. Falling back
            // to a bare format is far better than failing to stream at all.
            Log.w(TAG, "strict encoder config rejected, retrying minimal", e)
            encoder.reset()
            encoder.configure(buildFormat(strict = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    private fun buildFormat(strict: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)

            if (strict) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                )
                // Only ever a level the content actually fits in. Asserting a
                // low one here is the classic way to cap a stream at 720p30
                // without any error to explain why.
                level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
        }

    private fun drainLoop(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()

        while (running) {
            val index = try {
                encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "encoder went away while draining", e)
                break
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    deliverFormat(encoder.outputFormat)
                }

                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        handleOutput(buffer, info)
                    }
                    runCatching { encoder.releaseOutputBuffer(index, false) }
                }
            }

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    }

    private fun handleOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Parameter sets arrive out of band; they belong in avcC, not in a sample.
            if (!formatDelivered) {
                AnnexB.spsPps(bytes)?.let { (sps, pps) ->
                    formatDelivered = true
                    onFormat(sps, pps)
                }
            }
            return
        }

        val avcc = AnnexB.toAvcc(bytes)
        if (avcc.isEmpty()) return

        val isSync = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        onSample(avcc, info.presentationTimeUs, isSync)
    }

    private fun deliverFormat(format: MediaFormat) {
        if (formatDelivered) return

        val csd0 = format.getByteBuffer("csd-0")?.let { it.toByteArray() } ?: return
        val csd1 = format.getByteBuffer("csd-1")?.let { it.toByteArray() } ?: ByteArray(0)

        AnnexB.spsPps(csd0 + csd1)?.let { (sps, pps) ->
            formatDelivered = true
            onFormat(sps, pps)
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private companion object {
        const val TAG = "VideoEncoder"
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
