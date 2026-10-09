package org.llm4s.error

import org.llm4s.annotation.Stable
import org.llm4s.core.safety.{ DefaultErrorMapper, ErrorMapper }

/**
 * Extension methods for converting Throwable to domain-specific [[LLMError]] types.
 *
 * Provides a uniform way to convert exceptions into the LLM4S error hierarchy,
 * enabling consistent error handling across the codebase.
 *
 * @example
 * {{{
 * import org.llm4s.error.ThrowableOps._
 *
 * val error = new java.net.SocketTimeoutException("timeout").toLLMError
 * // Returns NetworkError("Request timeout", ...)
 *
 * // With custom mapper
 * implicit val mapper: ErrorMapper = customMapper
 * val customError = exception.toLLMError
 * }}}
 */
@Stable
object ThrowableOps {

  /**
   * Enriches Throwable with toLLMError conversion method.
   *
   * @param t The throwable to enrich
   */
  implicit final class RichThrowable(private val t: Throwable) extends AnyVal {

    /**
     * Converts this Throwable to an [[LLMError]] using the provided error mapper.
     *
     * @param em The error mapper to use (defaults to [[org.llm4s.core.safety.DefaultErrorMapper]])
     * @return An appropriate [[LLMError]] subtype based on the exception type
     */
    def toLLMError(implicit em: ErrorMapper = DefaultErrorMapper): LLMError = em(t)
  }
}
