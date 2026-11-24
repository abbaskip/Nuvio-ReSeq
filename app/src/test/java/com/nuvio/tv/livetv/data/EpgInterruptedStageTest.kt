package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class EpgInterruptedStageTest {
    private val context=RuntimeEnvironment.getApplication()
    private var db=EpgDatabase(context)
    @After fun close() { db.close(); context.deleteDatabase("livetv_guide.db"); java.io.File(context.filesDir,"guide-stage-in-progress").delete() }
    private fun stagedRows()=db.readableDatabase.rawQuery("SELECT count(*) FROM stage",null).use { it.moveToFirst();it.getLong(0) }
    @Test fun `missing titles occupy no programme rows and maintenance removes legacy copies`() = kotlinx.coroutines.runBlocking {
        val gap = EpgProgram(10, 20, "No Program Guide Available")
        val show = EpgProgram(20, 30, "Paid Programming")
        val incoming = listOf(gap, show, gap.copy(startMs = 35, stopMs = 45, title = " No Information. "))
        val writer = db.startStage("provider")
        writer.add("station", incoming) { false }
        writer.finish("stamp", emptyList())
        assertEquals(1L, stagedRows())
        val assignment = mapOf("channel" to com.nuvio.tv.livetv.model.EpgAssignment("provider", "station"))
        db.replaceAllFromStageBatched("guide", assignment, assignment, null, 0, 80, 15) {}
        db.mergeChannelArchive("channel", listOf(gap.copy(startMs = 21, stopMs = 25)))
        assertEquals(listOf(show), db.channelRange("channel", 0, 80))

        val sql = db.writableDatabase
        sql.execSQL("INSERT INTO stage(src,xid,start,stop,title) VALUES ('provider','station',10,20,'NO PROGRAMME GUIDE AVAILABLE.')")
        sql.execSQL("INSERT INTO programs(ch,start,stop,title) VALUES ('channel',10,20,'No Program Guide Available')")
        db.compact()
        db.compact()
        assertEquals(1L, stagedRows())
        val count = sql.rawQuery("SELECT COUNT(*) FROM programs", null).use { it.moveToFirst(); it.getLong(0) }
        assertEquals(1L, count)
        assertEquals(listOf(show), db.channelRange("channel", 0, 80))
    }
    @Test fun `reopen removes committed partial batches while retaining the previous published guide`() {
        val old=EpgProgram(100,300,"Published guide")
        db.replaceAll("committed",mapOf("old" to listOf(old)),emptyMap())
        val writer=db.startStage("interrupted")
        writer.add("working",List(50_001) { old.copy(title="New $it") }) { false }
        writer.pauseBatch()
        assertEquals(50_001,stagedRows())
        db.close() // Process death: abort/finally did not run.
        db=EpgDatabase(context)
        assertEquals(0,stagedRows())
        assertEquals("committed",db.fingerprint())
        assertEquals("Published guide",db.channelRange("old",0,500).single().title)
        db.close(); db=EpgDatabase(context)
        assertEquals(0,stagedRows())
    }
    @Test fun `abort removes prior committed batches and closes the working transaction`() {
        val writer=db.startStage("failed")
        writer.add("working",List(50_001) { EpgProgram(it.toLong(),it+1L,"Programme") }) { false }
        writer.pauseBatch(); writer.abort()
        assertEquals(0,stagedRows())
        assertFalse(db.writableDatabase.inTransaction())
        val retry=db.startStage("retry")
        retry.add("channel",listOf(EpgProgram(1,2,"Success"))) { false }
        retry.finish("stamp",emptyList())
        assertEquals(1,stagedRows())
    }

    @Test fun `storage full in a later batch preserves the committed guide and cleans the earlier batch`() {
        val old=EpgProgram(100,300,"Published guide")
        // max_page_count is per connection in the JVM backend. Pin this fault injection to
        // its single primary connection; device verification exercises WAL under real ENOSPC.
        db.writableDatabase.disableWriteAheadLogging()
        db.replaceAll("published",mapOf("old" to listOf(old)),emptyMap())
        val writer=db.startStage("full")
        writer.add("working",List(50_001) { old }) { false }
        writer.pauseBatch()
        val pages=db.writableDatabase.rawQuery("PRAGMA page_count",null).use { it.moveToFirst();it.getLong(0) }
        db.writableDatabase.rawQuery("PRAGMA max_page_count=${pages+32}",null).use { it.moveToFirst() }
        var failure:Throwable?=null
        try {
            writer.resumeBatch()
            writer.add("large",List(1000) { old.copy(description="x".repeat(8192)) }) { false }
        } catch(error:Throwable) { failure=error } finally { writer.abort() }
        assertTrue(failure.toString(),failure is android.database.sqlite.SQLiteFullException)
        db.writableDatabase.rawQuery("PRAGMA max_page_count=2147483647",null).use { it.moveToFirst() }
        assertEquals(0,stagedRows())
        assertEquals("published",db.fingerprint())
        assertEquals("Published guide",db.channelRange("old",0,500).single().title)
        db.close(); db=EpgDatabase(context)
        assertEquals(0,stagedRows())
    }

    @Test fun `three days of aired listings survive a rolling refresh and reopen`() = kotlinx.coroutines.runBlocking {
        val hour = 3_600_000L
        val now = 100 * hour
        val assignment = com.nuvio.tv.livetv.model.EpgAssignment("provider", "station")
        val original = linkedMapOf("main" to assignment, "alias" to assignment, "removed" to assignment)
        fun show(start: Int, end: Int, title: String) = EpgProgram(start * hour, end * hour, title)
        fun stage(shows: List<EpgProgram>) {
            db.startStage("provider").apply {
                add("station", shows) { false }
                finish("stamp", emptyList())
            }
        }
        stage(listOf(show(25, 26, "Expired"), show(27, 29, "At the cutoff"), show(60, 61, "Saved"),
            show(90, 91, "Old title"), show(110, 111, "Withdrawn future")))
        db.replaceAllFromStageBatched("first", original, original, null, 0, now + 24 * hour) {}
        db.mergeChannelArchive("main", listOf(show(40, 41, "Archive A"), show(60, 61, "Archive correction"), show(70, 71, "Archive B")))
        val current = linkedMapOf("alias" to assignment, "main" to assignment)
        stage(listOf(show(60, 61, "No Program Guide Available"), show(89, 91, "Corrected times"), show(115, 116, "Fresh future")))
        repeat(2) {
            db.replaceAllFromStageBatched("refresh", current, current, null, now - 72 * hour, now + 24 * hour, now) {}
            db.close()
            db = EpgDatabase(context)
            fun titles(key: String) = db.channelRange(key, 0, now + 24 * hour).map { it.title }
            assertEquals(listOf("At the cutoff", "Saved", "Corrected times", "Fresh future"), titles("alias"))
            assertEquals(listOf("At the cutoff", "Archive A", "Archive correction", "Archive B", "Corrected times", "Fresh future"), titles("main"))
            assertTrue(titles("removed").isEmpty())
        }
    }

    @Test fun `upgrade carries legacy shared history and only active archive channels`() = kotlinx.coroutines.runBlocking {
        val assignment = com.nuvio.tv.livetv.model.EpgAssignment("provider", "station")
        val choices = mapOf("channel" to assignment)
        db.replaceAll("legacy", mapOf("channel" to listOf(EpgProgram(10, 20, "Legacy guide"))), choices)
        db.writableDatabase.execSQL("UPDATE programs SET shared=1 WHERE ch='channel'")
        db.writableDatabase.execSQL("INSERT INTO schedule_channels(ch,ref) VALUES ('channel','channel')")
        for (key in listOf("archive", "hidden")) db.mergeChannelArchive(key, listOf(EpgProgram(15, 25, key)))
        db.startStage("provider").apply {
            add("station", listOf(EpgProgram(40, 50, "New guide"))) { false }
            finish("stamp", emptyList())
        }
        db.replaceAllFromStageBatched("refresh", choices, choices, null, 0, 60, 30, setOf("archive")) {}
        assertEquals(listOf("Legacy guide", "New guide"), db.channelRange("channel", 0, 60).map { it.title })
        assertEquals("archive", db.channelRange("archive", 0, 60).single().title)
        assertTrue(db.channelRange("hidden", 0, 60).isEmpty())
    }

    @Test fun `large aborted staging returns storage close to a fresh committed guide`() {
        db.replaceAll("published",mapOf("old" to List(1000) { EpgProgram(it*1000L,it*1000L+500,"Saved $it") }),emptyMap())
        db.compact()
        val file=java.io.File(db.writableDatabase.path)
        val fresh=file.length()
        val writer=db.startStage("large")
        val description="x".repeat(512)
        writer.add("working",List(50_001) { EpgProgram(it*1000L,it*1000L+500,"Working $it",description) }) { false }
        writer.pauseBatch()
        assertTrue(file.length()>fresh+8*1024*1024)
        writer.abort()
        assertEquals(0,stagedRows())
        assertTrue("fresh=$fresh recovered=${file.length()}",file.length()<=fresh*1.10+4096)
        assertEquals("published",db.fingerprint())
        assertEquals(1000,db.channelRange("old",0,1_000_000).size)
    }

    @Test fun `interrupted publication discards its working copy and retains completed input for retry`() = kotlinx.coroutines.runBlocking {
        db.replaceAll("published",mapOf("old" to listOf(EpgProgram(1,3,"Old"))),emptyMap())
        val writer=db.startStage("complete")
        writer.add("channel",listOf(EpgProgram(1,3,"New"))) { false }
        writer.finish("stamp",emptyList())
        val assignment=com.nuvio.tv.livetv.model.EpgAssignment("complete","channel")
        val error=runCatching { db.replaceAllFromStageBatched("new",mapOf("new" to assignment),emptyMap(),null,0,5) {
            throw kotlinx.coroutines.CancellationException("fixture interruption")
        } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(1L, stagedRows())
        db.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE name='guide_next'", null).use {
            assertFalse(it.moveToFirst())
        }
        assertEquals("published",db.fingerprint())
        assertEquals("Old",db.channelRange("old",0,5).single().title)
        db.close()
        db = EpgDatabase(context)
        db.replaceAllFromStageBatched("retried", mapOf("new" to assignment), emptyMap(), null, 0, 5) {}
        assertEquals("retried", db.fingerprint())
        assertEquals("New", db.channelRange("new", 0, 5).single().title)
    }
}
