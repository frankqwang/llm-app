/*
 * Copyright 2026 The pc-pilot v3 authors
 */
package com.vlogcopilot

import com.google.ai.edge.gallery.customtasks.common.CustomTask
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
internal object VlogCopilotTaskModule {
  @Provides @IntoSet
  fun provideVlogCopilotTask(): CustomTask = VlogCopilotTask()
}
