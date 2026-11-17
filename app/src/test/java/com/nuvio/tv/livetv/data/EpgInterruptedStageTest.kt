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

    @Test fun `interrupted publication removes complete staging too and retains the committed guide`() = kotlinx.coroutines.runBlocking {
        db.replaceAll("published",mapOf("old" to listOf(EpgProgram(1,3,"Old"))),emptyMap())
        val writer=db.startStage("complete")
        writer.add("channel",listOf(EpgProgram(1,3,"New"))) { false }
        writer.finish("stamp",emptyList())
        val assignment=com.nuvio.tv.livetv.model.EpgAssignment("complete","channel")
        val error=runCatching { db.replaceAllFromStageBatched("new",mapOf("new" to assignment),emptyMap(),null,0,5) {
            throw kotlinx.coroutines.CancellationException("fixture interruption")
        } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertEquals(0,stagedRows())
        assertEquals("published",db.fingerprint())
        assertEquals("Old",db.channelRange("old",0,5).single().title)
    }
}
