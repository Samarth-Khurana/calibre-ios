package com.samarth.calibreios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.samarth.calibreios.reader.PositionStore
import com.samarth.calibreios.reader.ReadingPosition
import com.samarth.calibreios.reader.Mark
import com.samarth.calibreios.reader.MarkStore
import com.samarth.calibreios.nowplaying.createNowPlaying
import com.samarth.calibreios.reader.SearchHit
import com.samarth.calibreios.reader.mergeMarks
import com.samarth.calibreios.reader.TocEntry
import com.samarth.calibreios.reader.shouldOfferMacPosition
import com.samarth.calibreios.settings.SettingsStore
import com.samarth.calibreios.storage.Storage
import com.samarth.calibreios.tts.Speaker
import com.samarth.calibreios.tts.TtsSession
import com.samarth.calibreios.tts.createSpeaker
import com.samarth.calibreios.tts.nowMillis

private const val FIXTURE_URL = "epubapp://reader/fixture.epub"
private const val BOOK_URL = "epubapp://book/current"

@Composable
fun App() {
    val systemDark = isSystemInDarkTheme()

    val storage = remember { Storage() }
    val library = remember { Library(storage) }
    val settingsStore = remember { SettingsStore(storage) }
    val positions = remember { PositionStore(storage) }
    val marks = remember { MarkStore(storage) }
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
                    ) { srv, calibreBook, path ->
                        openBook = OpenBook(
                            title = calibreBook.title,
                            path = path,
                            libraryId = srv.libraryId,
                            bookId = calibreBook.id,
                        )
                    }
                }

                else -> ReaderScreen(
                    book = book,
                    positions = positions,
                    marks = marks,
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

/**
 * A downloaded book, ready to open.
 *
 * Carries calibre's identity (#8) rather than only a path, because the reading
 * position is keyed on it and must survive the file being deleted (#15).
 */
data class OpenBook(
    val title: String,
    val path: String,
    val libraryId: String = "",
    val bookId: Int = 0,
)

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
    positions: PositionStore,
    marks: MarkStore,
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

    var selection by remember { mutableStateOf("") }
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    var showToc by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchHits by remember { mutableStateOf<ReaderEvent.SearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }

    var ownMarks by remember(book) {
        mutableStateOf(marks.own(book.libraryId, book.bookId))
    }
    var calibreMarks by remember(book) { mutableStateOf<List<Mark>>(emptyList()) }
    val allMarks = remember(ownMarks, calibreMarks) { mergeMarks(ownMarks, calibreMarks) }
    var macOffer by remember { mutableStateOf<String?>(null) }
    var lastToc by remember { mutableStateOf("") }

    // Read once: the phone's own position is what the book opens at, and a
    // differing Mac position is offered rather than applied (#8).
    val saved = remember(book) { positions.get(book.libraryId, book.bookId) }
    var lastSaved by remember(book) { mutableStateOf(saved) }

    val scope = rememberCoroutineScope()
    val nowPlaying = remember { createNowPlaying() }

    val state by (session?.state?.collectAsState()
        ?: remember { mutableStateOf(TtsSession.State.Idle) })
    val current by (session?.current?.collectAsState() ?: remember { mutableStateOf(null) })

    LaunchedEffect(session, ttsSettings) { session?.update(ttsSettings) }

    // The lock screen drives the same session as the in-app transport -- one
    // set of behaviour, two surfaces (#19).
    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        nowPlaying.setHandlers(
            onPlay = { if (s.state.value == TtsSession.State.Paused) s.resume() else s.start() },
            onPause = { s.pause() },
            onNext = { s.skip() },
            onPrevious = { s.previous() },
        )
    }

    // Keep the lock screen honest about what is playing.
    LaunchedEffect(state, lastToc, book) {
        nowPlaying.update(
            title = book.title,
            chapter = lastToc,
            playing = state == TtsSession.State.Speaking,
        )
    }

    // Stop claiming the lock screen when the reader closes; leaving stale
    // controls there implies a session that no longer exists.
    DisposableEffect(Unit) { onDispose { nowPlaying.clear() } }

    // Keyed on `rendered`, not on the controller: `setTheme` goes through
    // `renderer.setStyles`, and the renderer does not exist until the book has
    // been opened. Sending it earlier fails silently inside the web view and
    // leaves an unthemed page with nothing to re-trigger it.
    LaunchedEffect(rendered, readerTheme) {
        if (rendered) controller?.send(ReaderCommand.SetTheme(readerTheme))
    }

    if (showSearch) {
        SearchSheet(
            query = searchQuery,
            onQuery = { searchQuery = it },
            searching = searching,
            results = searchHits,
            onSubmit = {
                searching = true
                searchHits = null
                controller?.send(ReaderCommand.Search(searchQuery))
            },
            onPick = { hit ->
                controller?.send(ReaderCommand.GoTo(hit.cfi))
                showSearch = false
            },
            onDismiss = {
                showSearch = false
                controller?.send(ReaderCommand.ClearSearch)
            },
        )
    }

    if (showToc) {
        ContentsSheet(
            entries = toc,
            marks = allMarks,
            onPickEntry = { entry ->
                entry.href?.let { controller?.send(ReaderCommand.GoToHref(it)) }
                showToc = false
            },
            onPickMark = { mark ->
                controller?.send(ReaderCommand.GoTo(mark.cfi))
                showToc = false
            },
            onRemoveMark = { mark ->
                marks.remove(book.libraryId, book.bookId, mark.id)
                ownMarks = marks.own(book.libraryId, book.bookId)
                controller?.send(ReaderCommand.HideMark(mark.cfi))
            },
            onDismiss = { showToc = false },
        )
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {

        // Ask, never jump (#8). Reading is never silently discarded, so this is
        // an offer with a dismiss, not an automatic move.
        macOffer?.let { macCfi ->
            Row(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "The Mac is further on in this book",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    // GoTo with the converted CFI, not GoToFraction: #12 showed
                    // the CFI resolves to the right paragraph, while fractions
                    // are not comparable across renderers.
                    controller?.send(ReaderCommand.GoTo(macCfi))
                    macOffer = null
                }) { Text("Go there", fontSize = 12.sp) }
                TextButton(onClick = { macOffer = null }) { Text("Dismiss", fontSize = 12.sp) }
            }
        }

        // A top bar rather than more buttons below: five controls in one row
        // squeezed the last one to a sliver, and navigation out of the reader
        // does not belong next to the read-aloud transport anyway.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Library", fontSize = 13.sp) }
            Text(
                status,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            // The bookmark ribbon lives up here, where readers expect it, and
            // because the bottom row has now overflowed twice at five controls.
            TextButton(onClick = {
                controller?.send(ReaderCommand.MakeBookmark(id = "b${nowMillis()}"))
            }) { Text("⚑", fontSize = 15.sp) }
            TextButton(onClick = onSettings) { Text("Settings", fontSize = 13.sp) }
        }

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
                            // The phone's own position wins; the Mac's is still
                            // read, so it can be *offered* if it differs (#8).
                            controller?.send(
                                ReaderCommand.Open(
                                    url = BOOK_URL,
                                    initialLocation = saved?.cfi,
                                    useCalibreBookmark = true,
                                )
                            )
                        } else {
                            controller?.send(ReaderCommand.Open(FIXTURE_URL))
                        }
                    }

                    is ReaderEvent.Opened -> {
                        rendered = true
                        status = event.title ?: book.title
                        resume = when {
                            saved != null -> ""
                            event.calibrePos != null -> "opened at the Mac's position"
                            else -> ""
                        }
                        // Ask, never jump (#8).
                        if (shouldOfferMacPosition(saved, event.calibreRelative)) {
                            macOffer = event.calibreCfi
                        }
                        controller?.send(ReaderCommand.RequestToc)
                        controller?.send(ReaderCommand.RequestCalibreMarks)
                        // Paint what is already saved for this book.
                        if (ownMarks.isNotEmpty()) {
                            controller?.send(ReaderCommand.ShowMarks(marksJson(ownMarks)))
                        }
                        if (harnessMark()) {
                            controller?.send(ReaderCommand.MakeBookmark(id = "b-harness"))
                        }
                        harnessSearch()?.let { term ->
                            searchQuery = term
                            searching = true
                            controller?.send(ReaderCommand.Search(term))
                        }
                    }

                    is ReaderEvent.Toc -> toc = event.entries

                    is ReaderEvent.CalibreMarks -> {
                        calibreMarks = event.marks
                        println("[calibre] calibre marks: ${event.marks.size}")
                        val highlights = event.marks.filter { it.kind == Mark.Kind.Highlight }
                        if (highlights.isNotEmpty()) {
                            controller?.send(ReaderCommand.ShowMarks(marksJson(highlights)))
                        }
                    }

                    is ReaderEvent.MarkCreated -> {
                        // The engine owns CFIs, so a mark is only real once it
                        // reports back with one.
                        val mark = Mark(
                            id = event.id,
                            kind = if (event.kind == "bookmark") Mark.Kind.Bookmark
                            else Mark.Kind.Highlight,
                            cfi = event.cfi,
                            text = event.text,
                            label = lastToc,
                            createdAt = nowMillis(),
                        )
                        marks.add(book.libraryId, book.bookId, mark)
                        ownMarks = marks.own(book.libraryId, book.bookId)
                        println(
                            "[calibre] mark created: ${mark.kind} cfi=${mark.cfi.take(40)} " +
                                "text='${mark.text.take(40)}' own=${ownMarks.size}"
                        )
                    }

                    is ReaderEvent.SearchResults -> {
                        searching = false
                        searchHits = event
                        println(
                            "[calibre] search '${event.query}' -> ${event.results.size} hits" +
                                (if (event.truncated) " (capped)" else "") +
                                (event.results.firstOrNull()?.let { " | first: ${it.excerpt.take(70)}" } ?: "")
                        )
                    }

                    is ReaderEvent.Selection -> selection = if (event.active) event.text else ""

                    is ReaderEvent.Location -> {
                        if (autoplayEnabled() && !autoplayed) {
                            autoplayed = true
                            session?.start()
                        }
                        lastToc = event.tocLabel ?: lastToc
                        location = "${event.fraction?.let { (it * 100).toInt() } ?: "-"}% " +
                            (event.tocLabel ?: "")

                        // Persist as you read. Keyed on calibre's identity, not
                        // the file path, so it survives the book being removed
                        // to reclaim space (#15).
                        val cfi = event.cfi
                        if (cfi != null && book.bookId != 0) {
                            val next = ReadingPosition(
                                cfi = cfi,
                                fraction = event.fraction ?: 0.0,
                                updatedAt = nowMillis(),
                            )
                            if (next.cfi != lastSaved?.cfi) {
                                lastSaved = next
                                positions.put(book.libraryId, book.bookId, next)
                            }
                        }
                    }

                    is ReaderEvent.Error -> {
                        println("[calibre] reader error @${event.where}: ${event.message}")
                        status = "error: ${event.message}"
                    }

                    else -> Unit
                }
            },
        )

        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {

            // Our own bar rather than an item in the iOS callout (#13): the
            // native menu needs UIMenu interop with no clean Kotlin/Native
            // binding and no Android equivalent. Accepted cost -- iOS's own
            // callout can be on screen at the same time.
            if (selection.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "“${selection.take(40)}”",
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        selection = ""
                        session?.start()
                    }) { Text("Read from here", fontSize = 12.sp) }
                    TextButton(onClick = {
                        controller?.send(
                            ReaderCommand.MakeHighlight(id = "h${nowMillis()}")
                        )
                        selection = ""
                    }) { Text("Highlight", fontSize = 12.sp) }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                // Back one sentence: the only recovery from a missed line, and
                // the most-wanted control in a read-aloud app (#13).
                OutlinedButton(onClick = { session?.previous() }) { Text("↺") }
                Button(onClick = { session?.start() }) { Text("▶ read") }
                Button(
                    onClick = {
                        if (state == TtsSession.State.Paused) session?.resume()
                        else session?.pause()
                    }
                ) { Text(if (state == TtsSession.State.Paused) "resume" else "pause") }
                OutlinedButton(onClick = { session?.skip() }) { Text("↻") }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Four, not five. Five overflowed here once already; page
                // turning also works by tapping the page edges, so the arrows
                // are the pair that can afford to be compact.
                OutlinedButton(onClick = { controller?.send(ReaderCommand.PrevPage) }) { Text("◀") }
                OutlinedButton(
                    enabled = toc.isNotEmpty(),
                    onClick = { showToc = true },
                ) { Text("contents") }
                OutlinedButton(onClick = { showSearch = true }) { Text("find") }
                OutlinedButton(onClick = { controller?.send(ReaderCommand.NextPage) }) { Text("▶") }
            }

            Text(
                listOfNotNull(
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

/**
 * Contents and Marks in one sheet.
 *
 * Both answer "where in this book do I want to be", so they belong together --
 * and it keeps the reader's bottom row from overflowing, which it already did
 * once at five controls.
 */
@Composable
private fun ContentsSheet(
    entries: List<TocEntry>,
    marks: List<Mark>,
    onPickEntry: (TocEntry) -> Unit,
    onPickMark: (Mark) -> Unit,
    onRemoveMark: (Mark) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { tab = 0 }) {
                    Text("Contents", fontSize = 14.sp,
                        color = if (tab == 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { tab = 1 }) {
                    Text("Marks (${marks.size})", fontSize = 14.sp,
                        color = if (tab == 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (tab == 0) {
                    items(entries.size) { i ->
                        val entry = entries[i]
                        Text(
                            entry.label,
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickEntry(entry) }
                                .padding(start = (entry.depth * 14).dp, top = 8.dp, bottom = 8.dp),
                        )
                        HorizontalDivider()
                    }
                } else if (marks.isEmpty()) {
                    item {
                        Text(
                            "No marks yet. Select text to highlight it, or use " +
                                "\u201cmark\u201d to bookmark this page.",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                } else {
                    items(marks.size) { i ->
                        val mark = marks[i]
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f).clickable { onPickMark(mark) },
                            ) {
                                Text(
                                    if (mark.kind == Mark.Kind.Highlight) "\u201c${mark.text}\u201d"
                                    else mark.text.ifBlank { mark.label.ifBlank { "Bookmark" } },
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (mark.note.isNotBlank()) {
                                    Text(
                                        mark.note,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    listOfNotNull(
                                        mark.label.takeIf { it.isNotBlank() },
                                        // Say where it came from, because it is
                                        // also why there is no Remove (#17).
                                        "from calibre".takeIf { mark.fromCalibre },
                                    ).joinToString(" \u00b7 "),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Read-only: calibre marks live in the Mac's copy of
                            // the book, so a delete here would be undone by the
                            // next download.
                            if (!mark.fromCalibre) {
                                TextButton(onClick = { onRemoveMark(mark) }) {
                                    Text("Remove", fontSize = 11.sp)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Full-book search. Results are capped by the engine; say so when they are. */
@Composable
private fun SearchSheet(
    query: String,
    onQuery: (String) -> Unit,
    searching: Boolean,
    results: ReaderEvent.SearchResults?,
    onSubmit: () -> Unit,
    onPick: (SearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find in book") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text("Search", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    enabled = query.isNotBlank() && !searching,
                    onClick = onSubmit,
                ) { Text(if (searching) "Searching…" else "Search") }

                results?.let { r ->
                    Text(
                        buildString {
                            append(if (r.results.isEmpty()) "No matches" else "${r.results.size} matches")
                            // Never let a cap read as completeness.
                            if (r.truncated) append(" (first 60)")
                        },
                        fontSize = 11.sp,
                    )
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(r.results.size) { i ->
                            val hit = r.results[i]
                            Column(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(hit) }
                                    .padding(vertical = 8.dp),
                            ) {
                                if (hit.label.isNotBlank()) {
                                    Text(hit.label, fontSize = 10.sp)
                                }
                                Text(
                                    hit.excerpt,
                                    fontSize = 12.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Minimal JSON for the engine's `showMarks`; only the painting fields. */
private fun marksJson(marks: List<Mark>): String =
    marks.filter { it.kind == Mark.Kind.Highlight }.joinToString(
        separator = ",",
        prefix = "[",
        postfix = "]",
    ) { m ->
        """{"id":"${m.id}","kind":"highlight","cfi":${quote(m.cfi)},""" +
            """"color":${m.color?.let { quote(it) } ?: "null"}}"""
    }

private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
