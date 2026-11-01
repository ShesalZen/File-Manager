package com.zenlabs.filemanager.screen.viewer.epub

import android.net.Uri
import androidx.activity.compose.setContent
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.common.ui.SafeSurface
import com.zenlabs.filemanager.screen.viewer.ViewerActivity
import com.zenlabs.filemanager.screen.viewer.ViewerInstance
import com.zenlabs.filemanager.screen.viewer.epub.ui.EpubViewerScreen
import com.zenlabs.filemanager.theme.FileExplorerTheme

class EpubViewerActivity : ViewerActivity() {

    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        val extraPath = intent.getStringExtra("extra_file_path")
        return EpubViewerInstance(uri, uid, extraPath)
    }

    override fun onReady(instance: ViewerInstance) {
        if (instance !is EpubViewerInstance) {
            globalClass.showMsg("Invalid EPUB file")
            finish()
            return
        }

        setContent {
            FileExplorerTheme {
                SafeSurface(enableStatusBarsPadding = false) {
                    EpubViewerScreen(
                        instance = instance,
                        onBackPress = { onBackPressedDispatcher.onBackPressed() }
                    )
                }
            }
        }
    }
}
