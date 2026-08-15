package com.samarth.calibreios.tts

import com.samarth.calibreios.reader.ReaderCommand
import com.samarth.calibreios.reader.ReaderController
import com.samarth.calibreios.reader.ReaderEvent
import com.samarth.calibreios.reader.TtsChunk
import com.samarth.calibreios.reader.TtsDelimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The read-aloud interaction: highlight the chunk that is *about* to be spoken,
 * hold for a configured interval, then speak it -- the behaviour the user wants
 * carried over from calibre, and the reason this project exists.
 *
 * ## Why the pause is timed here rather than by the platform
 *
 * AVSpeechUtterance has `preUtteranceDelay`, which looks like exactly the right
 * tool and is a trap. Research (#3) established that the ordering of `didStart`
 * against that delay is undocumented, and this loop's correctness depends on it:
 * if `didStart` fires *after* the delay elapses, then a queued design cannot
 * paint the highlight before the pause, and the feature silently degrades into
 * "highlight and speak simultaneously" -- calibre's behaviour, minus the point
 * of it.
 *
 * So the loop paints the highlight, `delay`s itself, and only then speaks a
 * single utterance, waiting for completion before moving on. This is correct
 * whichever way the undocumented ordering falls. The cost is one bridge round
 * trip of dead air between chunks, on top of the configured pause.
 *
 * ## Boundaries
 *
 * foliate's TTS is bound to one rendered *section*, not the whole book, so
 * running out of chunks means "end of section", not "end of book". The loop
 * turns the page, re-segments, and continues -- which is also what makes a chunk
 * spanning a page boundary work: highlighting scrolls the chunk into view, so
 * pagination follows the voice instead of fighting it.
 */
class TtsSession(
    private val scope: CoroutineScope,
    private val reader: ReaderController,
    private val speaker: Speaker,
) {

    data class Settings(
        val delimiter: TtsDelimiter = TtsDelimiter.Sentence,
        /** How long the highlight is held before the voice starts. */
        val holdMillis: Long = 400,
        val speech: SpeechSettings = SpeechSettings(),
    )

    enum class State { Idle, Speaking, Paused, Finished }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state

    /** The chunk currently highlighted/spoken, for UI. */
    private val _current = MutableStateFlow<TtsChunk?>(null)
    val current: StateFlow<TtsChunk?> = _current

    /** Human-readable trace of what the loop did, for the prototype's log pane. */
    private val _trace = MutableSharedFlow<String>(replay = 40, extraBufferCapacity = 80)
    val trace: SharedFlow<String> = _trace

    var settings: Settings = Settings()
        private set

    // A Channel, not a replaying flow: each delivery must be consumed exactly
    // once. With `replay = 1`, the next `first()` would return the *previous*
    // block immediately and the loop would read the same text twice.
    private val chunkDeliveries = Channel<ReaderEvent.TtsChunks>(capacity = 8)

    private var pending: List<TtsChunk> = emptyList()
    private var index = 0
    private var job: Job? = null
    private var segmented = false
    private var lastCfi: String? = null

    /** Forward every reader event here; the session picks out what it needs. */
    fun onReaderEvent(event: ReaderEvent) {
        when (event) {
            is ReaderEvent.TtsChunks -> chunkDeliveries.trySend(event)
            is ReaderEvent.Location -> lastCfi = event.cfi
            is ReaderEvent.TtsReady -> {
                segmented = true
                _trace.tryEmit("segmented: ${event.granularity}")
            }
            // A new section invalidates the segmentation bound to the old one.
            is ReaderEvent.Opened -> segmented = false
            else -> Unit
        }
    }

    /**
     * Change settings. A delimiter change re-segments and restarts the current
     * block, because the chunk under the cursor no longer exists as such.
     */
    fun update(new: Settings) {
        val resegment = new.delimiter != settings.delimiter
        settings = new
        if (resegment && _state.value != State.Idle) {
            scope.launch {
                stop()
                start(fromStart = false)
            }
        }
    }

    fun start(fromStart: Boolean = false) {
        job?.cancel()
        _state.value = State.Speaking
        job = scope.launch { runLoop(fromStart) }
    }

    fun pause() {
        if (_state.value != State.Speaking) return
        _state.value = State.Paused
        speaker.pause()
        _trace.tryEmit("paused")
    }

    fun resume() {
        if (_state.value != State.Paused) return
        _state.value = State.Speaking
        speaker.resume()
        _trace.tryEmit("resumed")
    }

    fun stop() {
        job?.cancel()
        job = null
        speaker.stop()
        _state.value = State.Idle
        _current.value = null
    }

    /** Skip the rest of the current chunk and move to the next. */
    fun skip() {
        speaker.stop()
        _trace.tryEmit("skipped")
    }

    // ------------------------------------------------------------------ loop

    private suspend fun runLoop(fromStart: Boolean) {
        ensureSegmented()
        pending = emptyList()
        index = 0

        var first = fromStart
        // A book opens on its cover or title page, which has no speakable
        // blocks at all. Running dry is therefore the *normal* first outcome,
        // not the end -- so exhausting a section always tries the next one, and
        // only a run of empty sections ends the session.
        var emptySections = 0

        while (true) {
            if (index >= pending.size) {
                val got = requestChunks(first)
                first = false
                if (got) {
                    emptySections = 0
                } else {
                    if (++emptySections > MAX_EMPTY_SECTIONS || !advanceSection()) {
                        _state.value = State.Finished
                        _current.value = null
                        _trace.emit("end of book")
                        return
                    }
                    continue
                }
            }

            val chunk = pending[index]
            _current.value = chunk

            // 1. Highlight the chunk we are ABOUT to read. This also scrolls it
            //    into view, which is what carries the voice across a page break.
            val paintedAt = chunk.mark?.let {
                reader.send(ReaderCommand.TtsHighlight(it))
                nowMillis()
            }

            // 2. Hold, so the eye can find the line before the voice starts.
            if (settings.holdMillis > 0) delay(settings.holdMillis)

            // 3. Speak exactly one chunk, and wait for it. Every wait below is
            //    filtered by the utterance id, because the speaker's event flow
            //    replays and an unfiltered wait would match the *previous*
            //    chunk's Finished and run the loop a chunk ahead of the audio.
            val id = speaker.speak(chunk.text, settings.speech)

            val started = withTimeoutOrNull(SPEECH_START_TIMEOUT_MS) {
                speaker.events.first { it is SpeechEvent.Started && it.utteranceId == id }
            }
            if (started != null && paintedAt != null) {
                _trace.emit(
                    "chunk ${index + 1}/${pending.size} " +
                        "hold=${started.atMillis - paintedAt}ms " +
                        "\"${chunk.text.take(48)}\""
                )
            } else if (started == null) {
                _trace.emit("chunk ${index + 1} never started -- skipping")
                index++
                continue
            }

            val ended = speaker.events.first {
                it.utteranceId == id &&
                    (it is SpeechEvent.Finished ||
                        it is SpeechEvent.Cancelled ||
                        it is SpeechEvent.Failed)
            }
            // stop() cancels this coroutine outright, so a Cancelled seen here
            // is a skip: advance rather than treating it as the end.
            if (ended is SpeechEvent.Failed) {
                _trace.emit("speech failed: ${ended.message}")
                _state.value = State.Idle
                return
            }

            index++
        }
    }

    private suspend fun ensureSegmented() {
        if (segmented) return
        reader.send(ReaderCommand.TtsInit(settings.delimiter))
        // The reply is `ttsReady`, but the loop can proceed without it as long
        // as the chunk request that follows is ordered behind it in the web
        // view's queue -- which it is, both being evaluateJavaScript calls.
        withTimeoutOrNull(TTS_READY_TIMEOUT_MS) {
            while (!segmented) delay(20)
        }
    }

    /** Pulls the next block. Returns false when the section is exhausted. */
    private suspend fun requestChunks(fromStart: Boolean): Boolean {
        var first = fromStart
        // A block can legitimately segment to nothing -- an image, a rule, a
        // figure caption of pure punctuation. Skip over those rather than
        // treating an empty delivery as the end of the section.
        repeat(MAX_EMPTY_BLOCKS) {
            reader.send(ReaderCommand.TtsNext(fromStart = first))
            first = false
            val delivery = withTimeoutOrNull(CHUNKS_TIMEOUT_MS) { chunkDeliveries.receive() }
            if (delivery == null) {
                _trace.emit("timed out waiting for chunks")
                return false
            }
            if (delivery.done) return false
            if (delivery.chunks.isNotEmpty()) {
                pending = delivery.chunks
                index = 0
                return true
            }
        }
        _trace.emit("gave up after $MAX_EMPTY_BLOCKS empty blocks")
        return false
    }

    /**
     * Turn the page and re-segment against whatever is now rendered.
     *
     * foliate's TTS binds to one document, so this is what carries reading
     * across a section break. Returns false at the end of the book, detected by
     * the position refusing to move.
     */
    private suspend fun advanceSection(): Boolean {
        val before = lastCfi
        reader.send(ReaderCommand.NextPage)
        delay(PAGE_SETTLE_MS)
        if (lastCfi != null && lastCfi == before) {
            _trace.emit("position did not move -- end of book")
            return false
        }
        segmented = false
        ensureSegmented()
        _trace.emit("advanced past an unreadable section")
        return true
    }

    private companion object {
        const val TTS_READY_TIMEOUT_MS = 3_000L
        const val CHUNKS_TIMEOUT_MS = 3_000L
        const val SPEECH_START_TIMEOUT_MS = 4_000L
        const val MAX_EMPTY_BLOCKS = 24

        /** Front matter can be several unreadable sections deep. */
        const val MAX_EMPTY_SECTIONS = 8

        /** Pagination and re-render are async in the web view. */
        const val PAGE_SETTLE_MS = 350L
    }
}

internal expect fun nowMillis(): Long
