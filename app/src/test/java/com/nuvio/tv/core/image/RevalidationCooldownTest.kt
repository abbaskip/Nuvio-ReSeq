package com.nuvio.tv.core.image

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RevalidationCooldownTest {
    @Test fun cooldownExpiresFromCompletionAndReadsDoNotExtendIt() {
        var now = 0L
        val cooldown = RevalidationCooldown(600_000, nowMs = { now })
        assertFalse(cooldown.contains("logo"))
        cooldown.record("logo")
        now = 599_999
        assertTrue(cooldown.contains("logo"))
        now = 600_000
        assertFalse(cooldown.contains("logo"))
        cooldown.record("logo")
        assertTrue(cooldown.contains("logo"))
    }

    @Test fun refreshedCompletionRetainsWriteOrderAndStorageIsBounded() {
        var now = 0L
        val cooldown = RevalidationCooldown(100, 2) { now }
        cooldown.record("first")
        now = 10
        cooldown.record("second")
        now = 20
        cooldown.record("first")
        cooldown.record("third")
        assertTrue(cooldown.contains("first"))
        assertTrue(cooldown.contains("third"))
        assertFalse(cooldown.contains("second"))
        now = 120
        assertFalse(cooldown.contains("first"))
        assertFalse(cooldown.contains("third"))
    }

    @Test fun parallelImageCompletionsAndExpiryDoNotCrashOrLoseTheLatestCompletion() {
        val now = AtomicLong()
        val cooldown = RevalidationCooldown(100, 128, now::get)
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map { worker -> workers.submit {
                repeat(2_000) { i ->
                    now.incrementAndGet()
                    val url = "logo-${(i + worker) % 64}"
                    cooldown.record(url)
                    cooldown.contains(url)
                }
            } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            cooldown.record("latest")
            assertTrue(cooldown.contains("latest"))
            now.addAndGet(100)
            assertFalse(cooldown.contains("latest"))
        } finally {
            workers.shutdownNow()
        }
    }
}
