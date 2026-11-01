package com.zenlabs.filemanager.screen.main.tab.files.provider

import com.zenlabs.filemanager.screen.main.tab.files.holder.ContentHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.LocalFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RemoteFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RootFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.VirtualFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.ZipFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.shizuku.ShizukuFileHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    @Test
    fun holderClassesAreClassifiedIntoStableProviderKinds() {
        assertKind(ProviderKind.PRIVILEGED, RootFileHolder::class.java)
        assertKind(ProviderKind.PRIVILEGED, ShizukuFileHolder::class.java)
        assertKind(ProviderKind.REMOTE, RemoteFileHolder::class.java)
        assertKind(ProviderKind.ARCHIVE, ZipFileHolder::class.java)
        assertKind(ProviderKind.VIRTUAL, VirtualFileHolder::class.java)
        assertKind(ProviderKind.LOCAL, LocalFileHolder::class.java)
        assertKind(ProviderKind.UNKNOWN, UnknownContentHolder::class.java)
    }

    private fun assertKind(
        expected: ProviderKind,
        holderClass: Class<out ContentHolder>
    ) {
        assertEquals(expected, ProviderSourceClassifier.classify(holderClass).kind)
    }

    private class UnknownContentHolder : ContentHolder() {
        override val uniquePath = "unknown"
        override val displayName = "Unknown"
        override val isFolder = false
        override val lastModified = 0L
        override val size = 0L
        override val extension = ""
        override val canRead = false
        override val canWrite = false
        override val canAddNewContent = false
        override suspend fun listContent() = arrayListOf<ContentHolder>()
        override suspend fun getParent(): ContentHolder? = null
        override suspend fun getContentCount() =
            com.zenlabs.filemanager.screen.main.tab.files.misc.ContentCount()

        override suspend fun findFile(name: String): ContentHolder? = null
        override suspend fun isValid() = false
    }

    @Test
    fun writableLocalProviderGetsMutationCapabilitiesButNotTransferCapabilities() {
        val capabilities = ProviderCatalog.capabilitiesFor(
            kind = ProviderKind.LOCAL,
            canRead = true,
            canWrite = true,
            canAddNewContent = true
        )

        assertTrue(capabilities.canBrowse)
        assertTrue(capabilities.canRead)
        assertTrue(capabilities.canWrite)
        assertTrue(capabilities.canCreateFiles)
        assertTrue(capabilities.canCreateFolders)
        assertTrue(capabilities.canRename)
        assertTrue(capabilities.canDelete)
        assertTrue(capabilities.canOpenInApp)
        assertFalse(capabilities.canUpload)
        assertFalse(capabilities.canDownload)
    }

    @Test
    fun readOnlyLocalProviderCannotMutate() {
        val capabilities = ProviderCatalog.capabilitiesFor(
            kind = ProviderKind.LOCAL,
            canRead = true,
            canWrite = false,
            canAddNewContent = false
        )

        assertTrue(capabilities.canRead)
        assertFalse(capabilities.canWrite)
        assertFalse(capabilities.canCreateFiles)
        assertFalse(capabilities.canCreateFolders)
        assertFalse(capabilities.canRename)
        assertFalse(capabilities.canDelete)
    }

    @Test
    fun virtualCollectionsAreAlwaysReadOnly() {
        val capabilities = ProviderCatalog.capabilitiesFor(
            kind = ProviderKind.VIRTUAL,
            canRead = true,
            canWrite = true,
            canAddNewContent = true
        )

        assertTrue(capabilities.canBrowse)
        assertTrue(capabilities.canRead)
        assertFalse(capabilities.canWrite)
        assertFalse(capabilities.canCreateFiles)
        assertFalse(capabilities.canCreateFolders)
        assertFalse(capabilities.canRename)
        assertFalse(capabilities.canDelete)
    }

    @Test
    fun remoteProviderAdvertisesTransfersWithoutNetworkAssumptions() {
        val capabilities = ProviderCatalog.capabilitiesFor(
            kind = ProviderKind.REMOTE,
            canRead = true,
            canWrite = true,
            canAddNewContent = true
        )

        assertTrue(capabilities.canUpload)
        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canCreateFiles)
        assertTrue(capabilities.canCreateFolders)

        val descriptor = ProviderDescriptor(
            id = "remote:test",
            displayName = "Test server",
            kind = ProviderKind.REMOTE,
            connectionState = ProviderConnectionState.CONNECTED,
            capabilities = capabilities
        )
        assertTrue(descriptor.requiresNetwork)
        assertFalse(descriptor.requiresAuthentication)
    }

    @Test
    fun cloudCapabilitiesRequireAuthentication() {
        val capabilities = ProviderCatalog.capabilitiesFor(
            kind = ProviderKind.CLOUD,
            canRead = true,
            canWrite = true,
            canAddNewContent = true
        )
        val descriptor = ProviderDescriptor(
            id = "cloud:test",
            displayName = "Cloud",
            kind = ProviderKind.CLOUD,
            connectionState = ProviderConnectionState.AUTH_REQUIRED,
            capabilities = capabilities
        )

        assertTrue(descriptor.requiresNetwork)
        assertTrue(descriptor.requiresAuthentication)
        assertTrue(descriptor.capabilities.canUpload)
        assertTrue(descriptor.capabilities.canDownload)
        assertTrue(descriptor.capabilities.canOpenInApp)
    }
}
