package com.nuvio.tv.livetv.calendar

import android.util.Log
import com.nuvio.tv.livetv.sports.Sport
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal data class CalendarSelection(val mode: CalendarMode, val filter: CalendarFilter, val allSports: Boolean = false, val quickSport: Sport? = null)

@Singleton
internal class CalendarPreferences @Inject constructor(private val stores: ProfileDataStoreFactory) {
    private val modeKey = stringPreferencesKey("view")
    private val sportKey = stringPreferencesKey("quick_sport")
    private val filterKey = stringPreferencesKey("filter")
    private val allSportsKey = androidx.datastore.preferences.core.booleanPreferencesKey("all_sports")
    // Enqueue remote clicks in order and let an edit finish even if Calendar closes immediately.
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun selection(profile: Int) = flow {
        emitAll(stores.get(profile, "calendar_display").data)
    }.catch { error ->
        if (error !is IOException) throw error
        emit(emptyPreferences())
    }.map { prefs ->
        CalendarSelection(
            CalendarMode.entries.firstOrNull { it.name == prefs[modeKey] } ?: CalendarMode.DAY,
            CalendarFilter.entries.firstOrNull { it.name == prefs[filterKey] } ?: CalendarFilter.ALL,
            prefs[allSportsKey] ?: false,
            Sport.entries.firstOrNull { it.name == prefs[sportKey] }
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun setMode(profile: Int, mode: CalendarMode) = write(profile) { it[modeKey] = mode.name }
    fun setFilter(profile: Int, filter: CalendarFilter) = write(profile) {
        it[filterKey] = filter.name
        it.remove(sportKey)
    }

    fun setAllSports(profile: Int, enabled: Boolean) = write(profile) { it[allSportsKey] = enabled }

    fun toggleSport(profile: Int, sport: Sport) = write(profile) {
        if (it[sportKey] == sport.name) it.remove(sportKey) else it[sportKey] = sport.name
    }

    fun clearSport(profile: Int, sport: Sport) = write(profile) {
        if (it[sportKey] == sport.name) it.remove(sportKey)
    }

    private fun write(profile: Int, change: (MutablePreferences) -> Unit) {
        writes.launch {
            try {
                stores.get(profile, "calendar_display").edit { change(it) }
            } catch (error: IOException) {
                Log.w("Calendar", "Could not save Calendar display preference", error)
            }
        }
    }
}
