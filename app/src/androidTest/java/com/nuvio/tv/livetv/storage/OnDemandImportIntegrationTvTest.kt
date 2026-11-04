package com.nuvio.tv.livetv.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.IptvStorageTestEntryPoint
import com.nuvio.tv.domain.model.EpisodeCoordinates
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.PlaylistSource
import com.nuvio.tv.livetv.ondemand.*
import com.nuvio.tv.livetv.sync.driveBackupData
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDemandImportIntegrationTvTest {
    @Test fun friendsEnglishSurvivesImportMigrationModesFailuresAndGooglePayloadRestore() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val context = StorageTestContext(app)
        val profileManager = EntryPointAccessors.fromApplication(app.applicationContext, IptvStorageTestEntryPoint::class.java).profileManager()
        val preferences = LiveTvPreferences(context, profileManager)
        FixtureXtreamServer().use { provider ->
            OnDemandDatabase(context).use { database ->
                val repository = OnDemandRepository(database, preferences)
                val source = PlaylistSource("fixture", "Synthetic provider", "", importVod = true, importLive = false,
                    xtreamServer = provider.base, xtreamUsername = "fixture", xtreamPassword = "fixture")
                preferences.updatePlaylists { listOf(source) }
                repository.refresh(force = true)
                val target = VodStreamTarget(VodKind.SERIES, "1668", "Friends", 1994,
                    providerCoordinates = EpisodeCoordinates(1, 1))
                val full = repository.integratedSources(target, source.id)
                assertEquals(2, full.size)
                assertTrue("English Friends source must be present", full.any { it.title == "Friends (en)" })
                assertFalse("Conflicting reunion ID must not match", full.any { it.url.contains("20301") })

                preferences.updatePlaylists { listOf(source.copy(vodSourceOnly = true)) }
                repository.refresh(force = true)
                val compact = repository.integratedSources(target, source.id)
                assertEquals(full.map { it.url }.toSet(), compact.map { it.url }.toSet())
                assertTrue(database.byTitle(VodKind.SERIES, "Friends").all { it.icon == null })
                // A source-only import must not make artwork requests or category browsing requests.
                assertEquals(0, provider.requests[""]?.get() ?: 0)
                val page = repository.items(VodKind.SERIES, database.categories(VodKind.SERIES).first { it.id == "en" }, emptySet(), false, 30, 0)
                withTimeout(20_000) { while (database.item(source.id, VodKind.SERIES, "201")?.icon == null) delay(100) }
                assertTrue(page.any { it.id == "201" })
                assertEquals(full.map { it.url }.toSet(), repository.integratedSources(target, source.id).map { it.url }.toSet())
                provider.mediaFile = SyntheticVideo.create(context.cacheDir)
                playMatchedSource(compact.first { it.title.contains("(en)") }.url)

                provider.failSeries = true
                repository.refresh(force = true)
                assertEquals("Interrupted refresh must retain the English matching index", 3, database.count(VodKind.SERIES))
                provider.failSeries = false

                val selection = source.copy(vodSourceOnly = true, vodMovies = false, vodSeriesCategories = setOf("en"))
                preferences.updatePlaylists { listOf(selection) }
                repository.refresh(force = true)
                assertEquals(0, database.count(VodKind.MOVIE))
                assertEquals(2, database.count(VodKind.SERIES))
                assertTrue(repository.integratedSources(target, source.id).single().title.contains("(en)"))
                val group = "fixture::English"
                preferences.toggleFavoriteGroup(group)
                preferences.setGroupOrder(listOf(group, "unavailable-group"))
                preferences.setChannelOrder(group, listOf("channel-b", "channel-a"))
                val exported = preferences.exportForSync()
                val restored = driveBackupData(JSONObject().put("format", 1).put("data", exported))
                preferences.toggleFavoriteGroup(group)
                preferences.setGroupOrder(listOf("changed"))
                preferences.setChannelOrder(group, listOf("changed"))
                preferences.updatePlaylists { emptyList() }
                repository.refresh(force = true)
                assertEquals(0, database.count(VodKind.SERIES))
                preferences.importFromSync(restored)
                val recovered = preferences.playlists.first().single()
                assertEquals(selection.vodImportPolicy(), recovered.vodImportPolicy())
                val restoredOrder = preferences.userState.first()
                assertEquals(setOf(group), restoredOrder.favoriteGroups)
                assertEquals(listOf(group, "unavailable-group"), restoredOrder.groupOrder)
                assertEquals(listOf("channel-b", "channel-a"), restoredOrder.channelOrder[group])
                repository.refresh(force = true)
                assertEquals(2, database.count(VodKind.SERIES))
                assertTrue(repository.integratedSources(target, source.id).single().title.contains("(en)"))
                preferences.updatePlaylists { emptyList() }
            }
        }
    }

    private suspend fun playMatchedSource(url: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = java.util.concurrent.atomic.AtomicInteger()
        val frameError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val thread = android.os.HandlerThread("synthetic-video-frames").apply { start() }
        val reader = android.media.ImageReader.newInstance(160, 96, android.graphics.ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireLatestImage()?.use { frames.incrementAndGet() } }
            catch (error: Exception) { frameError.set(error) }
        }, android.os.Handler(thread.looper))
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val active = androidx.media3.exoplayer.ExoPlayer.Builder(instrumentation.targetContext).build()
                player = active
                active.setVideoSurface(reader.surface)
                active.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
                active.prepare(); active.playWhenReady = true
            }
            withTimeout(20_000) {
                var progressed = false
                while (!progressed) {
                    instrumentation.runOnMainSync {
                        val active = checkNotNull(player)
                        assertNull("Matched source playback must not fail", active.playerError)
                        assertNull("Decoded frames must be readable", frameError.get())
                        progressed = active.currentPosition >= 1000 && frames.get() > 0
                    }
                    if (!progressed) delay(100)
                }
            }
        } finally {
            instrumentation.runOnMainSync { player?.release() }
            reader.close(); thread.quitSafely()
        }
    }
}
