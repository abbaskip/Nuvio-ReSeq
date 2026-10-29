package com.nuvio.tv.data.tvdb

import com.nuvio.tv.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TvdbCloudProgressTest {
    private val first = EpisodeSourceIdentity("tt123:5:14", EpisodeCoordinates(5, 14), listOf(101), "First", 22)
    private val second = EpisodeSourceIdentity("tt123:5:15", EpisodeCoordinates(5, 15), listOf(102), "Second", 22)
    private val combined = TvdbPlaybackContext(1, "alternate", 103, 5, 16, listOf(101, 102), "tt123:5:16", 44,
        providerSources = listOf(first, second))
    private fun progress(fraction: Double, context: TvdbPlaybackContext = combined) = WatchProgress(
        "tt123", "series", "Example", null, null, null, context.requestVideoId, context.season, context.episode,
        null, (2_818_000 * fraction).toLong(), 2_818_000, 2000, tvdbContext = context,
        tvdbEpisodeIds = context.episodeIds)

    @Test fun `first half updates only the first verified native episode without changing local time`() {
        val local = progress(0.25)
        val cloud = local.forNuvioCloud().single()
        assertEquals(14, cloud.episode)
        assertEquals(first.videoId, cloud.videoId)
        assertEquals(0.5f, cloud.progressPercentage, 0.001f)
        assertEquals(22 * 60_000L, cloud.duration)
        assertEquals(704_500L, local.position)
        assertEquals(16, local.episode)
    }

    @Test fun `second half completes the first native episode and partially advances the second`() {
        val cloud = progress(0.75).forNuvioCloud().associateBy { it.episode }
        assertEquals(setOf(14, 15), cloud.keys)
        assertTrue(cloud.getValue(14).isCompleted())
        assertEquals(0.5f, cloud.getValue(15).progressPercentage, 0.001f)
        assertTrue(cloud.values.all { it.episode != 16 && it.lastWatched == 2000L })
    }

    @Test fun `the backend cannot complete the first episode before the estimated boundary`() {
        val before = progress(0.49).forNuvioCloud().single()
        assertTrue(before.position < before.duration * 0.9)
        assertEquals(0.89f, before.progressPercentage, 0.001f)
        val boundary = progress(0.5).forNuvioCloud().single()
        assertEquals(14, boundary.episode)
        assertEquals(boundary.duration, boundary.position)
        assertTrue(progress(1.0).forNuvioCloud().all { it.isCompleted() })
    }

    @Test fun `known durations weight the verified sequence independently of input or numeric order`() {
        val context = combined.copy(providerSources = listOf(second.copy(runtimeMinutes = 40), first.copy(runtimeMinutes = 20)))
        val cloud = progress(0.5, context).forNuvioCloud()
        assertTrue(cloud.single { it.episode == 14 }.isCompleted())
        assertEquals(0.25f, cloud.single { it.episode == 15 }.progressPercentage, 0.001f)
        assertEquals(50f, nativeCloudResume("tt123", context, cloud)!!.progressPercent!!, 0.01f)
    }

    @Test fun `missing part durations use equal boundaries and preserve inverse resume percentage`() {
        val context = combined.copy(providerSources = listOf(first.copy(runtimeMinutes = null), second.copy(runtimeMinutes = null)))
        val cloud = progress(0.75, context).forNuvioCloud()
        val resume = nativeCloudResume("tt123", context, cloud)!!
        assertEquals(75f, resume.progressPercent!!, 0.001f)
        assertEquals(0L, resume.position)
        assertEquals(2_113_500L, resume.resolveResumePosition(2_818_000))
    }

    @Test fun `split video progress occupies only its verified range of a combined native episode`() {
        val native = first.copy(episodeIds = listOf(101, 102), runtimeMinutes = 44)
        val part1 = combined.copy(episodeIds = listOf(101), providerSources = listOf(native))
        val part2 = combined.copy(episodeIds = listOf(102), providerSources = listOf(native))
        assertEquals(0.25f, progress(0.5, part1).forNuvioCloud().single().progressPercentage, 0.001f)
        assertEquals(0.75f, progress(0.5, part2).forNuvioCloud().single().progressPercentage, 0.001f)
        assertFalse(progress(1.0, part2).forNuvioCloud().single().isCompleted())
        assertTrue(progress(1.0, part2).forNuvioCloud(setOf(101)).single().isCompleted())
    }

    @Test fun `uncertain duration coverage cannot complete any native target`() {
        val uncertain = progress(1.0).copy(tvdbCoverageVerified = false)
        assertTrue(uncertain.forNuvioCloud().all { it.position < it.duration * 0.9 })
    }

    @Test fun `ordinary extended cuts retain their actual duration and position without scaling`() {
        val context = combined.copy(episodeIds = listOf(101), providerSources = listOf(first))
        val local = progress(0.75, context)
        val cloud = local.forNuvioCloud().single()
        assertEquals(local.position, cloud.position)
        assertEquals(local.duration, cloud.duration)
        assertEquals(14, cloud.episode)
    }

    @Test fun `fresh device resumes from native records and rejects the unrelated selected-number record`() {
        val records = progress(0.75).forNuvioCloud()
        val wrong = records.first().copy(episode = 16, videoId = combined.requestVideoId, lastWatched = 9000)
        val context = TvdbPlaybackContext.decode(combined.encode())!!
        val resume = nativeCloudResume("tt123", context, records + wrong)!!
        assertEquals(75f, resume.progressPercent!!, 0.001f)
        assertEquals(combined.requestVideoId, resume.videoId)
        assertEquals(combined.episodeIds.toSet(), resume.tvdbContentIds)
        assertNull(nativeCloudResume("tt123", context, listOf(wrong)))
    }

    @Test fun `newer replay wins over older completed parts and equal timestamps follow content sequence`() {
        val older = progress(1.0).forNuvioCloud().map { it.copy(lastWatched = 1000) }
        val replay = progress(0.25).forNuvioCloud()
        assertEquals(25f, nativeCloudResume("tt123", combined, older + replay)!!.progressPercent!!, 0.001f)
        val current = progress(0.75).forNuvioCloud()
        assertEquals(75f, nativeCloudResume("tt123", combined, current.reversed())!!.progressPercent!!, 0.001f)
    }

    @Test fun `missing or ambiguous correspondence never falls back to the displayed numbers`() {
        assertTrue(progress(0.25, combined.copy(providerSources = null)).forNuvioCloud().isEmpty())
        assertTrue(progress(0.25, combined.copy(providerSources = listOf(first, first, second))).forNuvioCloud().isEmpty())
        assertNull(nativeCloudResume("tt123", combined.copy(providerSources = null), progress(0.75).forNuvioCloud()))
        val native = progress(0.25).copy(tvdbContext = null)
        assertSame(native, native.forNuvioCloud().single())
    }
}
