package com.zenlabs.filemanager.common

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages extraction of the bundled tectonic_cache.zip.xz from assets to
 * filesDir/Tectonic/ for offline LaTeX compilation.
 *
 * The cache is shipped as a single LZMA2 stream wrapping a zip archive. The APK compresses
 * every asset once, so a deflate-compressed zip could not be shrunk any further by the
 * packaging step. Storing the archive as XZ instead cuts roughly 3 MB off every APK while
 * producing byte-identical files.
 *
 * Ported from NFile's extractTectonicCacheAsset().
 */
object TectonicCacheManager {

    private const val TECTONIC_DIR_NAME = "Tectonic"
    private const val BUNDLES_DIR_NAME = "bundles"
    private const val CACHE_ASSET_NAME = "tectonic_cache.zip.xz"

    /**
     * Get the Tectonic cache directory path.
     */
    fun getTectonicDir(context: Context): File {
        return File(context.filesDir, TECTONIC_DIR_NAME)
    }

    /**
     * Check if the Tectonic cache is already extracted.
     */
    fun isExtracted(context: Context): Boolean {
        val tectonicDir = getTectonicDir(context)
        return tectonicDir.exists() && File(tectonicDir, "bundles/data/6ffe055852f8faf66c0acbe1a7fb27f87b869a90bad1204f3bf4d9683f597c7c.index").exists()
    }

    /**
     * Extract the tectonic cache from assets if not already done.
     *
     * @return The path to the Tectonic directory
     */
    suspend fun ensureExtracted(context: Context): String = withContext(Dispatchers.IO) {
        val tectonicDir = getTectonicDir(context)
        val baseDir = context.filesDir

        if (isExtracted(context)) {
            return@withContext tectonicDir.absolutePath
        }

        if (tectonicDir.exists()) {
            tectonicDir.deleteRecursively()
        }

        context.assets.open(CACHE_ASSET_NAME).use { assetStream ->
            XZInputStream(assetStream).use { xzStream ->
                ZipInputStream(xzStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(baseDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (zis.read(buffer).also { len = it } > 0) {
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }

        tectonicDir.absolutePath
    }
}
