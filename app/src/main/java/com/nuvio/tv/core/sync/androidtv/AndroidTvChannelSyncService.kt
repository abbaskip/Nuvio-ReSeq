package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.poster.patternForScreen
import com.nuvio.tv.core.poster.withCustomPosterArt
import com.nuvio.tv.core.recommendations.TvRecommendationManager
import com.nuvio.tv.data.local.CachedInProgressItem
import com.nuvio.tv.data.local.CachedNextUpItem
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TvChannelSync"
private const val PUBLICATION_INTERVAL_MS = 500L

/**
 * Keeps the Android TV "Continue Watching" preview channel in sync with the app's
 * CW enrichment cache. Uses the same enriched data that the in-app Continue Watching
 * section displays, respecting user settings (days cap, show unaired, etc.).
 *
 * Items come from the durable app-scoped projection, including off-Home completion.
 * Membership and visibility decisions are made by the repository reducer.
 *
 * Before publishing new programs, all stale programs are removed first to avoid
 * the launcher showing outdated tiles while new ones are being inserted.
 */
@Singleton
class AndroidTvChannelSyncService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: AndroidTvChannelManager,
    private val cwEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val tvRecommendationManager: TvRecommendationManager
) {
    private val reconcileMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Publish membership/offer changes promptly during playback. Suppress position-only
    // provider writes until backgrounding so Projectivy can repaint without IPC churn.
    private var lastPublishedStructure: Pair<String, List<WatchProgress>>? = null
    @Volatile private var appInForeground = false

    /** Called from the host Activity's onStart/onStop. On background we reconcile once so the
     *  channel reflects the latest watch progress exactly as the launcher regains foreground. */
    fun onForegroundChanged(foreground: Boolean) {
        val wasForeground = appInForeground
        appInForeground = foreground
        if (wasForeground && !foreground) {
            scope.launch { reconcileFromCache() }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        if (!manager.isSupported()) {
            Log.d(TAG, "Non-leanback device; channel sync skipped")
            return
        }
        TvChannelRefreshJobService.schedulePeriodic(context)

        scope.launch {
            // A durable projection is safe to publish on startup and while Home is absent.
            combine(
                cwEnrichmentCache.projection.filterNotNull(),
                traktSettingsDataStore.continueWatchingDaysCap,
                traktSettingsDataStore.dismissedNextUpKeys,
                layoutPreferenceDataStore.useEpisodeThumbnailsInCw
            ) { _, daysCap, dismissed, useEpisodeThumbnails ->
                ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
            }
                .sample(PUBLICATION_INTERVAL_MS)
                .collect { settings ->
                    reconcileFromCache(settings)
                }
        }
    }

    /**
     * Reads the CW enrichment cache and reconciles the launcher channel.
     * Called both from the live observer and from [TvChannelRefreshJobService].
     */
    suspend fun reconcileFromCache(settings: ChannelSettingsSnapshot? = null) = reconcileMutex.withLock {
        val resolvedSettings = settings ?: run {
            val daysCap = traktSettingsDataStore.continueWatchingDaysCap.first()
            val dismissed = traktSettingsDataStore.dismissedNextUpKeys.first()
            val useEpisodeThumbnails = layoutPreferenceDataStore.useEpisodeThumbnailsInCw.first()
            ChannelSettingsSnapshot(daysCap, dismissed, useEpisodeThumbnails)
        }

        val projection = cwEnrichmentCache.getSnapshot()
        val eligible = com.nuvio.tv.data.cw.CwMembershipReducer.visible(projection,
            resolvedSettings.daysCap, resolvedSettings.dismissedNextUp, showUnaired = false,
            now = System.currentTimeMillis(), launcher = true)
        val inProgressItems = eligible.inProgress
        val nextUpItems = eligible.nextUp

        val posterPattern = patternForScreen(
            layoutPreferenceDataStore.customPosterUrlPattern.first(),
            CustomPosterScreen.TV_RECOMMENDATIONS,
            layoutPreferenceDataStore.customPosterEnabledScreens.first()
        )
        val channelItems = buildChannelItems(inProgressItems, nextUpItems, resolvedSettings)
            .withCustomPosterArt(posterPattern)

        Log.d(
            TAG,
            "Reconciling from cache: ${channelItems.size} items " +
                "(${inProgressItems.size} in-progress, ${nextUpItems.size} next-up raw)"
        )
        val structure = "${projection.profileId}:${projection.source}" to channelItems.map {
            it.copy(position = 0, duration = 0, lastWatched = 0, progressPercent = null)
        }
        if (appInForeground && structure == lastPublishedStructure) return@withLock
        if (cwEnrichmentCache.getSnapshot().lease != projection.lease) return@withLock
        manager.reconcile(channelItems)

        runCatching {
            tvRecommendationManager.updateWatchNext(channelItems)
        }
        lastPublishedStructure = structure
    }

    /**
     * Merges in-progress and next-up cached items into a single list of [WatchProgress]
     * suitable for the launcher channel, applying user settings:
     * - Days cap filtering
     * - Dismissed next-up filtering
     * - Unreleased/unaired filtering (next-up items that haven't aired yet are excluded
     *   to avoid showing content that can't be played)
     * - Deduplication (in-progress takes priority over next-up for same contentId)
     */
    private fun buildChannelItems(
        inProgress: List<CachedInProgressItem>,
        nextUp: List<CachedNextUpItem>,
        settings: ChannelSettingsSnapshot
    ): List<WatchProgress> {
        val filteredInProgress = inProgress
        val filteredNextUp = nextUp

        // Deduplicate: in-progress wins over next-up for same contentId
        val inProgressContentIds = filteredInProgress.mapTo(mutableSetOf()) { it.contentId }
        val deduplicatedNextUp = filteredNextUp.filter { it.contentId !in inProgressContentIds }

        // Sort: in-progress by lastWatched, next-up by sortTimestamp (which is
        // releaseTimestamp for release alerts, lastWatched for regular next-up).
        // This mirrors the in-app CW order for aired content in both sort modes.
        data class SortableItem(val watchProgress: WatchProgress, val sortKey: Long)

        val inProgressSorted = filteredInProgress.map { item ->
            SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.lastWatched)
        }

        val nextUpSorted = deduplicatedNextUp.map { item ->
            SortableItem(item.toWatchProgress(settings.useEpisodeThumbnails), item.sortTimestamp)
        }

        return (inProgressSorted + nextUpSorted)
            .sortedByDescending { it.sortKey }
            .map { it.watchProgress }
            .distinctBy { it.contentId }
    }

    private fun nextUpDismissKey(item: CachedNextUpItem): String {
        return buildString {
            append(item.contentId)
            if (item.seedSeason != null) {
                append("_s${item.seedSeason}")
                if (item.seedEpisode != null) append("e${item.seedEpisode}")
            }
        }
    }

    data class ChannelSettingsSnapshot(
        val daysCap: Int,
        val dismissedNextUp: Set<String>,
        val useEpisodeThumbnails: Boolean
    )
}

