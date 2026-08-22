package com.vrplayer.filebrowser

enum class SortBy {
    NAME,
    DATE,
    SIZE,
    TYPE,
    LAST_PLAYED
}

// Directories are grouped before files regardless of sort mode, matching the
// convention of every mainstream file browser.
fun sortMediaEntries(entries: List<MediaEntry>, sortBy: SortBy, ascending: Boolean = true): List<MediaEntry> {
    val fieldComparator = when (sortBy) {
        SortBy.NAME -> compareBy<MediaEntry> { it.name.lowercase() }
        SortBy.DATE -> compareBy { it.lastModified }
        SortBy.SIZE -> compareBy { it.sizeBytes }
        SortBy.TYPE -> compareBy { it.type.name }
        SortBy.LAST_PLAYED -> compareBy { it.lastPlayedAt ?: 0L }
    }

    val directoriesFirst = compareByDescending<MediaEntry> { it.type == MediaType.DIRECTORY }
    val ordered = if (ascending) fieldComparator else fieldComparator.reversed()

    return entries.sortedWith(directoriesFirst.then(ordered))
}
