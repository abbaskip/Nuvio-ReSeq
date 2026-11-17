package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HomeContentReadyTest {
    @Test fun cachedCardsRenderWhileCataloguesAreStillLoading() {
        assertTrue(homeContentReady(true, true, true, false, true, false, 4))
    }
    @Test fun emptyCacheStillWaitsForInitialCatalogue() {
        assertFalse(homeContentReady(true, false, true, true, true, false, 4))
        assertTrue(homeContentReady(true, false, true, true, false, true, 4))
    }
    @Test fun unresolvedScopeAndLayoutDoNotReleaseCachedCards() {
        assertFalse(homeContentReady(false, true, true, true, false, true, 4))
        assertFalse(homeContentReady(true, true, false, true, false, true, 4))
    }
}
