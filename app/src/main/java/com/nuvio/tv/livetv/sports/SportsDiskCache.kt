package com.nuvio.tv.livetv.sports

import java.io.File

/** Downloaded/derived sports data is disposable through Android's Clear cache action. */
internal object SportsDiskCache {
    @Synchronized fun directory(filesDir: File, cacheDir: File): File {
        val target = File(cacheDir, "sports").apply { mkdirs() }
        val legacy = File(filesDir, "sports")
        // The tiny AI request-budget ledger is persistent state, not downloaded content.
        legacy.listFiles().orEmpty().filter { it.isFile && isCache(it.name) }.forEach { old ->
            val replacement = File(target, old.name)
            if (replacement.exists()) old.delete()
            else if (!old.renameTo(replacement)) {
                // These files are recoverable downloads; don't strand them in app data
                // if a move is unavailable. The normal cache-miss path refetches them.
                old.delete()
            }
        }
        if (legacy.listFiles()?.isEmpty() == true) legacy.delete()
        return target
    }

    private fun isCache(name: String): Boolean = name != "gemini-guide-usage.json" &&
        (name == "matches.json" || name.startsWith("base-feed") ||
            listOf("sb_", "wk_", "ts_", "teams_", "free_", "cricket_au_", "fx_", "table_", "gemini-guide-")
                .any(name::startsWith))
}
