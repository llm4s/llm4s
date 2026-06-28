package org.llm4s.llmconnect.provider

import org.llm4s.types.Result

private[provider] object ProviderResultOps:
  extension [A](result: Result[A])
    def tapRight(f: A => Unit): Result[A] =
      result.map { value =>
        f(value)
        value
      }

    def tapLeft(f: org.llm4s.error.LLMError => Unit): Result[A] =
      result.left.map { error =>
        f(error)
        error
      }
