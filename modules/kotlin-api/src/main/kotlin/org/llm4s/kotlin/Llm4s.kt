package org.llm4s.kotlin

import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmResult
import org.llm4s.javaapi.Llm4s as JLlm4s

/**
 * Internal seam that allows tests to replace the calls to [JLlm4s] static
 * methods without requiring real LLM credentials in unit tests.
 */
internal interface ClientFactory {
    fun createDefault(): LlmResult<JLlmClient>
    fun createAgent(client: JLlmClient): JAgent
}

internal object DefaultClientFactory : ClientFactory {
    override fun createDefault(): LlmResult<JLlmClient> = JLlm4s.createDefaultClient()
    override fun createAgent(client: JLlmClient): JAgent = JLlm4s.createAgent(client)
}

/**
 * Entry-point factory for Kotlin callers.
 *
 * ```kotlin
 * val client = Llm4s.createDefaultClient()   // reads LLM_MODEL + API key env vars
 * val agent  = Llm4s.createAgent(client)
 * ```
 */
object Llm4s {
    internal var factory: ClientFactory = DefaultClientFactory

    /**
     * Creates an [LLMClientKt] from the default provider configured via
     * environment variables (e.g. `LLM_MODEL`, `OPENAI_API_KEY`).
     * Throws [LLMException] if the provider cannot be configured.
     */
    fun createDefaultClient(): LLMClientKt {
        val jClient = factory.createDefault().unwrap("Failed to create client", cancellation = false)
        return LLMClientKt(jClient)
    }

    /**
     * Wraps an [LLMClientKt] in an [AgentKt] ready for natural-language queries.
     */
    fun createAgent(client: LLMClientKt): AgentKt {
        val jAgent = factory.createAgent(client.underlying)
        return AgentKt(jAgent)
    }
}
