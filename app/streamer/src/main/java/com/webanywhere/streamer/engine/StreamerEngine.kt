package com.webanywhere.streamer.engine

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.media.projection.MediaProjection
import android.util.Log
import android.view.WindowManager
import com.webanywhere.streamer.Profile
import com.webanywhere.streamer.StreamConfig
import com.webanywhere.streamer.StreamHub
import com.webanywhere.streamer.capture.MjpegPipeline
import com.webanywhere.streamer.capture.ScreenCapture
import com.webanywhere.streamer.encode.AudioEncoder
import com.webanywhere.streamer.encode.VideoCaps
import com.webanywhere.streamer.encode.VideoEncoder
import com.webanywhere.streamer.mux.Fmp4
import com.webanywhere.streamer.mux.Fmp4Track
import com.webanywhere.streamer.stream.SegmentRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the capture and encode pipelines, and starts them only when someone is
 * actually watching.
 *
 * Both profiles run off the same `MediaProjection` but need different virtual
 * displays (different resolutions), and each display costs continuous
 * compositing work. Running both permanently would burn battery and thermal
 * headroom for a profile that may never get a client — and thermal throttling
 * is precisely what destroys the frame rate this project exists to protect.
 *
 * So: reference-count clients per profile, start on the first, and stop after a
 * grace period on the last. The grace period matters because a browser
 * reloading the page briefly drops to zero, and tearing down an encoder just to
 * rebuild it a second later would be worse than idling.
 */
