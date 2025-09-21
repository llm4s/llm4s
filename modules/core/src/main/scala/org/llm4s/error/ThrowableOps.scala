package org.llm4s.error

import org.llm4s.core.safety.{ DefaultErrorMapper, ErrorMapper }

/** Extension methods to convert Throwable to domain errors in a uniform way. */
object ThrowableOps {
  implicit final class RichThrowable(private val t: Throwable) extends AnyVal {
    def toLLMError(implicit em: ErrorMapper = DefaultErrorMapper): LLMError = em(t)
  }
}
