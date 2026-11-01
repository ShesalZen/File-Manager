package com.zenlabs.filemanager.screen.main.tab.files.misc

data class RecentOpenWithApps(
    val history: List<RecentOpenWithEntry> = listOf()
)

data class RecentOpenWithEntry(
    val extension: String,
    val packageName: String,
    val className: String,
    val timestamp: Long
)
