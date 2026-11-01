package com.zenlabs.filemanager.screen.main.tab.home.holder

import com.zenlabs.filemanager.screen.main.tab.files.holder.LocalFileHolder
import java.io.File

data class RecentFile(
    val name: String,
    val path: String,
    val lastModified: Long,
    val file: LocalFileHolder = LocalFileHolder(File(path))
)