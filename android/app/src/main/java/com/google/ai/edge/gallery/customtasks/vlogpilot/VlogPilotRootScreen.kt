/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * App-launch landing for VlogPilot. Wraps VlogPilotScreen in a Scaffold with a
 * minimal top bar: title plus one Apps action that opens the original gallery
 * home (where Models / Import / other tasks live behind the side drawer).
 *
 * Ships an iOS-style aesthetic via VlogPilotTheme — inset-grouped backgrounds,
 * system blue accent, hairline separators, generous whitespace.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.google.ai.edge.gallery.customtasks.vlogpilot.ui.theme.VlogPilotTheme
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlogPilotRootScreen(
  modelManagerViewModel: ModelManagerViewModel,
  onOpenGallery: () -> Unit,
) {
  VlogPilotTheme {
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
    ) { innerPadding ->
      VlogPilotScreen(
        bottomPadding = innerPadding.calculateBottomPadding(),
        modelManagerViewModel = modelManagerViewModel,
        modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
      )
    }
  }
}
