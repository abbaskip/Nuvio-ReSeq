package com.nuvio.tv.livetv.data

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Database files on Android grow but never shrink: space freed by deleting rows (fewer guide
 * days, a playlist removed, groups hidden) stays in the file as empty pages. This gives it back
 * when there's a lot of it. Cheap to call: it only looks at two numbers unless there's work.
 */
internal object SqliteCompact {
    private const val TAG = "SqliteCompact"
    /** Only worth the work past this much empty space, and when it's a good part of the file. */
    private const val MIN_FREE_BYTES = 8L * 1024 * 1024
    private const val SPACE_RESERVE_BYTES = 64L * 1024 * 1024

    /** Returns the bytes given back (0 when there was nothing worth doing). */
    fun compactIfWasteful(db: SQLiteDatabase, label: String, reclaimOnCommit: Boolean = false): Long = runCatching {
        fun pragma(q: String): Long = db.rawQuery(q, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        fun checkpoint(): Boolean = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
            it.moveToFirst() && it.getInt(0) == 0
        }
        val file = File(db.path)
        fun allocated() = file.length() + File(db.path + "-wal").length() + File(db.path + "-shm").length()
        val before = allocated()
        if (db.inTransaction() || !checkpoint()) return@runCatching 0L
        val pageSize = pragma("PRAGMA page_size")
        val pages = pragma("PRAGMA page_count")
        val free = pragma("PRAGMA freelist_count")
        val freeBytes = free * pageSize
        if(freeBytes>=MIN_FREE_BYTES && pragma("PRAGMA auto_vacuum")==2L) {
            var remaining=free
            while(remaining>0) {
                db.rawQuery("PRAGMA incremental_vacuum(4096)",null).use { cursor -> while(cursor.moveToNext()) { /* Drain every reclamation step. */ } }
                if(!checkpoint()) break
                val next=pragma("PRAGMA freelist_count")
                if(next>=remaining) break
                remaining=next
            }
            return@runCatching (before-allocated()).coerceAtLeast(0)
        }
        if (freeBytes < MIN_FREE_BYTES || free * 4 < pages) return@runCatching (before - allocated()).coerceAtLeast(0)
        // In WAL mode the temporary copy and the new journal contain the rebuilt database.
        // Free pages in the original file aren't copied to either one.
        val copyBytes = if (reclaimOnCommit && db.isWriteAheadLoggingEnabled) (pages - free) * pageSize else file.length()
        if (file.usableSpace < copyBytes * 2 + SPACE_RESERVE_BYTES) {
            com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Deferred $label database compaction: ${freeBytes / (1024 * 1024)} MB reusable inside the database")
            return@runCatching (before - allocated()).coerceAtLeast(0)
        }
        // The guide repeatedly stages and deletes hundreds of thousands of listings.
        // Enable page reclamation during the same rebuild so later imports return those pages at commit.
        if (reclaimOnCommit && pragma("PRAGMA auto_vacuum") != 1L) db.execSQL("PRAGMA auto_vacuum = FULL")
        db.execSQL("VACUUM")
        checkpoint()
        val reclaimed = (before - allocated()).coerceAtLeast(0)
        val mb = reclaimed / (1024 * 1024)
        com.nuvio.tv.livetv.model.LiveTvLoadReport.add("Compacted the $label database: $mb MB given back")
        reclaimed
    }.onFailure { Log.w(TAG, "Couldn't compact $label", it) }.getOrDefault(0L)
}
