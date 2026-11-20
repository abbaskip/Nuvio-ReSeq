package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.sports.*
import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarSportsTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val today = LocalDate.now(zone)
    private val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val country = FollowedTeam(InternationalCricket.LEAGUE_ID, "australia", "Australia", "AUS", null, null)
    private val raw = MutableStateFlow<String?>(null)
    private val context = RuntimeEnvironment.getApplication()
    private val cache = SportsFixtureCache(context)
    private val prefs = mockk<LiveTvPreferences> { every { sportsMineJson(7) } returns raw }
    private val local = CalendarLocalListings(context, Lazy { prefs }, Lazy { error("No channel load") },
        Lazy { error("No TV-guide load") }, Lazy { cache })

    private fun fixture(id: String, league: String, offset: Long, name: String? = null, title: String = id): SportsEvent {
        val away = name?.let { Side(TeamInfo(league, "provider-$id", it, it, "", null, null, null, null), null, null, false, false) }
        return SportsEvent(id, league, noon + offset, true, GameState.PRE, title, null, null, away, null)
    }

    @Test fun countryAcrossFormatsAndWholeLeagueMergeOnceChronologicallyWithTeamsFirstOnlyAtEqualTimes() = runBlocking {
        val test = "cricket/19430"
        val odi = "taxonomy/cricket.odi-one-day.odi-internationals"
        val t20 = "taxonomy/cricket.t20-internationals.t20-internationals"
        val league = fixture("same-time-league", test, 2, "England")
        val australia = fixture("odi", odi, 2, "Australia ODI")
        cache.known = MutableStateFlow(listOf(league, fixture("test", test, 3, "Australia Test"), australia,
            fixture("t20", t20, 4, title = "India vs. Australia (T20I)"), fixture("earlier", test, 1, "India"),
            fixture("domestic", "cricket/8048", 0, "Australia A"), fixture("women", t20, 1, "Australia Women"),
            fixture("football", "soccer/fifa.world", 0, "Australia"), australia))
        raw.value = SportsFollows(teams = listOf(country), pins = listOf(test)).toJson()
        val matched = local.sports(7, zone, CalendarWindow.around(today)).first()
        val displayed = CalendarPolicy.displayed(matched, CalendarWindow.around(today), today.toEpochDay())
        assertEquals(listOf("earlier", "odi", "same-time-league", "test", "t20"), displayed.map { it.contentId })
        assertEquals(listOf(null, "Australia", null, "Australia", "Australia"), displayed.map { it.followedTeam })

        // The next visit reuses the fixture data with today's follows, even while release dates are fresh.
        raw.value = """{"leagues":["cricket/19430"]}"""
        assertEquals(setOf("earlier", "test", "same-time-league"), local.sports(7, zone, CalendarWindow.around(today)).first().map { it.contentId }.toSet())
        assertTrue(local.sports(7, zone, CalendarWindow.around(today)).first().all { it.followedTeam == null })
        raw.value = SportsFollows().toJson()
        assertTrue(local.sports(7, zone, CalendarWindow.around(today)).first().isEmpty())
    }

    @Test fun aNewProcessUsesTheSavedHubScheduleWithCurrentCountryFollow() = runBlocking {
        val file = File(context.cacheDir, "sports/page-snapshot.json")
        val match = fixture("disk-odi", "taxonomy/cricket.odi-one-day.odi-internationals", 2, title = "England v Australia")
        val now = System.currentTimeMillis()
        try {
            SportsPageSnapshot.write(file, listOf(match), emptyMap(), now, noon - 86_400_000, noon + 86_400_000) { true }
            raw.value = SportsFollows(teams = listOf(country)).toJson()
            val row = local.sports(7, zone, CalendarWindow.around(today)).first().single()
            assertEquals("disk-odi", row.contentId)
            assertEquals("Australia", row.followedTeam)
        } finally { file.delete() }
    }
}
