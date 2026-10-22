package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import org.llm4s.javaapi.ConversationBuilder
import org.llm4s.javaapi.JCompletionOptions
import org.llm4s.javaapi.JLlmClient

/**
 * Kotlin coroutine wrapper around [JLlmClient].
 *
 * All blocking calls are dispatched on [Dispatchers.IO] automatically, and cancelling the calling
 * coroutine (or the collector of a [Flow]) interrupts the blocked call. Errors
 * surface as [LLMException] rather than Scala [Either], so callers never need
 * to import any Scala types.
 *
 * Obtain instances via [Llm4s.createDefaultClient], or wrap a client built in code with [Llm4s.wrapClient].
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
    suspend fun complete(query: String): String = runInterruptible(Dispatchers.IO) {
        underlying.complete(query).unwrap("LLM call failed")
    }

    /**
     * Suspends until the LLM answers [query], sent as a user message with [options] - built with [completionOptions]
     * or `JCompletionOptions.builder()` (#1495). Throws [LLMException] if the underlying call fails.
     *
     * ```kotlin
     * val reply = client.complete("Name a colour", completionOptions { temperature = 0.2; maxTokens = 20 })
     * ```
     */
    suspend fun complete(query: String, options: JCompletionOptions): String = runInterruptible(Dispatchers.IO) {
        underlying.complete(ConversationBuilder.create().user(query).build(), options).unwrap("LLM call failed")
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
