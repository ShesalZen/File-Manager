package com.zenlabs.filemanager.screen.viewer.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.zenlabs.filemanager.common.showMsg
import com.zenlabs.filemanager.common.ui.SafeSurface
import com.zenlabs.filemanager.screen.viewer.ViewerActivity
import com.zenlabs.filemanager.screen.viewer.ViewerInstance
import com.zenlabs.filemanager.screen.viewer.image.ui.ImageEditorScreen
import com.zenlabs.filemanager.theme.FileExplorerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ImageEditorActivity"

class ImageEditorActivity : ViewerActivity() {

    override fun onCreateNewInstance(uri: Uri, uid: String): ViewerInstance {
        return ImageViewerInstance(uri, uid)
    }

    override fun onReady(instance: ViewerInstance) {
        val uri = instance.uri
        val extraPath = intent.getStringExtra("extra_file_path")
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { loadBitmapFromUri(this@ImageEditorActivity, uri) }
            if (bitmap == null) {
                showMsg("Failed to load image for editing.")
                finish()
                return@launch
            }

            setContent {
            FileExplorerTheme {
                SafeSurface(enableStatusBarsPadding = false) {
                    ImageEditorScreen(
                        originalBitmap = bitmap,
                        onSave = { editedBitmap, overwrite, filename ->
                            // Run save on IO dispatcher to avoid blocking the UI thread
                            lifecycleScope.launch {
                                val (success, errorMsg) = withContext(Dispatchers.IO) {
                                    try {
                                        if (overwrite) {
                                            // Prefer real file path; fallback to ContentResolver
                                            if (extraPath != null) {
                                                saveBitmapToFile(editedBitmap, extraPath)
                                            } else {
                                                saveBitmapToUri(this@ImageEditorActivity, editedBitmap, uri)
                                            }
                                        } else {
                                            saveBitmapAsCopy(this@ImageEditorActivity, editedBitmap, uri, extraPath, filename)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Save failed", e)
                                        Pair(false, e.message ?: "Unknown error")
                                    }
                                }
                                if (success) {
                                    showMsg(if (overwrite) "Changes saved!" else "Saved as \"$filename\"!")
                                    finish()
                                } else {
                                    showMsg("Failed to save: $errorMsg")
                                }
                            }
                        },
                        onCancel = { finish() }
                    )
                }
            }
            }
        }
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap", e)
            null
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        val runtime = Runtime.getRuntime()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val availableHeap = (runtime.maxMemory() - usedHeap).coerceAtLeast(1L)
        val heapPixelBudget = (availableHeap / 4L * 0.25).toLong().coerceAtLeast(1L)
        val maxPixels = minOf(4096L * 4096L, heapPixelBudget)
        var sample = 1
        while (
            sample < 1024 && (
                ((width + sample - 1L) / sample) * ((height + sample - 1L) / sample) > maxPixels ||
                (width + sample - 1L) / sample > 4096L ||
                (height + sample - 1L) / sample > 4096L
            )
        ) {
            sample *= 2
        }
        return sample
    }

    /** Save directly to the real filesystem path — most reliable method. */
    private fun saveBitmapToFile(bitmap: Bitmap, path: String): Pair<Boolean, String> {
        return try {
            // Normalize internal /storage_root alias to real path
            val realPath = normalizeStoragePath(path)
            val file = File(realPath)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
            }
            val format = when (file.extension.lowercase()) {
                "png"  -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP
                else   -> Bitmap.CompressFormat.JPEG
            }
            val compressed = FileOutputStream(file).use { out -> bitmap.compress(format, 95, out) }
            if (!compressed) {
                Log.e(TAG, "bitmap.compress() returned false for $realPath")
                return Pair(false, "Compression failed")
            }
            MediaScannerConnection.scanFile(this, arrayOf(realPath), null, null)
            Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToFile failed for $path", e)
            Pair(false, e.message ?: "IO error")
        }
    }

    /** Strip /storage_root prefix used internally by the app for Shizuku/root paths. */
    private fun normalizeStoragePath(path: String): String {
        return when {
            path.startsWith("/storage_root/storage") -> path.removePrefix("/storage_root")
            path.startsWith("/storage_root")         -> path.removePrefix("/storage_root")
            else -> path
        }
    }

    /** Fallback: save via ContentResolver (may fail with some URI types). */
    private fun saveBitmapToUri(context: Context, bitmap: Bitmap, uri: Uri): Pair<Boolean, String> {
        return try {
            val type = context.contentResolver.getType(uri) ?: "image/jpeg"
            val format = when {
                type.contains("png")  -> Bitmap.CompressFormat.PNG
                type.contains("webp") -> Bitmap.CompressFormat.WEBP
                else                  -> Bitmap.CompressFormat.JPEG
            }
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: return Pair(false, "Could not open output stream for URI")
            val compressed = out.use { bitmap.compress(format, 95, it) }
            if (!compressed) Pair(false, "Compression returned false") else Pair(true, "")
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToUri failed", e)
            Pair(false, e.message ?: "URI write error")
        }
    }

    private fun saveBitmapAsCopy(
        context: Context,
        bitmap: Bitmap,
        originalUri: Uri,
        originalPath: String?,
        customName: String = ""
    ): Pair<Boolean, String> {
        return try {
            // Normalize /storage_root alias before using as real filesystem path
            val normalizedPath = originalPath?.let { normalizeStoragePath(it) }
            val originalFile = normalizedPath?.let { File(it) }
            val parentDir = originalFile?.parentFile
                ?: android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            if (!parentDir.exists()) parentDir.mkdirs()

            val extension = originalFile?.extension?.takeIf { it.isNotEmpty() } ?: "jpg"
            val baseName = customName.ifBlank {
                val nameWithoutExtension = originalFile?.nameWithoutExtension ?: "edited_image"
                "${nameWithoutExtension}_edited"
            }

            var targetFile = File(parentDir, "$baseName.$extension")
            if (customName.isBlank()) {
                var counter = 1
                while (targetFile.exists()) {
                    targetFile = File(parentDir, "${baseName}_$counter.$extension")
                    counter++
                }
            }

            val format = when (extension.lowercase()) {
                "png"  -> Bitmap.CompressFormat.PNG
                "webp" -> Bitmap.CompressFormat.WEBP
                else   -> Bitmap.CompressFormat.JPEG
            }
            val compressed = FileOutputStream(targetFile).use { out -> bitmap.compress(format, 95, out) }
            if (!compressed) {
                targetFile.delete()
                return Pair(false, "Compression failed")
            }
            MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)
            Pair(true, targetFile.name)
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapAsCopy failed", e)
            Pair(false, e.message ?: "Copy error")
        }
    }
}
