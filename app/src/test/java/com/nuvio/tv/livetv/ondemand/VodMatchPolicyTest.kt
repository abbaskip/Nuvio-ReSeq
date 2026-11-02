package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.domain.model.EpisodeCoordinates
import org.junit.Assert.*
import org.junit.Test

class VodMatchPolicyTest {
    private fun item(name: String = "The Office", year: Int? = 2005, tmdb: String? = "2316", kind: VodKind = VodKind.SERIES) =
        VodItem("one", kind, "42", name, null, "category", "mkv", tmdb, year, null, 0)
    private fun episode(name: String, season: Int, number: Int, tvdb: Long? = null, imdb: String? = null) =
        VodEpisode(season, number, "$season-$number-$name", "mkv", name, null, null, "https://media.example.test/$season/$number", tvdbId = tvdb, imdbId = imdb)
    private fun target(vararg names: String) = VodStreamTarget(VodKind.SERIES, "2316", "The Office", 2005, names.toSet())

    @Test fun `Tulsa King series year decorations do not become episode part numbers`() {
        val names = listOf("Go West, Old Man", "Center of the Universe", "Caprice", "Visitation Place", "Token Joe")
        val candidates = names.mapIndexed { index, name -> episode("Tulsa King (2022) - S01E%02d - $name".format(index + 1), 1, index + 1) }
        names.forEachIndexed { index, name ->
            val wanted = target(name).copy(title = "Tulsa King", year = 2022)
            assertEquals(name, listOf(candidates[index]), VodMatchPolicy.episodes(wanted, candidates, "Tulsa King"))
        }
        assertEquals("gowestoldman", VodMatchPolicy.episodeKey(candidates.first().title, "Tulsa King"))
    }

    @Test fun `series year and country decorations are separate from an episode title`() {
        listOf("Tulsa King (2022) - S01E01 - Go West, Old Man",
            "Tulsa.King.2022.US.S01E01.Go.West.Old.Man",
            "Tulsa_King_2022_US_S01E01_Go_West_Old_Man",
            "Tulsa King (2022) - Go West, Old Man").forEach { name ->
            assertEquals(name, "gowestoldman", VodMatchPolicy.episodeKey(name, "Tulsa King"))
        }
        val wanted = target().copy(tmdbId = null, title = "Tulsa King", year = 2022)
        val show = item(name = "Tulsa King (2022)", year = 2022, tmdb = null)
        assertTrue(OnDemandDatabase.normalize(show.name) in OnDemandDatabase.titleLookupKeys(wanted.title))
        assertTrue(VodMatchPolicy.parentMatches(wanted, show, null))
    }

    @Test fun `decorated episode title matching still overrides different default coordinates`() {
        val wanted = target("Go West, Old Man").copy(title = "Tulsa King", year = 2022,
            providerCoordinates = EpisodeCoordinates(1, 2))
        val correct = episode("Tulsa King (2022) - S01E01 - Go West, Old Man", 1, 1)
        val other = episode("Tulsa King (2022) - S01E02 - Center of the Universe", 1, 2)
        assertEquals(listOf(correct), VodMatchPolicy.episodes(wanted, listOf(correct, other), "Tulsa King"))
    }

    @Test fun `series cleanup preserves real episode names years and part labels`() {
        assertEquals("fireflyblossom", VodMatchPolicy.episodeKey("Firefly - S01E01 - Firefly Blossom", "Firefly"))
        assertEquals("lecturecircuitpart2", VodMatchPolicy.episodeKey("The Office (2005) - S05E15 - Lecture Circuit (2)", "The Office"))
        assertEquals("theyear2022", VodMatchPolicy.episodeKey("Tulsa King (2022) - S01E01 - The Year 2022", "Tulsa King"))
        assertTrue(VodMatchPolicy.episodes(target("Blossom"), listOf(episode("Firefly - S01E01 - Firefly Blossom", 1, 1)), "Firefly").isEmpty())
    }

