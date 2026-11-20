package com.nuvio.tv.livetv.calendar

import android.util.Log
import androidx.datastore.preferences.core.Preferences
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

internal data class CalendarSelection(val mode: CalendarMode, val filter: CalendarFilter, val allSports: Boolean = false)

@Singleton
internal class CalendarPreferences @Inject constructor(private val stores: ProfileDataStoreFactory) {
    private val modeKey = stringPreferencesKey("view")
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
            prefs[allSportsKey] ?: false
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun setMode(profile: Int, mode: CalendarMode) = write(profile, modeKey, mode.name)
    fun setFilter(profile: Int, filter: CalendarFilter) = write(profile, filterKey, filter.name)

    fun setAllSports(profile: Int, enabled: Boolean) = write(profile, allSportsKey, enabled)

    private fun <T> write(profile: Int, key: Preferences.Key<T>, value: T) {
        writes.launch {
            try {
                stores.get(profile, "calendar_display").edit { it[key] = value }
            } catch (error: IOException) {
                Log.w("Calendar", "Could not save Calendar display preference", error)
            }
        }
    }
}
