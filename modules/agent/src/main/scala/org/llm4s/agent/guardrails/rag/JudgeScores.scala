package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/**
 * Reads the scores of a RAG judge's structured reply with the grammar `LLMGuardrail` reads a score with (#3234): one
 * plain decimal from 0 to 1, never clamped, so a judge that answers `2/10`, `30%` or `85` is unreadable rather than
 * read as a passing score. A missing or unreadable score is a `Left`; nothing here lets content through on a reply
 * it cannot read.
 */
private[guardrails] object JudgeScores {

  /** The score on the first of `lines` that starts with `label`, read as `LLMGuardrail.readScore` reads a reply. */
  def required(lines: Seq[String], label: String, field: String, response: String): Result[Double] =
    lines.find(_.startsWith(label)) match {
      case None => Left(unreadable(field, s"the reply has no $label line", response))
      case Some(line) =>
        LLMGuardrail
          .readScore(line.stripPrefix(label))
          .map(_.toDouble)
          .toRight(unreadable(field, s"$label is not a number from 0 to 1", response))
    }

  /** Each comma-separated score of `value`, every one a number from 0 to 1; a `Left` if any is not. */
  def list(value: String, label: String, field: String, response: String): Result[Seq[Double]] = {
    val parts = value.split(",").map(_.trim).filter(_.nonEmpty).toSeq
    val read  = parts.map(LLMGuardrail.readScore)
    if (read.forall(_.isDefined)) Right(read.flatten.map(_.toDouble))
    else Left(unreadable(field, s"$label holds a value that is not a number from 0 to 1", response))
  }

  /** Whether `score` reaches `threshold`, compared as `LLMGuardrail` compares them. */
  def reaches(score: Double, threshold: Double): Boolean =
    LLMGuardrail.reaches(BigDecimal(score), threshold)

  private def unreadable(field: String, why: String, response: String): ValidationError =
    ValidationError.invalid(
      field,
      s"Could not parse the judge's reply ($why): ${Redaction.safeBody(response, 200)}"
    )
}
