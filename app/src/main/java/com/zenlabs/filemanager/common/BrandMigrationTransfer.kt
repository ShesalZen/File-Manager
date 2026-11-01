package com.zenlabs.filemanager.common

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal object BrandMigrationTransfer {
    fun copyResumably(
        source: File,
        destination: File,
        manifest: MigrationManifest
    ) {
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IllegalStateException("Cannot create ${destination.absolutePath}")
        }

        manifest.directories.sorted().forEach { relativePath ->
            val directory = File(destination, relativePath)
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IllegalStateException("Cannot create ${directory.absolutePath}")
            }
        }

        manifest.files.toSortedMap().forEach { (relativePath, expected) ->
            val sourceFile = File(source, relativePath)
            val destinationFile = File(destination, relativePath)
            val parent = destinationFile.parentFile
                ?: throw IllegalStateException("Cannot resolve destination for $relativePath")
            if (!parent.mkdirs() && !parent.isDirectory) {
                throw IllegalStateException("Cannot create ${parent.absolutePath}")
            }

            if (destinationFile.isFile &&
                runCatching { BrandMigrationIntegrity.digest(destinationFile) }.getOrNull() == expected
            ) {
                return@forEach
            }

            copyFile(sourceFile, destinationFile, expected)
        }
    }

    fun removeUnexpectedFiles(destination: File, manifest: MigrationManifest) {
        if (!destination.isDirectory) return
        val root = destination.canonicalFile
        val unexpectedFiles = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it !in manifest.files }
            .toList()
        unexpectedFiles.forEach { relativePath ->
            File(destination, relativePath).delete()
        }

        val unexpectedDirectories = root.walkTopDown()
            .filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it !in manifest.directories }
            .sortedByDescending { it.length }
            .toList()
        unexpectedDirectories.forEach { relativePath ->
            File(destination, relativePath).deleteRecursively()
        }
    }

    fun publish(stagingDirectory: File, targetDirectory: File) {
        if (targetDirectory.exists()) {
            throw IllegalStateException("Migration target appeared before publish")
        }
        if (!stagingDirectory.renameTo(targetDirectory)) {
            throw IllegalStateException("Could not publish verified migration directory")
        }
    }

    private fun copyFile(
        source: File,
        destination: File,
        expected: MigrationFileDigest
    ) {
        val temporary = File.createTempFile(".zenlabs-migration-", ".part", destination.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        size += count
                    }
                }
                output.fd.sync()
            }

            val actual = MigrationFileDigest(size, digest.digest().toHex())
            if (actual != expected) {
                throw IllegalStateException("Source file changed while copying ${source.absolutePath}")
            }
            if (destination.exists() && !destination.delete()) {
                throw IllegalStateException("Cannot replace ${destination.absolutePath}")
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("Cannot publish copied file ${destination.absolutePath}")
            }
            destination.setLastModified(source.lastModified())
        } finally {
            temporary.delete()
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
