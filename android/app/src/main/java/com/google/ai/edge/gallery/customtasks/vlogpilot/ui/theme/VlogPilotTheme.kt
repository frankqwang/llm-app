/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * VlogPilot-scoped theme wrapper. Layered ON TOP of the gallery-wide
 * GalleryTheme: we keep the global Material3 typography (so font weights
 * stay coherent with the rest of the app) but override the colorScheme,
 * shape system, and provide VlogPilot-specific extended tokens (spacing,
 * motion, extra colors) via CompositionLocal.
 *
 * The aesthetic target is iOS Photos / Settings:
 *   - Inset-grouped page backgrounds (gray) with white surface cells
 *   - System blue accent
 *   - Continuous-corner shapes
 *   - Translucent separators instead of heavy borders
 *   - Hairline weights, generous whitespace
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val vlogPilotLightScheme =
  lightColorScheme(
    primary = VlogPilotPalette.accentLight,
    onPrimary = VlogPilotPalette.labelDark,
    primaryContainer = VlogPilotPalette.accentTintLight,
    onPrimaryContainer = VlogPilotPalette.accentLight,
    secondary = VlogPilotPalette.gray1Light,
    onSecondary = VlogPilotPalette.labelDark,
    secondaryContainer = VlogPilotPalette.gray6Light,
    onSecondaryContainer = VlogPilotPalette.labelLight,
    tertiary = VlogPilotPalette.systemPurpleLight,
    background = VlogPilotPalette.groupedBgLight,
    onBackground = VlogPilotPalette.labelLight,
    surface = VlogPilotPalette.groupedSurfaceLight,
    onSurface = VlogPilotPalette.labelLight,
    surfaceVariant = VlogPilotPalette.gray6Light,
    onSurfaceVariant = VlogPilotPalette.secondaryLabelLight,
    surfaceContainerLowest = VlogPilotPalette.groupedSurfaceLight,
    surfaceContainerLow = VlogPilotPalette.groupedSurfaceLight,
    surfaceContainer = VlogPilotPalette.gray6Light,
    surfaceContainerHigh = VlogPilotPalette.gray5Light,
    surfaceContainerHighest = VlogPilotPalette.gray4Light,
    outline = VlogPilotPalette.opaqueSeparatorLight,
    outlineVariant = VlogPilotPalette.gray5Light,
  )

private val vlogPilotDarkScheme =
  darkColorScheme(
    primary = VlogPilotPalette.accentDark,
    onPrimary = VlogPilotPalette.labelDark,
    primaryContainer = VlogPilotPalette.accentTintDark,
    onPrimaryContainer = VlogPilotPalette.accentDark,
    secondary = VlogPilotPalette.gray1Dark,
    onSecondary = VlogPilotPalette.labelDark,
    secondaryContainer = VlogPilotPalette.gray5Dark,
    onSecondaryContainer = VlogPilotPalette.labelDark,
    tertiary = VlogPilotPalette.systemPurpleDark,
    background = VlogPilotPalette.groupedBgDark,
    onBackground = VlogPilotPalette.labelDark,
    surface = VlogPilotPalette.groupedSurfaceDark,
    onSurface = VlogPilotPalette.labelDark,
    surfaceVariant = VlogPilotPalette.gray5Dark,
    onSurfaceVariant = VlogPilotPalette.secondaryLabelDark,
    surfaceContainerLowest = VlogPilotPalette.groupedBgDark,
    surfaceContainerLow = VlogPilotPalette.groupedSurfaceDark,
    surfaceContainer = VlogPilotPalette.groupedSurfaceRaisedDark,
    surfaceContainerHigh = VlogPilotPalette.gray4Dark,
    surfaceContainerHighest = VlogPilotPalette.gray3Dark,
    outline = VlogPilotPalette.opaqueSeparatorDark,
    outlineVariant = VlogPilotPalette.gray4Dark,
  )

private val extraColorsLight =
  VlogPilotExtraColors(
    groupedBackground = VlogPilotPalette.groupedBgLight,
    groupedSurface = VlogPilotPalette.groupedSurfaceLight,
    groupedSurfaceRaised = VlogPilotPalette.groupedSurfaceLight,
    secondaryLabel = VlogPilotPalette.secondaryLabelLight,
    tertiaryLabel = VlogPilotPalette.tertiaryLabelLight,
    separator = VlogPilotPalette.separatorLight,
    opaqueSeparator = VlogPilotPalette.opaqueSeparatorLight,
    accent = VlogPilotPalette.accentLight,
    accentTint = VlogPilotPalette.accentTintLight,
    systemRed = VlogPilotPalette.systemRedLight,
    systemGreen = VlogPilotPalette.systemGreenLight,
    systemOrange = VlogPilotPalette.systemOrangeLight,
    systemPurple = VlogPilotPalette.systemPurpleLight,
    systemPink = VlogPilotPalette.systemPinkLight,
    isDark = false,
  )

private val extraColorsDark =
  VlogPilotExtraColors(
    groupedBackground = VlogPilotPalette.groupedBgDark,
    groupedSurface = VlogPilotPalette.groupedSurfaceDark,
    groupedSurfaceRaised = VlogPilotPalette.groupedSurfaceRaisedDark,
    secondaryLabel = VlogPilotPalette.secondaryLabelDark,
    tertiaryLabel = VlogPilotPalette.tertiaryLabelDark,
    separator = VlogPilotPalette.separatorDark,
    opaqueSeparator = VlogPilotPalette.opaqueSeparatorDark,
    accent = VlogPilotPalette.accentDark,
    accentTint = VlogPilotPalette.accentTintDark,
    systemRed = VlogPilotPalette.systemRedDark,
    systemGreen = VlogPilotPalette.systemGreenDark,
    systemOrange = VlogPilotPalette.systemOrangeDark,
    systemPurple = VlogPilotPalette.systemPurpleDark,
    systemPink = VlogPilotPalette.systemPinkDark,
    isDark = true,
  )

@Composable
fun VlogPilotTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) vlogPilotDarkScheme else vlogPilotLightScheme
  val extra = if (darkTheme) extraColorsDark else extraColorsLight
  CompositionLocalProvider(
    LocalVlogPilotExtraColors provides extra,
    LocalVlogPilotSpacing provides VlogPilotSpacing(),
    LocalVlogPilotMotion provides VlogPilotMotion(),
  ) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = MaterialTheme.typography,
      shapes = VlogPilotShapes.materialShapes,
      content = content,
    )
  }
}
