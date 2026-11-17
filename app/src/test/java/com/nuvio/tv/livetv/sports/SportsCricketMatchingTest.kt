package com.nuvio.tv.livetv.sports

import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.model.EpgProgram
import com.nuvio.tv.livetv.model.LiveChannel
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class SportsCricketMatchingTest {
    private val now = 1_794_312_000_000L
    private val league = "taxonomy/cricket.odi-one-day.odi-internationals"
    private fun side(name: String, abbr: String, home: Boolean) = Side(
        TeamInfo(league, abbr, name, name, abbr, null, null, null, null), null, null, false, home)
    private val fixture = SportsEvent("australia-england-odi", league, now, true, GameState.LIVE,
        "Australia vs England", null, null, side("England", "ENG", false), side("Australia", "AUS", true))
    private fun channel(key: String, name: String) = LiveChannel(key, "playlist", "Test", name,
        null, null, null, "general", "Australian TV", 1, "https://example.invalid/live")

    @Test fun `South Africa abbreviation does not become South Australia`() {
        val team = TeamInfo(league, "sa", "SA", "South Africa", "SA", null, null, null, null)
        val names = SportsCatalog.teamNames(team, requireNotNull(SportsCatalog.league(league))).first
        assertFalse("south australia" in names)
        assertFalse("s australia" in names)
    }

    @Test fun `simultaneous One Day Cup fixtures match their own Sky guide listings`() {
        val leagueId = "ca/one-day"
        val guideNames = listOf("New South Wales v S Australia", "Victoria v W Australia", "Queensland v Tasmania")
        val identities = listOf(
            listOf("NSW" to "SA", "VIC" to "WA", "QLD" to "TAS"),
            listOf("New South Wales" to "South Australia", "Victoria" to "Western Australia", "Queensland" to "Tasmania"),
            listOf("NSW Blues" to "South Australia Redbacks", "Victoria" to "Western Australia", "Queensland Bulls" to "Tasmanian Tigers"))
        val channels = guideNames.indices.map { channel("sky-${it + 3}", "NZ SKY Sport ${it + 3}") }
        val programmes = channels.mapIndexed { i, ch -> ch.key to EpgProgram(now - 2 * 3_600_000,
            now + 3_600_000, "LIVE Cricket: Marsh One Day Cup : ODC: ${guideNames[i]}") }
        for (names in identities) for (currentOnly in listOf(false, true)) {
            fun domesticSide(name: String, home: Boolean) = Side(
                TeamInfo(leagueId, name, name, name, name, null, null, null, null), null, null, false, home)
            val events = names.mapIndexed { i, (home, away) -> SportsEvent("domestic-$i", leagueId,
                now - 2 * 3_600_000, true, GameState.LIVE, "$home vs $away", null, null,
                domesticSide(away, false), domesticSide(home, true)) }
            val db = mockk<EpgDatabase>()
            every { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) } answers {
                val rows: List<Pair<String, com.nuvio.tv.livetv.model.EpgProgram>> = run {
                // Respect the database's candidate search, rather than returning every row.
                val words = arg<Collection<String>>(3).filter { it.length >= 3 }
                programmes.filter { (_, p) -> words.any { p.title.contains(it, ignoreCase = true) } }

                }
                rows.forEach { (ch,p) -> lastArg<(String,com.nuvio.tv.livetv.model.EpgProgram)->Unit>()(ch,p) }
            }
            val result = SportsMatcher(db).match(SportsMatcher.Input(events, channels, emptyMap(), 0,
                SportsSettings(), now, includeGuide = !currentOnly,
                currentGuidePrograms = if (currentOnly) programmes else emptyList()))
            events.forEachIndexed { i, event ->
                val matches = result.matches[event.id].orEmpty()
                assertEquals("$names / currentOnly=$currentOnly / ${event.title}",
                    listOf(channels[i].key), matches.map { it.channel.key })
                assertTrue(matches.all { it.sure })
            }
        }
    }

    private fun match(rows: List<Pair<LiveChannel, EpgProgram>>, currentOnly: Boolean): List<ChannelMatch> {
        val db = mockk<EpgDatabase>()
        val programmes = rows.map { (ch, p) -> ch.key to p }
        every { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) } answers {
            val rows: List<Pair<String,com.nuvio.tv.livetv.model.EpgProgram>> = programmes
            rows.forEach { (ch,p) -> lastArg<(String,com.nuvio.tv.livetv.model.EpgProgram)->Unit>()(ch,p) }
        }
        return SportsMatcher(db).match(SportsMatcher.Input(listOf(fixture), rows.map { it.first },
            emptyMap(), 0, SportsSettings(), now, includeGuide = !currentOnly,
            currentGuidePrograms = if (currentOnly) programmes else emptyList())).matches[fixture.id].orEmpty()
    }

    @Test fun `regional news and country mentions do not identify a cricket broadcast`() {
        val news = listOf("Sydney", "Melbourne", "Brisbane", "Adelaide", "Perth", "Hobart", "Darwin", "Canberra")
            .map { city -> channel(city, "AU: ABC News $city") to EpgProgram(now, now + 3_600_000,
                "ABC News", "News from Australia and England, including the latest ODI cricket results.") }
        val other = listOf(
            channel("breakfast", "AU: ABC TV") to EpgProgram(now, now + 3_600_000, "News Breakfast",
                "Australia vs England\nThe latest news, weather and cricket results."),
            channel("category", "AU: General TV") to EpgProgram(now, now + 3_600_000, "Afternoon Briefing",
                "Australia vs England ODI cricket updates.", category = "News / Current affairs"),
            channel("countries", "AU: World TV") to EpgProgram(now, now + 3_600_000, "The World",
                "Reports from Australia and England on today's headlines."))
        for (currentOnly in listOf(false, true)) {
            assertEquals("currentOnly=$currentOnly", emptyList<String>(),
                match(news + other, currentOnly).map { it.channel.key })
        }
    }

    @Test fun `live cricket keeps title description abbreviated and general channel listings`() {
        val programmes = listOf(
            EpgProgram(now, now + 3_600_000, "Australia vs England"),
            EpgProgram(now, now + 3_600_000, "Live Cricket", "Australia takes on England in the second ODI."),
            EpgProgram(now, now + 3_600_000, "ODI: AUS vs ENG Live"),
            EpgProgram(now, now + 3_600_000, "Live Sport", "Australia vs England"),
            EpgProgram(now, now + 3_600_000, "Australia vs England - News follows"))
        val rows = programmes.mapIndexed { i, p -> channel("live-$i", "AU: General Channel $i") to p }
        for (currentOnly in listOf(false, true)) {
            val matches = match(rows, currentOnly)
            assertEquals(rows.map { it.first.key }.toSet(), matches.map { it.channel.key }.toSet())
            assertTrue(matches.all { it.sure })
        }
    }

    @Test fun `listed sports broadcaster showing a news bulletin is rejected`() {
        val event = fixture.copy(broadcasts = listOf("Fox Sports 501"))
        val ch = channel("fox", "AU: Fox Sports 501 HD")
        val db = mockk<EpgDatabase>()
        every { db.visitMatchPrograms(any(), any(), any(), any(), any(), any()) } answers {
            val rows: List<Pair<String,com.nuvio.tv.livetv.model.EpgProgram>> = emptyList()
            rows.forEach { (ch,p) -> lastArg<(String,com.nuvio.tv.livetv.model.EpgProgram)->Unit>()(ch,p) }
        }
        for (title in listOf("Live Sports News", "Live ODI Cricket")) {
            every { db.range(any(), any(), any(), any(), any()) } returns
                mapOf(ch.key to listOf(EpgProgram(now, now + 3_600_000, title)))
            val matches = SportsMatcher(db).match(SportsMatcher.Input(listOf(event), listOf(ch),
                emptyMap(), 0, SportsSettings(), now)).matches[event.id].orEmpty()
            assertEquals(title, title == "Live ODI Cricket", matches.isNotEmpty())
        }
    }
}
