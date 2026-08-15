package com.samarth.calibreios.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android placeholder.
 *
 * The map's constraint is that Android stays *viable*, not that it ships in V1
 * (#7). The contract in `ReaderContract.kt` is deliberately platform-neutral, so
 * the real implementation here is an Android `WebView` plus a
 * `WebViewAssetLoader` serving the same `epubapp://` routes and a
 * `@JavascriptInterface` in place of `WKScriptMessageHandler`. Nothing above
 * this layer should need to change.
 */
@Composable
actual fun ReaderView(
    modifier: Modifier,
    onReady: (ReaderController) -> Unit,
    onEvent: (ReaderEvent) -> Unit,
) {
    Box(modifier) {
        Text("Reader is iOS-only in V1.")
    }
}
