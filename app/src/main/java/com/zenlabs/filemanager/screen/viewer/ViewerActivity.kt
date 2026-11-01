package com.zenlabs.filemanager.screen.viewer

import android.net.Uri
import android.provider.MediaStore
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import com.zenlabs.filemanager.App.Companion.globalClass
import com.zenlabs.filemanager.base.BaseActivity
import com.zenlabs.filemanager.common.emptyString
import com.zenlabs.filemanager.common.randomString
import com.zenlabs.filemanager.screen.main.tab.home.HomeTab

abstract class ViewerActivity : BaseActivity() {
    private var uri: Uri? = null
    private var uid: String = emptyString
    private var currentInstance: ViewerInstance? = null

    abstract fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance
    abstract fun onReady(instance: ViewerInstance)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        if (intent.data == null) {
            finish()
            return
        }

        uri = intent.data

        if (savedInstanceState != null) {
            uid = savedInstanceState.getString("uid", String.randomString(12))
        }

        if (uid.isEmpty()) {
            uid = String.randomString(12)
        }

        val instance = globalClass.viewersManager.instances.find { it.id == uid }

        if (instance == null) {
            globalClass.viewersManager.instances.add(
                onCreateNewInstance(uri!!, uid).also { currentInstance = it }
            )
        }

        checkPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("uid", uid)
    }

    override fun onDestroy() {
        super.onDestroy()
        currentInstance?.onClose()
        globalClass.viewersManager.instances.remove(currentInstance)
    }

    /**
     * Removes stale MediaStore and in-memory Recent Files references after a viewer deletion.
     */
    fun onFileDeleted(path: String?) {
        if (!path.isNullOrEmpty()) {
            runCatching {
                contentResolver.delete(
                    MediaStore.Files.getContentUri("external"),
                    "${MediaStore.Files.FileColumns.DATA} = ?",
                    arrayOf(path)
                )
            }
            globalClass.mainActivityManager.state.value.tabs
                .filterIsInstance<HomeTab>()
                .forEach { it.removeRecentFile(path) }
        }
    }

    override fun onPermissionGranted() {
        onReady(currentInstance!!)
    }
}