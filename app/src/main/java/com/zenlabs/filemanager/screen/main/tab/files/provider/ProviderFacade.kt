package com.zenlabs.filemanager.screen.main.tab.files.provider

import android.content.Context
import com.zenlabs.filemanager.screen.main.tab.files.holder.ContentHolder
import kotlinx.coroutines.CompletableDeferred

/**
 * Common browser surface used by file-manager screens and future cloud providers.
 *
 * Implementations remain responsible for their own I/O. The facade does not copy, cache, or
 * reinterpret content; it only normalizes lifecycle and capability reporting.
 */
interface ProviderFacade {
    val descriptor: ProviderDescriptor

    suspend fun list(): List<ContentHolder>

    suspend fun find(name: String): ContentHolder?

    suspend fun createFile(name: String): ContentHolder?

    suspend fun createFolder(name: String): ContentHolder?

    fun open(
        context: Context,
        anonymous: Boolean = false,
        skipSupportedExtensions: Boolean = false,
        customMimeType: String? = null
    )
}

/**
 * Adapter over the mature [ContentHolder] hierarchy. This is the bridge that lets the rest of the
 * app adopt provider descriptors incrementally instead of migrating every holder at once.
 */
class ContentHolderProviderFacade(
    val holder: ContentHolder
) : ProviderFacade {

    override val descriptor: ProviderDescriptor = ProviderDescriptorFactory.forHolder(holder)

    override suspend fun list(): List<ContentHolder> = holder.listContent()

    override suspend fun find(name: String): ContentHolder? = holder.findFile(name)

    override suspend fun createFile(name: String): ContentHolder? {
        val result = CompletableDeferred<ContentHolder?>()
        holder.createSubFile(name) { created -> result.complete(created) }
        return result.await()
    }

    override suspend fun createFolder(name: String): ContentHolder? {
        val result = CompletableDeferred<ContentHolder?>()
        holder.createSubFolder(name) { created -> result.complete(created) }
        return result.await()
    }

    override fun open(
        context: Context,
        anonymous: Boolean,
        skipSupportedExtensions: Boolean,
        customMimeType: String?
    ) {
        holder.open(context, anonymous, skipSupportedExtensions, customMimeType)
    }
}
