package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.streams.streamSourceKey
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/** A presentation of existing sources, not synthetic HTTP addons or a second addon registry. */
@Singleton
class IptvStreamSources @Inject constructor(
    private val addons: AddonRepository,
    private val preferences: AddonPreferences,
    private val live: LiveTvPreferences,
    private val catalogs: OnDemandRepository? = null
) {
    fun sources(): Flow<List<Addon>> = combine(addons.getInstalledAddons(), live.playlists,
        live.settings, preferences.mixedStreamSources, catalogs?.version ?: kotlinx.coroutines.flow.MutableStateFlow(0)) { installed, playlists, settings, saved, _ ->
        val eligible = if (settings.onDemandInStreams) playlists.filter { it.enabled && it.supportsVod && it.importVod } else emptyList()
        val unique = uniqueM3uProviders(eligible, saved.first, saved.second) {
            if (it.isM3u) catalogs?.m3uProviderKey(it) else M3uLogin.native(it)?.key()
        }
        mixedSources(installed, unique, saved.first, saved.second, settings.onDemandStreamsPosition)
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun setOrder(sources: List<Addon>) {
        preferences.setStreamSourceOrder(sources.map { it.baseUrl })
        addons.setAddonOrder(sources.filterNot { isPlaylist(it.baseUrl) }.map { it.baseUrl })
    }

    suspend fun setEnabled(key: String, enabled: Boolean) {
        if (isPlaylist(key)) preferences.setPlaylistStreamEnabled(key, enabled) else addons.setAddonEnabled(key, enabled)
    }

    companion object {
        const val PREFIX = "iptv-playlist:"
        fun isPlaylist(key: String) = key.startsWith(PREFIX)

        internal fun uniqueM3uProviders(playlists: List<PlaylistSource>, saved: List<String>, disabled: Set<String>,
            provider: (PlaylistSource) -> String?): List<PlaylistSource> {
            if (playlists.none(PlaylistSource::isM3u)) return playlists
            val groups = playlists.mapNotNull { pl -> provider(pl)?.let { it to pl } }.groupBy({ it.first }, { it.second })
            val omit = mutableSetOf<String>()
            for (copies in groups.values) {
                if (copies.size < 2 || copies.none(PlaylistSource::isM3u)) continue
                val enabled = copies.filter { PREFIX + it.id !in disabled }
                val preferred = saved.firstNotNullOfOrNull { key -> enabled.find { PREFIX + it.id == key } }
                    ?: enabled.firstOrNull { !it.isM3u } ?: enabled.firstOrNull() ?: copies.first()
                copies.filter { (it.isM3u || preferred.isM3u) && it.id != preferred.id }.forEach { omit.add(it.id) }
            }
            return playlists.filterNot { it.id in omit }
        }

        internal fun mixedSources(installed: List<Addon>, playlists: List<PlaylistSource>,
            saved: List<String>, disabled: Set<String>, legacyPosition: String = "LAST"): List<Addon> {
            val occupied = installed.map { it.displayName }.toMutableSet()
            val counts = playlists.groupingBy { it.name }.eachCount()
            val virtual = playlists.map { playlist ->
                val key = PREFIX + playlist.id
                val baseName = "IPTV · ${playlist.name}"
                val name = if (counts[playlist.name] != 1 || baseName in occupied) "$baseName · ${playlist.id.take(8)}" else baseName
                occupied += name
                Addon(id = key, name = name, version = "IPTV", description = "Movies and series from this playlist. Manage its connection in Settings → IPTV.",
                    logo = null, baseUrl = key, catalogs = emptyList(), types = listOf(ContentType.MOVIE, ContentType.SERIES),
                    resources = listOf(AddonResource("stream", listOf("movie", "series", "tv"), null)), enabled = key !in disabled)
            }
            val all = installed + virtual
            if (saved.isEmpty()) {
                val newKeys = playlists.filter(PlaylistSource::isM3u).mapTo(HashSet()) { PREFIX + it.id }
                val (newSources, original) = virtual.partition { it.baseUrl in newKeys }
                return when (legacyPosition) {
                    "FIRST" -> original + installed + newSources
                    "AFTER_FIRST" -> installed.take(1) + original + installed.drop(1) + newSources
                    else -> installed + original + newSources
                }
            }
            val byKey = all.associateBy { streamSourceKey(it.baseUrl) }
            // Keep IPTV anchors while accepting a changed real-addon order from Nuvio cloud.
            val real = installed.iterator()
            val seen = mutableSetOf<String>()
            val ordered = saved.map(::streamSourceKey).mapNotNull { key ->
                val source = if (isPlaylist(key)) byKey[key] else if (key in byKey && real.hasNext()) real.next() else null
                source?.takeIf { seen.add(it.baseUrl) }
            }
            return ordered + all.filter { seen.add(it.baseUrl) }
        }
    }
}
