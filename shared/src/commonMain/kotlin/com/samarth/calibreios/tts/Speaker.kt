package com.samarth.calibreios.tts

import kotlinx.coroutines.flow.SharedFlow

/**
 * A single-utterance speech engine.
 *
 * Deliberately narrow: [speak] takes one chunk and the caller waits for
 * [SpeechEvent.Finished] before sending the next. The alternative -- queueing
 * many utterances and letting the platform sequence them with `preUtteranceDelay`
 * -- is *not* modelled here, and that is a decision, not an omission. See
 * [SpeechSettings.preUtteranceDelaySec] for why.
 *
 * Nothing in this interface is iOS-specific. Android's `TextToSpeech` maps onto
 * it with `UtteranceProgressListener`, which is why the sequencing engine that
 * consumes it ([TtsSession]) lives in common code.
 */
interface Speaker {

    /** Engine lifecycle. Hot; a late subscriber misses earlier utterances. */
    val events: SharedFlow<SpeechEvent>

    /** True between [SpeechEvent.Started] and [SpeechEvent.Finished]/[SpeechEvent.Cancelled]. */
    val isSpeaking: Boolean

    /**
     * Speak [text], cancelling any in-flight utterance first.
     *
     * Returns the id that every resulting [SpeechEvent] carries. Callers must
     * filter on it: [events] replays, so a bare "wait for the next Finished"
     * can match the *previous* chunk's completion and race ahead by one.
     */
    fun speak(text: String, settings: SpeechSettings): Long

    /** Suspend audio at the next word boundary. No-op when not speaking. */
    fun pause()

    /** Resume after [pause]. */
    fun resume()

    /** Abandon the current utterance immediately; emits [SpeechEvent.Cancelled]. */
    fun stop()

    /** Installed voices for [languagePrefix], best quality first. */
    fun voices(languagePrefix: String = "en"): List<VoiceInfo>
}

/**
 * What the engine reports back.
 *
 * [atMillis] is a monotonic timestamp taken inside the platform callback. It is
 * load-bearing rather than diagnostic: the gap between [Started] and the moment
 * the highlight was painted is exactly the "hold before speaking" the reader
 * configures, and it is the only way to check the platform is honouring it.
 */
sealed interface SpeechEvent {
    val atMillis: Long

    /** Which [Speaker.speak] call this belongs to. Zero for engine-wide events. */
    val utteranceId: Long

    data class Started(
        override val atMillis: Long,
        override val utteranceId: Long,
        val text: String,
    ) : SpeechEvent

    data class Finished(
        override val atMillis: Long,
        override val utteranceId: Long,
    ) : SpeechEvent

    data class Cancelled(
        override val atMillis: Long,
        override val utteranceId: Long,
    ) : SpeechEvent

    data class Paused(
        override val atMillis: Long,
        override val utteranceId: Long,
    ) : SpeechEvent

    data class Continued(
        override val atMillis: Long,
        override val utteranceId: Long,
    ) : SpeechEvent

    /** The engine could not speak -- no voice, audio session refused, etc. */
    data class Failed(
        override val atMillis: Long,
        override val utteranceId: Long,
        val message: String,
    ) : SpeechEvent
}

data class VoiceInfo(
    val identifier: String,
    val name: String,
    val language: String,
    /** `default`, `enhanced`, or `premium`. */
    val quality: String,
    /** True for a voice the user recorded themselves (iOS Personal Voice). */
    val isPersonal: Boolean = false,
)

/**
 * Everything the reader can tune about the voice itself.
 *
 * [preUtteranceDelaySec] is exposed but **left at zero by the sequencing
 * engine**, which times the pause itself with a plain `delay`. The reason is
 * research finding #3: the ordering of `didStart` against `preUtteranceDelay` is
 * undocumented. If `didStart` fires *after* the delay, a queued design cannot
 * paint the highlight before the pause -- which is the entire feature. Timing
 * the gap ourselves makes the behaviour independent of that ordering, at the
 * cost of a small inter-utterance gap from the round trip.
 */
data class SpeechSettings(
    /** 0.0..1.0. AVSpeechUtterance's default is ~0.5; below ~0.4 sounds slurred. */
    val rate: Float = 0.5f,
    /** 0.5..2.0. */
    val pitch: Float = 1.0f,
    /** 0.0..1.0. */
    val volume: Float = 1.0f,
    /** Platform voice identifier; null uses the system default for the locale. */
    val voiceId: String? = null,
    val preUtteranceDelaySec: Double = 0.0,
    val postUtteranceDelaySec: Double = 0.0,
)

/** Creates the platform speech engine. */
expect fun createSpeaker(): Speaker
