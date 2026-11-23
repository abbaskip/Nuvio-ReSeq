package com.nuvio.tv.livetv.ui

import com.nuvio.tv.domain.model.*
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import org.junit.Assert.*
import org.junit.Test

class GuideLookupTest {
    private val channel = LiveChannel("test", "source", "provider", "Sports", null, null, null, "sport", "Sport", 1, "local")
    private fun episode(n: Int, title: String) = Video("episode-$n", title, null, null, season = 1, episode = n, overview = null)

    @Test fun usesShowNameAndRecognizesEpisodeTitleOnASportsChannel() {
        val guide = GuideLookup.from(EpgProgram(0, 1_800_000, "Face to Face on Fox : Dylan Brown"), channel)
        assertEquals("Face to Face on Fox", guide.query)
        assertEquals("Dylan Brown", guide.subtitle)
        val match = episode(2, "Dylan Brown")
        assertEquals(match, guideEpisode(listOf(episode(1, "Pilot"), match), guide.number, guide.subtitle))
    }

    @Test fun usesAiredNumbersThroughACustomTvdbOrderAndAvoidsAmbiguousTitles() {
        val pilot = episode(1, "Pilot")
        val airing = episode(2, "Pilot").copy(episodeIdentity = EpisodeIdentity(101, 9, "dvd", EpisodeCoordinates(1, 2), EpisodeCoordinates(3, 7)))
        assertEquals(airing, guideEpisode(listOf(pilot, airing), "S3 E7", ""))
        assertNull(guideEpisode(listOf(pilot, airing), "", "Pilot"))
        assertNull(guideEpisode(listOf(pilot, airing), "S1 E2", ""))
    }

    @Test fun matchingRemakesRequiresAnUnambiguousCatalogueIdentity() {
        val one = MetaPreview("show1", ContentType.SERIES, name = "The Office", poster = null, posterShape = PosterShape.POSTER,
            background = null, logo = null, description = null, releaseInfo = "2001–2003", imdbRating = null, genres = emptyList())
        val two = one.copy(id = "show2", releaseInfo = "2005–2013")
        val row = CatalogRow("fixture", "Fixture", "fixture-url", "all", "All", ContentType.SERIES, items = listOf(one, two))
        val lookup = GuideLookup("The Office", "series", "", "", "")
        assertNull(lookup.exactMatch(listOf(row)))
        assertEquals(two, lookup.copy(year = "2005").exactMatch(listOf(row, row))?.first)
    }
}
