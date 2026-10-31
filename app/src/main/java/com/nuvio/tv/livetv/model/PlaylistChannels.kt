package com.nuvio.tv.livetv.model

internal fun List<LiveChannel>.forPlaylist(sourceId: String?): List<LiveChannel> =
    if (sourceId == null) this else filter { it.sourceId == sourceId }
