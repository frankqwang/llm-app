/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Apple-flavored Compose primitives for the VlogPilot UI. These mirror common
 * iOS Photos / Settings patterns — inset-grouped sections, tinted buttons,
 * capsule chips, large titles, hairline separators — using Material3 under
 * the hood so Android conventions (ripples, accessibility, focus order)
 * still apply.
 *
 * All layout is driven by VlogPilotTokens (theme/) so light/dark theming
 * comes free.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens

// ---------------------------------------------------------------------------
// Layout helpers
// ---------------------------------------------------------------------------

/**
 * iOS-style large title block. Sits above the page content (not in TopAppBar)
 * so it can scroll away naturally with the LazyColumn.
 */
@Composable
fun LargeTitleHeader(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  trailing: (@Composable () -> Unit)? = null,
) {
  val tokens = VlogPilotTokens
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = tokens.spacing.pageInset, vertical = tokens.spacing.md),
    verticalAlignment = Alignment.Bottom,
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.size(2.dp))
        Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = tokens.colors.secondaryLabel,
        )
      }
    }
    trailing?.invoke()
  }
}

/**
 * "Inset grouped" section title: small uppercase label drawn above a section,
 * matches iOS Settings / Photos.
 */
@Composable
fun InsetSectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
) {
  val tokens = VlogPilotTokens
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(
        start = tokens.spacing.pageInset + tokens.spacing.xs,
        end = tokens.spacing.pageInset + tokens.spacing.xs,
        top = tokens.spacing.lg,
        bottom = tokens.spacing.sm,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      title.uppercase(),
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.labelSmall,
      color = tokens.colors.secondaryLabel,
      fontWeight = FontWeight.Medium,
      letterSpacing = 0.6.sp,
    )
    trailing?.invoke()
  }
}

/**
 * Container for a group of cells. Uses a single rounded surface; children
 * render hairline dividers between themselves via [HairlineDivider].
 */
@Composable
fun InsetGroupedSurface(
  modifier: Modifier = Modifier,
  shape: Shape = VlogPilotTokens.shapes.lg,
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = VlogPilotTokens.spacing.pageInset),
    color = MaterialTheme.colorScheme.surface,
    shape = shape,
    tonalElevation = 0.dp,
  ) {
    Column(content = content)
  }
}

/**
 * Translucent floating card. Used for hero areas (the rendered video preview)
 * where Apple Photos uses a slightly elevated cell. Light-elevation,
 * continuous corner radius, no harsh borders.
 */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = VlogPilotTokens.shapes.lg,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  content: @Composable ColumnScope.() -> Unit,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    shape = shape,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(contentPadding), content = content)
  }
}

/** 0.5dp / hairline weight separator drawn at translucent gray. */
@Composable
fun HairlineDivider(
  modifier: Modifier = Modifier,
  startInset: Dp = VlogPilotTokens.spacing.lg,
  endInset: Dp = 0.dp,
) {
  Box(
    modifier = modifier
      .fillMaxWidth()
      .padding(start = startInset, end = endInset),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 0.5.dp, max = 0.5.dp)
        .background(VlogPilotTokens.colors.opaqueSeparator),
    )
  }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

/**
 * The "first / hero" action on a screen. Filled with the accent, white text,
 * 50dp tall capsule. Use sparingly — at most one per visible group.
 */
