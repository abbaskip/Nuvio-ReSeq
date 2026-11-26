package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.streams.streamSourceKey
import com.nuvio.tv.core.usenet.BuiltInUsenetService
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** A presentation of existing sources, not synthetic HTTP addons or a second addon registry. */
@Singleton
class IptvStreamSources @Inject constructor(
    private val addons: AddonRepository,
    private val preferences: AddonPreferences,
    private val live: LiveTvPreferences,
    private val catalogs: OnDemandRepository? = null,
    private val usenet: UsenetSourceSettings? = null
) {
    fun sources(): Flow<List<Addon>> {
        val settingsAndCatalogue = combine(live.settings, catalogs?.version ?: flowOf(0)) { settings, _ -> settings }
        val usenetSnapshots = usenet?.snapshots ?: flowOf(0 to Result.success(UsenetSourceConfiguration()))
        return combine(addons.getInstalledAddons(), live.playlists, settingsAndCatalogue,
            preferences.mixedStreamSources, usenetSnapshots) { installed, playlists, settings, saved, usenetSnapshot ->
            val eligible = if (settings.onDemandInStreams) playlists.filter { it.enabled && it.supportsVod && it.importVod } else emptyList()
            val unique = uniqueM3uProviders(eligible, saved.first, saved.second, ::accountKey)
            val direct = usenetSnapshot.second.getOrNull()?.takeIf { it.ready || (it.configured && it.firstUseAccepted) }?.let { config ->
                Addon(id = BuiltInUsenetService.SOURCE_KEY, name = BuiltInUsenetService.GROUP_NAME, version = "Usenet",
                    description = "Manage providers and indexers in Settings → Integrations → Usenet.",
                    logo = null, baseUrl = BuiltInUsenetService.SOURCE_KEY, catalogs = emptyList(),
                    types = listOf(ContentType.MOVIE, ContentType.SERIES),
                    resources = listOf(AddonResource("stream", listOf("movie", "series", "tv", "show", "anime"), null)),
                    enabled = config.ready)
            }
            mixedSources(installed, unique, saved.first, saved.second, settings.onDemandStreamsPosition, direct)
        }.flowOn(Dispatchers.IO)
    }

    /** Effective duplicate accounts can have different category selections and cached catalogues. */
    internal suspend fun alternatives(id: String): List<PlaylistSource> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sources = live.playlists.first().filter { it.enabled && it.supportsVod && it.importVod }
        if (sources.none(PlaylistSource::isM3u)) return@withContext emptyList()
        val active = sources.firstOrNull { it.id == id } ?: return@withContext emptyList()
        val key = accountKey(active) ?: return@withContext emptyList()
        val disabled = preferences.mixedStreamSources.first().second
        sources.filter { it.id != id && (it.isM3u || active.isM3u) && PREFIX + it.id !in disabled && accountKey(it) == key }
    }

    private fun accountKey(source: PlaylistSource) =
        if (source.isM3u) catalogs?.m3uProviderKey(source) else M3uLogin.native(source)?.key()

    suspend fun setOrder(sources: List<Addon>) {
        preferences.setStreamSourceOrder(sources.map { it.baseUrl })
        addons.setAddonOrder(sources.filterNot { isVirtual(it.baseUrl) }.map { it.baseUrl })
    }

    suspend fun setEnabled(key: String, enabled: Boolean) {
        when {
            key == BuiltInUsenetService.SOURCE_KEY -> usenet?.setEnabled(enabled)
            isPlaylist(key) -> preferences.setPlaylistStreamEnabled(key, enabled)
            else -> addons.setAddonEnabled(key, enabled)
        }
    }

    companion object {
        const val PREFIX = "iptv-playlist:"
        fun isPlaylist(key: String) = key.startsWith(PREFIX)
        fun isVirtual(key: String) = isPlaylist(key) || key == BuiltInUsenetService.SOURCE_KEY

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
            saved: List<String>, disabled: Set<String>, legacyPosition: String = "LAST", direct: Addon? = null): List<Addon> {
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
            val all = installed + virtual + listOfNotNull(direct)
            if (saved.isEmpty()) {
                val newKeys = playlists.filter(PlaylistSource::isM3u).mapTo(HashSet()) { PREFIX + it.id }
                val (newSources, original) = virtual.partition { it.baseUrl in newKeys }
                return when (legacyPosition) {
                    "FIRST" -> original + installed + newSources + listOfNotNull(direct)
                    "AFTER_FIRST" -> installed.take(1) + original + installed.drop(1) + newSources + listOfNotNull(direct)
                    else -> installed + original + newSources + listOfNotNull(direct)
                }
            }
            val byKey = all.associateBy { streamSourceKey(it.baseUrl) }
            // Keep IPTV anchors while accepting a changed real-addon order from Nuvio cloud.
            val real = installed.iterator()
            val seen = mutableSetOf<String>()
            val ordered = saved.map(::streamSourceKey).mapNotNull { key ->
                val source = if (isVirtual(key)) byKey[key] else if (key in byKey && real.hasNext()) real.next() else null
                source?.takeIf { seen.add(it.baseUrl) }
            }
            return ordered + all.filter { seen.add(it.baseUrl) }
        }
    }
}
