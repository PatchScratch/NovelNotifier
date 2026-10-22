package com.patchscratch.novelnotifier

import android.content.Context
import android.content.SharedPreferences

/**
 * Recent search queries per surface (Wayne, 2026-10-22: "text entry needs a
 * history") — the library/site search fields retype long queries against a
 * 5K library. One prefs string per scope (sets lose order; a delimited
 * string keeps most-recent-first), capped at 20 entries. Long-press a chip
 * to forget one entry.
 */
object SearchHistory {

    private const val MAX = 20
    private const val SEP = "⟂"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("novel_prefs", Context.MODE_PRIVATE)

    private fun key(scope: String) = "search_hist_$scope"

    fun recent(context: Context, scope: String): List<String> =
        prefs(context).getString(key(scope), "")
            ?.split(SEP)?.filter { it.isNotBlank() } ?: emptyList()

    /** Prepend [query] (deduped), newest first, capped. */
    fun push(context: Context, scope: String, query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + recent(context, scope).filter { it != clean }).take(MAX)
        prefs(context).edit().putString(key(scope), next.joinToString(SEP)).apply()
    }

    fun remove(context: Context, scope: String, query: String) {
        val next = recent(context, scope).filter { it != query }
        prefs(context).edit().putString(key(scope), next.joinToString(SEP)).apply()
    }

    fun clear(context: Context, scope: String) {
        prefs(context).edit().remove(key(scope)).apply()
    }
}
