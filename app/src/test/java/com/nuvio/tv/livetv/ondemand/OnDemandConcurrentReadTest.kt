package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.player.PlayerSessionTracker
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OnDemandConcurrentReadTest {
    private fun item(id: Int) = VodItem("test", VodKind.MOVIE, "$id", "Film $id", null,
        "a", "mp4", null, null, null, 0)

    @Test fun `browsing remains available while import waits for its next network page`() =
        withPausedImport(false)

    @Test fun `browsing remains available while committed import yields to a trailer`() =
        withPausedImport(true)

    @Test fun `separate imports still serialize their bookkeeping`() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        val db = OnDemandDatabase(context)
        val workers = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        try {
            val first = workers.submit<Int> {
                db.replace("test", VodKind.MOVIE, listOf("a" to "Movies")) { emit ->
                    firstEntered.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    emit(item(1))
                    true
                }
            }
            assertTrue(firstEntered.await(10, TimeUnit.SECONDS))
            val second = workers.submit<Int> {
                secondStarted.countDown()
                db.replace("other", VodKind.MOVIE, listOf("a" to "Movies")) { emit ->
                    secondEntered.countDown()
                    emit(item(2).copy(playlistId = "other"))
                    true
                }
            }
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            assertFalse("Second producer must wait for the first import", secondEntered.await(200, TimeUnit.MILLISECONDS))
            resume.countDown()
            assertEquals(1, first.get(10, TimeUnit.SECONDS))
            assertEquals(1, second.get(10, TimeUnit.SECONDS))
            assertEquals(2, db.count(VodKind.MOVIE))
        } finally {
            resume.countDown()
            workers.shutdown()
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS))
            db.close()
        }
    }

    private fun withPausedImport(trailer: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("livetv_on_demand.db")
        val db = OnDemandDatabase(context)
        val workers = Executors.newFixedThreadPool(2)
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val session = if (trailer) PlayerSessionTracker.openTrailer() else null
        try {
            // The existing catalog must remain readable as the replacement is imported.
            db.writableDatabase
            val importer = workers.submit<Int> {
                db.replace("test", VodKind.MOVIE, listOf("a" to "Movies"),
                    onBatchCommitted = { if (trailer) paused.countDown() }) { emit ->
                    repeat(500) { emit(item(it)) }
                    if (!trailer) {
                        paused.countDown()
                        check(resume.await(10, TimeUnit.SECONDS))
                    }
                    false // Partial response must preserve the committed batch.
                }
            }
            assertTrue("Import reached the pause", paused.await(10, TimeUnit.SECONDS))
            val reader = workers.submit<List<VodItem>> {
                db.items(VodKind.MOVIE, null, emptySet(), false, 20, 0)
            }
            assertEquals(20, reader.get(2, TimeUnit.SECONDS).size)
            assertFalse("Import should still be paused", importer.isDone)
            session?.close()
            resume.countDown()
            assertEquals(500, importer.get(10, TimeUnit.SECONDS))
            assertEquals(500, db.count(VodKind.MOVIE))
        } finally {
            session?.close()
            resume.countDown()
            workers.shutdown()
            assertTrue("Workers stopped", workers.awaitTermination(15, TimeUnit.SECONDS))
            db.close()
        }
    }
}
