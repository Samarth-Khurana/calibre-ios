package com.samarth.calibreios

/**
 * Harness-only escape hatch, removed with the harness.
 *
 * `simctl` cannot tap, so a prototype whose whole point is a transport you press
 * is otherwise unverifiable anywhere but a real device. Setting `CALIBRE_AUTOPLAY`
 * in the launch environment makes the harness start reading by itself:
 *
 *     xcrun simctl launch --console-pty <udid> <bundle-id> CALIBRE_AUTOPLAY=1
 */
internal expect fun autoplayEnabled(): Boolean

/**
 * Harness-only: name of the delimiter preset to select at launch, matched
 * against [com.samarth.calibreios.reader.TtsDelimiter.label]. Set with
 * `SIMCTL_CHILD_CALIBRE_DELIMITER=Clause`.
 */
internal expect fun autoplayDelimiter(): String?

/**
 * Harness-only: absolute path to an EPUB to open instead of the bundled
 * fixture, so a real book carrying a real calibre position can be tested
 * before the download path exists. Set with `SIMCTL_CHILD_CALIBRE_BOOK=/path`.
 */
internal expect fun testBookPath(): String?
