package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource

object StreamAutoPlayPolicy {
    data class NextEpisodeSelection(val settings: PlayerSettings, val automatic: Boolean, val bingeGroupOnly: Boolean)

    /** The same one-time manual-mode fallback is used by ordinary episodes and mixes. */
    fun nextEpisodeSelection(settings: PlayerSettings): NextEpisodeSelection {
        val manualFallback = settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
            (settings.streamAutoPlayNextEpisodeEnabled || settings.streamAutoPlayPreferBingeGroupForNextEpisode)
        val bingeGroupOnly = manualFallback &&
            (!settings.streamAutoPlayNextEpisodeEnabled || !settings.streamAutoPlayNextEpisodeFallbackEnabled) &&
            settings.streamAutoPlayPreferBingeGroupForNextEpisode
        val effective = if (manualFallback) settings.copy(
            streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM,
            streamAutoPlaySource = StreamAutoPlaySource.ALL_SOURCES,
            streamAutoPlaySelectedAddons = emptySet(),
            streamAutoPlaySelectedPlugins = emptySet(),
            streamAutoPlayRegex = ""
        ) else settings
        return NextEpisodeSelection(effective, effective.streamAutoPlayMode != StreamAutoPlayMode.MANUAL, bingeGroupOnly)
    }

    fun isEffectivelyEnabled(playerSettings: PlayerSettings): Boolean {
        if (playerSettings.streamReuseLastLinkEnabled) return true
        if (TieredStreamAutoPlay.isEnabled(playerSettings)) return true
        val addons = playerSettings.streamAutoPlayAddonsEnabled
            ?: (playerSettings.streamAutoPlaySource != com.nuvio.tv.data.local.StreamAutoPlaySource.ENABLED_PLUGINS_ONLY)
        val plugins = com.nuvio.tv.core.build.AppFeaturePolicy.pluginsEnabled && (playerSettings.streamAutoPlayPluginsEnabled
            ?: (playerSettings.streamAutoPlaySource != com.nuvio.tv.data.local.StreamAutoPlaySource.INSTALLED_ADDONS_ONLY))
        if (!addons && !plugins && !playerSettings.streamAutoPlayPlaylistsEnabled) return false
        if (playerSettings.streamAutoPlayReuseBingeGroup &&
            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return true

        return when (playerSettings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
