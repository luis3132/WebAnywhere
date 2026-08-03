package com.webanywhere.streamer.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Inspects the link the car will actually be pulling video over, because the
 * bitrate the encoder is willing to produce and the bitrate the link can carry
 * are unrelated numbers — and when the link loses, the symptom is stutter, not
 * softness, which is much easier to misdiagnose.
 *
 * The two topologies fail in different ways:
 *  - **Joined to a Wi-Fi network**: band and signal are readable, so the
 *    warning can be specific.
 *  - **Phone as hotspot**: the band is *not* readable by an ordinary app.
 *    `getSoftApConfiguration` is gated behind `NETWORK_SETTINGS`, which is a
 *    system permission. It is attempted anyway — when it works it is the exact
 *    answer — but the honest fallback is to tell the user where to look.
 *
 * Note that frequency, RSSI and link speed are *not* subject to the location
 * permission redaction that hides SSID and BSSID, so none of this needs
 * location access.
 */
object LinkQuality {

    enum class Mode { WIFI, HOTSPOT, OTHER, NONE }

    enum class Severity { OK, WARN, BAD }

    data class Report(
        val mode: Mode,
        val severity: Severity,
        val headline: String,
        val detail: String,
        /** −1 when unknown. */
        val linkSpeedMbps: Int = -1,
        val signalLevel: Int = -1,
        val maxSignalLevel: Int = 4,
    )

    fun inspect(context: Context): Report {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val onWifi = connectivity?.let { manager ->
            val network = manager.activeNetwork ?: return@let false
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: false

        if (onWifi) return inspectWifi(context, connectivity)

        val addresses = NetInfo.addresses()
        if (addresses.any { it.isLikelyHotspot }) return inspectHotspot(context)
        if (addresses.isEmpty()) {
            return Report(
                mode = Mode.NONE,
                severity = Severity.BAD,
                headline = "Sin red",
                detail = "Conéctate a una Wi-Fi o activa el punto de acceso: " +
                    "el coche tiene que poder alcanzar al teléfono.",
            )
        }

        return Report(
            mode = Mode.OTHER,
            severity = Severity.WARN,
            headline = "Red no reconocida",
            detail = "Hay dirección IP pero no es Wi-Fi ni punto de acceso. " +
                "Si es datos móviles, el coche no podrá conectarse.",
        )
    }

    // ------------------------------------------------------------------ wifi

    private fun inspectWifi(context: Context, connectivity: ConnectivityManager?): Report {
        val info = wifiInfo(context, connectivity)
            ?: return Report(
                mode = Mode.WIFI,
                severity = Severity.WARN,
                headline = "Wi-Fi conectada",
                detail = "No se pudo leer la banda ni la señal del enlace.",
            )

        val frequency = runCatching { info.frequency }.getOrDefault(0)
        val linkSpeed = runCatching { info.linkSpeed }.getOrDefault(-1)
        val rssi = runCatching { info.rssi }.getOrDefault(0)
        val level = signalLevel(context, rssi)
        val band = bandOf(frequency)

        val parts = ArrayList<String>()
        if (band != null) parts.add(band)
        if (linkSpeed > 0) parts.add("enlace $linkSpeed Mbps")
        if (rssi != 0) parts.add("$rssi dBm")

        val weakSignal = level in 0..1

        // 2.4 GHz is shared with everything and typically nets 20-30 Mbps of
        // real payload however good the signal is.
        val severity = when {
            weakSignal -> Severity.BAD
            frequency in 1..2_999 -> Severity.WARN
            linkSpeed in 1..49 -> Severity.WARN
            else -> Severity.OK
        }

        val detail = when {
            weakSignal ->
                "Señal débil. Acerca el teléfono al router o al coche; " +
                    "a esta potencia el vídeo se cortará."
            frequency in 1..2_999 ->
                "La banda de 2,4 GHz mueve unos 20-30 Mbps reales. " +
                    "Si va a tirones, baja la calidad de imagen."
            linkSpeed in 1..49 ->
                "El enlace negocia solo $linkSpeed Mbps. " +
                    "Puede quedarse corto para la calidad seleccionada."
            else ->
                "Enlace holgado para transmitir vídeo."
        }

        val standard = wifiStandard(info)
        val headline = "Wi-Fi · " + (parts.joinToString(" · ").ifEmpty { "conectada" }) +
            (if (standard != null) " · $standard" else "")

        return Report(
            mode = Mode.WIFI,
            severity = severity,
            headline = headline,
            detail = detail,
            linkSpeedMbps = linkSpeed,
            signalLevel = level,
            maxSignalLevel = maxSignalLevel(context),
        )
    }

    @Suppress("DEPRECATION")
    private fun wifiInfo(context: Context, connectivity: ConnectivityManager?): WifiInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivity?.activeNetwork
            val transport = network?.let { connectivity.getNetworkCapabilities(it)?.transportInfo }
            if (transport is WifiInfo) return transport
        }
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        return runCatching { wifi?.connectionInfo }.getOrNull()
    }

    private fun bandOf(frequencyMhz: Int): String? = when (frequencyMhz) {
        in 2_400..2_999 -> "2,4 GHz"
        in 4_900..5_999 -> "5 GHz"
        in 5_925..7_125 -> "6 GHz"
        else -> null
    }

    private fun wifiStandard(info: WifiInfo): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return when (runCatching { info.wifiStandard }.getOrDefault(0)) {
            4 -> "Wi-Fi 4"
            5 -> "Wi-Fi 5"
            6 -> "Wi-Fi 6"
            7 -> "Wi-Fi 6E"
            8 -> "Wi-Fi 7"
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun signalLevel(context: Context, rssi: Int): Int {
        if (rssi == 0) return -1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            runCatching { wifi?.calculateSignalLevel(rssi) ?: -1 }.getOrDefault(-1)
        } else {
            runCatching { WifiManager.calculateSignalLevel(rssi, 5) }.getOrDefault(-1)
        }
    }

    @Suppress("DEPRECATION")
    private fun maxSignalLevel(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 4
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        return runCatching { (wifi?.maxSignalLevel ?: 5) - 1 }.getOrDefault(4)
    }

    // --------------------------------------------------------------- hotspot

    /**
     * The band cannot be read here, and not for lack of trying:
     * `WifiManager.getSoftApConfiguration` is a `@SystemApi` gated behind
     * `NETWORK_SETTINGS`, so it is not even part of the public SDK. What the
     * app *can* tell is whether the phone is capable of a 5 GHz AP, which at
     * least narrows down what the user will find in Settings.
     */
    private fun inspectHotspot(context: Context): Report {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val supports5GHz = runCatching { wifi?.is5GHzBandSupported == true }.getOrDefault(false)

        val hint = if (supports5GHz) {
            "Este teléfono admite 5 GHz: si el coche también, cámbialo en " +
                "Ajustes → Punto de acceso → Banda AP y podrás subir la calidad."
        } else {
            "Este teléfono solo emite en 2,4 GHz como punto de acceso, " +
                "así que cuenta con 20-30 Mbps reales."
        }

        return Report(
            mode = Mode.HOTSPOT,
            severity = Severity.WARN,
            headline = "Punto de acceso activo · banda no legible",
            detail = "Android no deja a una app normal leer la banda del punto de " +
                "acceso. Compruébala en Ajustes → Punto de acceso. $hint",
        )
    }
}
