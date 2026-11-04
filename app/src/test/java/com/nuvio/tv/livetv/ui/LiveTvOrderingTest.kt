package com.nuvio.tv.livetv.ui

import org.junit.Assert.*
import org.junit.Test

class LiveTvOrderingTest {
    private val order = listOf("A", "B", "C", "D", "E", "F")
    @Test fun `non adjacent selection becomes stable block moving up`() {
        assertEquals(listOf("B", "D", "A", "C", "E", "F"), moveOrderedItems(order, linkedSetOf("D", "B"), OrderMove.UP))
    }
    @Test fun `non adjacent selection becomes stable block moving down`() {
        assertEquals(listOf("A", "C", "E", "B", "D", "F"), moveOrderedItems(order, setOf("D", "B"), OrderMove.DOWN))
    }
    @Test fun `top and bottom retain selection and remainder order`() {
        assertEquals(listOf("B", "D", "A", "C", "E", "F"), moveOrderedItems(order, setOf("B", "D"), OrderMove.TOP))
        assertEquals(listOf("A", "C", "E", "F", "B", "D"), moveOrderedItems(order, setOf("B", "D"), OrderMove.BOTTOM))
    }
    @Test fun `empty stale and complete selections are safe`() {
        OrderMove.entries.forEach {
            assertEquals(order, moveOrderedItems(order, emptySet(), it))
            assertEquals(order, moveOrderedItems(order, setOf("gone"), it))
            assertEquals(order, moveOrderedItems(order, order.toSet(), it))
            assertEquals(emptyList<String>(), moveOrderedItems(emptyList(), setOf("gone"), it))
        }
    }
    @Test fun `single item boundaries do not wrap`() {
        assertEquals(order, moveOrderedItems(order, setOf("A"), OrderMove.UP))
        assertEquals(order, moveOrderedItems(order, setOf("F"), OrderMove.DOWN))
    }
    @Test fun `moving one playlist leaves other playlists slots untouched`() {
        assertEquals(listOf("C", "X", "A", "Y", "B"), replaceOrderScope(listOf("A", "X", "B", "Y", "C"), listOf("C", "A", "B")))
    }
    @Test fun `favourite in collapsed playlist stays reachable without duplicate rows`() {
        val groups = listOf(
            com.nuvio.tv.livetv.model.ChannelGroup("a", "Sports", 1, sourceId = "one", favorite = true),
            com.nuvio.tv.livetv.model.ChannelGroup("b", "News", 1, sourceId = "one"),
            com.nuvio.tv.livetv.model.ChannelGroup("c", "Sports", 1, sourceId = "two")
        )
        val overlay = overlayGroupRows(groups, true, setOf("one"))
        assertEquals(listOf(0, -1, -1, 2), overlay.map { it.group })
        assertEquals(listOf(0, -1, 1), overlayGroupRows(groups.filterNot { it.id == "b" }, true, setOf("one")).map { it.group })
        assertEquals(0, groupListIndex(groups, "a", true, setOf("one")))
        assertEquals(-1, groupListIndex(groups, "b", true, setOf("one")))
        assertEquals(3, groupListIndex(groups, "c", true, setOf("one")))
        assertEquals(1, groupListIndex(groups, "b", false, setOf("one")))
    }
    @Test fun `all subsets and directions preserve membership and relative order`() {
        for (mask in 0 until (1 shl order.size)) {
            val selected = order.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            for (direction in OrderMove.entries) {
                val result = moveOrderedItems(order, selected, direction)
                assertEquals(order.size, result.size)
                assertEquals(order.toSet(), result.toSet())
                assertEquals(order.filter { it in selected }, result.filter { it in selected })
                assertEquals(order.filterNot { it in selected }, result.filterNot { it in selected })
                val indices = result.indices.filter { result[it] in selected }
                if (indices.isNotEmpty()) assertEquals(indices.size, indices.last() - indices.first() + 1)
            }
        }
    }
}
