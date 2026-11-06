package com.zenlabs.filemanager.screen.main.tab.files.service

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Reader for the legacy XOR-obfuscated Vault v1 payload. */
object VaultLegacyCrypto {
    const val MAGIC = "NFILE_VAULT_V1"
    const val SCRAMBLE_SIZE = 8192
    private const val MAX_METADATA_BYTES = 1024 * 1024
    private const val BUFFER_SIZE = 64 * 1024

    fun decrypt(encryptedFile: File, plainFile: File, password: CharSequence) {
        BufferedInputStream(FileInputStream(encryptedFile)).use { input ->
            val magic = ByteArray(MAGIC.length)
            input.readFully(magic)
            require(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
                "Invalid legacy vault magic"
            }
            val metadataLength = readInt(input)
            require(metadataLength in 1..MAX_METADATA_BYTES) { "Invalid legacy vault metadata length" }
            val encryptedMetadata = ByteArray(metadataLength)
            input.readFully(encryptedMetadata)
            val metadataKey = deriveKey(password.toString(), metadataLength)
            val metadataJson = String(xorBytes(encryptedMetadata, metadataKey), Charsets.UTF_8)
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val metadata: Map<String, Any?> = Gson().fromJson(metadataJson, type)
                ?: throw IllegalArgumentException("Invalid legacy vault metadata")
            val originalSize = (metadata["size"] as? Number)?.toLong()
                ?: throw IllegalArgumentException("Legacy vault metadata has no size")
            require(originalSize >= 0L) { "Invalid legacy vault size" }

            val scrambledLength = minOf(SCRAMBLE_SIZE.toLong(), originalSize).toInt()
            plainFile.parentFile?.mkdirs()
            BufferedOutputStream(FileOutputStream(plainFile)).use { output ->
                val scrambled = ByteArray(scrambledLength)
                input.readFully(scrambled)
                output.write(xorBytes(scrambled, deriveKey(password.toString(), scrambledLength)))
                var remaining = originalSize - scrambledLength
                val buffer = ByteArray(BUFFER_SIZE)
                while (remaining > 0L) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, toRead)
                    require(read >= 0) { "Truncated legacy vault payload" }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    private fun deriveKey(password: String, length: Int): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return ByteArray(length) { index ->
            ((hash[index % hash.size].toInt() and 0xFF) xor (index and 0xFF)).toByte()
        }
    }

    private fun xorBytes(bytes: ByteArray, key: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        require(key.isNotEmpty()) { "Legacy XOR key cannot be empty" }
        return ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun readInt(input: java.io.InputStream): Int {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    private fun java.io.InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IllegalArgumentException("Truncated legacy vault file")
            offset += read
        }
    }
}
