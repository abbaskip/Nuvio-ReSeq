package com.nuvio.tv.livetv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live TV poster lookups, kept on the device: a show's poster (or "none found") is searched for
 * once and remembered across restarts, so the guide doesn't search your addons again every
 * time the app opens. Found posters are re-checked after 30 days, misses after 3.
 */
@Singleton
class LiveTvPosterStore @Inject constructor(@ApplicationContext context: Context) :
    SQLiteOpenHelper(context, "livetv_posters.db", null, 3) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE posters (k TEXT PRIMARY KEY, poster TEXT, checked INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS posters")
        onCreate(db)
    }

    /** null = not known (look it up); "" = looked up, nothing found; else the poster. */
    fun get(key: String): String? = runCatching {
        readableDatabase.rawQuery("SELECT poster, checked FROM posters WHERE k = ?", arrayOf(key)).use { c ->
            if (!c.moveToFirst()) return@use null
            val poster = if (c.isNull(0)) "" else c.getString(0)
            val age = System.currentTimeMillis() - c.getLong(1)
            val maxAge = if (poster.isEmpty()) MISS_MS else FOUND_MS
            if (age > maxAge) null else poster
        }
    }.getOrNull()

    /** Forgets everything (Settings: clear poster & show info). */
    fun clear() { runCatching { writableDatabase.delete("posters", null, null) } }

    fun put(key: String, poster: String?) {
        runCatching {
            writableDatabase.insertWithOnConflict("posters", null, ContentValues().apply {
                put("k", key); put("poster", poster ?: ""); put("checked", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private companion object {
        const val FOUND_MS = 30L * 24 * 60 * 60 * 1000
        const val MISS_MS = 3L * 24 * 60 * 60 * 1000
    }
}
