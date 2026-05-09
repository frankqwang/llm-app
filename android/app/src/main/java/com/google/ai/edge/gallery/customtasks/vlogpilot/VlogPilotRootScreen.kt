/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * App-launch landing for VlogPilot. Wraps VlogPilotScreen in a Scaffold with
 * a minimal top app bar (title + Apps action) and an iOS-style bottom
 * NavigationBar with 4 tabs (Works / Chat / Album / Settings). Tab state is
 * hoisted here so VlogPilotScreen takes selectedTab as a parameter and
 * doesn't have to manage its own internal tab routing.
 *
 * Ships an iOS-style aesthetic via VlogPilotTheme — inset-grouped backgrounds,
 * system blue accent, hairline separators, generous whitespace.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTokens
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTheme
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlogPilotRootScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenGallery: () -> Unit,
) {
  VlogPilotTheme {
    var selectedTab by remember { mutableStateOf(VlogPilotTab.Works) }
    // Hoisted here so the chat VM survives tab switches — otherwise
    // PipelineEventBus events that fire while the user is on a non-Chat
    // tab would be dropped (the bus uses replay=1, so a freshly recreated
    // VM only sees the latest event, not the ones in between).
    val context = androidx.compose.ui.platform.LocalContext.current
    val chatViewModel = remember {
      com.google.ai.edge.gallery.customtasks.vlogpilot.chat.ChatViewModel(context.applicationContext)
    }
    androidx.compose.runtime.DisposableEffect(chatViewModel) {
      onDispose { chatViewModel.close() }
    }
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background),
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        CenterAlignedTopAppBar(
          title = {
            Text(
              "VlogPilot",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
          },
          actions = {
            IconButton(onClick = onOpenGallery) {
              Icon(Icons.Outlined.Apps, contentDescription = "Open gallery")
            }
          },
          colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
          ),
        )
      },
      bottomBar = {
        VlogPilotBottomBar(selected = selectedTab, onSelect = { selectedTab = it })
      },
    ) { innerPadding ->
      VlogPilotScreen(
        selectedTab = selectedTab,
        onTabChange = { selectedTab = it },
        chatViewModel = chatViewModel,
        bottomPadding = innerPadding.calculateBottomPadding(),
        modelManagerViewModel = modelManagerViewModel,
        modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
      )
    }
  }
}

/** iOS-style bottom NavigationBar. Hairline separator on top, accent for the
 *  selected item, secondaryLabel for inactive — keeps the bar visually quiet
 *  so the content above it stays the focus. */
@Composable
private fun VlogPilotBottomBar(
  selected: VlogPilotTab,
  onSelect: (VlogPilotTab) -> Unit,
) {
  val tokens = VlogPilotTokens
  // Hairline separator at the top edge — Apple Photos / Settings have this
  // subtle line between content and tab bar so the bar doesn't bleed in.
  androidx.compose.foundation.layout.Column {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(0.5.dp)
        .background(tokens.colors.opaqueSeparator),
    )
    NavigationBar(
      containerColor = MaterialTheme.colorScheme.background,
      tonalElevation = 0.dp,
    ) {
      VlogPilotTab.entries.forEach { tab ->
        NavigationBarItem(
          selected = tab == selected,
          onClick = { onSelect(tab) },
          icon = {
            Icon(
              imageVector = tab.icon,
              contentDescription = null,
            )
          },
          label = {
            Text(
              tab.label,
              style = MaterialTheme.typography.labelSmall,
            )
          },
          alwaysShowLabel = true,
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = tokens.colors.accent,
            selectedTextColor = tokens.colors.accent,
            unselectedIconColor = tokens.colors.secondaryLabel,
            unselectedTextColor = tokens.colors.secondaryLabel,
            indicatorColor = tokens.colors.accentTint,
          ),
        )
      }
    }
  }
}
