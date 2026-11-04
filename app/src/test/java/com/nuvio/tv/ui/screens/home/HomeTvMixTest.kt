package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class HomeTvMixTest {
    private val upstream = HomeRow.Catalog(CatalogRow("addon", "Addon", "https://example.test", "shows",
        "Shows", ContentType.SERIES, items = emptyList()))
    private val mix = TvMix("saved-id", "Renovation", listOf(TvMixShow("tt123", "Example")))

    @Test fun `mix row appears at bottom only when saved mixes exist`() {
        assertEquals(listOf(upstream), listOf(upstream).withTvMixes(emptyList()))
        val rows = listOf(upstream).withTvMixes(listOf(mix))
        assertSame(upstream, rows.first())
        val row = (rows.last() as HomeRow.Catalog).row
        assertEquals("TV Mix", catalogRowTitle(row, true))
        assertEquals("tvmix:saved-id", row.items.single().id)
        assertEquals("tvmix", row.items.single().apiType)
        assertFalse(row.hasMore)
        assertEquals(listOf(upstream), rows.withTvMixes(emptyList()))
    }

    @Test fun `focus keys survive rename artwork changes and insertion of another mix`() {
        val row = (emptyList<HomeRow>().withTvMixes(listOf(mix)).single() as HomeRow.Catalog).row
        val renamed = mix.copy(name = "Renamed", shows = mix.shows + TvMixShow("tt456", "Other"))
        val updated = (emptyList<HomeRow>().withTvMixes(listOf(TvMix("new", "New"), renamed)).single() as HomeRow.Catalog).row
        assertEquals(row.stableKey(), updated.stableKey())
        assertEquals(row.stableItemKeys().single(), updated.stableItemKeys().last())
        assertEquals("Renamed", updated.items.last().name)
    }

    @Test fun `saved mix position is respected above between and below visible rows`() {
        val second = HomeRow.Catalog(upstream.row.copy(catalogId = "second"))
        val firstKey = upstream.row.legacyKey()
        val secondKey = second.row.legacyKey()
        val rows = listOf(upstream, second)
        val top = rows.withTvMixes(listOf(mix), listOf(TvMixHomeCatalog.key, firstKey, secondKey))
        val middle = rows.withTvMixes(listOf(mix), listOf(firstKey, TvMixHomeCatalog.key, secondKey))
        val bottom = rows.withTvMixes(listOf(mix), listOf(firstKey, secondKey, TvMixHomeCatalog.key))
        assertEquals("tvmix", (top.first() as HomeRow.Catalog).row.apiType)
        assertEquals("tvmix", (middle[1] as HomeRow.Catalog).row.apiType)
        assertEquals("tvmix", (bottom.last() as HomeRow.Catalog).row.apiType)
        assertEquals(listOf(upstream, second), middle.withTvMixes(emptyList()))
    }

    @Test fun `hidden mix stays absent and pinned collections retain their priority`() {
        val pinned = HomeRow.CollectionRow(Collection("pinned", "Pinned", pinToTop = true))
        val rows = listOf(pinned, upstream)
        val order = listOf(TvMixHomeCatalog.key, "collection_pinned", upstream.row.legacyKey())
        assertEquals(rows, rows.withTvMixes(listOf(mix), order, setOf(TvMixHomeCatalog.key)))
        val visible = rows.withTvMixes(listOf(mix), order)
        assertSame(pinned, visible.first())
        assertEquals("tvmix", (visible[1] as HomeRow.Catalog).row.apiType)
    }

    @Test fun `mix position can anchor to an unloaded catalogue`() {
        val placeholder = HomeRow.PlaceholderCatalog("pending", "stable", "addon", "Addon", "https://example.test",
            "pending", "Pending", "series", "Pending")
        val rows = listOf(upstream, placeholder).withTvMixes(listOf(mix),
            listOf(upstream.row.legacyKey(), TvMixHomeCatalog.key, "missing", "pending"))
        assertEquals("tvmix", (rows[1] as HomeRow.Catalog).row.apiType)
        assertSame(placeholder, rows.last())
    }

    @Test fun `addon only edits retain the saved mix slot without adding one for a new profile`() {
        val previous = listOf("addon-a", TvMixHomeCatalog.key, "addon-b")
        assertEquals(listOf("addon-b", TvMixHomeCatalog.key, "addon-a", "new-addon"),
            TvMixHomeCatalog.retainPosition(listOf("addon-b", "addon-a", "new-addon"), previous))
        assertEquals(listOf("addon-b", TvMixHomeCatalog.key),
            TvMixHomeCatalog.retainPosition(listOf("addon-b"), previous))
        assertEquals(listOf("addon-b"), TvMixHomeCatalog.retainPosition(listOf("addon-b"), emptyList()))
    }
    @Test fun `Continue Watching offers the whole series with original artwork rather than episode identity`() {
        val progress = WatchProgress("tmdb:99", "series", "Example", "episode-art", null, null,
            "tt123:1:2", 1, 2, "Episode two", 100L, 1000L, 1L)
        val item = ContinueWatchingItem.InProgress(progress, originalPoster = "series-art")
        val preview = item.tvMixPreview()!!
        assertEquals("tmdb:99", preview.id)
        assertEquals("series-art", preview.poster)
        assertEquals("series", preview.apiType)
        assertNull(ContinueWatchingItem.InProgress(progress.copy(contentType = "movie")).tvMixPreview())
    }

}
