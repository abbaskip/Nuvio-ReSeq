package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.storage.StorageTestContext
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.model.EpgAssignment
import com.nuvio.tv.livetv.model.EpgProgram
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GuideBatchTest {
    @Test fun cancellationKeepsPublishedGuideAndRetryPublishesAllBatches() = runBlocking {
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
            val choices = mapOf("channel" to EpgAssignment("source","xml"))
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

