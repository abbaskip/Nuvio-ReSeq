package com.nuvio.tv.updater

import java.io.File

/** Disposable installers only. Never follow a saved path outside the updater's directory. */
internal class UpdateFiles(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    fun contains(file: File): Boolean = file.canonicalFile.parentFile == directory.canonicalFile

    fun pin(file: File) {
        require(contains(file))
        File(directory, file.name + PIN_SUFFIX).apply { writeText(""); setLastModified(now()) }
    }

    fun isPinned(file: File): Boolean {
        if (!contains(file)) return false
        val marker = File(directory, file.name + PIN_SUFFIX)
        return marker.exists() && now() - marker.lastModified() < INSTALL_GRACE_MS
    }

    fun completed(file: File): Boolean {
        if (!contains(file)) return false
        val deleted = !file.exists() || file.delete()
        if (deleted) File(directory, file.name + PIN_SUFFIX).delete()
        return deleted
    }

    fun sweep(pending: File?, keepPending: Boolean): Boolean {
        val files = directory.listFiles().orEmpty()
        var success = true
        for (file in files) {
            if (!contains(file) || file.isDirectory) continue
            if (file.name.endsWith(PIN_SUFFIX)) {
                val source = File(directory, file.name.removeSuffix(PIN_SUFFIX))
                if (!source.exists() || now() - file.lastModified() >= INSTALL_GRACE_MS) {
                    if (!file.delete()) success = false
                }
                continue
            }
            val pin = File(directory, file.name + PIN_SUFFIX)
            val pinned = pin.exists() && now() - pin.lastModified() < INSTALL_GRACE_MS
            val retained = keepPending && pending != null && file.canonicalFile == pending.canonicalFile &&
                now() - file.lastModified() < PENDING_TTL_MS && file.length() > 0
            if (!pinned && !retained && !file.delete()) success = false
        }
        return success
    }

    companion object {
        const val PENDING_TTL_MS = 24 * 60 * 60_000L
        private const val INSTALL_GRACE_MS = 2 * 60 * 60_000L
        private const val PIN_SUFFIX = ".installing"
    }
}
