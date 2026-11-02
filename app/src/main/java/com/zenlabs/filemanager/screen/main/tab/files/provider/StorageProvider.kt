package com.zenlabs.filemanager.screen.main.tab.files.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.media.MediaScannerConnection
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.ContextCompat
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.R
import com.zenlabs.filemanager.screen.main.tab.files.holder.ContentHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.LocalFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RemoteFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RootFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.StorageDevice
import com.zenlabs.filemanager.screen.main.tab.files.holder.VirtualFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.misc.FileMimeType
import com.zenlabs.filemanager.screen.main.tab.files.misc.FileSortingPrefs
import com.zenlabs.filemanager.screen.main.tab.files.misc.SortingMethod.SORT_BY_DATE
import com.zenlabs.filemanager.screen.main.tab.files.misc.SortingMethod.SORT_BY_NAME
import com.zenlabs.filemanager.screen.main.tab.files.misc.SortingMethod.SORT_BY_SIZE
import com.zenlabs.filemanager.screen.main.tab.files.misc.SortingMethod.SORT_BY_TYPE
import com.zenlabs.filemanager.screen.main.tab.files.misc.StorageDeviceType.EXTERNAL_STORAGE
import com.zenlabs.filemanager.screen.main.tab.files.misc.StorageDeviceType.INTERNAL_STORAGE
import com.zenlabs.filemanager.screen.main.tab.files.misc.StorageDeviceType.REMOTE_STORAGE
import com.zenlabs.filemanager.screen.main.tab.files.misc.StorageDeviceType.ROOT
import com.zenlabs.filemanager.screen.main.tab.files.service.remote.NetworkConnectionsService
import com.zenlabs.filemanager.screen.main.tab.home.holder.RecentFile
import java.io.File

object StorageProvider {
    suspend fun getStorageDevices(context: Context): List<StorageDevice> {
        val storageDevices = mutableListOf<StorageDevice>()

        storageDevices.add(getPrimaryInternalStorage(context))

        val externalStoragePairs = getExternalStorageDirectories(context)

        for ((label, externalDir) in externalStoragePairs) {
            if (externalDir.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
                continue
            }

            val statFs = StatFs(externalDir.absolutePath)
            val totalSize = statFs.totalBytes
            val availableSize = statFs.availableBytes
            val usedSize = totalSize - availableSize
            val storageLabel = label.ifEmpty { "${externalDir.name}" }

            storageDevices.add(
                StorageDevice(
                    LocalFileHolder(externalDir),
                    storageLabel,
                    totalSize,
                    usedSize,
                    EXTERNAL_STORAGE
                )
            )
        }

        if (!globalClass.preferencesManager.hideRootStorage) {
            storageDevices.add(getRoot(context))
        }

        NetworkConnectionsService.getConnections(context).forEach { connection ->
            storageDevices.add(
                StorageDevice(
                    RemoteFileHolder.rootHolder(connection),
                    connection.name,
                    0L,
                    0L,
                    REMOTE_STORAGE
                )
            )
        }

        return storageDevices
    }

    fun getRoot(context: Context): StorageDevice {
        val externalStorageDir = Environment.getRootDirectory()

        val externalStatFs = StatFs(externalStorageDir.absolutePath)

        val externalTotalSize = externalStatFs.totalBytes
        val externalAvailableSize = externalStatFs.availableBytes
        val externalUsedSize = externalTotalSize - externalAvailableSize

        return StorageDevice(
            RootFileHolder(),
            context.getString(R.string.root_dir),
            externalTotalSize,
            externalUsedSize,
            ROOT
        )
    }

    fun getPrimaryInternalStorage(context: Context): StorageDevice {
        val externalStorageDir = Environment.getExternalStorageDirectory()

        val externalStatFs = StatFs(externalStorageDir.absolutePath)

        val externalTotalSize = externalStatFs.totalBytes
        val externalAvailableSize = externalStatFs.availableBytes
        val externalUsedSize = externalTotalSize - externalAvailableSize

        return StorageDevice(
            LocalFileHolder(externalStorageDir),
            context.getString(R.string.internal_storage),
            externalTotalSize,
            externalUsedSize,
            INTERNAL_STORAGE
        )
    }

    fun getAvailableStorageRoots(context: Context): List<Pair<String, File>> {
        val list = mutableListOf<Pair<String, File>>()
        list.add(Pair(context.getString(R.string.internal_storage), Environment.getExternalStorageDirectory()))
        val externalPairs = getExternalStorageDirectories(context)
        for (pair in externalPairs) {
            if (pair.second.absolutePath != Environment.getExternalStorageDirectory().absolutePath &&
                list.none { it.second.absolutePath == pair.second.absolutePath }
            ) {
                list.add(pair)
            }
        }
        val rootDir = File("/")
        if (rootDir.exists()) {
            list.add(Pair(context.getString(R.string.root_dir), rootDir))
        }
        return list
    }

