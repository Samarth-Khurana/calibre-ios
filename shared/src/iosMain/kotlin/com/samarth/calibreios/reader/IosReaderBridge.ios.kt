package com.samarth.calibreios.reader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import platform.Foundation.NSURLRequest
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * Drives foliate-js inside a WKWebView from Kotlin/Native. No Swift.
 *
 * The delegate/handler conformance below is the piece that was flagged as the
 * main interop risk when this architecture was chosen (#7); it resolves to an
 * ordinary `NSObject()` subclass implementing an Obj-C protocol.
 *
 * Two hazards worth knowing, both documented on the Apple side and both easy to
 * hit from Kotlin:
 *
 *  1. The message handler must be retained by something -- WKUserContentController
 *     holds it weakly in practice, so it is kept as a field here.
 *  2. An exception thrown out of a handler callback crosses the Obj-C boundary
 *     and terminates the process rather than propagating. Every callback below
 *     therefore swallows and reports instead of throwing.
 */
@OptIn(ExperimentalForeignApi::class)
class IosReaderBridge(assets: Map<String, ByteArray>) {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    // replay matters: the engine emits "ready" as soon as bridge.js loads,
    // which can precede the collector attaching. A replay=0 SharedFlow would
    // drop it and the reader would sit blank forever.
    private val _events = MutableSharedFlow<ReaderEvent>(replay = 32, extraBufferCapacity = 64)

    /** Everything the engine reports. The only channel results come back on. */
    val events: SharedFlow<ReaderEvent> = _events

    private val schemeHandler = ReaderSchemeHandler(assets)

    // Retained deliberately: see hazard (1) above.
    private val messageHandler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(
            userContentController: WKUserContentController,
            didReceiveScriptMessage: WKScriptMessage,
        ) {
            try {
                val body = didReceiveScriptMessage.body
                val raw = when (body) {
                    is String -> body
                    else -> body?.toString() ?: return
                }
                _events.tryEmit(decode(raw))
            } catch (t: Throwable) {
                // Never let this escape into Obj-C.
                _events.tryEmit(ReaderEvent.Error("messageHandler", t.message ?: "unknown"))
            }
        }
    }

    val webView: WKWebView = run {
        val controller = WKUserContentController()
        controller.addScriptMessageHandler(messageHandler, name = MESSAGE_NAME)

        val config = WKWebViewConfiguration().apply {
            userContentController = controller
            websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            setURLSchemeHandler(schemeHandler, forURLScheme = ReaderRoutes.SCHEME)
        }
        WKWebView(frame = CGRectZero.readValue(), configuration = config)
    }

    fun load() {
        nsUrl(ReaderRoutes.INDEX_URL)?.let {
            webView.loadRequest(NSURLRequest.requestWithURL(it))
        }
    }

    /** Point the `epubapp://book/current` route at a downloaded EPUB. */
    fun setBookPath(path: String) = schemeHandler.setBookPath(path)

    /** Fire a command. Results arrive on [events], never as a return value. */
    fun send(command: ReaderCommand) {
        // `void (...)` matters: most commands are async and therefore evaluate to
        // a Promise, which WKWebView cannot marshal back to native ("result of an
        // unsupported type"). The reply is the event stream, so the return value
        // is deliberately thrown away.
        webView.evaluateJavaScript("void (${command.toJs()});") { _, error ->
            if (error != null) {
                _events.tryEmit(
                    ReaderEvent.Error("evaluateJavaScript", error.localizedDescription)
                )
            }
        }
    }

    internal fun decode(raw: String): ReaderEvent =
        try {
            json.decodeFromString(ReaderEvent.serializer(), raw)
        } catch (t: Throwable) {
            ReaderEvent.Error("decode", "${t.message}: ${raw.take(200)}")
        }

}

private const val MESSAGE_NAME = "reader"
