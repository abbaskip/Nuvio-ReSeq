package com.nuvio.tv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Deduplicate requests without letting abandoned screen work outlive its last caller. */
internal class SharedMetadataRequests<T>(private val scope: CoroutineScope) {
    private class Load<T>(val result: Deferred<T>, var waiters: Int = 0)
    private val active = mutableMapOf<String, Load<T>>()

    suspend fun await(key: String, fetch: suspend () -> T): T {
        val load = synchronized(active) {
            active.getOrPut(key) { Load(scope.async(start = CoroutineStart.LAZY) { fetch() }) }
                .also { it.waiters++ }
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

    fun clear() = synchronized(active) {
        val removed = active.values.toList()
        active.clear()
        removed.forEach { it.result.cancel() }
    }
}
