package com.samarth.calibreios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samarth.calibreios.calibre.CalibreBook
import com.samarth.calibreios.calibre.CalibreClient
import com.samarth.calibreios.calibre.CalibreServer
import com.samarth.calibreios.calibre.Library
import com.samarth.calibreios.calibre.rawProbe
import com.samarth.calibreios.discovery.ServerDiscovery
import kotlinx.coroutines.launch

/** Integer MB hid a 55 KB book as "0 MB". */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/**
 * The calibre library, minimally.
 *
 * M1 deliberately stops here: a list is enough to prove the path from the Mac's
 * library to a book open on the phone at the desktop viewer's position. The
 * real browse experience is M2.
 */
@Composable
fun LibraryScreen(
    library: Library,
    onOpen: (CalibreServer, CalibreBook, String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf(library.loadServer()) }
    var host by remember { mutableStateOf(server?.host ?: "127.0.0.1") }
    var port by remember { mutableStateOf((server?.port ?: 8080).toString()) }

    val books by library.books.collectAsState()
    val serverState by library.serverState.collectAsState()
    val downloading by library.downloading.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // Bumped on every explicit connect attempt. Without it, selecting a server
    // equal to the current one is a no-op -- the client is not recreated, no
    // request is sent, and the previous error just stays on screen looking like
    // a fresh failure. There has to be a way to retry.
    var attempt by remember { mutableIntStateOf(0) }
    var probe by remember { mutableStateOf("") }

    // Keyed on `attempt`, not just `server`. NSURLSession evaluates the network
    // path when it is created and caches the verdict; a session built before the
    // Local Network permission was granted stays "unsatisfied" for its whole
    // life, so retrying with the same session can never succeed. A new client
    // per attempt is the only way a retry means anything.
    val client = remember(server, attempt) { server?.let { CalibreClient(it) } }
    DisposableEffect(client) { onDispose { client?.close() } }

    // Started before anything else, and deliberately so. Beyond finding the
    // server, a Bonjour browse is what raises the Local Network permission
    // prompt -- an HTTP request to a LAN address is blocked *silently*, leaving
    // the app absent from Settings with nothing for the user to switch on.
    val discovery = remember { ServerDiscovery() }
    val discovered by discovery.found.collectAsState()
    DisposableEffect(discovery) {
        discovery.start()
        onDispose { discovery.stop() }
    }

    // Cached catalogue first, so the list is populated before -- and without --
    // the network. Reaching the server is enrichment, never a precondition.
    LaunchedEffect(Unit) { library.loadCached() }

    LaunchedEffect(client, attempt) {
        val c = client ?: return@LaunchedEffect
        val s = server
        busy = true
        library.clearServerState()
        // Control experiment: the same URL through the platform's own stack.
        // If this succeeds while Ktor fails, the problem is ours, not iOS's.
        if (s != null) rawProbe("${s.baseUrl}/ajax/search?num=1") { probe = it }
        library.refresh(c)
        busy = false
    }

    // Harness-only: simctl cannot tap, so let the download-and-open path be
    // driven from the launch environment. Goes with the rest of the harness.
    LaunchedEffect(books, client) {
        val wanted = autoOpenBookId() ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val s = server ?: return@LaunchedEffect
        val target = books.firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        note = "auto-opening #${target.id}…"
        library.ensureDownloaded(c, s, target).fold(
            onSuccess = { path -> onOpen(s, target, path) },
            onFailure = { note = "Download failed: ${it.message}" },
        )
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        Text("Calibre library", style = MaterialTheme.typography.titleMedium)

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = host.isNotBlank() && !busy,
                onClick = {
                    val next = CalibreServer(host.trim(), port.toIntOrNull() ?: 8080)
                    library.saveServer(next)
                    server = next
                    attempt++
                },
            ) { Text("Connect") }
        }

        if (discovered.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                discovered.forEach { found ->
                    AssistChip(
                        onClick = {
                            host = found.host
                            port = found.port.toString()
                            val next = CalibreServer(found.host, found.port)
                            library.saveServer(next)
                            server = next
                            attempt++
                        },
                        label = { Text("${found.name} · ${found.host}", fontSize = 11.sp) },
                    )
                }
            }
        }

        val stateLine = when (val s = serverState) {
            is Library.ServerState.Reachable -> "connected · ${books.size} books"
            is Library.ServerState.Unreachable -> s.detail
            Library.ServerState.Unknown -> if (busy) "connecting…" else "not connected"
        }
        Text(stateLine, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (note.isNotEmpty()) Text(note, fontSize = 11.sp, maxLines = 2)
        if (probe.isNotEmpty()) Text(probe, fontSize = 10.sp, maxLines = 4)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(books, key = { it.id }) { book ->
                val srv = server
                val progress = downloading[book.id]
                val downloaded = srv != null && library.isDownloaded(srv, book)

                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = srv != null && client != null && progress == null) {
                            val s = srv ?: return@clickable
                            val c = client ?: return@clickable
                            scope.launch {
                                note = ""
                                library.ensureDownloaded(c, s, book).fold(
                                    onSuccess = { path -> onOpen(s, book, path) },
                                    onFailure = { note = "Download failed: ${it.message}" },
                                )
                            }
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Text(book.title, fontSize = 14.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(book.authorLine)
                            if (book.sizeBytes > 0) append(" · ${humanSize(book.sizeBytes)}")
                            append(if (downloaded) " · on device" else " · tap to download")
                        },
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
