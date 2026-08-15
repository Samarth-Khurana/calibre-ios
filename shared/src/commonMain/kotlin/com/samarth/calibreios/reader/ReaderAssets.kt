package com.samarth.calibreios.reader

import calibre_ios.shared.generated.resources.Res

/**
 * The reader's web assets, loaded out of Compose resources into memory.
 *
 * Compose resources are read asynchronously, but a URL scheme handler has to
 * answer synchronously, so everything is preloaded once before the web view is
 * created. The whole payload is ~240 KB (foliate-js minus the PDF stack, which
 * is dynamically imported and therefore droppable for an EPUB-only app), so
 * holding it in memory is cheaper than the plumbing to stream it.
 */
object ReaderAssets {

    private const val ROOT = "files/reader"

    /** Every file the reader needs, relative to [ROOT]. */
    private val FILES = listOf(
        "index.html",
        "bridge.js",
        // Spike fixture: calibre's own 55 KB sample book. Remove once the app
        // fetches real books from the content server.
        "fixture.epub",
        "foliate/view.js",
        "foliate/epubcfi.js",
        "foliate/progress.js",
        "foliate/overlayer.js",
        "foliate/text-walker.js",
        "foliate/epub.js",
        "foliate/paginator.js",
        "foliate/fixed-layout.js",
        "foliate/tts.js",
        "foliate/search.js",
        "foliate/footnotes.js",
        "foliate/uri-template.js",
        "foliate/vendor/zip.js",
        "foliate/vendor/fflate.js",
    )

    private var cache: Map<String, ByteArray>? = null

    suspend fun load(): Map<String, ByteArray> =
        cache ?: FILES.associateWith { Res.readBytes("$ROOT/$it") }.also { cache = it }

    fun mimeTypeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html"
        path.endsWith(".js") -> "text/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".epub") -> "application/epub+zip"
        else -> "application/octet-stream"
    }
}
