package com.samarth.calibreios.nowplaying

/**
 * What the system shows and offers while the app is not on screen.
 *
 * Read-aloud is the reason this app exists, so it has to survive the screen
 * locking — #13 deliberately settled read-aloud only for the app in the
 * foreground, and this closes that gap.
 *
 * The commands deliberately mirror the in-app transport rather than inventing a
 * second vocabulary: **next and previous move by a sentence, not a chapter**,
 * because a sentence is the unit the whole feature is built on.
 */
interface NowPlaying {

    /** What the lock screen displays. Safe to call often; only changes are sent. */
    fun update(title: String, chapter: String, playing: Boolean)

    /** Wire the remote controls. Called once, when a reading session exists. */
    fun setHandlers(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    )

    /** Stop claiming the lock screen. */
    fun clear()
}

expect fun createNowPlaying(): NowPlaying
