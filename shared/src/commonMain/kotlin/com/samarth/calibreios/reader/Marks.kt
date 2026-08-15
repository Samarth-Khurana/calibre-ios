package com.samarth.calibreios.reader

import com.samarth.calibreios.storage.Storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A place worth returning to: a highlight over text, or a plain bookmark.
 *
 * The two types mirror calibre's own, so a mark read from the Mac needs no
 * translation into a foreign shape (#17).
 */
@Serializable
data class Mark(
    val id: String,
    val kind: Kind,
    val cfi: String,
    /** The highlighted text, or a bookmark's label. */
    val text: String = "",
    /** calibre highlights can carry a note. We display them; we do not make them. */
    val note: String = "",
    /** Chapter, when known. */
    val label: String = "",
    val color: String? = null,
    val createdAt: Long = 0,
    /**
     * True for marks read out of calibre's own file.
     *
     * These are **read-only** (#17): they live in the Mac's copy of the book and
     * the phone genuinely cannot change them. Presenting them as deletable would
     * be a lie — a re-download brings them straight back.
     */
    val fromCalibre: Boolean = false,
) {
    @Serializable
    enum class Kind {
        // Wire names are calibre's own, lowercase. Without these the whole
        // calibreMarks payload fails to decode and the marks silently never
        // appear -- the error only surfaces as a string in the status bar.
        @SerialName("highlight") Highlight,
        @SerialName("bookmark") Bookmark,
    }
}

/**
 * Marks made on this device.
 *
 * Keyed on calibre's `book_id` scoped by `library_id` (#8) rather than a file
 * path, so they survive the book being removed to reclaim space (#15) — the
 * same rule as reading positions.
 */
class MarkStore(
    private val storage: Storage,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {

    private var cache: MutableMap<String, MutableList<Mark>> = load()

    private fun load(): MutableMap<String, MutableList<Mark>> =
        storage.readText(FILE)
            ?.let {
                runCatching {
                    json.decodeFromString<Map<String, List<Mark>>>(it)
                        .mapValues { (_, v) -> v.toMutableList() }
                        .toMutableMap()
                }.getOrNull()
            }
            ?: mutableMapOf()

    private fun key(libraryId: String, bookId: Int) = "$libraryId#$bookId"

    fun own(libraryId: String, bookId: Int): List<Mark> =
        cache[key(libraryId, bookId)].orEmpty()

    fun add(libraryId: String, bookId: Int, mark: Mark) {
        val list = cache.getOrPut(key(libraryId, bookId)) { mutableListOf() }
        list.removeAll { it.id == mark.id }
        list += mark
        persist()
    }

    fun remove(libraryId: String, bookId: Int, id: String) {
        cache[key(libraryId, bookId)]?.removeAll { it.id == id }
        persist()
    }

    private fun persist() {
        runCatching { storage.writeText(FILE, json.encodeToString(cache)) }
    }

    private companion object {
        const val FILE = "marks.json"
    }
}

/**
 * The list shown to the reader: yours plus the Mac's, in **book order**.
 *
 * Book order rather than creation order because this is a navigation aid — you
 * are looking for a place in the book, not a history of what you did.
 *
 * De-duplicated by id, with your own copy winning. calibre highlights carry a
 * `uuid` and bookmarks a title+position, so a re-download re-reads the same
 * marks with the same ids rather than accumulating duplicates.
 */
fun mergeMarks(own: List<Mark>, fromCalibre: List<Mark>): List<Mark> {
    val byId = LinkedHashMap<String, Mark>()
    fromCalibre.forEach { byId[it.id] = it }
    own.forEach { byId[it.id] = it }
    return byId.values.sortedWith(
        Comparator { a, b ->
            val byPosition = compareKeys(cfiSortKey(a.cfi), cfiSortKey(b.cfi))
            if (byPosition != 0) byPosition else a.text.compareTo(b.text)
        }
    )
}

/**
 * A comparable key from a CFI, good enough to order a list.
 *
 * Not a real CFI comparison — that lives in the engine (`CFI.compare`) and is
 * not worth a round trip per pair to sort a handful of rows. Extracting the
 * leading integer steps sorts correctly by spine item and then by position
 * within it, which is what a reader scanning the list actually needs.
 */
private fun cfiSortKey(cfi: String): List<Int> =
    Regex("\\d+").findAll(cfi).take(6).map { it.value.toIntOrNull() ?: 0 }.toList()

/** Lexicographic over the step lists; a shorter prefix sorts first. */
private fun compareKeys(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = a[i].compareTo(b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}
