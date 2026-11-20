package com.nuvio.tv.livetv.calendar

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
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
        Dispatchers.setMain(Dispatchers.Unconfined)
        val stores = ProfileDataStoreFactory(RuntimeEnvironment.getApplication())
        try {
            val preferences = CalendarPreferences(stores)
            val defaults = CalendarSelection(CalendarMode.DAY, CalendarFilter.ALL)
            val saved = CalendarSelection(CalendarMode.MONTH, CalendarFilter.SPORTS)
            assertEquals(defaults, preferences.selection(71).first())
            preferences.setMode(71, CalendarMode.WEEK)
            preferences.setMode(71, saved.mode)
            preferences.setFilter(71, saved.filter)
            withTimeout(5_000) { preferences.selection(71).first { it == saved } }

            val reopened = CalendarPreferences(stores)
            assertEquals(saved, reopened.selection(71).first())
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
