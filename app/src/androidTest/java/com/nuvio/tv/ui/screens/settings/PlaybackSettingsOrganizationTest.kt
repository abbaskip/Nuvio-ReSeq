package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nuvio.tv.TvHiltTestActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.usenet.BuiltInUsenetService
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSettingsOrganizationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TvHiltTestActivity>()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sectionsAreListedInOrder() {
        setSections()

        val tops = PlaybackSection.entries.map { section ->
            composeRule.onNodeWithTag(PlaybackSettingsTestTags.section(section))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        }
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun frameRateAndDolbyVisionLiveUnderVideo() {
        setSections()

        expand(PlaybackSection.AUDIO)
        expand(PlaybackSection.VIDEO)

        assertRowCount(R.string.playback_afr_mode, PlaybackSection.VIDEO, 1)
        assertRowCount(R.string.dv7_handling_title, PlaybackSection.VIDEO, 1)
        assertRowCount(R.string.playback_afr_mode, PlaybackSection.AUDIO, 0)
        assertRowCount(R.string.dv7_handling_title, PlaybackSection.AUDIO, 0)
        assertRowCount(R.string.audio_decoder_priority, PlaybackSection.AUDIO, 1)
    }

    @Test
    fun nextEpisodeRowsLiveUnderUpNextNotStreamSelection() {
        setSections(PlayerSettings(streamAutoPlayNextEpisodeEnabled = true))

        expand(PlaybackSection.STREAM_SELECTION)
        expand(PlaybackSection.UP_NEXT)

        assertRowCount(R.string.autoplay_next_episode, PlaybackSection.UP_NEXT, 1)
        assertRowCount(R.string.still_watching_setting_title, PlaybackSection.UP_NEXT, 1)
        assertRowCount(R.string.autoplay_next_episode, PlaybackSection.STREAM_SELECTION, 0)
        assertRowCount(R.string.autoplay_stream_selection, PlaybackSection.STREAM_SELECTION, 1)
    }

    @Test
    fun autoplayStagesGroupTheirOwnSourcesAndKeepAllowedAddonsBesideTheToggle() {
        setSections(
            PlayerSettings(
                streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
                streamAutoPlayImmediateEnabled = true,
                streamAutoPlayTimeoutSeconds = 5,
                streamAutoPlayAddonsEnabled = true,
                streamAutoPlayAnySourceFallbackEnabled = true
            ),
            hasDirectUsenet = true
        )
        expand(PlaybackSection.STREAM_SELECTION)

        fun row(title: String) = hasText(title, ignoreCase = true) and
            hasAnyAncestor(hasTestTag(PlaybackSettingsTestTags.section(PlaybackSection.STREAM_SELECTION)))
        fun top(title: Int) = composeRule.onNode(row(context.getString(title))).fetchSemanticsNode().positionInRoot.y

        val immediate = top(R.string.autoplay_stage_immediate)
        val timed = top(R.string.autoplay_stage_timed)
        val fallback = top(R.string.autoplay_stage_fallback)
        val other = top(R.string.autoplay_other_stream_options)
        assertTrue(immediate < top(R.string.autoplay_immediate_addons))
        assertTrue(top(R.string.autoplay_immediate_addons) < timed)
        assertTrue(timed < fallback && fallback < other)

        for (source in listOf(context.getString(R.string.autoplay_source_iptv), BuiltInUsenetService.GROUP_NAME)) {
            val sourceTops = composeRule.onAllNodes(row(source)).fetchSemanticsNodes().map { it.positionInRoot.y }
            assertEquals(3, sourceTops.size)
            assertTrue(sourceTops[0] in immediate..timed)
            assertTrue(sourceTops[1] in timed..fallback)
            assertTrue(sourceTops[2] in fallback..other)
        }

        val addons = top(R.string.autoplay_from_addons)
        val allowedAddons = top(R.string.autoplay_allowed_addons)
        assertTrue(timed < addons && addons < allowedAddons && allowedAddons < fallback)
        val interveningRows = composeRule.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag(PlaybackSettingsTestTags.section(PlaybackSection.STREAM_SELECTION)))
        ).fetchSemanticsNodes().count { it.positionInRoot.y > addons && it.positionInRoot.y < allowedAddons }
        assertEquals(0, interveningRows)
    }

    @Test
    fun externalPlayerOptionsOnlyAppearForExternalPlayers() {
        setSections(PlayerSettings(playerPreference = PlayerPreference.INTERNAL))
        expand(PlaybackSection.PLAYER)
        assertRowCount(R.string.playback_external_forward_subtitles, PlaybackSection.PLAYER, 0)
    }

    @Test
    fun skipSegmentsGroupsAutomaticSkipping() {
        setSections()

        expand(PlaybackSection.SKIP_SEGMENTS)

        assertRowCount(R.string.playback_skip_intro, PlaybackSection.SKIP_SEGMENTS, 1)
        assertRowCount(R.string.auto_skip_recap, PlaybackSection.SKIP_SEGMENTS, 1)
        assertRowCount(R.string.auto_skip_movie_credits, PlaybackSection.SKIP_SEGMENTS, 1)
        assertRowCount(R.string.playback_skip_intro, PlaybackSection.PLAYER_INTERFACE, 0)
    }

    @Test
    fun onlySectionHeadersShowIcons() {
        val settings = PlayerSettings(
            playerPreference = PlayerPreference.ASK_EVERY_TIME,
            streamAutoPlayNextEpisodeEnabled = true
        )
        setSections(settings)

        visiblePlaybackSections(settings).forEach { section ->
            expand(section)
            composeRule.onAllNodes(
                hasTestTag(SettingsTestTags.ROW_ICON) and
                    hasAnyAncestor(hasTestTag(PlaybackSettingsTestTags.section(section))),
                useUnmergedTree = true
            ).assertCountEquals(1)
            collapse(section)
        }
    }

    @Test
    fun bufferSectionIsHiddenForLibmpvOnly() {
        setSections(PlayerSettings(internalPlayerEngine = InternalPlayerEngine.MVP_PLAYER))

        composeRule.onAllNodes(hasTestTag(PlaybackSettingsTestTags.section(PlaybackSection.BUFFER_NETWORK)))
            .assertCountEquals(0)
        assertTrue(
            composeRule.onAllNodes(hasTestTag(PlaybackSettingsTestTags.section(PlaybackSection.VIDEO)))
                .fetchSemanticsNodes().size == 1
        )
    }

    private fun setSections(settings: PlayerSettings = PlayerSettings(), hasDirectUsenet: Boolean = false) {
        composeRule.setContent {
            NuvioTheme {
                Box(modifier = Modifier.requiredHeight(6000.dp)) {
                    PlaybackSettingsSections(
                        playerSettings = settings,
                        p2p = P2pSettingsUi(),
                        transparentLetterbox = false,
                        releaseDecoderOnBackground = false,
                        onUpdate = {},
                        onOpenDialog = {},
                        onMemorySettingChanged = {},
                        onClearTorrentCache = {},
                        hasDirectUsenet = hasDirectUsenet
                    )
                }
            }
        }
    }

    private fun expand(section: PlaybackSection) = toggle(section, R.string.layout_closed)

    private fun collapse(section: PlaybackSection) = toggle(section, R.string.layout_open)

    private fun toggle(section: PlaybackSection, currentState: Int) {
        composeRule.onNode(
            hasClickAction() and
                hasText(context.getString(section.title)) and
                hasText(context.getString(currentState)) and
                hasAnyAncestor(hasTestTag(PlaybackSettingsTestTags.section(section)))
        ).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private fun assertRowCount(titleRes: Int, section: PlaybackSection, expected: Int) {
        composeRule.onAllNodes(
            hasText(context.getString(titleRes)) and
                hasAnyAncestor(hasTestTag(PlaybackSettingsTestTags.section(section)))
        ).assertCountEquals(expected)
    }
}
