package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TvMix
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TvMixHomeCatalog
import com.nuvio.tv.domain.model.legacyKey

/** Reuse catalog cards, stable item keys and all three home layouts' focus restoration. */
internal fun List<HomeRow>.withTvMixes(
    mixes: List<TvMix>,
    orderKeys: List<String> = emptyList(),
    disabledKeys: Set<String> = emptySet()
): List<HomeRow> {
    val rows = filterNot { it is HomeRow.Catalog && it.row.apiType == "tvmix" }
    if (mixes.isEmpty() || TvMixHomeCatalog.key in disabledKeys) return rows
    val mixRow = HomeRow.Catalog(CatalogRow(
        addonId = TvMixHomeCatalog.ADDON_ID, addonName = "", addonBaseUrl = "", catalogId = TvMixHomeCatalog.CATALOG_ID,
        catalogName = TvMixHomeCatalog.TITLE, type = ContentType.UNKNOWN, rawType = TvMixHomeCatalog.TYPE,
        items = mixes.map { it.preview() }, hasMore = false
    ))
    // Anchor to the next visible row, including unloaded catalog placeholders.
    // Pinned collections retain their existing priority above ordered catalogs.
    val followingKeys = orderKeys.dropWhile { it != TvMixHomeCatalog.key }.drop(1).toSet()
    val insertAt = rows.indexOfFirst { row ->
        when (row) {
            is HomeRow.Catalog -> row.row.legacyKey() in followingKeys
            is HomeRow.PlaceholderCatalog -> row.catalogKey in followingKeys
            is HomeRow.CollectionRow -> !row.collection.pinToTop && "collection_${row.collection.id}" in followingKeys
        }
    }.takeIf { it >= 0 } ?: rows.size
    return rows.toMutableList().apply { add(insertAt, mixRow) }
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
