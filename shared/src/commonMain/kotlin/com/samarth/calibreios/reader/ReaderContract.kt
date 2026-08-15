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
     */
    data class Open(
        val url: String,
        val initialLocation: String? = null,
        val isCalibreLocation: Boolean = false,
    ) : ReaderCommand {
        override fun toJs() = "window.__reader.open(" +
            "${url.jsString()}, " +
            "${initialLocation?.jsString() ?: "null"}, " +
            "$isCalibreLocation)"
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

    /** Prepare TTS segmentation. [granularity] is `sentence` or `word`. */
    data class TtsInit(val granularity: TtsGranularity = TtsGranularity.Sentence) : ReaderCommand {
        override fun toJs() = "window.__reader.ttsInit(${granularity.wire.jsString()})"
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

    data object TtsFromCurrent : ReaderCommand {
        override fun toJs() = "window.__reader.ttsFromCurrent()"
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

    @Serializable
    @SerialName("themeApplied")
    data object ThemeApplied : ReaderEvent

    @Serializable
    @SerialName("error")
    data class Error(val where: String = "", val message: String = "") : ReaderEvent
}

@Serializable
data class TtsChunk(
    val mark: String? = null,
    val text: String,
)

// ---------------------------------------------------------------- theme

enum class TtsGranularity(val wire: String) {
    Sentence("sentence"),
    Word("word"),
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
        append("\"dark\":").append(dark)
        marginPx?.let { append(",\"marginPx\":").append(it) }
        append('}')
    }

    companion object {
        val Light = ReaderTheme()
        val Sepia = ReaderTheme(fg = "#3b2f1e", bg = "#f6ecd9")
        val Dark = ReaderTheme(fg = "#d8d8d8", bg = "#121212", dark = true)
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
