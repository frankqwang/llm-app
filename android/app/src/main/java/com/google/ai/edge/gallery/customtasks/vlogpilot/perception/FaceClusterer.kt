/*
 * Copyright 2026 The pc-pilot v3 authors
 *
 * Online agglomerative clustering over 192-D MobileFaceNet embeddings. Each
 * incoming face is assigned to the cluster with highest cosine similarity above
 * THRESH; otherwise a new cluster is created. Cluster centroids are updated as
 * running averages and re-L2-normalized after each merge.
 *
 * Output `personId` is a stable string label (`person_A`, `person_B`, ...).
 */
package com.google.ai.edge.gallery.customtasks.vlogpilot.perception

import kotlin.math.sqrt

class FaceClusterer(private val threshold: Float = 0.42f) {

  private data class Cluster(val id: String, var centroid: FloatArray, var count: Int)

  private val clusters = mutableListOf<Cluster>()
  private var nextLetter = 'A'

  /** Returns existing personId for `embedding` or assigns a new one. */
  fun assign(embedding: FloatArray): String {
    if (embedding.isEmpty()) return UNK
    var best: Cluster? = null
    var bestSim = -1f
    for (c in clusters) {
      val s = cosine(c.centroid, embedding)
      if (s > bestSim) { bestSim = s; best = c }
    }
    return if (best != null && bestSim >= threshold) {
      mergeInto(best, embedding)
      best.id
    } else {
      val id = newId()
      clusters += Cluster(id, embedding.copyOf(), 1)
      id
    }
  }

  fun knownPersons(): List<String> = clusters.map { it.id }

  // ----- helpers -----

  private fun mergeInto(c: Cluster, e: FloatArray) {
    val n = c.count.toFloat()
    val out = FloatArray(c.centroid.size)
    for (i in out.indices) out[i] = (c.centroid[i] * n + e[i]) / (n + 1f)
    c.centroid = l2norm(out)
    c.count += 1
  }

  private fun newId(): String {
    val id = "person_$nextLetter"
    nextLetter = if (nextLetter < 'Z') (nextLetter + 1) else nextLetter // cap at Z; runtime won't realistically exceed
    return id
  }

  private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size || a.isEmpty()) return 0f
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
  }

  private fun l2norm(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x * x
    val n = sqrt(s).toFloat().coerceAtLeast(1e-8f)
    val out = FloatArray(v.size)
    for (i in v.indices) out[i] = v[i] / n
    return out
  }

  companion object { const val UNK = "person_unknown" }
}
