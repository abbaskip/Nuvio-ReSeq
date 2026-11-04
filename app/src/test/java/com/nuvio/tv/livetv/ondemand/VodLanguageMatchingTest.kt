package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.EpisodeCoordinates
import org.junit.Assert.*
import org.junit.Test

class VodLanguageMatchingTest {
    private val target = VodStreamTarget(VodKind.SERIES, "1668", "Friends", 1994,
        providerCoordinates = EpisodeCoordinates(1, 1))
    private fun show(name: String, year: Int? = 1994, tmdb: String? = null) =
        VodItem("provider", VodKind.SERIES, "42", name, null, "english", null, tmdb, year, null, 0)

    @Test fun `English decorated Friends titles are discoverable and pass unchanged identity rules`() {
        listOf("Friends (en)", "Friends (EN)", "Friends [eng]", "Friends (English)", "Friends [en-US]",
            "Friends (1994) (en)", "Friends (en) 1080p", "[EN] Friends (en)", "Friends - en", "Friends [en/fr]", "Friends (English audio)").forEach { name ->
            val item = show(name)
            assertTrue(name, OnDemandDatabase.normalize(name) in OnDemandDatabase.titleLookupKeys(target.title))
            assertTrue(name, VodMatchPolicy.parentMatches(target, item, null))
            assertEquals("friends", OnDemandDatabase.normalize(name))
        }
    }

    @Test fun `language cleanup never overrides contrary year series kind or identifiers`() {
        assertFalse(VodMatchPolicy.parentMatches(target, show("Friends (en)", 2021), null))
        assertFalse(VodMatchPolicy.parentMatches(target, show("Friends (en)", null), null))
        assertFalse(VodMatchPolicy.parentMatches(target, show("Friends (en)", tmdb = "999"), null))
        assertFalse(VodMatchPolicy.parentMatches(target, show("Friends with Benefits (en)"), null))
        assertFalse(VodMatchPolicy.parentMatches(target, show("Friends (en)").copy(kind = VodKind.MOVIE), null))
    }

    @Test fun `real language-like title words and episode identity survive cleanup`() {
        assertEquals("it", OnDemandDatabase.normalize("It"))
        assertEquals("madeinheaven", OnDemandDatabase.normalize("Made in Heaven"))
        assertEquals("pokemon", OnDemandDatabase.normalize("Pokémon (en)"))
        assertEquals("theonewheremonicagetsaroommate", VodMatchPolicy.episodeKey("The One Where Monica Gets a Roommate (en)"))
        val episode = VodEpisode(9, 99, "1", "mkv", "Friends (en) S01E01", null, null, "https://example.invalid/1")
        assertEquals(1, VodMatchPolicy.episodes(target, listOf(episode), "Friends (en)").single().episode)
        assertTrue(VodMatchPolicy.episodes(target, listOf(episode.copy(title = "Friends (en) S01E02")), "Friends (en)").isEmpty())
    }
}
