package com.zenlabs.filemanager.screen.logs

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zenlabs.filemanager.base.BaseActivity
import com.zenlabs.filemanager.screen.logs.ui.LogsScreen
import com.zenlabs.filemanager.theme.FileExplorerTheme

class LogsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkPermissions()
    }

    override fun onPermissionGranted() {
        setContent {
            FileExplorerTheme {
                LogsScreen { onBackPressedDispatcher.onBackPressed() }
            }
        }
    }
}