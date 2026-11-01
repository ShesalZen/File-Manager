package com.zenlabs.filemanager.screen.viewer.audio.model

import android.graphics.Bitmap
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.R

data class AudioMetadata(
    val title: String = globalClass.getString(R.string.unknown_title),
    val artist: String = globalClass.getString(R.string.unknown_artist),
    val album: String = globalClass.getString(R.string.unknown_album),
    val duration: Long = 0L,
    val albumArt: Bitmap? = null
)