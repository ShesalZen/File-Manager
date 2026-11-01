package com.zenlabs.filemanager.screen.main.model

import com.zenlabs.filemanager.screen.main.tab.Tab
import java.util.UUID

data class ClosedTabEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String = "",
    val originalIndex: Int = -1,
    val timestamp: Long = System.currentTimeMillis(),
    val createTab: () -> Tab
)
