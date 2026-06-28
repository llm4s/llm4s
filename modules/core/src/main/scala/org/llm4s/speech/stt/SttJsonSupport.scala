package org.llm4s.speech.stt

import scala.util.Try
import ujson.Value

/**
 * Shared helpers for reading STT CLI/JSON output, used by both [[WhisperSpeechToText]] and
 * [[VoskSpeechToText]]. The field accessors swallow type/lookup errors and return `None`/empty so
 * callers can treat partial or unexpected JSON shapes uniformly.
 */
private[stt] object SttJsonSupport {

  def field(value: Value, key: String): Option[Value] =
    Try(value(key)).toOption

  def stringField(value: Value, key: String): Option[String] =
    field(value, key).flatMap(v => Try(v.str).toOption)

  def doubleField(value: Value, key: String): Option[Double] =
    field(value, key).flatMap(v => Try(v.num).toOption)

  def arrayField(value: Value, key: String): Seq[Value] =
    field(value, key).flatMap(v => Try(v.arr.toSeq).toOption).getOrElse(Seq.empty)

  /** Join the trimmed, non-empty words into a single space-separated string. */
  def renderWords(words: Seq[WordTimestamp]): String =
    words.map(_.word.trim).filter(_.nonEmpty).mkString(" ").trim

  /** Mean of the words' available confidence values, or `None` when none carry a confidence score. */
  def averageConfidence(words: Seq[WordTimestamp]): Option[Double] = {
    val confidences = words.flatMap(_.confidence)
    if (confidences.nonEmpty) Some(confidences.sum / confidences.size) else None
  }
}
