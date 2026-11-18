package com.nuvio.tv.data.cw

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.*
import org.junit.Test

class CwCardPublicationTest {
    @Test fun positiveFinishedSeriesEvidenceClearsAnAlreadySettledCardWithoutChangingHistory() {
        for (preference in listOf(null, false, true)) {
            val owner = CwCardPublication()
            val watched = WatchProgress("show", "series", "Show", null, null, null,
                "show:1:1", 1, 1, "Episode 1", 100, 100, 1000)
            val member = CwMember(progress = watched, nextUp = watched.seriesChoice().copy(
                videoId = "show:1:2", episode = 2, chooseEpisode = false), changedAtRevision = 1)
            val initial = CwProjection(profileId = 1, source = "TRAKT", revision = 1,
                members = mapOf("show" to member))
            val lease = preference?.let { owner.beginNextUp(initial.lease, it) } ?: initial.lease
            val shown = owner.settle(initial, lease, setOf("show"))
            owner.committed(shown, lease, setOf("show"))
            assertNotNull(shown.forPresentation().members.getValue("show").nextUp)
            val finished = shown.copy(revision = 2,
                members = mapOf("show" to member.copy(nextUp = null, changedAtRevision = 2)))
            val result = owner.settle(finished, finished.lease, setOf("show"))
            assertEquals(initial.completed, result.completed)
            assertEquals(watched, result.members.getValue("show").progress)
            assertNull(result.forPresentation().members.getValue("show").nextUp)
        }
    }
}
