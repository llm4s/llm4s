package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.llm4s.java.JLlmClient

/**
 * Kotlin coroutine wrapper around [JLlmClient].
 *
 * All blocking calls are dispatched on [Dispatchers.IO] automatically. Errors
 * surface as [LLMException] rather than Scala [Either], so callers never need
 * to import any Scala types.
 *
 * Obtain instances via [Llm4s.createDefaultClient].
 *
 * ```kotlin
 * val client = Llm4s.createDefaultClient()
 * val reply = client.complete("What is 2 + 2?")
 * ```
 */
class LLMClientKt internal constructor(internal val underlying: JLlmClient) : AutoCloseable {

    /**
     * Suspends until the LLM returns a response for the given [query].
     *
     * Throws [LLMException] if the underlying call fails.
     */
    suspend fun complete(query: String): String = withContext(Dispatchers.IO) {
        val result = underlying.complete(query)
        if (result.isSuccess) {
            result.get()
        } else {
            val err = result.getError()
            throw LLMException(err.message ?: "LLM call failed", err)
        }
    }

    /**
     * Returns a cold [Flow] that emits text chunks for the given [prompt].
     *
     * Currently emits a single chunk (the full response). Chunk-level streaming
     * will be added when the underlying java-api gains streaming support.
     */
    fun streamComplete(prompt: String): Flow<String> = flow {
        emit(complete(prompt))
    }

    override fun close(): Unit = underlying.close()
}
