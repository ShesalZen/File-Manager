package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkConnectionsServiceTest {
    @Test
    fun `metadata excludes password and preserves destination fields`() {
        val connection = NetworkConnectionModel(
            id = "connection-1",
            name = "Home server",
            type = "SFTP",
            host = "192.0.2.10",
            port = 22,
            username = "alice",
            password = "super-secret-password",
            rootPath = "/srv/files",
            protocol = "https"
        )

        val metadata = NetworkConnectionsService.toMetadata(connection)
        val serialized = Gson().toJson(metadata)

        assertFalse(serialized.contains("super-secret-password"))
        assertFalse(serialized.contains("password"))
        assertEquals(connection.id, metadata.id)
        assertEquals(connection.host, metadata.host)
        assertEquals(connection.port, metadata.port)
        assertEquals(connection.username, metadata.username)
        assertEquals(connection.rootPath, metadata.rootPath)
    }

    @Test
    fun `metadata can create a recovery marked connection without a password`() {
        val metadata = NetworkConnectionMetadata(
            id = "connection-2",
            name = "FTP server",
            type = "FTP",
            host = "198.51.100.20",
            port = 21,
            username = "bob",
            rootPath = "/share"
        )

        val connection = metadata.toModel(password = "", needsCredentialRecovery = true)

        assertTrue(connection.needsCredentialRecovery)
        assertEquals("", connection.password)
        assertEquals(metadata.host, connection.host)
        assertEquals(metadata.rootPath, connection.rootPath)
    }
}
