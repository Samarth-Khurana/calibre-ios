package com.samarth.calibreios.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The reader viewport, hosted inside the Compose shell.
 *
 * On iOS this is a WKWebView embedded via `UIKitView`; Compose owns everything
 * around it (library, settings, TTS controls) and the web view owns only the
 * page. That split is the architecture decided in #7.
 *
 * [onEvent] receives everything the engine reports -- location changes, TTS
 * chunks, errors. [onReady] hands back the controller so the caller can drive
 * the engine once the bridge script has loaded.
 */
@Composable
expect fun ReaderView(
    modifier: Modifier = Modifier,
    onReady: (ReaderController) -> Unit = {},
    onEvent: (ReaderEvent) -> Unit = {},
)

/** Platform-agnostic handle for driving the reader. */
interface ReaderController {
    fun send(command: ReaderCommand)

    /** Point the book route at a downloaded EPUB on local storage. */
    fun setBookPath(path: String)
}
