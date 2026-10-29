package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.repository.MetaRepositoryImpl
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Destination translation only; existing providers perform all writes and sync. */
@Singleton
class TvdbTrackingResolver @Inject constructor(
    private val store: TvdbOrderStore,
    private val overlay: dagger.Lazy<TvdbEpisodeResolver>,
    private val source: MetaRepositoryImpl,
    private val profiles: ProfileManager,
    private val trakt: TraktApi,
    private val traktAuth: TraktAuthService,
    private val tmdb: TmdbApi,
    private val tmdbService: TmdbService,
    private val local: com.nuvio.tv.data.local.WatchProgressPreferences? = null,
    private val traktProgress: dagger.Lazy<com.nuvio.tv.data.repository.TraktProgressService>? = null,
    private val simkl: dagger.Lazy<com.nuvio.tv.data.simkl.SimklSyncRepository>? = null,
    private val mdbList: dagger.Lazy<com.nuvio.tv.data.mdblist.MdbListSyncRepository>? = null
) {
    suspend fun isSelected(profileId: Int, contentId: String) = store.selected(profileId, listOf(contentId)) != null
    fun observeSelection(profileId: Int, contentId: String) = store.observeSelection(profileId, contentId)
    fun observeSelectedSeries(profileId: Int) = store.observeSelectedSeries(profileId)

    private suspend fun effectiveMetadata(profileId: Int, contentId: String, type: String, context: TvdbPlaybackContext? = null): Meta? {
        if (profileId != profiles.activeProfileId.value) return null
        val meta = source.getCachedMeta(type, contentId) ?: withTimeoutOrNull(8_000L) {
            (source.getMetaFromAllAddons(type, contentId).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
        } ?: return null
        if (context != null && store.knownSeries(profileId, listOf(contentId)) != context.seriesId) return null
        val effective = if (context == null) overlay.get().apply(profileId, meta)
            else overlay.get().forOrder(profileId, meta, TvdbOrderPreference(context.seriesId, context.orderType))
        return effective.takeIf { profiles.activeProfileId.value == profileId && it.episodeOrder?.effectiveType != null }
    }

    /** Shared by the stream screen and both player resume paths through the existing repository. */
    suspend fun projectProgress(profileId: Int, contentId: String, progress: Map<Pair<Int, Int>, WatchProgress>,
        localRecords: List<WatchProgress> = emptyList(), context: TvdbPlaybackContext? = null): Map<Pair<Int, Int>, WatchProgress> = withContext(Dispatchers.Default) {
        projectProgressOnWorker(profileId, contentId, progress, localRecords, context)
    }

    private suspend fun projectProgressOnWorker(profileId: Int, contentId: String, progress: Map<Pair<Int, Int>, WatchProgress>,
        localRecords: List<WatchProgress> = emptyList(), context: TvdbPlaybackContext? = null): Map<Pair<Int, Int>, WatchProgress> {
        if (context == null && !isSelected(profileId, contentId)) return progress
        return try {
            val meta = effectiveMetadata(profileId, contentId, "series", context) ?: return emptyMap()
            val entries = progress.values + localRecords
            val traktEntries = entries.filter { it.trackingProviderId == "trakt" || it.source.startsWith("trakt_") }.toHashSet()
            val targets = if (traktEntries.isNotEmpty()) traktTargets(profileId, meta) else emptyList()
            val otherTargets = if (entries.any { it.contentId != contentId && it.trackingProviderId == "simkl" }) simklTargets(meta) else emptyList()
            val mdbTargets = if (entries.any { it.contentId != contentId && it.trackingProviderId == "mdblist" })
                mdbListTargets(meta, meta.videos.flatMap { it.episodeIdentity?.trackingEpisodeIds.orEmpty() }.toSet()) else emptyList()
            val identified = entries.mapNotNull { entry ->
                if (entry in traktEntries) {
                    val target = targets.filter { it.matchesParent(entry) }.singleOrNull {
                        (entry.traktEpisodeId != null && entry.traktEpisodeId == it.episode.traktId) ||
                            (entry.traktEpisodeId == null && entry.season == it.episode.season && entry.episode == it.episode.number)
                    }
                    if (target == null) entry.copy(tvdbEpisodeId = null, tvdbEpisodeIds = emptyList())
                    else entry.copy(contentId = contentId, tvdbEpisodeId = target.contentIds.first(), tvdbEpisodeIds = target.contentIds.toList())
                } else if (entry.contentId != contentId) {
                    val selectedTargets = when (entry.trackingProviderId) { "simkl" -> otherTargets; "mdblist" -> mdbTargets; else -> emptyList() }
                    val target = selectedTargets.filter { it.matchesParent(entry) }.singleOrNull {
                        it.episode.season == entry.season && it.episode.number == entry.episode
                    } ?: return@mapNotNull null
                    entry.copy(contentId = contentId, tvdbEpisodeId = target.contentIds.first(), tvdbEpisodeIds = target.contentIds.toList())
                } else identifyProgress(profileId, entry)
            }
            reconcileTvdbProgress(meta, emptyMap(), identified)

        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyMap() }
    }

    suspend fun identifyProgress(profileId: Int, progress: WatchProgress): WatchProgress {
        if (progress.providerDefaultPlayback) return progress
        progress.tvdbContext?.let { return progress.copy(tvdbEpisodeIds = it.episodeIds, tvdbEpisodeId = it.episodeIds.firstOrNull()) }
        if (progress.tvdbEpisodeIds != null || progress.tvdbEpisodeId != null || progress.season == null || progress.episode == null) return progress
        if (progress.source != WatchProgress.SOURCE_LOCAL || progress.trackingProviderId != null) return progress
        val selected = store.selected(profileId, listOf(progress.contentId)) ?: return progress
        val known = store.sources(profileId, selected.seriesId).values
        val ref = known.filter {
            it.videoId == progress.videoId && it.coordinates.season == progress.season && it.coordinates.episode == progress.episode &&
                !it.titleKey.isNullOrBlank() && progress.episodeTitle?.let { title ->
                    normalizeEpisodeName(title) == it.titleKey ||
                        tvdbEpisodeMatchTitle(title) == it.titleKey ||
                        tvdbEpisodeMatchTitle(title).replace(" part ", " ") == it.titleKey ||
                        (it.canonicalEpisodeId != null && tvdbEpisodeBaseTitle(title) == tvdbEpisodeBaseTitle(it.titleKey))
                } == true
        }.distinctBy { it.trackingEpisodeId }.singleOrNull()
        if (ref != null) return progress.copy(tvdbEpisodeId = ref.trackingEpisodeId, tvdbEpisodeIds = ref.trackingEpisodeIds)
        if (known.any { it.videoId == progress.videoId && it.coordinates.season == progress.season && it.coordinates.episode == progress.episode }) return progress
        val metadata = try { effectiveMetadata(profileId, progress.contentId, progress.contentType) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return progress }
        val references = metadata?.videos.orEmpty()
            .flatMap { it.episodeIdentity?.providerSources.orEmpty() }.filter { source ->
                source.videoId == progress.videoId && source.coordinates.season == progress.season && source.coordinates.episode == progress.episode &&
                    progress.episodeTitle?.let { tvdbEpisodeMatchTitle(it) == tvdbEpisodeMatchTitle(source.title) } == true
            }.map { it.episodeIds.toSet() }.distinct().singleOrNull() ?: return progress
        return progress.copy(tvdbEpisodeId = references.first(), tvdbEpisodeIds = references.toList())
    }

    suspend fun nativeCompletedEpisodes(profileId: Int, progress: WatchProgress): List<Pair<EpisodeCoordinates, List<Long>>> {
        val context = progress.tvdbContext ?: return emptyList()
        val meta = effectiveMetadata(profileId, progress.contentId, "series", context) ?: return emptyList()
        val complete = local?.getAllRawEntries(profileId)?.values.orEmpty().filter { it.contentId == progress.contentId }
            .flatMap { it.tvdbWatchedIds }.toSet() + progress.tvdbWatchedIds
        return meta.videos.flatMap { it.episodeIdentity?.providerSources.orEmpty() }.filter { ref ->
            ref.episodeIds.any(progress.tvdbContentIds::contains) && complete.containsAll(ref.episodeIds)
        }.distinctBy { it.coordinates }.map { it.coordinates to it.episodeIds }
    }

    suspend fun selectionContext(profileId: Int, reference: TrackingMediaReference): TvdbPlaybackContext? {
        val catalog = reference.catalog ?: return null
        val episode = reference.episode ?: return null
        val meta = effectiveMetadata(profileId, catalog.contentId, catalog.contentType) ?: return null
        return meta.videos.singleOrNull { video -> video.sourceSeason == episode.season && video.sourceEpisode == episode.number &&
            (catalog.videoId == null || video.id == catalog.videoId) }?.tvdbPlaybackContext()
    }

    suspend fun nativeEpisodes(profileId: Int, contentId: String, context: TvdbPlaybackContext): List<EpisodeCoordinates> =
        effectiveMetadata(profileId, contentId, "series", context)?.videos.orEmpty()
            .flatMap { it.episodeIdentity?.providerSources.orEmpty() }.filter { it.episodeIds.any(context.episodeIds::contains) }
            .map { it.coordinates }.distinct()

    suspend fun resolve(profileId: Int, provider: TrackingProviderId, reference: TrackingMediaReference): TrackingMediaReference? {
        return resolveAll(profileId, provider, reference)?.singleOrNull()
    }

    /** Reuse native snapshots for each independently verified parent. A list preserves
     * original/revival S1E1 simultaneously instead of collapsing their numeric keys. */
    fun canonicalTraktRecords(profileId: Int, contentId: String, context: TvdbPlaybackContext? = null): kotlinx.coroutines.flow.Flow<List<WatchProgress>>? {
        val native = traktProgress?.get() ?: return null
        return kotlinx.coroutines.flow.flow {
            val meta = effectiveMetadata(profileId, contentId, "series", context)
            val parents = meta?.let { traktTargets(profileId, it).mapNotNull { row ->
                row.parentIds?.imdb ?: row.parentIds?.trakt?.let { id -> "trakt:$id" }
            } }.orEmpty()
            val flows = (listOf(contentId) + parents).distinct().map(native::observeCanonicalEpisodeProgress)
            emitAll(kotlinx.coroutines.flow.combine(flows) { maps -> maps.flatMap { it.values } })
        }
    }

    private val traktCatalogue = TvdbTraktCatalogue(trakt, traktAuth)
    private suspend fun traktTargets(profileId: Int, meta: Meta, ids: Set<Long>? = null) = traktCatalogue.targets(profileId, meta, ids)

    private suspend fun simklTargets(meta: Meta): List<TvdbTrackingTarget> {
        val repository = simkl?.get() ?: return emptyList()
        repository.ensureLoaded()
        val status = meta.episodeOrder ?: return emptyList()
        val records = status.content.groupBy { it.tvdbId }
        return repository.state.value.snapshot.entries.flatMap { entry ->
            val external = entry.media?.ids.orEmpty()
            fun value(key: String) = external[key]?.jsonPrimitive?.contentOrNull
            val native = value("tvdb") == status.seriesId.toString() || value("imdb") == (meta.imdbId ?: meta.id)
            val parent = TrackingExternalIds(imdb = value("imdb") ?: (meta.imdbId ?: meta.id.takeIf { it.startsWith("tt") }).takeIf { native },
                tmdb = value("tmdb")?.toLongOrNull(), tvdb = value("tvdb"), simkl = value("simkl")?.toLongOrNull())
            entry.seasons.flatMap { season -> season.episodes.mapNotNull { episode ->
                val record = records[episode.ids?.tvdbId]?.singleOrNull() ?: return@mapNotNull null
                val s = season.number ?: return@mapNotNull null
                val e = episode.number ?: return@mapNotNull null
                if (s == 0 && record.officialCoordinates?.season?.let { it > 0 } == true) return@mapNotNull null
                if (!native && (parent.simkl == null && parent.imdb == null ||
                    episode.tvdb?.let { it.season to it.episode } != record.officialCoordinates?.let { it.season to it.episode })) return@mapNotNull null
                TvdbTrackingTarget(TrackingEpisode(s, e, record.title, record.tvdbId, numberingProvider = "simkl"), record.episodeIds.toSet(), parent, entry.media?.title, entry.media?.year)
            } }
        }
    }

    private suspend fun mdbListTargets(meta: Meta, sourceIds: Set<Long>): List<TvdbTrackingTarget> {
        val status = meta.episodeOrder ?: return emptyList()
        val content = TvdbTrackerContentIndex(meta)
        val catalogueIds = status.content.mapTo(hashSetOf()) { it.tvdbId }
        val known = mdbList?.get()?.let { repository ->
            repository.ensureLoaded()
            repository.state.value.snapshot?.watched.orEmpty().mapNotNull { row ->
                // An undated history row can only match a known TVDB record ID.
                // Do not build or scan the series index for unrelated library entries.
                if (row.episodeTvdbId !in catalogueIds) return@mapNotNull null
                val native = row.media.ids.tvdb == status.seriesId || row.media.ids.imdb == (meta.imdbId ?: meta.id)
                if (!native && (row.episodeTvdbId == null || row.episodeTmdbId == null || row.media.ids.tmdb == null)) return@mapNotNull null
                val coverage = content.match(row.episodeTvdbId, row.episodeTitle, null) ?: return@mapNotNull null
                val s = row.season ?: return@mapNotNull null
                val e = row.episode ?: return@mapNotNull null
                TvdbTrackingTarget(TrackingEpisode(s, e, row.episodeTitle, row.episodeTvdbId,
                    tmdbId = row.episodeTmdbId, numberingProvider = "mdblist"), coverage,
                    TrackingExternalIds(imdb = row.media.ids.imdb, tmdb = row.media.ids.tmdb, tvdb = row.media.ids.tvdb?.toString()))
            }
        }.orEmpty()
        if (known.flatMap { it.contentIds }.containsAll(sourceIds)) return known
        if (tmdbService.apiKey().isBlank()) return known
        val found = status.content.filter { it.officialCoordinates != null && it.episodeIds.any(sourceIds::contains) }.mapNotNull { row ->
            val result = tmdb.findByExternalId(row.tvdbId.toString(), tmdbService.apiKey(), "tvdb_id").body()?.tvEpisodeResults?.singleOrNull()
                ?: return@mapNotNull null
            val showId = result.showId ?: return@mapNotNull null
            val parent = tmdb.getTvExternalIds(showId, tmdbService.apiKey()).body() ?: return@mapNotNull null
            if (parent.tvdbId?.toLong() != status.seriesId) {
                if (!tvdbAiredDatesAgree(row.aired, result.airDate)) return@mapNotNull null
                val reverse = tmdb.getTvEpisodeExternalIds(showId, result.seasonNumber ?: return@mapNotNull null,
                    result.episodeNumber ?: return@mapNotNull null, tmdbService.apiKey()).body()
                if (reverse?.id != result.id || reverse.tvdbId?.toLong() != row.tvdbId) return@mapNotNull null
            }
            val coverage = content.match(row.tvdbId, result.name ?: result.title, result.airDate) ?: return@mapNotNull null
            val s = result.seasonNumber ?: return@mapNotNull null
            val e = result.episodeNumber ?: return@mapNotNull null
            TvdbTrackingTarget(TrackingEpisode(s, e, result.name ?: result.title,
                tvdbId = row.tvdbId.takeIf { row.episodeIds.toSet() == coverage },
                tmdbId = result.id.toLong(), numberingProvider = "mdblist"), coverage,
                TrackingExternalIds(imdb = parent.imdbId, tmdb = showId.toLong(), tvdb = parent.tvdbId?.toString()))
        }
        return (known + found).distinctBy { Triple(it.parentIds?.tmdb, it.episode.season, it.episode.number) }
    }

    /** No selection means the original provider path, including its fallbacks. */
    suspend fun resolveAll(profileId: Int, provider: TrackingProviderId, reference: TrackingMediaReference,
        completed: Boolean? = null, coverageChangesOnly: Boolean = false): List<TrackingMediaReference>? {
        if (reference.catalog?.tvdbCoverageVerified == false) return emptyList()
        if (reference.catalog?.providerDefault == true) return if (coverageChangesOnly) emptyList() else listOf(reference)
        val contentId = reference.catalog?.contentId ?: return listOf(reference)
        val context = reference.catalog.tvdbContext
        if (reference.episode == null || (context == null && !isSelected(profileId, contentId))) return if (coverageChangesOnly) emptyList() else listOf(reference)
        // Playback/scrobble callbacks run on Main. All destination catalogue work,
        // including large Simkl/MDBList snapshots, must stay off that dispatcher.
        return withContext(Dispatchers.Default) {
            resolveSelected(profileId, provider, reference, completed, coverageChangesOnly)
        }
    }

    private data class ResolvedTargets(val context: TvdbPlaybackContext, val targets: List<TvdbTrackingTarget>) {
        val weights = context.progressWeights(additionalIds = targets.flatMap { it.contentIds })
        fun supportsProgress(): Boolean = context.episodeIds.progressRanges(context.progressWeights()) ==
            context.episodeIds.progressRanges(weights)
    }

    private suspend fun selectedTargets(profileId: Int, provider: TrackingProviderId, reference: TrackingMediaReference): ResolvedTargets? {
        val contentId = reference.catalog?.contentId ?: return null
        val episode = reference.episode ?: return null
        val context = reference.catalog.tvdbContext
        val meta = effectiveMetadata(profileId, contentId, reference.catalog.contentType, context) ?: return null
        if (meta.episodeOrder?.content.isNullOrEmpty()) return null
        val videos = meta.videos
        val source = videos.filter { it.hasResolvedSource && (context?.episodeId == null || it.episodeIdentity?.tvdbEpisodeId == context.episodeId) &&
            (reference.catalog.videoId == null || it.id == reference.catalog.videoId) &&
            it.sourceSeason == episode.season && it.sourceEpisode == episode.number }
            .distinctEpisodeSources().singleOrNull() ?: return null
        val sourceIds = source.episodeIdentity?.trackingEpisodeIds?.toSet() ?: return null
        val records = meta.episodeOrder.content.associateBy { it.tvdbId }
        val supplied = context?.episodeIds ?: reference.catalog.tvdbEpisodeIds
        val ids = supplied?.flatMap { records[it]?.episodeIds ?: listOf(it) }?.toSet() ?: sourceIds
        if (ids.isEmpty() || ids != sourceIds) return null
        val targets = when (provider) {
            TrackingProviderId.TRAKT -> traktTargets(profileId, meta, ids)
            TrackingProviderId.SIMKL -> simklTargets(meta)
            TrackingProviderId.MDBLIST -> mdbListTargets(meta, ids)
        }.filter { it.contentIds.any(ids::contains) }
        if (!targets.flatMap { it.contentIds }.containsAll(ids)) return null
        if (ids.any { id -> targets.count { id in it.contentIds } != 1 }) return null
        return ResolvedTargets(context?.copy(episodeIds = ids.toList()) ?: source.tvdbPlaybackContext() ?: return null, targets)
    }

    private fun TrackingMediaReference.forTarget(target: TvdbTrackingTarget, seriesId: Long): TrackingMediaReference {
        val parent = target.parentIds
        val nativeId = parent?.imdb ?: parent?.tmdb?.let { "tmdb:$it" } ?: parent?.trakt?.let { "trakt:$it" }
            ?: parent?.simkl?.let { "simkl:$it" } ?: parent?.tvdb?.let { "tvdb:$it" } ?: catalog!!.contentId
        return copy(title = target.parentTitle ?: title, year = target.parentYear ?: year,
            episode = target.episode, ids = parent ?: ids.copy(tvdb = seriesId.toString()),
            catalog = catalog!!.copy(contentId = nativeId, videoId = "$nativeId:${target.episode.season}:${target.episode.number}",
                tvdbContext = null, tvdbEpisodeIds = null, providerDefault = true))
    }

    private suspend fun resolveSelected(profileId: Int, provider: TrackingProviderId, reference: TrackingMediaReference,
        completed: Boolean?, coverageChangesOnly: Boolean): List<TrackingMediaReference>? {
        return try {
            val resolved = selectedTargets(profileId, provider, reference) ?: return null
            // Supported scrobblers handle compound completion too. Automatic history
            // fallback must not write the same watch a second time.
            if (coverageChangesOnly && resolved.supportsProgress()) return emptyList()
            val ids = resolved.context.episodeIds.toSet()
            val prior = if (completed == true) local?.getAllRawEntries(profileId)?.values.orEmpty()
                .filter { it.contentId == reference.catalog!!.contentId }.flatMap { it.tvdbWatchedIds }.toSet() else emptySet()
            eligibleTvdbTargets(ids, resolved.targets, completed, prior).map { reference.forTarget(it, resolved.context.seriesId) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun scrobbleEvents(provider: TrackingProviderId, action: TrackingScrobbleAction, event: TrackingScrobbleEvent,
        progressOnly: Boolean = false): List<Pair<TrackingScrobbleAction, TrackingScrobbleEvent>>? {
        val reference = event.media
        if (!event.progressPercent.isFinite() || reference.catalog?.tvdbCoverageVerified == false) return emptyList()
        val profileId = profiles.activeProfileId.value
        if (reference.catalog?.providerDefault == true || reference.catalog == null || reference.episode == null ||
            reference.catalog.tvdbContext == null && !isSelected(profileId, reference.catalog.contentId)) return listOf(action to event)
        return withContext(Dispatchers.Default) {
            try {
                val resolved = selectedTargets(profileId, provider, reference) ?: return@withContext null
                if (!resolved.supportsProgress()) return@withContext emptyList()
                val context = resolved.context
                val ids = context.episodeIds.toSet()
                if (resolved.targets.size == 1 && resolved.targets.single().contentIds == ids) {
                    return@withContext listOf(action to event.copy(media = reference.forTarget(resolved.targets.single(), context.seriesId)))
                }
                if (!progressOnly && action != TrackingScrobbleAction.START && event.progressPercent >= WatchProgress.COMPLETED_THRESHOLD * 100) {
                    val prior = local?.getAllRawEntries(profileId)?.values.orEmpty()
                        .filter { it.contentId == reference.catalog.contentId }.flatMap { it.tvdbWatchedIds }.toSet()
                    val complete = eligibleTvdbTargets(ids, resolved.targets, true, prior)
                    if (complete.isNotEmpty()) return@withContext complete.map {
                        TrackingScrobbleAction.STOP to event.copy(media = reference.forTarget(it, context.seriesId), progressPercent = 100.0)
                    }
                }
                val activeId = context.activeProgressPart(event.progressPercent, resolved.weights) ?: return@withContext emptyList()
                val target = resolved.targets.singleOrNull { activeId in it.contentIds } ?: return@withContext emptyList()
                val fraction = projectTvdbFraction(context.episodeIds, target.contentIds.toList(), resolved.weights,
                    (event.progressPercent / 100).coerceIn(0.0, 1.0)) ?: return@withContext emptyList()
                val mapped = event.copy(media = reference.forTarget(target, context.seriesId),
                    progressPercent = (fraction * 100).coerceIn(0.0, 79.0), allowCompletion = false)
                listOf((if (action == TrackingScrobbleAction.START) action else TrackingScrobbleAction.PAUSE) to mapped)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }

}