@Composable
fun PrimaryActionButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
) {
  PressableSurface(
    onClick = onClick,
    enabled = enabled,
    shape = VlogPilotTokens.shapes.pill,
    color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    modifier = modifier.heightIn(min = 50.dp),
  ) {
    Row(
      modifier = Modifier
        .padding(horizontal = 22.dp)
        .heightIn(min = 50.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
      }
      Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

/**
 * Tinted button — accent text on a faint accent background. The most common
 * action style on iOS (Photos: "Add to Album", Settings: "Sign In with…").
 */
@Composable
fun TintedActionButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  enabled: Boolean = true,
  tint: Color? = null,
) {
  val resolvedTint = tint ?: VlogPilotTokens.colors.accent
  val bg = resolvedTint.copy(alpha = if (VlogPilotTokens.colors.isDark) 0.24f else 0.14f)
  PressableSurface(
    onClick = onClick,
    enabled = enabled,
    shape = VlogPilotTokens.shapes.md,
    color = if (enabled) bg else bg.copy(alpha = 0.4f),
    modifier = modifier.heightIn(min = 44.dp),
  ) {
    Row(
      modifier = Modifier
        .padding(horizontal = 16.dp)
        .heightIn(min = 44.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center,
    ) {
      if (icon != null) {
        Icon(
          icon,
          contentDescription = null,
          tint = if (enabled) resolvedTint else resolvedTint.copy(alpha = 0.5f),
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
      }
      Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (enabled) resolvedTint else resolvedTint.copy(alpha = 0.5f),
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

/**
 * Plain text button — borderless, used for tertiary actions ("Cancel", "Skip").
 * Apple's default action style in toolbars.
 */
@Composable
fun PlainTextButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  color: Color? = null,
  enabled: Boolean = true,
) {
  val resolvedColor = color ?: VlogPilotTokens.colors.accent
  PressableSurface(
    onClick = onClick,
    enabled = enabled,
    shape = VlogPilotTokens.shapes.pill,
    color = Color.Transparent,
    modifier = modifier.heightIn(min = 36.dp),
  ) {
    Box(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = if (enabled) resolvedColor else resolvedColor.copy(alpha = 0.5f),
        fontWeight = FontWeight.Medium,
      )
    }
  }
}

/**
 * Capsule chip used in filter rows. Selected state uses accent tint,
 * unselected stays gray.
 */
@Composable
fun CapsuleChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  leadingIcon: ImageVector? = null,
) {
  val tokens = VlogPilotTokens
  val bg = if (selected) tokens.colors.accentTint
           else if (tokens.colors.isDark) tokens.colors.groupedSurfaceRaised
           else MaterialTheme.colorScheme.surface
  val fg = if (selected) tokens.colors.accent else MaterialTheme.colorScheme.onSurface
  PressableSurface(
    onClick = onClick,
    enabled = true,
    shape = tokens.shapes.pill,
    color = bg,
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (leadingIcon != null) {
        Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
      }
      Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = fg,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
      )
    }
  }
}

/** Subtle tap-press scale-down. Wraps a child surface that handles its own click. */
@Composable
fun PressableSurface(
  onClick: () -> Unit,
  enabled: Boolean,
  shape: Shape,
  color: Color,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val interactionSource = remember { MutableInteractionSource() }
  val pressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (pressed) 0.97f else 1f,
    animationSpec = spring(stiffness = 1100f, dampingRatio = 0.7f),
    label = "press-scale",
  )
  Surface(
    modifier = modifier
      .scale(scale)
      .clickable(
        enabled = enabled,
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
      ),
    color = color,
    shape = shape,
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Box { content() }
  }
}

// ---------------------------------------------------------------------------
// List cells
// ---------------------------------------------------------------------------

/**
 * Inset-grouped list cell — left icon (optional), title + optional subtitle,
 * trailing accessory. Tappable when [onClick] is non-null. Hairline dividers
 * are drawn by the parent [InsetGroupedSurface] via [HairlineDivider].
 */
@Composable
fun InsetGroupedCell(
  title: String,
  modifier: Modifier = Modifier,
  subtitle: String? = null,
  leadingIcon: ImageVector? = null,
  leadingTint: Color? = null,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  showChevron: Boolean = onClick != null,
) {
  val rowMod = if (onClick != null) {
    Modifier.clickable(onClick = onClick)
  } else Modifier
  Row(
    modifier = modifier
      .fillMaxWidth()
      .then(rowMod)
      .padding(horizontal = VlogPilotTokens.spacing.lg, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (leadingIcon != null) {
      Icon(
        leadingIcon,
        contentDescription = null,
        tint = leadingTint ?: VlogPilotTokens.colors.accent,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(VlogPilotTokens.spacing.md))
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (!subtitle.isNullOrBlank()) {
        Spacer(Modifier.size(2.dp))
        Text(
          subtitle,
          style = MaterialTheme.typography.bodyMedium,
          color = VlogPilotTokens.colors.secondaryLabel,
          maxLines = 2,
        )
      }
    }
    if (trailing != null) {
      Spacer(Modifier.width(VlogPilotTokens.spacing.sm))
      trailing()
    }
    if (showChevron) {
      Spacer(Modifier.width(VlogPilotTokens.spacing.xs))
      Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = VlogPilotTokens.colors.tertiaryLabel,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Animated containers
// ---------------------------------------------------------------------------

/**
 * Spring-driven expand/collapse for cards. Exposes [content] only when
 * [expanded] = true. Uses the standard VlogPilot motion curve.
 */
@Composable
fun AnimatedExpand(
  expanded: Boolean,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  AnimatedVisibility(
    visible = expanded,
    enter = expandVertically(
      animationSpec = VlogPilotTokens.motion.springSmooth.asAnimationSpec(),
    ) + fadeIn(animationSpec = VlogPilotTokens.motion.tweenStandard.asAnimationSpec()),
    exit = shrinkVertically(
      animationSpec = VlogPilotTokens.motion.springSmooth.asAnimationSpec(),
    ) + fadeOut(animationSpec = VlogPilotTokens.motion.tweenFast.asAnimationSpec()),
    modifier = modifier,
  ) {
    Column(content = content)
  }
}

/** Reflects the rotation of an arrow / chevron when its parent expands. */
@Composable
fun chevronRotation(expanded: Boolean): Float {
  val r by animateFloatAsState(
    targetValue = if (expanded) 90f else 0f,
    animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
    label = "chevron",
  )
  return r
}
