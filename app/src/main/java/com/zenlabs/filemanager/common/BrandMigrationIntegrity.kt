package com.zenlabs.filemanager.common

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal data class MigrationFileDigest(
    val size: Long,
    val sha256: String
)

internal data class MigrationManifest(
    val directories: Set<String>,
    val files: Map<String, MigrationFileDigest>
)

internal object BrandMigrationIntegrity {
    private const val MARKER_CONTENT = "zenlabs-brand-migration-v1\n"

    fun manifest(directory: File): MigrationManifest {
        require(directory.isDirectory) { "Cannot summarize non-directory ${directory.absolutePath}" }
        val root = directory.canonicalFile
        val directories = linkedSetOf<String>()
        val files = linkedMapOf<String, MigrationFileDigest>()

        root.walkTopDown().onEnter { file ->
            if (file != root && !file.canRead()) {
                throw IllegalStateException("Cannot read ${file.absolutePath}")
            }
            true
        }.forEach { file ->
            if (file == root) return@forEach
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            if (relativePath == BrandMigration.MARKER_NAME) {
                throw IllegalStateException(
                    "Legacy source contains reserved migration marker $relativePath"
                )
            }
            when {
                file.isDirectory -> directories += relativePath
                file.isFile -> files[relativePath] = digest(file)
            }
        }
        return MigrationManifest(directories, files)
    }

    fun digest(file: File): MigrationFileDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                size += count
            }
        }
        return MigrationFileDigest(size, digest.digest().toHex())
    }

    fun hasValidMarker(directory: File): Boolean {
        val marker = File(directory, BrandMigration.MARKER_NAME)
        return marker.isFile && runCatching { marker.readText() }.getOrNull() == MARKER_CONTENT
    }

    fun writeMarker(directory: File) {
        val marker = File(directory, BrandMigration.MARKER_NAME)
        val temporaryMarker = File(directory, ".${BrandMigration.MARKER_NAME}.tmp")
        FileOutputStream(temporaryMarker).use { output ->
            output.write(MARKER_CONTENT.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (marker.exists() && !marker.delete()) {
            temporaryMarker.delete()
            throw IllegalStateException("Cannot replace migration marker ${marker.absolutePath}")
        }
        if (!temporaryMarker.renameTo(marker)) {
            temporaryMarker.delete()
            throw IllegalStateException("Cannot publish migration marker ${marker.absolutePath}")
        }
    }

    private fun ByteArray.toHex(): String {
        val result = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xff
            result.append(HEX[value ushr 4])
            result.append(HEX[value and 0x0f])
        }
        return result.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
