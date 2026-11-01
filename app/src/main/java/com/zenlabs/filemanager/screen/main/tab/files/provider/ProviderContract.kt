package com.zenlabs.filemanager.screen.main.tab.files.provider

/**
 * Stable provider categories shown to the UI. The category describes where content lives,
 * not which class happens to implement it.
 */
enum class ProviderKind {
    LOCAL,
    PRIVILEGED,
    REMOTE,
    VIRTUAL,
    ARCHIVE,
    CLOUD,
    UNKNOWN
}

/**
 * Connection state for a provider instance. Local providers are [READY]; remote providers may be
 * [CONNECTED] or [DISCONNECTED]; OAuth-backed providers use [AUTH_REQUIRED] until a token exists.
 */
enum class ProviderConnectionState {
    READY,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    AUTH_REQUIRED,
    ERROR
}

/**
 * Capabilities describe operations supported by the provider as a whole. They are intentionally
 * explicit so the UI can disable unsupported actions instead of failing after a user tap.
 */
data class ProviderCapabilities(
    val canBrowse: Boolean = false,
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    val canCreateFiles: Boolean = false,
    val canCreateFolders: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val canUpload: Boolean = false,
    val canDownload: Boolean = false,
    val canOpenInApp: Boolean = false
) {
    companion object {
        val NONE = ProviderCapabilities()
    }
}

/**
 * UI-safe description of a provider. This type deliberately contains no Android or credential
 * objects and can therefore be cached, compared, and unit-tested.
 */
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val kind: ProviderKind,
    val connectionState: ProviderConnectionState,
    val capabilities: ProviderCapabilities,
    val statusMessage: String? = null
) {
    val requiresNetwork: Boolean
        get() = kind == ProviderKind.REMOTE || kind == ProviderKind.CLOUD

    val requiresAuthentication: Boolean
        get() = kind == ProviderKind.CLOUD
}

/**
 * Pure capability rules shared by holder-backed and cloud providers. Keeping these rules here
 * prevents each adapter from inventing a different definition of "writable".
 */
object ProviderCatalog {
    fun capabilitiesFor(
        kind: ProviderKind,
        canRead: Boolean,
        canWrite: Boolean,
        canAddNewContent: Boolean,
        canOpenInApp: Boolean = canRead
    ): ProviderCapabilities {
        if (kind == ProviderKind.VIRTUAL || kind == ProviderKind.UNKNOWN) {
            return ProviderCapabilities(
                canBrowse = true,
                canRead = canRead,
                canOpenInApp = canOpenInApp && canRead
            )
        }

        val isRemote = kind == ProviderKind.REMOTE
        val isCloud = kind == ProviderKind.CLOUD
        val writable = canRead && canWrite
        return ProviderCapabilities(
            canBrowse = true,
            canRead = canRead,
            canWrite = writable,
            canCreateFiles = writable && (canAddNewContent || isRemote || isCloud),
            canCreateFolders = writable && (canAddNewContent || isRemote || isCloud),
            canRename = writable,
            canDelete = writable,
            canUpload = isRemote || isCloud,
            canDownload = isRemote || isCloud,
            canOpenInApp = canOpenInApp && canRead
        )
    }
}
