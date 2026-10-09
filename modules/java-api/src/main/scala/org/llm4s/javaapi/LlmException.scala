package org.llm4s.javaapi

import org.llm4s.error.LLMError

/**
 * A checked-free runtime exception wrapping an [[LLMError]], thrown only when
 * Java callers dereference a failed [[LlmResult]] via [[LlmResult#get]].
 *
 * If the wrapped error carries an underlying `Throwable` (for example a
 * `cause: Option[Throwable]` field), it is exposed through `getCause`.
 */
final class LlmException(val error: LLMError) extends RuntimeException(error.message, LlmException.causeOf(error))

object LlmException {

  /** Finds the underlying `Throwable` carried by an error, or `null` when there is none. */
  private def causeOf(error: LLMError): Throwable =
    error.productIterator.collectFirst {
      case t: Throwable       => t
      case Some(t: Throwable) => t
    }.orNull
}
