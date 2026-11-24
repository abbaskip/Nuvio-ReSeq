package com.nuvio.tv.mobile

import android.app.Application
import com.nuvio.tv.data.tvdb.*
import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class MobileOfflineMediaTest {
    private val media = MobileOfflineMedia(RuntimeEnvironment.getApplication())
    private val movie = MobileVideo(1, "movie", "tt1", "tt1", "../Same name")

    @Test fun offlineHistoryJobWaitsForConnectivityAndSurvivesRestart() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = MobileOfflineWatchScheduler(context)
        scheduler.schedule(1)
        scheduler.schedule(1)
        val jobs = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs
            .filter { it.service.className == MobileOfflineWatchJob::class.java.name }
        val job = jobs.single()
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(1, job.extras.getInt("profile"))
        assertTrue(job.isPersisted)
    }

    @Test fun switchingOrdersUpdatesSavedDownloadAndPreservesThePlaybackIdentityAfterRestart() {
        val orders = officeOrderFixtures()
        val alt = orders.getValue("alttwo")
        val source = recordedProvider(alt)
        val meta = tvdbEpisodeOverlay(source, 73244, alt)
        val row = meta.videos.single { it.title == "New Leads (Extended Cut)" }
        val selected = row.tvdbPlaybackContext()!!
        val original = MobileVideo(1, "series", meta.id, row.id, meta.name, row.sourceSeason, row.sourceEpisode,
            row.title, selected.encode())
        val dir = media.folder(original).apply { mkdirs() }
        media.publish(original, File(dir, "media.part").apply { writeText("saved episode") }, "mkv", meta = meta)
        val other = tvdbEpisodeOverlay(source, 73244, orders.getValue("dvd"))
        val target = other.videos.single {
            it.hasResolvedSource && it.episodeIdentity!!.trackingEpisodeIds.toSet() == selected.episodeIds.toSet()
        }
        media.updateDetails(media.saved(1).single(), meta, otherOrders = listOf(other))
        val indexed = MobileOfflineMedia(RuntimeEnvironment.getApplication()).saved(1).single()
        val cached = indexed.inOrder(TvdbOrderPreference(73244, "dvd"))
        assertEquals("dvd", TvdbPlaybackContext.decode(cached.video.tvdb)?.orderType)
        assertEquals("alttwo", TvdbPlaybackContext.decode(indexed.inOrder(TvdbOrderPreference(73244, "alttwo")).video.tvdb)?.orderType)
        media.updateDetails(indexed, other)
        val restarted = MobileOfflineMedia(RuntimeEnvironment.getApplication())
        val record = restarted.saved(1).single()
        val current = TvdbPlaybackContext.decode(record.video.tvdb)!!
        assertEquals(20, selected.episode)
        assertEquals(18, current.episode)
        assertEquals(target.displayEpisode, current.episode)
        assertEquals("dvd", current.orderType)
        assertEquals(selected.episodeIds.toSet(), current.episodeIds.toSet())
        assertEquals(original, record.downloadedVideo)
        assertEquals(restarted.find(original), restarted.find(record.video))
        assertNull(restarted.find(original.copy(tvdb = selected.copy(episodeIds = selected.episodeIds + 999999L).encode())))
        assertNotNull(restarted.localRoute(record.video))
        restarted.remove(record)
        assertFalse(dir.exists())
    }

    @Test fun verifiedEpisodesUseContentIdentityAndStayIsolatedByProfile() {
        val order = TvdbPlaybackContext(42, "official", 501, 2, 4, listOf(501), "tt2:2:4")
        val ep = MobileVideo(1, "series", "tt2", order.requestVideoId, "Series", 2, 4, tvdb = order.encode())
        assertEquals("Series S2E4", ep.copy(season = 1, episode = 3).label)
        assertEquals(ep.key, ep.copy(videoId = "tt2:1:3", tvdb = order.copy(orderType = "dvd", season = 1,
            episode = 3, requestVideoId = "tt2:1:3").encode()).key)
        assertNotEquals(ep.key, ep.copy(profile = 2).key)
        assertNotEquals(ep.key, ep.copy(tvdb = order.copy(episodeIds = listOf(501, 502)).encode()).key)
        assertNotEquals(movie.key, movie.copy(contentId = "tt3", videoId = "tt3").key)
    }

    @Test fun incompleteOrMissingFilesNeverWinAndReplacementSurvivesRestart() {
        val dir = media.folder(movie).apply { mkdirs() }
        val partial = File(dir, "media.part").apply { writeText("first") }
        assertNull(media.find(movie))
        media.publish(movie, partial, "mp4")
        val first = requireNotNull(media.find(movie))
        assertEquals(dir.canonicalFile, first.canonicalFile.parentFile)
        partial.writeText("a partial replacement")
        assertEquals(first, media.find(movie))
        partial.writeText("second")
        media.publish(movie, partial, "mkv")
        val restarted = MobileOfflineMedia(RuntimeEnvironment.getApplication())
        val replacement = requireNotNull(restarted.find(movie))
        assertEquals("second", replacement.readText())
        assertFalse(first.exists())
        replacement.writeText("x")
        assertNull(restarted.find(movie))
        assertTrue(restarted.saved(movie.profile).isEmpty())
    }

    @Test fun removingOneSavedFileUpdatesPlaybackAndPreservesOtherProfiles() {
        fun save(video: MobileVideo): OfflineFile {
            val dir = media.folder(video).apply { mkdirs() }
            val partial = File(dir, "media.part").apply { writeText("saved video") }
            media.publish(video, partial, "mp4")
            return media.saved(video.profile).single { it.video.key == video.key }
        }
        val first = save(movie)
        val replaced = save(movie)
        val other = save(movie.copy(profile = 2))
        assertTrue(runCatching { media.remove(first) }.isFailure)
        assertEquals(media.find(movie)?.name, media.localStream(movie)?.behaviorHints?.filename)
        assertNotNull(media.localRoute(movie))
        val revision = media.changes.value
        media.remove(replaced)
        assertTrue(media.changes.value > revision)
        assertFalse(File(media.folder(movie), replaced.filename).exists())
        assertNull(media.localStream(movie))
        assertNull(media.localRoute(movie))
        assertTrue(media.saved(movie.profile).isEmpty())
        assertNotNull(media.localRoute(other.video))
    }

    @Test fun transferSniffsTheContainerAndRejectsManifestsAndErrorPages() {
        assertEquals("mp4", videoContainer(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray()))
        assertEquals("mkv", videoContainer(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertNull(videoContainer("#EXTM3U".toByteArray()))
        assertNull(videoContainer("<html>login</html>".toByteArray()))
    }

    @Test fun offlineDetailsArtworkAndLocalPlaybackSurviveRestartAndAnOlderDownloadCanBeEnriched() {
        val source = com.nuvio.tv.data.tvdb.tvdbFixtureSource()
        val meta = com.nuvio.tv.data.tvdb.tvdbFixtureMeta(source)
        val video = MobileVideo(1, "series", meta.id, source.first().id, meta.name, 1, 1, source.first().title)
        val dir = media.folder(video).apply { mkdirs() }
        val partial = File(dir, "media.part").apply { writeText("saved") }
        media.publish(video, partial, "mp4") // Original index without offline metadata
        media.updateDetails(media.saved(1).single(), meta)
        val richer = media.saved(1).single()
        assertEquals("provider episode", richer.details?.episodeSynopsis)
        media.updateDetails(richer, meta.copy(videos = emptyList(), description = null))
        assertEquals(richer.details, media.saved(1).single().details)
        partial.writeText("new media")
        media.publish(video, partial, "mp4", cover = byteArrayOf(1, 2, 3))
        val fresh = MobileOfflineMedia(RuntimeEnvironment.getApplication())
        val saved = fresh.saved(1).single()
        assertEquals("provider description", saved.details?.synopsis)
        assertEquals("2002-09-20", saved.details?.episodeAirDate)
        assertNotNull(saved.artwork)
        assertNotNull(fresh.localRoute(video))
        assertTrue(fresh.saved(2).isEmpty())
        fresh.remove(saved)
        assertFalse(dir.exists())
    }
}
