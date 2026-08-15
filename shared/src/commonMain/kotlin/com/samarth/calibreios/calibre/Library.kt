package com.samarth.calibreios.calibre

import com.samarth.calibreios.storage.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/**
 * The on-device library: what calibre offers, what we hold, and getting from
 * one to the other.
 *
 * Offline-first (#8) — the catalogue is cached to disk, so the app opens and
 * shows books with the Mac asleep, which is the common case rather than an
 * edge case. Reaching the server is an *enrichment*, never a precondition.
 */
class Library(
    private val storage: Storage,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {

    sealed interface ServerState {
        data object Unknown : ServerState
        data object Reachable : ServerState
        /** Not an error state. The Mac is usually asleep (#8). */
        data class Unreachable(val detail: String) : ServerState
    }

    private val _books = MutableStateFlow<List<CalibreBook>>(emptyList())
    val books: StateFlow<List<CalibreBook>> = _books

    private val _server = MutableStateFlow<ServerState>(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _server

    private val _downloading = MutableStateFlow<Map<Int, Float>>(emptyMap())
    /** Book id → 0..1 progress, for books currently transferring. */
    val downloading: StateFlow<Map<Int, Float>> = _downloading

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
    }

    /**
     * Refresh from the server, keeping the cached list if it cannot be reached.
     *
     * Returns false when the server was unreachable — the caller shows that as
     * a state, not a failure.
     */
    suspend fun refresh(client: CalibreClient): Boolean {
        val result = client.listBooks(limit = 500)
        return result.fold(
            onSuccess = { books ->
                _books.value = books
                storage.writeText(CATALOGUE_FILE, json.encodeToString(books))
                _server.value = ServerState.Reachable
                true
            },
            onFailure = { error ->
                _server.value = ServerState.Unreachable(error.describe())
                false
            },
        )
    }

    // ------------------------------------------------------------- downloads

    fun localPath(server: CalibreServer, book: CalibreBook): String =
        storage.bookFile(server.libraryId, book.id)

    fun isDownloaded(server: CalibreServer, book: CalibreBook): Boolean {
        val path = localPath(server, book)
        if (!storage.exists(path)) return false
        // A partial file from an interrupted transfer is not a downloaded book.
        // Size is the only check available without opening the archive, and it
        // is enough: calibre tells us the expected size in format_metadata.
        return book.sizeBytes <= 0 || storage.sizeOf(path) >= book.sizeBytes
    }

    /**
     * Download [book] if it is not already complete, resuming a partial file.
     *
     * Returns the local path on success.
     */
    suspend fun ensureDownloaded(
        client: CalibreClient,
        server: CalibreServer,
        book: CalibreBook,
    ): Result<String> {
        val path = localPath(server, book)
        if (isDownloaded(server, book)) return Result.success(path)

        val have = if (storage.exists(path)) storage.sizeOf(path) else 0L
        // Guard against a stale partial that is somehow larger than the book:
        // appending to it would produce a corrupt archive.
        val resumeFrom = if (book.sizeBytes in 1..<have) { storage.delete(path); 0L } else have

        _downloading.value = _downloading.value + (book.id to 0f)

        val result = client.download(
            book = book,
            alreadyHave = resumeFrom,
            onProgress = { received, total ->
                if (total > 0) {
                    _downloading.value =
                        _downloading.value + (book.id to (received.toFloat() / total))
                }
            },
            write = { bytes -> storage.append(path, bytes) },
        )

        _downloading.value = _downloading.value - book.id

        return result.fold(
            onSuccess = {
                _server.value = ServerState.Reachable
                Result.success(path)
            },
            onFailure = { error ->
                _server.value = ServerState.Unreachable(error.describe())
                // The partial file is kept deliberately -- that is what makes
                // the next attempt a resume rather than a restart.
                Result.failure(error)
            },
        )
    }

    private companion object {
        const val SERVER_FILE = "calibre-server.json"
        const val CATALOGUE_FILE = "calibre-catalogue.json"
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
 * other by waking the Mac, and telling a user to check the wrong one wastes
 * their time.
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
    // specific diagnosis must win over the generic one.
    val localNetworkBlocked = raw.contains("Local network prohibited", ignoreCase = true)
    if (localNetworkBlocked) {
        return "iOS is blocking this app from reaching your local network.\n\n" +
            "Settings › Privacy & Security › Local Network, and turn on " +
            "\"calibre-ios\". If it isn't listed, delete the app and reinstall " +
            "it — the permission prompt only appears once per install, and a " +
            "missed prompt cannot be re-triggered."
    }

    val looksUnreachable = listOf(
        "Connection refused", "Network is unreachable", "timeout", "timed out",
        "Could not connect", "-1009", "-1004",
    ).any { raw.contains(it, ignoreCase = true) }

    return if (looksUnreachable) {
        "Can't reach the server — the Mac may be asleep, or on a different " +
            "network.\n\n$raw"
    } else {
        raw
    }
}
