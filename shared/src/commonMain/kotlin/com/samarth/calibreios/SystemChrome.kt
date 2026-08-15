package com.samarth.calibreios

/**
 * Hide the platform's own chrome — on iOS the status bar and home indicator —
 * for distraction-free reading. No-op where the platform has no equivalent.
 */
expect fun setSystemChromeHidden(hidden: Boolean)
