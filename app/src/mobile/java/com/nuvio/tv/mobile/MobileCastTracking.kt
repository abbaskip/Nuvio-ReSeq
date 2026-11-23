package com.nuvio.tv.mobile

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackSessionStore
import com.nuvio.tv.core.cloud.CloudLibraryPlaybackProgressStore
import com.nuvio.tv.data.tvdb.activeProgressPart
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import kotlin.math.abs

/** One ordered stream of receiver observations, including when the player UI is in the background. */
internal class MobileCastTracking @Inject constructor(
    private val progress: WatchProgressRepository,
    private val scrobbles: TrackingScrobbleCoordinator,
    private val profiles: ProfileManager,
    private val cloudSessions: CloudLibraryPlaybackSessionStore,
    private val cloudProgress: CloudLibraryPlaybackProgressStore
) {
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var last: CastSample? = null
    private var lastSaved = 0L
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (write in writes) {
                try { write() } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.w("ReSeqCast", "Could not save Cast tracking update", e) }
            }
        }
    }

    suspend fun resume(video: MobileVideo, cloudToken: String?): Long {
        if (video.type == "cloud") {
            val session = cloudSessions.load(cloudToken) ?: return 0L
            return session.fileForVideoId(video.videoId)?.let { cloudProgress.load(session.item, it)?.resumePositionMs } ?: 0L
        }
        val context = TvdbPlaybackContext.decode(video.tvdb)
        val row = when {
            context != null -> progress.getEpisodeProgress(video.contentId, context, video.profile)
            video.season != null && video.episode != null ->
                progress.getProviderEpisodeProgress(video.contentId, video.season, video.episode, video.profile)
            else -> progress.getProgress(video.contentId, video.profile)
        }.first()
        return row?.takeIf { !it.isCompleted() }?.position?.coerceAtLeast(0) ?: 0
    }

    fun observe(sample: CastSample) {
        if (sample.duration <= 0 || com.nuvio.tv.ui.screens.player.isShortPlaceholderDuration(sample.duration)) return
        val previous = last
        val next = sample.copy(position = sample.position.coerceIn(0, sample.duration))
        val seek = previous != null && abs((next.position - previous.position) -
            (if (previous.playing) (next.clock - previous.clock) else 0L)) > 4_000L
        val action = when {
            previous == null && !next.playing -> null
            previous == null || !previous.playing && next.playing -> TrackingScrobbleAction.START
            previous.playing && !next.playing -> TrackingScrobbleAction.PAUSE
            else -> null
        }
        last = next
        val save = action != null || seek || next.clock - lastSaved >= 15_000L
        if (save) lastSaved = next.clock
        val context = TvdbPlaybackContext.decode(next.video.tvdb)
        val boundary = next.playing && previous?.playing == true && !seek && context != null && context.episodeIds.size > 1 &&
            context.activeProgressPart(previous.percent) != context.activeProgressPart(next.percent)
        if (!save && !boundary) return
        writes.trySend {
            if (next.video.profile != profiles.activeProfileId.value) return@trySend
            if (next.video.type == "cloud") { if (save) saveCloud(next, false); return@trySend }
            val event = next.event()
            if (save && next.position > 0) progress.saveProgress(next.row(), next.video.profile, syncRemote = action != null || seek)
            if (event != null) {
                if (seek && previous != null) {
                    previous.event()?.let { scrobbles.scrobbleSeek(TrackingScrobbleAction.PAUSE, it.copy(allowCompletion = false)) }
                    if (next.playing) scrobbles.scrobbleSeek(TrackingScrobbleAction.START, event.copy(allowCompletion = false))
                } else if (boundary && previous != null) previous.event()?.let { scrobbles.scrobbleBoundary(it, event) }
                if (action != null) scrobbles.scrobble(action, event)
            }
        }
    }

    fun finish(completed: Boolean) {
        val previous = last ?: return
        last = null
        lastSaved = 0
        val final = if (completed) previous.copy(position = previous.duration) else previous
        writes.trySend {
            if (final.video.profile != profiles.activeProfileId.value) return@trySend
            if (final.video.type == "cloud") { saveCloud(final, completed); return@trySend }
            val row = final.row()
            if (completed && row.isCompleted()) progress.markAsCompleted(row, final.video.profile, broadcastTrackingHistory = false)
            else if (final.position > 0) progress.saveProgress(row, final.video.profile)
            final.event()?.let { scrobbles.scrobble(TrackingScrobbleAction.STOP, it) }
        }
    }
    private fun saveCloud(sample: CastSample, completed: Boolean) {
        val session = cloudSessions.load(sample.cloudToken) ?: return
        val file = session.fileForVideoId(sample.video.videoId) ?: return
        cloudProgress.save(session.item, file, sample.position, sample.duration, completed)
    }
}

internal data class CastSample(val video: MobileVideo, val position: Long, val duration: Long, val playing: Boolean, val clock: Long,
    val cloudToken: String? = null) {
    val percent get() = if (duration > 0) position * 100.0 / duration else 0.0
    fun row(): WatchProgress {
        val context = TvdbPlaybackContext.decode(video.tvdb)
        return WatchProgress(video.contentId, video.type, video.title, video.poster, null, null, video.videoId,
            video.season, video.episode, video.episodeName, position, duration, System.currentTimeMillis(),
            tvdbContext = context, tvdbEpisodeId = context?.episodeIds?.firstOrNull(), tvdbEpisodeIds = context?.episodeIds,
            providerDefaultPlayback = video.tvdb == "provider", tvdbCoverageVerified = context?.permitsTracking(duration))
    }
    fun event(): TrackingScrobbleEvent? {
        val row = row()
        val media = buildTrackingMediaReference(video.type, video.contentId, video.videoId, video.title,
            seasonNumber = video.season, episodeNumber = video.episode, episodeTitle = video.episodeName)
        if (!media.hasResolvableIdentity || row.tvdbCoverageVerified == false ||
            media.kind !in setOf(TrackingMediaKind.MOVIE, TrackingMediaKind.ANIME) && media.episode == null) return null
        return TrackingScrobbleEvent(media.copy(catalog = media.catalog?.copy(tvdbContext = row.tvdbContext,
            tvdbEpisodeIds = row.tvdbEpisodeIds.orEmpty(), providerDefault = row.providerDefaultPlayback)), percent)
    }
}
