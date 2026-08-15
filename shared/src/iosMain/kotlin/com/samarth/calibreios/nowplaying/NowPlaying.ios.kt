package com.samarth.calibreios.nowplaying

import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

/**
 * Lock-screen presence via MediaPlayer. No Swift, no cinterop — Kotlin/Native
 * ships the `MediaPlayer` platform klib with all of this bound.
 *
 * Two things make this simpler than the AVSpeechSynthesizer delegate (#9):
 * `MPRemoteCommandCenter` takes **block handlers** rather than an Obj-C
 * protocol, so there is no `NSObject()` subclass and no selector collision to
 * work around.
 *
 * The usual Obj-C caution still applies: an exception escaping a handler
 * crosses the boundary and terminates the process instead of propagating, so
 * every handler swallows and reports.
 */
private class IosNowPlaying : NowPlaying {

    private val infoCenter = MPNowPlayingInfoCenter.defaultCenter()
    private val commands = MPRemoteCommandCenter.sharedCommandCenter()

    private var lastTitle: String? = null
    private var lastChapter: String? = null
    private var lastPlaying: Boolean? = null
    private var wired = false

    override fun update(title: String, chapter: String, playing: Boolean) {
        // Only push real changes. `nowPlayingInfo` is a cross-process write and
        // this is called from every relocate, which fires on each page turn.
        if (title == lastTitle && chapter == lastChapter && playing == lastPlaying) return
        lastTitle = title
        lastChapter = chapter
        lastPlaying = playing

        infoCenter.nowPlayingInfo = mapOf<Any?, Any?>(
            // Title is the chapter, not the book: on the lock screen the top
            // line is what you are listening to *now*, and the book is the
            // container -- the same shape as track-then-album.
            MPMediaItemPropertyTitle to chapter.ifBlank { title },
            MPMediaItemPropertyAlbumTitle to title,
            // Speech has no seekable timeline, so elapsed time and duration are
            // deliberately omitted -- reporting them would draw a scrubber that
            // cannot work. The rate alone drives the play/pause glyph.
            MPNowPlayingInfoPropertyPlaybackRate to
                NSNumber.numberWithDouble(if (playing) 1.0 else 0.0),
        )
    }

    override fun setHandlers(
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        // Handlers accumulate: addTargetWithHandler appends, so wiring twice
        // would fire each action twice per press.
        if (wired) return
        wired = true

        fun wire(command: platform.MediaPlayer.MPRemoteCommand, action: () -> Unit) {
            command.enabled = true
            command.addTargetWithHandler { _ ->
                runCatching { action() }
                MPRemoteCommandHandlerStatusSuccess
            }
        }

        wire(commands.playCommand) { onPlay() }
        wire(commands.pauseCommand) { onPause() }
        wire(commands.togglePlayPauseCommand) {
            if (lastPlaying == true) onPause() else onPlay()
        }
        // Sentence, not chapter -- the unit the in-app transport uses (#19).
        wire(commands.nextTrackCommand) { onNext() }
        wire(commands.previousTrackCommand) { onPrevious() }
    }

    override fun clear() {
        infoCenter.nowPlayingInfo = null
        lastTitle = null
        lastChapter = null
        lastPlaying = null
    }
}

actual fun createNowPlaying(): NowPlaying = IosNowPlaying()
