package com.samarth.calibreios.tts

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityEnhanced
import platform.AVFAudio.AVSpeechSynthesisVoiceQualityPremium
import platform.AVFAudio.AVSpeechSynthesisVoiceTraitIsPersonalVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSDate
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * AVSpeechSynthesizer behind the common [Speaker] interface. No Swift.
 *
 * The delegate is the piece #3 flagged as the interop risk, and it resolves the
 * same way the WebKit handlers did: an `NSObject()` subclass conforming to an
 * Obj-C protocol. Two Kotlin-specific wrinkles:
 *
 *  1. Every delegate callback is named `speechSynthesizer` and they differ only
 *     in the second parameter, so all five need `@ObjCSignatureOverride` --
 *     each one collides with the other four, so there is no "first" to exempt.
 *  2. An exception escaping a callback crosses into Obj-C and kills the process
 *     instead of propagating, so each one swallows and reports.
 *
 * `willSpeakRangeOfSpeechString` is deliberately not implemented. It is the
 * word-level highlighting hook, and #3 found it unreliable; chunk-level
 * highlighting needs only start/finish, which is why the feature was scoped to
 * chunks in the first place.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSpeaker : Speaker {

    private val _events = MutableSharedFlow<SpeechEvent>(replay = 8, extraBufferCapacity = 64)
    override val events: SharedFlow<SpeechEvent> = _events

    private val synthesizer = AVSpeechSynthesizer()

    override val isSpeaking: Boolean get() = synthesizer.isSpeaking()

    private fun now(): Long = nowMillis()

    private fun emit(event: SpeechEvent) {
        _events.tryEmit(event)
    }

    // Correlates delegate callbacks back to the speak() that caused them.
    // A cancelled utterance can report *after* its replacement has been handed
    // to the synthesizer, so a single "current id" field would mislabel it and
    // the sequencing loop would treat the stale cancel as its own.
    private var nextId = 1L
    private val idsByUtterance = mutableMapOf<AVSpeechUtterance, Long>()

    private fun idOf(utterance: AVSpeechUtterance): Long = idsByUtterance[utterance] ?: 0L

    private fun retire(utterance: AVSpeechUtterance): Long =
        idsByUtterance.remove(utterance) ?: 0L

    // Retained deliberately: AVSpeechSynthesizer holds its delegate weakly.
    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didStartSpeechUtterance: AVSpeechUtterance,
        ) {
            runCatching {
                emit(
                    SpeechEvent.Started(
                        now(),
                        idOf(didStartSpeechUtterance),
                        didStartSpeechUtterance.speechString,
                    )
                )
            }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            runCatching { emit(SpeechEvent.Finished(now(), retire(didFinishSpeechUtterance))) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            runCatching { emit(SpeechEvent.Cancelled(now(), retire(didCancelSpeechUtterance))) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didPauseSpeechUtterance: AVSpeechUtterance,
        ) {
            runCatching { emit(SpeechEvent.Paused(now(), idOf(didPauseSpeechUtterance))) }
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didContinueSpeechUtterance: AVSpeechUtterance,
        ) {
            runCatching { emit(SpeechEvent.Continued(now(), idOf(didContinueSpeechUtterance))) }
        }
    }

    init {
        synthesizer.delegate = delegate
        configureAudioSession()
        observeInterruptions()
    }

    /**
     * Playback + spokenAudio: keeps speech going when the ringer switch is
     * silent, and tells the system this is speech so other spoken audio ducks
     * rather than mixes. Failure is reported, not thrown -- the simulator can
     * refuse the session while speech still works.
     */
    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        memScopedError { errPtr ->
            session.setCategory(
                category = AVAudioSessionCategoryPlayback,
                mode = AVAudioSessionModeSpokenAudio,
                options = 0uL,
                error = errPtr,
            )
        }?.let { emit(SpeechEvent.Failed(now(), 0L, "audio session category: $it")) }

        // No explicit setActive: AVSpeechSynthesizer activates the session
        // itself when it starts speaking, and `setActive:error:` is not exposed
        // on AVAudioSession by the Kotlin/Native AVFAudio bindings anyway.
        // Setting the category is the part that changes behaviour.
    }

    override fun speak(text: String, settings: SpeechSettings): Long {
        val id = nextId++
        val speakable = text.trim()
        if (speakable.isEmpty()) {
            // Nothing to say, but the caller is awaiting a completion, so
            // synthesise one rather than stalling the sequencing loop.
            emit(SpeechEvent.Started(now(), id, ""))
            emit(SpeechEvent.Finished(now(), id))
            return id
        }

        if (synthesizer.isSpeaking()) {
            synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }

        val utterance = AVSpeechUtterance.speechUtteranceWithString(speakable).apply {
            setRate(settings.rate)
            setPitchMultiplier(settings.pitch)
            setVolume(settings.volume)
            setPreUtteranceDelay(settings.preUtteranceDelaySec)
            setPostUtteranceDelay(settings.postUtteranceDelaySec)
            settings.voiceId
                ?.let { AVSpeechSynthesisVoice.voiceWithIdentifier(it) }
                ?.let { setVoice(it) }
        }
        idsByUtterance[utterance] = id
        synthesizer.speakUtterance(utterance)
        return id
    }

    override fun pause() {
        if (synthesizer.isSpeaking()) synthesizer.pauseSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryWord)
    }

    override fun resume() {
        synthesizer.continueSpeaking()
    }

    override fun stop() {
        // Reports as `didCancel`, never `didFinish`, so the sequencing loop can
        // tell "the reader stopped me" from "the chunk ended".
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
    }

    /**
     * Pause for a phone call or another app taking audio, and report it.
     *
     * Without this the synthesizer is stopped by the system and the session
     * would sit thinking it is still speaking -- so read-aloud would appear to
     * hang rather than pause.
     */
    private fun observeInterruptions() {
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVAudioSessionInterruptionNotification,
            `object` = null,
            queue = null,
        ) { notification ->
            runCatching {
                val type = (notification?.userInfo
                    ?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
                when (type) {
                    AVAudioSessionInterruptionTypeBegan -> {
                        emit(SpeechEvent.Interrupted(now()))
                    }
                    AVAudioSessionInterruptionTypeEnded -> {
                        val options = (notification?.userInfo
                            ?.get(AVAudioSessionInterruptionOptionKey) as? NSNumber)
                            ?.unsignedLongValue ?: 0uL
                        // Only resume when the system says we may; resuming
                        // uninvited talks over whatever took the audio.
                        val mayResume =
                            (options and AVAudioSessionInterruptionOptionShouldResume) != 0uL
                        emit(SpeechEvent.InterruptionEnded(now(), mayResume))
                    }
                }
            }
        }
    }

    override fun voices(languagePrefix: String): List<VoiceInfo> =
        AVSpeechSynthesisVoice.speechVoices()
            .filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.startsWith(languagePrefix, ignoreCase = true) }
            .map { voice ->
                VoiceInfo(
                    identifier = voice.identifier,
                    name = voice.name,
                    language = voice.language,
                    quality = when (voice.quality) {
                        AVSpeechSynthesisVoiceQualityPremium -> "premium"
                        AVSpeechSynthesisVoiceQualityEnhanced -> "enhanced"
                        else -> "default"
                    },
                    isPersonal =
                        (voice.voiceTraits and AVSpeechSynthesisVoiceTraitIsPersonalVoice) != 0uL,
                )
            }
            // Best first. #3 established these cannot be *downloaded* via API --
            // only used once the user has installed them in Settings.
            .sortedByDescending {
                when (it.quality) {
                    "premium" -> 2
                    "enhanced" -> 1
                    else -> 0
                }
            }
}

actual fun createSpeaker(): Speaker = IosSpeaker()

/**
 * Runs an `NSError**`-style call and returns its message, or null on success.
 */
@OptIn(ExperimentalForeignApi::class)
private inline fun memScopedError(
    block: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean,
): String? = memScoped {
    val err = alloc<ObjCObjectVar<NSError?>>()
    if (block(err.ptr)) null else err.value?.localizedDescription ?: "unknown error"
}
