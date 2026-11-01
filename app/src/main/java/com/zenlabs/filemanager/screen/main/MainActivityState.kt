package com.zenlabs.filemanager.screen.main

import androidx.compose.foundation.lazy.LazyListState
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.R
import com.zenlabs.filemanager.common.emptyString
import com.zenlabs.filemanager.screen.main.tab.Tab
import com.zenlabs.filemanager.screen.main.tab.files.holder.StorageDevice
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderDescriptor

data class MainActivityState(
    val title: String = globalClass.getString(R.string.main_activity_title),
    val subtitle: String = emptyString,
    val showAppInfoDialog: Boolean = false,
    val showJumpToPathDialog: Boolean = false,
    val showSaveEditorFilesDialog: Boolean = false,
    val showStartupTabsDialog: Boolean = false,
    val isSavingFiles: Boolean = false,
    val selectedTabIndex: Int = 0,
    val storageDevices: List<StorageDevice> = emptyList(),
    val providerDescriptors: List<ProviderDescriptor> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val tabLayoutState: LazyListState = LazyListState(),
    val hasNewUpdate: Boolean = false
)