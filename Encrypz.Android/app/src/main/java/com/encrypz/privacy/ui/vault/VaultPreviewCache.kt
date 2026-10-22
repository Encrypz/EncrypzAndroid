package com.encrypz.privacy.ui.vault

import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal class VaultPreviewCache {
    // ByteArray equality uses identity, so replacing encrypted content invalidates its preview.
    private class Key(val payload: ByteArray, val thumbnail: ByteArray?, val dimension: Int) {
        override fun equals(other: Any?) = other is Key && payload === other.payload &&
            thumbnail === other.thumbnail && dimension == other.dimension
        override fun hashCode() = 31 * (31 * System.identityHashCode(payload) +
            System.identityHashCode(thumbnail)) + dimension
    }
    private val cache = object : LruCache<Key, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val workers = Semaphore(2)
    private val generation = AtomicInteger()

    suspend fun load(file: VaultFileEntry, dimension: Int, viewModel: VaultViewModel): Bitmap? {
        val version = generation.get()
        val key = Key(file.encryptedPayload, file.encryptedThumbnail, dimension)
        return withContext(Dispatchers.Default) {
            workers.withPermit {
                currentCoroutineContext().ensureActive()
                cache.get(key)?.let { return@withPermit it }
                // Binary documents/audio/video without a thumbnail cannot be decoded as images.
                val bytes = viewModel.decryptThumbnailBytes(file)
                    ?: if (file.category == VaultCategoryFilter.PHOTOS) viewModel.decryptFileBytes(file) else null
                val bitmap = bytes?.let {
                    try { decodeSampledBitmap(it, dimension) } finally { it.fill(0) }
                }
                currentCoroutineContext().ensureActive()
                synchronized(this@VaultPreviewCache) {
                    if (generation.get() != version) return@synchronized null
                    if (bitmap != null) cache.put(key, bitmap)
                    bitmap
                }
            }
        }
    }

    @Synchronized fun clear() { generation.incrementAndGet(); cache.evictAll() }
}
