package com.nuvio.tv.ui.screens.tvmix

import com.nuvio.tv.domain.model.TvMixSelection
import com.nuvio.tv.domain.model.resolveContentLanguage
import com.nuvio.tv.domain.model.tvdbPlaybackContext
import com.nuvio.tv.ui.navigation.Screen

internal const val TV_MIX_ROUTE = "tv-mix/{mixId}?manage={manage}"

/** Forward the original video identity to the normal source and tracker pipelines. */
internal fun TvMixSelection.streamRoute(profileId: Int?) = Screen.Stream.createRoute(
    videoId = video.id, contentType = meta.apiType, title = meta.name,
    poster = meta.poster, backdrop = meta.backdropUrl, logo = meta.logo,
    season = video.season, episode = video.episode, episodeName = video.title,
    contentId = meta.id, contentName = meta.name, runtime = video.runtime,
    startFromBeginning = true, contentLanguage = meta.resolveContentLanguage(),
    profileId = profileId, tvdbContext = video.tvdbPlaybackContext()?.encode()
)
