package com.zenlabs.filemanager.screen.main.tab.files.holder

import android.graphics.Bitmap
import com.zenlabs.filemanager.common.randomString

data class OpenWithActivityHolder(
    val id: String = String.randomString(8),
    val label: String,
    val name: String,
    val packageName: String,
    val icon: Bitmap?
)