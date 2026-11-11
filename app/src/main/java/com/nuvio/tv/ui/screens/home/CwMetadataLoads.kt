package com.nuvio.tv.ui.screens.home

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The tracker pipeline and shuffle may await the same series. One cancelled waiter must
 * not restart the other's lookup. With no waiters the underlying request is cancelled. */
internal class CwMetadataLoads {
    private data class Key(val profile: Int, val type: String, val id: String)
    private class Load(val result: Deferred<CwMetaSummary?>, var waiters: Int = 0)
    private val active = mutableMapOf<Key, Load>()
    private val slots = Semaphore(4)

    suspend fun await(scope: CoroutineScope, profile: Int, type: String, id: String,
        fetch: suspend () -> CwMetaSummary?): CwMetaSummary? {
        val key = Key(profile, type, id)
        val context = currentCoroutineContext().minusKey(Job)
        val load = synchronized(active) {
            active.getOrPut(key) {
                Load(scope.async(context, start = CoroutineStart.LAZY) { slots.withPermit { fetch() } })
            }.also { it.waiters++ }
        }
        try {
            return load.result.await()
        } finally {
            synchronized(active) {
                if (--load.waiters == 0 && active[key] === load) {
                    active.remove(key)
                    load.result.cancel()
                }
            }
        }
    }

    fun invalidate(ids: Set<String>? = null) {
        synchronized(active) {
            for (key in active.keys.filter { ids == null || it.id in ids }) {
                active.remove(key)?.result?.cancel()
            }
        }
    }
}
