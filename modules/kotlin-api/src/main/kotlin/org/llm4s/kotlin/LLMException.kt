package org.llm4s.kotlin

import kotlinx.coroutines.CancellationException
import org.llm4s.error.CancelledError
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult

/** Runtime exception thrown by Kotlin coroutine wrappers instead of returning Scala [Either]. */
class LLMException(message: String, cause: LlmException? = null) : RuntimeException(message, cause)

/**
 * Returns the value of a successful result, or throws.
 *
 * A failure is thrown as [LLMException]. The exception is a [CancellationException] instead when
 * the failure is a [CancelledError] and [cancellation] is set: llm4s providers answer a thread
 * interrupt (which is how a cancelled coroutine reaches them, see `runInterruptible`) with
 * `Left(CancelledError)`, and a coroutine that ends with an ordinary exception after being
 * cancelled would otherwise be reported as a failure and cancel its parent scope.
 */
internal fun <T> LlmResult<T>.unwrap(defaultMessage: String, cancellation: Boolean = true): T {
    if (isSuccess) return get()
    val err = getError()
    val message = err.message ?: defaultMessage
    if (cancellation && err.error() is CancelledError) {
        throw CancellationException(message).also { it.initCause(err) }
    }
    throw LLMException(message, err)
}
