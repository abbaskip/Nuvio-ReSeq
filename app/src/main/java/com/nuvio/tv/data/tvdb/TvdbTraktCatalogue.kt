package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public catalogue correspondence only. Episode proof may establish a different
 * native parent; it never merges profile preferences or remote history. */
internal class TvdbTraktCatalogue(private val api: TraktApi, private val auth: TraktAuthService) {
    private data class Catalogue(val parent: TrackingExternalIds, val episodes: List<TraktEpisodeDto>, val title: String?, val year: Int?)
    private data class Timed<T>(val time: Long, val value: T)
    private val mutex = Mutex()
    private val catalogues = object : LinkedHashMap<String, Timed<Catalogue>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed<Catalogue>>?) = size > 64
    }
    private val parents = object : LinkedHashMap<String, TraktShowDto>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TraktShowDto>?) = size > 64
    }
    private val verifiedLinks = object : LinkedHashMap<String, MutableSet<Long>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableSet<Long>>?) = size > 64
    }
    private val negative = object : LinkedHashMap<String, Long>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    private data class ProjectionKey(val profile: Int, val series: Long, val parent: String,
        val content: List<com.nuvio.tv.domain.model.EpisodeContentIdentity>, val catalogue: Catalogue)
    // Cache public identity correspondence, never watched history or numeric display slots.
    private val projections = object : LinkedHashMap<ProjectionKey, List<TvdbTrackingTarget>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ProjectionKey, List<TvdbTrackingTarget>>?) = size > 64
    }
    private val ttl = 6 * 60 * 60_000L

    private fun TraktIdsDto.external() = TrackingExternalIds(imdb, tmdb?.toLong(), tvdb?.toString(), trakt?.toLong())

    private suspend fun catalogue(profile: Int, ids: TrackingExternalIds, title: String? = null, year: Int? = null): Catalogue? {
        val id = ids.trakt ?: return null
        val key = "$profile:$id"
        val now = System.currentTimeMillis()
        catalogues[key]?.takeIf { now - it.time < ttl }?.let { return it.value }
        val response = auth.executeAuthorizedRequest { header -> api.getShowSeasons(header, id.toString(), "episodes,full") }
        if (response?.isSuccessful != true) return null
        val result = Catalogue(ids, response.body().orEmpty().flatMap { it.episodes.orEmpty() }, title, year)
        catalogues[key] = Timed(now, result)
        return result
    }

    suspend fun targets(profile: Int, meta: Meta, requiredIds: Set<Long>? = null): List<TvdbTrackingTarget> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { mutex.withLock {
        val started = System.nanoTime()
        val status = meta.episodeOrder ?: return@withLock emptyList()
        val parent = meta.imdbId ?: meta.id
        val parentKey = "$profile:$parent"
        val base = parents[parentKey] ?: (if (parent.startsWith("tt")) auth.executeAuthorizedRequest { header -> api.searchById(header, "imdb", parent, "show") }
            ?.body().orEmpty().mapNotNull { it.show }.singleOrNull {
                it.ids?.imdb == parent || it.ids?.tvdb?.toLong() == status.seriesId
            }?.let { show -> show.copy(ids = show.ids?.let { ids -> ids.copy(imdb = ids.imdb ?: parent) }).also { parents[parentKey] = it } } else null)
        val loaded = mutableListOf<Catalogue>()
        base?.let { it.ids?.external()?.let { ids -> catalogue(profile, ids, it.title, it.year) } }?.let(loaded::add)
        val seriesKey = "$profile:${status.seriesId}"
        verifiedLinks[seriesKey].orEmpty().forEach { id ->
            catalogues["$profile:$id"]?.takeIf { System.currentTimeMillis() - it.time < ttl }?.value?.let { if (it !in loaded) loaded += it }
        }
        val index by lazy { TvdbTrackerContentIndex(meta) }
        fun mapped(): List<TvdbTrackingTarget> = loaded.flatMap { catalogue ->
            val key = ProjectionKey(profile, status.seriesId, parent, status.content, catalogue)
            projections.getOrPut(key) { catalogue.episodes.mapNotNull { row ->
                val ids = index.match(row.ids?.tvdb?.toLong(), row.title, row.firstAired) ?: return@mapNotNull null
                // A foreign parent needs independent date corroboration even for an exact ID hit.
                if (catalogue.parent.imdb != parent && status.content.none { record ->
                    record.episodeIds.toSet() == ids && (record.tvdbId == row.ids?.tvdb?.toLong() ||
                        trackerEpisodeTitleKey(record.title) == row.title?.let(::trackerEpisodeTitleKey)) &&
                        tvdbAiredDatesAgree(record.aired, row.firstAired)
                }) return@mapNotNull null
                val s = row.season ?: return@mapNotNull null
                val e = row.number ?: return@mapNotNull null
                TvdbTrackingTarget(TrackingEpisode(s, e, row.title, row.ids?.tvdb?.toLong(),
                    tmdbId = row.ids?.tmdb?.toLong(), traktId = row.ids?.trakt, numberingProvider = "trakt"), ids, catalogue.parent, catalogue.title, catalogue.year)
            } }
        }.distinctBy { Triple(it.parentIds?.trakt, it.episode.season, it.episode.number) }

        val initial = mapped()
        val covered = initial.flatMap { it.contentIds }.toSet()
        val records = status.content.filter { it.officialCoordinates != null }
        val candidates = if (requiredIds != null) records.filter { it.episodeIds.any(requiredIds::contains) && !covered.containsAll(it.episodeIds) }
        else records.groupBy { it.officialCoordinates!!.season }.toSortedMap().values.mapNotNull { season ->
            season.firstOrNull()?.takeIf { season.none { row -> row.episodeIds.any(covered::contains) } }
        }
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
        for (record in candidates.take(6)) {
            if (mapped().flatMap { it.contentIds }.containsAll(record.episodeIds)) continue
            val lookupKey = "$seriesKey:${record.tvdbId}"
            if (negative[lookupKey]?.let { System.currentTimeMillis() - it < ttl } == true) continue
            val hits = auth.executeAuthorizedRequest { header -> api.searchById(header, "tvdb", record.tvdbId.toString(), "episode", extended = "full") }
                ?.body().orEmpty().filter { hit ->
                    val row = hit.episode
                    row?.ids?.tvdb?.toLong() == record.tvdbId && hit.show?.ids?.trakt != null &&
                        (hit.show.ids.imdb != null || hit.show.ids.tmdb != null) &&
                        index.match(record.tvdbId, row.title, row.firstAired) == record.episodeIds.toSet() &&
                        tvdbAiredDatesAgree(record.aired, row.firstAired)
                }.distinctBy { it.show?.ids?.trakt to it.episode?.ids?.trakt }
            val ids = hits.singleOrNull()?.show?.ids?.external()
            val extra = ids?.let { catalogue(profile, it, hits.single().show?.title, hits.single().show?.year) }
            if (extra == null) { negative[lookupKey] = System.currentTimeMillis(); continue }
            if (extra !in loaded) loaded += extra
            ids.trakt?.let { verifiedLinks.getOrPut(seriesKey, ::mutableSetOf).add(it) }
        }
        }
        mapped().also { result ->
            android.util.Log.d("TvdbTracking", "series=${status.seriesId} targets=${result.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    } }
}
