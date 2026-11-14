package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.data.local.EpisodeShuffleProfile
import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import com.nuvio.tv.domain.model.EpisodeShuffle
import com.nuvio.tv.domain.model.EpisodeShuffleSettings
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeEpisodeShuffleTest {
    @Test fun `shuffle fallback cannot resurrect removed members or cross profiles`() {
        val p = WatchProgress("show", "series", "Show", null, null, null, "show:1:1", 1, 1, null, 100, 100, 1_000)
        val before = com.nuvio.tv.data.cw.CwMembershipReducer.progress(
            com.nuvio.tv.data.cw.CwProjection(profileId = 1, source = "TRAKT"), listOf(p))
        assertEquals(listOf(p), ownedShuffleSeeds(before, 1))
        assertTrue(ownedShuffleSeeds(before, 2).isEmpty())
        val removed = com.nuvio.tv.data.cw.CwMembershipReducer.remove(before, before.lease,
            com.nuvio.tv.data.cw.CwRemovalProof(com.nuvio.tv.data.cw.CwRemovalReason.USER_REMOVAL, setOf("show"), 2_000))
        assertTrue(ownedShuffleSeeds(removed, 1).isEmpty())
        val resumed = com.nuvio.tv.data.cw.CwMembershipReducer.progress(before, listOf(p.copy(position = 20, lastWatched = 3_000)))
        assertTrue(ownedShuffleSeeds(resumed, 1).isEmpty())
    }

    private val videos = (1..5).map { Video("show:1:$it", "Episode $it", "2020-01-01", "image-$it",
        season = 1, episode = it, overview = "Description $it") }
    private val upcoming = ContinueWatchingItem.NextUp(NextUpInfo(
        "show", "series", "Show", null, null, null, "show:2:1", 2, 1, "Upcoming",
        thumbnail = null, released = "2999-01-01", hasAired = false, airDateLabel = "Soon",
        lastWatched = 100, sortTimestamp = 100, isReleaseAlert = true, isNewSeasonRelease = true
    ))
    private val finishedCard = ContinueWatchingItem.NextUp(NextUpInfo(
        "show", "series", "Show", null, null, null, "show:1:5", 1, 5, null,
        thumbnail = null, lastWatched = 50, sortTimestamp = 50, seedSeason = 1, seedEpisode = 5
    ))
    private val state = HomeUiState(upcomingItems = listOf(upcoming))
    private val profile = EpisodeShuffleProfile(1, true, mapOf("show" to EpisodeShuffleSettings(true, false)))
    private val shuffle = EpisodeShuffle()

    @Test
    fun `card details and playback id describe the same aired random episode`() {
        val card = project().continueWatchingItems.single() as ContinueWatchingItem.NextUp
        val episode = videos.single { it.id == card.info.videoId }
        assertEquals(episode.title, card.info.episodeTitle)
        assertEquals(episode.overview, card.info.episodeDescription)
        assertEquals(episode.thumbnail, card.info.thumbnail)
        assertEquals(episode.episode, card.info.episode)
        assertTrue(card.info.hasAired)
        assertTrue(card.shufflePlayback)
        assertFalse(card.info.isReleaseAlert)
        assertFalse(card.info.isNewSeasonRelease)
        assertTrue(project().upcomingItems.isEmpty())
    }

    @Test
    fun `disabling shuffle restores native upcoming data`() {
        project()
        assertEquals(state, project(profile = profile.copy(available = false)))
        assertEquals(state, project(profile = profile.copy(shows = emptyMap())))
    }

    @Test
    fun `home emissions preserve selection and a new visit advances it`() {
        val first = project().continueWatchingItems.single()
        repeat(20) { assertEquals(first, project().continueWatchingItems.single()) }
        assertNotEquals(first, project(visit = 2).continueWatchingItems.single())
    }

    @Test
    fun `focus identity follows the show across random targets and resume`() {
        val first = project().continueWatchingItems.single()
        val second = project(visit = 2).continueWatchingItems.single()
        val resume = ContinueWatchingItem.InProgress(progress(), shufflePlayback = true)
        assertEquals(first.shuffleFocusKey, second.shuffleFocusKey)
        assertEquals(first.shuffleFocusKey, resume.shuffleFocusKey)
        assertEquals(continueWatchingItemKey(first), continueWatchingItemKey(second))
    }

    @Test
    fun `continue watching keeps the exact incomplete episode and position`() {
        val resume = ContinueWatchingItem.InProgress(progress())
        val projected = project(input = HomeUiState(continueWatchingItems = listOf(resume)))
        val card = projected.continueWatchingItems.single() as ContinueWatchingItem.InProgress
        assertEquals(resume.progress, card.progress)
        assertTrue(card.shufflePlayback)
    }

    @Test
    fun `latest resume replaces transient next up and older progress without duplicate focus keys`() {
        val older = ContinueWatchingItem.InProgress(progress())
        val latest = ContinueWatchingItem.InProgress(progress().copy(videoId = "show:1:4", episode = 4, lastWatched = 200))
        val input = HomeUiState(continueWatchingItems = listOf(older, latest), upcomingItems = listOf(upcoming))
        val output = project(input)
        assertEquals(listOf(latest.copy(shufflePlayback = true)), output.continueWatchingItems)
        assertTrue(output.upcomingItems.isEmpty())
    }

    @Test
    fun `exhausted unwatched pool preserves native upcoming and resume`() {
        val watched = videos.map { it.season!! to it.episode!! }.toSet()
        assertEquals(listOf(upcoming), project(watched = mapOf("show" to watched)).upcomingItems)
        val resume = ContinueWatchingItem.InProgress(progress())
        val input = HomeUiState(continueWatchingItems = listOf(resume))
        assertEquals(resume.progress, (project(input, watched = mapOf("show" to watched))
            .continueWatchingItems.single() as ContinueWatchingItem.InProgress).progress)
    }

    @Test
    fun `native upcoming survives until shuffle metadata arrives`() {
        assertEquals(listOf(upcoming), project(catalogue = null).upcomingItems)
        assertEquals(1, project().continueWatchingItems.size)
    }

    @Test
    fun `tracker replacement during a cache miss keeps the displayed random episode and badge`() {
        val selected = project().continueWatchingItems.single() as ContinueWatchingItem.NextUp
        val sequential = upcoming.copy(info = upcoming.info.copy(
            videoId = "show:1:2", season = 1, episode = 2, episodeTitle = "Sequential next episode",
            hasAired = true, released = "2020-01-01"
        ))
        val refreshed = project(HomeUiState(continueWatchingItems = listOf(sequential)), catalogue = null)
            .continueWatchingItems.single() as ContinueWatchingItem.NextUp
        assertTrue(refreshed.shufflePlayback)
        assertFalse(refreshed.chooseEpisode)
        assertEquals(selected.info.videoId, refreshed.info.videoId)
        assertEquals(selected.info.episodeTitle, refreshed.info.episodeTitle)
        assertEquals(selected.info.thumbnail, refreshed.info.thumbnail)
    }

    @Test
    fun `retained shuffle target cannot cross profile or pool changes`() {
        project()
        val otherProfile = project(profile = profile.copy(profileId = 2), catalogue = null)
        val otherPool = project(profile = profile.copy(shows = mapOf("show" to EpisodeShuffleSettings(true, true))), catalogue = null)
        assertFalse(otherProfile.upcomingItems.single().shufflePlayback)
        assertFalse(otherPool.upcomingItems.single().shufflePlayback)
    }

    @Test
    fun `removing a native card cannot be undone by the shuffle projection`() {
        project()
        assertTrue(project(HomeUiState()).continueWatchingItems.isEmpty())
    }

    @Test
    fun `movies and disabled shows preserve their native targets`() {
        val movie = ContinueWatchingItem.InProgress(progress().copy(contentType = "movie"))
        assertFalse((project(HomeUiState(continueWatchingItems = listOf(movie)))
            .continueWatchingItems.single()).shufflePlayback)
        val other = upcoming.copy(info = upcoming.info.copy(contentId = "other"))
        assertEquals(listOf(other), project(HomeUiState(upcomingItems = listOf(other))).upcomingItems)
    }

    @Test
    fun `completed watched ids exclude a target while all mode retains it`() {
        val allWatched = mapOf("show" to videos.map { it.season!! to it.episode!! }.toSet())
        val all = profile.copy(shows = mapOf("show" to EpisodeShuffleSettings(true, true)))
        assertEquals(1, project(profile = all, watched = allWatched).continueWatchingItems.size)
    }

    @Test
    fun `finished show with shuffle on returns with a random episode`() {
        val card = project(HomeUiState(), finished = listOf(finishedCard)).continueWatchingItems.single()
            as ContinueWatchingItem.NextUp
        assertTrue(card.shufflePlayback)
        assertTrue(videos.any { it.id == card.info.videoId && it.episode == card.info.episode })
        assertEquals(5, card.info.seedEpisode)
    }

    @Test
    fun `finished show needs shuffle on and never duplicates an existing card`() {
        val otherShowOnly = profile.copy(shows = mapOf("other" to EpisodeShuffleSettings(true, false)))
        assertTrue(project(HomeUiState(), otherShowOnly, finished = listOf(finishedCard)).continueWatchingItems.isEmpty())
        val output = project(finished = listOf(finishedCard))
        assertEquals(1, output.continueWatchingItems.size + output.upcomingItems.size)
    }

    private fun project(
        input: HomeUiState = state,
        profile: EpisodeShuffleProfile = this.profile,
        watched: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
        visit: Long = 1,
        catalogue: List<Video>? = videos,
        finished: List<ContinueWatchingItem.NextUp> = emptyList()
    ) = applyHomeShuffle(input, profile, watched, shuffle, visit, ContinueWatchingSortMode.SPLIT_UPCOMING, finished) { _, _ -> catalogue }

    private fun progress() = WatchProgress("show", "series", "Show", null, null, null,
        "show:1:3", 1, 3, "Episode 3", 34000, 100000, 100, progressPercent = 34f)
}
