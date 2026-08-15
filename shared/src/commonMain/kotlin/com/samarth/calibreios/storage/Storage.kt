package com.samarth.calibreios.storage

/**
 * The small amount of filesystem V1 needs.
 *
 * Deliberately not a database. #14 settled on serialized files: a personal
 * library is a few hundred books, and JSON on disk needs no schema, no
 * codegen and no migrations. Revisit only if browse actually gets slow.
 */
expect class Storage() {

    /** Directory for downloaded books. Excluded from device backup. */
    fun booksDir(): String

    /** Absolute path a given book's EPUB occupies, downloaded or not. */
    fun bookFile(libraryId: String, bookId: Int): String

    fun exists(path: String): Boolean
    fun sizeOf(path: String): Long
    fun delete(path: String)

    /** Append [bytes] to [path], creating it if absent. Used by resumable download. */
    fun append(path: String, bytes: ByteArray)

    /** Small JSON documents: server config, settings, positions. */
    fun readText(name: String): String?
    fun writeText(name: String, text: String)
}
