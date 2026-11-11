package com.nuvio.tv.livetv.ui

import com.nuvio.tv.livetv.model.EpgChannelEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgPickerRegressionTest {
    @Test fun lateExactNamesOutrankPartialMatchesWhileInputOrderAndLimitArePreserved() {
        val rows = List(1200) { index ->
            EpgChannelEntry("channel-$index", listOf("Local Sports $index",
                if (index % 4 == 2) "SPORTS" else "Alternate $index"), null)
        }
        for (query in listOf("", "sports", "  spOrts ", "channel 72", "Local 1", "unmatched")) {
            val words = query.lowercase().trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            val expected = if (words.isEmpty()) rows.take(300) else rows.filter { channel ->
                val text = (channel.names + channel.id).joinToString(" ").lowercase()
                words.all { it in text }
            }.sortedByDescending { channel -> channel.names.any { it.equals(query.trim(), true) } }.take(300)
            assertEquals(expected, filterEpgChannels(rows, query))
        }
    }
}
