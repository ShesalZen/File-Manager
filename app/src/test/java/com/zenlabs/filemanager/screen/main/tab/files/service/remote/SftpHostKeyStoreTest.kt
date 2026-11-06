package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator

class SftpHostKeyStoreTest {
    @Test
    fun `unknown key requires explicit trust`() {
        val store = SftpHostKeyStore(tempKnownHostsFile())
        val key = rsaKey()
        val verifier = store.verifier("example.test", 22)

        var unknown: SftpUnknownHostKeyException? = null
        try {
            verifier.verify("example.test", 22, key)
        } catch (e: SftpUnknownHostKeyException) {
            unknown = e
        }
        assertTrue(unknown != null)

        store.trust(unknown!!)
        assertTrue(verifier.verify("example.test", 22, key))
    }

    @Test
    fun `changed key fails closed`() {
        val store = SftpHostKeyStore(tempKnownHostsFile())
        val firstKey = rsaKey()
        val secondKey = rsaKey()
        val verifier = store.verifier("example.test", 22)

        val unknown = try {
            verifier.verify("example.test", 22, firstKey)
            throw AssertionError("Expected unknown host key")
        } catch (e: SftpUnknownHostKeyException) {
            e
        }
        store.trust(unknown)

        var mismatch = false
        try {
            verifier.verify("example.test", 22, secondKey)
        } catch (_: SftpHostKeyMismatchException) {
            mismatch = true
        }
        assertTrue(mismatch)
    }

    private fun rsaKey() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

    private fun tempKnownHostsFile(): File {
        return File.createTempFile("zenlabs-known-hosts", "").also { it.delete() }
    }
}
