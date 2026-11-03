package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape

/** Reuse catalog cards, stable item keys and all three home layouts' focus restoration. */
internal fun List<HomeRow>.withTvMixes(mixes: List<TvMix>): List<HomeRow> {
    val rows = filterNot { it is HomeRow.Catalog && it.row.apiType == "tvmix" }
    if (mixes.isEmpty()) return rows
    return rows + HomeRow.Catalog(CatalogRow(
        addonId = "reseq-tv-mix", addonName = "", addonBaseUrl = "", catalogId = "tv-mix",
        catalogName = "TV Mix", type = ContentType.UNKNOWN, rawType = "tvmix",
        items = mixes.map { it.preview() }, hasMore = false
    ))
}

internal fun ContinueWatchingItem.tvMixPreview(): MetaPreview? {
    fun preview(id: String, type: String, name: String, poster: String?, background: String?, logo: String?): MetaPreview? {
        if (type.lowercase() !in listOf("series", "tv", "anime")) return null
        return MetaPreview(id, ContentType.fromString(type), type, name, poster, PosterShape.POSTER,
            background, logo, null, null, null, emptyList(), imdbId = id.takeIf { it.startsWith("tt") })
    }
    return when (this) {
        is ContinueWatchingItem.InProgress -> preview(progress.contentId, progress.contentType, progress.name,
            originalPoster ?: progress.poster, progress.backdrop, progress.logo)
        is ContinueWatchingItem.NextUp -> preview(info.contentId, info.contentType, info.name,
            originalPoster ?: info.poster, info.backdrop, info.logo)
    }
}
