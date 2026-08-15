package com.samarth.calibreios.discovery

import kotlinx.coroutines.flow.StateFlow

/** A calibre content server found by Bonjour. */
data class DiscoveredServer(
    val name: String,
    /** Usually the `.local` form, which survives a DHCP address change. */
    val host: String,
    val port: Int,
)

/**
 * Finds calibre servers on the local network.
 *
 * Two jobs, and the second is the reason this exists at all:
 *
 * 1. **Discovery.** calibre advertises over Bonjour by default -- verified on
 *    the user's own server, which logs "OPDS feeds advertised via BonJour" at
 *    startup. This resolves the `.local` hostname, which survives the router
 *    handing out a different address (spec risk #2), so the user never types
 *    an IP that later goes stale.
 *
 * 2. **Triggering the Local Network permission prompt.** An outbound HTTP
 *    request to a LAN address gets *silently blocked* -- iOS reports
 *    `-1009 "The Internet connection appears to be offline"` with Wi-Fi up, and
 *    the app never appears in Settings › Privacy › Local Network, so there is
 *    nothing for the user to switch on. Starting a Bonjour browse is what
 *    reliably raises the prompt. Observed on iPhone 17 Pro / iOS 26.
 */
expect class ServerDiscovery() {
    val found: StateFlow<List<DiscoveredServer>>
    fun start()
    fun stop()
}
