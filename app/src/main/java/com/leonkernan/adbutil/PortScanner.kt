package com.leonkernan.adbutil

import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the IPv4 address of the WiFi station interface (wlan0, wlan1, …), if any.
 * Other interfaces (loopback, ethernet, AP/hotspot, USB, …) are ignored on purpose.
 */
fun getWifiIPv4Address(): InetAddress? {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces()
    } catch (e: IOException) {
        return null
    }
    val wlanInterfaces = interfaces.toList()
        .filter { it.name.startsWith("wlan") }
        .sortedBy { it.name }
    for (iface in wlanInterfaces) {
        if (!runCatching { iface.isUp }.getOrDefault(false)) continue
        if (runCatching { iface.isLoopback }.getOrDefault(true)) continue
        val addr = iface.inetAddresses.toList().firstOrNull { it is Inet4Address }
        if (addr != null) return addr
    }
    return null
}

/** True if nothing is currently bound to [port] on [address]. */
private fun isPortFree(address: InetAddress, port: Int): Boolean = try {
    ServerSocket(port, 1, address).use { true }
} catch (e: IOException) {
    false
}

data class PortScanResult(
    val address: InetAddress,
    val freePorts: List<Int>,
    val busyPorts: List<Int>,
    val source: String,
)

/**
 * Tests each of [ports] on [address] and splits them into free (this device can bind a
 * [ServerSocket] to it) and busy. Unlike a range scan, [ports] is meant to be a short,
 * explicit candidate list — e.g. the car firewall's own TCP whitelist from [CarFirewall] —
 * never an arbitrary sweep of the port space.
 */
fun scanPorts(
    address: InetAddress,
    ports: List<Int>,
    source: String,
    parallelism: Int = 16,
): PortScanResult {
    val executor = Executors.newFixedThreadPool(parallelism.coerceIn(1, 64))
    val freePorts = Collections.synchronizedList(mutableListOf<Int>())
    val busyPorts = Collections.synchronizedList(mutableListOf<Int>())
    try {
        val futures = ports.map { port ->
            executor.submit {
                if (isPortFree(address, port)) freePorts.add(port) else busyPorts.add(port)
            }
        }
        for (f in futures) {
            try {
                f.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // ignore individual port timeout/errors, keep going
            }
        }
    } finally {
        executor.shutdownNow()
    }
    return PortScanResult(
        address = address,
        freePorts = freePorts.sorted(),
        busyPorts = busyPorts.sorted(),
        source = source,
    )
}
