package org.llm4s.java

import org.llm4s.error.LLMError

/**
 * A checked-free runtime exception wrapping an [[LLMError]], thrown only when
 * Java callers dereference a failed [[LlmResult]] via [[LlmResult#get]].
 */
final class LlmException(val error: LLMError) extends RuntimeException(error.message)
