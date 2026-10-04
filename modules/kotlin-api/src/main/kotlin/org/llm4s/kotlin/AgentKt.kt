package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.llm4s.agent.AgentState
import org.llm4s.javaapi.JAgent

/**
 * Kotlin coroutine wrapper around [JAgent].
 *
 * Dispatches the blocking agent run on [Dispatchers.IO] (cancelling the caller interrupts it) and converts
 * Scala [org.llm4s.javaapi.LlmResult] errors into [LLMException].
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
    suspend fun run(query: String): AgentState = runInterruptible(Dispatchers.IO) {
        underlying.run(query).unwrap("Agent run failed")
    }
}
