package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.llm4s.agent.AgentState
import org.llm4s.java.JAgent

/**
 * Kotlin coroutine wrapper around [JAgent].
 *
 * Dispatches the blocking agent run on [Dispatchers.IO] and converts
 * Scala [org.llm4s.java.LlmResult] errors into [LLMException].
 *
 * Obtain instances via [Llm4s.createAgent].
 *
 * ```kotlin
 * val agent = Llm4s.createAgent(client)
 * val state = agent.run("Summarise today's news")
 * ```
 */
class AgentKt internal constructor(private val underlying: JAgent) {

    /**
     * Suspends until the agent completes the given [query] and returns the
     * resulting [AgentState]. Throws [LLMException] on failure.
     */
    suspend fun run(query: String): AgentState = withContext(Dispatchers.IO) {
        val result = underlying.run(query)
        if (result.isSuccess) {
            result.get()
        } else {
            val err = result.getError()
            throw LLMException(err.message ?: "Agent run failed", err)
        }
    }
}
