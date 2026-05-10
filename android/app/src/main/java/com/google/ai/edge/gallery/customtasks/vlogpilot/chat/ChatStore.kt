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

  // Reader is lenient and ignores unknown keys so schema additions don't break
  // older on-disk records. Writer is COMPACT (single line per record) so the
  // .jsonl invariant holds — readLines() + decodeFromString(line) only works
  // when each record is one line. Pretty-print here was a long-standing bug:
  // messages survived in-memory but vanished after every app restart because
  // the loader couldn't parse the multi-line entries the writer produced.
  private val readerJson = Json { ignoreUnknownKeys = true; isLenient = true }
  private val writerJson = Json { prettyPrint = false; ignoreUnknownKeys = true }
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

  /** Reads all messages for a conversation in chronological order.
   *  Falls back to a brace-balanced parser if the file was written by an
   *  earlier version that pretty-printed records across multiple lines. */
  fun loadMessages(context: Context, convoId: String): List<ChatMessage> {
    val file = File(convoDir(context, convoId), MESSAGES_FILE)
    if (!file.isFile) return emptyList()
    return try {
      val perLine = file.readLines()
        .mapNotNull { line ->
          if (line.isBlank()) return@mapNotNull null
          runCatching { readerJson.decodeFromString(ChatMessage.serializer(), line) }.getOrNull()
        }
      if (perLine.isNotEmpty()) return perLine
      // Legacy fallback — a previous build wrote pretty-printed JSON which
      // can't be parsed line-by-line. Recover by extracting brace-balanced
      // chunks from the whole file content.
      extractJsonObjects(file.readText())
        .mapNotNull { chunk ->
          runCatching { readerJson.decodeFromString(ChatMessage.serializer(), chunk) }.getOrNull()
        }
    } catch (t: Throwable) {
      Log.w(TAG, "loadMessages($convoId) failed: ${t.message}")
      emptyList()
    }
  }

  /** Splits a string containing concatenated JSON objects into individual
   *  object strings. Brace-balanced; ignores braces inside string literals. */
  private fun extractJsonObjects(text: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var inString = false
    var escape = false
    var start = -1
    text.forEachIndexed { i, c ->
      if (escape) { escape = false; return@forEachIndexed }
      if (inString) {
        if (c == '\\') escape = true
        else if (c == '"') inString = false
        return@forEachIndexed
      }
      when (c) {
        '"' -> inString = true
        '{' -> { if (depth == 0) start = i; depth++ }
        '}' -> {
          depth--
          if (depth == 0 && start >= 0) {
            out.add(text.substring(start, i + 1))
            start = -1
          }
        }
      }
    }
    return out
  }

  /** Appends a message and bumps the conversation's updatedAt. Thread-safe. */
  fun appendMessage(context: Context, msg: ChatMessage) {
    synchronized(lock) {
      val dir = convoDir(context, msg.conversationId)
      val msgFile = File(dir, MESSAGES_FILE)
      runCatching {
        msgFile.appendText(writerJson.encodeToString(ChatMessage.serializer(), msg) + "\n")
      }.onFailure { Log.w(TAG, "appendMessage failed: ${it.message}") }
      // Bump meta.updatedAt
      val metaFile = File(dir, META_FILE)
      val existing = readMeta(metaFile)
      if (existing != null) {
        writeMeta(metaFile, existing.copy(updatedAtMs = msg.timestampMs))
      }
    }
  }

  fun saveAudioAttachment(
    context: Context,
    convoId: String,
    messageId: String,
    audioWav: ByteArray,
  ): String {
    val file = File(File(convoDir(context, convoId), "attachments"), "$messageId.wav")
    synchronized(lock) {
      file.parentFile?.mkdirs()
      file.writeBytes(audioWav)
    }
    return file.absolutePath
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
      readerJson.decodeFromString(ChatConversation.serializer(), file.readText())
    } catch (t: Throwable) {
      Log.w(TAG, "readMeta($file) failed: ${t.message}")
      null
    }
  }

  private fun writeMeta(file: File, convo: ChatConversation) {
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(writerJson.encodeToString(ChatConversation.serializer(), convo))
    }.onFailure { Log.w(TAG, "writeMeta failed: ${it.message}") }
  }
}
