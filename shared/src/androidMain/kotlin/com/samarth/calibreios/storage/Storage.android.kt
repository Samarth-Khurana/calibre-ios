package com.samarth.calibreios.storage

/**
 * Placeholder. Android has no UI in V1; this exists so `commonMain` compiles
 * for the Android target. The real implementation is `Context.filesDir` with
 * exactly this shape.
 */
actual class Storage actual constructor() {

    actual fun booksDir(): String {
        unsupported()
    }

    actual fun bookFile(libraryId: String, bookId: Int): String {
        unsupported()
    }

    actual fun bookFiles(): List<String> = emptyList()

    actual fun exists(path: String): Boolean = false

    actual fun sizeOf(path: String): Long = 0

    actual fun delete(path: String) = Unit

    actual fun append(path: String, bytes: ByteArray) {
        unsupported()
    }

    actual fun readText(name: String): String? = null

    actual fun writeText(name: String, text: String) {
        unsupported()
    }

    private fun unsupported(): Nothing =
        error("Storage is not implemented on Android in V1")
}
