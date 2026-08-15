package com.samarth.calibreios.reader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Kotlin <-> JS bridge contract for the reader surface.
 *
 * Architecture (decided in #7): a Compose Multiplatform shell hosts a platform
 * web view running foliate-js. Kotlin drives the engine; the engine owns layout,
 * pagination and text ranges. Nothing about this file is iOS-specific -- the
 * same contract is intended to back an Android WebView when Android happens.
 *
 * Two directions, deliberately asymmetric:
 *
 *  - [ReaderCommand] is fire-and-forget. Kotlin evaluates JS and does not await
 *    a return value, because the web view's reply channel is the event stream.
 *  - [ReaderEvent] is the only way results come back.
 *
 * Keeping calls one-way avoids modelling a request/response correlation layer
 * over a boundary that is already asynchronous on both sides.
 */

// ---------------------------------------------------------------- commands

sealed interface ReaderCommand {
    /** The JS expression to evaluate in the web view. */
    fun toJs(): String

    /**
     * Open a book, optionally resuming at [initialLocation].
     *
     * Resuming is part of opening rather than a follow-up [GoTo]: foliate's
     * `open()` only parses, and `init()` is what renders -- and it accepts the
     * start position. Seeking afterwards would render the first page and then
     * visibly jump.
     *
     * Set [isCalibreLocation] when [initialLocation] came from
     * `META-INF/calibre_bookmarks.txt`.
     *
     * [useCalibreBookmark] resumes at the Mac desktop viewer's position without
     * being told what it is: the engine reads it from the EPUB's own
     * `META-INF/calibre_bookmarks.txt`. This is the whole Mac -> iPhone position
     * path (#4, #6, #8), and it costs no extra request because the position
     * ships inside the file we already downloaded. An explicit
     * [initialLocation] always wins -- the phone's own position takes
     * precedence, and a differing Mac position is offered, never applied
     * silently (#8, "ask, never jump").
     */
    data class Open(
        val url: String,
        val initialLocation: String? = null,
        val isCalibreLocation: Boolean = false,
        val useCalibreBookmark: Boolean = false,
    ) : ReaderCommand {
        override fun toJs() = "window.__reader.open(" +
            "${url.jsString()}, " +
            "${initialLocation?.jsString() ?: "null"}, " +
            "$isCalibreLocation, " +
            "$useCalibreBookmark)"
    }

    /**
     * Navigate to a position.
     *
     * When [isCalibre] is true, [cfi] is calibre's flat spine-step form as found
     * in `META-INF/calibre_bookmarks.txt`, and the JS side converts it via
     * foliate's `CFI.fromCalibrePos`. Verified against a real library: calibre's
     * `epubcfi(/26/2/4/18/1:127)` becomes `epubcfi(/6/26!/4/18/1:127)`, character
     * offset intact.
     */
    data class GoTo(val cfi: String, val isCalibre: Boolean = false) : ReaderCommand {
        override fun toJs() = "window.__reader.goTo(${cfi.jsString()}, $isCalibre)"
    }

    /**
     * Navigate by fraction through the book.
     *
     * Prefer [GoTo]. Fractions are NOT comparable across renderers: for the same
     * position, calibre stored 0.0567 where foliate reports 0.0447 -- roughly ten
     * pages apart in a full-length book. Use this only when no CFI is available.
     */
    data class GoToFraction(val fraction: Double) : ReaderCommand {
        override fun toJs() = "window.__reader.goToFraction($fraction)"
    }

    data object PrevPage : ReaderCommand {
        override fun toJs() = "window.__reader.prev()"
    }

    data object NextPage : ReaderCommand {
        override fun toJs() = "window.__reader.next()"
    }

    data class SetTheme(val theme: ReaderTheme) : ReaderCommand {
        override fun toJs() = "window.__reader.setTheme(${theme.toJson().jsString()})"
    }

    /**
     * (Re-)segment the current section for TTS.
     *
     * Safe to send again with a different [delimiter]; the bridge drops the
     * existing TTS instance so the new segmentation takes effect immediately.
     */
    data class TtsInit(val delimiter: TtsDelimiter = TtsDelimiter.Sentence) : ReaderCommand {
        override fun toJs() = "window.__reader.ttsInit(${delimiter.toJsArg()})"
    }

    /** Request the next block of chunks; replies with [ReaderEvent.TtsChunks]. */
    data class TtsNext(val fromStart: Boolean = false) : ReaderCommand {
        override fun toJs() = "window.__reader.ttsNext($fromStart)"
    }

    data object TtsPrev : ReaderCommand {
        override fun toJs() = "window.__reader.ttsPrev()"
    }

    /**
     * Paint the highlight for a chunk.
     *
     * This is the load-bearing call for the feature the project exists for:
     * highlight the sentence that is *about* to be read, hold for the configured
     * pause, then speak it. The highlight is anchored to a DOM Range, so it
     * survives repagination and re-theming.
     */
    data class TtsHighlight(val mark: String) : ReaderCommand {
        override fun toJs() = "window.__reader.ttsHighlight(${mark.jsString()})"
    }

    /**
     * Begin TTS where the reader is looking: the selection if there is one,
     * otherwise the first chunk of the visible page (#13).
     *
     * Explicitly *not* the top of the section — that was the reported bug.
     */
    data object TtsFromCurrent : ReaderCommand {
        override fun toJs() = "window.__reader.ttsFromCurrent()"
    }

    data object ClearSelection : ReaderCommand {
        override fun toJs() = "window.__reader.clearSelection()"
    }

    /** Ask for the table of contents; replies with [ReaderEvent.Toc]. */
    data object RequestToc : ReaderCommand {
        override fun toJs() = "window.__reader.toc()"
    }

    /** Search the whole book; replies with [ReaderEvent.SearchResults]. */
    data class Search(val query: String) : ReaderCommand {
        override fun toJs() = "window.__reader.search(${query.jsString()})"
    }

    data object ClearSearch : ReaderCommand {
        override fun toJs() = "window.__reader.clearSearch()"
    }

    /** Ask for calibre's own bookmarks and highlights, read from the EPUB. */
    data object RequestCalibreMarks : ReaderCommand {
        override fun toJs() = "window.__reader.calibreMarks()"
    }

    /** Turn the current selection into a highlight; replies [ReaderEvent.MarkCreated]. */
    data class MakeHighlight(val id: String, val color: String? = null) : ReaderCommand {
        override fun toJs() =
            "window.__reader.makeHighlight(${id.jsString()}, ${color?.jsString() ?: "null"})"
    }

    /** Bookmark the current page; replies [ReaderEvent.MarkCreated]. */
    data class MakeBookmark(val id: String) : ReaderCommand {
        override fun toJs() = "window.__reader.makeBookmark(${id.jsString()})"
    }

    /** Paint saved highlights. [json] is a list of `{id, kind, cfi, color}`. */
    data class ShowMarks(val json: String) : ReaderCommand {
        override fun toJs() = "window.__reader.showMarks(${json.jsString()})"
    }

    data class HideMark(val cfi: String) : ReaderCommand {
        override fun toJs() = "window.__reader.hideMark(${cfi.jsString()})"
    }

    /** Navigate to a TOC entry's href. */
    data class GoToHref(val href: String) : ReaderCommand {
        override fun toJs() = "window.__reader.goToHref(${href.jsString()})"
    }

    /** Remove the read-aloud highlight, so a stopped chunk does not stay lit. */
    data object TtsClearHighlight : ReaderCommand {
        override fun toJs() = "window.__reader.ttsClearHighlight()"
    }
}

// ---------------------------------------------------------------- events

@Serializable
sealed interface ReaderEvent {

    /** Bridge script loaded; safe to send commands. */
    @Serializable
    @SerialName("ready")
    data object Ready : ReaderEvent

    @Serializable
    @SerialName("opened")
    data class Opened(
        val title: String? = null,
        val author: String? = null,
        val sectionCount: Int = 0,
        /** The raw calibre position found in the EPUB, if any. */
        val calibrePos: String? = null,
        /** The Mac's position converted to a CFI this engine can navigate to. */
        val calibreCfi: String? = null,
        /**
         * Where the Mac's position sits relative to where the book actually
         * opened: `1` ahead, `0` the same, `-1` behind, null if not comparable.
         *
         * A comparison rather than a percentage, because calibre's embedded
         * bookmark carries **no `pos_frac`** — that lives only in `metadata.db`,
         * which #6 proved is unreachable per-user over HTTP. `CFI.compare` is
         * also exact, where a cross-renderer fraction is approximate (#12).
         */
        val calibreRelative: Int? = null,
        /** What the engine actually resumed at, after conversion. */
        val resolvedCfi: String? = null,
        val diag: String? = null,
    ) : ReaderEvent

    /**
     * Emitted on every relocation. [cfi] is what gets persisted as the reading
     * position; [fraction] is for progress UI only, never for sync.
     */
    @Serializable
    @SerialName("location")
    data class Location(
        val cfi: String? = null,
        val fraction: Double? = null,
        val tocLabel: String? = null,
        val pageItem: String? = null,
    ) : ReaderEvent

    @Serializable
    @SerialName("ttsReady")
    data class TtsReady(val granularity: String) : ReaderEvent

    /**
     * One block's worth of speakable chunks, in reading order.
     *
     * [done] is true when the book is exhausted. Chunks carry plain text rather
     * than SSML because AVSpeechSynthesizer takes strings; the [TtsChunk.mark]
     * is the handle back into the engine for highlighting.
     */
    @Serializable
    @SerialName("ttsChunks")
    data class TtsChunks(
        val chunks: List<TtsChunk> = emptyList(),
        val done: Boolean = false,
    ) : ReaderEvent

    /**
     * The reader's text selection changed.
     *
     * Drives the in-app "Read from here" bar (#13). [text] is truncated —
     * it is a label, not content.
     */
    @Serializable
    @SerialName("selection")
    data class Selection(
        val text: String = "",
        val active: Boolean = false,
    ) : ReaderEvent

    @Serializable
    @SerialName("toc")
    data class Toc(val entries: List<TocEntry> = emptyList()) : ReaderEvent

    @Serializable
    @SerialName("searchResults")
    data class SearchResults(
        val query: String = "",
        val results: List<SearchHit> = emptyList(),
        /** True when the cap was hit — say so rather than imply completeness. */
        val truncated: Boolean = false,
    ) : ReaderEvent

    /** calibre's own marks, parsed out of the downloaded EPUB. */
    @Serializable
    @SerialName("calibreMarks")
    data class CalibreMarks(val marks: List<Mark> = emptyList()) : ReaderEvent

    /** A mark the engine just created, with the CFI only it could compute. */
    @Serializable
    @SerialName("markCreated")
    data class MarkCreated(
        val id: String = "",
        val kind: String = "",
        val cfi: String = "",
        val text: String = "",
    ) : ReaderEvent

    @Serializable
    @SerialName("themeApplied")
    data object ThemeApplied : ReaderEvent

    @Serializable
    @SerialName("error")
    data class Error(val where: String = "", val message: String = "") : ReaderEvent
}

@Serializable
data class SearchHit(
    val cfi: String = "",
    /** Chapter this hit is in, when the engine knows it. */
    val label: String = "",
    val excerpt: String = "",
)

/** One table-of-contents entry, flattened; [depth] is for indentation. */
@Serializable
data class TocEntry(
    val label: String = "",
    val href: String? = null,
    val depth: Int = 0,
)

@Serializable
data class TtsChunk(
    val mark: String? = null,
    val text: String,
)

// ---------------------------------------------------------------- theme

// ---------------------------------------------------------------- delimiter

/**
 * How the text is cut into speakable chunks -- the unit that gets highlighted,
 * paused before, and spoken.
 *
 * [Sentence] and [Word] are `Intl.Segmenter` granularities, so they are
 * locale-aware and handle abbreviations and quotes correctly. [Custom] is the
 * calibre-style "tell me your delimiter" mode, added by a local patch to
 * foliate's `tts.js`; it is a plain regex over the block's text and knows
 * nothing about language.
 *
 * Prefer [Sentence]: it is what a reader means by "a line" almost all the time,
 * and it does not mis-split on "Mr." or "e.g.". [Custom] exists for the cases
 * sentence segmentation cannot express -- verse, dialogue, scripture.
 */
sealed interface TtsDelimiter {

    /** The JS argument for `__reader.ttsInit`. */
    fun toJsArg(): String

    /** Human-readable, for settings UI. */
    val label: String

    data object Sentence : TtsDelimiter {
        override fun toJsArg() = "\"sentence\""
        override val label get() = "Sentence"
    }

    data object Word : TtsDelimiter {
        override fun toJsArg() = "\"word\""
        override val label get() = "Word"
    }

    /**
     * Break wherever [pattern] matches. The delimiter is kept at the end of the
     * chunk it terminates, so punctuation is spoken with its own sentence.
     *
     * [pattern] is a JS regex source compiled with the `u` flag. Zero-width
     * matches are skipped rather than looping. A pattern that matches nothing
     * yields one chunk per block, which is a legitimate outcome (read the whole
     * paragraph at once), not an error.
     */
    data class Custom(val pattern: String, override val label: String) : TtsDelimiter {
        override fun toJsArg() = "{\"custom\": ${pattern.jsString()}}"
    }

    companion object {
        /** Every hard stop, plus closing quotes and brackets. */
        val Punctuation = Custom("[.!?…]+[\"'”’)\\]]*\\s+", "Punctuation")

        /** One line of verse or dialogue per chunk. */
        val LineBreak = Custom("\\n+", "Line break")

        /** Breath-length chunks: commas and semicolons as well as full stops. */
        val Clause = Custom("[,;:.!?…]+\\s+", "Clause")

        val presets: List<TtsDelimiter> =
            listOf(Sentence, Punctuation, Clause, LineBreak, Word)
    }
}

/**
 * Runtime-applied reader appearance.
 *
 * Verified against foliate-js: every field here re-renders and repaginates live
 * via a single `renderer.setStyles` call, so the engine is not the constraint on
 * how far theming can be scoped (#10).
 */
data class ReaderTheme(
    val fontFamily: String = "Georgia, serif",
    /** Multiplier applied to the base font size, in `em`. */
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.5,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val fg: String = "#111111",
    val bg: String = "#ffffff",
    /**
     * Link colour. Needs overriding rather than inheriting: EPUBs hard-code
     * their own (usually the default browser blue), which is unreadable on a
     * dark background -- a table of contents is entirely links.
     */
    val link: String = "#0645ad",
    /**
     * Fill painted behind the chunk being read aloud.
     *
     * Opaque, because [highlightBlend] does the work of keeping the text
     * readable. foliate multiplies this by `--overlayer-highlight-opacity`
     * (default .3) as well, so a translucent colour here is attenuated twice
     * and renders far weaker than it looks.
     */
    val highlight: String = "#ffd21f",
    /**
     * How the highlight composites with the text beneath it.
     *
     * `multiply` on light backgrounds: white becomes gold, black text stays
     * black — the real highlighter-pen effect, and the only way to be both
     * loud and legible. `screen` on dark backgrounds, where multiply would
     * darken the light text into the wash instead.
     */
    val highlightBlend: String = "multiply",
    /** Overrides foliate's .3 default; the blend mode preserves legibility. */
    val highlightOpacity: Double = 1.0,
    val dark: Boolean = false,
    val marginPx: Int? = null,
) {
    fun toJson(): String = buildString {
        append('{')
        append("\"fontFamily\":").append(fontFamily.jsonString()).append(',')
        append("\"fontSize\":").append(fontSize).append(',')
        append("\"lineHeight\":").append(lineHeight).append(',')
        append("\"justify\":").append(justify).append(',')
        append("\"hyphenate\":").append(hyphenate).append(',')
        append("\"fg\":").append(fg.jsonString()).append(',')
        append("\"bg\":").append(bg.jsonString()).append(',')
        append("\"link\":").append(link.jsonString()).append(',')
        append("\"highlight\":").append(highlight.jsonString()).append(',')
        append("\"highlightBlend\":").append(highlightBlend.jsonString()).append(',')
        append("\"highlightOpacity\":").append(highlightOpacity).append(',')
        append("\"dark\":").append(dark)
        marginPx?.let { append(",\"marginPx\":").append(it) }
        append('}')
    }

    companion object {
        val Light = ReaderTheme()
        val Sepia = ReaderTheme(
            fg = "#3b2f1e", bg = "#f6ecd9", link = "#7a4b2a",
            // Cream is already warm, so a plain yellow barely registers on it.
            // A saturated marigold multiplies down to a distinctly deeper,
            // more saturated band while staying inside the sepia palette.
            highlight = "#f0b429",
            highlightBlend = "multiply",
        )
        val Dark = ReaderTheme(
            fg = "#d8d8d8", bg = "#121212", link = "#8ab4f8",
            // Golden here too, but achieved by screening a DARK gold: on
            // near-black that lifts the background to a solid gold band while
            // pushing the light text towards white. A bright gold would swamp
            // the text; multiply would darken it into the wash.
            highlight = "#6b5200",
            highlightBlend = "screen",
            dark = true,
        )
    }
}

// ---------------------------------------------------------------- escaping

/** Escapes a Kotlin string for embedding as a JS string literal. */
internal fun String.jsString(): String = jsonString()

internal fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    for (c in this@jsonString) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        // U+2028/U+2029 are valid JSON but terminate a JS line, so escape them.
        '\u2028' -> append("\\u2028")
        '\u2029' -> append("\\u2029")
        else -> if (c < ' ') append("\\u").append(c.code.toString(16).padStart(4, '0')) else append(c)
    }
    append('"')
}
