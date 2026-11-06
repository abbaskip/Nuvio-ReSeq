package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FolderTabLoadErrorTest {
    private val row = CatalogRow(
        addonId = "tmdb", addonName = "TMDB", addonBaseUrl = "", catalogId = "collection",
        catalogName = "Films", type = ContentType.MOVIE, items = listOf(mockk<MetaPreview>()),
        isLoading = true
    )

    @Test
    fun `failed first page discards the placeholder row`() {
        val tab = FolderTab("Films", catalogRow = row).withLoadError("Unavailable", append = false)
        assertNull(tab.catalogRow)
        assertFalse(tab.isLoading)
        assertEquals("Unavailable", tab.error)
    }

    @Test
    fun `failed later page keeps previously loaded titles`() {
        val tab = FolderTab("Films", catalogRow = row).withLoadError("Unavailable", append = true)
        assertEquals(row.copy(isLoading = false), tab.catalogRow)
    }
}
