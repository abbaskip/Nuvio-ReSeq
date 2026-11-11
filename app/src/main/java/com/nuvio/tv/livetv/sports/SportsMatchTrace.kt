package com.nuvio.tv.livetv.sports

import android.util.Log

/** Opt-in, bounded trigger diagnostics. No channel URLs, programme text or credentials. */
internal class SportsMatchTrace {
    private val previous = mutableMapOf<String, Any?>()
    private val pending = linkedSetOf<String>()
    private val enabled get() = Log.isLoggable(TAG, Log.DEBUG)

    @Synchronized fun observe(name: String, value: Any?) {
        if (!enabled) return
        if (!previous.containsKey(name) || previous[name] != value) {
            pending += if (value is Boolean || value is Int) "$name=$value" else name
            previous[name] = value
        }
    }

    @Synchronized fun fixtures(keys: List<String>) {
        if (!enabled) return
        @Suppress("UNCHECKED_CAST") val old = previous["fixtures"] as? List<String>
        if (old == keys) return
        fun index(list: List<String>) = list.associateBy { it.substringBefore('@') }
        val before = index(old.orEmpty())
        val after = index(keys)
        val changes = (before.keys + after.keys).filter { before[it] != after[it] }.take(12).map { id ->
            val a = before[id]?.substringAfter('@')?.split(':')
            val b = after[id]?.substringAfter('@')?.split(':')
            val fields = if (a == null) "added" else if (b == null) "removed" else
                listOf("start", "broadcasts", "coverage").filterIndexed { i, _ -> a.getOrNull(i) != b.getOrNull(i) }.joinToString("+")
            "$id:$fields"
        }
        pending += "fixtures(count=${keys.size},order=${old != null && before == after},changes=$changes)"
        previous["fixtures"] = keys
    }

    @Synchronized fun begin() {
        if (!enabled) return
        Log.d(TAG, "runMatch inputs=${pending.joinToString(";").take(1800)}")
        pending.clear()
    }

    fun end(completed: Boolean) { if (enabled) Log.d(TAG, "runMatch completed=$completed") }

    companion object { private const val TAG = "SportsMatchTrigger" }
}
