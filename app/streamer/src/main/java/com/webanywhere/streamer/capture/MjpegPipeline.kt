package com.webanywhere.streamer.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream

/**
 * Screen pixels → JPEG frames, for the profile that works on absolutely
 * anything that can render an `<img>`.
 *
 * This is the one place we *do* pull frames through app memory, because JPEG
 * has no surface-input encoder path. It is therefore the expensive pipeline,
 * and the reason the MJPEG profile runs at a lower resolution than the H.264
 * one. Two mitigations matter:
 *
 *  - **Bitmap reuse.** Allocating two full-screen bitmaps per frame would spend
 *    more time in GC than in JPEG.
 *  - **Frame pacing.** `ImageReader` delivers as fast as the compositor draws;
 *    encoding every one of those wastes CPU nobody is watching.
 */
class MjpegPipeline(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val quality: Int,
    private val onFrame: (ByteArray) -> Unit,
) {

    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    // Reused across frames; sized on first delivery once the row stride is known.
    private var padded: Bitmap? = null
    private var cropped: Bitmap? = null
    private var canvas: Canvas? = null
    private val jpeg = ByteArrayOutputStream(256 * 1024)

    private var lastFrameNs = 0L
    private val minIntervalNs = 1_000_000_000L / fps.coerceAtLeast(1)

    var framesEncoded = 0L
        private set

    /** Render target for the virtual display. Valid only between start and stop. */
    val surface: Surface? get() = reader?.surface

    fun start() {
        val handlerThread = HandlerThread("mjpeg-pipeline").apply { start() }
        val handler = Handler(handlerThread.looper)

        // maxImages = 2: enough to avoid stalling the producer, small enough
        // that we are never encoding a frame that is already two frames stale.
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)

        thread = handlerThread
        reader = imageReader
    }

    fun stop() {
        reader?.runCatching {
            setOnImageAvailableListener(null, null)
            close()
        }
        reader = null

        thread?.quitSafely()
        thread = null

        padded?.recycle()
        padded = null
        cropped?.recycle()
        cropped = null
        canvas = null
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        val image = try {
            imageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "image acquire failed", e)
            null
        } ?: return

        try {
            val now = System.nanoTime()
            if (now - lastFrameNs < minIntervalNs) return  // pacing: drop, do not encode
            lastFrameNs = now

            encode(image)?.let {
                framesEncoded++
                onFrame(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame encode failed", e)
        } finally {
            runCatching { image.close() }
        }
    }

    private fun encode(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        // The buffer rows are padded to a hardware-friendly stride, so we copy
        // into a wider bitmap and then crop, rather than shipping the padding.
        val strideWidth = rowStride / pixelStride

        val source = padded.takeIf { it != null && it.width == strideWidth && it.height == height }
            ?: Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888).also {
                padded?.recycle()
                padded = it
            }

        val target = cropped ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            cropped = it
            canvas = Canvas(it)
        }

        plane.buffer.rewind()
        source.copyPixelsFromBuffer(plane.buffer)

        if (strideWidth == width) {
            jpeg.reset()
            return if (source.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) {
                jpeg.toByteArray()
            } else {
                null
            }
        }

        val c = canvas ?: return null
        c.drawBitmap(source, Rect(0, 0, width, height), Rect(0, 0, width, height), null)

        jpeg.reset()
        return if (target.compress(Bitmap.CompressFormat.JPEG, quality, jpeg)) {
            jpeg.toByteArray()
        } else {
            null
        }
    }

    private companion object {
        const val TAG = "MjpegPipeline"
    }
}
