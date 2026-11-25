package com.nuvio.tv.livetv.data

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.model.EpgProgram
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgTitleSearchTest {
    @Test
    fun findsCurrentAndLaterAiringsOnVisibleChannelsInTimeOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "epg-title-search-${UUID.randomUUID()}").apply { check(mkdir()) }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File = File(directory, name)

            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)

            override fun openOrCreateDatabase(
                name: String,
                mode: Int,
                factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)
        }
        val now = 2_000_000_000_000L
        val tomorrow = now + 86_400_000L
        val current = EpgProgram(now - 300, now + 300, "CAFÉ | live")
        val later = EpgProgram(tomorrow, tomorrow + 300, "Café | repeat")
        val latest = later.copy(startMs = tomorrow + 600, stopMs = tomorrow + 900)
        try {
            EpgDatabase(isolatedContext).use { db ->
                db.replaceAll(
                    fingerprint = "test",
                    programs = mapOf(
                        "one" to listOf(latest, current, current, current.copy(stopMs = now)),
                        "two" to listOf(later, later.copy(startMs = tomorrow + 2_000, stopMs = tomorrow + 3_000)),
                        "hidden" to listOf(current.copy(startMs = now - 600))
                    ),
                    auto = emptyMap()
                )
                val keys = setOf("one", "two")
                val cutoff = tomorrow + 2_000
                assertEquals(
                    listOf("one" to current, "two" to later, "one" to latest),
                    db.searchTitles("café", keys, now, cutoff, limit = 10)
                )
                assertEquals(listOf("one" to current), db.searchTitles("café", keys, now, cutoff, limit = 1))
                assertEquals(emptyList<Pair<String, EpgProgram>>(), db.searchTitles("%", keys, now, cutoff, limit = 10))
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
