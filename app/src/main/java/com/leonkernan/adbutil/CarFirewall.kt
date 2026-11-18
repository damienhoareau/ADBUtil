package com.leonkernan.adbutil

import java.io.File

/**
 * Firewall quirks of the MG4/SAIC firmware, detected on the car itself.
 *
 * Mirrors the logic reverse-engineered for DiAuto-MG4's CarNetworkQuirks: the SAIC
 * `arp_update.sh` script installs an `iptables INPUT ... REJECT` after a whitelist of
 * TCP ports (6010, 8080, 30517, 30518, 5288 on known firmwares). Only those ports are
 * worth probing on this car — anything else is rejected before it reaches any service,
 * so testing it would be meaningless.
 */
object CarFirewall {
    private val SCRIPT_PATHS = listOf("/system/bin/arp_update.sh", "/vendor/bin/arp_update.sh")

    private val ACCEPT_TCP = Regex("""INPUT\s+-p\s+tcp\s+--dport\s+(\d+)\s+-j\s+ACCEPT""")
    private val DEFAULT_REJECT = Regex("""INPUT\s+-j\s+(REJECT|DROP)\b""")

    /** Ports known to be allowed on every observed SAIC/MG4 firmware (SWI68, SWI69, SWI132, SWI133). */
    val KNOWN_PORTS = listOf(30518, 30517, 8080, 6010, 5288)

    data class Firewall(val source: String, val rejectsByDefault: Boolean, val allowedTcp: List<Int>)

    /** Extracts the TCP whitelist and whether there is a default reject. Commented-out lines are ignored. */
    fun parseFirewall(text: String, source: String): Firewall? {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val allowed = lines.flatMap { ACCEPT_TCP.findAll(it).map { m -> m.groupValues[1].toInt() }.toList() }.distinct()
        val rejects = lines.any { DEFAULT_REJECT.containsMatchIn(it) }
        if (allowed.isEmpty() && !rejects) return null
        return Firewall(source, rejects, allowed)
    }

    private fun readFirewall(): Firewall? {
        for (path in SCRIPT_PATHS) {
            try {
                val f = File(path)
                if (f.isFile && f.canRead()) {
                    parseFirewall(f.readText(), path)?.let { return it }
                }
            } catch (e: Exception) {
                // ignore, try next path / fall back
            }
        }
        return null
    }

    /**
     * Ports worth probing on this car, with a short explanation of where the list came from.
     * Never returns an arbitrary range: either the firewall's own whitelist, or the set of
     * ports known to be allowed on every MG4/SAIC firmware seen so far.
     */
    fun allowedPorts(): Pair<List<Int>, String> {
        val fw = readFirewall()
        return when {
            fw != null && fw.rejectsByDefault && fw.allowedTcp.isNotEmpty() ->
                fw.allowedTcp.sorted() to "restrictive firewall read from ${fw.source}"
            fw != null ->
                KNOWN_PORTS to "firewall read from ${fw.source} has no default reject; using known MG4 ports"
            else ->
                KNOWN_PORTS to "arp_update.sh unreadable; using ports known to be allowed on other MG4 firmwares"
        }
    }
}
