package com.nuvio.tv.livetv.ui

import org.junit.Assert.*
import org.junit.Test

class LiveTvOrderingTest {
    @Test fun `adding another playlist retains favourite and order without selecting its same named group`() {
        val saved = groupPreferenceKey("Sport", "one")
        val user = com.nuvio.tv.livetv.model.LiveUserState(
            favoriteGroups = setOf(saved), groupOrder = listOf(saved),
            channelOrder = mapOf(saved to listOf("channel-b", "channel-a")))
        val original = com.nuvio.tv.livetv.model.ChannelGroup("one::Sport", "Sport", 2, sourceId = "one")
        val second = original.copy(id = "two::Sport", sourceId = "two")
        assertEquals(saved, groupPreferenceKey(original.id, original.sourceId))
        org.junit.Assert.assertTrue(user.isFavoriteGroup(original))
        org.junit.Assert.assertFalse(user.isFavoriteGroup(second))
        assertEquals(listOf("channel-b", "channel-a"), user.channelOrderFor(original.id, original.sourceId))
        assertEquals(null, user.channelOrderFor(second.id, second.sourceId))
        assertEquals(saved, groupPreferenceKey("Sport", "one"))
    }

    @Test fun `legacy custom orders remain readable and built in scopes are unchanged`() {
        val legacy = com.nuvio.tv.livetv.model.LiveUserState(channelOrder = mapOf("Sport" to listOf("old")))
        assertEquals(listOf("old"), legacy.channelOrderFor("Sport", "one"))
        assertEquals("__fav__", groupPreferenceKey("__fav__", "one"))
        assertEquals("custom:mine", groupPreferenceKey("custom:mine", "one"))
    }
    @Test fun providerRanksRespectSourceSavedMovesAndAlphabeticalMode() {
        fun group(source: String?, id: String, title: String) =
            com.nuvio.tv.livetv.model.ChannelGroup(id, title, 1, sourceId = source)
        val groups = listOf(
            group(null, "custom:1", "Mine"), group("a", "a::Late", "Late"),
            group("a", "a::News", "Renamed"), group("a", "a::Sport", "Sport"),
            group("a", "a::Other", "Other"), group("b", "b::Sport", "Sport"), group("b", "b::News", "News")
        )
        val ranks = mapOf("a::Sport" to 0, "a::News" to 1, "b::News" to 0, "b::Sport" to 1)
        val defaults = listOf("custom:1", "a::Sport", "a::News", "a::Late", "a::Other", "b::News", "b::Sport")
        fun ids(mode: String, saved: List<String> = emptyList()) =
            orderLiveGroups(groups, saved, mode, true, ranks).map { it.id }
        assertEquals(defaults, ids("playlist", listOf("a::Other")))
        assertEquals(defaults, ids("custom"))
        assertEquals(listOf("a::Other") + defaults.minus("a::Other"), ids("custom", listOf("a::Other")))
        assertEquals(listOf("custom:1", "a::Late", "a::Other", "a::News", "a::Sport", "b::News", "b::Sport"), ids("name"))
        assertEquals(groups, orderLiveGroups(groups, emptyList(), "playlist", true, emptyMap()))
        val single = listOf(group("a", "News", "News"), group("a", "Sport", "Sport"))
        assertEquals(single, orderLiveGroups(single, listOf("a::News"), "custom", true, mapOf("Sport" to 0, "News" to 1)))
    }

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
