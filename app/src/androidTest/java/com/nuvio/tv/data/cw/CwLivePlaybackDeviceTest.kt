package com.nuvio.tv.data.cw

import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.livetv.data.BackgroundWork
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class CwLivePlaybackDeviceTest {
    @Test fun bulkWorkResumesWithTheLatestSavedProjectionAfterLiveTvCloses() = runBlocking {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "cw-playback-test-" + UUID.randomUUID())
        val store = CwProjectionStore(directory)
        val original = CwProjection(profileId = 1, source = "TRAKT", revision = 1)
        val updated = original.copy(revision = 3, removals = mapOf("watched-title" to 1234L))
        val reads = Channel<CwProjection>(Channel.UNLIMITED)
        val stopped = CompletableDeferred<Unit>()
        store.write(original)
        val worker = launchWhenCwPlaybackIdle {
            reads.send(store.read(original.profileId, original.source))
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        }
        try {
            withTimeout(10_000) {
                assertEquals(original, reads.receive())
                BackgroundWork.liveChannelSignal.single(true)
                stopped.await()
                store.write(original.copy(revision = 2))
                store.write(updated)
                BackgroundWork.liveChannelSignal.single(false)
                assertEquals(updated, reads.receive())
            }
        } finally {
            worker.cancelAndJoin()
            BackgroundWork.liveChannelSignal.single(false)
            directory.deleteRecursively()
        }
    }
}
