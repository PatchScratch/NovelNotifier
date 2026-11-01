package com.patchscratch.novelnotifier.companion

/**
 * Mirror of the phone's `/api/v1` JSON contract (see app LanApi.kt).
 * Field names must match exactly — these classes parse what the phone
 * serves, nothing else.
 */

data class LibRef(val id: String, val name: String)

data class DeviceStatus(
    val device: String,
    val appVersion: String,
    val apiVersion: Int,
    val port: Int,
    val libraries: List<LibRef>
)

data class Shelf(val id: String, val name: String, val hidden: Boolean)

data class Volume(
    val id: String,
    val title: String,
    val author: String,
    val chapters: Int,
    val addedAt: Long,
    val linked: Boolean,
    val pos: Int
)

data class SeriesRow(
    val id: String,
    val name: String,
    val key: String,
    val description: String,
    val coverBookId: String,
    val volumes: List<Volume>
) {
    val firstVolumeId: String get() = volumes.firstOrNull()?.id ?: ""
    val coverRef: String get() = coverBookId.ifBlank { firstVolumeId }
    val volumeCount: Int get() = volumes.size
    val lastAdded: Long get() = volumes.maxOfOrNull { it.addedAt } ?: 0L
}

data class LibraryData(
    val library: LibRef,
    val shelves: List<Shelf>,
    /** seriesId → shelfId (single membership, move-only — the phone's rule). */
    val assignments: Map<String, String>,
    val series: List<SeriesRow>
) {
    fun shelfOf(seriesId: String): Shelf? =
        assignments[seriesId]?.let { sid -> shelves.firstOrNull { it.id == sid } }

    fun countIn(shelfId: String): Int = assignments.values.count { it == shelfId }

    val unshelvedCount: Int get() = series.count { assignments[it.id] == null }
}
