package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** One queue per native handle. A slow command cannot hold up input or the next player's queue. */
internal class MpvCommandQueue {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Nuvio-mpv").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    @Volatile private var closed = false

    @Synchronized fun execute(command: () -> Unit) {
        if (!closed) executor.execute { if (!closed) command() }
    }

    suspend fun <T> result(command: () -> T): T? =
        withContext(dispatcher) { if (closed) null else command() }

    @Synchronized fun close(release: () -> Unit) {
        if (closed) return
        closed = true
        executor.execute(release)
        dispatcher.close()
    }
}
