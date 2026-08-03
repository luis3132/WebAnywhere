package com.webanywhere.streamer.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface

/**
 * Wraps `MediaProjection` and hands out one [VirtualDisplay] per consumer.
 *
 * A single projection can drive several virtual displays, which is how the
 * fMP4 and MJPEG pipelines run at different resolutions from one user consent.
 * Each display costs real compositing work, so the engine only creates one when
 * a client is actually watching that profile.
 */
class ScreenCapture(private val projection: MediaProjection) {

    private val displays = mutableListOf<VirtualDisplay>()

    fun createDisplay(
        name: String,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): VirtualDisplay? {
        val display = projection.createVirtualDisplay(
            name,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        ) ?: return null

        synchronized(displays) { displays.add(display) }
        return display
    }

    fun release(display: VirtualDisplay?) {
        if (display == null) return
        synchronized(displays) { displays.remove(display) }
        runCatching { display.release() }
    }

    fun releaseAll() {
        val snapshot = synchronized(displays) { displays.toList().also { displays.clear() } }
        snapshot.forEach { runCatching { it.release() } }
    }
}
