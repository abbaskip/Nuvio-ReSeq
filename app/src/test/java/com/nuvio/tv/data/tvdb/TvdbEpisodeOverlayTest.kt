package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.player.PlayerNextEpisodeRules
import org.junit.Assert.*
import org.junit.Test

internal fun tvdbFixtureMeta(videos: List<Video>) = Meta(
    id = "tt0303461", type = ContentType.SERIES, name = "Provider series title",
    poster = "provider-poster", posterShape = PosterShape.POSTER, background = "provider-background",
    logo = null, description = "provider description", releaseInfo = null, imdbRating = null,
    genres = emptyList(), runtime = null, director = emptyList(), cast = emptyList(), videos = videos,
    country = null, awards = null, language = null, links = emptyList())

internal fun tvdbFixtureSource() = listOf(
    Video("tt0303461:1:1", "The Train Job", "2002-09-20", null, season = 1, episode = 1, overview = "provider episode"),
    Video("tt0303461:1:6", "Shindig", "2002-11-01", null, season = 1, episode = 6, overview = null))

internal fun tvdbFixtureOrder(): TvdbEpisodeOrder {
    val official = listOf(
        TvdbEpisode(297989, EpisodeCoordinates(1, 1), "The Train Job", "TVDB train description", "tvdb-train", "2002-09-20", 44),
        TvdbEpisode(297994, EpisodeCoordinates(1, 6), "Shindig", "TVDB shindig description", "tvdb-shindig", "2002-11-01", 44))
    return TvdbEpisodeOrder("dvd", "dvd", listOf(official[0].copy(coordinates = EpisodeCoordinates(1, 2)),
        official[1].copy(coordinates = EpisodeCoordinates(1, 4))), official)
}

class TvdbEpisodeOverlayTest {
    @Test fun `provider changes cannot reuse numeric history slots or embedded streams`() {
        val first = tvdbFixtureSource()
        val sources = mapOf(297989L to TvdbSourceReference(297989, first[0].id, EpisodeCoordinates(1, 1)),
            297994L to TvdbSourceReference(297994, first[1].id, EpisodeCoordinates(1, 6)))
        val changed = listOf(first[1].copy(id = first[0].id, episode = 1), first[0].copy(id = first[1].id, episode = 6))
        val result = tvdbEpisodeOverlay(tvdbFixtureMeta(changed), 78874, tvdbFixtureOrder(), sources)
        val train = result.videos.single { it.episodeIdentity?.tvdbEpisodeId == 297989L }
        assertTrue(train.hasResolvedSource)
        assertEquals("tt0303461:1:2", train.id)
        assertNull(train.episodeIdentity!!.providerVideoId)
        assertEquals(1, train.season)
        assertEquals(2, train.episode)
        assertTrue(train.streams.isEmpty())
        assertNull(result.videos.single { it.episodeIdentity?.tvdbEpisodeId == 297994L }.episodeIdentity!!.providerVideoId)
    }
    @Test fun `equal counts and reversed numbering search the selected coordinates`() {
        val source = tvdbFixtureMeta(tvdbFixtureSource())
        // Synthetic equal-count permutation in addition to the actual Firefly DVD fixture.
        val synthetic = tvdbFixtureOrder().let { order -> order.copy(selectedType = "test-alternative", effectiveType = "test-alternative",
            episodes = listOf(order.officialEpisodes[1].copy(coordinates = EpisodeCoordinates(1, 1)),
                order.officialEpisodes[0].copy(coordinates = EpisodeCoordinates(1, 2)))) }
        val result = tvdbEpisodeOverlay(source, 78874, synthetic)
        assertEquals(source.name, result.name)
        assertEquals(source.background, result.background)
        assertEquals("tt0303461:1:1", result.videos[0].id)
        assertEquals("TVDB shindig description", result.videos[0].overview)
        assertEquals(1, result.videos[0].displayEpisode)
        assertEquals(1, result.videos[0].sourceEpisode)
        assertEquals(297994L, result.videos[0].episodeIdentity?.tvdbEpisodeId)
        assertEquals("tt0303461:1:2", PlayerNextEpisodeRules.resolveNextEpisode(result.videos, 1, 1)?.id)
        assertEquals(source.videos[1].season, result.videos[0].forEpisodeDisplay().forEpisodeSource().season)
        assertEquals(1, result.videos[0].forEpisodeDisplay().forEpisodeSource().episode)
    }

    @Test fun `missing dates duplicate titles and combined parts do not guess source IDs`() {
        val source = tvdbFixtureSource()
        val missing = tvdbEpisodeOverlay(tvdbFixtureMeta(source.map { it.copy(released = null) }), 78874, tvdbFixtureOrder())
        assertTrue(missing.videos.all { it.hasResolvedSource && it.episodeIdentity!!.providerVideoId == null })
        assertTrue(missing.videos.all { it.available == null })
        val ambiguous = tvdbEpisodeOverlay(tvdbFixtureMeta(source + source.first().copy(id = "duplicate")), 78874, tvdbFixtureOrder())
        assertNull(ambiguous.videos.single { it.episodeIdentity?.tvdbEpisodeId == 297989L }.episodeIdentity!!.providerVideoId)
    }

    @Test fun `changing selected order never mutates provider episodes`() {
        val source = tvdbFixtureMeta(tvdbFixtureSource())
        val dvd = tvdbEpisodeOverlay(source, 78874, tvdbFixtureOrder())
        val official = tvdbFixtureOrder().let { it.copy(selectedType = "official", effectiveType = "official", episodes = it.officialEpisodes) }
        val aired = tvdbEpisodeOverlay(source, 78874, official)
        assertNotEquals(dvd.videos.map { it.id }.toSet(), aired.videos.map { it.id }.toSet())
        assertEquals(listOf(1, 6), source.videos.map { it.episode })
        assertEquals(2, dvd.videos.single { it.episodeIdentity!!.providerVideoId == source.videos[0].id }.displayEpisode)
        assertEquals(1, aired.videos.single { it.id == source.videos[0].id }.displayEpisode)
    }
}
