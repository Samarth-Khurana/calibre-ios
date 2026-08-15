package com.samarth.calibreios

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samarth.calibreios.reader.ReaderCommand
import com.samarth.calibreios.reader.ReaderController
import com.samarth.calibreios.reader.ReaderEvent
import com.samarth.calibreios.reader.ReaderTheme
import com.samarth.calibreios.reader.ReaderView
import com.samarth.calibreios.reader.TtsDelimiter
import com.samarth.calibreios.tts.SpeechSettings
import com.samarth.calibreios.tts.TtsSession
import com.samarth.calibreios.tts.VoiceInfo
import com.samarth.calibreios.tts.createSpeaker

/**
 * Prototype harness for #9 -- not the app.
 *
 * #12 proved the reader engine works. This one exists to make the *interaction*
 * concrete enough to react to: pick a delimiter and a voice, set how long the
 * highlight is held before the voice starts, and listen. The trace pane reports
 * the measured hold per chunk, which is the number the setting is about.
 *
 * Everything here is throwaway. The parts meant to survive are `TtsSession`
 * (common) and `IosSpeaker` (iOS).
 */
private const val FIXTURE_URL = "epubapp://reader/fixture.epub"
private const val BOOK_URL = "epubapp://book/current"

@Composable
fun App() {
    // Two independent theming systems have to agree: Compose for the shell and
    // CSS-in-the-web-view for the page. Nothing keeps them in sync automatically,
    // and getting it wrong is invisible until the device is actually in dark
    // mode -- see the note on `readerTheme` below.
    val systemDark = isSystemInDarkTheme()

    MaterialTheme(colorScheme = if (systemDark) darkColorScheme() else lightColorScheme()) {
        // Surface is load-bearing, not decoration: without it nothing paints a
        // background, so the window shows through and the shell's text is drawn
        // in a colour chosen for the opposite background.
        Surface(Modifier.fillMaxSize()) {
            HarnessContent(systemDark)
        }
    }
}

