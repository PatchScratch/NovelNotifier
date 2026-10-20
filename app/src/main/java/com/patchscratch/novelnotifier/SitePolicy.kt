package com.patchscratch.novelnotifier

/**
 * Host-level access policies (Wayne's rulings, 2026-10-20). Shared by the
 * probe sweep, the bulk downloader, and the reader so a ruling is enforced
 * everywhere at once.
 */
object SitePolicy {
    /**
     * NEVER scraped by anything in this app — Qidian and Webnovel
     * actively seek out and ban apps that access their sites. No probe
     * fetch, no bulk fetch, no reader WebView load or extraction; the UI
     * shows a warning instead and reading stays possible only outside the
     * app (open in browser).
     */
    val PROHIBITED_HOSTS = setOf("qidian.com", "webnovel.com")

    /** Null when the URL's host is allowed (or unknowable, e.g. NU redirects). */
    fun prohibitedHostOf(url: String): String? {
        val h = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return h.takeIf { isProhibited(it) }
    }

    fun isProhibited(host: String): Boolean =
        host.isNotBlank() && PROHIBITED_HOSTS.any { host == it || host.endsWith(".$it") }
}
