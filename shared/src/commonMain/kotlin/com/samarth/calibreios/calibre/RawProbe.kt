package com.samarth.calibreios.calibre

/**
 * Diagnostic: fetch [url] with the platform's own HTTP stack, bypassing Ktor.
 *
 * Exists to answer one question — when a LAN request fails, is it iOS refusing
 * or our client misconfigured? Ktor's Darwin engine builds its own
 * NSURLSession, so a plain shared-session request is the control.
 *
 * Temporary. Delete once the local-network path is settled.
 */
expect fun rawProbe(url: String, report: (String) -> Unit)
