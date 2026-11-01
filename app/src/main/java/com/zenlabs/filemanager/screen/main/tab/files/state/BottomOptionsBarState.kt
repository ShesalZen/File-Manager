package com.zenlabs.filemanager.screen.main.tab.files.state

data class BottomOptionsBarState(
    val showMoreOptionsButton: Boolean = false,
    val showEmptyRecycleBinButton: Boolean = false,
    val showRestoreButton: Boolean = false,
    val showCreateNewContentButton: Boolean = true,
    val showQuickOptions: Boolean = false
)