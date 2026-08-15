package com.samarth.calibreios.nowplaying

/** Placeholder; Android has no UI in V1. The equivalent is MediaSessionCompat. */
private class NoNowPlaying : NowPlaying {
    override fun update(title: String, chapter: String, playing: Boolean) = Unit
    override fun setHandlers(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) = Unit
    override fun clear() = Unit
}

actual fun createNowPlaying(): NowPlaying = NoNowPlaying()
