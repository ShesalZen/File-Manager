package com.zenlabs.filemanager.screen.viewer.comic

import android.net.Uri
import androidx.activity.compose.setContent
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.common.ui.SafeSurface
import com.zenlabs.filemanager.screen.viewer.ViewerActivity
import com.zenlabs.filemanager.screen.viewer.ViewerInstance
import com.zenlabs.filemanager.screen.viewer.comic.ui.ComicViewerScreen
import com.zenlabs.filemanager.theme.FileExplorerTheme

class ComicViewerActivity : ViewerActivity() {

    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        val extraPath = intent.getStringExtra("extra_file_path")
        return ComicViewerInstance(uri, uid, extraPath)
    }

    override fun onReady(instance: ViewerInstance) {
        if (instance !is ComicViewerInstance) {
            globalClass.showMsg("Invalid comic file")
            finish()
            return
        }

        setContent {
            FileExplorerTheme {
                SafeSurface(enableStatusBarsPadding = false) {
                    ComicViewerScreen(
                        instance = instance,
                        onBackPress = { onBackPressedDispatcher.onBackPressed() }
                    )
                }
            }
        }
    }
}