    private fun getExternalStorageDirectories(context: Context): List<Pair<String, File>> {
        val storageList = mutableListOf<Pair<String, File>>()
        val addedPaths = mutableSetOf<String>()
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        // Method 1: Using ContextCompat.getExternalFilesDirs ()
        ContextCompat.getExternalFilesDirs(context, null).forEach { directory ->
            val volume = directory?.parentFile?.parentFile?.parentFile?.parentFile
            if (volume != null && volume.exists() && addedPaths.add(volume.absolutePath)) {
                var label = ""
                try {
                    val storageVolume = storageManager.getStorageVolume(volume)
                    if (storageVolume != null) {
                        label =
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                storageVolume.getDescription(context) ?: volume.name
                            } else {
                                @Suppress("DEPRECATION")
                                storageVolume.getDescription(context) ?: volume.name
                            }
                    }
                } catch (_: Exception) { /* Ignore */
                }
                storageList.add(Pair(label.ifEmpty { volume.name }, volume))
            }
        }

        // Method 2: Parse /proc/mounts for additional storage devices
        try {
            val mountsFile = File("/proc/mounts")
            if (mountsFile.exists()) {
                mountsFile.readLines().forEach { line ->
                    val parts = line.split(" ")
                    if (parts.size >= 2) {
                        val mountPoint = parts[1]
                        val fsType = if (parts.size >= 3) parts[2] else ""

                        // Look for common removable storage mount points and file systems
                        if ((mountPoint.contains("/storage/") || mountPoint.contains("/mnt/")) &&
                            !mountPoint.contains("emulated") &&
                            (fsType in listOf("vfat", "exfat", "ntfs", "ext4", "ext3", "ext2")) &&
                            mountPoint != "/storage/self"
                        ) {

                            val storageDir = File(mountPoint)
                            if (storageDir.exists() && storageDir.canRead() &&
                                addedPaths.add(storageDir.absolutePath)
                            ) {
                                storageList.add(Pair(storageDir.name, storageDir))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore errors reading /proc/mounts
        }

        // Method 3: Check common OTG/USB mount points
        val commonOtgPaths = listOf(
            "/storage/usb",
            "/storage/usbotg",
            "/storage/usb1",
            "/storage/usb2",
            "/mnt/usb",
            "/mnt/usbdisk",
            "/mnt/usb_storage"
        )

        commonOtgPaths.forEach { path ->
            val dir = File(path)
            if (dir.exists() && dir.canRead() && addedPaths.add(dir.absolutePath)) {
                storageList.add(Pair(dir.name, dir))
            }

            // Also check subdirectories
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { subDir ->
                    if (subDir.isDirectory && subDir.canRead() &&
                        addedPaths.add(subDir.absolutePath)
                    ) {
                        storageList.add(Pair(subDir.name, subDir))
                    }
                }
            }
        }

        return storageList
    }

    /**
     * Generic helper function to fetch files based on a list of MIME types.
     */
    private fun getFilesByMimeTypes(
        mimeTypes: Array<String>,
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        if (mimeTypes.isEmpty()) {
            return ArrayList()
        }

        val files = ArrayList<LocalFileHolder>()
        val contentResolver: ContentResolver = globalClass.contentResolver

        val uri: Uri = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME, // Required for SORT_BY_NAME
            MediaStore.Files.FileColumns.DATE_MODIFIED, // Required for SORT_BY_DATE
            MediaStore.Files.FileColumns.SIZE         // Required for SORT_BY_SIZE
        )

        // Build the selection clause to match any of the provided MIME types.
        val selection =
            mimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = ?" }
        val selectionArgs = mimeTypes

        // EFFICIENT SORTING: Build the SQL ORDER BY clause to handle sorting and reversal.
        val sortOrder = when (sortingPrefs?.sortMethod) {
            SORT_BY_NAME -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} ${if (sortingPrefs.reverseSorting) "DESC" else "ASC"}"
            SORT_BY_DATE -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            SORT_BY_SIZE -> "${MediaStore.Files.FileColumns.SIZE} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            val sortCol = when (sortingPrefs?.sortMethod) {
                SORT_BY_NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
                SORT_BY_SIZE -> MediaStore.Files.FileColumns.SIZE
                else -> MediaStore.Files.FileColumns.DATE_MODIFIED
            }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortCol))
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                if (sortingPrefs?.reverseSorting == true) ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                else ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, queryArgs, null)
        } catch (_: Exception) {
            val fallbackUri = uri.buildUpon().appendQueryParameter("limit", "$offset,$limit").build()
            try {
                cursor = contentResolver.query(fallbackUri, projection, selection, selectionArgs, sortOrder)
            } catch (_: Exception) {}
        }

        cursor?.use {
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (it.moveToNext()) {
                val path = it.getString(pathColumn)
                if (!path.isNullOrEmpty()) {
                    val name = it.getString(nameColumn) ?: File(path).name
                    val dateModified = it.getLong(dateColumn) * 1000L
                    val size = it.getLong(sizeColumn)
                    files.add(
                        LocalFileHolder(
                            file = File(path),
                            cachedName = name,
                            cachedIsDir = false,
                            cachedSize = size,
                            cachedLastModified = dateModified
                        )
                    )
                }
            }
        }

        return files
    }

    /**
     * Gets all document files.
     */
    fun getDocumentFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        val documentMimeTypes = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // DOCX
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // XLSX
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" // PPTX
        )
        return getFilesByMimeTypes(documentMimeTypes, sortingPrefs, limit, offset)
    }

    /**
     * Gets all archive files.
     */
    fun getArchiveFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        val archiveMimeTypes = arrayOf(
            "application/zip",
            "application/x-rar-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-7z-compressed"
        )
        return getFilesByMimeTypes(archiveMimeTypes, sortingPrefs, limit, offset)
    }

    fun getApkFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        val files = ArrayList<LocalFileHolder>()
        val contentResolver: ContentResolver = globalClass.contentResolver
        val uri: Uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.SIZE
        )
        val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.apk' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.apks' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.xapk' OR " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.apkm'"
        val selectionArgs = arrayOf("application/vnd.android.package-archive")

        val sortOrder = when (sortingPrefs?.sortMethod) {
            SORT_BY_NAME -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} ${if (sortingPrefs.reverseSorting) "DESC" else "ASC"}"
            SORT_BY_DATE -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            SORT_BY_SIZE -> "${MediaStore.Files.FileColumns.SIZE} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            val sortCol = when (sortingPrefs?.sortMethod) {
                SORT_BY_NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
                SORT_BY_SIZE -> MediaStore.Files.FileColumns.SIZE
                else -> MediaStore.Files.FileColumns.DATE_MODIFIED
            }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortCol))
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                if (sortingPrefs?.reverseSorting == true) ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                else ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, queryArgs, null)
        } catch (_: Exception) {
            val fallbackUri = uri.buildUpon().appendQueryParameter("limit", "$offset,$limit").build()
            try {
                cursor = contentResolver.query(fallbackUri, projection, selection, selectionArgs, sortOrder)
            } catch (_: Exception) {}
        }

        cursor?.use {
            val pathCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (it.moveToNext()) {
                val path = it.getString(pathCol)
                if (!path.isNullOrEmpty()) {
                    val name = it.getString(nameCol) ?: File(path).name
                    val dateModified = it.getLong(dateCol) * 1000L
                    val size = it.getLong(sizeCol)
                    files.add(
                        LocalFileHolder(
                            file = File(path),
                            cachedName = name,
                            cachedIsDir = false,
                            cachedSize = size,
                            cachedLastModified = dateModified
                        )
                    )
                }
            }
        }
        return files
    }

    /**
     * Generic helper function to fetch media files of a specific type.
     */
    private fun getMediaFiles(
        mediaType: Int,
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        val files = ArrayList<LocalFileHolder>()
        val contentResolver: ContentResolver = globalClass.contentResolver

        val uri: Uri = MediaStore.Files.getContentUri("external")

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME, // Required for SORT_BY_NAME
            MediaStore.Files.FileColumns.DATE_MODIFIED, // Required for SORT_BY_DATE
            MediaStore.Files.FileColumns.SIZE         // Required for SORT_BY_SIZE
        )

        // Filter the results to only the media type we want (images, video, etc.).
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val selectionArgs = arrayOf(mediaType.toString())

        val sortOrder = when (sortingPrefs?.sortMethod) {
            SORT_BY_NAME -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} ${if (sortingPrefs.reverseSorting) "DESC" else "ASC"}"
            SORT_BY_DATE -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            SORT_BY_SIZE -> "${MediaStore.Files.FileColumns.SIZE} ${if (sortingPrefs.reverseSorting) "ASC" else "DESC"}"
            SORT_BY_TYPE -> {
                val direction = if (sortingPrefs.reverseSorting) "DESC" else "ASC"
                """
            CASE
                WHEN INSTR(${MediaStore.Files.FileColumns.DISPLAY_NAME}, '.') = 0 THEN 1
                ELSE 0
            END,
            SUBSTR(${MediaStore.Files.FileColumns.DISPLAY_NAME}, INSTR(${MediaStore.Files.FileColumns.DISPLAY_NAME}, '.') + 1) $direction
            """.trimIndent()
            }

            else -> "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        }

        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            val sortCol = when (sortingPrefs?.sortMethod) {
                SORT_BY_NAME -> MediaStore.Files.FileColumns.DISPLAY_NAME
                SORT_BY_SIZE -> MediaStore.Files.FileColumns.SIZE
                else -> MediaStore.Files.FileColumns.DATE_MODIFIED
            }
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(sortCol))
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                if (sortingPrefs?.reverseSorting == true) ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                else ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
            )
        }

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, queryArgs, null)
        } catch (_: Exception) {
            val fallbackUri = uri.buildUpon().appendQueryParameter("limit", "$offset,$limit").build()
            try {
                cursor = contentResolver.query(fallbackUri, projection, selection, selectionArgs, sortOrder)
            } catch (_: Exception) {}
        }

        cursor?.use {
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val nameColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dateColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val sizeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

            while (it.moveToNext()) {
                val path = it.getString(pathColumn)
                if (!path.isNullOrEmpty()) {
                    val name = it.getString(nameColumn) ?: File(path).name
                    val dateModified = it.getLong(dateColumn) * 1000L
                    val size = it.getLong(sizeColumn)
                    files.add(
                        LocalFileHolder(
                            file = File(path),
                            cachedName = name,
                            cachedIsDir = false,
                            cachedSize = size,
                            cachedLastModified = dateModified
                        )
                    )
                }
            }
        }

        return files
    }

    /**
     * Gets all image files.
     */
    fun getImageFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        return getMediaFiles(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, sortingPrefs, limit, offset)
    }

    /**
     * Gets all video files.
     */
    fun getVideoFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        return getMediaFiles(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, sortingPrefs, limit, offset)
    }

    /**
     * Gets all audio files.
     */
    fun getAudioFiles(
        sortingPrefs: FileSortingPrefs?,
        limit: Int = 200,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        return getMediaFiles(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO, sortingPrefs, limit, offset)
    }

    fun getBookmarks() = globalClass.preferencesManager.bookmarks
        .map { LocalFileHolder(File(it)) } as ArrayList<LocalFileHolder>

    @Volatile
    private var cachedAllDeviceFiles: ArrayList<RecentFile>? = null
    @Volatile
    private var cachedAllDeviceKey: String = ""
    @Volatile
    private var cachedAllDeviceTimestamp: Long = 0L
    @Volatile
    private var lastMutationCounter: Long = 0L
    private const val RECENT_CACHE_TTL_MS = 20_000L

    private val recentlyMutatedFiles = ConcurrentHashMap<String, Long>()

    // Bounded Recent-files cache. Recent is driven by a recency window (see below) instead of a
    // whole-device walk, so the candidate set stays small and is safe to page over from memory.
    @Volatile
    private var cachedRecentCandidates: ArrayList<RecentFile>? = null
    @Volatile
    private var cachedRecentKey: String = ""
    @Volatile
    private var cachedRecentTimestamp: Long = 0L

    /**
     * How far back the Recent screen looks. Anything older than this is not surfaced as "recent";
     * it stays reachable through its folder or All Files. A month of activity is covered while
     * avoiding a full filesystem scan.
     */
    private const val DEFAULT_RECENT_WINDOW_DAYS = 30

    /**
     * Hard upper bound on the number of MediaStore rows read for Recent, newest first. This bounds
     * memory only; because the query is sorted newest-first it never drops the most recent files.
     */
    private const val MAX_RECENT_CANDIDATES = 3000

    fun invalidateRecentFilesCache() {
        cachedAllDeviceTimestamp = 0L
        cachedRecentTimestamp = 0L
        lastMutationCounter++
    }

    fun notifyFileMutated(file: File) {
        try {
            if (file.exists() && file.isFile) {
                recentlyMutatedFiles[file.absolutePath] = System.currentTimeMillis()
            } else if (file.exists() && file.isDirectory) {
                file.walkTopDown().maxDepth(4).forEach { child ->
                    if (child.isFile) {
                        recentlyMutatedFiles[child.absolutePath] = System.currentTimeMillis()
                    }
                }
            }
            invalidateRecentFilesCache()
            MediaScannerConnection.scanFile(
                globalClass,
                arrayOf(file.absolutePath),
                null,
                null
            )
        } catch (_: Exception) {}
    }

    private fun shouldIncludePath(
        path: String,
        name: String,
        showHiddenFiles: Boolean,
        excludeHiddenPaths: Boolean,
        hideTempAndDb: Boolean,
        excludedPaths: Set<String>,
        recycleBinPath: String
    ): Boolean = !RecentFilePolicy.isExcludedPath(
        path = path,
        name = name,
        showHiddenFiles = showHiddenFiles,
        excludeHiddenPaths = excludeHiddenPaths,
        hideTempAndDb = hideTempAndDb,
        excludedPaths = excludedPaths,
        recycleBinPath = recycleBinPath
    )

    @Synchronized
    private fun collectAllDeviceFilesSorted(): ArrayList<RecentFile> {
        val now = System.currentTimeMillis()
        val prefs = globalClass.preferencesManager
        val showHiddenFiles = prefs.showHiddenFiles
        val excludeHiddenPaths = prefs.removeHiddenPathsFromRecentFiles
        val hideTempAndDb = prefs.hideTempAndDbFilesFromRecentFiles
        val excludedPaths = prefs.excludedPathsFromRecentFiles
        val recycleBinPath = runCatching { globalClass.recycleBinDir.file.absolutePath }.getOrDefault("")

        val cacheKey = "$showHiddenFiles:$excludeHiddenPaths:$hideTempAndDb:${excludedPaths.joinToString()}:$lastMutationCounter"
        val existingCache = cachedAllDeviceFiles
        if (existingCache != null && cachedAllDeviceKey == cacheKey && (now - cachedAllDeviceTimestamp < RECENT_CACHE_TTL_MS)) {
            return existingCache
        }

        val fileMap = HashMap<String, RecentFile>(4096)

        fun addOrUpdateFile(file: File, name: String, size: Long, timestampMs: Long) {
            val path = file.absolutePath
            if (!shouldIncludePath(path, name, showHiddenFiles, excludeHiddenPaths, hideTempAndDb, excludedPaths, recycleBinPath)) {
                return
            }
            val existing = fileMap[path]
            if (existing == null || timestampMs > existing.lastModified) {
                val holder = LocalFileHolder(
                    file = file,
                    cachedName = name,
                    cachedIsDir = false,
                    cachedSize = size,
                    cachedLastModified = timestampMs
                )
                fileMap[path] = RecentFile(
                    name = name,
                    path = path,
                    lastModified = timestampMs,
                    file = holder
                )
            }
        }

        // 1. Always include recently created/modified files tracked in-app
        val staleMutated = mutableListOf<String>()
        for ((path, recordedTime) in recentlyMutatedFiles) {
            val f = File(path)
            if (f.exists() && f.isFile) {
                val mtime = maxOf(f.lastModified(), recordedTime)
                addOrUpdateFile(f, f.name, f.length(), mtime)
            } else if (!f.exists()) {
                staleMutated.add(path)
            }
        }
        staleMutated.forEach { recentlyMutatedFiles.remove(it) }

        // 2. Walk storage roots directly on the filesystem so newly created files in Download
        // or any internal/external directory are always found regardless of MediaStore lag.
        val roots = mutableListOf<File>()
        val primaryRoot = Environment.getExternalStorageDirectory()
        if (primaryRoot.exists()) roots.add(primaryRoot)
        for ((_, extDir) in getExternalStorageDirectories(globalClass)) {
            if (extDir.exists() && roots.none { it.absolutePath == extDir.absolutePath }) {
                roots.add(extDir)
            }
        }

        fun walkDir(dir: File, depth: Int) {
            if (depth > 14) return
            val dirPath = dir.absolutePath
            val dirName = dir.name
            if (dirName in RecentFilePolicy.SKIPPED_SYSTEM_DIR_NAMES) return
            if (recycleBinPath.isNotEmpty() && dirPath.startsWith(recycleBinPath)) return
            if ((excludeHiddenPaths || !showHiddenFiles) && dirName.startsWith(".")) return
            if (hideTempAndDb && (dirPath.endsWith("/Android/data") || dirPath.endsWith("/Android/obb"))) return
            for (excluded in excludedPaths) {
                if (excluded.isNotEmpty() && dirPath.startsWith(excluded)) return
            }

            val children = dir.listFiles() ?: return
            for (child in children) {
                val childName = child.name
                if ((excludeHiddenPaths || !showHiddenFiles) && childName.startsWith(".")) continue
                if (child.isDirectory) {
                    walkDir(child, depth + 1)
                } else if (child.isFile) {
                    val mtime = child.lastModified()
                    addOrUpdateFile(child, childName, child.length(), mtime)
                }
            }
        }

        for (root in roots) {
            try {
                walkDir(root, 0)
            } catch (_: Exception) {}
        }

        // 3. Merge MediaStore.Files (using max of DATE_ADDED and DATE_MODIFIED for creation date accuracy)
        try {
            val uri: Uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE
            )
            globalClass.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val colPath = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val colMod = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val colAdd = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                val colName = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val colSize = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)

                while (cursor.moveToNext()) {
                    val filePath = cursor.getString(colPath) ?: continue
                    val f = File(filePath)
                    if (!f.exists() || f.isDirectory) continue
                    val name = cursor.getString(colName) ?: f.name
                    val modSec = cursor.getLong(colMod)
                    val addSec = if (colAdd >= 0) cursor.getLong(colAdd) else 0L
                    val bestSec = maxOf(modSec, addSec)
                    val timestampMs = maxOf(
                        RecentFilePolicy.mediaStoreSecondsToMillis(bestSec),
                        f.lastModified()
                    )
                    val size = if (colSize >= 0) cursor.getLong(colSize).takeIf { it > 0 } ?: f.length() else f.length()
                    addOrUpdateFile(f, name, size, timestampMs)
                }
            }
        } catch (_: Exception) {}

        // Sort from newer to older
        val sorted = ArrayList(fileMap.values)
        sorted.sortWith { a, b ->
            b.file.lastModified.compareTo(a.file.lastModified)
        }

        cachedAllDeviceFiles = sorted
        cachedAllDeviceKey = cacheKey
        cachedAllDeviceTimestamp = System.currentTimeMillis()
        return sorted
    }

    private fun recentWindowMs(recentHours: Int): Long {
        val hours = if (recentHours > 0) recentHours else DEFAULT_RECENT_WINDOW_DAYS * 24
        return hours.toLong() * 60L * 60L * 1000L
    }

    /**
     * The MediaStore selection used for the Recent list.
     *
     * It is shared by the list build and the lightweight change-detection query so that both
     * observe exactly the same set of files.
     */
    private data class RecentMediaStoreQuery(
        val selection: String,
        val selectionArgs: Array<String>,
        val sortOrder: String
    )

    /**
     * Builds the MediaStore query that selects files modified inside the recency window, applying
     * the same exclusion rules as [RecentFilePolicy].
     */
    private fun buildRecentMediaStoreQuery(
        windowMs: Long,
        showHiddenFiles: Boolean,
        excludeHiddenPaths: Boolean,
        hideTempAndDb: Boolean,
        excludedPaths: Set<String>,
        recycleBinPath: String
    ): RecentMediaStoreQuery {
        val sinceSec = (System.currentTimeMillis() - windowMs) / 1000L
        val selectionClauses = mutableListOf("${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?")
        val selectionArgsList = mutableListOf(sinceSec.toString())

        if (!showHiddenFiles) {
            selectionClauses.add("${MediaStore.Files.FileColumns.DISPLAY_NAME} NOT LIKE '.%'")
        }
        if (excludeHiddenPaths) {
            selectionClauses.add("${MediaStore.Files.FileColumns.DATA} NOT LIKE '%/.%'")
        }
        if (hideTempAndDb) {
            listOf(
                "%.tmp", "%.temp", "%.bak", "%.log",
                "%.crdownload", "%.part",
                "%-shm", "%-wal", "%-journal",
                "%.db-shm", "%.db-wal", "%.db-journal",
                "%/Android/data/%", "%/Android/obb/%"
            ).forEach { pattern ->
                selectionClauses.add("${MediaStore.Files.FileColumns.DATA} NOT LIKE ?")
                selectionArgsList.add(pattern)
            }
        }
        if (recycleBinPath.isNotEmpty()) {
            selectionClauses.add("${MediaStore.Files.FileColumns.DATA} NOT LIKE ?")
            selectionArgsList.add("$recycleBinPath%")
        }
        excludedPaths.forEach { excludedPath ->
            if (excludedPath.isNotEmpty()) {
                selectionClauses.add("${MediaStore.Files.FileColumns.DATA} NOT LIKE ?")
                selectionArgsList.add("$excludedPath%")
            }
        }

        return RecentMediaStoreQuery(
            selection = selectionClauses.joinToString(" AND "),
            selectionArgs = selectionArgsList.toTypedArray(),
            sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )
    }

    /**
     * Modification time (in seconds) of the newest file that matches the Recent query.
     *
     * Used as a cheap change-detection signal for the Recent screen: it reads a single row instead
     * of the whole candidate list. Returns `0L` when nothing matches or the query fails, which
     * simply means "no change detected".
     */
    private fun recentNewestModifiedSeconds(): Long {
        val prefs = globalClass.preferencesManager
        val recycleBinPath = runCatching { globalClass.recycleBinDir.file.absolutePath }.getOrDefault("")
        val query = buildRecentMediaStoreQuery(
            windowMs = recentWindowMs(-1),
            showHiddenFiles = prefs.showHiddenFiles,
            excludeHiddenPaths = prefs.removeHiddenPathsFromRecentFiles,
            hideTempAndDb = prefs.hideTempAndDbFilesFromRecentFiles,
            excludedPaths = prefs.excludedPathsFromRecentFiles,
            recycleBinPath = recycleBinPath
        )
        val uri: Uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED)

        var newest = 0L
        try {
            val queryArgs = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.selectionArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, query.sortOrder)
            }
            globalClass.contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    newest = cursor.getLong(0)
                }
            }
        } catch (_: Exception) {
            try {
                val queryUri = uri.buildUpon().appendQueryParameter("limit", "1").build()
                globalClass.contentResolver.query(
                    queryUri, projection, query.selection, query.selectionArgs, query.sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        newest = cursor.getLong(0)
                    }
                }
            } catch (_: Exception) {}
        }
        return newest
    }

    /**
     * Builds the bounded, newest-first list of "recent" files.
     *
     * Recency is based on last-modified time, which is the timestamp the app can obtain reliably
     * for every provider. The app does not track a separate "last opened" time, so modification
     * time is used consistently and is never presented as an open time.
     *
     * Only two bounded sources feed the list:
     *  1. files the user created/renamed/copied/edited inside the app ([recentlyMutatedFiles]),
     *     which are immediately relevant even before MediaStore catches up, and
     *  2. a MediaStore query restricted to the recency window, sorted newest-first and capped.
     *
     * This replaces the previous whole-device walk that could enumerate tens of thousands of files.
     */
    @Synchronized
    private fun collectRecentFilesBounded(windowMs: Long): ArrayList<RecentFile> {
        val now = System.currentTimeMillis()
        val prefs = globalClass.preferencesManager
        val showHiddenFiles = prefs.showHiddenFiles
        val excludeHiddenPaths = prefs.removeHiddenPathsFromRecentFiles
        val hideTempAndDb = prefs.hideTempAndDbFilesFromRecentFiles
        val excludedPaths = prefs.excludedPathsFromRecentFiles
        val recycleBinPath = runCatching { globalClass.recycleBinDir.file.absolutePath }.getOrDefault("")

        val cacheKey = "$windowMs:$showHiddenFiles:$excludeHiddenPaths:$hideTempAndDb:${excludedPaths.joinToString()}:$lastMutationCounter"
        cachedRecentCandidates?.let { cached ->
            if (cachedRecentKey == cacheKey && (now - cachedRecentTimestamp < RECENT_CACHE_TTL_MS)) {
                return cached
            }
        }

        val byPath = HashMap<String, RecentFile>(512)

        fun addCandidate(file: File, name: String, size: Long, lastModifiedMs: Long) {
            val path = file.absolutePath
            if (RecentFilePolicy.isExcludedPath(
                    path, name, showHiddenFiles, excludeHiddenPaths, hideTempAndDb, excludedPaths, recycleBinPath
                )
            ) {
                return
            }
            val existing = byPath[path]
            if (existing == null || lastModifiedMs > existing.lastModified) {
                byPath[path] = RecentFile(
                    name = name,
                    path = path,
                    lastModified = lastModifiedMs,
                    file = LocalFileHolder(
                        file = file,
                        cachedName = name,
                        cachedIsDir = false,
                        cachedSize = size,
                        cachedLastModified = lastModifiedMs
                    )
                )
            }
        }

        // 1. Files created/renamed/copied/edited through this app are always recent.
        val staleMutated = mutableListOf<String>()
        for ((path, recordedTime) in recentlyMutatedFiles) {
            val f = File(path)
            if (f.exists() && f.isFile) {
                addCandidate(f, f.name, f.length(), maxOf(f.lastModified(), recordedTime))
            } else if (!f.exists()) {
                staleMutated.add(path)
            }
        }
        staleMutated.forEach { recentlyMutatedFiles.remove(it) }

        // 2. MediaStore, restricted to the recency window and sorted newest-first.
        val uri: Uri = MediaStore.Files.getContentUri("external")
        val recentQuery = buildRecentMediaStoreQuery(
            windowMs = windowMs,
            showHiddenFiles = showHiddenFiles,
            excludeHiddenPaths = excludeHiddenPaths,
            hideTempAndDb = hideTempAndDb,
            excludedPaths = excludedPaths,
            recycleBinPath = recycleBinPath
        )
        val selection = recentQuery.selection
        val selectionArgs = recentQuery.selectionArgs
        val sortOrder = recentQuery.sortOrder

        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE
        )
        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_RECENT_CANDIDATES)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Files.FileColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        }

        var cursor: Cursor? = null
        try {
            cursor = globalClass.contentResolver.query(uri, projection, queryArgs, null)
        } catch (_: Exception) {
            val queryUri = uri.buildUpon().appendQueryParameter("limit", MAX_RECENT_CANDIDATES.toString()).build()
            try {
                cursor = globalClass.contentResolver.query(queryUri, projection, selection, selectionArgs, sortOrder)
            } catch (_: Exception) {}
        }

        cursor?.use {
            val columnIndexPath = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val columnLastModified = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val columnName = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val columnSize = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)

            while (it.moveToNext()) {
                val filePath = it.getString(columnIndexPath) ?: continue
                val name = it.getString(columnName) ?: continue
                val f = File(filePath)
                if (!f.exists() || f.isDirectory) continue
                val size = if (columnSize >= 0) it.getLong(columnSize).takeIf { s -> s > 0 } ?: f.length() else f.length()
                val mtimeMs = RecentFilePolicy.mediaStoreSecondsToMillis(it.getLong(columnLastModified))
                addCandidate(f, name, size, maxOf(mtimeMs, f.lastModified()))
            }
        }

        val ordered = RecentFilePolicy.sortNewestFirst(byPath.values) { it.lastModified }
        val bounded = if (ordered.size > MAX_RECENT_CANDIDATES) {
            ArrayList(ordered.subList(0, MAX_RECENT_CANDIDATES))
        } else {
            ArrayList(ordered)
        }

        cachedRecentCandidates = bounded
        cachedRecentKey = cacheKey
        cachedRecentTimestamp = System.currentTimeMillis()
        return bounded
    }

    fun getRawRecentFiles(
        recentHours: Int = -1,
        limit: Int = 100
    ): ArrayList<RecentFile> {
        val candidates = collectRecentFilesBounded(recentWindowMs(recentHours))
        val effectiveLimit = if (limit <= 0) candidates.size else minOf(limit, candidates.size)
        return ArrayList(candidates.subList(0, effectiveLimit))
    }

    private fun applySorting(
        holders: List<LocalFileHolder>,
        sortingPrefs: FileSortingPrefs?
    ): List<LocalFileHolder> {
        if (sortingPrefs == null) return holders
        return when (sortingPrefs.sortMethod) {
            SORT_BY_NAME -> if (sortingPrefs.reverseSorting) {
                holders.sortedWith { a, b -> b.displayName.lowercase().compareTo(a.displayName.lowercase()) }
            } else {
                holders.sortedWith { a, b -> a.displayName.lowercase().compareTo(b.displayName.lowercase()) }
            }
            SORT_BY_SIZE -> if (sortingPrefs.reverseSorting) {
                holders.sortedByDescending { it.size }
            } else {
                holders.sortedBy { it.size }
            }
            SORT_BY_DATE -> if (sortingPrefs.reverseSorting) {
                holders.sortedBy { it.lastModified }
            } else {
                // Already sorted descending (newer to older) by default
                holders
            }
            else -> holders
        }
    }

    fun getRecentFiles(
        recentHours: Int = -1,
        limit: Int = 250,
        offset: Int = 0,
        sortingPrefs: FileSortingPrefs? = null
    ): ArrayList<LocalFileHolder> {
        // Recent is bounded by a recency window instead of a whole-device walk: only files modified
        // inside the window (plus files mutated through the app) are considered, newest first.
        val candidates = collectRecentFilesBounded(recentWindowMs(recentHours))
        val configuredMax = globalClass.preferencesManager.recentFilesLimit
        val capped = if (configuredMax > 0) candidates.take(configuredMax) else candidates
        val holders = applySorting(capped.map { it.file }, sortingPrefs)
        if (offset >= holders.size) return arrayListOf()
        val end = minOf(offset + limit, holders.size)
        return ArrayList(holders.subList(offset, end))
    }

    fun getAllFiles(
        sortingPrefs: FileSortingPrefs? = null,
        limit: Int = 250,
        offset: Int = 0
    ): ArrayList<LocalFileHolder> {
        val allSorted = collectAllDeviceFilesSorted()
        val holders = applySorting(allSorted.map { it.file }, sortingPrefs)
        if (offset >= holders.size) return arrayListOf()
        val end = minOf(offset + limit, holders.size)
        return ArrayList(holders.subList(offset, end))
    }

    fun getCategoryQuickCheck(type: Int): Pair<Int, Long> {
        if (type == VirtualFileHolder.ALL_FILES) {
            return Pair(lastMutationCounter.toInt(), cachedAllDeviceTimestamp)
        }
        if (type == VirtualFileHolder.RECENT) {
            // Recent is built from the bounded MediaStore query, not from the all-device walk, so
            // its change detection has to observe that same query. The count is the mutation
            // counter (so in-app edits always refresh) and the timestamp is the newest matching
            // file's modification time.
            return Pair(lastMutationCounter.toInt(), recentNewestModifiedSeconds())
        }
        val uri: Uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val selection = when (type) {
            VirtualFileHolder.IMAGE -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
            VirtualFileHolder.VIDEO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"
            VirtualFileHolder.AUDIO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}"
            VirtualFileHolder.DOCUMENT -> {
                val documentMimeTypes = arrayOf(
                    "application/pdf", "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                )
                documentMimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = '$it'" }
            }
            VirtualFileHolder.ARCHIVE -> {
                val archiveMimeTypes = arrayOf(
                    "application/zip", "application/x-rar-compressed",
                    "application/x-tar", "application/gzip", "application/x-7z-compressed"
                )
                archiveMimeTypes.joinToString(" OR ") { "${MediaStore.Files.FileColumns.MIME_TYPE} = '$it'" }
            }
            VirtualFileHolder.APK -> "${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/vnd.android.package-archive' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.apk'"
            else -> return Pair(0, 0L)
        }

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        var count = 0
        var maxMtime = 0L

        try {
            val queryArgs = Bundle().apply {
                putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
            }
            globalClass.contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                count = cursor.count
                if (cursor.moveToFirst()) {
                    maxMtime = cursor.getLong(0)
                }
            }
        } catch (_: Exception) {
            try {
                val queryUri = uri.buildUpon().appendQueryParameter("limit", "1").build()
                globalClass.contentResolver.query(queryUri, projection, selection, null, sortOrder)?.use { cursor ->
                    count = cursor.count
                    if (cursor.moveToFirst()) {
                        maxMtime = cursor.getLong(0)
                    }
                }
            } catch (_: Exception) {}
        }
        return Pair(count, maxMtime)
    }

    fun getSearchResult(): ArrayList<ContentHolder> {
        return ArrayList(globalClass.searchManager.searchResults.map { it.file }.distinctBy { it.uniquePath })
    }
}