package org.llm4s.kotlin

import org.llm4s.java.LlmException

/** Runtime exception thrown by Kotlin coroutine wrappers instead of returning Scala [Either]. */
class LLMException(message: String, cause: LlmException? = null) : RuntimeException(message, cause)
