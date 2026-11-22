package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.storage.StorageTestContext
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GuideBatchTest {
    @Test fun upgradingAStoredVersionThreeGuideKeepsListingsUntilTheNextSuccessfulSave(): Unit = runBlocking {
        val context = StorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val path = context.getDatabasePath("livetv_guide.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("CREATE TABLE programs (ch TEXT,start INTEGER,stop INTEGER,title TEXT,descr TEXT,cat TEXT,ep TEXT,icon TEXT,year INTEGER,people TEXT)")
            legacy.execSQL("CREATE INDEX next_programs_ch_start ON programs(ch,start)")
            legacy.execSQL("CREATE TABLE auto (ch TEXT PRIMARY KEY,source TEXT,xmltv TEXT)")
            legacy.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY,v TEXT)")
            legacy.execSQL("INSERT INTO meta VALUES ('fingerprint','published')")
            legacy.execSQL("INSERT INTO programs(ch,start,stop,title) VALUES ('original',1,10,'Existing appointment')")
            legacy.version = 3
        }
        try {
            EpgDatabase(context).use { db ->
                assertEquals("published", db.fingerprint())
                assertEquals("Existing appointment", db.program("original", 1)?.title)
                val listings = mutableListOf<String>()
                db.visitCurrentPrograms(5, listOf("original")) { _, program -> listings += program.title }
                assertEquals(listOf("Existing appointment"), listings)
            }
        } finally { context.deleteDatabase("livetv_guide.db") }
    }
    @Test fun aFullDatabaseLeavesTheOldGuideReadableAndCanRetryAfterSpaceReturns(): Unit = runBlocking {
        val context = StorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            EpgDatabase(context).use { db ->
                db.replaceAll("old", mapOf("ch" to listOf(EpgProgram(0, 10, "Old listing"))), emptyMap())
                val writer = db.startStage("source")
                writer.add("id", List(1536) { EpgProgram(it.toLong(), it + 1L, "Listing", "x".repeat(1800)) }) { false }
                writer.finish("ready", listOf(Triple("id", listOf("Channel"), null)))
                val sql = db.writableDatabase
                fun pragma(statement: String): Long {
                    sql.beginTransaction()
                    try {
                        val result = sql.rawQuery("PRAGMA " + statement, null).use { it.moveToFirst(); it.getLong(0) }
                        sql.setTransactionSuccessful()
                        return result
                    } finally { sql.endTransaction() }
                }
                val limit = pragma("max_page_count")
                val assignments = mapOf("ch" to EpgAssignment("source", "id"))
                try {
                    pragma("max_page_count=" + (pragma("page_count") + 48))
                    try {
                        db.replaceAllFromStageBatched("full", assignments, assignments, null, 0, 2000) {}
                        fail("Expected a real SQLite storage limit failure")
                    } catch (_: android.database.sqlite.SQLiteFullException) { }
                    assertEquals("old", db.fingerprint())
                    assertEquals("Old listing", db.program("ch", 0)?.title)
                } finally { pragma("max_page_count=" + limit) }
                db.replaceAllFromStageBatched("recovered", assignments, assignments, null, 0, 2000) {}
                assertEquals(1536, db.channelRange("ch", 0, 2000).size)
                assertEquals("recovered", db.fingerprint())
            }
        } finally { context.deleteDatabase("livetv_guide.db") }
    }
    @Test fun playlistVariantsRetainEveryListingWithOneStoredCopyPerSchedule(): Unit = runBlocking {
        val context = StorageTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            EpgDatabase(context).use { db ->
                val assignments = linkedMapOf<String, EpgAssignment>()
                val writer = db.startStage("provider")
                repeat(40) { schedule ->
                    val id = "schedule-$schedule"
                    writer.add(id, List(48) { slot ->
                        EpgProgram(slot * 1000L, (slot + 1) * 1000L, "Show $schedule/$slot", "Programme details ".repeat(18))
                    }) { false }
                    repeat(12) { assignments["playlist$it|$schedule"] = EpgAssignment("provider", id) }
                }
                writer.finish("ready", emptyList())
                fun rows() = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM programs", null).use { it.moveToFirst(); it.getInt(0) }
                val start = android.os.SystemClock.elapsedRealtime()
                db.replaceAllFromStage("legacy", assignments, emptyMap(), assignments, null, 0, 48000)
                val oldMs = android.os.SystemClock.elapsedRealtime() - start
                val oldCount = rows()
                val readOld = android.os.SystemClock.elapsedRealtime()
                assertEquals(480, db.range(0, 48000, light = true).size)
                val oldReadMs = android.os.SystemClock.elapsedRealtime() - readOld
                val next = android.os.SystemClock.elapsedRealtime()
                db.replaceAllFromStageBatched("shared", assignments, assignments, null, 0, 48000) {}
                val newMs = android.os.SystemClock.elapsedRealtime() - next
                val newCount = rows()
                val readNew = android.os.SystemClock.elapsedRealtime()
                val displayed = db.range(0, 48000, light = true)
                val newReadMs = android.os.SystemClock.elapsedRealtime() - readNew
                assertEquals(40 * 12 * 48, oldCount)
                assertEquals(40 * 48, newCount)
                assertEquals(assignments.keys, displayed.keys)
                assertTrue(displayed.values.all { it.size == 48 })
                assertTrue(displayed.getValue("playlist0|0") === displayed.getValue("playlist1|0"))
                val details = db.range(0, 48000)
                assertEquals("Programme details ".repeat(18), details.getValue("playlist1|0").first().description)
                android.util.Log.i("GuideStorageTest", "480 channels: $oldCount legacy rows / $oldMs ms, $newCount shared rows / $newMs ms; read $oldReadMs ms vs $newReadMs ms")
            }
        } finally { context.deleteDatabase("livetv_guide.db") }
    }
    @Test fun cancellationKeepsPublishedGuideAndRetryPublishesAllBatches(): Unit = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = StorageTestContext(base)
        context.deleteDatabase("livetv_guide.db")
        val db = EpgDatabase(context)
        try {
            db.replaceAll("old", mapOf("channel" to listOf(EpgProgram(1,10000,"Old"))), emptyMap())
            val writer = db.startStage("source")
            val listings = (0 until 600).map { EpgProgram(it*1000L,it*1000L+1000,"New $it") }
            for (batch in listings.chunked(64)) {
                writer.add("xml",batch) { false }
                writer.pauseBatch()
                withContext(Dispatchers.Default) { delay(1) }
                writer.resumeBatch()
            }
            writer.finishBatched("stamp",listOf(Triple("xml",listOf("Channel"),null))) { delay(1) }
            val shared = EpgAssignment("source", "xml")
            val choices = mapOf("channel" to shared, "variant" to shared)
            var steps = 0
            try {
                db.replaceAllFromStageBatched("new",choices,choices,null,0,700000) {
                    assertEquals("old",db.fingerprint())
                    if (++steps == 3) throw CancellationException("fixture cancellation")
                    delay(1)
                }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals("old",db.fingerprint())
            assertEquals("Old",db.range(0,700000)["channel"]!!.single().title)
            repeat(2) {
                db.replaceAllFromStageBatched("new$it",choices,choices,null,0,700000) { delay(1) }
                assertEquals(600,db.range(0,700000)["channel"]!!.size)
                assertEquals("new$it",db.fingerprint())
            }
            val physicalRows = db.readableDatabase.rawQuery("SELECT COUNT(*) FROM programs", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertEquals(600, physicalRows)
            assertEquals(db.channelRange("channel", 0, 700000), db.channelRange("variant", 0, 700000))
            val nowOn = mutableSetOf<String>()
            db.visitCurrentPrograms(750, choices.keys) { ch, show -> if (show.title == "New 0") nowOn += ch }
            assertEquals(choices.keys, nowOn)
            assertEquals(choices.keys, db.findByWords(0, 1000, 500, listOf("New")).map { it.first }.toSet())
            db.mergeChannelArchive("channel", listOf(EpgProgram(0, 1000, "Catch-up")))
            assertEquals("Catch-up", db.program("channel", 0)?.title)
            assertEquals("New 0", db.program("variant", 0)?.title)
            assertEquals("New 1", db.program("channel", 1000)?.title)
            db.deleteChannels(listOf("channel"))
            assertEquals(600, db.channelRange("variant", 0, 700000).size)
            // Turning the setting off and on must also work after either index-name generation.
            repeat(2) {
                db.replaceAllFromStageBatched("batched$it",choices,choices,null,0,700000) { delay(1) }
                db.replaceAllFromStage("legacy$it",choices,emptyMap(),choices,null,0,700000)
                db.replaceAllFromStageBatched("again$it",choices,choices,null,0,700000) { delay(1) }
                assertEquals(600,db.range(0,700000)["channel"]!!.size)
                assertEquals("again$it",db.fingerprint())
            }
        } finally { db.close(); context.deleteDatabase("livetv_guide.db") }
    }
}