@Composable
private fun HarnessContent(systemDark: Boolean) {
    var controller by remember { mutableStateOf<ReaderController?>(null) }
    var session by remember { mutableStateOf<TtsSession?>(null) }
    var status by remember { mutableStateOf("loading…") }
    var location by remember { mutableStateOf("") }
    var resume by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    var delimiter by remember {
        mutableStateOf<TtsDelimiter>(
            autoplayDelimiter()
                ?.let { name -> TtsDelimiter.presets.firstOrNull { it.label == name } }
                ?: TtsDelimiter.Sentence
        )
    }
    var holdMillis by remember { mutableStateOf(400f) }
    var rate by remember { mutableStateOf(0.5f) }
    var autoplayed by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }

    var voices by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var voiceId by remember { mutableStateOf<String?>(null) }

    // The reader theme follows the system unless overridden. This is the second
    // half of the dark-mode fix: theming the Compose shell leaves the page inside
    // the web view untouched, because that is styled by CSS the shell never sees.
    var themeOverride by remember { mutableStateOf<ReaderTheme?>(null) }
    val readerTheme = themeOverride
        ?: if (systemDark) ReaderTheme.Dark else ReaderTheme.Light

    val scope = rememberCoroutineScope()
    val speaker = remember { createSpeaker() }

    val state by (session?.state?.collectAsState()
        ?: remember { mutableStateOf(TtsSession.State.Idle) })
    val current by (session?.current?.collectAsState() ?: remember { mutableStateOf(null) })

    LaunchedEffect(session, delimiter, holdMillis, rate, voiceId) {
        session?.update(
            TtsSession.Settings(
                delimiter = delimiter,
                holdMillis = holdMillis.toLong(),
                speech = SpeechSettings(rate = rate, voiceId = voiceId),
            )
        )
    }

    // Keyed on `rendered`, not on the controller: `setTheme` goes through
    // `renderer.setStyles`, and the renderer does not exist until the book has
    // been opened. Sending it earlier fails silently inside the web view and
    // leaves a white page under a dark shell, with nothing to re-trigger it.
    LaunchedEffect(rendered, readerTheme) {
        if (rendered) controller?.send(ReaderCommand.SetTheme(readerTheme))
    }

    LaunchedEffect(session) {
        session?.trace?.collect { line ->
            log.add(0, line)
            if (log.size > 40) log.removeLast()
        }
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {

        ReaderView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            onReady = { ready ->
                controller = ready
                session = TtsSession(scope, ready, speaker)
                voices = speaker.voices("en")
            },
            onEvent = { event ->
                session?.onReaderEvent(event)
                when (event) {
                    is ReaderEvent.Ready -> {
                        status = "bridge ready"
                        // A real calibre book if one was supplied, so the
                        // Mac -> iPhone resume path can be exercised against a
                        // genuine position; otherwise the bundled fixture,
                        // which has never been opened on a desktop.
                        val real = testBookPath()
                        if (real != null) {
                            controller?.setBookPath(real)
                            controller?.send(
                                ReaderCommand.Open(BOOK_URL, useCalibreBookmark = true)
                            )
                        } else {
                            controller?.send(ReaderCommand.Open(FIXTURE_URL))
                        }
                    }

                    is ReaderEvent.Opened -> {
                        rendered = true
                        status = "opened: ${event.title ?: "?"} (${event.sectionCount} sec)"
                        resume = when {
                            event.calibrePos == null -> "no calibre position in this book"
                            else -> "calibre ${event.calibrePos} → ${event.resolvedCfi ?: "unresolved"}"
                        }
                    }

                    is ReaderEvent.Location -> {
                        if (autoplayEnabled() && !autoplayed) {
                            autoplayed = true
                            session?.start(fromStart = true)
                        }
                        location = "cfi=${event.cfi?.take(28) ?: "-"} " +
                            "frac=${event.fraction?.let { (it * 100).toInt() } ?: "-"}% " +
                            (event.tocLabel ?: "")
                    }

                    is ReaderEvent.TtsReady -> status = "segmented: ${event.granularity}"

                    is ReaderEvent.Error -> status = "ERROR ${event.where}: ${event.message}"

                    else -> Unit
                }
            },
        )

        Column(Modifier.padding(horizontal = 8.dp)) {

            // ---- delimiter -------------------------------------------------
            ChipRow {
                TtsDelimiter.presets.forEach { preset ->
                    FilterChip(
                        selected = delimiter == preset,
                        onClick = { delimiter = preset },
                        label = { Text(preset.label, fontSize = 11.sp) },
                    )
                }
                // The edge case worth feeling: a delimiter that matches nothing.
                // Falls back to one chunk per block -- the whole paragraph
                // highlighted and read at once.
                val never = remember { TtsDelimiter.Custom("\\uE000", "No match") }
                FilterChip(
                    selected = delimiter == never,
                    onClick = { delimiter = never },
                    label = { Text("No match", fontSize = 11.sp) },
                )
            }

            // ---- voice -------------------------------------------------------
            // Sorted best-quality-first by the speaker. #3 established these
            // cannot be downloaded or detected as installable via API, so the
            // list is exactly what the user has already added in Settings.
            ChipRow {
                FilterChip(
                    selected = voiceId == null,
                    onClick = { voiceId = null },
                    label = { Text("System", fontSize = 11.sp) },
                )
                voices.forEach { voice ->
                    FilterChip(
                        selected = voiceId == voice.identifier,
                        onClick = { voiceId = voice.identifier },
                        label = {
                            Text(
                                if (voice.quality == "default") voice.name
                                else "${voice.name} · ${voice.quality}",
                                fontSize = 11.sp,
                            )
                        },
                    )
                }
            }

            Text("hold before speaking: ${holdMillis.toInt()} ms", fontSize = 11.sp)
            Slider(holdMillis, { holdMillis = it }, valueRange = 0f..2000f)

            Text("rate: ${(rate * 100).toInt()}%", fontSize = 11.sp)
            Slider(rate, { rate = it }, valueRange = 0.25f..0.75f)

            // ---- transport ----------------------------------------------------
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { session?.start(fromStart = true) }) { Text("▶ read") }
                Button(
                    onClick = {
                        if (state == TtsSession.State.Paused) session?.resume()
                        else session?.pause()
                    }
                ) { Text(if (state == TtsSession.State.Paused) "resume" else "pause") }
                Button(onClick = { session?.skip() }) { Text("skip") }
                OutlinedButton(onClick = { session?.stop() }) { Text("stop") }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = { controller?.send(ReaderCommand.PrevPage) }) { Text("◀") }
                OutlinedButton(onClick = { controller?.send(ReaderCommand.NextPage) }) { Text("▶") }
                OutlinedButton(onClick = { themeOverride = null }) { Text("auto") }
                OutlinedButton(onClick = { themeOverride = ReaderTheme.Sepia }) { Text("sepia") }
                OutlinedButton(
                    onClick = {
                        themeOverride =
                            if (readerTheme == ReaderTheme.Dark) ReaderTheme.Light
                            else ReaderTheme.Dark
                    }
                ) { Text("flip") }
            }

            // ---- readout --------------------------------------------------------
            Line("$state · $status")
            Line(location)
            if (resume.isNotEmpty()) Line(resume)
            Line(
                "voices: ${voices.size} · " +
                    (voices.firstOrNull { it.identifier == voiceId }?.let {
                        "${it.name} (${it.quality})"
                    } ?: "system default")
            )
            current?.let { Line("▶ ${it.text.take(90)}", maxLines = 2) }

            Column(
                Modifier.fillMaxWidth().heightIn(max = 84.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                log.forEach { Line(it) }
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Line(text: String, maxLines: Int = 1) {
    Text(text, fontSize = 10.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
