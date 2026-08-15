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
