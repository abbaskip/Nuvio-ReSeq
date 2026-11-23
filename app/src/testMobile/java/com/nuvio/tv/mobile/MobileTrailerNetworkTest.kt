package com.nuvio.tv.mobile

import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.trailer.TrailerNetworkAccess
import com.nuvio.tv.data.trailer.TrailerService
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Test

class MobileTrailerNetworkTest {
    private val network = object : TrailerNetworkAccess {
        override val playbackAllowed = MutableStateFlow(false)
        override fun allowsPlaybackNow() = playbackAllowed.value
    }
    private val settings = mockk<TmdbSettingsDataStore>()
    private val service = TrailerService(mockk(), mockk(), mockk(), settings, mockk(), trailerNetwork = network)

    @Test fun cellularRestrictionAlsoBlocksGalleryAndPostPlayLookups() = runBlocking {
        assertNull(service.getTrailerPlaybackSource("A movie", tmdbId = "1", ignoreUseTrailersGate = true))
        assertNull(service.getTrailerPlaybackSourceFromYouTubeUrl("https://www.youtube.com/watch?v=abcdefghijk"))
        assertNull(service.getTrailerPlaybackSourceFromTmdbId("1", "movie"))
        assertNull(service.getExternalTrailerUrl("1", "movie"))
        verify { settings wasNot Called }
    }

    @Test fun losingWifiCancelsAnActiveLookupAndReconnectingCanRetry() = runBlocking {
        withTimeout(10_000) {
            repeat(2) {
                val started = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                every { settings.settings } returns flow {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
                network.playbackAllowed.value = true
                val request = async { service.getExternalTrailerUrl("1", "movie") }
                started.await()
                network.playbackAllowed.value = false
                assertNull(request.await())
                stopped.await()
            }
        }
    }
}
