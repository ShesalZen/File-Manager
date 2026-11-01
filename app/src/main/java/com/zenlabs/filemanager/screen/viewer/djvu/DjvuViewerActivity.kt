package com.zenlabs.filemanager.screen.viewer.djvu

import android.net.Uri
import androidx.activity.compose.setContent
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.common.ui.SafeSurface
import com.zenlabs.filemanager.screen.viewer.ViewerActivity
import com.zenlabs.filemanager.screen.viewer.ViewerInstance
import com.zenlabs.filemanager.screen.viewer.djvu.ui.DjvuViewerScreen
import com.zenlabs.filemanager.theme.FileExplorerTheme

class DjvuViewerActivity : ViewerActivity() {

    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        val extraPath = intent.getStringExtra("extra_file_path")
        return DjvuViewerInstance(uri, uid, extraPath)
    }

    override fun onReady(instance: ViewerInstance) {
        if (instance !is DjvuViewerInstance) {
            globalClass.showMsg("Invalid DJVU file")
            finish()
            return
        }

        setContent {
            FileExplorerTheme {
                SafeSurface(enableStatusBarsPadding = false) {
                    DjvuViewerScreen(
                        instance = instance,
                        onBackPress = { onBackPressedDispatcher.onBackPressed() }
                    )
                }
            }
        }
    }
}
