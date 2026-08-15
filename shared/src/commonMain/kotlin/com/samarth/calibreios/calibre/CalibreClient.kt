package com.samarth.calibreios.calibre

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.core.remaining
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

/**
 * The calibre content server, as much of it as V1 needs.
 *
 * Uses the undocumented `/ajax` API rather than OPDS, chosen in #8 for richer
 * metadata and custom-column access. It is unpromised but has been stable for
 * years; OPDS remains the conservative fallback and reaches the same books.
 *
 * **Unreachable is a normal state, not an error** (#8). The Mac is asleep most
 * of the time. Every call here returns a [Result] rather than throwing, so
 * callers are forced to decide what to show rather than letting an exception
 * surface as a crash or a scary dialog.
 */
class CalibreClient(
    private val server: CalibreServer,
    engine: HttpClient? = null,
) {

    private val client: HttpClient = engine ?: HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            // Deliberately short. A sleeping Mac should be reported quickly,
            // not hung on.
            connectTimeoutMillis = 4_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Cheap reachability probe. */
    suspend fun ping(): Result<Boolean> = call {
        val response = client.get("${server.baseUrl}/ajax/search") {
            auth()
            parameter("num", 1)
        }
        response.status.isSuccess()
    }

    /**
     * Book ids matching [query], newest-relevant first per the server's sort.
     *
     * Paging is server-side (#8) — pass [offset] and [limit] rather than
     * pulling the whole library and slicing locally.
     */
    suspend fun search(
        query: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<List<Int>> = call {
        val response: SearchResponse = client.get("${server.baseUrl}/ajax/search") {
            auth()
            parameter("library_id", server.libraryId)
            parameter("num", limit)
            parameter("offset", offset)
            if (!query.isNullOrBlank()) parameter("query", query)
        }.body()
        response.bookIds
    }

    /**
     * Hydrate [ids] in one request.
     *
     * Only books with an EPUB are returned — V1 is EPUB-only, and a book we
     * cannot open should not appear in a library we can read from.
     */
    suspend fun books(ids: List<Int>): Result<List<CalibreBook>> = call {
        if (ids.isEmpty()) return@call emptyList()
        val raw: Map<String, BookEntry> = client.get("${server.baseUrl}/ajax/books") {
            auth()
            parameter("library_id", server.libraryId)
            parameter("ids", ids.joinToString(","))
        }.body()

        ids.mapNotNull { id ->
            val entry = raw[id.toString()] ?: return@mapNotNull null
            // The server hands back a ready-made path in `main_format`. Use it
            // rather than composing one: #6 found that a malformed /get/ route
            // returns `{}` with HTTP 200 -- a silent wrong answer -- so not
            // building the URL ourselves removes that whole class of bug.
            val epubPath = entry.mainFormat.entries
                .firstOrNull { it.key.equals("epub", ignoreCase = true) }
                ?.value
                ?: return@mapNotNull null

            CalibreBook(
                id = id,
                title = entry.title,
                authors = entry.authors,
                downloadPath = epubPath,
                sizeBytes = entry.formatMetadata.entries
                    .firstOrNull { it.key.equals("epub", ignoreCase = true) }
                    ?.value?.size ?: 0,
                coverPath = entry.cover,
                lastModified = entry.lastModified,
            )
        }
    }

    /** Convenience: search then hydrate. */
    suspend fun listBooks(
        query: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): Result<List<CalibreBook>> =
        search(query, offset, limit).mapCatching { ids -> books(ids).getOrThrow() }

    /**
     * Stream a book to disk, resuming from [alreadyHave] bytes if the server
     * honours the range request.
     *
     * #6 confirmed `206` + `Accept-Ranges` on this endpoint, so an interrupted
     * download of a 7.5 MB book does not start over. [onProgress] receives
     * bytes-so-far and total (total is -1 when the server does not say).
     */
    suspend fun download(
        book: CalibreBook,
        alreadyHave: Long = 0,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
        write: suspend (ByteArray) -> Unit,
    ): Result<Long> = call {
        var received = alreadyHave

        client.prepareGet("${server.baseUrl}${book.downloadPath}") {
            auth()
            if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-")
        }.execute { response ->
            // 206 means our range was honoured. A 200 means the server ignored
            // it and is sending the whole file, so anything already on disk has
            // to be discarded rather than appended to.
            val resuming = response.status == HttpStatusCode.PartialContent
            if (alreadyHave > 0 && !resuming) received = 0

            val declared = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
            val total = if (declared >= 0) received + declared else book.sizeBytes

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val packet = channel.readRemaining(CHUNK)
                while (packet.remaining > 0) {
                    val bytes = packet.readByteArray()
                    write(bytes)
                    received += bytes.size
                    onProgress(received, total)
                }
            }
        }
        received
    }

    fun close() = client.close()

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        val creds = server.credentials ?: return
        header("Authorization", "Basic ${basicAuth(creds.username, creds.password)}")
    }

    /** Never swallow cancellation — it is control flow, not a failure. */
    private inline fun <T> call(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private companion object {
        const val CHUNK = 64L * 1024
    }
}

/** Minimal base64 for Basic auth; avoids a dependency for one header. */
internal fun basicAuth(user: String, password: String): String {
    val bytes = "$user:$password".encodeToByteArray()
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString {
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            append(table[b0 shr 2])
            append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
            append(if (i + 1 < bytes.size) table[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
            append(if (i + 2 < bytes.size) table[b2 and 0x3F] else '=')
            i += 3
        }
    }
}
