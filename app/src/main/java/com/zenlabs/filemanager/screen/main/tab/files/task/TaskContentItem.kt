package com.zenlabs.filemanager.screen.main.tab.files.task

import com.zenlabs.filemanager.screen.main.tab.files.holder.ContentHolder

data class TaskContentItem(
    val content: ContentHolder,
    var relativePath: String,
    var status: TaskContentStatus,
    val rootFolderName: String? = null
)
