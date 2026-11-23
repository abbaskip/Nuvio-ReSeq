package com.nuvio.tv.mobile

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.lerp
import com.nuvio.tv.ui.theme.NuvioColorScheme

internal fun mobileColorScheme(c: NuvioColorScheme) = darkColorScheme(
    primary = c.Secondary, onPrimary = c.OnSecondary, primaryContainer = c.TintedControl, onPrimaryContainer = c.TextPrimary,
    secondary = c.MutedAccent, onSecondary = c.TextInverse, secondaryContainer = c.TintedControl, onSecondaryContainer = c.TextPrimary,
    tertiary = c.MutedAccent, onTertiary = c.TextInverse, tertiaryContainer = c.TintedControl, onTertiaryContainer = c.TextPrimary,
    background = c.TintedBackground, onBackground = c.TextPrimary,
    surface = c.TintedPanel, onSurface = c.TextPrimary, surfaceVariant = c.TintedControl, onSurfaceVariant = c.TextSecondary,
    surfaceDim = c.TintedBackground, surfaceBright = c.TintedControl, surfaceTint = c.Secondary,
    surfaceContainerLowest = c.TintedBackground, surfaceContainerLow = c.TintedPanel, surfaceContainer = c.TintedPanel,
    surfaceContainerHigh = lerp(c.TintedPanel, c.TintedControl, .5f), surfaceContainerHighest = c.TintedControl,
    outline = lerp(c.TintedControl, c.TextSecondary, .5f), outlineVariant = lerp(c.TintedPanel, c.TextSecondary, .3f),
    inverseSurface = c.PaleFocus, inverseOnSurface = c.TextInverse, inversePrimary = c.SecondaryVariant,
    error = c.Error
)