    @Test fun `Gilded Age filename numbers match standard metadata despite shuffled provider positions`() {
        val wanted = VodStreamTarget(VodKind.SERIES, "81354", "The Gilded Age", 2022,
            setOf("Never the New"), providerCoordinates = EpisodeCoordinates(1, 1))
        val rows = listOf(2, 3, 1, 7, 8).mapIndexed { index, number ->
            episode("The.Gilded.Age.2022.US.S01E%02d".format(number), 1, index + 1)
        }
        val matched = VodMatchPolicy.episodes(wanted, rows, "The Gilded Age").single()
        assertEquals(rows[2].id, matched.id)
        assertEquals(rows[2].url, matched.url)
        assertEquals(1 to 1, matched.season to matched.episode)
    }

    @Test fun `filename fallback uses metadata numbers when titles are absent or localized`() {
        val wanted = target().copy(providerCoordinates = EpisodeCoordinates(1, 1))
        val candidate = episode("The.Office.s01e01.Localized.Title.1080p.mkv", 9, 9)
        assertEquals(listOf(candidate.copy(season = 1, episode = 1)), VodMatchPolicy.episodes(wanted, listOf(candidate), "The Office"))
    }

    @Test fun `episode title identity takes priority over a standard numbered filename`() {
        val wanted = target("Serenity").copy(providerCoordinates = EpisodeCoordinates(1, 1))
        val serenity = episode("Firefly.S01E11.Serenity", 1, 11)
        assertEquals(listOf(serenity), VodMatchPolicy.episodes(wanted,
            listOf(episode("Firefly.S01E01", 1, 1), serenity), "Firefly"))
    }

    @Test fun `filename fallback requires known standard coordinates and rejects contrary ids`() {
        val filename = episode("The.Office.S01E01", 1, 1)
        assertTrue(VodMatchPolicy.episodes(target("Pilot"), listOf(filename), "The Office").isEmpty())
        val wanted = target("Pilot").copy(providerCoordinates = EpisodeCoordinates(1, 1),
            tvdbEpisodeIds = setOf(101), selectedTvdbEpisodeId = 101)
        assertTrue(VodMatchPolicy.episodes(wanted, listOf(filename.copy(tvdbId = 999)), "The Office").isEmpty())
    }

    @Test fun `filename fallback never guesses from array positions or another season`() {
        val wanted = target("Pilot").copy(providerCoordinates = EpisodeCoordinates(1, 1))
        assertTrue(VodMatchPolicy.episodes(wanted, listOf(episode("Episode 1", 1, 1),
            episode("The.Office.S02E01", 1, 1), episode("The.Office.S01E02", 1, 1)), "The Office").isEmpty())
    }

    @Test fun `filename fallback rejects multi episode and range entries`() {
        val wanted = target("Pilot").copy(providerCoordinates = EpisodeCoordinates(1, 1))
        listOf("S01E01E02", "S01E01-E02", "S01E01-02", "S01E01.S01E02", "S01E01.E02").forEach { name ->
            assertTrue(name, VodMatchPolicy.episodes(wanted, listOf(episode(name, 1, 1)), "The Office").isEmpty())
        }
    }

    @Test fun `ambiguous title matches do not fall through to filename numbering`() {
        val wanted = target("Pilot").copy(providerCoordinates = EpisodeCoordinates(1, 1))
        assertTrue(VodMatchPolicy.episodes(wanted, listOf(episode("Pilot", 1, 2), episode("Pilot", 2, 2),
            episode("The.Office.S01E01", 1, 1)), "The Office").isEmpty())
    }

    @Test fun `filename fallback retains multiple copies of the same episode`() {
        val wanted = target("Pilot").copy(providerCoordinates = EpisodeCoordinates(1, 1))
        val copies = listOf(episode("The.Office.S01E01.1080p", 1, 1), episode("The.Office.S01E01.2160p", 1, 1))
        assertEquals(copies, VodMatchPolicy.episodes(wanted, copies, "The Office"))
    }

