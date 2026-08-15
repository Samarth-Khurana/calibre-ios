package com.samarth.calibreios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun autoplayEnabled(): Boolean =
    getenv("CALIBRE_AUTOPLAY")?.toKString().isNullOrEmpty().not()

@OptIn(ExperimentalForeignApi::class)
internal actual fun autoplayDelimiter(): String? =
    getenv("CALIBRE_DELIMITER")?.toKString()?.takeIf { it.isNotEmpty() }

@OptIn(ExperimentalForeignApi::class)
internal actual fun testBookPath(): String? =
    getenv("CALIBRE_BOOK")?.toKString()?.takeIf { it.isNotEmpty() }

@OptIn(ExperimentalForeignApi::class)
internal actual fun autoOpenBookId(): Int? =
    getenv("CALIBRE_OPEN_ID")?.toKString()?.toIntOrNull()
