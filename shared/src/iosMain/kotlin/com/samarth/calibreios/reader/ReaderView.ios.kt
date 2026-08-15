package com.samarth.calibreios.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ReaderView(
    modifier: Modifier,
    onReady: (ReaderController) -> Unit,
    onEvent: (ReaderEvent) -> Unit,
) {
    // Assets are read out of Compose resources once, then held for the scheme
    // handler, which must answer synchronously.
    val assets by produceState<Map<String, ByteArray>?>(null) {
        value = ReaderAssets.load()
    }

    val loaded = assets ?: return

    val bridge = remember(loaded) { IosReaderBridge(loaded) }

    val controller = remember(bridge) {
        object : ReaderController {
            override fun send(command: ReaderCommand) = bridge.send(command)
            override fun setBookPath(path: String) = bridge.setBookPath(path)
        }
    }

    LaunchedEffect(bridge) {
        bridge.load()
        onReady(controller)
        bridge.events.collect(onEvent)
    }

    UIKitView(
        factory = { bridge.webView },
        modifier = modifier,
    )
}
