package org.llm4s.jev

/**
 * Jev's answer to one question. Its shape follows the question's type, and the API reports the type with every
 * answer (`type` on the wire): see [[JevQuestion]].
 */
sealed trait JevAnswer

/**
 * The answer to a [[JevQuestion.Noul]].
 *
 * @param probability the probability that the answer is yes, from 0 (no) to 1 (yes). The API reports no
 *                    confidence for a Noul.
 */
final case class NoulAnswer(probability: Double) extends JevAnswer

/**
 * The answer to a [[JevQuestion.Choice]].
 *
 * @param choice        the highest-probability option
 * @param probabilities every option with its probability; the API says they sum to 1, which is not re-checked
 *                      here because the values are rounded on the wire
 * @param confidence    how certain the model is, from 0 to 1, derived from the distribution (not the same thing
 *                      as the probability of the choice)
 */
final case class ChoiceAnswer(choice: String, probabilities: Map[String, Double], confidence: Double) extends JevAnswer

/** One level of a [[ScoreAnswer]]. */
final case class ScoreLevel(index: Int, description: ujson.Value, probability: Double)

/**
 * The answer to a [[JevQuestion.Score]].
 *
 * @param score      the probability-weighted level, which can land between levels (`1.05` is just above level 1). It
 *                   lies within the levels: a score the API sends a rounding error outside them is clamped in
 * @param levels     every level with its description and probability, in level order
 * @param confidence how certain the model is, from 0 to 1
 */
final case class ScoreAnswer(score: Double, levels: Seq[ScoreLevel], confidence: Double) extends JevAnswer

/** Tokens billed for a request. The API charges per input token; output tokens are reported but free. */
final case class JevUsage(inputTokens: Int, outputTokens: Int)
