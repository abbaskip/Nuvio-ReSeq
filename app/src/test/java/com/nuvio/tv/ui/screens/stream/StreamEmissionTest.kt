package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import org.junit.Assert.*
import org.junit.Test

class StreamEmissionTest {
    @Test fun preservesOrderLatestStreamFieldsAndSourceSpecificBadges() {
        val original = Stream("1080p", "Old title", null, "http://localhost/movie", null, null,
            null, null, null, "First", null, badges = listOf(StreamBadge("Cached")))
        val incoming = original.copy(title = "Updated title", badges = emptyList())
        val other = incoming.copy(addonName = "Second")
        val fresh = incoming.copy(badges = listOf(StreamBadge("Fresh")))
        val result = prepareStreamEmission(listOf(AddonStreams("Second", null, listOf(other)),
            AddonStreams("First", null, listOf(incoming, fresh))), listOf("First", "Second"), emptySet(),
            listOf(original)) { streams -> streams.first() }
        assertEquals(listOf("First", "Second"), result.groups.map { it.addonName })
        assertEquals(listOf(incoming.copy(badges = original.badges), fresh, other), result.streams)
        assertSame(result.streams.first(), result.selection)
    }
}
