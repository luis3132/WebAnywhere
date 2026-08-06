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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Every start and stop runs here, in order, one at a time.
     *
     * Neither operation is quick — `StreamServer.stop` drains in-flight requests
     * for up to two seconds and the media stack blocks while it releases
     * encoders — so neither can run on the main thread. But once they are off
     * it, they can also overlap, and that is what broke restarting: a start
     * arriving while the previous session was still tearing down found `engine`
     * not yet null, gave up, and was then finished off by the teardown's own
     * `stopSelf`. From the user's side the consent dialog was accepted and
     * nothing happened.
     *
     * A single worker makes the order the obvious one: a start queued behind a
     * stop begins after the stop has finished.
     */
    private val lifecycle: ExecutorService =
        Executors.newSingleThreadExecutor { r -> Thread(r, "streamer-lifecycle") }

    /**
     * Starts asked for but not yet carried out. `stopEverything` reads it to
     * decide whether dropping out of the foreground is still the right thing to
     * do, because a queued start has already put the notification back up.
     */
    private val pendingStarts = AtomicInteger(0)

    @Volatile
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent, startId)
            ACTION_STOP -> stopAsync(startId)
        }
        return START_NOT_STICKY
    }

    /**
     * The user swiped the app away from the recents list. That is an explicit
     * "close this", not the app-switching the mirror is meant to survive — so
     * the stream, the notification and the service go with it. Without this the
     * projection kept running with no window left to stop it from.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "task removed, shutting the stream down")
        stopAsync(STOP_ANY_START_ID)
        super.onTaskRemoved(rootIntent)
    }

    private fun handleStart(intent: Intent, startId: Int) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData == null) {
            Log.e(TAG, "no projection consent payload")
            stopSelf(startId)
            return
        }

        val config = StreamHub.config

        stopping = false
        pendingStarts.incrementAndGet()

        // Has to happen here rather than on the worker: the system gives a
        // service started with startForegroundService a few seconds to enter the
        // foreground, and queueing behind a teardown could burn them. It also
        // has to happen before the projection token is redeemed — on Android 14+
        // the service must already be foreground with the mediaProjection type,
        // or the redemption throws.
        startForegroundNotification(config)

        val queued = submit {
            val started = try {
                startEverything(resultCode, resultData, config)
            } catch (e: Exception) {
                Log.e(TAG, "could not start the stream", e)
                StreamHub.updateStats { it.copy(lastError = "No se pudo iniciar: ${e.message}") }
                false
            }

            // Decremented before the cleanup, not after: `stopEverything` reads
            // it to decide whether the notification still belongs to somebody,
            // and a failed start no longer has any claim on it.
            pendingStarts.decrementAndGet()

            if (!started) {
                stopEverything()
                stopSelf(startId)
            }
        }

        if (!queued) {
            pendingStarts.decrementAndGet()
            stopSelf(startId)
        }
    }

    /** Returns false when the service is already on its way out. */
    private fun submit(task: () -> Unit): Boolean =
        runCatching { lifecycle.execute(task) }
            .onFailure { Log.w(TAG, "lifecycle worker is gone, dropping the request", it) }
            .isSuccess

    private fun startEverything(
        resultCode: Int,
        resultData: Intent,
        config: StreamConfig,
    ): Boolean {
        if (engine != null) return true

        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "projection rejected", e)
            StreamHub.updateStats { it.copy(lastError = "Proyección rechazada: ${e.message}") }
            return false
        }

        if (projection == null) {
            Log.e(TAG, "projection consent produced no session")
            return false
        }

        // Before anything can publish into them: whatever the last run left in
        // the rings sits on sequence numbers this run is about to reuse.
        StreamHub.beginSession()

        acquireLocks()

        engine = StreamerEngine(
            context = applicationContext,
            projection = projection,
            config = config,
            // Revoking the capture from the system UI used to leave the service
            // running with a notification, two locks and a bound port for a
            // session that no longer existed — and holding an engine, which
            // made every later start a no-op.
            onProjectionLost = {
                Log.i(TAG, "projection lost, stopping the service")
                StreamHub.updateStats {
                    it.copy(lastError = "La captura de pantalla se detuvo desde el sistema.")
                }
                stopAsync(STOP_ANY_START_ID)
            },
        ).also { StreamHub.engine = it }

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
        return true
    }

    /**
     * The flag the UI watches is flipped straight away; the real teardown is
     * queued. The notification is dismissed there too, not here: dropping out of
     * the foreground while the projection is still alive is exactly what
     * Android 14 penalises.
     */
    private fun stopAsync(startId: Int) {
        if (stopping) return
        stopping = true

        StreamHub.updateStats { it.copy(running = false, urls = emptyList()) }

        submit {
            stopEverything()
            // Passing the id rather than calling the bare stopSelf(): if a new
            // start has arrived in the meantime this becomes a no-op instead of
            // killing the session the user has just asked for.
            if (startId == STOP_ANY_START_ID) stopSelf() else stopSelf(startId)
        }
    }

    /** Runs on [lifecycle], so it is never concurrent with a start. */
    private fun stopEverything() {
        // The engine goes out of reach first: an HTTP handler picking it up now
        // would be acquiring pipelines on a session that is being dismantled.
        StreamHub.engine = null

        runCatching { server?.stop() }.onFailure { Log.w(TAG, "server stop failed", it) }
        server = null

        runCatching { engine?.shutdown() }.onFailure { Log.w(TAG, "engine shutdown failed", it) }
        engine = null

        releaseLocks()

        StreamHub.updateStats { it.copy(running = false, urls = emptyList()) }

        // A start already queued has put the notification back up; tearing it
        // down here would leave that session foreground-less.
        if (pendingStarts.get() == 0) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    override fun onDestroy() {
        // On the normal path the teardown has already run — it is what called
        // stopSelf. This matters when the system destroys the service out from
        // under us, and there the cleanup has to happen regardless.
        stopping = true
        pendingStarts.set(0)
        submit { stopEverything() }
        lifecycle.shutdown()
        runCatching { lifecycle.awaitTermination(TEARDOWN_TIMEOUT_S, TimeUnit.SECONDS) }
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

        /**
         * Stop unconditionally, for the paths with no start id of their own —
         * the projection dying, the task being swiped away. Any negative value
         * would do; -1 just reads as "not a real id".
         */
        private const val STOP_ANY_START_ID = -1

        /** How long onDestroy waits for a teardown already in flight. */
        private const val TEARDOWN_TIMEOUT_S = 3L

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
