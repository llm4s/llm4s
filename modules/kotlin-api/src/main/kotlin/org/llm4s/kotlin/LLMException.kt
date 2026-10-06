package org.llm4s.kotlin

import kotlinx.coroutines.CancellationException
import org.llm4s.error.CancelledError
import org.llm4s.javaapi.LlmException
import org.llm4s.javaapi.LlmResult

/** Runtime exception thrown by Kotlin coroutine wrappers instead of returning Scala [Either]. */
class LLMException(message: String, cause: LlmException? = null) : RuntimeException(message, cause)

/**
 * Returns the value of a successful result, or throws its error; see [toKotlin].
 */
internal fun <T> LlmResult<T>.unwrap(defaultMessage: String, cancellation: Boolean = true): T {
    if (isSuccess) return get()
    throw getError().toKotlin(defaultMessage, cancellation)
}

/**
 * The exception a Kotlin caller sees for this failure: an [LLMException], or a
 * [CancellationException] when the failure is a [CancelledError] and [cancellation] is set. llm4s
 * providers answer a thread interrupt (which is how a cancelled coroutine reaches them, see
 * `runInterruptible`) with `Left(CancelledError)`, and a coroutine that ends with an ordinary
 * exception after being cancelled would otherwise be reported as a failure and cancel its parent
 * scope.
 */
internal fun LlmException.toKotlin(defaultMessage: String, cancellation: Boolean = true): RuntimeException {
    val message = message ?: defaultMessage
    if (cancellation && error() is CancelledError) {
        return CancellationException(message).also { it.initCause(this) }
    }
    return LLMException(message, this)
}
