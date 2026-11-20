package androidx.media3.exoplayer.upstream

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultAllocatorNativeTest {

    @Test
    fun testAllocationAndReleaseLifecycle() {
        val size = 65536
        // Allocate native memory
        val allocation = DefaultAllocatorNative.createAllocation(size)

        // Assert allocation is successful and direct
        assertNotNull("Allocation should not be null", allocation)
        assertNotNull("Allocation buffer should not be null", allocation!!.buffer)
        assertTrue("Allocation buffer must be direct", allocation.buffer!!.isDirect)
        assertEquals("Allocation buffer capacity should match size", size, allocation.buffer!!.capacity())
        assertTrue("Allocation native handle should be non-zero", allocation.nativeHandle != 0L)

        // Free allocation
        DefaultAllocatorNative.freeAllocation(allocation)

        // Verify that java-side handle is cleared immediately to prevent double-free
        assertEquals("Allocation handle should be cleared immediately", 0L, allocation.nativeHandle)

        // Let's also check allocating and freeing multiple buffers
        val allocation2 = DefaultAllocatorNative.createAllocation(32768)
        assertNotNull(allocation2)
        assertTrue(allocation2!!.buffer!!.isDirect)
        DefaultAllocatorNative.freeAllocation(allocation2)
    }

    @Test
    fun testAllocationZeroAndNegativeSize() {
        // Size 0 should return null
        val allocationZero = DefaultAllocatorNative.createAllocation(0)
        assertNull("Allocation with size 0 should be null", allocationZero)

        // Negative size should return null
        val allocationNeg = DefaultAllocatorNative.createAllocation(-100)
        assertNull("Allocation with negative size should be null", allocationNeg)
    }

    @Test
    fun testArenaPoolExhaustionAndReplenishment() {
        val segmentSize = 65536
        
        // Fetch arena field addresses via reflection
        val fieldBase = DefaultAllocatorNative::class.java.getDeclaredField("arenaBaseAddress")
        fieldBase.isAccessible = true
        
        val fieldEnd = DefaultAllocatorNative::class.java.getDeclaredField("arenaEndAddress")
        fieldEnd.isAccessible = true

        // Trigger initialization of the Arena by making one allocation
        val firstAlloc = DefaultAllocatorNative.createAllocation(segmentSize)
        assertNotNull(firstAlloc)
        DefaultAllocatorNative.freeAllocation(firstAlloc!!)

        val arenaBaseAddress = fieldBase.get(null) as Long
        val arenaEndAddress = fieldEnd.get(null) as Long

        assertTrue("Native arena must be present on the instrumented TV", arenaBaseAddress != 0L)
        val capacity = ((arenaEndAddress - arenaBaseAddress) / segmentSize).toInt()
        assertTrue("The arena uses 64 to 512 chunks", capacity in 64..512)
        val pool = DefaultAllocatorNative::class.java.getDeclaredField("arenaPool").run {
            isAccessible = true
            get(null) as java.util.Queue<*>
        }
        val available = pool.size
        assertTrue("Arena should have free chunks", available in 1..capacity)

        val owned = mutableListOf<Allocation>()
        fun allocate(): Allocation = requireNotNull(DefaultAllocatorNative.createAllocation(segmentSize)).also { owned.add(it) }
        try {
            repeat(available) {
                assertTrue("Expected an arena chunk", allocate().nativeHandle in arenaBaseAddress until arenaEndAddress)
            }
            assertTrue("Overflow must use a dynamic allocation", allocate().nativeHandle !in arenaBaseAddress until arenaEndAddress)
            val returned = owned.removeAt(0)
            val address = returned.nativeHandle
            DefaultAllocatorNative.freeAllocation(returned)
            assertEquals("Expected the returned arena chunk", address, allocate().nativeHandle)
        } finally {
            owned.forEach(DefaultAllocatorNative::freeAllocation)
        }
        assertEquals("Restore the pool for subsequent tests", available, pool.size)
    }
}
