package com.webanywhere.streamer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.webanywhere.streamer.ImageQuality
import com.webanywhere.streamer.Quality
import com.webanywhere.streamer.StreamConfig
import com.webanywhere.streamer.StreamHub
import com.webanywhere.streamer.net.LinkQuality
import com.webanywhere.streamer.net.Qr
import kotlinx.coroutines.delay
import java.util.Locale

private val Accent = Color(0xFF7CE7C7)
private val Background = Color(0xFF101014)
private val CardBg = Color(0xFF1A1A20)

@Composable
fun StreamerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Background,
            background = Background,
            surface = CardBg,
            onSurface = Color(0xFFE8E8EC),
        ),
        content = content,
    )
}

@Composable
fun StreamerScreen(onStart: () -> Unit, onStop: () -> Unit) {
    val stats by StreamHub.stats.collectAsStateWithLifecycle()
    val clients by StreamHub.clients.collectAsStateWithLifecycle()
    val probes by StreamHub.probes.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // targetSdk 36 forces edge-to-edge: the window now spans the
                // whole screen, so the status and navigation bars have to be
                // discounted here or they sit on top of the content.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "WebAnywhere Streamer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Accent,
            )

            stats.lastError?.let { error ->
                InfoCard {
                    Text("Error", fontWeight = FontWeight.Bold, color = Color(0xFFFF8A80))
                    Text(error, fontSize = 13.sp)
                }
            }

            if (stats.running) {
                AddressCard(stats.urls)
            } else {
                InfoCard {
                    Text(
                        "Pulsa iniciar y concede el permiso de captura. " +
                            "Después abre la dirección que aparezca aquí desde el navegador del vehículo.",
                        fontSize = 14.sp,
                    )
                }
            }

            Button(
                onClick = if (stats.running) onStop else onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    text = if (stats.running) "Detener transmisión" else "Iniciar transmisión",
                    fontWeight = FontWeight.Bold,
                )
            }

            LinkCard()

            QualityCard(enabled = !stats.running)

            StatsCard(stats)

            if (clients.isNotEmpty()) {
                InfoCard {
                    SectionTitle("Clientes conectados")
                    clients.forEach { client ->
                        Text(
                            "${client.host} · ${client.profile}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                        if (client.userAgent.isNotEmpty()) {
                            Text(
                                client.userAgent,
                                fontSize = 11.sp,
                                color = Color(0xFF9A9AA6),
                            )
                        }
                    }
                }
            }

            if (probes.isNotEmpty()) {
                InfoCard {
                    SectionTitle("Diagnóstico recibido de /probe")
                    probes.forEach { probe ->
                        Text(probe.host, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "MSE: ${probe.hasMediaSource.yesNo()} · " +
                                "WebSocket: ${probe.hasWebSocket.yesNo()} · " +
                                "HLS nativo: ${probe.nativeHls.ifEmpty { "no" }}",
                            fontSize = 12.sp,
                        )
                        if (probe.supportedCodecs.isNotEmpty()) {
                            Text(
                                "Codecs: ${probe.supportedCodecs.joinToString(", ")}",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Accent,
                            )
                        }
                        Text(
                            probe.userAgent,
                            fontSize = 10.sp,
                            color = Color(0xFF9A9AA6),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            InfoCard {
                SectionTitle("Rutas útiles")
                Mono("/            reproductor (elige perfil solo)")
                Mono("/?p=mjpeg    fuerza MJPEG")
                Mono("/?p=mse      fuerza fMP4 + MSE")
                Mono("/probe       diagnóstico del navegador")
                Mono("/status      estado en JSON")
            }
        }
    }
}

@Composable
private fun AddressCard(urls: List<String>) {
    val primary = urls.firstOrNull()

    InfoCard {
        SectionTitle("Abre esto en el vehículo")

        if (primary == null) {
            Text("Sin dirección IP. Conecta el Wi-Fi o activa el punto de acceso.", fontSize = 14.sp)
            return@InfoCard
        }

        Text(
            text = primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Accent,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        // Typing an IP on a car touchscreen is genuinely painful.
        val qr = remember(primary) { Qr.bitmap(primary, 512) }
        if (qr != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Código QR con la dirección",
                    modifier = Modifier
                        .size(180.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                )
            }
        }

        if (urls.size > 1) {
            Spacer(Modifier.height(4.dp))
            Text("Otras interfaces:", fontSize = 12.sp, color = Color(0xFF9A9AA6))
            urls.drop(1).forEach { Mono(it) }
        }
    }
}

/**
 * The link is the limit nobody thinks to check. A 2.4 GHz hotspot carries a
 * fraction of what the encoder will happily produce, and the result looks like
 * a broken app rather than a saturated radio.
 */
@Composable
private fun LinkCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(LinkQuality.inspect(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            report = LinkQuality.inspect(context)
        }
    }

    val accent = when (report.severity) {
        LinkQuality.Severity.OK -> Accent
        LinkQuality.Severity.WARN -> Color(0xFFFFB86C)
        LinkQuality.Severity.BAD -> Color(0xFFFF8A80)
    }

    InfoCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (report.severity) {
                    LinkQuality.Severity.OK -> "●"
                    else -> "▲"
                },
                color = accent,
                fontSize = 14.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = report.headline,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = accent,
            )
        }

        if (report.signalLevel >= 0) {
            Text(
                "Señal ${report.signalLevel} de ${report.maxSignalLevel}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF9A9AA6),
            )
        }

        Text(report.detail, fontSize = 12.sp, color = Color(0xFF9A9AA6))
    }
}

