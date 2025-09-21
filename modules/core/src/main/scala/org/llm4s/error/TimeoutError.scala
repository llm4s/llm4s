package org.llm4s.error

import scala.concurrent.duration.Duration

/**
 * Timeout errors (can retry with different timeout)
 */
final case class TimeoutError(
  message: String,
  timeoutDuration: Duration,
  operation: String,
  cause: Option[Throwable] = None,
  override val context: Map[String, String] = Map.empty
) extends LLMError
    with RecoverableError {

  def withContext(key: String, value: String): TimeoutError =
    copy(context = context + (key -> value))

  def withContext(entries: Map[String, String]): TimeoutError =
    copy(context = context ++ entries)

  def withOperation(op: String): TimeoutError =
    copy(operation = op, context = context + ("operation" -> op))
}
