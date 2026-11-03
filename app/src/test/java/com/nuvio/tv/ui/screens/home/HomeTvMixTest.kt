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
