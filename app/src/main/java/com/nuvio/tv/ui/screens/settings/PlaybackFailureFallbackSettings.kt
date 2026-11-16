package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.PlayerPreference
import com.nuvio.tv.data.local.PlayerSettings

@Composable
internal fun PlaybackFailureFallbackSettings(settings: PlayerSettings, onUpdate: PlaybackSettingsUpdate) {
    SettingsToggleRow(
        title = stringResource(R.string.autoplay_fallback_next_source),
        subtitle = stringResource(R.string.autoplay_fallback_next_source_sub),
        checked = settings.streamAutoPlayFallbackOnFailure,
        onToggle = { onUpdate { setStreamAutoPlayFallbackOnFailure(!settings.streamAutoPlayFallbackOnFailure) } },
        enabled = settings.playerPreference != PlayerPreference.EXTERNAL
    )
    if (settings.streamAutoPlayFallbackOnFailure) {
        SliderSettingsItem(
            title = stringResource(R.string.autoplay_fallback_max_sources),
            subtitle = stringResource(R.string.autoplay_fallback_max_sources_sub),
            value = settings.streamAutoPlayFallbackMaxSources,
            valueText = settings.streamAutoPlayFallbackMaxSources.toString(),
            minValue = PlayerSettings.MIN_SOURCE_FALLBACK_MAX_SOURCES,
            maxValue = PlayerSettings.MAX_SOURCE_FALLBACK_MAX_SOURCES,
            step = 1,
            enabled = settings.playerPreference != PlayerPreference.EXTERNAL,
            onValueChange = { onUpdate { setStreamAutoPlayFallbackMaxSources(it) } }
        )
        SliderSettingsItem(
            title = stringResource(R.string.autoplay_fallback_startup_timeout),
            subtitle = stringResource(R.string.autoplay_fallback_startup_timeout_sub),
            value = settings.streamAutoPlayFallbackStartupTimeoutSeconds,
            valueText = stringResource(R.string.autoplay_fallback_startup_timeout_value, settings.streamAutoPlayFallbackStartupTimeoutSeconds),
            minValue = PlayerSettings.MIN_SOURCE_STARTUP_TIMEOUT_SECONDS,
            maxValue = PlayerSettings.MAX_SOURCE_STARTUP_TIMEOUT_SECONDS,
            step = PlayerSettings.SOURCE_STARTUP_TIMEOUT_SECONDS_STEP,
            enabled = settings.playerPreference != PlayerPreference.EXTERNAL,
            onValueChange = { onUpdate { setStreamAutoPlayFallbackStartupTimeoutSeconds(it) } }
        )
    }
}
