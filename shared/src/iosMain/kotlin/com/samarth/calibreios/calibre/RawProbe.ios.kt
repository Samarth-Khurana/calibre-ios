package com.samarth.calibreios.calibre

import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL

actual fun rawProbe(url: String, report: (String) -> Unit) {
    val target = NSURL.URLWithString(url) ?: run {
        report("raw: bad url")
        return
    }
    NSURLSession.sharedSession.dataTaskWithURL(target) { data, response, error ->
        val status = (response as? NSHTTPURLResponse)?.statusCode
        val text = when {
            error != null -> "raw: FAILED ${error.localizedDescription}"
            else -> "raw: OK status=$status bytes=${data?.length ?: 0uL}"
        }
        println("[calibre] $text")
        report(text)
    }.resume()
}
