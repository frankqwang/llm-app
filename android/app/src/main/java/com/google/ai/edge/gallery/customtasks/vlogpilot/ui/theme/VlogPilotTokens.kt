/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Design tokens for VlogPilot — colors, shapes, spacing, motion. Borrows
 * heavily from Apple's HIG palette (iOS system grays, system blue, content
 * elevation through translucency rather than shadows). Kept inside the
 * vlogpilot package so the rest of the gallery app keeps its existing
 * Material brand untouched.
 *
 * Usage:
 *   VlogPilotTheme {
 *     // your composables can read MaterialTheme.colorScheme as usual,
 *     // or VlogPilotTokens.spacing.lg etc. for the extended tokens
 *   }
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------- Color palette ----------------
//
// Apple-aligned: a near-white "grouped" background, layered with brighter
// surface cells ("inset grouped"). Dark mode flips to true black with
// raised surface tiers — matches iOS Photos / Settings.

object VlogPilotPalette {
  // System blue, Apple's default accent. Slightly brighter for dark.
  val accentLight = Color(0xFF007AFF)
  val accentDark = Color(0xFF0A84FF)

  // System grays — Apple specifies 6 levels for light, 6 for dark.
  val gray1Light = Color(0xFF8E8E93)
  val gray2Light = Color(0xFFAEAEB2)
  val gray3Light = Color(0xFFC7C7CC)
  val gray4Light = Color(0xFFD1D1D6)
  val gray5Light = Color(0xFFE5E5EA)
  val gray6Light = Color(0xFFF2F2F7)

  val gray1Dark = Color(0xFF8E8E93)
  val gray2Dark = Color(0xFF636366)
  val gray3Dark = Color(0xFF48484A)
  val gray4Dark = Color(0xFF3A3A3C)
  val gray5Dark = Color(0xFF2C2C2E)
  val gray6Dark = Color(0xFF1C1C1E)

  // Backgrounds
  val groupedBgLight = Color(0xFFF2F2F7)         // page background
  val groupedSurfaceLight = Color(0xFFFFFFFF)     // cell / card
  val groupedBgDark = Color(0xFF000000)
  val groupedSurfaceDark = Color(0xFF1C1C1E)
  val groupedSurfaceRaisedDark = Color(0xFF2C2C2E)

  // Text
  val labelLight = Color(0xFF000000)
  val secondaryLabelLight = Color(0x993C3C43)     // 60% black
  val tertiaryLabelLight = Color(0x4C3C3C43)      // 30% black
  val labelDark = Color(0xFFFFFFFF)
  val secondaryLabelDark = Color(0x99EBEBF5)
  val tertiaryLabelDark = Color(0x4CEBEBF5)

  // Separators (hairline lines between cells)
  val separatorLight = Color(0x4C3C3C43)          // 30% black
  val separatorDark = Color(0x4CEBEBF5)
  val opaqueSeparatorLight = Color(0xFFC6C6C8)
  val opaqueSeparatorDark = Color(0xFF38383A)

  // Tints used in tinted buttons (15% accent on light bg, 24% on dark)
  val accentTintLight = Color(0x26007AFF)         // 15%
  val accentTintDark = Color(0x3D0A84FF)          // 24%

  // Status / semantic
  val systemRedLight = Color(0xFFFF3B30)
  val systemRedDark = Color(0xFFFF453A)
  val systemGreenLight = Color(0xFF34C759)
  val systemGreenDark = Color(0xFF30D158)
  val systemOrangeLight = Color(0xFFFF9500)
  val systemOrangeDark = Color(0xFFFF9F0A)
  val systemPurpleLight = Color(0xFFAF52DE)
  val systemPurpleDark = Color(0xFFBF5AF2)
  val systemPinkLight = Color(0xFFFF2D55)
  val systemPinkDark = Color(0xFFFF375F)
}

// ---------------- Shapes ----------------
//
// Apple uses continuous corners (squircles). Compose's RoundedCornerShape is
// circular, but at typical radii (>=12dp) the visual difference is small.

object VlogPilotShapes {
  val xs = RoundedCornerShape(6.dp)        // chip
  val sm = RoundedCornerShape(10.dp)       // small button, badge
  val md = RoundedCornerShape(14.dp)       // standard button
  val lg = RoundedCornerShape(20.dp)       // card, panel
  val xl = RoundedCornerShape(28.dp)       // sheet, hero
  val pill = RoundedCornerShape(50)        // capsule

  val materialShapes =
    Shapes(
      extraSmall = xs,
      small = sm,
      medium = md,
      large = lg,
      extraLarge = xl,
    )
}

// ---------------- Spacing scale ----------------

@Immutable
data class VlogPilotSpacing(
  val xxs: Dp = 2.dp,
  val xs: Dp = 4.dp,
  val sm: Dp = 8.dp,
  val md: Dp = 12.dp,
  val lg: Dp = 16.dp,
  val xl: Dp = 24.dp,
  val xxl: Dp = 32.dp,
  val xxxl: Dp = 40.dp,
  // Standard inset for inset-grouped lists / page edges
  val pageInset: Dp = 16.dp,
)

internal val LocalVlogPilotSpacing = staticCompositionLocalOf { VlogPilotSpacing() }

// ---------------- Motion ----------------
//
// Apple's standard animations are spring-based; these constants pin the feel.

@Immutable
data class VlogPilotMotion(
  val springSnappy: SpringSpec = SpringSpec(stiffness = 380f, dampingRatio = 0.85f),
  val springSmooth: SpringSpec = SpringSpec(stiffness = 240f, dampingRatio = 0.92f),
  val tweenFast: TweenSpec = TweenSpec(durationMillis = 180),
  val tweenStandard: TweenSpec = TweenSpec(durationMillis = 280),
  val tweenSlow: TweenSpec = TweenSpec(durationMillis = 420),
)

@Immutable
data class SpringSpec(val stiffness: Float, val dampingRatio: Float) {
  fun <T> asAnimationSpec() = spring<T>(dampingRatio = dampingRatio, stiffness = stiffness)
}

@Immutable
data class TweenSpec(val durationMillis: Int) {
  fun <T> asAnimationSpec() = tween<T>(durationMillis = durationMillis, easing = AppleEasing)
}

// Apple's "ease-out-expo"-ish curve, matches the system spring closely.
val AppleEasing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)

internal val LocalVlogPilotMotion = staticCompositionLocalOf { VlogPilotMotion() }

// ---------------- Extended palette (for tinted buttons / chips / separators) ----------------

@Immutable
data class VlogPilotExtraColors(
  val groupedBackground: Color,
  val groupedSurface: Color,
  val groupedSurfaceRaised: Color,
  val secondaryLabel: Color,
  val tertiaryLabel: Color,
  val separator: Color,
  val opaqueSeparator: Color,
  val accent: Color,
  val accentTint: Color,
  val systemRed: Color,
  val systemGreen: Color,
  val systemOrange: Color,
  val systemPurple: Color,
  val systemPink: Color,
  val isDark: Boolean,
)

internal val LocalVlogPilotExtraColors = staticCompositionLocalOf {
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
}

// ---------------- Theme accessors ----------------

object VlogPilotTokens {
  val spacing: VlogPilotSpacing
    @Composable @ReadOnlyComposable get() = LocalVlogPilotSpacing.current

  val motion: VlogPilotMotion
    @Composable @ReadOnlyComposable get() = LocalVlogPilotMotion.current

  val colors: VlogPilotExtraColors
    @Composable @ReadOnlyComposable get() = LocalVlogPilotExtraColors.current

  val shapes get() = VlogPilotShapes
}
