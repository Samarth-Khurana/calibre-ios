package com.samarth.calibreios.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Placeholder; Android has no UI in V1. The equivalent is `NsdManager`. */
actual class ServerDiscovery actual constructor() {
    actual val found: StateFlow<List<DiscoveredServer>> = MutableStateFlow(emptyList())
    actual fun start() = Unit
    actual fun stop() = Unit
}
