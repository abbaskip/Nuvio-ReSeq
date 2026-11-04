package com.nuvio.tv.livetv.data

import android.content.Context
import java.io.File

data class StorageCategory(val label: String, val bytes: Long)
data class IptvStorageSnapshot(val categories: List<StorageCategory>) {
    val totalBytes get() = categories.sumOf { it.bytes }
    fun readable(): String = categories.joinToString("\n") { "${it.label}: ${size(it.bytes)}" }
    companion object {
        fun size(bytes: Long): String = "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
    }
}

/** File sizes only: no rows, preference values, provider names, URLs or credentials are exported. */
object IptvStorageReport {
    fun bytes(root: File): Long {
        if (!root.exists()) return 0L
        if (root.isFile) return root.length()
        val boundary = root.canonicalPath + File.separator
        return root.walkTopDown().onEnter { it == root || it.canonicalPath.startsWith(boundary) }
            .filter { it.isFile && it.canonicalPath.startsWith(boundary) }.sumOf { it.length() }
    }

    fun scan(context: Context): IptvStorageSnapshot {
        fun database(name: String): Long {
            val path = context.getDatabasePath(name)
            return listOf(path, File(path.path + "-wal"), File(path.path + "-shm")).sumOf { it.length() }
        }
        val sourceBytes = bytes(File(context.cacheDir, "livetv_sources")) +
            bytes(File(context.filesDir, "livetv/epg")) + bytes(File(context.filesDir, "livetv/playlists"))
        val categories = listOf(
            StorageCategory("On Demand catalogue and journal", database("livetv_on_demand.db")),
            StorageCategory("TV guide and journal", database("livetv_guide.db")),
            StorageCategory("Poster lookup database", database("livetv_posters.db")),
            StorageCategory("Downloaded guide and playlist files", sourceBytes),
            StorageCategory("Other Live TV setup files", (bytes(File(context.filesDir, "livetv")) -
                bytes(File(context.filesDir, "livetv/epg")) - bytes(File(context.filesDir, "livetv/playlists"))).coerceAtLeast(0)),
            StorageCategory("Pending update installers", bytes(File(context.filesDir, "updates"))),
            StorageCategory("Cached artwork", bytes(File(context.cacheDir, "image_cache")) + bytes(File(context.cacheDir, "launcher_img"))),
            StorageCategory("Recordings on this device (kept)", bytes(File(context.filesDir, "recordings")) +
                (context.getExternalFilesDir(null)?.let { bytes(File(it, "recordings")) } ?: 0L))
        )
        return IptvStorageSnapshot(categories)
    }
}
