package com.samarth.calibreios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samarth.calibreios.calibre.CalibreBook
import com.samarth.calibreios.calibre.CalibreClient
import com.samarth.calibreios.calibre.CalibreServer
import com.samarth.calibreios.calibre.Library
import com.samarth.calibreios.discovery.ServerDiscovery
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The calibre library: browse, search, download, and manage what is on device.
 *
 * Reading requires the book to be on the device (offline-first, #8), so the
 * list distinguishes three states — on device, transferring, available on the
 * server — rather than pretending every book is equally ready to open.
 */
@Composable
fun LibraryScreen(
    library: Library,
    onSettings: () -> Unit,
    onOpen: (CalibreServer, CalibreBook, String) -> Unit,
) {
    var server by remember { mutableStateOf(library.loadServer()) }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var port by remember { mutableStateOf((server?.port ?: 8080).toString()) }
    var search by remember { mutableStateOf("") }

    val books by library.books.collectAsState()
    val serverState by library.serverState.collectAsState()
    val downloads by library.downloads.collectAsState()
    val bytesOnDisk by library.bytesOnDisk.collectAsState()
    val hasMore by library.hasMore.collectAsState()

    var busy by remember { mutableStateOf(false) }
    var confirmRemoveAll by remember { mutableStateOf(false) }
    // Bumped on every explicit connect attempt. Without it, selecting a server
    // equal to the current one is a no-op -- no request is sent and the previous
    // error stays on screen looking like a fresh failure.
    var attempt by remember { mutableIntStateOf(0) }

    // Keyed on `attempt`, not just `server`. NSURLSession evaluates the network
    // path when it is created and caches the verdict; a session built before the
    // Local Network permission was granted stays "unsatisfied" for its whole
    // life, so retrying with the same session can never succeed.
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
        busy = true
        library.clearServerState()
        library.refresh(c, search)
        busy = false
    }

    // Debounced, and `drop(1)` so the initial empty value does not immediately
    // re-run the load above. Search is server-side (#8) rather than filtering
    // the loaded page, or results would silently stop at whatever was paged in.
    LaunchedEffect(client) {
        snapshotFlow { search }
            .drop(1)
            .debounce(300)
            .distinctUntilChanged()
            .collect { term ->
                val c = client ?: return@collect
                busy = true
                library.refresh(c, term)
                busy = false
            }
    }

    // Harness-only: simctl cannot tap, so drive queue-then-open from the launch
    // environment. Goes with the rest of the harness (M5).
    LaunchedEffect(books, downloads, client) {
        val wanted = autoOpenBookId() ?: return@LaunchedEffect
        val c = client ?: return@LaunchedEffect
        val s = server ?: return@LaunchedEffect
        val target = books.firstOrNull { it.id == wanted } ?: return@LaunchedEffect
        if (library.isDownloaded(s, target)) onOpen(s, target, library.localPath(s, target))
        else library.enqueue(c, s, target)
    }

    val listState = rememberLazyListState()
    // Page in as the end approaches, rather than making the reader find a button.
    LaunchedEffect(listState, client) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .map { it >= books.size - 5 }
            .distinctUntilChanged()
            .filter { it && hasMore && !busy }
            .collect { library.loadMore(client ?: return@collect) }
    }

    if (confirmRemoveAll) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAll = false },
            title = { Text("Remove all downloads?") },
            text = {
                Text(
                    "Frees ${humanSize(bytesOnDisk)}. The books stay in calibre and " +
                        "your reading positions are kept, so re-downloading puts you " +
                        "back where you were."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    library.removeAllDownloads()
                    confirmRemoveAll = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAll = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onSettings) { Text("Settings") }
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text("Search", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Server", fontSize = 11.sp) },
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
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
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
            is Library.ServerState.Reachable -> "connected · ${books.size} shown"
            is Library.ServerState.Unreachable -> s.detail
            Library.ServerState.Unknown ->
                if (busy) "connecting…" else "not connected · showing what's cached"
        }
        Text(
            stateLine,
            fontSize = 11.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        LazyColumn(Modifier.weight(1f), state = listState) {
            items(books, key = { it.id }) { book ->
                val srv = server
                BookRow(
                    book = book,
                    downloaded = srv != null && library.isDownloaded(srv, book),
                    download = downloads[book.id],
                    onTap = {
                        val s = srv ?: return@BookRow
                        val c = client ?: return@BookRow
                        if (library.isDownloaded(s, book)) {
                            onOpen(s, book, library.localPath(s, book))
                        } else {
                            // Tapping an available book queues it; tapping a
                            // failed one retries, resuming from the partial.
                            library.enqueue(c, s, book)
                        }
                    },
                    onRemove = { server?.let { library.removeDownload(it, book) } },
                )
                HorizontalDivider()
            }

            if (hasMore) {
                item {
                    Text(
                        "loading more…",
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                    )
                }
            }
        }

        if (bytesOnDisk > 0) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${humanSize(bytesOnDisk)} on device", fontSize = 11.sp)
                TextButton(onClick = { confirmRemoveAll = true }) {
                    Text("Remove all", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: CalibreBook,
    downloaded: Boolean,
    download: Library.Download?,
    onTap: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = download !is Library.Download.Running) { onTap() }
            .padding(vertical = 10.dp),
    ) {
        Text(book.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val status = when {
                download is Library.Download.Queued -> "queued"
                download is Library.Download.Running -> "${(download.fraction * 100).toInt()}%"
                download is Library.Download.Failed -> "failed — tap to resume"
                downloaded -> "on device"
                else -> "tap to download"
            }
            Text(
                "${book.authorLine} · ${humanSize(book.sizeBytes)} · $status",
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (downloaded) {
                TextButton(onClick = onRemove) { Text("Remove", fontSize = 11.sp) }
            }
        }

        if (download is Library.Download.Running) {
            LinearProgressIndicator(
                progress = { download.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        if (download is Library.Download.Failed) {
            Text(
                download.reason,
                fontSize = 10.sp,
                maxLines = 3,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Integer MB hid a 55 KB book as "0 MB". */
internal fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
