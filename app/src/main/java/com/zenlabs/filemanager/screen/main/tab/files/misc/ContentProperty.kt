package com.zenlabs.filemanager.screen.main.tab.files.misc

data class ContentProperty(
    val label: String,
    val copiable: Boolean = true,
    val updateValue: () -> String
)