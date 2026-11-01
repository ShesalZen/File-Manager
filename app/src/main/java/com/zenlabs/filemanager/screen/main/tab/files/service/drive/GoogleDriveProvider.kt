package com.zenlabs.filemanager.screen.main.tab.files.service.drive

import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderCapabilities
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderCatalog
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderConnectionState
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderDescriptor
import com.zenlabs.filemanager.screen.main.tab.files.provider.ProviderKind

/** OAuth configuration is injected at the app boundary; no secret is compiled into the APK. */
data class GoogleDriveConfiguration(
    val clientId: String? = null,
    val redirectUri: String? = null
) {
    val isConfigured: Boolean
        get() = !clientId.isNullOrBlank() && !redirectUri.isNullOrBlank()
}

data class GoogleDriveAuthorizationRequest(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>
)

class GoogleDriveCredentialsRequiredException :
    IllegalStateException("Google Drive OAuth client credentials are not configured")

/**
 * Credential-free Google Drive integration seam.
 *
 * The provider is discoverable while disconnected so the UI can explain why Drive is unavailable
 * and can enable it automatically once configuration is supplied. Network/OAuth code must not be
 * added here until a client ID, redirect URI, and the final consent-flow ownership are confirmed.
 */
class GoogleDriveProvider(
    private val configuration: GoogleDriveConfiguration = GoogleDriveConfiguration()
) {
    val descriptor: ProviderDescriptor
        get() {
            val configured = configuration.isConfigured
            return ProviderDescriptor(
                id = PROVIDER_ID,
                displayName = DISPLAY_NAME,
                kind = ProviderKind.CLOUD,
                connectionState = if (configured) {
                    ProviderConnectionState.DISCONNECTED
                } else {
                    ProviderConnectionState.AUTH_REQUIRED
                },
                capabilities = ProviderCatalog.capabilitiesFor(
                    kind = ProviderKind.CLOUD,
                    canRead = true,
                    canWrite = true,
                    canAddNewContent = true
                ),
                statusMessage = if (configured) {
                    "Google Drive is not connected"
                } else {
                    "Google Drive OAuth credentials are not configured"
                }
            )
        }

    /**
     * Returns the input required by a future OAuth implementation. This function performs no
     * network access and never fabricates a token.
     */
    fun createAuthorizationRequest(): GoogleDriveAuthorizationRequest {
        if (!configuration.isConfigured) {
            throw GoogleDriveCredentialsRequiredException()
        }
        return GoogleDriveAuthorizationRequest(
            clientId = requireNotNull(configuration.clientId),
            redirectUri = requireNotNull(configuration.redirectUri),
            scopes = listOf(DRIVE_FILE_SCOPE)
        )
    }

    companion object {
        const val PROVIDER_ID = "google-drive"
        const val DISPLAY_NAME = "Google Drive"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
