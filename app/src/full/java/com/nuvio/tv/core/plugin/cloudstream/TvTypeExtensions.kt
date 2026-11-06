package com.nuvio.tv.core.plugin.cloudstream

import com.lagradost.cloudstream3.TvType

internal fun cloudStreamContentTypes(tvTypes: List<String>?): List<String> =
    tvTypes.orEmpty().mapNotNull { type ->
        TvType.entries.firstOrNull { it.name.equals(type.trim(), ignoreCase = true) }
    }.flatMap { type ->
        when (type) {
            TvType.Others -> listOf("movie", "tv")
            TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> listOf("movie")
            else -> listOf("tv")
        }
    }.distinct().ifEmpty { listOf("movie", "tv") }
