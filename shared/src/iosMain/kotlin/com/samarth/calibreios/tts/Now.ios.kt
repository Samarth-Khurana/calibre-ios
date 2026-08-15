package com.samarth.calibreios.tts

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Wall-clock milliseconds. Shared by the speaker's delegate callbacks and the
 * sequencing loop, so the hold measurement subtracts two readings of the same
 * clock rather than mixing sources.
 */
internal actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()
