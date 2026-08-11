package com.voidlink.android.protocol.http

import com.voidlink.android.protocol.Hex
import com.voidlink.android.protocol.ProtocolLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Disk cache for `/appasset` box art (spec §3.5).
 *
 * Box art almost never changes and each image is a separate pinned-TLS round trip, so caching is
 * the difference between an app grid that appears instantly and one that re-downloads a megabyte
 * every time the user opens a host.
 *
 * Trimming is deliberately crude — oldest modification time first, once the directory exceeds the
 * budget — because the access pattern is "a few dozen small files per host" and anything cleverer
 * would be bookkeeping for its own sake.
 *
 * @param baseDir the application's `cacheDir`.
 * @param maxBytes budget for the whole cache directory.
 */
class BoxArtCache(baseDir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {

    private val directory = File(baseDir, DIRECTORY_NAME)
    private val trimMutex = Mutex()

    /**
     * Reads cached art.
     *
     * @return the encoded PNG bytes, or `null` when nothing is cached.
     */
    suspend fun get(hostKey: String, appId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileFor(hostKey, appId)
        if (!file.isFile) {
            null
        } else {
            runCatching {
                // Touch so the trim treats recently-used art as recently-written.
                file.setLastModified(System.currentTimeMillis())
                file.readBytes()
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Stores art, trimming the cache afterwards if it has outgrown its budget.
     */
    suspend fun put(hostKey: String, appId: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
                val file = fileFor(hostKey, appId)
                val temp = File(directory, "${file.name}.tmp")
                temp.writeBytes(bytes)
                if (!temp.renameTo(file)) {
                    file.delete()
                    if (!temp.renameTo(file)) temp.delete()
                }
            }.onFailure {
                ProtocolLog.d(ProtocolLog.TAG_HTTP, "Box art cache write failed: ${it.message}")
            }
        }
        trim()
    }

    /** Drops every cached image for one host, used when the host is forgotten. */
    suspend fun clearHost(hostKey: String) = withContext(Dispatchers.IO) {
        val prefix = hostPrefix(hostKey)
        runCatching {
            directory.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix)) file.delete()
            }
        }
        Unit
    }

    private suspend fun trim() = trimMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val files = directory.listFiles()?.filter { it.isFile } ?: return@runCatching
                var total = files.sumOf { it.length() }
                if (total <= maxBytes) return@runCatching
                files.sortedBy { it.lastModified() }.forEach { file ->
                    if (total <= maxBytes) return@forEach
                    val size = file.length()
                    if (file.delete()) total -= size
                }
            }
            Unit
        }
    }

    /**
     * Filenames hex-encode the host key so a value chosen by a remote machine can never contain a
     * path separator.
     */
    private fun fileFor(hostKey: String, appId: Long): File =
        File(directory, "${hostPrefix(hostKey)}$appId$FILE_SUFFIX")

    private fun hostPrefix(hostKey: String): String =
        Hex.encode(hostKey.toByteArray(Charsets.UTF_8)) + "_"

    private companion object {
        const val DIRECTORY_NAME = "boxart"
        const val FILE_SUFFIX = ".png"

        /** Architecture §7 budgets 64 MB for box art. */
        const val DEFAULT_MAX_BYTES = 64L * 1024L * 1024L
    }
}
