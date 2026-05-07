/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot

import com.google.ai.edge.gallery.customtasks.common.CustomTask
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal object VlogPilotTaskModule {
  @Provides @IntoSet
  fun provideVlogPilotTask(): CustomTask = VlogPilotTask()
}
