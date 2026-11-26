package com.nuvio.tv.livetv.ondemand

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.resolvePluginSeasonEpisode
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.extractTrackingYear
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamContentContext
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.livetv.data.LiveTvPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Catalogue matching only. Playback and tracker identities remain owned by the existing player. */
@Singleton
class IptvVodStreams @Inject constructor(
    private val repository: OnDemandRepository,
    private val metadata: MetaRepository,
    private val tmdb: TmdbService,
    private val preferences: LiveTvPreferences,
    private val profiles: ProfileManager,
    private val titleAliases: VodTitleAliases? = null,
    private val sourceOrdering: IptvStreamSources? = null
) {
    suspend fun revision(): String = MessageDigest.getInstance("SHA-256").digest(
        (preferences.playlists.first().filter { it.enabled && it.importVod && it.supportsVod }
            .map { listOf(it.id, it.xtreamServer, it.xtreamUsername, it.xtreamPassword, it.stalkerPortal, it.stalkerMac, it.stalkerUsername, it.stalkerPassword, it.stalkerSerial, it.userAgent).let { existing ->
                when {
                    it.isM3u -> existing + listOf(it.url, it.vodImportPolicy())
                    it.stalkerDeviceId.isNotBlank() || it.stalkerDeviceId2.isNotBlank() ->
                        existing + listOf(it.stalkerDeviceId, it.stalkerDeviceId2)
                    else -> existing
                }
            } }.toString() + repository.version.value + OnDemandRepository.systemLanguage().toLanguageTag())
            .toByteArray()).joinToString("") { "%02x".format(it) }

    internal suspend fun prepare(type: String, videoId: String, season: Int?, episode: Int?,
        selection: StreamContentContext?): VodStreamTarget? {
        val kind = when (type) { "movie" -> VodKind.MOVIE; "series", "tv" -> VodKind.SERIES; else -> return null }
        val id = selection?.contentId?.takeIf { it.isNotBlank() } ?: when {
            videoId.startsWith("tt") -> videoId.substringBefore(':')
            videoId.startsWith("tmdb:") -> "tmdb:" + videoId.substringAfter(':').substringBefore(':')
            kind == VodKind.MOVIE -> videoId
            else -> return null
        }
        val ids = parseTrackingExternalIds(id)
        val needsMetadata = ids.imdb == null && ids.tmdb == null || selection == null || selection.title.isBlank() || selection.year == null ||
            kind == VodKind.SERIES && selection.episodeTitle.isNullOrBlank()
        val meta = if (needsMetadata) withTimeoutOrNull(15_000) {
            (metadata.getProviderMeta(type, id).first { it !is NetworkResult.Loading } as? NetworkResult.Success)?.data
        } else null
        val imdb = meta?.imdbId ?: ids.imdb
        var tmdbId = ids.tmdb?.toString() ?: if (tmdb.apiKey().isBlank()) null else withTimeoutOrNull(8_000) {
            try { tmdb.ensureTmdbId(imdb ?: id, type) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
        val aliases = titleAliases?.resolve(kind, tmdbId, imdb, selection?.tvdb?.seriesId ?: id.takeIf { it.startsWith("tvdb:") }?.removePrefix("tvdb:")?.toLongOrNull())
        tmdbId = aliases?.tmdbId ?: tmdbId
        val native = if (selection?.tvdb == null) meta?.videos?.singleOrNull {
            it.id == videoId || it.season == season && it.episode == episode
        } else null
        val context = selection?.tvdb
        val nativeSources = context?.providerSources.orEmpty().filter { source ->
            context != null && context.episodeIds.isNotEmpty() && source.episodeIds.toSet() == context.episodeIds.toSet()
        }
        val (nativeSeason, nativeEpisode) = resolvePluginSeasonEpisode(videoId, season, episode)
        val providerCoordinates = if (kind != VodKind.SERIES) null else if (context == null) {
            if (nativeSeason != null && nativeSeason >= 0 && nativeEpisode != null && nativeEpisode > 0)
                EpisodeCoordinates(nativeSeason, nativeEpisode) else null
        } else {
            // Never interpret selected TVDB coordinates as Provider Default coordinates.
            nativeSources.map { it.coordinates }.distinct().singleOrNull()
        }
        val titles = linkedSetOf<String>()
        (selection?.episodeTitle ?: native?.title)?.takeIf { it.isNotBlank() }?.let(titles::add)
        nativeSources.forEach { titles += it.title }
        android.util.Log.d("IptvVod", "Catalogue target: kind=$kind tmdb=${tmdbId != null} imdb=${imdb != null} year=${selection?.year != null || meta != null} titles=${titles.size}")
        return VodStreamTarget(kind, tmdbId, selection?.title?.takeIf { it.isNotBlank() } ?: meta?.name.orEmpty(),
            selection?.year ?: extractTrackingYear(meta?.releaseInfo), titles,
            context?.episodeIds.orEmpty().toSet(), context?.episodeId,
            (native?.id ?: videoId).takeIf { it.matches(Regex("tt[0-9]+")) && it != id }, imdb, providerCoordinates, aliases?.titles.orEmpty())
    }

    internal suspend fun load(playlistKey: String, sourceName: String, target: VodStreamTarget?, profileId: Int,
        templates: com.nuvio.tv.core.streams.SourceStreamTemplates? = null): List<Stream> {
        if (target == null || profiles.activeProfileId.value != profileId) return emptyList()
        repository.start()
        val playlistId = playlistKey.removePrefix(IptvStreamSources.PREFIX)
        val ordered = repository.integratedSources(target, playlistId).toMutableList()
        val alternatives = sourceOrdering?.alternatives(playlistId).orEmpty()
        if (alternatives.isNotEmpty()) {
            val primary = preferences.playlists.first().firstOrNull { it.id == playlistId }
            val series = target.kind == VodKind.SERIES
            val primaryEnabled = primary?.let { if (series) it.vodSeries else it.vodMovies } == true
            val covered = primary?.vodCategories(series)
            val seen = ordered.mapTo(mutableSetOf()) { it.catalogEntry }
            val primaryHadResults = ordered.isNotEmpty()
            for (other in alternatives) {
                val selected = other.vodCategories(series)
                if (primaryHadResults && primaryEnabled && (covered == null || selected != null && covered.containsAll(selected))) continue
                for (result in repository.integratedSources(target, other.id)) {
                    if (seen.add(result.catalogEntry)) ordered += result
                }
            }
        }
        if (profiles.activeProfileId.value != profileId) return emptyList()
        return ordered.map { OnDemandStreams.stream(it, sourceName, templates) }
    }
}
