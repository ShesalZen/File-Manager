package com.zenlabs.filemanager.screen.main.tab.files.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class WebSharingServerTest {
    @Test
    fun `html escaping neutralizes markup and attributes`() {
        val escaped = WebSharingServer.escapeHtml("<img src=x onerror=\"alert('x')\">&")

        assertFalse(escaped.contains("<"))
        assertFalse(escaped.contains(">"))
        assertFalse(escaped.contains("\""))
        assertFalse(escaped.contains("'"))
        assertEquals("\u0026lt;img src=x onerror=\u0026quot;alert(\u0026#x27;x\u0026#x27;)\u0026quot;\u0026gt;\u0026amp;", escaped)
    }

    @Test
    fun `authentication accepts bearer query and cookie independently`() {
        val token = "0123456789abcdef0123456789abcdef"

        assertTrue(
            WebSharingServer.isAuthorized(
                mapOf("authorization" to "Bearer $token"),
                null,
                token
            )
        )
        assertTrue(WebSharingServer.isAuthorized(emptyMap(), token, token))
        assertTrue(
            WebSharingServer.isAuthorized(
                mapOf("cookie" to "other=value; session=$token"),
                null,
                token
            )
        )
    }

    @Test
    fun `authentication rejects missing invalid and empty tokens`() {
        val token = "0123456789abcdef0123456789abcdef"

        assertFalse(WebSharingServer.isAuthorized(emptyMap(), null, token))
        assertFalse(
            WebSharingServer.isAuthorized(
                mapOf("authorization" to "Bearer wrong"),
                null,
                token
            )
        )
        assertFalse(WebSharingServer.isAuthorized(emptyMap(), null, ""))
    }

    @Test
    fun `tunnel verifier records an unpinned host key mismatch`() {
        val verifier = PinnedHostKeyVerifier(setOf("SHA256:not-a-real-pinned-key"))
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val error = try {
            verifier.verify("localhost.run", 22, keyPair.public)
            null
        } catch (e: WebShareTunnelHostKeyMismatchException) {
            e
        }

        assertTrue(verifier.mismatchDetected)
        assertTrue(error?.message?.contains("rotated its security keys") == true)
    }
}
