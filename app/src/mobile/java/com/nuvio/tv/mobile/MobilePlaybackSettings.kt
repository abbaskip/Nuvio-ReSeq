package com.nuvio.tv.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.tv.data.local.*
import com.nuvio.tv.ui.screens.settings.PlaybackSettingsViewModel
import kotlinx.coroutines.launch

@Composable
internal fun TogglePreference(label: String, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
        .toggleable(enabled, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = set)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(label, Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun <T : Enum<T>> OptionPreference(label: String, current: T, choices: List<T>, select: (T) -> Unit) {
    Label(label)
    LazyRow { items(choices) { entry ->
        val title = if (entry is AudioOutputChannels) entry.displayLabel else entry.name.lowercase().replace('_', ' ')
        Action(title, selected = current == entry) { select(entry) }
    } }
}

@Composable
private fun LanguagePreference(label: String, current: String?, audio: Boolean = false, optional: Boolean = false, select: (String?) -> Unit) {
    val choices = remember(audio, optional) {
        buildList<Pair<String?, String>> {
            if (optional) add(null to "None")
            add("device" to "Device language")
            if (audio) { add(AudioLanguageOption.DEFAULT to "Media default"); add(AudioLanguageOption.ORIGINAL to "Original language") }
            addAll(AVAILABLE_SUBTITLE_LANGUAGES.map { it.code to it.displayName })
        }
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    Action(label + " · " + (choices.firstOrNull { it.first == current }?.second ?: current.orEmpty())) { picking = true }
    if (picking) TouchSheet({ picking = false }) {
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            item { BrowseHeading(label) { picking = false } }
            items(choices) { (code, name) -> Action(name, Modifier.fillMaxWidth(), code == current) { select(code); picking = false } }
        }
    }
}

@Composable
internal fun MobilePlaybackPreferences(state: PlayerSettings, vm: PlaybackSettingsViewModel) {
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    listOf("Playback failure and recovery", "Next episode and recommendations", "Audio and decoder", "Subtitles", "Network and cache").forEach { title ->
        Action(title, Modifier.fillMaxWidth()) { page = title }
    }
    page?.let { group ->
        TouchSheet({ page = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrowseHeading(group) { page = null }
                when (group) {
                    "Playback failure and recovery" -> {
                        TogglePreference("Reuse last working link", state.streamReuseLastLinkEnabled) { scope.launch { vm.setStreamReuseLastLinkEnabled(it) } }
                        ChoiceRow("Remember link (hours)", state.streamReuseLastLinkCacheHours, listOf(1, 3, 6, 12, 24)) { scope.launch { vm.setStreamReuseLastLinkCacheHours(it) } }
                        TogglePreference("Try another source on failure", state.streamAutoPlayFallbackOnFailure) { scope.launch { vm.setStreamAutoPlayFallbackOnFailure(it) } }
                        ChoiceRow("Maximum fallback attempts", state.streamAutoPlayFallbackMaxSources, (1..10).toList()) { scope.launch { vm.setStreamAutoPlayFallbackMaxSources(it) } }
                        ChoiceRow("Startup timeout (seconds)", state.streamAutoPlayFallbackStartupTimeoutSeconds, (5..45 step 5).toList()) { scope.launch { vm.setStreamAutoPlayFallbackStartupTimeoutSeconds(it) } }


                        TogglePreference("Prefer the same release group for the next episode", state.streamAutoPlayPreferBingeGroupForNextEpisode) { scope.launch { vm.setStreamAutoPlayPreferBingeGroupForNextEpisode(it) } }
                        TogglePreference("Remember the release group", state.streamAutoPlayReuseBingeGroup) { scope.launch { vm.setStreamAutoPlayReuseBingeGroup(it) } }
                        TogglePreference("Preload next episode sources", state.preloadNextEpisodeSources) { scope.launch { vm.setPreloadNextEpisodeSources(it) } }
                        TogglePreference("Pass subtitles to external players", state.externalPlayerForwardSubtitles) { scope.launch { vm.setExternalPlayerForwardSubtitles(it) } }
                        TogglePreference("Pass skip segments to external players", state.externalPlayerSendSkipSegments) { scope.launch { vm.setExternalPlayerSendSkipSegments(it) } }

                    }
                    "Next episode and recommendations" -> {
                        TogglePreference("Automatically play the next episode", state.streamAutoPlayNextEpisodeEnabled) { scope.launch { vm.setStreamAutoPlayNextEpisodeEnabled(it) } }
                        TogglePreference("Try other sources for the next episode", state.streamAutoPlayNextEpisodeFallbackEnabled) { scope.launch { vm.setStreamAutoPlayNextEpisodeFallbackEnabled(it) } }
                        TogglePreference("Ask if I’m still watching", state.stillWatchingEnabled) { scope.launch { vm.setStillWatchingEnabled(it) } }
                        ChoiceRow("Episodes before asking", state.stillWatchingEpisodeThreshold, (2..6).toList()) { scope.launch { vm.setStillWatchingEpisodeThreshold(it) } }
                        OptionPreference("Next episode threshold", state.nextEpisodeThresholdMode, NextEpisodeThresholdMode.entries) { scope.launch { vm.setNextEpisodeThresholdMode(it) } }
                        TogglePreference("Recommend another movie", state.postPlayRecommendationsEnabled) { scope.launch { vm.setPostPlayRecommendationsEnabled(it) } }
                        ChoiceRow("Movie watched before recommendations (%)", state.postPlayMovieThresholdPercent, (80..100).toList()) { scope.launch { vm.setPostPlayMovieThresholdPercent(it) } }
                        TogglePreference("Prompt to rate completed movies", state.promptToRateAfterWatching) { scope.launch { vm.setPromptToRateAfterWatching(it) } }
                        TogglePreference("Offer skip intro", state.skipIntroEnabled) { scope.launch { vm.setSkipIntroEnabled(it) } }
                        AutoSkipSegmentType.entries.forEach { segment -> TogglePreference("Automatically skip " + segment.storedValue, segment in state.autoSkipSegmentTypes) { scope.launch { vm.setAutoSkipSegmentTypeEnabled(segment, it) } } }
                        if (state.nextEpisodeThresholdMode == NextEpisodeThresholdMode.PERCENTAGE) ChoiceRow("Episode watched (%)", state.nextEpisodeThresholdPercent.toInt(), (97..100).toList()) { scope.launch { vm.setNextEpisodeThresholdPercent(it.toFloat()) } }
                        else ChoiceRow("Seconds before credits", (state.nextEpisodeThresholdMinutesBeforeEnd * 60).toInt(), (0..210 step 30).toList()) { scope.launch { vm.setNextEpisodeThresholdMinutesBeforeEnd(it / 60f) } }
                    }
                    "Audio and decoder" -> {
                        TogglePreference("Switch player engine on playback errors", state.autoSwitchInternalPlayerOnError) { scope.launch { vm.setAutoSwitchInternalPlayerOnError(it) } }
                        OptionPreference("MPV hardware decoding", state.mpvHardwareDecodeMode, MpvHardwareDecodeMode.entries) { scope.launch { vm.setMpvHardwareDecodeMode(it) } }
                        OptionPreference("Audio output layout", state.audioOutputChannels, AudioOutputChannels.entries) { scope.launch { vm.setAudioOutputChannels(it) } }
                        TogglePreference("Downmix audio", state.downmixEnabled) { scope.launch { vm.setDownmixEnabled(it) } }
                        TogglePreference("Preserve original channel count when possible", state.maintainOriginalAudioOnDownmix) { scope.launch { vm.setMaintainOriginalAudioOnDownmix(it) } }
                        OptionPreference("Downmix LFE contribution", state.downmixLfeLevel, DownmixLfeLevel.entries) { scope.launch { vm.setDownmixLfeLevel(it) } }
                        TogglePreference("Remember audio delay per device", state.rememberAudioDelayPerDevice) { scope.launch { vm.setRememberAudioDelayPerDevice(it) } }
                        TogglePreference("Skip silence", state.skipSilence) { scope.launch { vm.setSkipSilence(it) } }
                        OptionPreference("Night mode", state.nightMode, NightMode.entries) { scope.launch { vm.setNightMode(it) } }
                        TogglePreference("ExoPlayer chapters", state.exoChaptersEnabled) { scope.launch { vm.setExoChaptersEnabled(it) } }
                        TogglePreference("MPV chapters", state.mpvChaptersEnabled) { scope.launch { vm.setMpvChaptersEnabled(it) } }
                        ChoiceRow("Decoder priority (0 system, 1 normal, 2 prefer FFmpeg)", state.decoderPriority, listOf(0, 1, 2)) { scope.launch { vm.setDecoderPriority(it) } }
                        LanguagePreference("Audio language", state.preferredAudioLanguage, audio = true) { scope.launch { vm.setPreferredAudioLanguage(it ?: AudioLanguageOption.DEVICE) } }
                        LanguagePreference("Secondary audio language", state.secondaryPreferredAudioLanguage, audio = true, optional = true) { scope.launch { vm.setSecondaryPreferredAudioLanguage(it) } }
                    }
                    "Subtitles" -> {
                        TogglePreference("Use forced subtitles", state.subtitleStyle.useForcedSubtitles) { scope.launch { vm.setUseForcedSubtitles(it) } }
                        TogglePreference("Only show preferred languages", state.subtitleStyle.showOnlyPreferredLanguages) { scope.launch { vm.setSubtitleShowOnlyPreferredLanguages(it) } }
                        TogglePreference("Remove hearing-impaired cues", state.subtitleStyle.stripSdh) { scope.launch { vm.setSubtitleStripSdh(it) } }
                        OptionPreference("Subtitle organization", state.subtitleOrganizationMode, SubtitleOrganizationMode.entries) { scope.launch { vm.setSubtitleOrganizationMode(it) } }
                        TogglePreference("Use libass for styled subtitles", state.useLibass) { scope.launch { vm.setUseLibass(it) } }
                        OptionPreference("libass renderer", state.libassRenderType, LibassRenderType.entries) { scope.launch { vm.setLibassRenderType(it) } }
                        OptionPreference("libass resolution", state.libassRenderScale, LibassRenderScale.entries) { scope.launch { vm.setLibassRenderScale(it) } }
                        ChoiceRow("Text size (%)", state.subtitleStyle.size, (50..200 step 10).toList()) { scope.launch { vm.setSubtitleSize(it) } }
                        ChoiceRow("Vertical offset (%)", state.subtitleStyle.verticalOffset, (-20..50 step 5).toList()) { scope.launch { vm.setSubtitleVerticalOffset(it) } }
                        TogglePreference("Bold text", state.subtitleStyle.bold) { scope.launch { vm.setSubtitleBold(it) } }
                        TogglePreference("Text outline", state.subtitleStyle.outlineEnabled) { scope.launch { vm.setSubtitleOutlineEnabled(it) } }
                        TogglePreference("Text shadow", state.subtitleStyle.shadowEnabled) { scope.launch { vm.setSubtitleShadowEnabled(it) } }
                        TogglePreference("Use HDR subtitle appearance", state.subtitleStyle.hdrStyleEnabled) { scope.launch { vm.setSubtitleHdrStyleEnabled(it) } }
                        LanguagePreference("Subtitle language", if (state.subtitleStyle.isPreferredLanguageSystemDefault) SubtitleLanguageOption.DEVICE else state.subtitleStyle.preferredLanguage) { scope.launch { vm.setSubtitlePreferredLanguage(it ?: SubtitleLanguageOption.DEVICE) } }
                        LanguagePreference("Secondary subtitle language", state.subtitleStyle.secondaryPreferredLanguage, optional = true) { scope.launch { vm.setSubtitleSecondaryLanguage(it) } }
                        Action("Reset subtitle appearance") { scope.launch { vm.resetSubtitleAppearance() } }
                    }
                    "Network and cache" -> {
                        TogglePreference("Use VOD disk cache", state.vodCacheEnabled) { scope.launch { vm.setVodCacheEnabled(it) } }
                        OptionPreference("Cache size mode", state.vodCacheSizeMode, VodCacheSizeMode.entries) { scope.launch { vm.setVodCacheSizeMode(it) } }
                        ChoiceRow("Cache size (MB)", state.vodCacheSizeMb, listOf(100, 250, 500, 1000, 2000, 4000, 8000)) { scope.launch { vm.setVodCacheSizeMb(it) } }
                        TogglePreference("Use ReSeq buffer engine", state.bufferEngineEnabled) { scope.launch { vm.setBufferEngineEnabled(it) } }
                        TogglePreference("Manage buffer within memory budget", state.bufferBudgetManaged) { scope.launch { vm.setBufferBudgetManaged(it) } }
                        TogglePreference("Parallel network engine", state.parallelNetworkEnabled) { scope.launch { vm.setParallelNetworkEnabled(it) } }
                        TogglePreference("Use parallel connections", state.useParallelConnections) { scope.launch { vm.setUseParallelConnections(it) } }
                        ChoiceRow("Parallel connections", state.parallelConnectionCount, listOf(2, 3, 4, 6, 8)) { scope.launch { vm.setParallelConnectionCount(it) } }

                    }
                }
            }
        }
    }
}
