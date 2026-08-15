# Can KMP render a reflowable EPUB on iOS?

Research for [issue #2](https://github.com/Samarth-Khurana/calibre-ios/issues/2). **Survey only — no recommendation.**
Researched August 2026. Version numbers are as of 2026-08-15.

## The evaluation axis

Every option below is judged first on one question: **can it report and highlight a text range at line/sentence granularity?** The map's motivating feature is TTS that highlights the chunk it is *about to* speak. An option that renders beautifully but cannot address a text range is a dead end.

Two distinct capabilities are needed and should not be conflated:

1. **Range reporting** — given the visible content, produce a stable address for "this sentence" that survives resize and reflow.
2. **Range decoration** — given that address, draw a highlight over exactly those glyphs, and scroll/paginate to it.

---

## Summary table

| Option | Reflow + pagination | Per-range addressing | Per-range highlight | Runs from Kotlin on iOS |
|---|---|---|---|---|
| Readium Kotlin toolkit | Yes (Android only) | Yes (`Locator`) | Yes (Decoration API) | **No** — Android-only artifacts |
| Readium Swift toolkit | Yes | Yes (`Locator`) | Yes (Decoration API) | **No** — Swift API not Obj-C exportable |
| Compose Multiplatform native text | No HTML/CSS engine | Yes (`TextLayoutResult`, per-line) | Yes (own draw) | Yes |
| WKWebView driven from Kotlin/Native | Yes (browser engine) | Yes (DOM `Range` / CFI) | Yes (CSS overlay) | **Yes — proven in the wild** |

---

## 1. Readium

### 1a. `readium/kotlin-toolkit` — Android only, and the coupling is deep

Latest release **3.3.0, 2026-06-02** ([releases](https://github.com/readium/kotlin-toolkit/releases)). Modules: `readium-shared`, `readium-streamer`, `readium-navigator`, `readium-opds`, `readium-lcp` ([getting-started](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/getting-started.md)).

**Is the parsing/navigation layer separable from rendering?** Architecturally yes — `shared` (models) and `streamer` (parsers) are separate Gradle modules from `navigator` (rendering). **In practice, no**, because those modules are not merely *published* as Android libraries; they are *written against the Android SDK*:

- `readium/shared/src/main/java/org/readium/r2/shared/util/Url.kt` — the toolkit's most fundamental value type — imports `android.net.Uri`. Every `Publication`, `Link`, and `Locator` href flows through it.
- Same for `util/http/HttpRequest.kt`, `extensions/String.kt`, `extensions/ContentResolver.kt`.
- `readium/shared/build.gradle.kts` applies the Android library convention plugin and depends on `androidx.annotation` and `kotlinx-coroutines-android`.

So "just take the parser" is not a repackaging job; it is a port of the core URL/IO types.

**Maintainer position on KMP** (primary source, both are Readium maintainers):

> "Yes, we want to do it. I doubt it will be completed before one year though, at least if Mickaël and I have to do all the work. Subliminal message: feel free to do it."
> — qnga, [discussion #547](https://github.com/readium/kotlin-toolkit/discussions/547), 2024-07-17

> "The Kotlin toolkit is not KMP yet. You might still be able to write a KMP wrapper around the Kotlin toolkit for the Android target and the Swift toolkit for the iOS target." … "Yes, we'd love to, but it's not a priority. Feel free to contribute if you can."
> — qnga, [discussion #625](https://github.com/readium/kotlin-toolkit/discussions/625), 2025-03-17

Two years on from #547, no KMP work has landed. Treat KMP-Readium as **not coming on this project's timeline**. The maintainer's own suggested shape — a KMP `expect`/`actual` wrapper over two native toolkits — is the realistic Readium path, and it means writing and maintaining the iOS half in Swift.

### 1b. `readium/swift-toolkit` — does everything we need, in Swift

Latest **3.11.0 (2026-07-17)**, with **4.0.0-alpha.1 released 2026-08-14** ([releases](https://github.com/readium/swift-toolkit/releases)) — a major version is actively in flight, so the API is a moving target right now.

This toolkit already implements, natively, the exact feature this project is built around:

- `PublicationSpeechSynthesizer` iterates publication content, splits it into utterances via a `ContentTokenizer` ("usually by sentences"), and speaks them with a pluggable `TTSEngine` (default: Apple Speech Synthesis). It is "opened for extension if you want to use a different TTS engine" — which matters for the map's user-configurable delimiter chunking.
- Its `.playing(Utterance, range: Locator?)` state is "updated repeatedly while the utterance is spoken, updating the `range` value with the portion of utterance being played (usually the current word)". That is **word-level range reporting, already shipped**.
- `navigator.firstVisibleElementLocator()` starts TTS from the visible page.
- Highlighting the spoken utterance is the documented pattern: apply a `Decoration` on the utterance's `Locator` via `DecorableNavigator`.
  ([TTS guide](https://github.com/readium/swift-toolkit/blob/develop/docs/Guides/TTS.md))

The **Decoration API** ([Decorations guide](https://github.com/readium/swift-toolkit/blob/develop/docs/Guides/Navigator/Decorations.md)) pairs a `Locator` with an abstract `Decoration.Style` (`highlight`, `underline`, or custom). For EPUB each style maps to an `HTMLDecorationTemplate` that "translates the abstract style into concrete HTML/CSS injected into the page". Decorations are declared as groups (`highlights`, `search`, `tts`) and the navigator diffs group state internally. Tap callbacks return `event.decoration.id` and `event.rect`. Caveat stated in the docs: **"Only `EPUBNavigatorViewController` implements `DecorableNavigator` today."** Fine for us — V1 is EPUB only.

The address type is [`Locator`](https://readium.org/architecture/models/locators/): `href` + `type`, plus `locations` (`progression`, `totalProgression`, `position`, `fragments`) and a `text` object (`before` / `highlight` / `after`). The `text` context is what makes a locator survive reflow — you can re-find the range by its surrounding text even if pagination changed.

**Can Kotlin/Native consume it?** Not directly. Kotlin/Native interops with **C and Objective-C**, not Swift. Swift APIs are only visible if exposed via `@objc`, which requires classes inheriting `NSObject` and Obj-C-representable signatures — the Readium Swift toolkit is idiomatic modern Swift (structs, protocols with associated types, generics, `async`), essentially none of which is `@objc`-exportable. The `swift-export` work JetBrains ships is **Kotlin → Swift** (still Alpha in Kotlin 2.3/2.4, "not production-ready", per [What's new in Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html) and the [swift-export-sample](https://github.com/Kotlin/swift-export-sample)) — it does not make Swift libraries callable from Kotlin. Using swift-toolkit therefore means **hand-writing an Obj-C-compatible Swift shim layer** for every call and callback you need, and that shim is Swift code you own forever.

### 1c. The bit of Readium that *is* portable

Both toolkits render reflowable EPUB by injecting the same JavaScript into a web view. The Swift toolkit ships `readium-reflowable.js` / `readium-fixed.js` under `Sources/Navigator/EPUB/Assets/Static/scripts/`; the Kotlin toolkit ships `readium-reflowable.js` / `readium-fixed.js` under `readium/navigator/src/main/assets/readium/scripts/`. **The rendering engine is JavaScript on both platforms** — the native layer is a driver, not a renderer. That is the single most important structural fact in this survey: it means the "native toolkit" advantage is thinner than it looks, and it makes the WKWebView option below a re-implementation of the *driver*, not of the *renderer*.

---

## 2. Compose Multiplatform native text

Latest **1.11.1, released 2026-06-02** ([what's new](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html)). iOS is stable; minimum iOS raised 13.0 → 14.0; `iosX64` target removed; requires Kotlin 2.3.10+. 1.11.1 adds **experimental** native iOS text input via `UITextInput`/`UIKeyInput` (`usingNativeTextInput(true)`, `@ExperimentalComposeUiApi`) giving native caret, selection handles, and system context menus.

**What it can do for our axis — better than expected.** Compose's `TextLayoutResult` (commonMain, `androidx.compose.ui.text`) exposes `getLineForOffset`, `getLineStart`, `getLineEnd`, `getBoundingBox(offset)`, and `getPathForRange`. If you lay text out yourself, you get **exact per-line and per-character-range geometry for free**, and drawing a highlight behind an arbitrary range is trivial. Range addressing is *not* the problem with this option.

**What it cannot do — and this is decisive.** There is no HTML/CSS layout engine in Compose. To render an EPUB spine item natively you must parse XHTML+CSS and reimplement layout yourself: floats, tables, nested lists, `@font-face`, MathML, SVG, RTL/vertical writing modes, publisher stylesheets, page-break hints, footnote popups. Real EPUBs from a Calibre library are arbitrary publisher HTML, not a controlled subset. Nothing in the Compose Multiplatform release notes through 1.11.1 addresses this; the mature rich-text options in the ecosystem ([compose-rich-editor](https://github.com/MohamedRejeb/compose-rich-editor), [richtext-compose-multiplatform](https://github.com/Wavesonics/richtext-compose-multiplatform)) target *editors over a constrained markup subset*, and even those document limits (e.g. inline images) — they are not EPUB renderers. Compose 1.9.0 did add an HTML escape hatch, `WebElementView` (renamed `HtmlElementView`), but that is **web-target only** — it embeds a DOM element in Compose-for-Web, not on iOS ([1.9.0 announcement](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/), [what's new 1.9.x](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html)).

Verdict for the record: **great range API, no renderer.** Building the renderer is a multi-year browser-engine project, not a V1 task.

---

## 3. WKWebView driven from Kotlin/Native

This is what Readium itself does on both platforms (see 1c), and what the JS ecosystem is built for.

### The boundary, concretely

Everything needed is in Kotlin/Native's `platform.WebKit` platform library, generated from Apple's Obj-C headers — **no Swift, no shim**:

- `WKWebView`, `WKWebViewConfiguration`, `WKUserContentController`, `WKUserScript` / `WKUserScriptInjectionTime` (inject the reader JS at document start).
- **Kotlin → JS:** `webView.evaluateJavaScript(script, completionHandler)`.
- **JS → Kotlin:** implement `WKScriptMessageHandlerProtocol` on a `NSObject` subclass in Kotlin and register it with `contentController.addScriptMessageHandler(handler, name = "reader")`; JS calls `window.webkit.messageHandlers.reader.postMessage(...)`.
- **Embedding in Compose:** `androidx.compose.ui.viewinterop.UIKitView` with `UIKitInteropProperties` / `UIKitInteropInteractionMode` — the last one matters, because text selection inside the web view requires the native view to receive touches rather than Compose swallowing them.

Costs of the boundary, honestly:

- **Async and one-way-typed.** `evaluateJavaScript` is fire-and-forget-or-callback; `postMessage` bodies arrive as `NSObject` and are in practice serialized strings/JSON. Expect a hand-rolled envelope (Episteme below uses `"$method\n$payload"` and parses with `kotlinx.serialization`). Every call is a serialization hop, so per-frame chatter (e.g. scroll position at 60fps) must be throttled JS-side.
- **`@OptIn(ExperimentalForeignApi::class)`** is required across the interop surface, and `NSRange` etc. arrive as `CValue<>` needing `useContents { }`.
- **Lifecycle is manual.** `removeScriptMessageHandlerForName` on dispose, or the handler retains the web view and leaks.
- **Historically flaky enough to be worth noting:** [compose-multiplatform#3922](https://github.com/JetBrains/compose-multiplatform/issues/3922) (Nov 2023, Kotlin 1.9.20 / CMP 1.5.10) reported `addScriptMessageHandler` callbacks never firing from Kotlin/Native. Closed without a documented root cause. It demonstrably works today (see §4), but budget time for interop debugging where the failure mode is silence.
- **Two languages, one feature.** Any range logic lives in JS; Kotlin holds state and policy. This is a real, permanent architectural tax — but it is the same tax Readium pays, in Swift.

### Range addressing across the boundary — the good news

This is the option's strongest suit, because the DOM already has the primitive: a native `Range` object, with `getClientRects()` for geometry and `document.caretRangeFromPoint` / `Intl.Segmenter` for sentence and word splitting.

- **[foliate-js](https://github.com/johnfactotum/foliate-js)** paginates with CSS multi-column (same strategy as epub.js) but is a standalone module with **no CFI concept — it operates on `Range` objects directly**, uses bisection to find the currently visible range (documented as more accurate than epub.js), anchors the view to a `Range`/`Element`/fraction across resizes, and emits `event.detail = { range, index, fraction }` on every location change. That is exactly "report me the visible text range", built in.
- **epub.js** offers CFI-based ranges over the same multi-column pagination.
- **Readium's own `readium-reflowable.js`** does it via `Locator` + `HTMLDecorationTemplate`.

Highlighting a range is then CSS: absolutely-positioned divs over `range.getClientRects()`, or `CSS.highlights` / `::highlight()`. Reporting a range up to Kotlin is `postMessage` with a serializable address (CFI, or a `{sectionIndex, startOffset, endOffset}` triple over the section's text content).

**Known sharp edge:** the JS renderer must be *inside* the app bundle and loadable offline, and WKWebView's file access is restrictive — `loadFileURL(_:allowingReadAccessTo:)` or a custom `WKURLSchemeHandler` is required. Readium's Kotlin toolkit historically ran a local HTTP server for this reason; the Swift toolkit uses a scheme handler. Both approaches are reachable from Kotlin/Native, but this is a known non-trivial plumbing item, not a freebie.

---

## 4. Existence proof: Episteme

**[Aryan-Raj3112/episteme](https://github.com/Aryan-Raj3112/episteme)** — "A multi-platform document and e-book reader", ~1,135 stars, last push **2026-08-13** (i.e. two days before this research). This is not a toy.

Verified from source, not from the README:

- `shared/build.gradle.kts` declares **`iosArm64()` and `iosSimulatorArm64()`** targets alongside `android` and `jvm("desktop")`, using Compose Multiplatform. There is a real `iosApp/Reader.xcodeproj`.
- `shared/src/iosMain/.../ui/SharedMobileEpubPlatform.ios.kt` (877 lines) is the whole EPUB reader surface on iOS, written **entirely in Kotlin/Native**:
  - Renders via `WKWebView` embedded with `UIKitView`; injects a bootstrap script via `WKUserScript`; registers a Kotlin `IosEpubScriptMessageHandler : NSObject(), WKScriptMessageHandlerProtocol` under the bridge name `"reader"`; drives the page with `evaluateJavaScript`.
  - The bridge envelope is `"$method\n$payload"`, parsed in `handleBridgeMessage`. It implements **content virtualization over the bridge** — JS asks for chunk *n* via `readerChunkRequested`, Kotlin answers with `window.readerVirtualization.provideChunk(n, …)`. Good evidence the boundary can carry real load, and also evidence that you end up building this machinery yourself.
  - `IosEpubNavigationDelegate : NSObject(), WKNavigationDelegateProtocol` for load completion; `removeScriptMessageHandlerForName` on release.
- **TTS with range highlighting is implemented in Kotlin/Native on iOS**: `AVSpeechSynthesizer` + `AVSpeechSynthesizerDelegateProtocol`, with `speechSynthesizer(_:willSpeakRangeOfSpeechString:utterance:)` overridden — `utteranceWillSpeakRange(utterance, range: CValue<NSRange>)` reads `range.useContents { location }` and adds it to `activeUtteranceBaseOffset` to get a document-absolute spoken offset. Chunking is modelled in **common** code as `ReaderTtsChunk` / `ReaderTtsProgress`, so the chunking policy is shared across platforms and only the speech engine is platform-specific.
- Also in `iosMain`, and directly relevant to this map: `IosEpubArchive.kt`, `IosOpdsRepository.kt` / `IosOpdsCoverImage.kt` (**OPDS from Kotlin/Native** — Calibre's content server speaks OPDS), `IosReaderSessionPersistence.kt` (reading position), `IosLibraryPersistence.kt`, `IosTtsAudioInterruptionMonitor.kt`, plus `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter` wiring for **lock-screen TTS controls**. The Android side has a `CalibreBundleExtractor.kt`.

**What this proves:** every architectural risk in issue #2 — Compose Multiplatform on iOS, WKWebView from Kotlin/Native, a bidirectional JS bridge, native TTS with sub-utterance range callbacks, lock-screen controls, OPDS — has a working, actively maintained implementation in a single KMP codebase, with **zero Swift in the reader path**.

**What it does not prove:** it is one app's bespoke stack, not a reusable library. Its EPUB parsing, `epub_reader.js`, and TTS chunking are all hand-rolled. It is Apache/CLA-gated source to learn from, not a dependency to adopt. Its rendering fidelity against gnarly publisher EPUBs is untested by this research. And note its highlight model is *offset-based* (`activeSpokenOffset` into a chunk's text), not `Range`-based — simpler than Readium's `Locator`, and likely less robust across reflow.

Secondary/negative data point: [`compose-webview-multiplatform`](https://github.com/KevinnZou/compose-webview-multiplatform) (v2.0.3) wraps `WKWebView` on iOS and offers `evaluateJavaScript` plus a `WebViewJsBridge` (since 1.8.0). Usable as a starting point, but it is a general-purpose web view — it gives you no EPUB pagination, no range reporting, and no decoration layer.

---

## Open questions this research did not settle

- Rendering fidelity of a hand-driven `foliate-js` / `epub.js` stack versus `readium-reflowable.js` on real Calibre-library EPUBs. Not measurable without a prototype.
- Whether Readium's `Locator` (with `text.before`/`highlight`/`after`) is *needed* for position stability, or whether an offset-based address like Episteme's is sufficient for a personal, single-device-family reader.
- Cost of the WKWebView offline resource plumbing (`WKURLSchemeHandler` vs local HTTP server) implemented from Kotlin/Native.
- Whether `swift-toolkit` 4.0 (alpha as of 2026-08-14) changes any of the interop calculus.

## Sources

- Readium Kotlin toolkit: [repo](https://github.com/readium/kotlin-toolkit), [releases](https://github.com/readium/kotlin-toolkit/releases), [getting-started](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/getting-started.md), [discussion #547](https://github.com/readium/kotlin-toolkit/discussions/547), [discussion #625](https://github.com/readium/kotlin-toolkit/discussions/625)
- Readium Swift toolkit: [repo](https://github.com/readium/swift-toolkit), [releases](https://github.com/readium/swift-toolkit/releases), [TTS guide](https://github.com/readium/swift-toolkit/blob/develop/docs/Guides/TTS.md), [Decorations guide](https://github.com/readium/swift-toolkit/blob/develop/docs/Guides/Navigator/Decorations.md)
- Readium architecture: [Locator model](https://readium.org/architecture/models/locators/), [Readium Mobile](https://github.com/readium/mobile)
- Compose Multiplatform: [what's new 1.11.1](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html), [what's new 1.9.x](https://kotlinlang.org/docs/multiplatform/whats-new-compose-190.html), [1.9.0 announcement](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/), [issue #3922](https://github.com/JetBrains/compose-multiplatform/issues/3922)
- Kotlin/Swift interop: [What's new in Kotlin 2.3.0](https://kotlinlang.org/docs/whatsnew23.html), [swift-export-sample](https://github.com/Kotlin/swift-export-sample)
- JS renderers: [foliate-js](https://github.com/johnfactotum/foliate-js), [foliate-js README](https://github.com/johnfactotum/foliate-js/blob/main/README.md)
- KMP existence proof: [Episteme](https://github.com/Aryan-Raj3112/episteme), [compose-webview-multiplatform](https://github.com/KevinnZou/compose-webview-multiplatform)
