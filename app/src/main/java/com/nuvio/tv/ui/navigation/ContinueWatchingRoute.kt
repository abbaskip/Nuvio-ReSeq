package com.nuvio.tv.ui.navigation

import com.nuvio.tv.ui.screens.home.ContinueWatchingItem

internal fun createContinueWatchingRoute(
    item: ContinueWatchingItem,
    manualSelection: Boolean = false,
    startFromBeginning: Boolean = false
): String {
    return when (item) {
        is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
            videoId = item.progress.videoId,
            contentType = item.progress.contentType,
            title = item.progress.name,
            poster = item.progress.poster,
            backdrop = item.progress.backdrop,
            logo = item.progress.logo,
            season = item.progress.season,
            episode = item.progress.episode,
            episodeName = item.progress.episodeTitle,
            genres = null,
            year = null,
            contentId = item.progress.contentId,
            contentName = item.progress.name,
            runtime = null,
            manualSelection = manualSelection,
            returnToDetailOnBack = item.progress.contentType.equals("series", ignoreCase = true),
            returnToHomeOnBack = true,
            startFromBeginning = startFromBeginning,
            contentLanguage = item.contentLanguage,
            tvdbContext = item.progress.tvdbContext?.encode()
        )
        is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
            videoId = item.info.videoId,
            contentType = item.info.contentType,
            title = item.info.name,
            poster = item.info.poster,
            backdrop = item.info.backdrop,
            logo = item.info.logo,
            season = item.info.season,
            episode = item.info.episode,
            episodeName = item.info.episodeTitle,
            genres = null,
            year = null,
            contentId = item.info.contentId,
            contentName = item.info.name,
            runtime = null,
            manualSelection = manualSelection,
            returnToDetailOnBack = item.info.contentType.equals("series", ignoreCase = true),
            returnToHomeOnBack = true,
            startFromBeginning = startFromBeginning,
            contentLanguage = item.info.contentLanguage,
            tvdbContext = item.info.tvdbContext?.encode()
        )
    }
}
