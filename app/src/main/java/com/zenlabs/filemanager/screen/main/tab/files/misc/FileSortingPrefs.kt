package com.zenlabs.filemanager.screen.main.tab.files.misc

import com.zenlabs.filemanager.screen.main.tab.files.misc.SortingMethod.SORT_BY_NAME

data class FileSortingPrefs(
    val sortMethod: Int = SORT_BY_NAME,
    val showFoldersFirst: Boolean = true,
    val reverseSorting: Boolean = false,
    val applyForThisFileOnly: Boolean = true
)