package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.domain.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbEpisodeResolver @Inject constructor(
    private val preferences: TvdbOrderStore,
    private val orders: TvdbOrderRepository,
    private val api: TvdbClient,
    private val tmdb: TmdbService
) {
    private data class ProjectionKey(val profile: Int, val meta: Meta, val selection: TvdbOrderPreference,
        val order: TvdbEpisodeOrder, val sources: Map<Long, TvdbSourceReference>)
    private val projectionMutex = Mutex()
    private val projections = object : LinkedHashMap<ProjectionKey, Meta>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProjectionKey, Meta>?) = size > 16
    }

    private suspend fun project(profile: Int, meta: Meta, selection: TvdbOrderPreference,
        order: TvdbEpisodeOrder, sources: Map<Long, TvdbSourceReference>): Meta = projectionMutex.withLock {
        projections.getOrPut(ProjectionKey(profile, meta, selection, order, sources)) {
            tvdbEpisodeOverlay(meta, selection.seriesId, order, sources)
        }
    }

    suspend fun seriesId(profileId: Int, meta: Meta, aliases: Collection<String>): Long? {
        preferences.knownSeries(profileId, aliases)?.let { return it }
        meta.id.takeIf { it.startsWith("tvdb:") }?.substringAfter(':')?.substringBefore(':')?.toLongOrNull()?.let { return it }
        val imdb = meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") }
            ?: meta.id.takeIf { it.startsWith("tmdb:") }?.substringAfter(':')?.toIntOrNull()?.let {
                tmdb.tmdbToImdb(it, "series")
            }
        return imdb?.let { api.seriesByRemoteId(profileId, it) }
    }

    suspend fun apply(profileId: Int, meta: Meta): Meta {
        if (meta.apiType !in listOf("series", "tv") || com.nuvio.tv.data.mediaserver.ServerItemRef.isServerId(meta.id)) return meta
        val aliases = listOfNotNull(meta.id, meta.imdbId)
        repeat(3) {
            val selected = preferences.selected(profileId, aliases) ?: return meta
            val result = try {
                withTimeoutOrNull(20_000L) {
                    withContext(Dispatchers.Default) {
                        val started = System.nanoTime()
                        fun timing(stage: String) { android.util.Log.d("TvdbResolution", "series=${selected.seriesId} order=${selected.orderType} stage=$stage elapsedMs=${(System.nanoTime() - started) / 1_000_000}") }
                        timing("load")
                        val order = orders.load(profileId, selected.seriesId, selected.orderType, includeRelated = true)
                        timing("metadata")
                        val existing = preferences.sources(profileId, selected.seriesId)
                        val proposed = project(profileId, meta, selected, order, existing)
                        timing("mapped")
                        val references = proposed.videos.flatMap { video ->
                            video.episodeIdentity?.providerSources.orEmpty().map { source ->
                                TvdbSourceReference(source.episodeIds.first(), source.videoId, source.coordinates,
                                    tvdbEpisodeMatchTitle(source.title), canonicalEpisodeIds = source.episodeIds)
                            }
                        }
                        val sources = preferences.rememberSources(profileId, selected.seriesId, references)
                        timing("aliases")
                        (if (sources == existing) proposed else project(profileId, meta, selected, order, sources))
                            .also { timing("complete") }
                    }
                } ?: fallback(meta, selected, "TVDB timed out. Using metadata provider episodes.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { fallback(meta, selected, "TVDB unavailable. Using metadata provider episodes.") }
            if (preferences.selected(profileId, aliases) == selected) return result
        }
        return meta // a rapidly changing selection is resolved by the caller's reload
    }

    /** Resolve an immutable playback selection without changing profile preferences. */
    suspend fun forOrder(profileId: Int, meta: Meta, selection: TvdbOrderPreference): Meta = withContext(Dispatchers.Default) {
        project(profileId, meta, selection,
            orders.load(profileId, selection.seriesId, selection.orderType, includeRelated = true),
            preferences.sources(profileId, selection.seriesId))
    }

    private fun fallback(meta: Meta, selected: TvdbOrderPreference, message: String) = meta.copy(
        episodeOrder = EpisodeOrderStatus(selected.seriesId, selected.orderType, null, message))
}
