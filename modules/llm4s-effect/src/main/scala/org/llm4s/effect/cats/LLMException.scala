package org.llm4s.effect.cats

import org.llm4s.error.LLMError

/** Bridges [[LLMError]] into the cats-effect error channel as a [[Throwable]]. */
final class LLMException(val error: LLMError) extends RuntimeException(error.message)
