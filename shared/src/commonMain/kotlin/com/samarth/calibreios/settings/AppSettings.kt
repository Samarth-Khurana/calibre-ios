package com.samarth.calibreios.settings

import com.samarth.calibreios.reader.ReaderTheme
import com.samarth.calibreios.reader.TtsDelimiter
import com.samarth.calibreios.storage.Storage
import com.samarth.calibreios.tts.SpeechSettings
import com.samarth.calibreios.tts.TtsSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the reader can configure. One record, global to all books (#10).
 *
 * This type is **the single source of truth for appearance**, and that matters
 * structurally: the Compose shell and the web-view page are styled by two
 * independent systems that nothing keeps in sync (spec §5.3). Both derive from
 * here — [resolvedPalette] feeds the shell, [toReaderTheme] feeds the page — so
 * the two can only disagree if this file is wrong.
 */
@Serializable
data class AppSettings(
    val appearance: Appearance = Appearance(),
    val readAloud: ReadAloud = ReadAloud(),
) {

    @Serializable
    data class Appearance(
        val mode: Mode = Mode.Auto,
        /** Used when the system is in light appearance and [mode] is [Mode.Auto]. */
        val dayPalette: Palette = Palette.Light,
        /** Used when the system is in dark appearance and [mode] is [Mode.Auto]. */
        val nightPalette: Palette = Palette.Dark,
        /** Used when [mode] is [Mode.Fixed]. */
        val fixedPalette: Palette = Palette.Light,

        val fontId: String = ReaderFont.default.id,
        /** Multiplier on the base size, in `em`. */
        val fontScale: Double = 1.0,
        val lineHeight: Double = 1.5,
        val marginPx: Int = 24,
        val justify: Boolean = true,
        val hyphenate: Boolean = true,
    ) {
        /** Which palette applies right now, given the system appearance. */
        fun resolvedPalette(systemDark: Boolean): Palette = when (mode) {
            Mode.Auto -> if (systemDark) nightPalette else dayPalette
            Mode.Fixed -> fixedPalette
        }

        val font: ReaderFont get() = ReaderFont.byId(fontId)
    }

    /** Auto follows the system; Fixed pins one palette regardless. */
    @Serializable
    enum class Mode { Auto, Fixed }

    @Serializable
    enum class Palette(val label: String) {
        Light("Light"),
        Sepia("Sepia"),
        Dark("Dark"),
        ;

        val isDark: Boolean get() = this == Dark

        fun toReaderThemeBase(): ReaderTheme = when (this) {
            Light -> ReaderTheme.Light
            Sepia -> ReaderTheme.Sepia
            Dark -> ReaderTheme.Dark
        }
    }

    @Serializable
    data class ReadAloud(
        /** Matches [TtsDelimiter.label]; presets only, no raw regex (#9). */
        val delimiterLabel: String = TtsDelimiter.Sentence.label,
        val holdMillis: Long = 400,
        /** Platform voice identifier; null means the system default. */
        val voiceId: String? = null,
        val rate: Float = 0.5f,
    ) {
        val delimiter: TtsDelimiter
            get() = TtsDelimiter.presets.firstOrNull { it.label == delimiterLabel }
                ?: TtsDelimiter.Sentence

        fun toSessionSettings(): TtsSession.Settings = TtsSession.Settings(
            delimiter = delimiter,
            holdMillis = holdMillis,
            speech = SpeechSettings(rate = rate, voiceId = voiceId),
        )
    }

    /** The page's appearance, derived from the palette plus typography. */
    fun toReaderTheme(systemDark: Boolean): ReaderTheme =
        appearance.resolvedPalette(systemDark).toReaderThemeBase().copy(
            fontFamily = appearance.font.cssStack,
            fontSize = appearance.fontScale,
            lineHeight = appearance.lineHeight,
            justify = appearance.justify,
            hyphenate = appearance.hyphenate,
            marginPx = appearance.marginPx,
        )
}

/**
 * The font list. iOS system faces only (#10) — no bundle size, no licensing,
 * no `@font-face` plumbing through the scheme handler.
 *
 * Accepted gap: **no dyslexia-oriented face.** iOS ships nothing equivalent to
 * OpenDyslexic, and bundling one was deliberately deferred.
 *
 * Each stack ends in a generic family so a missing face degrades rather than
 * falling back to the browser default.
 */
data class ReaderFont(
    val id: String,
    val displayName: String,
    val cssStack: String,
) {
    companion object {
        val all: List<ReaderFont> = listOf(
            // Apple's reading serif, designed for screens; the best default here.
            ReaderFont("new-york", "New York", "'New York', ui-serif, Georgia, serif"),
            ReaderFont("georgia", "Georgia", "Georgia, ui-serif, serif"),
            ReaderFont("palatino", "Palatino", "Palatino, 'Palatino Linotype', ui-serif, serif"),
            ReaderFont("iowan", "Iowan Old Style", "'Iowan Old Style', ui-serif, Georgia, serif"),
            ReaderFont("charter", "Charter", "Charter, ui-serif, Georgia, serif"),
            ReaderFont("baskerville", "Baskerville", "Baskerville, ui-serif, serif"),
            ReaderFont("system", "San Francisco", "-apple-system, ui-sans-serif, sans-serif"),
            ReaderFont("avenir", "Avenir Next", "'Avenir Next', ui-sans-serif, sans-serif"),
        )

        val default: ReaderFont get() = all.first()

        fun byId(id: String): ReaderFont = all.firstOrNull { it.id == id } ?: default
    }
}

/**
 * Persists [AppSettings] as JSON on disk.
 *
 * Files rather than a database, per #14: a personal library needs no schema,
 * no codegen and no migrations. Unknown keys are ignored and any read failure
 * falls back to defaults, so a settings file written by a newer build — or a
 * corrupt one — degrades to "fresh install" instead of preventing launch.
 */
class SettingsStore(
    private val storage: Storage,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    },
) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings

    private fun load(): AppSettings =
        storage.readText(FILE)
            ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        // Failures are reported, never swallowed. Settings silently failing to
        // persist looks exactly like the app ignoring you, and a runCatching
        // that drops the reason leaves nothing to diagnose from.
        val encoded = runCatching { json.encodeToString(next) }
            .onFailure { println("[calibre] settings encode failed: ${it.message}") }
            .getOrNull() ?: return
        runCatching { storage.writeText(FILE, encoded) }
            .onFailure { println("[calibre] settings write failed: ${it.message}") }
    }

    private companion object {
        const val FILE = "settings.json"
    }
}
