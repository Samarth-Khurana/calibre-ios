# iOS TTS capabilities, and their reachability from Kotlin/Native

Research for issue [#3](https://github.com/Samarth-Khurana/calibre-ios/issues/3). Capabilities and limits only — no design.

Researched August 2026. Version-sensitive facts carry an iOS version. Current shipping OS at time of writing is iOS 26.x; nothing below requires anything newer than iOS 17 except where noted.

## Verdict in one line

Every capability the motivating feature needs exists on iOS and is reachable from Kotlin/Native, **including the delegate protocol** — which is the part the ticket flagged as risky and which turns out to be routine. The real constraints are elsewhere: enhanced/premium voices cannot be downloaded programmatically, and `preUtteranceDelay` is not documented to be the right hook for "highlight, then wait, then speak".

---

## 1. AVSpeechSynthesizer: speaking a caller-defined chunk

The synthesizer speaks whatever string you hand it. There is no sentence/paragraph segmentation imposed by the framework — `AVSpeechUtterance(string:)` takes an arbitrary `String`, so chunking by a user-chosen delimiter in shared code and feeding one chunk per utterance is exactly the intended usage.

Queueing is explicit in the docs:

> "The speech synthesizer maintains a queue of utterances that it speaks. If the synthesizer isn't speaking, calling `speak(_:)` begins speaking that utterance either immediately or after pausing for its `preUtteranceDelay`, if necessary. If the synthesizer is speaking, the synthesizer adds utterances to a queue and speaks them in the order it receives them."

— [AVSpeechSynthesizer](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer)

So both architectures are available: enqueue the whole chapter up front and let the queue drain, or speak one chunk and enqueue the next on `didFinish`. They differ in what pause/stop mean (see §3).

Two documented gotchas from the same page:

- **"The system doesn't automatically retain the speech synthesizer, so you need to manually retain it until speech concludes."** A synthesizer created as a local and left to ARC will go silent. Applies equally to Kotlin/Native, where an object with no strong reference is collectable.
- `stopSpeaking(at:)` "stops the speech entirely and remove[s] all remaining utterances in the queue" — stop is destructive to the queue, pause is not.

### `willSpeakRangeOfSpeechString` and what range it reports

Signature and semantics:

- `speechSynthesizer(_:willSpeakRangeOfSpeechString:utterance:)` — "Tells the delegate when the synthesizer is about to speak a portion of an utterance's text." ([AVSpeechSynthesizerDelegate](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizerdelegate))
- The `characterRange` is an `NSRange` **relative to the utterance's own `speechString`**, not to any larger document. If you chunk the book and speak one chunk per utterance, the ranges you receive are offsets *within that chunk*; mapping back to a document offset is the caller's job.
- In practice it fires roughly per word. Apple does not contract a granularity, which is the root of the bug reports below.

**This callback is a liability, and the chunk-at-a-time design does not need it.** Documented, long-running defects:

- iOS 14.2+ — the synthesizer *crashes* when a delegate implements `willSpeakRangeOfSpeechString`, but only for certain voices; the default voice crashed while enhanced "Alex" did not. ([Apple Developer Forums 671863](https://developer.apple.com/forums/thread/671863))
- iOS 17 — voices stop at random points mid-string, `willSpeakRange` "jumps all over the place", and `didFinish` fires prematurely (reported against "Daniel Enhanced"). ([Apple Developer Forums 738048](https://developer.apple.com/forums/thread/738048), [737685](https://developer.apple.com/forums/thread/737685))
- Wrong character ranges for some tokens, e.g. numerals like "2020". ([Apple Developer Forums 133104](https://developer.apple.com/forums/thread/133104))

None of this affects highlighting a whole chunk before speaking it, which needs only `didStart` / `didFinish` and the app's own chunk index. **Word-level highlighting (the justRead behaviour) is the fragile capability; chunk-level highlighting is not.** These are different risk profiles and should not be conflated.

Adjacent, if word-level ever comes back: `speechSynthesizer(_:willSpeak:utterance:)` (iOS 17+) reports `AVSpeechSynthesisMarker` objects rather than ranges, and is the newer mechanism, tied to SSML/marker input.

---

## 2. Highlight chunk → wait a configurable interval → speak

### What `preUtteranceDelay` gives you

`AVSpeechUtterance.preUtteranceDelay` is a `TimeInterval`, default `0.0`, described as the silence duration before the utterance's speech begins; `postUtteranceDelay` is its mirror. ([AVSpeechUtterance](https://developer.apple.com/documentation/avfaudio/avspeechutterance))

So the *audible* pause between chunks is available for free, via either property, with no timers.

### What it does not give you

The requirement is not just silence — it is **highlight first, then silence, then speech**. That needs a callback at the *start* of the delay window. Apple does not document when `didStart` fires relative to `preUtteranceDelay`:

- `speechSynthesizer(_:didStart:)` is documented only as "Tells the delegate when the synthesizer begins speaking an utterance" ([AVSpeechSynthesizerDelegate](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizerdelegate)) — "begins speaking" most naturally reads as after the delay, i.e. too late to drive a pre-speech highlight.
- No Apple source found states the ordering either way. Searches of the developer forums turned up no authoritative answer.

**Consequence: `preUtteranceDelay` alone is not established as sufficient.** Two positions the implementer can take, both fully supported by the API:

1. **Manual sequencing** — highlight chunk N in app code, wait a caller-controlled interval on the app's own clock, then `speak(utterance)` with `preUtteranceDelay = 0`. Deterministic, delay is trivially reconfigurable at runtime, no dependence on undocumented callback ordering. Costs a timer and makes the synthesizer queue effectively depth-1.
2. **Queue-driven** — enqueue chunks with `preUtteranceDelay` set, drive highlighting from a delegate callback. Cheaper, smoother audio, but depends on `didStart` firing before the delay — which must be **measured on device, not assumed**.

This is a genuinely open empirical question and a good candidate for the prototype ticket. It is cheap to settle: log timestamps for `didStart` against a known `preUtteranceDelay`.

### Other sequencing gotchas

- **Pause/resume mid-utterance.** `pauseSpeaking(at:)` takes an `AVSpeechBoundary` — `.immediate` or `.word`. Resuming is `continueSpeaking()`. Both return `Bool`; they can fail. Pausing does not fire `didFinish`, it fires `didPause`, and `continueSpeaking()` fires `didContinue`. A state machine driven only by `didStart`/`didFinish` will desync across a pause.
- **Pause does not interact with `preUtteranceDelay` in any documented way.** If the app is pausing inside a delay window rather than inside speech, behaviour is unspecified. Another reason manual sequencing is the conservative choice.
- **`didCancel` has been unreliable.** Reported not firing on iOS 15. ([Apple Developer Forums 691347](https://developer.apple.com/forums/thread/691347)) Do not make cancellation cleanup depend solely on it.
- **Delegate callbacks and threading.** Apple does not document a queue guarantee for these callbacks; UI highlighting must be hopped to the main thread explicitly rather than assumed.

### SSML as an alternative pause mechanism

iOS 16 added `AVSpeechUtterance(ssmlRepresentation:)`, which accepts SSML including `<break>`. This can express inter-chunk pauses inside a *single* utterance. It moves the pause into the audio engine and away from app control, and it gives no per-chunk callback, so it does not solve the highlight-before-speech problem — noted for completeness only.

---

## 3. Voice quality: Enhanced / Premium / Personal Voice

### The quality tiers

`AVSpeechSynthesisVoice.quality` returns `AVSpeechSynthesisVoiceQuality`, with three cases, all available since iOS 9 as *symbols* (the premium **voices** themselves arrived with iOS 16):

| Case | Apple's description |
|---|---|
| `.default` | "A basic quality voice that's available on the device by default." |
| `.enhanced` | "An enhanced quality voice that you must download to use." |
| `.premium` | "A premium quality voice that you must download to use." |

— [AVSpeechSynthesisVoiceQuality](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoicequality)

**All on-device. No account, no network at speech time, no cost.** This is the "downloadable natural voices" that justRead advertises — it is not a third-party engine, it is the system voice catalogue.

### The hard limit: no download API

This is the single most important constraint in this section.

- `AVSpeechSynthesisVoice.speechVoices()` "Retrieves all available voices on the device" — meaning **only those already installed**. ([AVSpeechSynthesisVoice](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoice))
- There is **no API to enumerate voices available-but-not-downloaded, and no API to trigger a download.** Developers have filed requests for both. ([Apple Developer Forums 758460](https://developer.apple.com/forums/thread/758460), [787779](https://developer.apple.com/forums/thread/787779))
- The user must install them by hand, in Settings → Accessibility → Spoken Content (or Live Speech) → Voices. Enhanced/premium voices are >100MB each.

Practical consequences, stated as constraints not solutions: a fresh install will have only `.default` voices; the app cannot detect that better ones exist; anything the app says about voice quality has to be inferred from what `speechVoices()` returns, and any instruction to the user is a prose instruction to visit Settings.

A related reported defect: `speechVoices()` has returned voices that are listed but not actually usable after an iOS upgrade. ([Apple Developer Forums 735893](https://developer.apple.com/forums/thread/735893)) Selecting a voice by identifier should tolerate failure.

### Personal Voice (iOS 17+)

- Gated behind an explicit authorization prompt: `AVSpeechSynthesizer.requestPersonalVoiceAuthorization(completionHandler:)`, iOS 17.0+ / macOS 14.0+ / watchOS 10.0+ / visionOS 1.0+. Async variant available. Current state readable from `personalVoiceAuthorizationStatus`. ([Apple docs](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer/requestpersonalvoiceauthorization(completionhandler:)))
- **No entitlement is documented as required** — it is a privacy authorization, not a capability. (Apple's docs list none; corroborated by a working third-party implementation, [Ben Dodson, 2024](https://bendodson.com/weblog/2024/04/03/using-your-personal-voice-in-an-ios-app/).) This matters less here anyway, since distribution is personal sideload.
- Once authorized, personal voices appear in `speechVoices()` and are identified via `AVSpeechSynthesisVoice.Traits.isPersonalVoice` (iOS 17+, an `OptionSet`; the sibling trait is `isNoveltyVoice`). ([Traits](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoice/traits))
- Created and stored **on-device via on-device machine learning**; the user records it themselves in Settings. Only newer hardware supports creation — Dodson reports older-but-supported devices returning `.denied` rather than `.unsupported`, so status handling must not assume the enum is meaningful.
- `AVSpeechSynthesizer.availableVoicesDidChangeNotification` exists to observe the voice list changing (e.g. after the user downloads one or authorizes personal voice) without polling.
- A Personal Voice regression against `AVSpeechSynthesizer` was reported in the iOS 26 beta cycle. ([Apple Developer Forums 792409](https://developer.apple.com/forums/thread/792409))

Assessment: Personal Voice is a nice-to-have with a real support tail. Enhanced/premium are the load-bearing quality story.

---

## 4. Background audio and lock-screen / Now Playing controls

Standard, well-trodden, all three pieces independent:

**Audio session.** `AVAudioSession.Category.playback` — "Audio continues with the Silent switch"; audio "plays when the screen locks" and when the app backgrounds. Nonmixable by default; `.mixWithOthers` option available. ([Apple docs](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback))

**Info.plist.** Background playback requires `UIBackgroundModes` to contain `audio`. Same source. Not optional — without it audio stops at lock.

**The synthesizer's own session.** `AVSpeechSynthesizer.usesApplicationAudioSession` (iOS 13+): "A Boolean value that specifies whether the app manages the audio session. If you set this value to `false`, the system creates a separate audio session to automatically manage speech, interruptions, and mixing and ducking the speech with other audio sources." ([Apple docs](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer/usesapplicationaudiosession)) So there is a fork: let the app configure `playback` itself (leave `true`, the default), or hand session management to the system (`false`) and lose control over category/backgrounding. For background reading, the app must own the session.

**Lock-screen controls.** `MPRemoteCommandCenter.shared()` (iOS 7.1+) exposes `playCommand`, `pauseCommand`, `togglePlayPauseCommand`, `stopCommand`, `nextTrackCommand`, `previousTrackCommand`, `skipForwardCommand`, `skipBackwardCommand`, `changePlaybackPositionCommand`, `changePlaybackRateCommand`, and more. ([Apple docs](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter)) Enable only the commands you handle — enabled-but-unhandled commands are how Now Playing UIs end up with dead buttons. Metadata for the lock screen goes through `MPNowPlayingInfoCenter`. Apple's own guidance is the "Becoming a now-playable app" article.

Note the mapping problem this creates: remote commands are track-shaped (next/previous track, scrub position) while the reading model is chunk-shaped. That is a design question, out of scope here — but the API surface does not constrain the answer, since commands can be selectively enabled and mapped to whatever the app wants.

---

## 5. Kotlin/Native interop

### The framework surface is already there

Kotlin/Native ships platform libraries for the Apple SDK frameworks; no hand-written `.def` cinterop file is needed for any of the above. `platform.AVFAudio.*` and `platform.MediaPlayer.*` are importable directly. Confirmed by GitHub code search across public repositories: **29 public Kotlin files reference `AVSpeechSynthesizerDelegateProtocol`, and 29 reference `platform.MediaPlayer.MPRemoteCommandCenter`, with 74 referencing `MPNowPlayingInfoCenter`.** This is a well-worn path, not frontier work.

### Protocol conformance from Kotlin — the flagged risk, and it does not materialise

Kotlin's official position:

> "Protocols are imported as interfaces with a `Protocol` name suffix, for example, `@protocol Foo` → `interface FooProtocol`."

> "Swift/Objective-C classes and protocols can be subclassed with a Kotlin `final` class. Non-`final` Kotlin classes inheriting Swift/Objective-C types aren't supported yet... Normal methods can be overridden using the `override` Kotlin keyword. In this case, the overriding method must have the same parameter names as the overridden one."

— [Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)

Concrete evidence that this works for *this specific protocol* — a KMP EPUB reader, essentially the same problem this project has, from [Aryan-Raj3112/episteme](https://github.com/Aryan-Raj3112/episteme/blob/f80365ea468464315446986c39831a6e25ba7296/shared/src/iosMain/kotlin/com/aryan/reader/shared/ios/IosBookTtsListening.kt):

```kotlin
private class IosBookTtsSpeechDelegate(...) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    override fun speechSynthesizer(
        synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString: CValue<NSRange>,
        utterance: AVSpeechUtterance,
    ) { ... }
}
```

The same file configures the audio session via `AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)`. So the full chain — synthesizer, delegate protocol conformance, range callback, audio session — has been demonstrated from Kotlin in a shipping-shaped project.

### Concrete details worth knowing before writing any of it

- **Delegate classes must extend `NSObject()`** (`platform.darwin.NSObject`) as well as implementing the `…Protocol` interface. Kotlin interface implementation alone does not produce an object the Objective-C runtime will accept as a delegate.
- **`final` only.** Per the Kotlin docs above, the delegate class cannot be open or abstract, and cannot sit in a hierarchy that inherits Obj-C types. This forecloses "abstract base delegate + per-platform subclass" designs.
- **Parameter names are load-bearing.** Overrides must match the imported Kotlin parameter names exactly — note above that the second parameter is literally named `willSpeakRangeOfSpeechString`, not `characterRange`, because Kotlin derives names from the Obj-C selector. Getting this wrong is a compile error, not a silent failure, so it is annoying rather than dangerous.
- **`NSRange` arrives as `CValue<NSRange>`**, not a plain struct; reading `.location`/`.length` requires `useContents { }` and the whole file needs `@OptIn(ExperimentalForeignApi::class)`.
- **Optional protocol methods.** `AVSpeechSynthesizerDelegate`'s methods are all optional in Obj-C. Kotlin imports them as interface members with default implementations, so you override only what you need — the example above overrides four of the seven. The Kotlin docs do not spell this out explicitly; the working example is the evidence.
- **Retain the delegate and the synthesizer.** Obj-C delegate references are typically weak, and `AVSpeechSynthesizer` is documented as not self-retaining. Both must be held in a long-lived Kotlin object or speech dies unpredictably. This is the most likely source of "works in a test, fails in the app" behaviour.
- **`@ObjCSignatureOverride`** exists for the case where several inherited Obj-C methods collapse to clashing Kotlin signatures. `AVSpeechSynthesizerDelegate` has several `speechSynthesizer(...)` overloads distinguished only by their second parameter's name; if a future SDK adds a colliding one, this annotation is the escape hatch.
- **Exceptions do not cross the boundary.** A Kotlin exception thrown inside a delegate callback and not declared `@Throws` "reaching Swift/Objective-C [is] considered unhandled and cause[s] program termination." Delegate callbacks must not let exceptions escape — a parse error in chunk-mapping code becomes a process crash, not a caught error.
- **Threading.** Delegate callbacks arrive on whatever queue AVFoundation chooses; UI updates must be dispatched to the main queue explicitly.

### Bottom line for the KMP question

Nothing in the TTS feature requires Swift. The delegate — flagged in the ticket as the risky part — is demonstrably implementable from Kotlin, with multiple independent public examples. If a Swift/KMP boundary is drawn for this project, TTS is not the reason it has to be drawn.

---

## Open questions this research did not settle

1. **Does `didStart` fire before or after `preUtteranceDelay`?** Undocumented by Apple; no authoritative source found. Decides whether §2's queue-driven approach is viable. Cheap to measure on device.
2. **Are the iOS 17-era `willSpeakRange`/`didFinish` defects still present on iOS 26?** The reports are from iOS 14–17. Only matters if word-level highlighting is ever wanted; irrelevant to chunk-level.
3. **What does `didFinish` timing look like relative to `postUtteranceDelay`?** Same shape of question as (1), relevant to how a chunk's highlight is cleared.

## Sources

- [AVSpeechSynthesizer](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer)
- [AVSpeechSynthesizerDelegate](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizerdelegate)
- [AVSpeechUtterance](https://developer.apple.com/documentation/avfaudio/avspeechutterance)
- [AVSpeechSynthesisVoice](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoice)
- [AVSpeechSynthesisVoiceQuality](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoicequality)
- [AVSpeechSynthesisVoice.Traits](https://developer.apple.com/documentation/avfaudio/avspeechsynthesisvoice/traits)
- [requestPersonalVoiceAuthorization(completionHandler:)](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer/requestpersonalvoiceauthorization(completionhandler:))
- [AVSpeechSynthesizer.usesApplicationAudioSession](https://developer.apple.com/documentation/avfaudio/avspeechsynthesizer/usesapplicationaudiosession)
- [AVAudioSession.Category.playback](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback)
- [MPRemoteCommandCenter](https://developer.apple.com/documentation/mediaplayer/mpremotecommandcenter)
- [Kotlin — Interoperability with Swift/Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)
- [episteme — IosBookTtsListening.kt](https://github.com/Aryan-Raj3112/episteme/blob/f80365ea468464315446986c39831a6e25ba7296/shared/src/iosMain/kotlin/com/aryan/reader/shared/ios/IosBookTtsListening.kt)
- [IReader — TTSEngine.ios.kt](https://github.com/IReaderorg/IReader/blob/424cb6794d45468e497152a1dacc84e0f78fde95/domain/src/iosMain/kotlin/ireader/domain/services/tts_service/TTSEngine.ios.kt)
- [Ben Dodson — Using your Personal Voice in an iOS app (2024)](https://bendodson.com/weblog/2024/04/03/using-your-personal-voice-in-an-ios-app/)
- Apple Developer Forums: [671863](https://developer.apple.com/forums/thread/671863), [738048](https://developer.apple.com/forums/thread/738048), [737685](https://developer.apple.com/forums/thread/737685), [133104](https://developer.apple.com/forums/thread/133104), [691347](https://developer.apple.com/forums/thread/691347), [758460](https://developer.apple.com/forums/thread/758460), [787779](https://developer.apple.com/forums/thread/787779), [735893](https://developer.apple.com/forums/thread/735893), [792409](https://developer.apple.com/forums/thread/792409)
