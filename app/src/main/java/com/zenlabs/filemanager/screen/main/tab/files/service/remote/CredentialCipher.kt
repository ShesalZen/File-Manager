package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialRecoveryRequiredException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/** AES-GCM encryption backed by a non-exportable Android Keystore key. */
object CredentialCipher {
    private const val KEY_ALIAS = "zenlabs_remote_credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val FORMAT_PREFIX = "v1:"

    @Synchronized
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteInvalidatedKey()
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: UnrecoverableKeyException) {
            deleteInvalidatedKey()
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        } catch (e: KeyStoreException) {
            deleteInvalidatedKey()
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(payload)
        encrypted.copyInto(payload, cipher.iv.size)
        return FORMAT_PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        require(encoded.startsWith(FORMAT_PREFIX)) { "Unsupported encrypted credential format" }
        val payload = Base64.getDecoder().decode(encoded.substring(FORMAT_PREFIX.length))
        require(payload.size > GCM_IV_BYTES) { "Invalid encrypted credential payload" }
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteInvalidatedKey()
            throw CredentialRecoveryRequiredException("Android Keystore credential key was invalidated", e)
        } catch (e: UnrecoverableKeyException) {
            deleteInvalidatedKey()
            throw CredentialRecoveryRequiredException("Android Keystore credential key cannot be recovered", e)
        }
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun deleteInvalidatedKey() {
        try {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
        }
    }
}
