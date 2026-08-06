package com.webanywhere.streamer.capture

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * Owns the virtual displays fed by one `MediaProjection`.
 *
 * This is far less free than it looks. Since Android 14 a `MediaProjection`
 * grants exactly **one** `createVirtualDisplay` call: a second one throws
 * `SecurityException`, and there is no way to obtain another without sending the
 * user back through the consent dialog. Rebuilding a pipeline — which is what
 * rotating the phone does — used to call it again, and that is what killed the
 * app on rotation.
 *
 * So a display is never thrown away and recreated. One is created the first time
 * a pipeline asks for it and afterwards re-pointed with `resize` + `setSurface`,
 * which is the path the platform documents for configuration changes. Handing a
 * display back sets its surface to null, which stops the compositing work
 * without giving up a permission we could not get again.
 */
class ScreenCapture(private val projection: MediaProjection) {

    /** A created display plus whichever pipeline currently renders into it. */
    private class Lease(val display: VirtualDisplay, var owner: String?)

    private val lock = Any()
    private val leases = mutableListOf<Lease>()

    /**
     * Points a display at [surface], creating one only if this projection has
     * never been asked for one.
     *
     * Returns false when there is nothing to give: on Android 14+ that means one
     * pipeline already holds the single display this consent bought.
     */
    fun attach(
        owner: String,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean {
        synchronized(lock) {
            leases.firstOrNull { it.owner == owner }?.let {
                return repoint(it, surface, width, height, densityDpi)
            }

            leases.firstOrNull { it.owner == null }?.let {
                it.owner = owner
                return repoint(it, surface, width, height, densityDpi)
            }

            if (!canCreateAnother()) {
                Log.w(
                    TAG,
                    "no display left for $owner: Android 14+ grants exactly one " +
                        "per capture consent and it is already in use",
                )
                return false
            }

            val display = runCatching {
                projection.createVirtualDisplay(
                    owner,
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null,
                )
            }.onFailure {
                Log.e(TAG, "createVirtualDisplay failed for $owner", it)
            }.getOrNull() ?: return false

            leases.add(Lease(display, owner))
            return true
        }
    }

    /**
     * Stops feeding [owner]'s pipeline without releasing the display.
     *
     * A null surface is enough to end the compositing work, and it leaves the
     * display available for the next pipeline — releasing it would be giving
     * back a `createVirtualDisplay` call we cannot make again.
     */
    fun detach(owner: String) {
        synchronized(lock) {
            val lease = leases.firstOrNull { it.owner == owner } ?: return
            lease.owner = null
            runCatching { lease.display.setSurface(null) }
                .onFailure { Log.w(TAG, "could not unhook $owner", it) }
        }
    }

    fun releaseAll() {
        val snapshot = synchronized(lock) { leases.toList().also { leases.clear() } }
        snapshot.forEach { runCatching { it.display.release() } }
    }

    private fun repoint(
        lease: Lease,
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Boolean = runCatching {
        // Resize first: handing over a surface whose geometry the display does
        // not yet know produces one badly scaled frame on some devices.
        lease.display.resize(width, height, densityDpi)
        lease.display.setSurface(surface)
        true
    }.onFailure {
        Log.e(TAG, "could not re-point the display for ${lease.owner}", it)
    }.getOrDefault(false)

    /** True while this projection still has a `createVirtualDisplay` call left. */
    private fun canCreateAnother(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || leases.isEmpty()

    private companion object {
        const val TAG = "ScreenCapture"
    }
}
