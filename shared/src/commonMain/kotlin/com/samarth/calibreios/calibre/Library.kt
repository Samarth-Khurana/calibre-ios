package com.samarth.calibreios.calibre

import com.samarth.calibreios.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The on-device library: what calibre offers, what we hold, and getting from
 * one to the other.
 *
 * Offline-first (#8) — the catalogue is cached to disk, so the app opens and
 * shows books with the Mac asleep, which is the common case rather than an
 * edge case. Reaching the server is an *enrichment*, never a precondition.
 *
 * Storage is bounded manually (#15): nothing is evicted automatically, so a
 * book cannot vanish before a flight. Removing one deletes only the file — its
 * reading position is kept, so re-downloading returns you to where you were.
 */
class Library(
    private val storage: Storage,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {

    sealed interface ServerState {
        data object Unknown : ServerState
        data object Reachable : ServerState
        /** Not an error state. The Mac is usually asleep (#8). */
        data class Unreachable(val detail: String) : ServerState
    }

    /** Where a book is in the download queue. Absent means "not queued". */
    sealed interface Download {
        data object Queued : Download
        data class Running(val fraction: Float) : Download
        /** The partial file is kept, so retrying resumes rather than restarts. */
        data class Failed(val reason: String) : Download
    }

    private val _books = MutableStateFlow<List<CalibreBook>>(emptyList())
    val books: StateFlow<List<CalibreBook>> = _books

    private val _server = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _server

    private val _downloads = MutableStateFlow<Map<Int, Download>>(emptyMap())
    val downloads: StateFlow<Map<Int, Download>> = _downloads

    private val _bytesOnDisk = MutableStateFlow(0L)
    val bytesOnDisk: StateFlow<Long> = _bytesOnDisk

    /** True while more results are known to exist beyond what is loaded. */
    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore

    private var query: String? = null
    private var loaded = 0

    // ------------------------------------------------------------- config

    fun loadServer(): CalibreServer? =
        storage.readText(SERVER_FILE)?.let {
            runCatching { json.decodeFromString<CalibreServer>(it) }.getOrNull()
        }

    fun saveServer(server: CalibreServer) {
        storage.writeText(SERVER_FILE, json.encodeToString(server))
    }

    /** Drop a previous verdict so a retry does not display the old failure. */
    fun clearServerState() {
        _server.value = ServerState.Unknown
    }

    // ------------------------------------------------------------ catalogue

    /** Load the cached catalogue. Instant, works offline, may be stale. */
    fun loadCached() {
        val cached = storage.readText(CATALOGUE_FILE)
            ?.let { runCatching { json.decodeFromString<List<CalibreBook>>(it) }.getOrNull() }
        if (cached != null) _books.value = cached
        refreshDiskUsage()
    }

    /**
     * Load the first page for [search], replacing what is shown.
     *
     * Returns false when the server was unreachable — the caller shows that as
     * a state, not a failure. On failure the previous list is left alone rather
     * than blanked: stale results beat an empty screen.
     */
    suspend fun refresh(client: CalibreClient, search: String? = null): Boolean {
        query = search?.takeIf { it.isNotBlank() }
        loaded = 0
        return fetchPage(client, replace = true)
    }

    /** Append the next page. No-op when nothing further is known to exist. */
    suspend fun loadMore(client: CalibreClient): Boolean {
        if (!_hasMore.value) return true
        return fetchPage(client, replace = false)
    }

    private suspend fun fetchPage(client: CalibreClient, replace: Boolean): Boolean {
        val ids = client.search(query = query, offset = loaded, limit = PAGE)
            .getOrElse { error ->
                _server.value = ServerState.Unreachable(error.describe())
                return false
            }

        val page = client.books(ids).getOrElse { error ->
            _server.value = ServerState.Unreachable(error.describe())
            return false
        }

        _books.value = if (replace) page else _books.value + page
        loaded += ids.size
        // A short page means the server has nothing further; asking again would
        // return empty and spin.
        _hasMore.value = ids.size == PAGE
        _server.value = ServerState.Reachable

        // Only the unfiltered first page is cached -- that is what a cold,
        // offline start shows. Caching search results would make the offline
        // library depend on whatever happened to be typed last.
        if (replace && query == null) {
            storage.writeText(CATALOGUE_FILE, json.encodeToString(_books.value))
        }
        return true
    }

    // ------------------------------------------------------------- downloads

    fun localPath(server: CalibreServer, book: CalibreBook): String =
        storage.bookFile(server.libraryId, book.id)

    fun isDownloaded(server: CalibreServer, book: CalibreBook): Boolean {
        val path = localPath(server, book)
        if (!storage.exists(path)) return false
        // A partial file from an interrupted transfer is not a downloaded book.
        // Size is the only check available without opening the archive, and it
        // is enough: calibre reports the expected size in format_metadata.
        return book.sizeBytes <= 0 || storage.sizeOf(path) >= book.sizeBytes
    }

    // One worker, so several taps queue rather than competing for bandwidth.
    // Unbounded: a personal library is small, and silently dropping a request
    // the user explicitly made would be worse than a long queue.
    private val queue =
        Channel<Triple<CalibreClient, CalibreServer, CalibreBook>>(Channel.UNLIMITED)
    private var worker: Job? = null

    /**
     * Add [book] to the download queue.
     *
     * Idempotent: already-downloaded, queued or running books are ignored, so
     * tapping twice does not download twice. Re-enqueuing a *failed* book is
     * how a retry happens, and it resumes from the partial file.
     */
    fun enqueue(client: CalibreClient, server: CalibreServer, book: CalibreBook) {
        if (isDownloaded(server, book)) return
        when (_downloads.value[book.id]) {
            is Download.Queued, is Download.Running -> return
            else -> Unit
        }
        _downloads.value = _downloads.value + (book.id to Download.Queued)
        startWorker()
        queue.trySend(Triple(client, server, book))
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for ((client, server, book) in queue) {
                download(client, server, book)
            }
        }
    }

    private suspend fun download(
        client: CalibreClient,
        server: CalibreServer,
        book: CalibreBook,
    ) {
        val path = localPath(server, book)
        val have = if (storage.exists(path)) storage.sizeOf(path) else 0L
        // Guard against a stale partial larger than the book: appending to it
        // would produce a corrupt archive.
        val resumeFrom = if (book.sizeBytes in 1..<have) { storage.delete(path); 0L } else have

        _downloads.value = _downloads.value + (book.id to Download.Running(0f))

        val result = client.download(
            book = book,
            alreadyHave = resumeFrom,
            onProgress = { received, total ->
                if (total > 0) {
                    _downloads.value = _downloads.value +
                        (book.id to Download.Running(received.toFloat() / total))
                }
            },
            write = { bytes -> storage.append(path, bytes) },
        )

        result.fold(
            onSuccess = {
                _downloads.value = _downloads.value - book.id
                _server.value = ServerState.Reachable
                refreshDiskUsage()
            },
            onFailure = { error ->
                // The partial file is kept deliberately: that is what makes the
                // next attempt a resume rather than a restart.
                _downloads.value =
                    _downloads.value + (book.id to Download.Failed(error.describe()))
                _server.value = ServerState.Unreachable(error.describe())
                refreshDiskUsage()
            },
        )
    }

    /** Download now and wait — the "open this book" path. */
    suspend fun ensureDownloaded(
        client: CalibreClient,
        server: CalibreServer,
        book: CalibreBook,
    ): Result<String> {
        val path = localPath(server, book)
        if (isDownloaded(server, book)) return Result.success(path)
        download(client, server, book)
        return if (isDownloaded(server, book)) {
            Result.success(path)
        } else {
            val reason = (_downloads.value[book.id] as? Download.Failed)?.reason
                ?: "download did not complete"
            Result.failure(IllegalStateException(reason))
        }
    }

    // --------------------------------------------------------------- storage

    fun refreshDiskUsage() {
        _bytesOnDisk.value = storage.bookFiles().sumOf { storage.sizeOf(it) }
    }

    /**
     * Delete a downloaded file. **The reading position is deliberately kept** —
     * reclaiming space should not cost your place in the book (#15).
     */
    fun removeDownload(server: CalibreServer, book: CalibreBook) {
        storage.delete(localPath(server, book))
        _downloads.value = _downloads.value - book.id
        refreshDiskUsage()
    }

    fun removeAllDownloads() {
        storage.bookFiles().forEach { storage.delete(it) }
        _downloads.value = emptyMap()
        refreshDiskUsage()
    }

    private companion object {
        const val SERVER_FILE = "calibre-server.json"
        const val CATALOGUE_FILE = "calibre-catalogue.json"
        const val PAGE = 50
    }
}

/**
 * A human-readable reason, biased toward the honest one.
 *
 * Spec risk #1 said a denied Local Network permission is indistinguishable from
 * a sleeping Mac. **That turned out to be false, and the difference matters.**
 * iOS reports the denial as `NSURLErrorNotConnectedToInternet` (-1009,
 * "The Internet connection appears to be offline") even with Wi-Fi up — but the
 * underlying error carries `_NSURLErrorNWPathKey = unsatisfied (Local network
 * prohibited)`, which nothing else produces. Observed on an iPhone 17 Pro,
 * iOS 26, with the server reachable from Safari on the same device.
 *
 * So the two cases get two different messages: one is fixed in Settings, the
 * other by waking the Mac, and sending someone to the wrong one wastes their
 * time.
 */
internal fun Throwable.describe(): String {
    val raw = buildString {
        append(this@describe::class.simpleName ?: "Error")
        message?.let { append(": ").append(it) }
        cause?.let { append(" ← ").append(it::class.simpleName).append(": ").append(it.message) }
    }
    // Always print the untouched error. The friendly text below is a guess at
    // the cause; this is the fact, and hiding it made a real failure
    // undiagnosable from the device.
    println("[calibre] request failed: $raw")

    // Checked first: it also matches the "offline" patterns below, and the
    // specific diagnosis must beat the generic one.
    if (raw.contains("Local network prohibited", ignoreCase = true)) {
        return "iOS is blocking this app from reaching your local network.\n\n" +
            "Settings › Privacy & Security › Local Network, and turn on " +
            "\"calibre-ios\". If it is already on, switch it off and on again — " +
            "the grant does not always take effect until you do."
    }

    val looksUnreachable = listOf(
        "Connection refused", "Network is unreachable", "timeout", "timed out",
        "Could not connect", "-1009", "-1004",
    ).any { raw.contains(it, ignoreCase = true) }

    // The raw text goes to the console, not the screen. A sleeping Mac is the
    // normal case (#8), and pasting a Darwin exception under a plain-English
    // sentence makes an expected state look like a crash.
    return if (looksUnreachable) {
        "Can't reach the server — the Mac may be asleep, or on a different network."
    } else {
        raw
    }
}
