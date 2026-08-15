package com.samarth.calibreios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import com.samarth.calibreios.tts.createSpeaker
import kotlinx.coroutines.flow.toList

/**
 * Prototype harness for #9 -- not the app.
 *
 * #12 proved the reader engine works. This one exists to make the *interaction*
 * concrete enough to react to: pick a delimiter, set how long the highlight is
 * held before the voice starts, and listen. The trace pane below the transport
 * reports the measured hold per chunk, which is the number the setting is
 * ultimately about.
 *
 * Everything here is throwaway. The parts meant to survive are `TtsSession`
 * (common) and `IosSpeaker` (iOS).
 */
private const val FIXTURE_URL = "epubapp://reader/fixture.epub"

@Composable
fun App() {
    MaterialTheme {
        var controller by remember { mutableStateOf<ReaderController?>(null) }
        var session by remember { mutableStateOf<TtsSession?>(null) }
        var status by remember { mutableStateOf("loading…") }
        var location by remember { mutableStateOf("") }
        var voiceNote by remember { mutableStateOf("") }
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

        val scope = rememberCoroutineScope()
        val speaker = remember { createSpeaker() }

        val state by (session?.state?.collectAsState()
            ?: remember { mutableStateOf(TtsSession.State.Idle) })
        val current by (session?.current?.collectAsState()
            ?: remember { mutableStateOf(null) })

        // Push settings changes into the live session.
        LaunchedEffect(session, delimiter, holdMillis, rate) {
            session?.update(
                TtsSession.Settings(
                    delimiter = delimiter,
                    holdMillis = holdMillis.toLong(),
                    speech = SpeechSettings(rate = rate),
                )
            )
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
                    // #3 found enhanced/premium voices cannot be downloaded or
                    // even detected as *installable* via API -- only listed once
                    // the user has added them in Settings. Surface what is
                    // actually present, because it decides how good this sounds.
                    val voices = speaker.voices("en")
                    voiceNote = "voices: ${voices.size}, best=" +
                        (voices.firstOrNull()?.let { "${it.name} (${it.quality})" } ?: "none")
                },
                onEvent = { event ->
                    session?.onReaderEvent(event)
                    when (event) {
                        is ReaderEvent.Ready -> {
                            status = "bridge ready"
                            controller?.send(ReaderCommand.Open(FIXTURE_URL))
                        }

                        is ReaderEvent.Opened ->
                            status = "opened: ${event.title ?: "?"} (${event.sectionCount} sec)"

                        is ReaderEvent.Location -> {
                            if (autoplayEnabled() && !autoplayed) {
                                autoplayed = true
                                session?.start(fromStart = true)
                            }
                            location = "cfi=${event.cfi?.take(30) ?: "-"} " +
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

                // ---- delimiter --------------------------------------------
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TtsDelimiter.presets.forEach { preset ->
                        FilterChip(
                            selected = delimiter == preset,
                            onClick = { delimiter = preset },
                            label = { Text(preset.label, fontSize = 11.sp) },
                        )
                    }
                    // The edge case worth feeling: a delimiter that matches
                    // nothing. Falls back to one chunk per block, i.e. the whole
                    // paragraph highlighted and read at once.
                    val never = remember { TtsDelimiter.Custom("\\uE000", "No match") }
                    FilterChip(
                        selected = delimiter == never,
                        onClick = { delimiter = never },
                        label = { Text("No match", fontSize = 11.sp) },
                    )
                }

                Text("hold before speaking: ${holdMillis.toInt()} ms", fontSize = 11.sp)
                Slider(
                    value = holdMillis,
                    onValueChange = { holdMillis = it },
                    valueRange = 0f..2000f,
                )

                Text("rate: ${(rate * 100).toInt()}%", fontSize = 11.sp)
                Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.25f..0.75f)

                // ---- transport ---------------------------------------------
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
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

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OutlinedButton(onClick = { controller?.send(ReaderCommand.PrevPage) }) {
                        Text("◀")
                    }
                    OutlinedButton(onClick = { controller?.send(ReaderCommand.NextPage) }) {
                        Text("▶")
                    }
                    OutlinedButton(
                        onClick = { controller?.send(ReaderCommand.SetTheme(ReaderTheme.Sepia)) }
                    ) { Text("sepia") }
                    OutlinedButton(
                        onClick = { controller?.send(ReaderCommand.SetTheme(ReaderTheme.Dark)) }
                    ) { Text("dark") }
                }

                // ---- readout ------------------------------------------------
                Text("$state · $status", fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(location, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(voiceNote, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current?.let {
                    Text("▶ ${it.text.take(90)}", fontSize = 10.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 90.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    log.forEach {
                        Text(it, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
