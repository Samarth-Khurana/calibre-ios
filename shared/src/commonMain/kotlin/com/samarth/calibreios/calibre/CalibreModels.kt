package com.samarth.calibreios.calibre

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the calibre content server lives.
 *
 * [credentials] is nullable because authentication is genuinely optional here:
 * V1 reads reading position from inside the downloaded EPUB rather than from
 * the position endpoints (#4, #6, #8), and those endpoints are the only part of
 * the API that requires auth. A default calibre server on a home LAN needs none.
 */
@Serializable
data class CalibreServer(
    val host: String,
    val port: Int = 8080,
    val libraryId: String = "Calibre_Library",
    val credentials: Credentials? = null,
) {
    @Serializable
    data class Credentials(val username: String, val password: String)

    val baseUrl: String get() = "http://$host:$port"
}

/** One book, as far as V1 cares. */
@Serializable
data class CalibreBook(
    val id: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    /** Relative path from `main_format`, e.g. `/get/epub/2/Calibre_Library`. */
    val downloadPath: String,
    val sizeBytes: Long = 0,
    val coverPath: String? = null,
    /** Server-side modification time; the cheap staleness check for re-sync. */
    val lastModified: String? = null,
) {
    val authorLine: String get() = authors.joinToString(", ").ifEmpty { "Unknown" }
}

// ------------------------------------------------------------------ wire DTOs
//
// Separate from the domain types above because /ajax/* is undocumented and
// unpromised (#8) -- when it shifts, it shifts here and nowhere else.

@Serializable
internal data class SearchResponse(
    @SerialName("book_ids") val bookIds: List<Int> = emptyList(),
    @SerialName("total_num") val totalNum: Int = 0,
    @SerialName("library_id") val libraryId: String = "",
)

@Serializable
internal data class BookEntry(
    val title: String = "",
    val authors: List<String> = emptyList(),
    @SerialName("main_format") val mainFormat: Map<String, String> = emptyMap(),
    @SerialName("format_metadata") val formatMetadata: Map<String, FormatMetadata> = emptyMap(),
    val cover: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
)

@Serializable
internal data class FormatMetadata(
    val size: Long = 0,
    val mtime: String? = null,
)
