package com.nuvio.tv.livetv.sync

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbCredentialStore
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.livetv.sports.SportsGeminiCredentials
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import javax.inject.Inject

class DriveProviderKeys @Inject constructor(
    private val tmdb: TmdbCredentialStore,
    private val gemini: SportsGeminiCredentials,
    private val debrid: DebridSettingsDataStore,
    private val mdbList: MDBListSettingsDataStore,
    private val animeSkip: AnimeSkipSettingsDataStore
) {
    internal fun observe(profileId: Int) = combine(
        debrid.providerKeysForProfile(profileId), mdbList.apiKeyForProfile(profileId),
        animeSkip.clientIdForProfile(profileId), tmdb.changes, gemini.changes
    ) { debridKeys, mdb, anime, _, _ ->
        buildMap {
            putAll(debridKeys)
            listOf("tmdb" to tmdb.access(profileId).apiKey, "gemini" to gemini.read(profileId),
                "mdblist" to mdb, "animeskip" to anime).forEach { (service, value) ->
                if (!value.isNullOrBlank()) put(service, value)
            }
        }
    }.distinctUntilChanged()

    internal suspend fun restore(profileId: Int, service: String, expected: String, value: String) {
        when (service) {
            "tmdb" -> tmdb.restoreIfUnchanged(profileId, expected, value)
            "gemini" -> gemini.restoreIfUnchanged(profileId, expected, value)
            "mdblist" -> mdbList.setApiKey(value, profileId, expected)
            "animeskip" -> animeSkip.setClientId(value, profileId, expected)
            else -> debrid.setProviderApiKey(service, value, profileId, expected)
        }
    }
}

internal fun providerBackupProfiles(document: JSONObject): JSONObject {
    require(document.optInt("format") == 1)
    val profiles = document.getJSONObject("profiles")
    val services = DebridProviders.all().map { it.id } + listOf("tmdb", "gemini", "mdblist", "animeskip")
    for (id in profiles.keys()) {
        require(id.toIntOrNull() in 1..ProfileManager.MAX_PROFILES)
        val entry = profiles.getJSONObject(id)
        require((entry.opt("name") as? String)?.isNotBlank() == true)
        val keys = entry.getJSONObject("keys")
        for (service in keys.keys()) {
            val value = keys.opt(service)
            require(service in services && value is String && value.isNotBlank() && value.length <= 8192)
        }
    }
    return profiles
}