@Composable
private fun QualityCard(enabled: Boolean) {
    val config by StreamHub.configFlow.collectAsStateWithLifecycle()

    InfoCard {
        SectionTitle("Calidad")

        Text("Resolución máxima", fontSize = 12.sp, color = Color(0xFF9A9AA6))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { quality ->
                Choice(
                    label = quality.label,
                    selected = config.quality == quality,
                    enabled = enabled,
                ) { StreamHub.config = config.copy(quality = quality) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Fotogramas por segundo", fontSize = 12.sp, color = Color(0xFF9A9AA6))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60).forEach { fps ->
                Choice(
                    label = "$fps",
                    selected = config.videoFps == fps,
                    enabled = enabled,
                ) { StreamHub.config = config.copy(videoFps = fps) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Calidad de imagen", fontSize = 12.sp, color = Color(0xFF9A9AA6))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ImageQuality.entries.forEach { image ->
                Choice(
                    label = image.label,
                    selected = config.imageQuality == image,
                    enabled = enabled,
                ) { StreamHub.config = config.copy(imageQuality = image) }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = estimateLabel(config),
            fontSize = 12.sp,
            color = Accent,
            fontFamily = FontFamily.Monospace,
        )

        Text(
            text = if (!enabled) {
                "Detén la transmisión para cambiar la calidad."
            } else if (config.imageQuality == ImageQuality.UNCAPPED) {
                "Sin tope propio: se pide al codificador todo lo que declare. " +
                    "Si el enlace no da abasto se verá a tirones, no borroso — " +
                    "en ese caso baja un escalón."
            } else {
                "Es una red local, no internet: el bitrate es alto a propósito."
            },
            fontSize = 11.sp,
            color = Color(0xFF9A9AA6),
        )
    }
}

/**
 * Upper bound, not a prediction: the capture is scaled to the screen's aspect
 * ratio, so a portrait phone fills far fewer pixels than the budget allows. The
 * figure that matters shows up in Estado once it is running.
 */
private fun estimateLabel(config: StreamConfig): String {
    val bpp = config.imageQuality.bitsPerPixel
        ?: return "lo que declare el codificador · ${config.quality.label} a ${config.videoFps} fps"

    val mbps = config.videoWidth.toDouble() * config.videoHeight * config.videoFps *
        bpp / 1_000_000.0
    return String.format(
        Locale.ROOT,
        "hasta ≈ %.0f Mbps en %s a %d fps",
        mbps,
        config.quality.label,
        config.videoFps,
    )
}

@Composable
private fun Choice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Accent,
            selectedLabelColor = Background,
        ),
    )
}

@Composable
private fun StatsCard(stats: StreamHub.Stats) {
    InfoCard {
        SectionTitle("Estado")
        StatRow("Captura", if (stats.captureWidth > 0) "${stats.captureWidth}×${stats.captureHeight}" else "inactiva")
        if (stats.targetFps > 0) {
            StatRow(
                "Fotogramas/s",
                String.format(Locale.ROOT, "%.1f de %d", stats.measuredFps, stats.targetFps),
            )
            if (stats.requestedFps > stats.targetFps) {
                Text(
                    "El codificador no admite ${stats.requestedFps} fps a esta resolución.",
                    fontSize = 11.sp,
                    color = Color(0xFFFFB86C),
                )
            }
            StatRow("Bitrate", "${stats.measuredKbps} de ${stats.videoBitrateKbps} kbps")
        }
        StatRow("Clientes fMP4", stats.fmp4Clients.toString())
        StatRow("Clientes MJPEG", stats.mjpegClients.toString())
        StatRow("Segmentos de vídeo", stats.videoSegments.toString())
        StatRow("Fotogramas JPEG", stats.jpegFrames.toString())
        StatRow("JPEG descartados", stats.droppedJpegFrames.toString())
        StatRow("Audio", stats.audioAvailable.yesNo())
        StatRow("Codec", StreamHub.video.codec ?: "—")
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Color(0xFF9A9AA6))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFB8B8C4))
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

private fun Boolean.yesNo(): String = if (this) "sí" else "no"
