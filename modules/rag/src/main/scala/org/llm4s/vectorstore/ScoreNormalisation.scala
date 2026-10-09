package org.llm4s.vectorstore

/**
 * Maps one channel's raw scores into `(0, 1]` for [[FusionStrategy.WeightedScore]].
 *
 * Plain min-max put the lowest hit at exactly `0`, which is what a chunk the channel did not find at
 * all gets, so the weakest genuine hit was indistinguishable from a miss (#1318). The lowest hit now
 * maps to [[Floor]], the best to `1`, and a miss stays `0`.
 */
private[vectorstore] object ScoreNormalisation {

  /** What the lowest hit of a channel scores, when the channel's scores differ. */
  val Floor: Double = 0.1

  /** A normaliser for `scores`; every hit scores `1.0` when they are all equal (or there is one). */
  def over(scores: Seq[Double]): Double => Double =
    if (scores.isEmpty) _ => 1.0
    else {
      val (lo, hi) = (scores.min, scores.max)
      if (hi == lo) _ => 1.0
      else s => Floor + (1.0 - Floor) * (s - lo) / (hi - lo)
    }
}
