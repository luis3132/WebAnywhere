package com.webanywhere.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.webanywhere.streamer.MainActivity
import com.webanywhere.streamer.R
import com.webanywhere.streamer.StreamConfig
import com.webanywhere.streamer.StreamHub
import com.webanywhere.streamer.engine.StreamerEngine
import com.webanywhere.streamer.net.NetInfo
import com.webanywhere.streamer.server.StreamServer
import android.media.projection.MediaProjectionManager

/**
 * Keeps capture, encoding and the HTTP server alive while the user is in
 * another app — which is the entire point, since the thing being mirrored is
 * whatever they switch to.
 *
 * Three locks matter here and all three are easy to forget:
 *  - the foreground service itself, or Android kills the process;
 *  - a partial wake lock, or the CPU sleeps mid-stream with the screen off;
 *  - a **high-performance Wi-Fi lock**, or Wi-Fi power saving kicks in and
 *    latency goes from milliseconds to hundreds of milliseconds in bursts.
 */
class StreamerService : Service() {

    private var engine: StreamerEngine? = null
    private var server: StreamServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val teardownLock = Any()

    @Volatile
    private var teardownThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopAsync()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (engine != null) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            Log.e(TAG, "no projection consent payload")
            stopSelf()
            return
        }

        val config = StreamHub.config

        // Order matters on Android 14+: the service must already be in the
        // foreground with the mediaProjection type before the projection token
        // is redeemed, or the system throws.
        startForegroundNotification(config)

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "projection rejected", e)
            StreamHub.updateStats { it.copy(lastError = "Proyección rechazada: ${e.message}") }
            stopSelf()
            return
        }

        if (projection == null) {
            stopSelf()
            return
        }

        acquireLocks()

        engine = StreamerEngine(applicationContext, projection, config).also {
            StreamHub.engine = it
        }
        server = StreamServer(applicationContext, config).also {
            runCatching { it.start() }.onFailure { error ->
                Log.e(TAG, "server failed to bind port ${config.port}", error)
                StreamHub.updateStats { s ->
                    s.copy(lastError = "Puerto ${config.port} ocupado: ${error.message}")
                }
            }
        }

        StreamHub.updateStats {
            it.copy(running = true, urls = NetInfo.urls(config.port), lastError = null)
        }
    }

    /**
     * `onStartCommand` runs on the main thread, and tearing this down is not a
     * quick operation: `StreamServer.stop` waits for in-flight requests to
     * drain (up to two seconds by contract), and releasing the encoders blocks
     * on the media stack. Doing that inline froze the UI for ~700 ms.
     *
     * So the flag that the UI watches is flipped straight away and the actual
     * teardown moves to its own thread, which calls `stopSelf` when it is done.
     * The notification is dismissed there too, not here: dropping out of the
     * foreground while the projection is still alive is exactly what Android 14
     * penalises.
     */
    private fun stopAsync() {
        StreamHub.updateStats { it.copy(running = false, urls = emptyList()) }

        val thread = Thread({
            stopEverything()
            stopSelf()
        }, "streamer-teardown")

        teardownThread = thread
        thread.start()
    }

    private fun stopEverything() = synchronized(teardownLock) {
        server?.stop()
        server = null

        engine?.shutdown()
        engine = null
        StreamHub.engine = null

        releaseLocks()

        StreamHub.updateStats { it.copy(running = false, urls = emptyList()) }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        // On the normal path the teardown thread has already finished — it is
        // what called stopSelf. This only actually waits when the system is
        // destroying the service out from under us, and there the cleanup has
        // to happen regardless.
        teardownThread?.join(3_000)
        teardownThread = null
        stopEverything()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- locks

    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:cpu").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi?.createWifiLock(mode, "$TAG:wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    // ---------------------------------------------------------- notification

    private fun startForegroundNotification(config: StreamConfig) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val url = NetInfo.primaryUrl(config.port) ?: "sin red"
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Transmitiendo pantalla")
            .setContentText(url)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Detener", stop)
                    .build(),
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "StreamerService"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 42

        /** Long, but not infinite: a stuck stream should not hold the CPU forever. */
        private const val WAKELOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L

        const val ACTION_START = "com.webanywhere.streamer.START"
        const val ACTION_STOP = "com.webanywhere.streamer.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, StreamerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StreamerService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
