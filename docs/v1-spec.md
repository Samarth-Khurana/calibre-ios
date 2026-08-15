# Calibre-companion EPUB reader for iOS — verdict and V1 spec

Resolves [#11](https://github.com/Samarth-Khurana/calibre-ios/issues/11), the destination of the wayfinder map ([#1](https://github.com/Samarth-Khurana/calibre-ios/issues/1)). Every claim here traces to a closed ticket; the ticket number is the citation.

Four research documents in [`docs/research/`](.) hold the source-derived workings behind conclusions this spec only states — read them when you need the *why* rather than the decision:
[EPUB rendering in KMP on iOS](research/kmp-epub-rendering-ios.md) (#2) ·
[iOS TTS capabilities](research/ios-tts-capabilities.md) (#3) ·
[Calibre progress model and sync surfaces](research/calibre-progress-model.md) (#4) ·
[Fetching books from Calibre](research/calibre-book-fetching.md) (#5)

---

## 1. Verdict

**Go.** Build it in Kotlin Multiplatform. All three must-have features are achievable, and the architecture is not a proposal — it runs on an iPhone 17 Pro today, reading a real EPUB aloud with an enhanced voice.

The central question was whether KMP could do this without falling back to Swift. It can, and the answer is not a bet:

| Must-have | Verdict | Evidence |
|---|---|---|
| TTS with delimiter chunking, highlight, pause | **Yes, fully** | #9 — built and heard on device |
| Persistent theme / typography | **Yes, fully** | #10, #12 — every control applies live |
| Position continuity with the Mac | **Yes, one direction** | #4, #6, #8 — Mac → iPhone only |

**Zero Swift in V1.** Kotlin/Native reaches everything needed: `WKWebView` and its scheme and message handlers (#12), `AVSpeechSynthesizer` and its delegate (#9), `AVAudioSession` (#9). The reader viewport is a web view running foliate-js, driven from Kotlin — which is not a concession but the normal answer, since Readium injects JS into a web view on *both* platforms ([#2](research/kmp-epub-rendering-ios.md), #7).

### The one feature that is degraded, not delivered

**Position sync is one-way.** iPhone → calibre *desktop viewer* is impossible over HTTP: the viewer never reads `last_read_positions` when opening a book, and `/book-update-annotations` silently discards `last-read` entries ([#4](research/calibre-progress-model.md) — that document traces where the viewer's resume position actually comes from, which is why no HTTP route reaches it). Reaching it needs a Mac-side plugin or filesystem agent — a second codebase. V1 therefore reads the Mac's position and never writes back (#8).

This is a real reduction against the original ask ("sync … in the iOS as well as Mac book"). It is not a technical unknown; it is a closed door, and the map records it as out of scope rather than pending.

Two honesty notes carried into the spec rather than buried:
- Position resume promises **the right page, not the right word** (§5.3).
- The reference app, justRead, advertises two-way Calibre sync but its position sync is between *its own* iOS devices via iCloud; the Calibre part is metadata only (#4). **Nobody has solved the problem we are declining to solve.**

### Confidence

Unusually high for a plan, because most of it has been executed. What remains genuinely unproven is listed in §7 — and none of it threatens the verdict, because each has a known fallback.

---

## 2. Scope

### In

- **EPUB, reflowable, DRM-free.** One format.
- **Offline-first.** Books are downloaded and read from device storage. Server-unreachable is a normal state, not an error (#8).
- **iOS only, sideloaded.** No App Store review; a free Apple ID re-signs every 7 days.
- **Android kept viable, not built.** Shared code stays platform-neutral. `TtsSession` and the whole reader contract are already in `commonMain` (#9).

### Out

- PDF, comics, audiobooks; DRM-protected books.
- Cloud sync backends and streaming-without-download.
- **iPhone → Mac desktop-viewer write-back** (#4, #8) — deferred, not abandoned.
- App Store distribution.
- Android UI.

---

## 3. Architecture

```
┌────────────────────────────────────────────────┐
│  Compose Multiplatform shell        commonMain │
│  library · reader chrome · settings · transport│
├────────────────────────────────────────────────┤
│  TtsSession  ReaderContract  sync/domain       │  ← platform-neutral
├──────────────────────┬─────────────────────────┤
│ IosSpeaker           │ ReaderView.ios          │  ← iosMain
│ AVSpeechSynthesizer  │ WKWebView + scheme      │
└──────────────────────┴─────────────────────────┘
                       │  epubapp://  +  JSON events
                ┌──────┴──────────────────┐
                │  foliate-js (MIT)       │
                │  layout · CFI · TTS seg │
                └─────────────────────────┘
```

Decided in #7, proven in #12. Mirrors [Episteme](https://github.com/Aryan-Raj3112/episteme), a shipped KMP + iOS reader with no Swift in its reader path (#2).

### 3.1 The bridge contract

`ReaderContract.kt` (`commonMain`) defines it, and the asymmetry is deliberate:

- **`ReaderCommand`** — fire-and-forget. Kotlin evaluates JS and discards the return value.
- **`ReaderEvent`** — the *only* channel results come back on.

This avoids modelling request/response correlation over a boundary that is already asynchronous on both sides. Four runtime defects found in #12 justify keeping it:

1. `window.webkit.messageHandlers` **is not writable** — an injected `postMessage` shim fails *silently* and Kotlin receives an Obj-C dictionary description instead of JSON. Stringify at the call site.
2. `evaluateJavaScript` **cannot marshal a Promise** — async commands must be `void`-wrapped. Harmless precisely because the contract says events are the reply channel.
3. The event flow needs **`replay`** — the engine emits `ready` before a collector can attach; `replay = 0` left the reader blank with no error.
4. `view.open()` only *parses*. **`view.init()` renders, and takes the start position** — so resuming at a calibre position belongs inside `Open`, not a follow-up seek that would render page 1 and visibly jump.

### 3.2 Assets

foliate-js and the reader HTML are served over a private **`epubapp://`** scheme (#12), not `loadFileURL` — this controls every byte and sidesteps WKWebView's `file://` sub-resource restrictions. Assets preload into memory (~240 KB) because a scheme handler must answer synchronously.

### 3.3 The foliate patch — a standing maintenance cost

**foliate cannot do delimiters.** `initTTS(granularity)` forwards straight to `Intl.Segmenter`, so the only legal values are `word`, `sentence`, `grapheme` (#9). Calibre-style delimiters required a **local patch** to `tts.js` adding a regex segmenter matching the generator signature foliate already returns.

The patch is marked in-file and **must be re-applied on every foliate-js update.** Treat foliate as vendored, not as a dependency.

---

## 4. Must-have 1 — Read-aloud

The feature the project exists for. Built and heard on device (#9); steering decided in #13.

### 4.1 The loop

Per chunk, `TtsSession` (`commonMain`):

1. **Paint the highlight** on the chunk about to be read (also scrolls it into view).
2. **Hold** for the configured interval.
3. **Speak exactly one utterance** and await its completion.
4. Advance.

**Do not use `preUtteranceDelay`.** [#3](research/ios-tts-capabilities.md) left open whether `didStart` fires before or after it; this design *removes the dependency* rather than resolving it. If `didStart` fired after the delay, a queued design could not paint the highlight before the pause — which is the entire feature. Measured holds: **402–418 ms against a 400 ms setting**, so the round-trip cost is 2–18 ms and imperceptible.

### 4.2 Settings

| Setting | Values | Default |
|---|---|---|
| Delimiter | Sentence · Punctuation · Clause · Line break · Word | **Sentence** |
| Hold before speaking | 0–2000 ms | **400 ms** |
| Voice | System + installed voices, best quality first | System |
| Rate | 0.25–0.75 | 0.5 |

Presets, **not a raw regex box** — Clause visibly over-splits on the `:` in numbered headings, which is the argument for curation. Sentence is locale-aware and does not mis-split on "Mr." or "e.g.".

**Voice selection is V1, not a nicety.** It was requested on first real use; the enhanced-voice difference is too large for "system default" to be the ceiling. Constraint from #3: the app can **list and use** enhanced voices but cannot **install or detect installable** ones, so onboarding must tell the user to add them in Settings.

### 4.3 Steering (#13)

- **Read starts at the first chunk on the visible page.** No stored "spoken position" — you can always see where it will begin.
- **Selecting text shows an in-app "Read from here" bar** in the Compose shell. Deliberately **not** the iOS callout: that needs `UIMenu`/`buildMenu` interop with no clean Kotlin/Native binding and no Android equivalent, punching a platform hole for a cosmetic gain. Accepted cost: iOS's own callout and our bar can coexist on screen.
- **The page follows the voice**; manual paging snaps back on the next chunk. Because read resumes from the visible page, **pause → browse → read** is one coherent motion rather than a detached state needing its own design.
- **One highlight wash** throughout — no change when the voice starts. The anticipatory highlight calibre is liked for is present (line lights → hold → voice); only the colour shift is absent, and at 400 ms a second state risks reading as flicker.
- **Resume continues mid-sentence.**
- **Transport:** play/pause, back one sentence, forward one sentence. "Stop" collapses into pause.
- **Opening a book never auto-starts reading.**

Implementation note: `ttsFromCurrent`'s no-selection fallback must be `view.lastLocation.range`, **not** `tts.start()` — the latter is the top of the section and was the originally reported bug.

### 4.4 Boundaries

foliate binds TTS to one rendered *section*, so running out of chunks means end-of-section, not end-of-book. The loop turns the page and re-segments. Two cases that only surfaced by running it (#9):

- **Books open on a title page with no speakable blocks** — running dry is the *normal first outcome*. Exhausting a section must always try the next; end-of-book is detected by the position refusing to move.
- **Replayed events resolve the wrong wait.** Both the speech event flow and chunk deliveries would satisfy a naive `first()` with a stale value, running the loop a chunk ahead of the audio. Correlate by **per-utterance id**; use a **`Channel`** for chunk deliveries so each is consumed exactly once.

---

## 5. Must-have 2 — Appearance

Decided in #10. The engine was never the constraint (#7, #12); this is a taste-and-surface decision.

### 5.1 Controls — seven, one screen

Palette (Light / Sepia / Dark / **Auto**) · Font · Text size · Line spacing · Margin width · Justify + hyphenate.

- **Fonts are iOS system faces only** — New York, Georgia, Palatino, Iowan Old Style, Charter, Baskerville, San Francisco, Avenir Next. No bundle, no licensing, no `@font-face` plumbing. **Accepted gap: no dyslexia-oriented face**, since iOS ships nothing equivalent to OpenDyslexic.
- **Auto swaps a user-chosen day palette and night palette.** Typography is shared across both — rejected fully independent presets, where every control doubles and it stops being obvious which preset an edit lands in.
- **Persistence is global** — one record, every book.
- **Paginated only.** `flow` is one attribute, but scrolled mode makes all three of #13's page-based decisions mean something different, so its cost is re-deciding #13.
- **Images auto-dimmed in dark themes** (`brightness(.8)`), no control. Inversion fixes diagrams but ruins photographs, and EPUBs do not mark which is which.

### 5.2 Whose styles win

`renderer.setStyles` accepts **two** stylesheets, `[before, after]`, straddling the publisher's CSS. Each property can therefore be a default the book may override, or a rule that overrides the book.

- **Colours: always forced.** Not a preference — dark mode is unusable otherwise.
- **Font: forced for body and headings**, exempting `pre`/`code`, non-Latin scripts, and verse or drop caps.
- **Link colour must be overridden.** EPUBs hard-code their own, unreadable on dark — and a table of contents is entirely links.

**This is the one place the project takes on ongoing curation.** The exception list will be wrong on some book eventually. That is an accepted cost, not a testable defect.

### 5.3 Two theming systems, permanently

The Compose shell and the web-view page are styled independently and **nothing keeps them in sync.** Every appearance setting is applied twice. This is a structural fact of the architecture, not a bug, and it caused three separate dark-mode failures (#9) — each invisible until the device was actually in dark mode:

1. `MaterialTheme {}` with no arguments always uses the **light** colour scheme, and nothing drew a background.
2. Theming the shell leaves the page untouched.
3. `SetTheme` sent before the renderer exists **fails silently** with nothing to re-trigger it.

**Implementation requirement: appearance is applied after the book has opened, never on reader construction, and re-applied on every change including a system appearance flip.** A single source of truth must map onto both systems.

---

## 6. Must-have 3 — Calibre integration

Decided in #8, grounded in a live probe of the user's own library (#6).

### 6.1 Transport

| Concern | Decision |
|---|---|
| Browse | `/ajax/search` → ids, `/ajax/books` batch-hydrate, server-side `num`/`offset` paging |
| Download | `GET /get/{FMT}/{book_id}/{library_id}`, resumable — `206` + `Accept-Ranges` confirmed |
| Auth | Basic; required (position endpoints 404 without it) |
| Identity | calibre `book_id` + format, scoped by `library_id` |

`/ajax/*` is undocumented and unpromised, chosen over OPDS for richer metadata and custom columns — [#5](research/calibre-book-fetching.md) documents all five options considered and why the others fail offline-first. **Two route traps found the hard way (#6):**
- GET uses `{book}-{FMT}` with a **hyphen**. The colon form returns `{}` with HTTP **200** — a silent wrong answer.
- POST requires a `device` field or returns `404 Invalid data`.

### 6.2 Position

**Read from `META-INF/calibre_bookmarks.txt` inside the downloaded EPUB** — not the HTTP position API. #6 proved per-user isolation *empirically*: a phone-written row and the desktop's `user='local'` row coexisted for the same book and format, invisible to each other. The desktop's position is unreachable over the API but sits inside the file we already download, at **zero extra cost**.

**Refresh** by range-fetching just that ZIP entry: EOCD → central directory → single member. Measured **~9.5 KB (0.13%)** with a 256-byte tail, versus 7.5 MB for a re-download. Cheap enough to run on every app open. Caching the local-header offset reduces a refresh to one ~700-byte request.

### 6.3 Fidelity — state this plainly to the user

**CFI is primary; `pos_frac` is the fallback.** #8 assumed `pos_frac` might be the safer choice; #12 overturned that — foliate resolved the calibre CFI to fraction 0.0447 where calibre stored `pos_frac` 0.0567, roughly ten pages apart in a full-length book. `pos_frac` is renderer-independent but **lossy**.

Promise **the right page, not the right word.**

### 6.4 Conflict

**Ask, never jump.** Open at the phone's position; if the Mac's differs meaningfully, show a dismissible bar — *"Mac was at 60% — jump there?"*. Reading is never silently discarded.

---

## 7. Risks

Ordered by how likely they are to bite.

| # | Risk | Mitigation |
|---|---|---|
| 1 | **iOS Local Network permission.** ~~Untestable until a real app exists.~~ **Now measured on an iPhone 17 Pro / iOS 26 (#14) and worse than described** — see below. | **Browse Bonjour at startup, before any HTTP.** Detecting and explaining the failure is not enough. |
| 2 | ~~**`<mac>.local` vs hardcoded IP.**~~ **Resolved differently than planned (#14).** calibre registers its Bonjour service under a MAC-derived hostname (`Unknown_42:18:70:03:f8:4d.local`) whose colons cannot be parsed as a URL host, so there is no usable `.local` name to prefer. | **Re-discover via Bonjour every launch; never trust a stored address.** A stale IP stops being a failure mode when nothing stores one. |
| 3 | **The foliate patch** must be re-applied on every upstream update, or delimiters silently revert to sentence segmentation. | Marked in-file; pin the version; treat as vendored. |
| 4 | **`/ajax/*` is undocumented** and could change in any calibre release. | Stable for years. OPDS is the conservative fallback and reaches the same books. |
| 5 | **Free-account provisioning expires every 7 days.** | Known cost of sideloading; a paid account makes it a year. |
| 6 | **Style-exception curation** will be wrong on some book. | Accepted (§5.2). |

### Corrections from device testing (#14)

Three assumptions in this spec were wrong, and all three surfaced only on hardware.

1. **HTTP alone never raises the permission prompt.** A request to a LAN address is refused *silently* and does not register the app as having asked — so it never appears in Settings › Privacy › Local Network and the user has nothing to switch on. There is no recovery from inside the app. **A Bonjour browse is what raises the prompt**, which promotes discovery from a convenience to a hard prerequisite for any network access.
2. **The denial IS distinguishable from an unreachable server.** This spec claimed it was not. iOS reports `-1009 "The Internet connection appears to be offline"` with Wi-Fi up, but the underlying error carries `_NSURLErrorNWPathKey = unsatisfied (Local network prohibited)`, which nothing else produces. The two cases now get two different messages, because they have two different fixes.
3. **Granting the permission may not take effect until it is toggled off and on again.** Observed: listed and ON in Settings, Bonjour resolution working, Safari reaching the server from the same device — and both Ktor *and* a plain `NSURLSession` still refused. Toggling the switch off and back on cleared it. **Onboarding must tell the user this**; it is not discoverable and looks exactly like a broken app.

Two implementation notes that cost real time:

- **`NSURLSession` caches its network-path verdict at construction.** A session built before the permission was granted stays "unsatisfied" for its entire life, so a retry that reuses it can never succeed. Build a fresh client per attempt.
- **"State changed" is not "the user asked again."** Keying a request off the server value made re-selecting the same server a no-op that left the previous error on screen, indistinguishable from a fresh failure.

### Verified on an iPhone 17 Pro (#9, #14)

Read-aloud end-to-end with an enhanced voice; delimiter, hold and voice settings; pagination; theming. **Plus the whole of must-have 3 (#14):** Bonjour discovery of the server, `/ajax` browse, a 7.2 MB download over Wi-Fi, and the book opening at the Mac desktop viewer's position — `epubcfi(/26/2/4/18/1:127)` → `epubcfi(/6/26!/4/18/1:127)`, "3. Apples in the Basket".

### Verified on the simulator only

Highlight painting (the fix landed after the device session), custom delimiters re-segmenting live, dark mode in both appearances, and section advance past unreadable front matter.

### Not verified anywhere

- **Background audio and lock-screen controls** — the audio session is configured `playback`/`spokenAudio`, but nothing tests speech surviving a screen lock. `MPNowPlayingInfoCenter` and remote command handlers are untouched.

---

## 8. What already exists

On branch `chore/kmp-scaffold`. This is spike and prototype code — the contract and session are meant to survive; the harness is not.

**Keep:** `ReaderContract.kt`, `TtsSession.kt`, `Speaker.kt` (`commonMain`); `IosSpeaker`, `ReaderView.ios`, `IosReaderBridge`, `ReaderSchemeHandler` (`iosMain`); `bridge.js` and the patched foliate-js.

**Discard:** `App.kt` (a prototype transport, not app UI), `fixture.epub`, and the `CALIBRE_AUTOPLAY` / `CALIBRE_DELIMITER` environment hooks.

**Build:** library UI and browse; download, queue and storage management; the settings screen; reader chrome and navigation (TOC, bookmarks, search); position persistence and the conflict bar; the "Read from here" selection bar.

---

## 9. Deferred, deliberately

Recorded on the map's fog rather than decided here: library UI shape · download and storage bounds · reader navigation · background audio and lock-screen controls · enhanced-voice onboarding · what else the selection bar carries · continuous scrolling · fixed-layout EPUBs · reading statistics · Android UI.

**Revisit as its own effort:** iPhone → Mac position write-back, which needs a Mac-side component (#4).
