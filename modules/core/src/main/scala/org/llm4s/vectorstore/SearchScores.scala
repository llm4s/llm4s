package org.llm4s.vectorstore

/**
 * The one rule by which a store turns what its backend measures into a search score in `[0, 1]`, higher meaning more
 * relevant (#3037; #3006 applies it to the RAG vector stores).
 *
 *   - **Cosine** similarity `cos` in `[-1, 1]` scores `(1 + cos) / 2`: identical directions 1.0, orthogonal 0.5,
 *     opposite 0.0. pgvector's cosine distance `d = 1 - cos` scores `1 - d / 2`.
 *   - **Not comparable**: a pair whose cosine is not a finite number - either vector is all zeros, or holds a NaN or
 *     an infinity - has no direction to compare. It is not a match: the memory or record is left out of the results,
 *     never scored (a NaN would fail the score's range check) and never a reason for the search to fail.
 *   - **BM25**, as SQLite FTS5's `bm25()` reports it (negative, more negative for a better match), scores `s / (1 + s)`
 *     with `s = max(0, -bm25)`: monotonic, so the better match always scores higher, and saturating below 1.0.
 *
 * Shared by `llm4s-memory`, `llm4s-memory-postgres` and `llm4s-rag`, which do not depend on each other.
 */
private[llm4s] object SearchScores {

  /** The score of a cosine similarity, or `None` if it is not finite (the pair is not comparable). */
  def fromCosine(cosine: Double): Option[Double] =
    if (java.lang.Double.isFinite(cosine)) Some(clamp((1.0 + cosine) / 2.0)) else None

  /** The score of a cosine distance (`1 - cos`, pgvector's `<=>`), or `None` if it is not finite. */
  def fromCosineDistance(distance: Double): Option[Double] = fromCosine(1.0 - distance)

  /** The score of an FTS5 `bm25()` value: `s / (1 + s)` for `s = max(0, -bm25)`; 0.0 if it is not finite. */
  def fromBm25(bm25: Double): Double =
    if (!java.lang.Double.isFinite(bm25)) 0.0
    else {
      val s = math.max(0.0, -bm25)
      clamp(s / (1.0 + s))
    }

  /**
   * The cosine similarity of `a` and `b`, or `None` if they are not comparable: different lengths, empty, either all
   * zeros, or holding a non-finite component.
   */
  def cosine(a: Array[Float], b: Array[Float]): Option[Double] =
    if (a.length != b.length || a.isEmpty) None
    else {
      var dot   = 0.0
      var normA = 0.0
      var normB = 0.0
      var i     = 0
      while (i < a.length) {
        val x = a(i).toDouble
        val y = b(i).toDouble
        dot += x * y
        normA += x * x
        normB += y * y
        i += 1
      }
      val denominator = math.sqrt(normA) * math.sqrt(normB)
      val result      = dot / denominator
      if (denominator == 0.0 || !java.lang.Double.isFinite(result)) None
      else Some(math.max(-1.0, math.min(1.0, result)))
    }

  /** True if `v` can be compared at all: non-empty, every component finite, and not all zeros. */
  def comparable(v: Array[Float]): Boolean =
    v.forall(x => java.lang.Float.isFinite(x)) && v.exists(_ != 0.0f)

  private def clamp(score: Double): Double = math.max(0.0, math.min(1.0, score))
}
