package com.zenlabs.filemanager.screen.main.tab.files.provider

/**
 * Pure, unit-testable policy helpers for the Recent files screen.
 *
 * The Recent screen used to walk every storage root and merge the whole MediaStore, which is why
 * it could list tens of thousands of files. It now queries only files modified inside a bounded
 * recency window and applies the filtering/sorting rules defined here.
 *
 * The helpers keep no Android dependencies so they can run under plain JUnit.
 */
object RecentFilePolicy {

    /** Cache/temporary/database sidecar suffixes that should never appear in Recent. */
    val TEMP_DB_SUFFIXES = listOf(
        ".tmp", ".temp", ".bak", ".log",
        ".crdownload", ".part",
        "-shm", "-wal", "-journal",
        ".db-shm", ".db-wal", ".db-journal"
    )

    /** Directory names that hold recycle-bin / thumbnail style bookkeeping, never user files. */
    val SKIPPED_SYSTEM_DIR_NAMES = setOf(
        "lost+found", ".thumbnails", ".Trash", ".trashed", ".recycle", "recyclebin"
    )

    /**
     * True when [name] looks like a temporary cache or SQLite sidecar file.
     */
    fun isTempOrDatabaseName(name: String): Boolean {
        if (name.isEmpty()) return true
        val lower = name.lowercase()
        return TEMP_DB_SUFFIXES.any { lower.endsWith(it) }
    }

    /**
     * Decides whether a candidate path must be excluded from the Recent list.
     *
     * Mirrors the rules used by the general file scanner so Recent never hides a normal user file,
     * while still dropping obvious internal, cache, temporary, and system artefacts:
     *
     *  - the recycle bin (when known),
     *  - dot-files/folders when hidden files are disabled,
     *  - any hidden path component when the user asked to remove hidden paths,
     *  - user-configured excluded paths,
     *  - `Android/data` and `Android/obb` plus temp/database files when that filter is enabled.
     */
    fun isExcludedPath(
        path: String,
        name: String,
        showHiddenFiles: Boolean,
        excludeHiddenPaths: Boolean,
        hideTempAndDb: Boolean,
        excludedPaths: Set<String>,
        recycleBinPath: String
    ): Boolean {
        if (path.isEmpty() || name.isEmpty()) return true
        if (recycleBinPath.isNotEmpty() && path.startsWith(recycleBinPath)) return true
        if (!showHiddenFiles && name.startsWith(".")) return true
        if (excludeHiddenPaths && (name.startsWith(".") || path.contains("/."))) return true
        for (excluded in excludedPaths) {
            if (excluded.isNotEmpty() && path.startsWith(excluded)) return true
        }
        if (hideTempAndDb) {
            if (path.contains("/Android/data/") || path.contains("/Android/obb/")) return true
            if (isTempOrDatabaseName(name)) return true
        }
        return false
    }

    /**
     * Returns [items] ordered from newest to oldest using [lastModified] as the timestamp selector.
     */
    fun <T> sortNewestFirst(items: Collection<T>, lastModified: (T) -> Long): List<T> =
        items.sortedWith(compareByDescending(lastModified))

    /**
     * Converts a MediaStore timestamp (seconds since the epoch) to milliseconds.
     *
     * MediaStore reports `DATE_MODIFIED`/`DATE_ADDED` in seconds while the filesystem and
     * [java.io.File.lastModified] use milliseconds. The Recent list stores milliseconds, so every
     * MediaStore timestamp must pass through here; mixing the two units would silently scramble
     * the newest-first ordering.
     */
    fun mediaStoreSecondsToMillis(seconds: Long): Long = seconds * 1000L
}
