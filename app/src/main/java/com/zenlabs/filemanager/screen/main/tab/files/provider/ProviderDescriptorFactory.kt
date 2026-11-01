package com.zenlabs.filemanager.screen.main.tab.files.provider

import com.zenlabs.filemanager.screen.main.tab.files.holder.ContentHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.LocalFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RemoteFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.RootFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.VirtualFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.holder.ZipFileHolder
import com.zenlabs.filemanager.screen.main.tab.files.shizuku.ShizukuFileHolder

/** Concrete holder families understood by the provider registry. */
enum class ProviderSourceType(val kind: ProviderKind) {
    ROOT(ProviderKind.PRIVILEGED),
    SHIZUKU(ProviderKind.PRIVILEGED),
    REMOTE(ProviderKind.REMOTE),
    ARCHIVE(ProviderKind.ARCHIVE),
    VIRTUAL(ProviderKind.VIRTUAL),
    LOCAL(ProviderKind.LOCAL),
    UNKNOWN(ProviderKind.UNKNOWN)
}

/**
 * Classifies holder implementations without constructing them, keeping provider-kind rules
 * deterministic and unit-testable.
 */
object ProviderSourceClassifier {
    fun classify(holderClass: Class<out ContentHolder>): ProviderSourceType {
        return when {
            RootFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.ROOT
            ShizukuFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.SHIZUKU
            RemoteFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.REMOTE
            ZipFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.ARCHIVE
            VirtualFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.VIRTUAL
            LocalFileHolder::class.java.isAssignableFrom(holderClass) -> ProviderSourceType.LOCAL
            else -> ProviderSourceType.UNKNOWN
        }
    }
}

/**
 * Maps legacy holder implementations to stable provider metadata.
 */
object ProviderDescriptorFactory {
    fun forHolder(holder: ContentHolder): ProviderDescriptor {
        return when (ProviderSourceClassifier.classify(holder.javaClass)) {
            ProviderSourceType.ROOT -> descriptor(
                id = "root:/",
                name = holder.displayName,
                kind = ProviderKind.PRIVILEGED,
                holder = holder
            )

            ProviderSourceType.REMOTE -> {
                val remoteHolder = holder as RemoteFileHolder
                descriptor(
                    id = "remote:${remoteHolder.connection.id}",
                    name = remoteHolder.connection.name,
                    kind = ProviderKind.REMOTE,
                    holder = holder,
                    state = ProviderConnectionState.CONNECTED,
                    statusMessage = remoteHolder.connection.type
                )
            }

            ProviderSourceType.ARCHIVE -> {
                val archiveHolder = holder as ZipFileHolder
                descriptor(
                    id = "archive:${archiveHolder.zipTree.source.uniquePath}",
                    name = archiveHolder.zipTree.source.displayName,
                    kind = ProviderKind.ARCHIVE,
                    holder = holder
                )
            }

            ProviderSourceType.VIRTUAL -> {
                val virtualHolder = holder as VirtualFileHolder
                descriptor(
                    id = "virtual:${virtualHolder.type}",
                    name = holder.displayName,
                    kind = ProviderKind.VIRTUAL,
                    holder = holder
                )
            }

            ProviderSourceType.SHIZUKU -> descriptor(
                id = "privileged:${holder.uniquePath}",
                name = holder.displayName,
                kind = ProviderKind.PRIVILEGED,
                holder = holder
            )

            ProviderSourceType.LOCAL -> descriptor(
                id = "local:${holder.uniquePath}",
                name = holder.displayName,
                kind = ProviderKind.LOCAL,
                holder = holder
            )

            ProviderSourceType.UNKNOWN -> ProviderDescriptor(
                id = "unknown:${holder.uniquePath}",
                displayName = holder.displayName,
                kind = ProviderKind.UNKNOWN,
                connectionState = ProviderConnectionState.READY,
                capabilities = ProviderCapabilities.NONE
            )
        }
    }

    private fun descriptor(
        id: String,
        name: String,
        kind: ProviderKind,
        holder: ContentHolder,
        state: ProviderConnectionState = ProviderConnectionState.READY,
        statusMessage: String? = null
    ): ProviderDescriptor {
        return ProviderDescriptor(
            id = id,
            displayName = name,
            kind = kind,
            connectionState = state,
            capabilities = ProviderCatalog.capabilitiesFor(
                kind = kind,
                canRead = holder.canRead,
                canWrite = holder.canWrite,
                canAddNewContent = holder.canAddNewContent
            ),
            statusMessage = statusMessage
        )
    }
}

/**
 * Entry point for provider metadata. It intentionally returns descriptors rather than holders so
 * disconnected providers (such as Google Drive before OAuth) can participate in the catalog.
 */
object ProviderRegistry {
    fun facadeFor(holder: ContentHolder): ProviderFacade = ContentHolderProviderFacade(holder)

    fun descriptorFor(holder: ContentHolder): ProviderDescriptor =
        ProviderDescriptorFactory.forHolder(holder)

    fun descriptorsFor(holders: Iterable<ContentHolder>): List<ProviderDescriptor> =
        holders.map(::descriptorFor)

    fun catalog(holders: Iterable<ContentHolder>): List<ProviderDescriptor> =
        descriptorsFor(holders)
}
