package com.nuvio.tv.core.debrid

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalDebridServiceTest {
    private val canonical = "7db44b3713094e622246b715de410bb9b6d91230"
    private val requested = "b72ca5570c56c2387526b6aab184f0cf0075cff0"

    @Test
    fun `exact matches stay untouched without extra requests`() = runTest {
        val cached = mapOf("abc123" to LocalDebridCachedItem(name = "exact.mkv", size = 1L))

        val resolved = resolveTorboxAliasMatches(cached, listOf("abc123")) {
            error("no extra request expected")
        }

        assertEquals(cached, resolved)
    }

    @Test
    fun `a single aliased answer maps back to the requested hash`() = runTest {
        val item = LocalDebridCachedItem(name = "aliased.mkv", size = 2L)

        val resolved = resolveTorboxAliasMatches(mapOf(canonical to item), listOf(requested)) {
            error("no extra request expected")
        }

        assertEquals(mapOf(requested to item), resolved)
    }

    @Test
    fun `only the hashes missing from a batch are asked again`() = runTest {
        val exact = LocalDebridCachedItem(name = "exact.mkv", size = 3L)
        val aliased = LocalDebridCachedItem(name = "aliased.mkv", size = 2L)
        val asked = mutableListOf<String>()

        val resolved = resolveTorboxAliasMatches(
            cached = mapOf("abc123" to exact, canonical to aliased),
            requestedHashes = listOf("abc123", requested)
        ) { hash ->
            asked += hash
            mapOf(canonical to aliased)
        }

        assertEquals(listOf(requested), asked)
        assertEquals(mapOf("abc123" to exact, canonical to aliased, requested to aliased), resolved)
    }

    @Test
    fun `many alias probes are bounded while every requested hash is resolved`() = runTest {
        val hashes = (1..40).map { "hash-$it" }
        var active = 0
        var peak = 0
        val asked = mutableListOf<String>()
        val item = LocalDebridCachedItem(name = "cached.mkv", size = 9L)
        val result = resolveTorboxAliasMatches(mapOf(canonical to item), hashes) { hash ->
            asked += hash
            active++
            peak = maxOf(peak, active)
            try {
                delay(10)
                mapOf(canonical to item)
            } finally { active-- }
        }
        assertEquals(hashes.toSet(), asked.toSet())
        assertEquals(hashes.size, asked.size)
        assertTrue(peak in 1..4)
        hashes.forEach { assertEquals(item, result[it]) }
    }

    @Test
    fun `failed and missing probes do not discard exact matches`() = runTest {
        val item = LocalDebridCachedItem(name = "exact.mkv", size = 1L)
        val cached = mapOf("exact" to item, canonical to item)
        val result = resolveTorboxAliasMatches(cached, listOf("exact", "fails", "missing")) { hash ->
            if (hash == "fails") error("fixture failure") else emptyMap()
        }
        assertEquals(cached, result)
    }

    @Test
    fun `cancellation is propagated instead of reported as a cache miss`() = runTest {
        try {
            resolveTorboxAliasMatches(mapOf(canonical to LocalDebridCachedItem("test", 1L)),
                listOf("alias", "other")) { throw CancellationException("fixture cancellation") }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("fixture cancellation", expected.message)
        }
    }
}
