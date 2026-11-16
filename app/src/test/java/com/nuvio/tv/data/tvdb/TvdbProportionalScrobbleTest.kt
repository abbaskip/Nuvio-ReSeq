package com.nuvio.tv.data.tvdb

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.data.cw.*
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.mdblist.*
import com.nuvio.tv.data.remote.api.*
import com.nuvio.tv.data.remote.dto.trakt.*
import com.nuvio.tv.data.repository.*
import com.nuvio.tv.domain.model.*
import com.nuvio.tv.ui.screens.home.homeCard
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class TvdbProportionalScrobbleTest {
    @get:org.junit.Rule val androidRename = com.nuvio.tv.data.cw.AndroidAtomicRenameRule()
    private val orders = officeOrderFixtures()
    private val original = recordedProvider(orders.getValue("official"))
    private val effective = tvdbEpisodeOverlay(original, 73244, orders.getValue("alttwo"))
    private val store = mockk<TvdbOrderStore> {
        coEvery { selected(1, any()) } returns TvdbOrderPreference(73244, "alttwo")
        coEvery { knownSeries(1, any()) } returns 73244
    }
    private val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    private val overlay = mockk<TvdbEpisodeResolver> {
        coEvery { apply(1, original) } returns effective
        coEvery { forOrder(1, original, any()) } returns effective
    }
    private val source = mockk<MetaRepositoryImpl> { every { getCachedMeta("series", original.id) } returns original }
    private val trakt = mockk<TraktApi>()
    private val auth = mockk<TraktAuthService>()
    private val local = mockk<WatchProgressPreferences> { coEvery { getAllRawEntries(1) } returns emptyMap() }
    private val rows = Json.parseToJsonElement(javaClass.getResourceAsStream("/tvdb/office-trakt-episodes.json")!!
        .bufferedReader().use { it.readText() }).jsonArray.flatMap { it.jsonObject.getValue("episodes").jsonArray }
        .map { value -> value.jsonObject.let { row ->
            val ids = row.getValue("ids").jsonObject
            TraktEpisodeDto(row["title"]?.jsonPrimitive?.contentOrNull,
                row.getValue("season").jsonPrimitive.int, row.getValue("number").jsonPrimitive.int,
                TraktIdsDto(tvdb = ids["tvdb"]?.jsonPrimitive?.intOrNull, trakt = ids["trakt"]?.jsonPrimitive?.intOrNull),
                row["first_aired"]?.jsonPrimitive?.contentOrNull)
        } }
    private val nativeMedia = MdbListMedia(MdbListIds(imdb = original.id, tvdb = 73244, tmdb = 2316))
    private val mdbSnapshot = com.nuvio.tv.data.mdblist.MdbListSyncSnapshot(1,
        watched = rows.map { row -> MdbListWatchedRecord(MdbListItemType.EPISODE, nativeMedia, "2026-01-01",
            row.season, row.number, row.title, episodeTmdbId = row.ids!!.trakt!!.toLong(), episodeTvdbId = row.ids.tvdb?.toLong()) })
    private val mdb = mockk<MdbListSyncRepository> {
        coEvery { ensureLoaded() } returns mdbSnapshot
        every { state } returns MutableStateFlow(MdbListSyncState(snapshot = mdbSnapshot))
    }
    private val resolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth,
        mockk<TmdbApi>(), mockk<TmdbService>(), local,
        simkl = dagger.Lazy { fixtureSimkl(73244, *rows.mapNotNull { row -> row.ids?.tvdb?.toLong()?.let {
            it to EpisodeCoordinates(row.season!!, row.number!!)
        } }.toTypedArray()) }, mdbList = dagger.Lazy { mdb })

    init {
        coEvery { auth.executeAuthorizedRequest<List<TraktSearchResultDto>>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<List<TraktSearchResultDto>>>().invoke("fixture")
        }
        coEvery { trakt.searchById("fixture", "imdb", original.id, "show") } returns Response.success(listOf(
            TraktSearchResultDto(show = TraktShowDto(ids = TraktIdsDto(tvdb = 73244, trakt = 100)))))
        coEvery { trakt.getShowSeasons("fixture", "100", "episodes,full") } returns Response.success(
            rows.groupBy { it.season }.map { (season, episodes) -> TraktSeasonDto(season, episodes = episodes) })
    }

    private val lecture get() = effective.videos.single { it.episodeIdentity!!.tvdbEpisodeId == 10718533L }
    private fun event(percent: Double, video: Video = lecture) = TrackingScrobbleEvent(
        buildTrackingMediaReference("series", original.id, video.id, "The Office", seasonNumber = video.season,
            episodeNumber = video.episode, episodeTitle = video.title).let {
            it.copy(catalog = it.catalog!!.copy(tvdbContext = video.tvdbPlaybackContext(), tvdbCoverageVerified = true))
        }, percent)

    @Test fun `combined partial events use the active verified part for every tracker and cannot complete early`() = runTest {
        for (provider in TrackingProviderId.entries) {
            for ((percent, episode, expected) in listOf(Triple(0.0, 14, 0.0), Triple(25.0, 14, 50.0),
                Triple(49.0, 14, 79.0), Triple(50.0, 15, 0.0), Triple(75.0, 15, 50.0), Triple(89.0, 15, 78.0))) {
                for (action in TrackingScrobbleAction.entries) {
                    val (mappedAction, mapped) = resolver.scrobbleEvents(provider, action, event(percent))!!.single()
                    assertEquals(if (action == TrackingScrobbleAction.START) action else TrackingScrobbleAction.PAUSE, mappedAction)
                    assertEquals(episode, mapped.media.episode!!.number)
                    assertEquals(5, mapped.media.episode!!.season)
                    assertEquals(expected, mapped.progressPercent, 0.01)
                    assertFalse(mapped.allowCompletion)
                    assertEquals("tt0386676:5:16", event(percent).media.catalog!!.videoId)
                }
            }
        }
    }

    @Test fun `valid source numbers cannot substitute for a different assigned ReSeq episode`() = runTest {
        val playback = event(50.0)
        val catalog = requireNotNull(playback.media.catalog)
        val other = effective.videos.single { it.displaySeason == 6 && it.displayEpisode == 17 }
        val wrongIdentity = playback.media.copy(catalog = catalog.copy(tvdbContext = other.tvdbPlaybackContext()))
        for (provider in TrackingProviderId.entries) {
            for (action in TrackingScrobbleAction.entries) {
                assertTrue(resolver.scrobbleEvents(provider, action, playback.copy(media = wrongIdentity)).orEmpty().isEmpty())
            }
            for (completion in listOf(true, null)) {
                assertTrue(resolver.resolveAll(1, provider, wrongIdentity, completed = completion).orEmpty().isEmpty())
            }
        }
    }

    @Test fun officeAlternateOrderTrackerContract() = runTest {
        val events = mutableListOf<String>()
        val actual = mutableListOf<String>()
        val mapping = mockk<TraktEpisodeMappingService>(relaxUnitFun = true)
        coEvery { auth.getCurrentAuthState() } returns com.nuvio.tv.data.local.TraktAuthState("fixture", "fixture")
        every { auth.hasRequiredCredentials() } returns true
        coEvery { auth.executeAuthorizedWriteRequest<Any?>(any()) } coAnswers {
            firstArg<suspend (String) -> Response<Any?>>().invoke("fixture")
        }
        val progress = spyk(TraktProgressService(mockk(relaxed = true), trakt, auth, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mapping, profiles,
            mockk(relaxed = true)))
        coEvery { progress.refreshNow() } returns Unit
        val tracker = TraktTrackingScrobbler(TraktScrobbleService(trakt, auth, progress, profiles), mapping)
        val history = TraktTrackingHistoryWriter(progress, profiles)
        fun scrobble(action: String, body: TraktScrobbleRequestDto): Response<TraktScrobbleResponseDto> {
            events += listOf(action, body.show?.ids, body.episode, body.progress).joinToString("|")
            return Response.success(TraktScrobbleResponseDto(action))
        }
        coEvery { trakt.scrobbleStart("fixture", any()) } coAnswers { scrobble("start", secondArg()) }
        coEvery { trakt.scrobblePause("fixture", any()) } coAnswers { scrobble("pause", secondArg()) }
        coEvery { trakt.scrobbleStop("fixture", any()) } coAnswers { scrobble("stop", secondArg()) }
        coEvery { trakt.addHistory("fixture", any()) } coAnswers {
            val body = secondArg<TraktHistoryAddRequestDto>()
            events += "add|" + body.shows.orEmpty().map { show ->
                show.ids to show.seasons.orEmpty().map { it.number to it.episodes.orEmpty().map { ep -> ep.number to ep.ids } }
            } + "|" + body.episodes.orEmpty().map { it.number to it.ids }
            Response.success(TraktHistoryAddResponseDto(added = TraktHistoryRemoveCountDto(episodes = 2)))
        }
        coEvery { trakt.removeHistory("fixture", any()) } coAnswers {
            events += "remove|" + secondArg<TraktHistoryRemoveRequestDto>()
            Response.success(TraktHistoryRemoveResponseDto(deleted = TraktHistoryRemoveCountDto(episodes = 2)))
        }
        val registry = mockk<TrackingProviderRegistry> { every { connectedScrobblers() } returns listOf(tracker) }
        val coordinator = TrackingScrobbleCoordinator(registry, resolver)
        val split = tvdbEpisodeOverlay(original, 73244, orders.getValue("alternate"))
        for ((meta, video) in listOf(effective to lecture,
            effective to effective.videos.single { it.displaySeason == 6 && it.displayEpisode == 17 },
            split to split.videos.first { tvdbEpisodeBaseTitle(it.title) == tvdbEpisodeBaseTitle("Weight Loss") })) {
            coEvery { overlay.forOrder(1, original, any()) } returns meta
            events.clear()
            for ((action, at) in listOf(TrackingScrobbleAction.START to 0.0, TrackingScrobbleAction.PAUSE to 47.0,
                TrackingScrobbleAction.START to 47.0, TrackingScrobbleAction.STOP to 94.3592)) {
                assertTrue(coordinator.scrobble(action, event(at, video)).isEmpty())
            }
            assertTrue("The fixture must actually deliver playback", events.size >= 4)
            val media = event(100.0, video).media
            for (watched in listOf(true, false)) {
                val targets = resolver.resolveAll(1, tracker.providerId, media, completed = true.takeIf { watched })
                requireNotNull(targets)
                events += if (watched) "WATCHED" else "UNWATCHED"
                if (watched) history.addToHistory(1, targets.map { TrackingHistoryItem(it, 1_700_000_000_000) })
                else history.removeFromHistory(1, targets)
            }
            actual += events.map { "OFFICE_CONTRACT|${video.episodeIdentity!!.tvdbEpisodeId}|$it" }
        }
        val reference = javaClass.getResourceAsStream("/tvdb/office-98-tracker-contract.txt")!!
            .bufferedReader().use { it.readLines() }
        assertEquals(reference, actual)
    }

    @Test fun deliveryThresholdAdvancesAtTheEndOfEachSelectedPart() = runTest {
        val first = effective.videos.single { it.episodeIdentity?.tvdbEpisodeId == 10719652L }
        val second = effective.videos.single { it.displaySeason == first.displaySeason && it.displayEpisode == first.displayEpisode!! + 1 }
        val ids = listOf(first, second).flatMap { it.tvdbPlaybackContext()!!.episodeIds }
        val pause = WatchProgress(original.id, "series", "The Office", null, null, null, "native", 6, 16,
            "The Delivery", 0, 0, 1234, source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktEpisodeId = 182129)
        fun parts(percent: Float) = projectTrackerParts(pause.copy(progressPercent = percent), ids, effective.videos, .98f)
        assertFalse(parts(48.9f).single().isCompleted())
        assertTrue(parts(49f).single().isCompleted())
        val later = parts(55f)
        assertEquals(first.id, later.single { it.isCompleted() }.videoId)
        assertEquals(10f, later.single { !it.isCompleted() }.progressPercent!!, .001f)
        assertTrue(parts(99f).all { it.isCompleted() })
        val weighted = listOf(first, second).map { part ->
            part.copy(episodeIdentity = part.episodeIdentity!!.copy(providerSources = emptyList(),
                contentRuntimeMinutes = ids.zip(listOf(20, 40)).toMap()))
        }
        fun weightedFirst(percent: Float) = projectTrackerParts(pause.copy(progressPercent = percent), ids, weighted, .98f)
            .single { it.videoId == first.id }
        assertFalse(weightedFirst(32.6f).isCompleted())
        assertTrue(weightedFirst(32.7f).isCompleted())
        val next = resolver.continueWatchingResume(1, pause.copy(progressPercent = 50f))!!
        assertEquals(second.id, next.videoId)
        assertEquals(0f, next.progressPercentage)
        val completed = resolver.continueWatchingResume(1, pause.copy(progressPercent = 100f))!!
        assertEquals(second.displayEpisode!! + 1, completed.tvdbContext!!.episode)
    }

    @Test fun nativeSimklAndMdbListPausesUseTheSameVerifiedSplitForDetailsAndHome() = runTest {
        val first = effective.videos.single { it.episodeIdentity?.tvdbEpisodeId == 10719652L }
        val second = effective.videos.single { it.displaySeason == first.displaySeason && it.displayEpisode == first.displayEpisode!! + 1 }
        val combinedId = 123456789L
        val content = EpisodeContentIdentity(combinedId, "The Delivery", null, EpisodeCoordinates(6, 16),
            listOf(first, second).flatMap { it.tvdbPlaybackContext()!!.episodeIds })
        val order = requireNotNull(effective.episodeOrder)
        coEvery { overlay.apply(1, original) } returns effective.copy(episodeOrder = order.copy(content = order.content + content))
        for ((provider, origin) in listOf("simkl" to WatchProgress.SOURCE_SIMKL_PLAYBACK, "mdblist" to WatchProgress.SOURCE_REMOTE_PLAYBACK)) {
            val paused = WatchProgress(original.id, "series", "The Office", null, null, null, "native", 6, 16, "The Delivery",
                0, 0, 1234, source = origin, trackingProviderId = provider, tvdbEpisodeId = combinedId, progressPercent = 55f)
            val details = resolver.projectProgress(1, original.id, mapOf((6 to 16) to paused))
            assertTrue(details.getValue(first.season!! to first.episode!!).isCompleted())
            val focused = resolver.continueWatchingResume(1, paused)!!
            assertEquals(second.id, focused.videoId)
            assertEquals(10f, focused.progressPercent!!, .001f)
            assertEquals(focused.progressPercent, details.getValue(second.season!! to second.episode!!).progressPercent)
        }
    }

    @Test fun combinedLectureCircuitRetainsIndependentEvidenceForBothNativeEpisodes() {
        val nativeIds = lecture.tvdbPlaybackContext()!!.episodeIds
        assertEquals(2, nativeIds.size)
        val raw = WatchProgress(original.id, "series", original.name, null, null, null, "native",
            5, 14, null, 0, 0, 1_000, source = WatchProgress.SOURCE_TRAKT_PLAYBACK)
        fun read(secondPercent: Float, includeFirst: Boolean): WatchProgress {
            val first = if (includeFirst) projectTrackerParts(raw.copy(progressPercent = 100f),
                nativeIds.take(1), listOf(lecture), .98f) else emptyList()
            val second = projectTrackerParts(raw.copy(progressPercent = secondPercent, lastWatched = 2_000),
                nativeIds.takeLast(1), listOf(lecture), .98f)
            return reconcileTvdbProgress(effective, emptyMap(), projected = first + second).getValue(lecture.season!! to lecture.episode!!)
        }
        assertFalse(read(100f, false).isCompleted())
        assertEquals(75f, read(50f, true).progressPercent!!, .001f)
        assertFalse(read(95f, true).isCompleted())
        assertTrue(read(96f, true).isCompleted())
        assertTrue(read(100f, true).isCompleted())
    }

    @Test fun officeRewatchPreservesBothTheNewPlaybackSessionAndTheOlderCompletion() = runTest {
        val played = WatchProgress(original.id, "series", "The Office", null, null, null, "native", 6, 16,
            "The Delivery", 1, 1, 1_500_000_000_000L, progressPercent = 100f,
            source = WatchProgress.SOURCE_TRAKT_HISTORY, traktEpisodeId = 182129)
        val pause = played.copy(source = WatchProgress.SOURCE_TRAKT_PLAYBACK, lastWatched = 1_794_747_546_000L,
            position = 0, duration = 0, progressPercent = 49.75f, traktPlaybackId = 77)
        for (fraction in listOf(49.75f, 50f)) {
            val rows = resolver.projectProgress(1, original.id, emptyMap(), listOf(played, pause.copy(progressPercent = fraction)))
            val first = rows.values.single { it.tvdbContext?.episodeId == 10719652L }
            val second = rows.values.single { it.tvdbContext?.season == 6 && it.tvdbContext?.episode == 18 }
            assertTrue(first.hasRemovableTraktWatch())
            assertEquals(77L, first.traktPlaybackId)
            assertEquals(pause.lastWatched, first.lastWatched)
            assertTrue(second.isCompleted())
            assertEquals(played.lastWatched, second.lastWatched)
            assertNull(second.traktPlaybackId)
        }
    }

    @Test fun staleDeliveryPartOneYieldsToLatestPauseAndCannotOverwritePartTwo() = runTest {
        val show = original.id
        val raw = WatchProgress(show, "series", "The Office", null, null, null, "$show:6:11", 6, 11,
            "Shareholder Meeting", 0, 1_680_000, 1_794_747_546_000L, progressPercent = 49.75f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktEpisodeId = 182129, tvdbEpisodeId = 1832421L)
        val first = effective.videos.single { it.episodeIdentity?.tvdbEpisodeId == 10719652L }
        val stale = raw.seriesChoice().copy(videoId = first.id, season = 6, episode = 17, episodeTitle = first.title,
            tvdbContext = first.tvdbPlaybackContext(), tvdbEpisodeId = 7931991, chooseEpisode = false,
            lastWatched = 1_794_541_740_000L)
        val directory = java.nio.file.Files.createTempDirectory("office-cw-upgrade").toFile()
        val configuredResolver = TvdbTrackingResolver(store, dagger.Lazy { overlay }, source, profiles, trakt, auth,
            mockk(), mockk(), playerSettings = mockk {
                every { playerSettings } returns MutableStateFlow(mockk { every { nextEpisodeThresholdPercent } returns 98f })
            })
        val remote = mockk<com.nuvio.tv.domain.repository.WatchProgressRepository> {
            every { allProgress } returns MutableStateFlow(listOf(raw))
            every { observeNextUpSeeds() } returns MutableStateFlow(emptyList())
            every { observeRemoteProgressLoaded() } returns MutableStateFlow(true)
            every { hasActiveTrackingProgressProvider() } returns true
            every { isDroppedShow(any()) } returns false
        }
        coEvery { store.exportSelections(1) } returns mapOf("alias:$show" to "73244", "order:73244" to "alttwo")
        every { profiles.resolvedActiveProfileId } returns MutableStateFlow(1)
        fun owner() = ContinueWatchingRepository(mockk { every { filesDir } returns directory }, profiles,
            mockk { coEvery { getWatchProgressSource(1) } returns com.nuvio.tv.data.local.WatchProgressSource.TRAKT },
            javax.inject.Provider { remote }, tvdbOrders = store, tvdbTracking = javax.inject.Provider { configuredResolver })
        try {
            CwProjectionStore(java.io.File(directory, "cw_enrichment")).write(CwProjection(profileId = 1, source = "TRAKT",
                members = mapOf(show to CwMember(progress = raw, nextUp = stale))))
            val cw = owner()
            assertTrue(cw.snapshot().nextUp.isEmpty())
            cw.refreshProjection()
            val rebuilt = cw.snapshot()
            val partTwo = rebuilt.nextUp.single()
            assertEquals("The Delivery Part 2 (Extended Cut)", partTwo.episodeTitle)
            assertEquals(raw.lastWatched, partTwo.lastWatched)
            cw.enrich(rebuilt.lease, nextUp = listOf(stale))
            assertEquals(partTwo, cw.snapshot().nextUp.single())
            cw.enrich(rebuilt.lease, nextUp = listOf(stale.copy(lastWatched = raw.lastWatched)))
            assertEquals(partTwo, cw.snapshot().nextUp.single())
            val withArtwork = partTwo.copy(thumbnail = "new-thumbnail")
            cw.enrich(rebuilt.lease, nextUp = listOf(withArtwork))
            assertEquals(withArtwork, owner().snapshot().nextUp.single())
        } finally { directory.deleteRecursively() }
    }

    @Test fun splitOfficePauseSurvivesProviderReplayAndHomeRestart() = runTest {
        val show = original.id
        val raw = WatchProgress(show, "series", "The Office", null, null, null, "$show:6:11", 6, 11,
            "Shareholder Meeting", 0, 0, 1234L, progressPercent = 47.1796f,
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK, traktEpisodeId = 182129, tvdbEpisodeId = 1832421L)
        val directory = java.nio.file.Files.createTempDirectory("office-cw").toFile()
        val remote = mockk<com.nuvio.tv.domain.repository.WatchProgressRepository> {
            every { allProgress } returns MutableStateFlow(listOf(raw))
            every { observeNextUpSeeds() } returns MutableStateFlow(emptyList())
            every { observeRemoteProgressLoaded() } returns MutableStateFlow(true)
            every { isDroppedShow(any()) } returns false
        }
        coEvery { store.exportSelections(1) } returns mapOf("alias:$show" to "73244", "order:73244" to "alttwo")
        every { profiles.resolvedActiveProfileId } returns MutableStateFlow(1)
        fun owner() = com.nuvio.tv.data.cw.ContinueWatchingRepository(
            mockk { every { filesDir } returns directory }, profiles,
            mockk { coEvery { getWatchProgressSource(1) } returns com.nuvio.tv.data.local.WatchProgressSource.TRAKT },
            javax.inject.Provider { remote }, tvdbOrders = store, tvdbTracking = javax.inject.Provider { resolver })
        try {
            val cw = owner()
            repeat(2) { cw.refreshProjection() }
            val member = owner().snapshot().members.getValue(show)
            val card = member.inProgress!!.homeCard(member.progress)
            assertEquals(raw, member.progress)
            assertFalse(card.chooseEpisode)
            assertEquals(10719652L, card.progress.tvdbContext?.episodeId)
            assertEquals("The Delivery Part 1 (Extended Cut)", card.progress.episodeTitle)
            assertEquals(94.3592f, card.progress.progressPercent!!, 0.001f)
            assertFalse(card.progress.isCompleted())
            coVerify(exactly = 0) { trakt.scrobbleStart(any(), any()); trakt.scrobblePause(any(), any())
                trakt.scrobbleStop(any(), any()); trakt.addHistory(any(), any()); trakt.removeHistory(any(), any()) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun `only verified whole completion stops both native parts and avoids automatic history duplication`() = runTest {
        for (provider in TrackingProviderId.entries) {
            val completed = resolver.scrobbleEvents(provider, TrackingScrobbleAction.STOP, event(99.0))!!
            assertEquals(setOf(14, 15), completed.map { it.second.media.episode!!.number }.toSet())
            assertTrue(completed.all { it.first == TrackingScrobbleAction.STOP && it.second.progressPercent == 100.0 })
            assertTrue(resolver.resolveAll(1, provider, event(99.0).media, true, coverageChangesOnly = true)!!.isEmpty())
            assertEquals(2, resolver.resolveAll(1, provider, event(99.0).media, true)!!.size)
        }
    }

    @Test fun `boundary handoff pauses the old part then starts the new without periodic API writes`() = runTest {
        val adapters = TrackingProviderId.entries.map { id -> mockk<TrackingScrobbler>(relaxed = true) { every { providerId } returns id } }
        val coordinator = TrackingScrobbleCoordinator(mockk { every { connectedScrobblers() } returns adapters }, resolver)
        assertTrue(coordinator.scrobbleBoundary(event(49.99), event(50.01)).isEmpty())
        adapters.forEach { adapter ->
            coVerifyOrder {
                adapter.scrobble(TrackingScrobbleAction.PAUSE, match { it.media.episode!!.number == 14 && it.progressPercent < 80 })
                adapter.scrobble(TrackingScrobbleAction.START, match { it.media.episode!!.number == 15 && it.progressPercent < 1 })
            }
        }
        adapters.forEach { clearMocks(it, answers = false) }
        assertTrue(coordinator.scrobbleBoundary(event(60.0), event(61.0)).isEmpty())
        adapters.forEach { coVerify(exactly = 0) { it.scrobble(any(), any()) } }
        assertTrue(coordinator.scrobbleBoundary(event(49.0), event(51.0).let {
            it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(contentId = "other")))
        }).isEmpty())
        adapters.forEach { coVerify(exactly = 0) { it.scrobble(any(), any()) } }
    }

    @Test fun `split source cannot complete the combined tracker until both verified parts finish`() = runTest {
        val split = tvdbEpisodeOverlay(original, 73244, orders.getValue("alternate"))
        coEvery { overlay.forOrder(1, original, any()) } returns split
        val parts = split.videos.filter { tvdbEpisodeBaseTitle(it.title) == tvdbEpisodeBaseTitle("Weight Loss") }
            .sortedBy { it.displayEpisode }
        assertEquals(2, parts.size)
        val first = resolver.scrobbleEvents(TrackingProviderId.TRAKT, TrackingScrobbleAction.STOP, event(99.0, parts[0]))!!.single()
        assertEquals(TrackingScrobbleAction.PAUSE, first.first)
        assertFalse(first.second.allowCompletion)
        assertEquals(49.5, first.second.progressPercent, 0.01)
        for (action in TrackingScrobbleAction.entries) {
            assertTrue(resolver.scrobbleEvents(TrackingProviderId.TRAKT, action, event(99.0, parts[1]))!!.isEmpty())
        }
        val completed = WatchProgress(original.id, "series", "Example", null, null, null, parts[0].id,
            parts[0].season, parts[0].episode, parts[0].title, 1, 1, 1, tvdbContext = parts[0].tvdbPlaybackContext())
        coEvery { local.getAllRawEntries(1) } returns mapOf("part" to completed)
        val whole = resolver.scrobbleEvents(TrackingProviderId.TRAKT, TrackingScrobbleAction.STOP, event(99.0, parts[1]))!!.single()
        assertEquals(TrackingScrobbleAction.STOP, whole.first)
        assertEquals(100.0, whole.second.progressPercent, 0.0)
    }

    @Test fun `duration rejection and Provider Default still bypass estimated tracking`() = runTest {
        for (provider in TrackingProviderId.entries) {
            val invalid = event(95.0).let { it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(tvdbCoverageVerified = false))) }
            assertTrue(resolver.scrobbleEvents(provider, TrackingScrobbleAction.STOP, invalid)!!.isEmpty())
            val native = event(30.0).let { it.copy(media = it.media.copy(catalog = it.media.catalog!!.copy(providerDefault = true))) }
            assertEquals(listOf(TrackingScrobbleAction.PAUSE to native), resolver.scrobbleEvents(provider, TrackingScrobbleAction.PAUSE, native))
        }
    }

    @Test fun `ordinary extended cut keeps actual full-video progress without ratio scaling`() = runTest {
        val ordinary = effective.videos.single { it.title.startsWith("Business Ethics") }
        for (provider in TrackingProviderId.entries) {
            val mapped = resolver.scrobbleEvents(provider, TrackingScrobbleAction.PAUSE, event(85.0, ordinary))!!.single()
            assertEquals(85.0, mapped.second.progressPercent, 0.0)
            assertTrue(mapped.second.allowCompletion)
            assertEquals(2, mapped.second.media.episode!!.number)
        }
    }
}
