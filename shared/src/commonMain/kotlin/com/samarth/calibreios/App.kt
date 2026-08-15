package com.samarth.calibreios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.samarth.calibreios.reader.ReaderCommand
import com.samarth.calibreios.reader.ReaderController
import com.samarth.calibreios.reader.ReaderEvent
import com.samarth.calibreios.reader.ReaderTheme
import com.samarth.calibreios.reader.ReaderView
import com.samarth.calibreios.reader.TtsChunk
import com.samarth.calibreios.reader.TtsGranularity

/**
 * Spike harness for #12 -- not the app.
 *
 * Exercises every leg of the Kotlin <-> JS bridge against a bundled fixture
 * book: open, theme, paginate, and the delimiter-style TTS loop (fetch chunks,
 * highlight the upcoming sentence, advance). Speech synthesis itself is not
 * wired here; #3 established AVSpeechSynthesizer separately, and the question
 * this harness answers is whether the *engine* side behaves.
 */
private const val FIXTURE_URL = "epubapp://reader/fixture.epub"

@Composable
fun App() {
    MaterialTheme {
        var controller by remember { mutableStateOf<ReaderController?>(null) }
        var status by remember { mutableStateOf("loading…") }
        var location by remember { mutableStateOf("") }
        var chunks by remember { mutableStateOf<List<TtsChunk>>(emptyList()) }
        var chunkIndex by remember { mutableStateOf(0) }
        var dark by remember { mutableStateOf(false) }
        var ttsInitDone by remember { mutableStateOf(false) }
        var ttsReady by remember { mutableStateOf(false) }

        Column(Modifier.fillMaxSize().safeContentPadding()) {

            ReaderView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onReady = { controller = it },
                onEvent = { event ->
                    when (event) {
                        is ReaderEvent.Ready -> {
                            status = "bridge ready"
                            controller?.send(ReaderCommand.Open(FIXTURE_URL))
                        }

                        is ReaderEvent.Opened -> {
                            status = "opened: ${event.title ?: "?"} " +
                                "(${event.sectionCount} sec) ${event.diag ?: ""}"
                        }

                        is ReaderEvent.Location -> {
                            // TTS segmentation needs a rendered section; relocate is
                            // the first point where renderer.getContents() is populated.
                            if (!ttsInitDone) {
                                ttsInitDone = true
                                controller?.send(ReaderCommand.TtsInit(TtsGranularity.Sentence))
                            }
                            location = "cfi=${event.cfi?.take(34) ?: "-"} " +
                                "frac=${event.fraction?.let { (it * 100).toInt() } ?: "-"}% " +
                                (event.tocLabel ?: "")
                        }

                        is ReaderEvent.TtsReady -> {
                            status = "tts ready (${event.granularity})"
                            ttsReady = true
                        }

                        is ReaderEvent.TtsChunks -> {
                            chunks = event.chunks
                            chunkIndex = 0
                            status = "chunks=${event.chunks.size} done=${event.done}"
                            chunks.firstOrNull()?.mark?.let {
                                controller?.send(ReaderCommand.TtsHighlight(it))
                            }
                        }

                        is ReaderEvent.ThemeApplied -> status = "theme applied"

                        is ReaderEvent.Error -> status = "ERROR ${event.where}: ${event.message}"
                    }
                },
            )

            // Self-drive for the spike: simctl cannot tap, so step through the
            // loop automatically once TTS is ready. Remove with the harness.
            LaunchedEffect(ttsReady) {
                if (!ttsReady) return@LaunchedEffect
                delay(1200); controller?.send(ReaderCommand.NextPage)
                delay(1200); controller?.send(ReaderCommand.NextPage)
                delay(1200); controller?.send(ReaderCommand.SetTheme(ReaderTheme.Sepia))
                // Re-init after paging: foliate's TTS binds to the rendered
                // section, so it goes stale once the reader moves on.
                delay(1200); controller?.send(ReaderCommand.TtsInit(TtsGranularity.Sentence))
                delay(1200); controller?.send(ReaderCommand.TtsNext(fromStart = true))
                delay(1200); controller?.send(ReaderCommand.TtsNext())
            }

            Column(Modifier.padding(8.dp)) {
                Text(status, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(location, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                chunks.getOrNull(chunkIndex)?.let {
                    Text("say: ${it.text.take(70)}", fontSize = 10.sp, maxLines = 2)
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(onClick = { controller?.send(ReaderCommand.PrevPage) }) { Text("◀") }
                    Button(onClick = { controller?.send(ReaderCommand.NextPage) }) { Text("▶") }
                    Button(onClick = {
                        dark = !dark
                        controller?.send(
                            ReaderCommand.SetTheme(if (dark) ReaderTheme.Dark else ReaderTheme.Sepia)
                        )
                    }) { Text("theme") }
                    Button(onClick = {
                        // Advance one sentence: highlight the next, or pull the next block.
                        val next = chunkIndex + 1
                        if (next < chunks.size) {
                            chunkIndex = next
                            chunks[next].mark?.let { controller?.send(ReaderCommand.TtsHighlight(it)) }
                        } else {
                            controller?.send(ReaderCommand.TtsNext())
                        }
                    }) { Text("say ▶") }
                }
            }
        }
    }
}
