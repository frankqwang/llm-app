/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * VlogCopilot-scoped theme wrapper. Layered ON TOP of the gallery-wide
 * GalleryTheme: we keep the global Material3 typography (so font weights
 * stay coherent with the rest of the app) but override the colorScheme,
 * shape system, and provide VlogCopilot-specific extended tokens (spacing,
 * motion, extra colors) via CompositionLocal.
 *
 * The aesthetic target is iOS Photos / Settings:
 *   - Inset-grouped page backgrounds (gray) with white surface cells
 *   - System blue accent
 *   - Continuous-corner shapes
 *   - Translucent separators instead of heavy borders
 *   - Hairline weights, generous whitespace
 */
package com.vlogcopilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val vlogPilotLightScheme =
  lightColorScheme(
    primary = VlogCopilotPalette.accentLight,
    onPrimary = VlogCopilotPalette.labelDark,
    primaryContainer = VlogCopilotPalette.accentTintLight,
    onPrimaryContainer = VlogCopilotPalette.accentLight,
    secondary = VlogCopilotPalette.gray1Light,
    onSecondary = VlogCopilotPalette.labelDark,
    secondaryContainer = VlogCopilotPalette.gray6Light,
    onSecondaryContainer = VlogCopilotPalette.labelLight,
    tertiary = VlogCopilotPalette.systemPurpleLight,
    background = VlogCopilotPalette.groupedBgLight,
    onBackground = VlogCopilotPalette.labelLight,
    surface = VlogCopilotPalette.groupedSurfaceLight,
    onSurface = VlogCopilotPalette.labelLight,
    surfaceVariant = VlogCopilotPalette.gray6Light,
    onSurfaceVariant = VlogCopilotPalette.secondaryLabelLight,
    surfaceContainerLowest = VlogCopilotPalette.groupedSurfaceLight,
    surfaceContainerLow = VlogCopilotPalette.groupedSurfaceLight,
    surfaceContainer = VlogCopilotPalette.gray6Light,
    surfaceContainerHigh = VlogCopilotPalette.gray5Light,
    surfaceContainerHighest = VlogCopilotPalette.gray4Light,
    outline = VlogCopilotPalette.opaqueSeparatorLight,
    outlineVariant = VlogCopilotPalette.gray5Light,
  )

private val vlogPilotDarkScheme =
  darkColorScheme(
    primary = VlogCopilotPalette.accentDark,
    onPrimary = VlogCopilotPalette.labelDark,
    primaryContainer = VlogCopilotPalette.accentTintDark,
    onPrimaryContainer = VlogCopilotPalette.accentDark,
    secondary = VlogCopilotPalette.gray1Dark,
    onSecondary = VlogCopilotPalette.labelDark,
    secondaryContainer = VlogCopilotPalette.gray5Dark,
    onSecondaryContainer = VlogCopilotPalette.labelDark,
    tertiary = VlogCopilotPalette.systemPurpleDark,
    background = VlogCopilotPalette.groupedBgDark,
    onBackground = VlogCopilotPalette.labelDark,
    surface = VlogCopilotPalette.groupedSurfaceDark,
    onSurface = VlogCopilotPalette.labelDark,
    surfaceVariant = VlogCopilotPalette.gray5Dark,
    onSurfaceVariant = VlogCopilotPalette.secondaryLabelDark,
    surfaceContainerLowest = VlogCopilotPalette.groupedBgDark,
    surfaceContainerLow = VlogCopilotPalette.groupedSurfaceDark,
    surfaceContainer = VlogCopilotPalette.groupedSurfaceRaisedDark,
    surfaceContainerHigh = VlogCopilotPalette.gray4Dark,
    surfaceContainerHighest = VlogCopilotPalette.gray3Dark,
    outline = VlogCopilotPalette.opaqueSeparatorDark,
    outlineVariant = VlogCopilotPalette.gray4Dark,
  )

private val extraColorsLight =
  VlogCopilotExtraColors(
    groupedBackground = VlogCopilotPalette.groupedBgLight,
    groupedSurface = VlogCopilotPalette.groupedSurfaceLight,
    groupedSurfaceRaised = VlogCopilotPalette.groupedSurfaceLight,
    secondaryLabel = VlogCopilotPalette.secondaryLabelLight,
    tertiaryLabel = VlogCopilotPalette.tertiaryLabelLight,
    separator = VlogCopilotPalette.separatorLight,
    opaqueSeparator = VlogCopilotPalette.opaqueSeparatorLight,
    accent = VlogCopilotPalette.accentLight,
    accentTint = VlogCopilotPalette.accentTintLight,
    systemRed = VlogCopilotPalette.systemRedLight,
    systemGreen = VlogCopilotPalette.systemGreenLight,
    systemOrange = VlogCopilotPalette.systemOrangeLight,
    systemPurple = VlogCopilotPalette.systemPurpleLight,
    systemPink = VlogCopilotPalette.systemPinkLight,
    isDark = false,
  )

private val extraColorsDark =
  VlogCopilotExtraColors(
    groupedBackground = VlogCopilotPalette.groupedBgDark,
    groupedSurface = VlogCopilotPalette.groupedSurfaceDark,
    groupedSurfaceRaised = VlogCopilotPalette.groupedSurfaceRaisedDark,
    secondaryLabel = VlogCopilotPalette.secondaryLabelDark,
    tertiaryLabel = VlogCopilotPalette.tertiaryLabelDark,
    separator = VlogCopilotPalette.separatorDark,
    opaqueSeparator = VlogCopilotPalette.opaqueSeparatorDark,
    accent = VlogCopilotPalette.accentDark,
    accentTint = VlogCopilotPalette.accentTintDark,
    systemRed = VlogCopilotPalette.systemRedDark,
    systemGreen = VlogCopilotPalette.systemGreenDark,
    systemOrange = VlogCopilotPalette.systemOrangeDark,
    systemPurple = VlogCopilotPalette.systemPurpleDark,
    systemPink = VlogCopilotPalette.systemPinkDark,
    isDark = true,
  )

@Composable
fun VlogCopilotTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) vlogPilotDarkScheme else vlogPilotLightScheme
  val extra = if (darkTheme) extraColorsDark else extraColorsLight
  CompositionLocalProvider(
    LocalVlogCopilotExtraColors provides extra,
    LocalVlogCopilotSpacing provides VlogCopilotSpacing(),
    LocalVlogCopilotMotion provides VlogCopilotMotion(),
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = MaterialTheme.typography,
      shapes = VlogCopilotShapes.materialShapes,
      content = content,
    )
  }
}
