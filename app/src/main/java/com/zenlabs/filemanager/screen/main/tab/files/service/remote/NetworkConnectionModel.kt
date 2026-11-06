package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import androidx.annotation.Keep

/**
 * A saved remote connection.
 *
 * [needsCredentialRecovery] is a runtime-only marker. It is set when the
 * Android Keystore key is unavailable (for example after a device restore or
 * OEM key invalidation) and the password must be entered again.
 */
@Keep
data class NetworkConnectionModel(
    val id: String,
    val name: String,
    val type: String, // "FTP", "SFTP", "LAN/SMB", "WebDav"
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val rootPath: String = "/",
    val protocol: String = "http",
    val needsCredentialRecovery: Boolean = false
)

/**
 * Non-sensitive connection fields kept outside the encrypted credential blob.
 * This permits the destinations to survive backup/restore without ever
 * placing a password in backup data.
 */
@Keep
data class NetworkConnectionMetadata(
    val id: String,
    val name: String,
    val type: String,
    val host: String,
    val port: Int,
    val username: String,
    val rootPath: String = "/",
    val protocol: String = "http"
) {
    fun toModel(password: String, needsCredentialRecovery: Boolean = false) =
        NetworkConnectionModel(
            id = id,
            name = name,
            type = type,
            host = host,
            port = port,
            username = username,
            password = password,
            rootPath = rootPath,
            protocol = protocol,
            needsCredentialRecovery = needsCredentialRecovery
        )
}

/** Password material encrypted separately for each connection. */
@Keep
data class NetworkConnectionCredential(
    val password: String
)
