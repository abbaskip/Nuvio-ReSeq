package com.nuvio.tv.core.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** Disabled by default, no retained events, jobs, threads or disk writes. Shell DUMP permission
 * is the hidden toggle. Events contain identifiers/reasons, never tokens or network payloads. */
object StabilityDiagnostics {
    @Volatile var enabled = false
    fun event(kind: String, fields: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        Log.i("NuvioStability", JSONObject(fields).put("kind", kind)
            .put("elapsedMs", SystemClock.elapsedRealtime()).put("wallMs", System.currentTimeMillis()).toString())
    }
    fun cw(id: String, reason: String) { if (enabled) event("cw", mapOf("show" to id, "reason" to reason.take(512))) }
    fun pool(name: String, executor: ThreadPoolExecutor) { if (enabled) event("pool", mapOf(
        "pool" to name, "active" to executor.activeCount, "queued" to executor.queue.size,
        "completed" to executor.completedTaskCount, "size" to executor.poolSize)) }
    fun fixedPool(name: String, count: Int, factory: ThreadFactory): ThreadPoolExecutor =
        object : ThreadPoolExecutor(count, count, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), factory) {
            private val saturatedSubmissions = java.util.concurrent.atomic.AtomicLong()
            override fun execute(task: Runnable) {
                if (enabled && activeCount >= maximumPoolSize) {
                    event("pool-saturation", mapOf("pool" to name, "submissions" to saturatedSubmissions.incrementAndGet(),
                        "queued" to queue.size))
                }
                super.execute(task)
            }
            override fun beforeExecute(thread: Thread, task: Runnable) {
                super.beforeExecute(thread, task)
                pool(name, this)
            }
            override fun afterExecute(task: Runnable, error: Throwable?) {
                try { pool(name, this) } finally { super.afterExecute(task, error) }
            }
        }
}

class StabilityDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        StabilityDiagnostics.enabled = intent.getBooleanExtra("enabled", false)
        StabilityDiagnostics.event("toggle", mapOf("enabled" to true))
    }
}
