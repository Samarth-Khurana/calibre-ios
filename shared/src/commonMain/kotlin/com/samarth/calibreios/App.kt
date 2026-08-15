package com.samarth.calibreios

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samarth.calibreios.calibre.Library
import com.samarth.calibreios.reader.ReaderCommand
import com.samarth.calibreios.reader.ReaderController
import com.samarth.calibreios.reader.ReaderEvent
import com.samarth.calibreios.reader.ReaderTheme
import com.samarth.calibreios.reader.ReaderView
import com.samarth.calibreios.settings.SettingsStore
import com.samarth.calibreios.storage.Storage
import com.samarth.calibreios.tts.Speaker
import com.samarth.calibreios.tts.TtsSession
import com.samarth.calibreios.tts.createSpeaker

private const val FIXTURE_URL = "epubapp://reader/fixture.epub"
private const val BOOK_URL = "epubapp://book/current"

@Composable
fun App() {
    val systemDark = isSystemInDarkTheme()

    val storage = remember { Storage() }
    val library = remember { Library(storage) }
    val settingsStore = remember { SettingsStore(storage) }
    val settings by settingsStore.settings.collectAsState()
    val speaker = remember { createSpeaker() }

    var openBook by remember { mutableStateOf<OpenBook?>(null) }
    var showSettings by remember { mutableStateOf(harnessScreen() == "settings") }

    // Harness-only: land straight in the reader on a local book, so the reader
    // can be exercised without the download path. Goes with the harness.
    LaunchedEffect(Unit) {
        val local = testBookPath()
        if (local != null && harnessScreen() != "settings") {
            openBook = OpenBook(local.substringAfterLast('/'), local)
        }
    }

    // The shell and the page are styled by two independent systems and nothing
    // keeps them in sync (spec §5.3) -- so both are derived here, from the one
    // stored palette, rather than each deciding for itself.
    val palette = settings.appearance.resolvedPalette(systemDark)
    val readerTheme = settings.toReaderTheme(systemDark)

    MaterialTheme(
        colorScheme = if (palette.isDark) darkColorScheme() else lightColorScheme(),
    ) {
        // Surface is load-bearing, not decoration: without it nothing paints a
        // background, so the window shows through and the shell's text is drawn
        // in a colour chosen for the opposite background.
        Surface(Modifier.fillMaxSize()) {
            val book = openBook
            when {
                showSettings -> Column(Modifier.fillMaxSize().safeContentPadding()) {
                    SettingsScreen(
                        settings = settings,
                        // Re-read each time the screen opens: the user may have
                        // installed a voice in iOS Settings since last time, and
                        // #3 established there is no way to be notified of that.
                        voices = remember(showSettings) { speaker.voices("en") },
                        onChange = { settingsStore.update(it) },
                        onClose = { showSettings = false },
                    )
                }

                book == null -> Column(Modifier.fillMaxSize().safeContentPadding()) {
                    LibraryScreen(
                        library = library,
                        onSettings = { showSettings = true },
                    ) { _, calibreBook, path ->
                        openBook = OpenBook(calibreBook.title, path)
                    }
                }

                else -> ReaderScreen(
                    book = book,
                    readerTheme = readerTheme,
                    ttsSettings = settings.readAloud.toSessionSettings(),
                    speaker = speaker,
                    onSettings = { showSettings = true },
                    onBack = { openBook = null },
                )
            }
        }
    }
}

/** A book that has been downloaded and is ready to open. */
data class OpenBook(val title: String, val path: String)

/**
 * The reader.
 *
 * Still provisional — chrome, the "Read from here" bar and the conflict bar are
 * M4. What M3 settles is that appearance and read-aloud come from *stored
 * settings* rather than from controls parked next to the page.
 */
@Composable
private fun ReaderScreen(
    book: OpenBook,
    readerTheme: ReaderTheme,
    ttsSettings: TtsSession.Settings,
    speaker: Speaker,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    var controller by remember { mutableStateOf<ReaderController?>(null) }
    var session by remember { mutableStateOf<TtsSession?>(null) }
    var status by remember { mutableStateOf("opening…") }
    var location by remember { mutableStateOf("") }
    var resume by remember { mutableStateOf("") }
    var rendered by remember { mutableStateOf(false) }
    var autoplayed by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val state by (session?.state?.collectAsState()
        ?: remember { mutableStateOf(TtsSession.State.Idle) })
    val current by (session?.current?.collectAsState() ?: remember { mutableStateOf(null) })

    LaunchedEffect(session, ttsSettings) { session?.update(ttsSettings) }

    // Keyed on `rendered`, not on the controller: `setTheme` goes through
    // `renderer.setStyles`, and the renderer does not exist until the book has
    // been opened. Sending it earlier fails silently inside the web view and
    // leaves an unthemed page with nothing to re-trigger it.
    LaunchedEffect(rendered, readerTheme) {
        if (rendered) controller?.send(ReaderCommand.SetTheme(readerTheme))
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {

        ReaderView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            onReady = { ready ->
                controller = ready
                session = TtsSession(scope, ready, speaker)
            },
            onEvent = { event ->
                session?.onReaderEvent(event)
                when (event) {
                    is ReaderEvent.Ready -> {
                        val path = book.path.ifBlank { testBookPath().orEmpty() }
                        if (path.isNotBlank()) {
                            controller?.setBookPath(path)
                            controller?.send(
                                ReaderCommand.Open(BOOK_URL, useCalibreBookmark = true)
                            )
                        } else {
                            controller?.send(ReaderCommand.Open(FIXTURE_URL))
                        }
                    }

                    is ReaderEvent.Opened -> {
                        rendered = true
                        status = event.title ?: book.title
                        resume = if (event.calibrePos != null) "resumed at the Mac's position" else ""
                    }

                    is ReaderEvent.Location -> {
                        if (autoplayEnabled() && !autoplayed) {
                            autoplayed = true
                            session?.start(fromStart = true)
                        }
                        location = "${event.fraction?.let { (it * 100).toInt() } ?: "-"}% " +
                            (event.tocLabel ?: "")
                    }

                    is ReaderEvent.Error -> status = "error: ${event.message}"

                    else -> Unit
                }
            },
        )

        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { session?.start(fromStart = true) }) { Text("▶ read") }
                Button(
                    onClick = {
                        if (state == TtsSession.State.Paused) session?.resume()
                        else session?.pause()
                    }
                ) { Text(if (state == TtsSession.State.Paused) "resume" else "pause") }
                Button(onClick = { session?.skip() }) { Text("skip") }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = { controller?.send(ReaderCommand.PrevPage) }) { Text("◀") }
                OutlinedButton(onClick = { controller?.send(ReaderCommand.NextPage) }) { Text("▶") }
                OutlinedButton(onClick = onSettings) { Text("settings") }
                OutlinedButton(onClick = onBack) { Text("library") }
            }

            Text(
                listOfNotNull(
                    status.takeIf { it.isNotBlank() },
                    location.takeIf { it.isNotBlank() },
                    resume.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
            current?.let {
                Text(
                    "▶ ${it.text.take(80)}",
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
