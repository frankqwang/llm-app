/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Minimal CLIP BPE tokenizer. The MobileCLIP2 text tower expects the OpenAI CLIP
 * BPE tokenization (49408 vocab, BOS=49406, EOS=49407, max_len=77). We don't
 * implement the full byte-level BPE merge logic here — that requires a 1.5 MB
 * vocab + merges file we expect bundled at assets/models/clip_vocab.json and
 * assets/models/clip_merges.txt. If the vocab files are missing, encode() falls
 * back to a degenerate "[BOS] [unk]*N [EOS]" so calling code can no-op cleanly.
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import android.content.Context
import org.json.JSONObject

class ClipTokenizer(
  private val vocab: Map<String, Int>,
  private val merges: List<Pair<String, String>>,
  private val bos: Int = 49406,
  private val eos: Int = 49407,
  private val unk: Int = 49405,
) {

  private val mergeRanks: Map<Pair<String, String>, Int> = merges.withIndex().associate { it.value to it.index }

  fun encode(text: String, seqLen: Int): IntArray {
    val ids = mutableListOf(bos)
    for (token in text.lowercase().trim().split(Regex("\\s+"))) {
      if (token.isEmpty()) continue
      ids += bpeEncode(token)
      if (ids.size >= seqLen - 1) break
    }
    ids += eos
    val out = IntArray(seqLen)
    for (i in 0 until seqLen) out[i] = ids.getOrNull(i) ?: 0
    return out
  }

  private fun bpeEncode(token: String): List<Int> {
    if (token.isEmpty()) return emptyList()
    val withSuffix = token.dropLast(1).map { it.toString() }.toMutableList().apply { add(token.last() + "</w>") }
    if (withSuffix.size == 1) {
      return listOf(vocab[withSuffix[0]] ?: unk)
    }
    val pieces = withSuffix.toMutableList()
    while (pieces.size > 1) {
      var bestPair: Pair<Int, Int>? = null
      var bestRank = Int.MAX_VALUE
      for (i in 0 until pieces.size - 1) {
        val r = mergeRanks[pieces[i] to pieces[i + 1]] ?: continue
        if (r < bestRank) { bestRank = r; bestPair = i to (i + 1) }
      }
      val pair = bestPair ?: break
      val merged = pieces[pair.first] + pieces[pair.second]
      pieces[pair.first] = merged
      pieces.removeAt(pair.second)
    }
    return pieces.map { vocab[it] ?: unk }
  }

  companion object {
    fun tryLoad(context: Context): ClipTokenizer? = try {
      val vocabJson = context.assets.open("models/clip_vocab.json").bufferedReader().use { it.readText() }
      val mergesTxt = context.assets.open("models/clip_merges.txt").bufferedReader().use { it.readText() }
      val vocab = parseVocab(vocabJson)
      val merges = parseMerges(mergesTxt)
      ClipTokenizer(vocab, merges)
    } catch (_: Throwable) {
      null
    }

    private fun parseVocab(json: String): Map<String, Int> {
      val obj = JSONObject(json)
      val out = HashMap<String, Int>(obj.length())
      val keys = obj.keys()
      while (keys.hasNext()) {
        val k = keys.next()
        out[k] = obj.getInt(k)
      }
      return out
    }

    private fun parseMerges(txt: String): List<Pair<String, String>> {
      val lines = txt.split("\n")
      val out = ArrayList<Pair<String, String>>(lines.size)
      for (raw in lines) {
        val l = raw.trim()
        if (l.isEmpty() || l.startsWith("#")) continue
        val parts = l.split(" ")
        if (parts.size == 2) out += parts[0] to parts[1]
      }
      return out
    }
  }
}
