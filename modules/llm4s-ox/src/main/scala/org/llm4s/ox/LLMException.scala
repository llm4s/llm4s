package org.llm4s.ox

import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError

/**
 * Carries an [[org.llm4s.error.LLMError LLMError]] in a failed Ox `Flow`, which can only fail with a `Throwable`.
 * The `error` is the `LLMError` the provider reported. `LLMClientOx.accumulate` turns it back into a `Left`.
 */
@Experimental
final class LLMException(val error: LLMError) extends RuntimeException(error.message)