/**
 * Converts a [CachedInProgressItem] to [WatchProgress] for the launcher channel.
 */
private fun CachedInProgressItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        episodeThumbnail ?: backdrop
    } else {
        backdrop ?: episodeThumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = if (chooseEpisode) contentId else videoId,
        season = season.takeUnless { chooseEpisode },
        episode = episode.takeUnless { chooseEpisode },
        episodeTitle = episodeTitle,
        position = position,
        duration = duration,
        lastWatched = lastWatched,
        progressPercent = progressPercent
    )
}

/**
 * Converts a [CachedNextUpItem] to [WatchProgress] for the launcher channel.
 * Next-up items don't have playback position, so they appear as "0% watched"
 * which launchers typically render without a progress bar.
 */
private fun CachedNextUpItem.toWatchProgress(useEpisodeThumbnails: Boolean): WatchProgress {
    val image = if (useEpisodeThumbnails) {
        thumbnail ?: backdrop
    } else {
        backdrop ?: thumbnail
    }
    return WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = name,
        poster = poster,
        backdrop = image,
        logo = logo,
        videoId = videoId,
        season = season.takeUnless { chooseEpisode },
        episode = episode.takeUnless { chooseEpisode },
        episodeTitle = episodeTitle,
        position = 0,
        duration = 0,
        lastWatched = sortTimestamp
    )
}
