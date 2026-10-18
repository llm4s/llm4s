package org.llm4s.kotlin

import org.llm4s.agent.Agent
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.LlmResult
import org.llm4s.toolapi.ToolRegistry
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
 * val client = Llm4s.createDefaultClient()   // the default provider section of application.conf
 * val agent  = Llm4s.createAgent(client)
 *
 * // or a client configured in code, with one of the Java facade's builders
 * // (import org.llm4s.javaapi.Llm4s as JLlm4s)
 * val local = Llm4s.wrapClient(JLlm4s.ollama().model("llama3.2").build().get())
 * ```
 */
object Llm4s {
    internal var factory: ClientFactory = DefaultClientFactory

    /**
     * Creates an [LLMClientKt] from the default provider section of `application.conf`
     * (the one `llm4s.providers.provider` names).
     * Throws [LLMException] if the provider cannot be configured.
     */
    fun createDefaultClient(): LLMClientKt {
        val jClient = factory.createDefault().unwrap("Failed to create client", cancellation = false)
        return LLMClientKt(jClient)
    }

    /**
     * Wraps a [JLlmClient] in an [LLMClientKt], for a client the default configuration does not give: one built in
     * code with the Java facade's builders (`JLlm4s.openAi()`, `anthropic()`, `gemini()`, `ollama()`,
     * `openAiCompatible()`, `azureOpenAi()`), or with `JLlm4s.createClient(ProviderConfig)` or `JLlm4s.wrapClient`.
     *
     * Closing the returned client closes [client]. A builder's `build()` does not throw: a missing or invalid field is
     * a failed `LlmResult` of kind `VALIDATION`, whose `get()` throws the facade's `LlmException`, so check it before
     * wrapping the client.
     */
    fun wrapClient(client: JLlmClient): LLMClientKt = LLMClientKt(client)

    /**
     * Wraps an [LLMClientKt] in an [AgentKt] ready for natural-language queries.
     */
    fun createAgent(client: LLMClientKt): AgentKt {
        val jAgent = factory.createAgent(client.underlying)
        return AgentKt(jAgent)
    }

    /**
     * Wraps an [LLMClientKt] in an [AgentKt] that can call [tools], whose model calls stream when
     * [streaming] is set: then [AgentKt.stream] carries the answer's text deltas (`AgentEvents.TextDelta()`).
     * Without it the agent calls the model's `complete`, and a stream carries no deltas.
     */
    fun createAgent(client: LLMClientKt, tools: ToolRegistry, streaming: Boolean): AgentKt =
        AgentKt(JLlm4s.createAgent(client.underlying, tools, streaming))

    /**
     * Wraps an [Agent] built with `Agent.builder`, for what [createAgent] does not set: its middleware
     * (guardrails, approvals), handoffs, system prompt or runtime.
     */
    fun wrapAgent(agent: Agent): AgentKt = AgentKt(JLlm4s.wrapAgent(agent))
}
