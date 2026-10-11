package org.llm4s.pekko

import org.llm4s.error.LLMError

/**
 * Carries an [[LLMError]] in a failed Pekko stream or `Future`, which can only fail with a `Throwable`.
 * The `error` is the `LLMError` the provider, the tool or the runtime reported.
 */
final class LLMException(val error: LLMError) extends RuntimeException(error.message)
