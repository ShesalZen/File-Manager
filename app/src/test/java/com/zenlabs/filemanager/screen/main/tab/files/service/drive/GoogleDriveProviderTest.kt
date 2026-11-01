package com.zenlabs.filemanager.screen.main.tab.files.service.drive

import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderConnectionState
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleDriveProviderTest {

    @Test
    fun unconfiguredProviderIsDiscoverableButRequiresAuthentication() {
        val descriptor = GoogleDriveProvider().descriptor

        assertEquals(GoogleDriveProvider.PROVIDER_ID, descriptor.id)
        assertEquals(GoogleDriveProvider.DISPLAY_NAME, descriptor.displayName)
        assertEquals(ProviderKind.CLOUD, descriptor.kind)
        assertEquals(ProviderConnectionState.AUTH_REQUIRED, descriptor.connectionState)
        assertTrue(descriptor.requiresNetwork)
        assertTrue(descriptor.requiresAuthentication)
        assertTrue(descriptor.statusMessage?.contains("not configured") == true)
    }

    @Test
    fun unconfiguredProviderRejectsAuthorizationRequest() {
        try {
            GoogleDriveProvider().createAuthorizationRequest()
            fail("Expected GoogleDriveCredentialsRequiredException")
        } catch (_: GoogleDriveCredentialsRequiredException) {
            // Expected: no token or OAuth request is fabricated without configuration.
        }
    }

    @Test
    fun configuredProviderRemainsDisconnectedAndRequestsDriveFileScopeOnly() {
        val provider = GoogleDriveProvider(
            GoogleDriveConfiguration(
                clientId = "test-client",
                redirectUri = "com.zenlabs.filemanager:/oauth2redirect"
            )
        )

        assertEquals(ProviderConnectionState.DISCONNECTED, provider.descriptor.connectionState)
        val request = provider.createAuthorizationRequest()
        assertEquals("test-client", request.clientId)
        assertEquals(listOf(GoogleDriveProvider.DRIVE_FILE_SCOPE), request.scopes)
        assertFalse(request.scopes.size > 1)
    }
}
