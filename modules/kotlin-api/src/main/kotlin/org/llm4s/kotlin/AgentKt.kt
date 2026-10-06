package org.llm4s.kotlin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.llm4s.agent.AgentResult
import org.llm4s.javaapi.JAgent

/**
 * Kotlin coroutine wrapper around [JAgent].
 *
 * Dispatches the blocking agent run on [Dispatchers.IO] (cancelling the caller interrupts it) and converts
 * Scala [org.llm4s.javaapi.LlmResult] errors into [LLMException]. A conversation is a thread of the agent's
 * in-memory runtime, kept until [forget] removes it.
 *
 * Obtain instances via [Llm4s.createAgent].
 *
 * ```kotlin
 * val agent  = Llm4s.createAgent(client)
 * val first  = agent.run("Summarise today's news")
 * val second = agent.continueConversation(first, "And yesterday's?")
 * agent.forget(second)
 * ```
 */
class AgentKt internal constructor(private val underlying: JAgent) {

    /**
     * Suspends until the agent completes the given [query], the first turn of a new conversation, and
     * returns the resulting [AgentResult]. Throws [LLMException] on failure.
     */
    suspend fun run(query: String): AgentResult = runInterruptible(Dispatchers.IO) {
        underlying.run(query).unwrap("Agent run failed")
    }

    /**
     * Suspends until the agent completes [query] as the next turn of [previous]'s conversation. Throws
     * [LLMException] on failure.
     */
    suspend fun continueConversation(previous: AgentResult, query: String): AgentResult =
        runInterruptible(Dispatchers.IO) {
            underlying.continueConversation(previous, query).unwrap("Agent run failed")
        }

    /** Removes [previous]'s conversation from the agent's runtime. Throws [LLMException] on failure. */
    suspend fun forget(previous: AgentResult): Unit = runInterruptible(Dispatchers.IO) {
        underlying.forget(previous).unwrap("Agent forget failed")
        Unit
    }
}