class StreamerEngine(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: StreamConfig,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val capture = ScreenCapture(projection)
    private val lock = Any()

    private var videoEncoder: VideoEncoder? = null
    private var videoDisplay: VirtualDisplay? = null
    private var videoTrack: Fmp4Track? = null

    private var audioEncoder: AudioEncoder? = null
    private var audioTrack: Fmp4Track? = null

    private var mjpegPipeline: MjpegPipeline? = null
    private var mjpegDisplay: VirtualDisplay? = null

    private var fpsJob: Job? = null
    private val encodedFrames = AtomicLong(0)
    private val encodedBytes = AtomicLong(0)

    private var fmp4Demand = 0
    private var mjpegDemand = 0
    private var fmp4StopJob: Job? = null
    private var mjpegStopJob: Job? = null

    @Volatile
    private var shuttingDown = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "projection revoked by the system or the user")
            shutdown()
        }
    }

    /**
     * The callback runs off the main thread on purpose: it lands on `onStop`,
     * which tears down two encoders and an `AudioRecord`. Those calls block for
     * as long as the media stack needs, and on the main Looper that is a stall
     * the user sees.
     */
    private val callbackThread = HandlerThread("projection-callback").apply { start() }

    init {
        // Required before createVirtualDisplay on recent Android versions.
        projection.registerCallback(projectionCallback, Handler(callbackThread.looper))
    }

    // --------------------------------------------------------------- demand

    fun acquire(profile: Profile) {
        synchronized(lock) {
            if (shuttingDown) return
            when (profile) {
                Profile.FMP4 -> {
                    fmp4Demand++
                    fmp4StopJob?.cancel()
                    fmp4StopJob = null
                    if (videoEncoder == null) startVideo() else videoEncoder?.requestSyncFrame()
                }

                Profile.MJPEG -> {
                    mjpegDemand++
                    mjpegStopJob?.cancel()
                    mjpegStopJob = null
                    if (mjpegPipeline == null) startMjpeg()
                }
            }
        }
        publishClientCounts()
    }

    fun release(profile: Profile) {
        synchronized(lock) {
            when (profile) {
                Profile.FMP4 -> {
                    fmp4Demand = (fmp4Demand - 1).coerceAtLeast(0)
                    if (fmp4Demand == 0) {
                        fmp4StopJob = scope.launch {
                            delay(IDLE_GRACE_MS)
                            synchronized(lock) { if (fmp4Demand == 0) stopVideo() }
                        }
                    }
                }

                Profile.MJPEG -> {
                    mjpegDemand = (mjpegDemand - 1).coerceAtLeast(0)
                    if (mjpegDemand == 0) {
                        mjpegStopJob = scope.launch {
                            delay(IDLE_GRACE_MS)
                            synchronized(lock) { if (mjpegDemand == 0) stopMjpeg() }
                        }
                    }
                }
            }
        }
        publishClientCounts()
    }

    fun shutdown() {
        synchronized(lock) {
            if (shuttingDown) return
            shuttingDown = true
            stopVideo()
            stopMjpeg()
        }
        capture.releaseAll()
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { projection.stop() }
        // Safe even when shutdown() is running on this very thread: pending
        // messages are dropped and the loop exits once this returns.
        callbackThread.quitSafely()
        scope.cancel()
        StreamHub.reset()
        StreamHub.updateStats { it.copy(running = false, fmp4Clients = 0, mjpegClients = 0) }
    }

    // ---------------------------------------------------------------- video

    private fun startVideo() {
        val (fitWidth, fitHeight) = captureSize(config.videoWidth, config.videoHeight)
        val target = VideoCaps.resolve(
            width = fitWidth,
            height = fitHeight,
            fps = config.videoFps,
            requestedBitrate = config.videoBitrate,
            bitsPerPixel = config.imageQuality.bitsPerPixel,
        )
        val width = target.width
        val height = target.height
        Log.i(
            TAG,
            "starting H.264 pipeline at ${width}x$height@${target.fps} " +
                "at ${target.bitrate / 1000} kbps, level=${target.level}",
        )

        StreamHub.video.clear()

        val track = Fmp4Track(
            trackId = Fmp4.TRACK_VIDEO,
            timescale = VIDEO_TIMESCALE,
            targetSegmentUs = config.segmentTargetUs,
        ) { sequence, data, durationUs ->
            StreamHub.video.ring.add(SegmentRing.Segment(sequence, data, durationUs))
            StreamHub.updateStats { it.copy(videoSegments = it.videoSegments + 1) }
        }

        val encoder = VideoEncoder(
            width = width,
            height = height,
            fps = target.fps,
            bitrate = target.bitrate,
            keyFrameIntervalSec = config.keyFrameIntervalSec,
            level = target.level,
            onFormat = { sps, pps ->
                StreamHub.video.initSegment = Fmp4.videoInit(sps, pps, width, height, VIDEO_TIMESCALE)
                StreamHub.video.codec = Fmp4.avcCodecString(sps)
                Log.i(TAG, "video ready, codec=${StreamHub.video.codec}")
            },
            onSample = { avcc, ptsUs, isSync ->
                encodedFrames.incrementAndGet()
                encodedBytes.addAndGet(avcc.size.toLong())
                track.push(avcc, ptsUs, isSync)
            },
        )

        try {
            encoder.start()
        } catch (e: Exception) {
            Log.e(TAG, "video encoder failed to start", e)
            StreamHub.updateStats { it.copy(lastError = "Encoder H.264: ${e.message}") }
            return
        }

        val surface = encoder.inputSurface
        if (surface == null) {
            encoder.stop()
            return
        }

        // Zero-copy: the compositor renders straight into the encoder surface.
        videoDisplay = capture.createDisplay("wa-video", surface, width, height, densityDpi())
        videoEncoder = encoder
        videoTrack = track

        StreamHub.updateStats {
            it.copy(
                captureWidth = width,
                captureHeight = height,
                targetFps = target.fps,
                requestedFps = config.videoFps,
                videoBitrateKbps = target.bitrate / 1000,
            )
        }
        startFpsMeter()

        if (config.audioEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startAudio()
    }

    /**
     * Measures what the encoder is really producing, once a second.
     *
     * This is not decoration. `VirtualDisplay` only generates a frame when the
     * screen content changes, so the configured frame rate is a ceiling, never a
     * rate: a still screen legitimately produces almost nothing. Without a real
     * measurement there is no way to tell "nothing is moving" from "the encoder
     * cannot keep up", and those two need opposite responses.
     */
    private fun startFpsMeter() {
        fpsJob?.cancel()
        encodedFrames.set(0)
        encodedBytes.set(0)

        fpsJob = scope.launch {
            var lastFrames = 0L
            var lastBytes = 0L
            var lastAtMs = System.currentTimeMillis()

            while (true) {
                delay(FPS_WINDOW_MS)
                val now = System.currentTimeMillis()
                val elapsed = (now - lastAtMs).coerceAtLeast(1)
                val frames = encodedFrames.get()
                val bytes = encodedBytes.get()

                val fps = (frames - lastFrames) * 1000.0 / elapsed
                val kbps = ((bytes - lastBytes) * 8.0 / elapsed).toInt()

                lastFrames = frames
                lastBytes = bytes
                lastAtMs = now

                StreamHub.updateStats {
                    it.copy(measuredFps = fps, measuredKbps = kbps)
                }
            }
        }
    }

    private fun stopVideo() {
        fpsJob?.cancel()
        fpsJob = null

        capture.release(videoDisplay)
        videoDisplay = null

        videoEncoder?.stop()
        videoEncoder = null

        videoTrack?.flush()
        videoTrack = null

        stopAudio()
        StreamHub.video.clear()
        StreamHub.updateStats { it.copy(measuredFps = 0.0, measuredKbps = 0) }
        Log.i(TAG, "H.264 pipeline stopped")
    }

    // ---------------------------------------------------------------- audio

    private fun startAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        StreamHub.audio.clear()

        val track = Fmp4Track(
            trackId = Fmp4.TRACK_AUDIO,
            timescale = config.audioSampleRate,
            targetSegmentUs = AUDIO_SEGMENT_US,
            // Every AAC-LC frame is exactly 1024 samples, so durations are
            // exact and no frame needs to be held back to learn its length.
            fixedSampleDurationTicks = AAC_FRAME_SAMPLES,
        ) { sequence, data, durationUs ->
            StreamHub.audio.ring.add(SegmentRing.Segment(sequence, data, durationUs))
        }

        val encoder = AudioEncoder(
            projection = projection,
            sampleRate = config.audioSampleRate,
            channels = config.audioChannels,
            bitrate = config.audioBitrate,
            onFormat = { asc ->
                StreamHub.audio.initSegment =
                    Fmp4.audioInit(asc, config.audioSampleRate, config.audioChannels)
                StreamHub.audio.codec = "mp4a.40.2"
                StreamHub.updateStats { it.copy(audioAvailable = true) }
            },
            onSample = { aac, ptsUs -> track.push(aac, ptsUs, isSync = true) },
        )

        try {
            encoder.start()
            audioEncoder = encoder
            audioTrack = track
        } catch (e: Exception) {
            // Audio is a bonus, not a prerequisite. Losing it must not take the
            // video stream down with it.
            Log.w(TAG, "audio capture unavailable, continuing without it", e)
            StreamHub.updateStats { it.copy(audioAvailable = false) }
        }
    }

    private fun stopAudio() {
        audioEncoder?.stop()
        audioEncoder = null
        audioTrack?.flush()
        audioTrack = null
        StreamHub.audio.clear()
        StreamHub.updateStats { it.copy(audioAvailable = false) }
    }

    // ---------------------------------------------------------------- mjpeg

    private fun startMjpeg() {
        val (width, height) = captureSize(config.mjpegWidth, config.mjpegHeight)
        Log.i(TAG, "starting MJPEG pipeline at ${width}x$height")

        val pipeline = MjpegPipeline(
            width = width,
            height = height,
            fps = config.mjpegFps,
            quality = config.mjpegQuality,
            onFrame = { frame ->
                StreamHub.mjpeg.publish(frame)
                StreamHub.updateStats {
                    it.copy(
                        jpegFrames = it.jpegFrames + 1,
                        droppedJpegFrames = StreamHub.mjpeg.droppedFrames(),
                    )
                }
            },
        )

        pipeline.start()
        val surface = pipeline.surface
        if (surface == null) {
            pipeline.stop()
            return
        }

        mjpegDisplay = capture.createDisplay("wa-mjpeg", surface, width, height, densityDpi())
        mjpegPipeline = pipeline
    }

    private fun stopMjpeg() {
        capture.release(mjpegDisplay)
        mjpegDisplay = null
        mjpegPipeline?.stop()
        mjpegPipeline = null
        StreamHub.mjpeg.reset()
        Log.i(TAG, "MJPEG pipeline stopped")
    }

    // --------------------------------------------------------------- shared

    private fun publishClientCounts() {
        val (f, m) = synchronized(lock) { fmp4Demand to mjpegDemand }
        StreamHub.updateStats { it.copy(fmp4Clients = f, mjpegClients = m) }
    }

    /**
     * Scales the real screen down to fit the configured budget while keeping
     * its aspect ratio, so the car screen shows the phone undistorted.
     * Dimensions are forced even because H.264 chroma is subsampled 2x2 and
     * odd sizes are rejected or silently corrupted by many encoders.
     */
    private fun captureSize(maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return maxWidth to maxHeight

        val scale = minOf(
            maxWidth.toFloat() / screenW,
            maxHeight.toFloat() / screenH,
            1.0f,
        )

        fun even(v: Float) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(screenW * scale) to even(screenH * scale)
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun densityDpi(): Int = context.resources.displayMetrics.densityDpi

    private companion object {
        const val TAG = "StreamerEngine"

        /** Microsecond timescale: MediaCodec reports PTS in µs, so no rounding drift. */
        const val VIDEO_TIMESCALE = 1_000_000

        const val AAC_FRAME_SAMPLES = 1024
        const val AUDIO_SEGMENT_US = 500_000L

        /** Survive a page reload without tearing the encoder down and back up. */
        const val IDLE_GRACE_MS = 15_000L

        const val FPS_WINDOW_MS = 1_000L
    }
}
