package com.zenlabs.filemanager.screen.main.tab.files.service

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated v2 crypto primitives, independent of Android APIs. */
object VaultCrypto {
    const val MAGIC_V2 = "ZVAULT02"
    const val KEY_ITERATIONS = 120_000
    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128

    private const val BUFFER_SIZE = 64 * 1024

    fun isV2(file: File): Boolean {
        if (!file.isFile || file.length() < MAGIC_V2.length) return false
        return file.inputStream().use { input ->
            val magic = ByteArray(MAGIC_V2.length)
            input.readFully(magic)
            magic.contentEquals(MAGIC_V2.toByteArray(Charsets.US_ASCII))
        }
    }

    fun encryptFile(plainFile: File, encryptedFile: File, password: CharSequence) {
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        encryptedFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(encryptedFile)).use { rawOut ->
            rawOut.write(MAGIC_V2.toByteArray(Charsets.US_ASCII))
            rawOut.write(salt)
            rawOut.write(iv)
            rawOut.flush()
            CipherOutputStream(rawOut, cipher).use { encryptedOut ->
                BufferedInputStream(FileInputStream(plainFile)).use { input ->
                    input.copyTo(encryptedOut, BUFFER_SIZE)
                }
            }
        }
    }

    fun decryptFile(encryptedFile: File, plainFile: File, password: CharSequence) {
        require(isV2(encryptedFile)) { "Unsupported vault file format" }
        plainFile.parentFile?.mkdirs()
        BufferedInputStream(FileInputStream(encryptedFile)).use { rawIn ->
            val magic = ByteArray(MAGIC_V2.length)
            rawIn.readFully(magic)
            require(magic.contentEquals(MAGIC_V2.toByteArray(Charsets.US_ASCII))) {
                "Invalid vault magic"
            }
            val salt = ByteArray(SALT_BYTES)
            val iv = ByteArray(IV_BYTES)
            rawIn.readFully(salt)
            rawIn.readFully(iv)
            val key = deriveKey(password, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            CipherInputStream(rawIn, cipher).use { decryptedIn ->
                BufferedOutputStream(FileOutputStream(plainFile)).use { output ->
                    decryptedIn.copyTo(output, BUFFER_SIZE)
                }
            }
        }
    }

    /** Decrypts a legacy v1 payload and atomically replaces it with v2. */
    fun migrateLegacy(encryptedFile: File, password: CharSequence) {
        val plainTemp = File(encryptedFile.parentFile, "${encryptedFile.name}.legacy-plain.tmp")
        val encryptedTemp = File(encryptedFile.parentFile, "${encryptedFile.name}.v2.tmp")
        try {
            VaultLegacyCrypto.decrypt(encryptedFile, plainTemp, password)
            encryptFile(plainTemp, encryptedTemp, password)
            try {
                java.nio.file.Files.move(
                    encryptedTemp.toPath(),
                    encryptedFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    encryptedTemp.toPath(),
                    encryptedFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            plainTemp.delete()
            encryptedTemp.delete()
        }
    }

    fun deriveKey(password: CharSequence, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toString().toCharArray(), salt, KEY_ITERATIONS, 256)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also {
        SecureRandom().nextBytes(it)
    }

    private fun java.io.InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IllegalArgumentException("Truncated vault file")
            offset += read
        }
    }
}
