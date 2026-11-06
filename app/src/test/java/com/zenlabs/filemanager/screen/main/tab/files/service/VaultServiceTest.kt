package com.zenlabs.filemanager.screen.main.tab.files.service

import com.google.gson.Gson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class VaultServiceTest {
    @Test
    fun `v2 round trip preserves payload`() {
        val directory = createTempDirectory()
        val plain = File(directory, "plain.bin")
        val encrypted = File(directory, "vault.nfv")
        val restored = File(directory, "restored.bin")
        val payload = ByteArray(32 * 1024) { index -> (index % 251).toByte() }
        plain.writeBytes(payload)

        VaultCrypto.encryptFile(plain, encrypted, "correct horse battery staple")

        assertTrue(VaultCrypto.isV2(encrypted))
        VaultCrypto.decryptFile(encrypted, restored, "correct horse battery staple")
        assertArrayEquals(payload, restored.readBytes())
    }

    @Test
    fun `v2 rejects tampered ciphertext`() {
        val directory = createTempDirectory()
        val plain = File(directory, "plain.bin")
        val encrypted = File(directory, "vault.nfv")
        val restored = File(directory, "restored.bin")
        plain.writeBytes(ByteArray(16 * 1024) { 7 })
        VaultCrypto.encryptFile(plain, encrypted, "password")

        val bytes = encrypted.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        encrypted.writeBytes(bytes)

        var rejected = false
        try {
            VaultCrypto.decryptFile(encrypted, restored, "password")
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Tampered ciphertext must fail authentication", rejected)
    }

    @Test
    fun `legacy file migrates to authenticated v2`() {
        val directory = createTempDirectory()
        val legacy = File(directory, "legacy.nfv")
        val restored = File(directory, "restored.bin")
        val payload = ByteArray(20 * 1024) { index -> (index % 239).toByte() }
        writeLegacyVault(legacy, payload, "password")

        assertFalse(VaultCrypto.isV2(legacy))
        VaultCrypto.migrateLegacy(legacy, "password")
        assertTrue(VaultCrypto.isV2(legacy))

        VaultCrypto.decryptFile(legacy, restored, "password")
        assertArrayEquals(payload, restored.readBytes())
    }

    private fun writeLegacyVault(target: File, payload: ByteArray, password: String) {
        val metadata = Gson().toJson(mapOf("size" to payload.size.toLong()))
        val metadataBytes = metadata.toByteArray(Charsets.UTF_8)
        val encryptedMetadata = legacyXor(metadataBytes, legacyKey(password, metadataBytes.size))
        val scrambledLength = minOf(VaultLegacyCrypto.SCRAMBLE_SIZE, payload.size)
        val encryptedPayload = legacyXor(payload.copyOfRange(0, scrambledLength), legacyKey(password, scrambledLength)) +
            payload.copyOfRange(scrambledLength, payload.size)

        target.outputStream().use { output ->
            output.write(VaultLegacyCrypto.MAGIC.toByteArray(Charsets.UTF_8))
            output.write(byteArrayOf(
                ((metadataBytes.size shr 24) and 0xFF).toByte(),
                ((metadataBytes.size shr 16) and 0xFF).toByte(),
                ((metadataBytes.size shr 8) and 0xFF).toByte(),
                (metadataBytes.size and 0xFF).toByte()
            ))
            output.write(encryptedMetadata)
            output.write(encryptedPayload)
        }
    }

    private fun legacyKey(password: String, length: Int): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return ByteArray(length) { index ->
            ((hash[index % hash.size].toInt() and 0xFF) xor (index and 0xFF)).toByte()
        }
    }

    private fun legacyXor(bytes: ByteArray, key: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        return ByteArray(bytes.size) { index ->
            (bytes[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun createTempDirectory(): File {
        return File.createTempFile("zenlabs-vault-test", "").let { file ->
            file.delete()
            file.mkdirs()
            file
        }
    }
}
