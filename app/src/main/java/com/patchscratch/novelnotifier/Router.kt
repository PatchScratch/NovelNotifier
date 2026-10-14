package com.patchscratch.novelnotifier

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Explicit navigation stack (Phase 4b). Replaces NovelApp's 12 boolean
 * flags + 4 nullable "open screen" pointers, whose display order lived in
 * an 18-branch when-block and whose back order was a hand-mirrored second
 * when in the BackHandler — two tables that had to be kept in sync by hand.
 *
 * Only one screen composes at a time: the TOP of the stack. Back pops.
 * The historical ordering rules are preserved:
 * - Reader is the topmost surface; XlateFiles sits above it.
 * - NU login overlays whatever was below it; its Cancel re-opens Settings.
 * - Syosetu/Kakuyomu login replace Settings (Settings closes first, or it
 *   swallows the navigation — the when-block composed Settings earlier).
 * - Library screens sit BELOW their detail screens so back returns to the
 *   library, not to the home screen.
 *
 * Two deliberate changes from the flag era (both fixes):
 * - The login screens' own BackHandlers now pop the stack (previously only
 *   Kakuyomu's was reachable; Syosetu's relied on its screen-level handler
 *   while NovelApp's handler omitted it entirely).
 * - A session-expiry login request surfaces immediately instead of waiting
 *   for the user to leave the reader first.
 *
 * Routes carry identifiers only — payloads (Novel, SyosetuNovel, work,
 * ReaderTarget) live in the ViewModels, set at push time.
 */
sealed interface Route {
    /** The workspace home for the active site (NU / Syosetu / Kakuyomu). */
    data object Home : Route
    data class NuDetail(val url: String) : Route
    data class SyosetuDetail(val url: String) : Route
    data class KakuyomuDetail(val id: String) : Route
    /** "" shows all bookmarks; otherwise the category id. */
    data class SyosetuLibrary(val category: String) : Route
    data object KakuyomuLibrary : Route
    data object Discover : Route
    data object Search : Route
    data object Settings : Route
    data object EditLists : Route
    data object Onboarding : Route
    data object NuLogin : Route
    data object SyosetuLogin : Route
    data object KakuyomuLogin : Route
    data object Reader : Route
    data object XlateFiles : Route
}

class Router(initial: List<Route> = listOf(Route.Home)) {

    var stack by mutableStateOf(initial)
        private set

    val top: Route get() = stack.last()

    /** True when back should be intercepted instead of leaving the app. */
    val canGoBack: Boolean get() = stack.size > 1

    /** Open [route] above the current top. Re-pushing the same top is a no-op
     *  (the flag era set the same flag twice). */
    fun push(route: Route) {
        if (top != route) stack = stack + route
    }

    /** Swap the top route (e.g. Settings → that site's login screen). */
    fun replace(route: Route) {
        if (top != route) stack = stack.dropLast(1) + route
    }

    /** System/toolbar back. Returns false when there is nothing under us. */
    fun pop(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        return true
    }

    /**
     * Deep link: close every overlay and show [route] above Home. If the
     * reader is currently up, it stays on top — the flag era kept it, and
     * yanking someone out of a chapter mid-read is worse than deferring.
     */
    fun resetTo(route: Route) {
        val keepReader = top is Route.Reader
        stack = if (keepReader) listOf(Route.Home, route, Route.Reader) else listOf(Route.Home, route)
    }
}
