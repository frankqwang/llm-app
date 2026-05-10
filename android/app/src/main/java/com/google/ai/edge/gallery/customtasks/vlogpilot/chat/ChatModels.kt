/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Data model for the Chat tab. A user's lifetime of vlog creation in the
 * app is split into multiple Conversations — one per vlog or one per
 * editing thread, similar to ChatGPT / Claude.ai. Each conversation is
 * an append-only JSONL file under filesDir/conversations/<id>/messages.jsonl
 * plus a meta.json with title + linked event id.
 *
 * Messages have several Roles to drive UI:
 *  - USER: a bubble from the human ("做一条本周旅行的 vlog")
 *  - AGENT_STATUS: small left-aligned text ("好的，我先看看你的相册...")
 *  - AGENT_TOOL: a structured card representing one agent step (Browse /
 *    Audience / Director / Editor / Critic / Render). The body is rendered
 *    by AgentWorkPanel-style cards, so the data is a pointer to the
 *    underlying EventDecisions field rather than the rendered text.
 *  - RESULT: terminal card with mp4 preview + share/save/iterate actions
 *  - LOADING: pulsing placeholder while an agent is running
 *  - SYSTEM: meta info ("已切换到候选 X")
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.chat

import kotlinx.serialization.Serializable

@Serializable
enum class ChatRole {
  USER,
  AGENT_STATUS,
  AGENT_TOOL,
  RESULT,
  LOADING,
  SYSTEM,
}

@Serializable
data class ChatMessage(
  val id: String,
  val conversationId: String,
  val timestampMs: Long,
  val role: ChatRole,
  val text: String = "",
  // For AGENT_TOOL: which agent stage produced this (browse/audience/director/...)
  val agentStage: String? = null,
  // For AGENT_TOOL / RESULT: the eventId of the vlog under construction
  val eventId: String? = null,
  // For RESULT: where the rendered mp4 lives (so the chat can play it)
  val mp4Path: String? = null,
  // For USER voice messages: saved local WAV attachment and duration.
  val audioPath: String? = null,
  val audioDurationMs: Long? = null,
)

@Serializable
data class ChatConversation(
  val id: String,
  val title: String,
  val createdAtMs: Long,
  val updatedAtMs: Long,
  // Linked event id when this conversation produced (or is producing) a
  // specific vlog. Null for "free chat" sessions that haven't kicked off a
  // pipeline run yet.
  val eventId: String? = null,
)
