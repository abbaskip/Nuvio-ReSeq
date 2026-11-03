package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.TvMix

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
