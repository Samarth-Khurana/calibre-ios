package com.samarth.calibreios.discovery

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.darwin.NSObject
import platform.posix.AF_INET
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.memcpy
import platform.posix.sockaddr
import platform.posix.sockaddr_storage

/**
 * Bonjour discovery of calibre content servers. See the `expect` declaration
 * for why this carries the Local Network permission prompt as well.
 */
@OptIn(ExperimentalForeignApi::class)
actual class ServerDiscovery actual constructor() {

    private val _found = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    actual val found: StateFlow<List<DiscoveredServer>> = _found

    // calibre advertises OPDS over _http._tcp; _calibre._tcp is the wireless
    // device-driver service. Browsing both costs nothing and either one is
    // enough to raise the permission prompt.
    private val types = listOf("_calibre._tcp.", "_http._tcp.")

    // Both the browsers and the services being resolved must be retained.
    // NSNetService resolution is asynchronous and the object is held weakly by
    // the framework -- drop the reference and resolution silently never
    // completes, which looks identical to "nothing on the network".
    private val browsers = mutableListOf<NSNetServiceBrowser>()
    private val resolving = mutableListOf<NSNetService>()

    private val serviceDelegate = object : NSObject(), NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            runCatching {
                val port = sender.port.toInt()
                if (port <= 0) return

                // hostName is NOT trustworthy. calibre registers its service
                // under a MAC-derived name -- "Unknown_42:18:70:03:f8:4d.local"
                // -- whose colons make it unusable as a URL host: the parser
                // reads the first colon as a port separator. Prefer the
                // resolved numeric address, and accept hostName only when it
                // actually looks like a hostname.
                val host = sender.usableHostname() ?: sender.firstIPv4() ?: return

                val entry = DiscoveredServer(
                    name = sender.name.cleanServiceName().ifBlank { host },
                    host = host,
                    port = port,
                )
                if (_found.value.none { it.host == entry.host && it.port == entry.port }) {
                    _found.value = _found.value + entry
                }
                resolving.remove(sender)
            }
        }

        override fun netService(sender: NSNetService, didNotResolve: Map<Any?, *>) {
            resolving.remove(sender)
        }
    }

    private val browserDelegate = object : NSObject(), NSNetServiceBrowserDelegateProtocol {
        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            runCatching {
                // A found service carries only a name -- host and port need a
                // second, asynchronous resolve step.
                didFindService.delegate = serviceDelegate
                resolving += didFindService
                didFindService.resolveWithTimeout(5.0)
            }
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) {
            runCatching {
                _found.value = _found.value.filterNot { it.name == didRemoveService.name }
            }
        }

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didNotSearch: Map<Any?, *>,
        ) {
            // Most likely the Local Network permission was refused. Nothing to
            // do here: the HTTP path reports it with an actionable message.
        }
    }

    actual fun start() {
        if (browsers.isNotEmpty()) return
        types.forEach { type ->
            val browser = NSNetServiceBrowser()
            browser.delegate = browserDelegate
            browser.searchForServicesOfType(type, inDomain = "local.")
            browsers += browser
        }
    }

    actual fun stop() {
        browsers.forEach { it.stop() }
        browsers.clear()
        resolving.clear()
    }
}

/** `hostName` only if it is usable as a URL host. */
private fun NSNetService.usableHostname(): String? {
    val name = hostName?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
    // Colons mean either an IPv6 literal or, in calibre's case, a MAC-derived
    // name. Neither belongs in a host position unbracketed, and the numeric
    // address is a better answer than guessing which it is.
    return if (name.contains(':')) null else name
}

/**
 * First resolved IPv4 address, as a dotted-quad string.
 *
 * IPv4 only on purpose: it is what the calibre server binds by default, and it
 * sidesteps needing to bracket a literal in the URL.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSNetService.firstIPv4(): String? {
    val list = addresses ?: return null
    for (element in list) {
        val data = element as? NSData ?: continue
        val resolved = memScoped {
            val storage = alloc<sockaddr_storage>()
            memcpy(storage.ptr, data.bytes, data.length)
            if (storage.ss_family.toInt() != AF_INET) return@memScoped null
            val buffer = allocArray<ByteVar>(NI_MAXHOST)
            val rc = getnameinfo(
                storage.ptr.reinterpret<sockaddr>(),
                data.length.toUInt(),
                buffer, NI_MAXHOST.toUInt(),
                null, 0u,
                NI_NUMERICHOST,
            )
            if (rc == 0) buffer.toKString().takeIf { it.isNotBlank() } else null
        }
        if (resolved != null) return resolved
    }
    return null
}

/** "Books in calibre (on Unknown_… port 8080)" -> "Books in calibre". */
private fun String.cleanServiceName(): String =
    substringBefore(" (on ").trim()
