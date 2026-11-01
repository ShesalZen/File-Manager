package com.zenlabs.filemanager.screen.main.tab.home.holder

import androidx.compose.ui.graphics.vector.ImageVector

data class HomeCategory(
    val name: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)