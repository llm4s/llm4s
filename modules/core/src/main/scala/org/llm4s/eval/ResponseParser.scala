package org.llm4s.eval

import scala.util.Try

/**
 * Internal utility for parsing LLM evaluation responses.
 */
private[eval] object ResponseParser {

  /**
   * Extract score from structured response (e.g. "SCORE: 0.85").
   * Uses regex to handle malformed numbers like "0.81.0".
   */
  def extractScore(response: String, fieldName: String = "SCORE"): Option[Double] = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)
    lines.find(_.startsWith(s"$fieldName:")).flatMap { line =>
      val value = line.stripPrefix(s"$fieldName:").trim
      "([0-9]+\\.?[0-9]*)".r.findFirstIn(value).flatMap(s => Try(s.toDouble).toOption)
    }
  }

  /**
   * Extract explanation from structured response.
   */
  def extractExplanation(response: String, fieldName: String = "EXPLANATION"): String = {
    val lines = response.split("\n").map(_.trim).filter(_.nonEmpty)
    lines
      .find(_.startsWith(s"$fieldName:"))
      .map(_.stripPrefix(s"$fieldName:").trim)
      .getOrElse("")
  }

  /**
   * Fallback: extract first valid decimal number from response.
   */
  def extractFirstNumber(response: String): Option[Double] =
    "([0-9]+\\.?[0-9]*)".r.findFirstIn(response.trim).flatMap(s => Try(s.toDouble).toOption)
}
