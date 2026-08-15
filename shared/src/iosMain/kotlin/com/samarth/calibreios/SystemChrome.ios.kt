package com.samarth.calibreios

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewController

/**
 * Root controller that can hide the status bar, hosting Compose inside it.
 *
 * iOS has no setter for status-bar visibility: a controller *declares* a
 * preference by overriding a property, and the system re-asks when nudged.
 * Only the root controller is asked, which is the whole difficulty here —
 * `ComposeUIViewController` is the root and cannot be subclassed.
 *
 * Two better routes are closed by the bindings rather than by design:
 * `addChildViewController` (proper containment) and the application-level
 * `setStatusBarHidden` are both absent from Kotlin/Native's UIKit. So Compose
 * is hosted as a plain subview: its *view* is in the hierarchy and lays out
 * normally, while this controller stays the one iOS asks.
 *
 * **The home indicator stays visible.** `prefersHomeIndicatorAutoHidden` is not
 * exposed as an overridable member either, and has no application-level
 * equivalent. It dims itself over a still page, so full screen is one thin line
 * short of complete.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ReaderHostController : UIViewController(nibName = null, bundle = null) {

    private val compose: UIViewController = ComposeUIViewController { App() }

    var chromeHidden: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // Nothing happens without this: the override below is only
            // consulted when the system is told the answer may have changed.
            setNeedsStatusBarAppearanceUpdate()
        }

    override fun prefersStatusBarHidden(): Boolean = chromeHidden

    override fun viewDidLoad() {
        super.viewDidLoad()
        val child: UIView = compose.view
        child.setFrame(view.bounds)
        // Flexible on both axes: without containment there is no automatic
        // resize, so the child must follow rotation itself.
        child.setAutoresizingMask(
            UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight,
        )
        view.addSubview(child)
    }
}

private var host: ReaderHostController? = null

/**
 * Built here, exported from `MainViewController.kt`: the Obj-C facade class the
 * Swift entry point imports is named after the *file*, so that function has to
 * live in a file of that name.
 */
internal fun createRootController(): UIViewController =
    ReaderHostController().also { host = it }

actual fun setSystemChromeHidden(hidden: Boolean) {
    host?.chromeHidden = hidden
}
