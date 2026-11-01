package com.zenlabs.filemanager.common.ui

import androidx.compose.runtime.Composable

@Composable
fun Isolate(content: @Composable () -> Unit) {
    content()
}