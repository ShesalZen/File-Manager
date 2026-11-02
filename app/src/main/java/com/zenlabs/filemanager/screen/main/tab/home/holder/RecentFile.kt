package com.zenlabs.filemanager.screen.main.tab.home.holder

import com.zenlabs.filemanager.screen.main.tab.files.holder.LocalFileHolder
import java.io.File

/**
 * A file surfaced by the Recent screen.
 *
 * [lastModified] is the last-modified time in **milliseconds** since the epoch, matching
 * [LocalFileHolder.lastModified] and `File.lastModified()`. The app has no "last opened" tracking,
 * so this is always a modification time and must not be presented as an open time.
 */
data class RecentFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val file: LocalFileHolder = LocalFileHolder(File(path))
)