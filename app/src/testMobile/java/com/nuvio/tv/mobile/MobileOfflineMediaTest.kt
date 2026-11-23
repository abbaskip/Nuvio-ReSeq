package com.nuvio.tv.mobile

import android.app.Application
import com.nuvio.tv.domain.model.TvdbPlaybackContext
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

    @Test fun verifiedEpisodesUseContentIdentityAndStayIsolatedByProfile() {
        val order = TvdbPlaybackContext(42, "official", 501, 2, 4, listOf(501), "tt2:2:4")
        val ep = MobileVideo(1, "series", "tt2", order.requestVideoId, "Series", 2, 4, tvdb = order.encode())
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

    @Test fun transferSniffsTheContainerAndRejectsManifestsAndErrorPages() {
        assertEquals("mp4", videoContainer(byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray()))
        assertEquals("mkv", videoContainer(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertNull(videoContainer("#EXTM3U".toByteArray()))
        assertNull(videoContainer("<html>login</html>".toByteArray()))
    }
}
