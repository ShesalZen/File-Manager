package com.zenlabs.filemanager.screen.main.tab.files.misc

data class DefaultOpeningMethods(
    val openingMethods: List<OpeningMethod> = listOf()
)

data class OpeningMethod(
    val extension: String,
    val packageName: String,
    val className: String
)