package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.TvMixShow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal fun decodeTvMix(raw: String): TvMix {
    val mix = Gson().fromJson(raw, TvMix::class.java)
    require(mix.id.isNotBlank() && mix.name.isNotBlank() && mix.name.length <= 100)
    require(mix.shows.all { it.id.isNotBlank() && it.name.isNotBlank() && it.type in listOf("series", "tv", "anime") })
    require(mix.shows.indices.all { i -> mix.shows.take(i).none { it.sameShow(mix.shows[i]) } })
    return mix
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TvMixStore @Inject constructor(private val factory: ProfileDataStoreFactory, private val profiles: ProfileManager) {
    private val gson = Gson()
    private fun store(id: Int) = factory.get(id, "tv_mix")
    fun observe(id: Int) = store(id).data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (!key.name.startsWith("mix:")) null else runCatching { decodeTvMix(value as String) }.getOrNull()
        }.sortedWith(compareBy<TvMix> { it.name.lowercase() }.thenBy { it.id })
    }
    val mixes = profiles.activeProfileId.flatMapLatest(::observe)
    suspend fun save(profileId: Int, mix: TvMix) {
        val raw = gson.toJson(mix)
        decodeTvMix(raw)
        store(profileId).edit { it[stringPreferencesKey("mix:${mix.id}")] = raw }
    }
    suspend fun add(profileId: Int, mixId: String, show: TvMixShow) {
        store(profileId).edit { prefs ->
            val key = stringPreferencesKey("mix:$mixId")
            val current = prefs[key]?.let(::decodeTvMix) ?: error("Mix no longer exists")
            val updated = current.adding(show)
            val raw = gson.toJson(updated)
            decodeTvMix(raw)
            prefs[key] = raw
        }
    }
    suspend fun delete(profileId: Int, id: String) { store(profileId).edit { it.remove(stringPreferencesKey("mix:$id")) } }
    suspend fun export(profileId: Int): Map<String, String> = store(profileId).data.first().asMap()
        .mapNotNull { (key, value) -> if (key.name.startsWith("mix:")) key.name to (value as String) else null }.toMap()
    suspend fun restore(profileId: Int, values: Map<String, String>) {
        validateTvMixBackup(values)
        store(profileId).edit { prefs ->
            prefs.clear()
            values.forEach { (key, value) -> prefs[stringPreferencesKey(key)] = value }
        }
    }
}

internal fun validateTvMixBackup(values: Map<String, String>) {
    values.forEach { (key, value) -> require(key == "mix:${decodeTvMix(value).id}") { "Invalid TV Mix backup" } }
}
