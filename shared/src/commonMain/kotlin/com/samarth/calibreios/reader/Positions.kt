package com.samarth.calibreios.reader

import com.samarth.calibreios.storage.Storage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where you were in each book.
 *
 * Kept **separately from the downloaded file** (#15): removing a book to
 * reclaim space must not cost your place in it, so positions survive deletion
 * and are restored when the book comes back.
 *
 * Keyed by calibre `book_id` scoped to `library_id` (#8), not by file path —
 * a path is an implementation detail that changes if storage is reorganised.
 */
@Serializable
data class ReadingPosition(
    val cfi: String,
    /** For progress display only. Never for sync — see [ReaderCommand.GoToFraction]. */
    val fraction: Double = 0.0,
    /** Wall-clock millis, so a later Mac position can be recognised as newer. */
    val updatedAt: Long = 0,
)

class PositionStore(
    private val storage: Storage,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {

    private var cache: MutableMap<String, ReadingPosition> = load()

    private fun load(): MutableMap<String, ReadingPosition> =
        storage.readText(FILE)
            ?.let {
                runCatching {
                    json.decodeFromString<Map<String, ReadingPosition>>(it).toMutableMap()
                }.getOrNull()
            }
            ?: mutableMapOf()

    private fun key(libraryId: String, bookId: Int) = "$libraryId#$bookId"

    fun get(libraryId: String, bookId: Int): ReadingPosition? = cache[key(libraryId, bookId)]

    fun put(libraryId: String, bookId: Int, position: ReadingPosition) {
        cache[key(libraryId, bookId)] = position
        runCatching { storage.writeText(FILE, json.encodeToString(cache)) }
    }

    private companion object {
        const val FILE = "positions.json"
    }
}

/**
 * Whether the Mac's position is worth offering.
 *
 * #8 settled the policy: **ask, never jump.** Opening always uses the phone's
 * own position, and a Mac position that is further on is surfaced as a
 * dismissible offer. Reading is never silently discarded.
 *
 * #8 sketched this as *"Mac was at 60%"*, which turned out not to be
 * implementable: calibre's embedded bookmark carries **no `pos_frac`** — that
 * lives only in `metadata.db`, unreachable per-user over HTTP (#6). The engine
 * compares the two CFIs instead, which is both available and exact, where a
 * cross-renderer fraction would only ever be approximate (#12).
 *
 * Only *ahead* is offered. A Mac position behind the phone's means the phone
 * is the newer read, and offering to go backwards would be noise.
 */
fun shouldOfferMacPosition(
    phone: ReadingPosition?,
    macRelative: Int?,
): Boolean {
    // Nothing read on this device yet: opening at the Mac's position is not a
    // conflict, it is the whole point, and it has already been applied.
    if (phone == null) return false
    return macRelative == 1
}
