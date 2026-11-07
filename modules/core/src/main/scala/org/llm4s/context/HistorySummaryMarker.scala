package org.llm4s.context

import org.llm4s.llmconnect.model.{ Message, SystemMessage }

/**
 * The `[HISTORY_SUMMARY]` marker [[HistoryCompressor]] writes at the start of a digest message's
 * content, and the one place that recognises and builds it. `HistoryCompressor`, `LLMCompressor`
 * and `TokenWindow` each need to tell a digest message apart from every other kind; they share
 * this check instead of each writing their own, so a digest is recognised the same way everywhere.
 * A digest is a [[SystemMessage]] whose content starts with the marker - never a message of
 * another type whose text merely mentions it.
 */
private[context] object HistorySummaryMarker {

  private val Marker = "[HISTORY_SUMMARY]"

  /** `content` wrapped as a digest message's text, the marker on its own first line. */
  def wrap(content: String): String = s"$Marker\n$content"

  /** Whether `message` is a digest [[wrap]] produced. */
  def isDigest(message: Message): Boolean = message match {
    case s: SystemMessage => s.content.startsWith(Marker)
    case _                => false
  }

  /** `content`'s own text, the marker line removed; `content` unchanged if it carries no marker. */
  def unwrap(content: String): String = content.split("\n", 2) match {
    case Array(header, rest) if header == Marker => rest
    case _                                       => content
  }
}
