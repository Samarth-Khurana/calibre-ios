package com.samarth.calibreios.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
actual class Storage actual constructor() {

    private val fm = NSFileManager.defaultManager

    private val documents: String =
        NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .first() as String

    actual fun booksDir(): String {
        val dir = "$documents/books"
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
            // Books are re-downloadable from calibre, so backing them up would
            // bloat iCloud for nothing. Apple also rejects apps that back up
            // regenerable bulk data -- irrelevant for a sideload, but it is
            // still the correct flag.
            NSURL.fileURLWithPath(dir).setResourceValue(true, NSURLIsExcludedFromBackupKey, null)
        }
        return dir
    }

    actual fun bookFile(libraryId: String, bookId: Int): String =
        "${booksDir()}/${libraryId}_$bookId.epub"

    actual fun bookFiles(): List<String> {
        val dir = booksDir()
        val names = fm.contentsOfDirectoryAtPath(dir, null) ?: return emptyList()
        return names.filterIsInstance<String>()
            .filter { it.endsWith(".epub", ignoreCase = true) }
            .map { "$dir/$it" }
    }

    actual fun exists(path: String): Boolean = fm.fileExistsAtPath(path)

    actual fun sizeOf(path: String): Long =
        (fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? Number)?.toLong() ?: 0L

    actual fun delete(path: String) {
        if (fm.fileExistsAtPath(path)) fm.removeItemAtPath(path, null)
    }

    // POSIX rather than NSFileHandle: append is exactly what "ab" means, and
    // it avoids the Obj-C class-method binding, which does not resolve here.
    actual fun append(path: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val file = fopen(path, "ab") ?: return
        try {
            bytes.usePinned { pinned ->
                fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            }
        } finally {
            fclose(file)
        }
    }

    actual fun readText(name: String): String? =
        NSString.stringWithContentsOfFile(
            path = "$documents/$name",
            encoding = NSUTF8StringEncoding,
            error = null,
        )

    actual fun writeText(name: String, text: String) {
        // NSString.create, not `text as NSString`: a Kotlin String is not an
        // NSString subtype, so the cast compiles to a guaranteed failure.
        NSString.create(string = text).writeToFile(
            path = "$documents/$name",
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
    }
}
