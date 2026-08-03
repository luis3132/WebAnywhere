package com.webanywhere.streamer.encode

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer

/**
 * Captures what other apps are playing and encodes it to AAC-LC.
 *
 * Uses `AudioPlaybackCapture`, which needs API 29 and the same `MediaProjection`
 * consent as the screen grab. Note that apps can opt out of being captured
 * (`setAllowedCapturePolicy`), and most DRM-protected players do — the same
 * wall that blackens their video also silences their audio.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AudioEncoder(
    private val projection: MediaProjection,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int,
    private val onFormat: (audioSpecificConfig: ByteArray) -> Unit,
    private val onSample: (aac: ByteArray, ptsUs: Long) -> Unit,
) {

    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var formatDelivered = false

    private var framesCaptured = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        check(!running) { "already started" }

        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
                .apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer * 2)
                },
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE,
        )

        encoder.start()
        recorder.startRecording()

        record = recorder
        codec = encoder
        running = true
        formatDelivered = false
        framesCaptured = 0L

        thread = Thread({ pumpLoop(recorder, encoder, minBuffer) }, "audio-encoder").apply {
            start()
        }
    }

    fun stop() {
        running = false

        // Stop the recorder *before* joining. When the projection dies, the
        // record track dies with it and `AudioRecord.read()` blocks in native
        // code retrying it (three attempts, ~1.1 s). A volatile flag cannot
        // interrupt that, so joining first burns the whole timeout for nothing.
        record?.runCatching { stop() }

        thread?.join(1_000)
        thread = null

        record?.runCatching { release() }
        record = null

        codec?.runCatching {
            stop()
            release()
        }
        codec = null
    }

    private fun pumpLoop(recorder: AudioRecord, encoder: MediaCodec, bufferSize: Int) {
        val pcm = ByteArray(bufferSize)
        val info = MediaCodec.BufferInfo()
        val bytesPerFrame = 2 * channels

        while (running) {
            val read = try {
                recorder.read(pcm, 0, pcm.size)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "recorder stopped underneath us", e)
                break
            }

            if (read > 0) {
                val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index >= 0) {
                    val input = encoder.getInputBuffer(index)
                    if (input != null) {
                        input.clear()
                        input.put(pcm, 0, read)
                        val ptsUs = framesCaptured * 1_000_000L / sampleRate
                        encoder.queueInputBuffer(index, 0, read, ptsUs, 0)
                        framesCaptured += read / bytesPerFrame
                    }
                }
            }

            drain(encoder, info)
        }
    }

    private fun drain(encoder: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 0)
            } catch (e: IllegalStateException) {
                return
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!formatDelivered) {
                        encoder.outputFormat.getByteBuffer("csd-0")?.let { csd ->
                            formatDelivered = true
                            onFormat(csd.toByteArray())
                        }
                    }
                }

                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buffer.get(bytes)
                        onSample(bytes, info.presentationTimeUs)
                    }
                    runCatching { encoder.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        val copy = duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
