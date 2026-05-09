/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Persistence layer for chat conversations. Each conversation lives at
 *   filesDir/conversations/<convoId>/
 *     ├── meta.json           — title, timestamps, linked eventId
 *     └── messages.jsonl      — append-only message history
 *
 * Append-only / JSONL was chosen over a single rewrite-on-change file so:
 *   1. The worker (background thread) can append from a different process
 *      context without locking against the UI reader.
 *   2. Process death mid-conversation doesn't lose the last few messages.
 *   3. We can tail/scroll lazily — no need to load the whole history to
 *      append.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.chat

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

object ChatStore {

  private const val TAG = "ChatStore"
  private const val CONVO_DIR = "conversations"
  private const val META_FILE = "meta.json"
  private const val MESSAGES_FILE = "messages.jsonl"

  private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; isLenient = true }
  private val lock = Any()

  private fun rootDir(context: Context): File =
    File(context.filesDir, CONVO_DIR).apply { mkdirs() }

  private fun convoDir(context: Context, convoId: String): File =
    File(rootDir(context), convoId).apply { mkdirs() }

  /** Lists conversations sorted by updatedAt desc (newest first). */
  fun listConversations(context: Context): List<ChatConversation> {
    val root = rootDir(context)
    if (!root.isDirectory) return emptyList()
    return root.listFiles()
      ?.filter { it.isDirectory }
      ?.mapNotNull { dir -> readMeta(File(dir, META_FILE)) }
      ?.sortedByDescending { it.updatedAtMs }
      ?: emptyList()
  }

  /** Reads all messages for a conversation in chronological order. */
  fun loadMessages(context: Context, convoId: String): List<ChatMessage> {
    val file = File(convoDir(context, convoId), MESSAGES_FILE)
    if (!file.isFile) return emptyList()
    return try {
      file.readLines()
        .mapNotNull { line ->
          if (line.isBlank()) return@mapNotNull null
          runCatching { json.decodeFromString(ChatMessage.serializer(), line) }.getOrNull()
        }
    } catch (t: Throwable) {
      Log.w(TAG, "loadMessages($convoId) failed: ${t.message}")
      emptyList()
    }
  }

  /** Appends a message and bumps the conversation's updatedAt. Thread-safe. */
  fun appendMessage(context: Context, msg: ChatMessage) {
    synchronized(lock) {
      val dir = convoDir(context, msg.conversationId)
      val msgFile = File(dir, MESSAGES_FILE)
      runCatching {
        msgFile.appendText(json.encodeToString(ChatMessage.serializer(), msg) + "\n")
      }.onFailure { Log.w(TAG, "appendMessage failed: ${it.message}") }
      // Bump meta.updatedAt
      val metaFile = File(dir, META_FILE)
      val existing = readMeta(metaFile)
      if (existing != null) {
        writeMeta(metaFile, existing.copy(updatedAtMs = msg.timestampMs))
      }
    }
  }

  /** Creates a new conversation and persists its meta. */
  fun createConversation(context: Context, title: String, eventId: String? = null): ChatConversation {
    val now = System.currentTimeMillis()
    val convoId = "convo_${now}_${(0..9999).random().toString().padStart(4, '0')}"
    val convo = ChatConversation(
      id = convoId,
      title = title,
      createdAtMs = now,
      updatedAtMs = now,
      eventId = eventId,
    )
    synchronized(lock) {
      val metaFile = File(convoDir(context, convoId), META_FILE)
      writeMeta(metaFile, convo)
    }
    return convo
  }

  /** Updates a conversation's title or linked eventId. */
  fun updateConversation(
    context: Context,
    convoId: String,
    title: String? = null,
    eventId: String? = null,
  ): ChatConversation? {
    return synchronized(lock) {
      val metaFile = File(convoDir(context, convoId), META_FILE)
      val existing = readMeta(metaFile) ?: return@synchronized null
      val updated = existing.copy(
        title = title ?: existing.title,
        eventId = eventId ?: existing.eventId,
        updatedAtMs = System.currentTimeMillis(),
      )
      writeMeta(metaFile, updated)
      updated
    }
  }

  fun deleteConversation(context: Context, convoId: String) {
    synchronized(lock) {
      runCatching { convoDir(context, convoId).deleteRecursively() }
    }
  }

  private fun readMeta(file: File): ChatConversation? {
    if (!file.isFile) return null
    return try {
      json.decodeFromString(ChatConversation.serializer(), file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "readMeta($file) failed: ${t.message}")
      null
    }
  }

  private fun writeMeta(file: File, convo: ChatConversation) {
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(json.encodeToString(ChatConversation.serializer(), convo))
    }.onFailure { Log.w(TAG, "writeMeta failed: ${it.message}") }
  }
}
