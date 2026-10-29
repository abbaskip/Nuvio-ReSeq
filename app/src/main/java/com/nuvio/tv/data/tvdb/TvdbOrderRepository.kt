package com.nuvio.tv.data.tvdb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class TvdbEpisodeOrder(
    val selectedType: String,
    val effectiveType: String,
    val episodes: List<TvdbEpisode>,
    val officialEpisodes: List<TvdbEpisode>,
    val stale: Boolean = false,
    val relatedOrders: Map<String, List<TvdbEpisode>> = emptyMap()
)

/** Bounded metadata cache, not a separate episode/history database. */
@Singleton
class TvdbOrderRepository @Inject constructor(private val api: TvdbClient) {
    private data class Cached<T>(val value: T, val at: Long)
    private val mutex = Mutex()
    private val discoveries = object : LinkedHashMap<Long, Cached<List<TvdbOrder>>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Cached<List<TvdbOrder>>>?) = size > 32
    }
    private val orders = object : LinkedHashMap<String, Cached<TvdbEpisodeOrder>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<TvdbEpisodeOrder>>?) = size > 32
    }
    private val missingDirect = object : LinkedHashMap<String, Long>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 32
    }
    private fun fresh(at: Long) = System.currentTimeMillis() - at in 0 until 24 * 60 * 60 * 1000L

    suspend fun available(profileId: Int, seriesId: Long): List<TvdbOrder> = mutex.withLock {
        discover(profileId, seriesId)
    }

    private suspend fun discover(profileId: Int, seriesId: Long): List<TvdbOrder> {
        discoveries[seriesId]?.takeIf { fresh(it.at) }?.let { return it.value }
        val result = api.orders(profileId, seriesId)
        discoveries[seriesId] = Cached(result, System.currentTimeMillis())
        return result
    }

    suspend fun load(profileId: Int, seriesId: Long, selected: String, language: String = "eng",
        includeRelated: Boolean = false): TvdbEpisodeOrder = mutex.withLock {
        val current = loadBase(profileId, seriesId, selected, language)
        if (!includeRelated) return@withLock current
        val related = linkedMapOf("official" to current.officialEpisodes, selected to current.episodes)
        // Supplementary metadata must not turn a successful selected-order load
        // into a timeout. Partial supplementary reads are safe: unknown links block.
        withTimeoutOrNull(5_000L) { for (type in discover(profileId, seriesId).map { it.type }) {
            if (type in related) continue
            try {
                val key = "$seriesId:$type:$language"
                val cached = orders[key]?.takeIf { fresh(it.at) }?.value?.episodes
                if (cached == null && missingDirect[key]?.let(::fresh) == true) continue
                val episodes = cached
                    ?: api.episodes(profileId, seriesId, type, language).also { rows ->
                        if (rows.isNotEmpty()) orders[key] = Cached(TvdbEpisodeOrder(type, type, rows, current.officialEpisodes), System.currentTimeMillis())
                        else missingDirect[key] = System.currentTimeMillis()
                    }
                if (episodes.isNotEmpty()) related[type] = episodes
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: TvdbException) { /* Only the selected order may perform an expensive season fallback. */ }
        } }
        current.copy(relatedOrders = related)
    }

    private suspend fun loadBase(profileId: Int, seriesId: Long, selected: String, language: String): TvdbEpisodeOrder {
        val key = "$seriesId:$selected:$language"
        val cached = orders[key]
        cached?.takeIf { fresh(it.at) }?.let { return it.value }
        return try {
            val available = discover(profileId, seriesId)
            // Only an affirmative discovery response establishes unavailability.
            var effective = if (selected == "official" || available.any { it.type == selected }) selected else "official"
            val officialKey = "$seriesId:official:$language"
            val official = orders[officialKey]?.takeIf { fresh(it.at) }?.value?.officialEpisodes
                ?: api.episodes(profileId, seriesId, "official", language).also { rows ->
                    if (rows.isNotEmpty()) orders[officialKey] = Cached(
                        TvdbEpisodeOrder("official", "official", rows, rows), System.currentTimeMillis())
                }
            var episodes = if (effective == "official") official else try {
                api.episodes(profileId, seriesId, effective, language)
            } catch (e: TvdbException) {
                if (e.reason != TvdbException.Reason.UNAVAILABLE) throw e
                emptyList()
            }
            if (episodes.isEmpty() && effective != "official") {
                val base = official.associateBy { it.id }
                episodes = api.episodesFromSeasons(profileId, seriesId, effective).map { episode ->
                    base[episode.id]?.copy(coordinates = episode.coordinates) ?: episode
                }
                if (episodes.isEmpty()) { effective = "official"; episodes = official }
            }
            if (episodes.isEmpty() || official.isEmpty()) throw TvdbException(TvdbException.Reason.UNAVAILABLE)
            TvdbEpisodeOrder(selected, effective, episodes, official).also {
                orders[key] = Cached(it, System.currentTimeMillis())
            }
        } catch (e: CancellationException) { throw e }
        catch (e: TvdbException) {
            // Authentication errors are not treated as missing orders. Existing
            // complete data remains usable with an explicit stale indication.
            cached?.value?.copy(stale = true) ?: throw e
        }
    }
}
