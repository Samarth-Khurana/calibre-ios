package com.samarth.calibreios.tts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Placeholder. Android has no UI in V1 (see the map's constraints), so this
 * exists only to keep `commonMain` compiling for the Android target.
 *
 * The real implementation is `android.speech.tts.TextToSpeech` driven by an
 * `UtteranceProgressListener`: `onStart`/`onDone`/`onError` map onto
 * [SpeechEvent] one-for-one, and the utterance id this interface already
 * carries is exactly the `utteranceId` string that API keys its callbacks on.
 * [TtsSession] needs no changes -- the sequencing lives in common code
 * precisely so that porting is this small.
 */
private class UnavailableSpeaker : Speaker {

    private val _events = MutableSharedFlow<SpeechEvent>(replay = 1, extraBufferCapacity = 8)
    override val events: SharedFlow<SpeechEvent> = _events
    override val isSpeaking: Boolean = false

    override fun speak(text: String, settings: SpeechSettings): Long {
        _events.tryEmit(SpeechEvent.Failed(nowMillis(), 0L, "TTS not implemented on Android"))
        return 0L
    }

    override fun pause() = Unit
    override fun resume() = Unit
    override fun stop() = Unit
    override fun voices(languagePrefix: String): List<VoiceInfo> = emptyList()
}

actual fun createSpeaker(): Speaker = UnavailableSpeaker()
