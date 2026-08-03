package com.webanywhere.streamer.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Works out which addresses the car should be pointed at.
 *
 * Both supported topologies are covered: the phone joined to an existing
 * Wi-Fi network, and the phone acting as the access point. In the hotspot case
 * Android hands out `192.168.43.x` on an interface named `ap0` / `swlan0`
 * depending on vendor, so interface names are ranked rather than assumed.
 */
object NetInfo {

    private val PREFERRED_PREFIXES = listOf("ap", "swlan", "wlan", "rndis", "eth")

    data class Address(val iface: String, val ip: String) {
        val isLikelyHotspot: Boolean
            get() = ip.startsWith("192.168.43.") || iface.startsWith("ap") || iface.startsWith("swlan")
    }

    fun addresses(): List<Address> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .map { Address(iface.name, it.hostAddress ?: "") }
            }
            .filter { it.ip.isNotEmpty() }
            .sortedBy { addr -> rank(addr.iface) }
            .toList()
    }.getOrElse { emptyList() }

    fun urls(port: Int): List<String> = addresses().map { "http://${it.ip}:$port" }

    /** The one to show biggest in the UI and encode into the QR. */
    fun primaryUrl(port: Int): String? = urls(port).firstOrNull()

    private fun rank(iface: String): Int {
        val index = PREFERRED_PREFIXES.indexOfFirst { iface.startsWith(it) }
        return if (index >= 0) index else PREFERRED_PREFIXES.size
    }
}
