package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Stores destinations separately from encrypted passwords. The metadata index
 * is backup-safe; credential preferences are excluded by backup rules. A
 * missing or invalidated Keystore key therefore leaves the connection visible
 * but requires the user to enter its password again.
 */
object NetworkConnectionsService {
    private const val KEY_CONNECTIONS_LEGACY = "network_connections"
    private const val KEY_CONNECTIONS_ENCRYPTED = "network_connections_encrypted"
    private const val KEY_METADATA = "network_connections_metadata"
    private const val LEGACY_PREFS_NAME = "network_connections_prefs"
    private const val METADATA_PREFS_NAME = "network_connections_metadata"
    private const val CREDENTIAL_PREFS_NAME = "network_connections_credentials"
    private const val CREDENTIAL_KEY_PREFIX = "credential_"

    fun getConnections(context: Context): List<NetworkConnectionModel> {
        val metadata = readMetadata(context)
        if (metadata.isNotEmpty()) {
            migrateLegacyCredentials(context, metadata)
            return hydrate(readMetadata(context), context)
        }
        return migrateLegacyData(context)
    }

    fun saveConnection(context: Context, conn: NetworkConnectionModel): Boolean {
        ensureLegacyMigrated(context)
        val metadata = readMetadata(context).toMutableList()
        val next = toMetadata(conn)
        val index = metadata.indexOfFirst { it.id == conn.id }
        if (index >= 0) metadata[index] = next else metadata.add(next)
        writeMetadata(context, metadata)
        val credentialSaved = persistCredential(context, conn)
        if (credentialSaved) notifyRootsChanged(context)
        return credentialSaved
    }

    fun deleteConnection(context: Context, id: String) {
        ensureLegacyMigrated(context)
        writeMetadata(context, readMetadata(context).filterNot { it.id == id })
        credentialPreferences(context).edit().remove(credentialKey(id)).apply()
        RemoteConnectionPool.release(id)
        notifyRootsChanged(context)
    }

    /** Replaces one password without discarding other unavailable credentials. */
    fun updateCredential(context: Context, conn: NetworkConnectionModel, password: String): Boolean {
        val saved = persistCredential(
            context,
            conn.copy(password = password, needsCredentialRecovery = false)
        )
        if (saved) {
            RemoteConnectionPool.release(conn.id)
            notifyRootsChanged(context)
        }
        return saved
    }

    internal fun toMetadata(conn: NetworkConnectionModel) = NetworkConnectionMetadata(
        id = conn.id,
        name = conn.name,
        type = conn.type,
        host = conn.host,
        port = conn.port,
        username = conn.username,
        rootPath = conn.rootPath,
        protocol = conn.protocol
    )

    internal fun parseMetadata(json: String): List<NetworkConnectionMetadata> {
        return try {
            val type = object : TypeToken<List<NetworkConnectionMetadata>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseConnections(json: String): List<NetworkConnectionModel> {
        return try {
            val type = object : TypeToken<List<NetworkConnectionModel>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readMetadata(context: Context): List<NetworkConnectionMetadata> {
        val json = metadataPreferences(context).getString(KEY_METADATA, null) ?: return emptyList()
        return parseMetadata(json)
    }

    private fun writeMetadata(context: Context, metadata: List<NetworkConnectionMetadata>) {
        metadataPreferences(context).edit()
            .putString(KEY_METADATA, Gson().toJson(metadata))
            .apply()
    }

    private fun hydrate(
        metadata: List<NetworkConnectionMetadata>,
        context: Context
    ): List<NetworkConnectionModel> = metadata.map { entry ->
        val password = readCredential(context, entry.id)
        entry.toModel(password ?: "", needsCredentialRecovery = password == null)
    }

    private fun persistCredential(context: Context, conn: NetworkConnectionModel): Boolean {
        return try {
            val payload = Gson().toJson(NetworkConnectionCredential(conn.password))
            credentialPreferences(context).edit()
                .putString(credentialKey(conn.id), CredentialCipher.encrypt(payload))
                .apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readCredential(context: Context, id: String): String? {
        val encrypted = credentialPreferences(context).getString(credentialKey(id), null) ?: return null
        return try {
            val json = CredentialCipher.decrypt(encrypted)
            Gson().fromJson(json, NetworkConnectionCredential::class.java)?.password
        } catch (_: Exception) {
            null
        }
    }

    private fun ensureLegacyMigrated(context: Context) {
        val metadata = readMetadata(context)
        if (metadata.isEmpty()) {
            migrateLegacyData(context)
        } else {
            migrateLegacyCredentials(context, metadata)
        }
    }

    private fun migrateLegacyData(context: Context): List<NetworkConnectionModel> {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = legacyPrefs.getString(KEY_CONNECTIONS_ENCRYPTED, null)
        if (encrypted != null) {
            val connections = try {
                parseConnections(CredentialCipher.decrypt(encrypted))
            } catch (_: Exception) {
                emptyList()
            }
            if (connections.isNotEmpty()) {
                val metadata = connections.map { toMetadata(it) }
                writeMetadata(context, metadata)
                val allPersisted = connections.map { persistCredential(context, it) }.all { it }
                if (allPersisted) {
                    legacyPrefs.edit()
                        .remove(KEY_CONNECTIONS_ENCRYPTED)
                        .remove(KEY_CONNECTIONS_LEGACY)
                        .apply()
                }
                return hydrate(metadata, context)
            }
        }

        val legacyJson = legacyPrefs.getString(KEY_CONNECTIONS_LEGACY, null) ?: return emptyList()
        val connections = parseConnections(legacyJson)
        if (connections.isEmpty()) return emptyList()
        val metadata = connections.map { toMetadata(it) }
        writeMetadata(context, metadata)
        val allPersisted = connections.map { persistCredential(context, it) }.all { it }
        if (allPersisted) {
            legacyPrefs.edit()
                .remove(KEY_CONNECTIONS_ENCRYPTED)
                .remove(KEY_CONNECTIONS_LEGACY)
                .apply()
        }
        return hydrate(metadata, context)
    }

    private fun migrateLegacyCredentials(
        context: Context,
        metadata: List<NetworkConnectionMetadata>
    ) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = legacyPrefs.getString(KEY_CONNECTIONS_ENCRYPTED, null)
        val connections = if (encrypted != null) {
            try {
                parseConnections(CredentialCipher.decrypt(encrypted))
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            parseConnections(legacyPrefs.getString(KEY_CONNECTIONS_LEGACY, null) ?: return)
        }
        if (connections.isEmpty()) return
        val metadataById = metadata.associateBy { it.id }
        connections.forEach { conn ->
            if (metadataById.containsKey(conn.id) && readCredential(context, conn.id) == null) {
                persistCredential(context, conn)
            }
        }
        if (metadata.all { readCredential(context, it.id) != null }) {
            legacyPrefs.edit()
                .remove(KEY_CONNECTIONS_ENCRYPTED)
                .remove(KEY_CONNECTIONS_LEGACY)
                .apply()
        }
    }

    private fun credentialKey(id: String) = "$CREDENTIAL_KEY_PREFIX$id"

    private fun metadataPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(METADATA_PREFS_NAME, Context.MODE_PRIVATE)

    private fun credentialPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences(CREDENTIAL_PREFS_NAME, Context.MODE_PRIVATE)

    fun notifyRootsChanged(context: Context) {
        try {
            val rootsUri = android.provider.DocumentsContract.buildRootsUri("com.zenlabs.filemanager.documents")
            context.contentResolver.notifyChange(rootsUri, null)
        } catch (_: Exception) {
        }
    }
}
