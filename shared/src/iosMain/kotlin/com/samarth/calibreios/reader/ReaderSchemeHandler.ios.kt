package com.samarth.calibreios.reader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.WebKit.WKURLSchemeHandlerProtocol
import platform.WebKit.WKURLSchemeTaskProtocol
import platform.WebKit.WKWebView
import platform.darwin.NSObject

/**
 * Serves the reader's assets and the book itself over a private URL scheme.
 *
 * Using a scheme handler rather than `loadFileURL` keeps Kotlin in control of
 * every byte the web view sees. It also sidesteps WKWebView's restrictions on
 * `file://` sub-resource fetches, which is what an EPUB reader does constantly,
 * and means the downloaded book never needs to live inside the app bundle.
 *
 *   epubapp://reader/<path>  -> preloaded web assets
 *   epubapp://book/current   -> the EPUB on disk at [bookPath]
 */
@OptIn(ExperimentalForeignApi::class)
internal class ReaderSchemeHandler(
    private val assets: Map<String, ByteArray>,
    private var bookPath: String? = null,
) : NSObject(), WKURLSchemeHandlerProtocol {

    fun setBookPath(path: String?) {
        bookPath = path
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, startURLSchemeTask: WKURLSchemeTaskProtocol) {
        val url = startURLSchemeTask.request.URL
        if (url == null) {
            startURLSchemeTask.didFailWithError(notFound("no url"))
            return
        }

        val host = url.host ?: ""
        val path = (url.path ?: "").trimStart('/')

        val data: NSData? = when (host) {
            "reader" -> assets[path.ifEmpty { "index.html" }]?.toNSData()
            "book" -> bookPath?.let { NSData.dataWithContentsOfFile(it) }
            else -> null
        }

        if (data == null) {
            startURLSchemeTask.didFailWithError(notFound("$host/$path"))
            return
        }

        val mime = if (host == "book") "application/epub+zip" else ReaderAssets.mimeTypeOf(path)
        val response = NSHTTPURLResponse(
            uRL = url,
            statusCode = 200,
            HTTPVersion = "HTTP/1.1",
            headerFields = mapOf(
                "Content-Type" to mime,
                "Content-Length" to data.length.toString(),
                // The book is fetched by the engine from page context.
                "Access-Control-Allow-Origin" to "*",
            ),
        )
        startURLSchemeTask.didReceiveResponse(response)
        startURLSchemeTask.didReceiveData(data)
        startURLSchemeTask.didFinish()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, stopURLSchemeTask: WKURLSchemeTaskProtocol) {
        // Everything is served synchronously from memory or a local file, so
        // there is nothing in flight to cancel.
    }

    private fun notFound(what: String) = NSError.errorWithDomain(
        domain = "com.samarth.calibreios.reader",
        code = 404,
        userInfo = mapOf("resource" to what),
    )
}

/** Route constants. Kept outside the handler: an ObjC subclass cannot hold companion fields. */
internal object ReaderRoutes {
    const val SCHEME = "epubapp"
    const val INDEX_URL = "epubapp://reader/index.html"
    const val BOOK_URL = "epubapp://book/current"
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned {
        NSData.create(bytes = it.addressOf(0), length = size.toULong())
    }

internal fun nsUrl(s: String): NSURL? = NSURL.URLWithString(s)
