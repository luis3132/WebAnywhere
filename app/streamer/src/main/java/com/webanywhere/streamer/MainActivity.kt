package com.webanywhere.streamer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.webanywhere.streamer.service.StreamerService
import com.webanywhere.streamer.ui.StreamerScreen
import com.webanywhere.streamer.ui.StreamerTheme

class MainActivity : ComponentActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                StreamerService.start(this, result.resultCode, data)
            } else {
                StreamHub.updateStats { it.copy(lastError = "Permiso de captura denegado") }
            }
        }

    private val notificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The stream works either way; without it the user just gets no
            // ongoing notification to stop from.
            requestProjection()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The UI is dark whatever the system theme is, so the bars are forced
        // to their dark style: `enableEdgeToEdge()` on its own follows the
        // system and would paint dark icons over a dark background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        setContent {
            StreamerTheme {
                StreamerScreen(
                    onStart = ::onStartRequested,
                    onStop = { StreamerService.stop(this) },
                )
            }
        }
    }

    private fun onStartRequested() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
}
