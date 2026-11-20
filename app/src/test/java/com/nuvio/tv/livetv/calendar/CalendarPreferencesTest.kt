package com.nuvio.tv.livetv.calendar

import com.nuvio.tv.livetv.sports.Sport
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CalendarPreferencesTest {
    @Test fun aReopenedCalendarKeepsBothChoicesForTheRightProfile() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))
        val stores = ProfileDataStoreFactory(RuntimeEnvironment.getApplication())
        try {
            val preferences = CalendarPreferences(stores)
            val defaults = CalendarSelection(CalendarMode.DAY, CalendarFilter.ALL)
            val saved = CalendarSelection(CalendarMode.MONTH, CalendarFilter.SPORTS, allSports = true)
            assertEquals(defaults, preferences.selection(71).first())
            preferences.setMode(71, CalendarMode.WEEK)
            preferences.setMode(71, saved.mode)
            preferences.setFilter(71, saved.filter)
            preferences.setAllSports(71, true)
            withTimeout(5_000) { preferences.selection(71).first { it == saved } }

            val reopened = CalendarPreferences(stores)
            assertEquals(saved, reopened.selection(71).first())
            assertEquals(defaults, reopened.selection(72).first())
            val cricket = Sport.CRICKET
            val football = Sport.FOOTBALL
            suspend fun awaitSaved(sport: Sport?) = withTimeout(5_000) {
                while (reopened.selection(71).first() != saved.copy(quickSport = sport)) delay(25)
            }
            reopened.toggleSport(71, cricket)
            awaitSaved(cricket)
            reopened.toggleSport(71, football)
            awaitSaved(football)
            reopened.toggleSport(71, football)
            awaitSaved(null)
            reopened.toggleSport(71, cricket)
            awaitSaved(cricket)
            reopened.setFilter(71, saved.filter)
            awaitSaved(null)
            assertEquals(defaults, reopened.selection(72).first())
            stores.get(71, "calendar_display").edit { it[stringPreferencesKey("view")] = "UNKNOWN" }
            assertEquals(saved.copy(mode = CalendarMode.DAY), reopened.selection(71).first())

        } finally {
            stores.clearProfile(71)
            stores.clearProfile(72)
            Dispatchers.resetMain()
        }
    }
}
