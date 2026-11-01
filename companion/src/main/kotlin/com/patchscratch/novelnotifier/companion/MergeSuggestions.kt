package com.patchscratch.novelnotifier.companion

/**
 * Merge SUGGESTIONS — never automatic merges (Wayne's bulk-deletion law:
 * a human confirms every one). The panel looks for series the phone's
 * auto-grouping missed:
 *
 *   1. Same auto key, different series ids — the strongest signal (the
 *      titles normalize to the same series but sit apart).
 *   2. Near-identical normalized names (Levenshtein ≤ threshold) — catches
 *      "Ms. Elf!" vs "Ms. Elf!," style punctuation drift and stray volume
 *      suffixes that split keys.
 *
 * Only same-library pairs are proposed; merging across libraries is not a
 * thing on the phone either.
 */
data class MergeSuggestion(val from: SeriesRow, val into: SeriesRow, val reason: String)

object MergeSuggestions {

    /** Normalize for comparison: lowercase, strip punctuation/whitespace. */
    fun normalize(name: String): String =
        name.lowercase().replace(Regex("[\\p{Punct}\\s　、。・…]+"), "")

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1,
                    cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    fun forSeries(series: List<SeriesRow>, limit: Int = 300): List<MergeSuggestion> {
        val out = mutableListOf<MergeSuggestion>()
        val seen = HashSet<Pair<String, String>>()

        fun propose(a: SeriesRow, b: SeriesRow, reason: String) {
            val pair = if (a.id < b.id) a.id to b.id else b.id to a.id
            // Prefer merging the SMALLER series into the bigger one (fewer
            // volume moves on the phone, the survivor is the established row).
            val (from, into) = if (a.volumes.size <= b.volumes.size) a to b else b to a
            if (from.id == into.id) return
            if (pair !in seen) {
                seen.add(pair)
                out.add(MergeSuggestion(from, into, reason))
            }
        }

        // 1) Exact key collisions.
        val byKey = series.groupBy { it.key }
        byKey.values.filter { it.size > 1 }.forEach { group ->
            group.sortedBy { it.id }.zipWithNext().forEach { (a, b) -> propose(a, b, "same key") }
        }

        // 2) Fuzzy names (bounded: normalized prefix buckets keep this off
        //    the O(n²) cliff on a 6K-library; edit distance ≤ 2 for typical
        //    light-novel title lengths, scaled down for short names).
        val byPrefix = series.groupBy { normalize(it.name).take(3) }
        byPrefix.values.forEach { bucket ->
            for (i in bucket.indices) {
                for (j in i + 1 until bucket.size) {
                    val a = bucket[i]; val b = bucket[j]
                    if (a.key == b.key) continue    // already handled above
                    val na = normalize(a.name); val nb = normalize(b.name)
                    if (na.isEmpty() || nb.isEmpty()) continue
                    val max = maxOf(na.length, nb.length)
                    val allowance = when {
                        max < 6 -> 0
                        max < 15 -> 1
                        else -> 2
                    }
                    if (allowance > 0 && levenshtein(na, nb) <= allowance) {
                        propose(a, b, "similar name")
                    }
                }
            }
        }
        return out.take(limit)
    }
}