    private fun realCatalogue(tmdb: String): Pair<String, List<VodEpisode>> {
        val raw = javaClass.classLoader!!.getResourceAsStream("iptv/numbering-catalogues.json")!!.bufferedReader().use { it.readText() }
        val shows = org.json.JSONArray(raw)
        val show = (0 until shows.length()).map { shows.getJSONObject(it) }.single { it.getString("tmdb") == tmdb }
        val rows = show.getJSONArray("episodes")
        return show.getString("title") to (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            episode(row.getString("title"), row.getInt("season"), row.getInt("episode"))
        }
    }
    @Test fun `real catalogue resolves alternate Business Ethics to provider S5E2`() {
        val (name, episodes) = realCatalogue("2316")
        val matched = VodMatchPolicy.episodes(target("Business Ethics"), episodes, name)
        assertEquals(5 to 2, matched.single().let { it.season to it.episode })
    }
    @Test fun `real broadcast catalogue resolves DVD Serenity and specials by identity metadata`() {
        val (name, episodes) = realCatalogue("1437")
        assertEquals(1 to 11, VodMatchPolicy.episodes(target("Serenity"), episodes, name).single().let { it.season to it.episode })
        assertEquals(0 to 1, VodMatchPolicy.episodes(target("Trash"), episodes, name).single().let { it.season to it.episode })
        assertTrue(VodMatchPolicy.episodes(target("No Episode Title"), episodes, name).isEmpty())
    }
    @Test fun `a combined display entry cannot use either individual provider part as the full story`() {
        val (name, episodes) = realCatalogue("2316")
        assertTrue(VodMatchPolicy.episodes(target("Lecture Circuit"), episodes, name).isEmpty())
        assertEquals(5 to 15, VodMatchPolicy.episodes(target("Lecture Circuit (2)"), episodes, name).single().let { it.season to it.episode })
    }
    @Test fun `a genuine episode title starting with the series name is not a decoration`() {
        assertTrue(VodMatchPolicy.episodes(target("Blossom"), listOf(episode("Firefly Blossom", 1, 1)), "Firefly").isEmpty())
    }
    @Test fun `contradictory parent ids cannot be overridden by a matching other id`() {
        val detail = VodInfo(null, null, null, null, null, null, tmdbId = "999", imdbId = "tt0386676")
        assertFalse(VodMatchPolicy.parentMatches(target().copy(imdbId = "tt0386676"), item(tmdb = "999"), detail))
        assertFalse(VodMatchPolicy.parentMatches(target().copy(imdbId = "tt0386676"), item(), detail))
        assertTrue(VodMatchPolicy.parentMatches(target().copy(tmdbId = null, imdbId = "tt0386676"), item(tmdb = null, year = null), detail))
    }
    @Test fun `shifted numbering uses episode content not displayed coordinates`() {
        val ethics = episode("The Office - S05E02 - Business Ethics", 5, 2)
        val wrong = episode("Baby Shower", 5, 3)
        assertEquals(listOf(ethics), VodMatchPolicy.episodes(target("Business Ethics"), listOf(wrong, ethics), "The Office"))
    }
    @Test fun `provider default can match a differently numbered catalogue without TVDB preferences`() {
        val serenity = episode("Firefly - S01E11 - Serenity", 1, 11)
        assertEquals(listOf(serenity), VodMatchPolicy.episodes(target("Serenity"), listOf(serenity, episode("The Train Job", 1, 1)), "Firefly"))
    }
    @Test fun `provider episode coordinates alone cannot match generic episode titles`() {
        assertTrue(VodMatchPolicy.episodes(target("Episode 3"), listOf(episode("Episode 3", 5, 3)), "The Office").isEmpty())
    }
    @Test fun `explicit episode ids beat localized titles and numbering`() {
        val expected = episode("Titre local", 9, 17, tvdb = 101)
        val wanted = target("Business Ethics").copy(tvdbEpisodeIds = setOf(101), selectedTvdbEpisodeId = 201)
        assertEquals(listOf(expected), VodMatchPolicy.episodes(wanted, listOf(expected), "The Office"))
    }
    @Test fun `a contradictory episode id rejects even an exact title`() {
        val wanted = target("Business Ethics").copy(tvdbEpisodeIds = setOf(101), selectedTvdbEpisodeId = 201)
        assertTrue(VodMatchPolicy.episodes(wanted, listOf(episode("Business Ethics", 5, 2, tvdb = 999)), "The Office").isEmpty())
    }
    @Test fun `extended cuts retain ordinary episode identity`() {
        assertEquals("businessethics", VodMatchPolicy.episodeKey("Business Ethics (Extended Cut)"))
        val standard = episode("Business Ethics", 5, 2)
        assertEquals(listOf(standard), VodMatchPolicy.episodes(target("Business Ethics (Extended Cut)"), listOf(standard), "The Office"))
    }
    @Test fun `part labels are preserved and compatible spellings match`() {
        val first = episode("Weight Loss Part 1", 5, 1)
        val second = episode("Weight Loss Part 2", 5, 2)
        assertEquals(listOf(second), VodMatchPolicy.episodes(target("Weight Loss (2)"), listOf(first, second), "The Office"))
    }
    @Test fun `combined request does not silently choose an individual part`() {
        assertTrue(VodMatchPolicy.episodes(target("Lecture Circuit").copy(tvdbEpisodeIds = setOf(14, 15), selectedTvdbEpisodeId = 160),
            listOf(episode("Lecture Circuit Part 1", 5, 14, tvdb = 14), episode("Lecture Circuit Part 2", 5, 15, tvdb = 15)), "The Office").isEmpty())
    }
    @Test fun `verified combined record is accepted without inventing two source links`() {
        val combined = episode("Lecture Circuit", 5, 16, tvdb = 160)
        val wanted = target("Lecture Circuit").copy(tvdbEpisodeIds = setOf(14, 15), selectedTvdbEpisodeId = 160)
        assertEquals(listOf(combined), VodMatchPolicy.episodes(wanted, listOf(combined), "The Office"))
    }
    @Test fun `duplicate episode names across distinct positions stay unresolved`() {
        assertTrue(VodMatchPolicy.episodes(target("Pilot"), listOf(episode("Pilot", 1, 1), episode("Pilot", 2, 1)), "The Office").isEmpty())
    }
    @Test fun `verified same episode copies are retained`() {
        val one = episode("Business Ethics", 5, 2)
        val two = one.copy(id = "quality-2", url = "https://media.example.test/quality-2")
        assertEquals(listOf(one, two), VodMatchPolicy.episodes(target("Business Ethics"), listOf(one, two), "The Office"))
    }
    @Test fun `parent identifiers prevent original revival and remake confusion`() {
        assertFalse(VodMatchPolicy.titleMatches(target(), item(tmdb = "999")))
        assertFalse(VodMatchPolicy.titleMatches(target().copy(tmdbId = null), item(year = 2024, tmdb = null)))
        assertFalse(VodMatchPolicy.titleMatches(target().copy(tmdbId = null), item(year = null, tmdb = null)))
        assertTrue(VodMatchPolicy.titleMatches(target(), item(name = "A localized name", year = null)))
    }
    @Test fun `title year fallback is exact and content type aware`() {
        assertTrue(VodMatchPolicy.titleMatches(target().copy(tmdbId = null), item(name = "The Office (2005) 4K", tmdb = null)))
        assertFalse(VodMatchPolicy.titleMatches(target(), item(kind = VodKind.MOVIE)))
        assertFalse(VodMatchPolicy.titleMatches(target().copy(tmdbId = null), item(name = "The Office Australia", tmdb = null)))
    }
    @Test fun `provider numbering decorations do not obscure the episode title`() {
        assertEquals("businessethics", VodMatchPolicy.episodeKey("The Office - 5x02 - Business Ethics", "The Office"))
        assertEquals("businessethics", VodMatchPolicy.episodeKey("The Office - Episode 2 - Business Ethics", "The Office"))
    }
    @Test fun `real Golden Ticket catalogue entry matches the selected extended title without using its number`() {
        val (name, episodes) = realCatalogue("2316")
        val match = VodMatchPolicy.episodes(target("Golden Ticket (Extended Cut)"), episodes, name).single()
        assertEquals(5 to 17, match.season to match.episode)
    }

    @Test fun `indexed title lookup includes the same cleaned title accepted by the year checked matcher`() {
        val wanted = target("Golden Ticket (Extended Cut)").copy(tmdbId = null, title = "The Office (US)")
        val catalogue = item(name = "The Office (2005)", tmdb = null)
        assertTrue(OnDemandDatabase.normalize(catalogue.name) in OnDemandDatabase.titleLookupKeys(wanted.title))
        assertTrue(VodMatchPolicy.titleMatches(wanted, catalogue))
        assertFalse(VodMatchPolicy.titleMatches(wanted, catalogue.copy(year = 2024)))
        assertFalse(VodMatchPolicy.titleMatches(wanted, catalogue.copy(year = null)))
    }

}
