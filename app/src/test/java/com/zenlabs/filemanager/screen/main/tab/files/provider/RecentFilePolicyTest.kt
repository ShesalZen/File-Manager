package com.zenlabs.filemanager.screen.main.tab.files.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure filtering/sorting rules that keep the Recent screen small and sane.
 *
 * The screen previously walked the whole device and listed tens of thousands of files; these rules
 * decide which candidates survive and in what order.
 */
class RecentFilePolicyTest {

    private data class Entry(val name: String, val lastModified: Long)

    @Test
    fun sortingReturnsNewestFirst() {
        val entries = listOf(
            Entry("middle.bin", 200L),
            Entry("oldest.bin", 100L),
            Entry("newest.bin", 300L)
        )

        val sorted = RecentFilePolicy.sortNewestFirst(entries) { it.lastModified }

        assertEquals(listOf("newest.bin", "middle.bin", "oldest.bin"), sorted.map { it.name })
    }

    @Test
    fun sortingIsStableForIdenticalTimestamps() {
        val entries = listOf(
            Entry("first.bin", 500L),
            Entry("second.bin", 500L),
            Entry("third.bin", 500L)
        )

        val sorted = RecentFilePolicy.sortNewestFirst(entries) { it.lastModified }

        assertEquals(listOf("first.bin", "second.bin", "third.bin"), sorted.map { it.name })
    }

    @Test
    fun tempAndDatabaseSidecarNamesAreDetected() {
        listOf(
            "document.tmp",
            "document.temp",
            "backup.bak",
            "session.log",
            "movie.mp4.crdownload",
            "download.part",
            "library.db-shm",
            "library.db-wal",
            "library.db-journal",
            "library-journal"
        ).forEach { name ->
            assertTrue(
                "expected '$name' to look temporary",
                RecentFilePolicy.isTempOrDatabaseName(name)
            )
        }
    }

    @Test
    fun tempDetectionIsCaseInsensitiveAndIgnoresRealUserFiles() {
        assertTrue(RecentFilePolicy.isTempOrDatabaseName("PHOTO.TMP"))
        assertFalse(RecentFilePolicy.isTempOrDatabaseName("photo.jpg"))
        assertFalse(RecentFilePolicy.isTempOrDatabaseName("notes.txt"))
        assertFalse(RecentFilePolicy.isTempOrDatabaseName("wallet-notes.pdf"))
    }

    @Test
    fun emptyOrBlankNamesAreTreatedAsTemporary() {
        assertTrue(RecentFilePolicy.isTempOrDatabaseName(""))
    }

    @Test
    fun emptyPathOrNameIsAlwaysExcluded() {
        assertTrue(excluded(path = "", name = "file.txt"))
        assertTrue(excluded(path = "/storage/emulated/0/file.txt", name = ""))
    }

    @Test
    fun dotFilesAreExcludedOnlyWhenHiddenFilesAreDisabled() {
        assertTrue(excluded(path = "/storage/emulated/0/.secret", name = ".secret"))
        assertFalse(
            excluded(
                path = "/storage/emulated/0/.secret",
                name = ".secret",
                showHiddenFiles = true
            )
        )
    }

    @Test
    fun hiddenPathComponentsAreExcludedWhenTheUserAsksForIt() {
        assertTrue(
            excluded(
                path = "/storage/emulated/0/.cache/photo.jpg",
                name = "photo.jpg",
                showHiddenFiles = true,
                excludeHiddenPaths = true
            )
        )
    }

    @Test
    fun recycleBinContentsAreExcluded() {
        assertTrue(
            excluded(
                path = "/storage/emulated/0/.zenlabsRecycleBin/deleted.txt",
                name = "deleted.txt",
                recycleBinPath = "/storage/emulated/0/.zenlabsRecycleBin"
            )
        )
    }

    @Test
    fun userConfiguredExcludedPathsAreExcluded() {
        assertTrue(
            excluded(
                path = "/storage/emulated/0/Private/plan.txt",
                name = "plan.txt",
                excludedPaths = setOf("/storage/emulated/0/Private")
            )
        )
    }

    @Test
    fun androidDataAndObbAreExcludedOnlyWhenTheTempFilterIsOn() {
        val dataPath = "/storage/emulated/0/Android/data/com.example/cache/blob.bin"
        val obbPath = "/storage/emulated/0/Android/obb/com.example/main.1.obb"

        assertTrue(excluded(path = dataPath, name = "blob.bin", hideTempAndDb = true))
        assertTrue(excluded(path = obbPath, name = "main.1.obb", hideTempAndDb = true))
        assertFalse(excluded(path = dataPath, name = "blob.bin", hideTempAndDb = false))
    }

    @Test
    fun tempFilesAreExcludedOnlyWhenTheTempFilterIsOn() {
        val path = "/storage/emulated/0/Download/report.pdf.crdownload"

        assertTrue(excluded(path = path, name = "report.pdf.crdownload", hideTempAndDb = true))
        assertFalse(excluded(path = path, name = "report.pdf.crdownload", hideTempAndDb = false))
    }

    @Test
    fun ordinaryUserFilesInDownloadsSurviveEveryFilter() {
        assertFalse(
            excluded(
                path = "/storage/emulated/0/Download/report.pdf",
                name = "report.pdf",
                showHiddenFiles = true,
                excludeHiddenPaths = true,
                hideTempAndDb = true,
                excludedPaths = setOf("/storage/emulated/0/Private"),
                recycleBinPath = "/storage/emulated/0/.zenlabsRecycleBin"
            )
        )
    }

    @Test
    fun bookkeepingDirectoriesAreListedAsSkipped() {
        assertTrue(RecentFilePolicy.SKIPPED_SYSTEM_DIR_NAMES.contains("lost+found"))
        assertTrue(RecentFilePolicy.SKIPPED_SYSTEM_DIR_NAMES.contains(".thumbnails"))
        assertTrue(RecentFilePolicy.SKIPPED_SYSTEM_DIR_NAMES.contains(".Trash"))
    }

    @Test
    fun recentFileStoresLastModifiedInMilliseconds() {
        // Both collectors feed the same newest-first ordering, so they must agree on the unit.
        // MediaStore reports seconds; the Recent list stores milliseconds (File.lastModified()).
        val seconds = 1_700_000_000L
        val millis = RecentFilePolicy.mediaStoreSecondsToMillis(seconds)

        assertEquals(1_700_000_000_000L, millis)
        assertTrue(millis > 1_000_000_000_000L)
        assertEquals(millis, RecentFilePolicy.mediaStoreSecondsToMillis(millis / 1000L))
    }

    /**
     * Convenience wrapper mirroring how [StorageProvider] calls the policy, so each test only has to
     * spell out the flags it actually cares about.
     */
    private fun excluded(
        path: String,
        name: String,
        showHiddenFiles: Boolean = false,
        excludeHiddenPaths: Boolean = false,
        hideTempAndDb: Boolean = false,
        excludedPaths: Set<String> = emptySet(),
        recycleBinPath: String = ""
    ): Boolean = RecentFilePolicy.isExcludedPath(
        path = path,
        name = name,
        showHiddenFiles = showHiddenFiles,
        excludeHiddenPaths = excludeHiddenPaths,
        hideTempAndDb = hideTempAndDb,
        excludedPaths = excludedPaths,
        recycleBinPath = recycleBinPath
    )
}
