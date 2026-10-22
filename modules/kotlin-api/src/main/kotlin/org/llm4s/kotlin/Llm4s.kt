package org.llm4s.kotlin

import org.llm4s.agent.Agent
import org.llm4s.javaapi.JAgent
import org.llm4s.javaapi.JLlmClient
import org.llm4s.javaapi.JTool
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
     * An OpenAI client configured in code (#1495), with no `application.conf`: `apiKey` and `model` are required.
     *
     * ```kotlin
     * val client = Llm4s.openAi {
     *     apiKey = key
     *     model = "gpt-4o-mini"
     *     requestTimeout = 30.seconds
     * }
     * ```
     *
     * Each block is the Java facade's builder for its provider (`JLlm4s.openAi()` here), so it sends no request; a
     * required field left out, or a value out of range, throws [LLMException] naming the field.
     */
    fun openAi(configure: OpenAiSpec.() -> Unit): LLMClientKt = built(OpenAiSpec().apply(configure).toBuilder().build())

    /** An Anthropic client configured in code: `apiKey` and `model` are required; see [openAi]. */
    fun anthropic(configure: AnthropicSpec.() -> Unit): LLMClientKt =
        built(AnthropicSpec().apply(configure).toBuilder().build())

    /** A client of the Google Gemini API configured in code: `apiKey` and `model` are required; see [openAi]. */
    fun gemini(configure: GeminiSpec.() -> Unit): LLMClientKt = built(GeminiSpec().apply(configure).toBuilder().build())

    /** An Ollama client configured in code: `model` is required, the server `http://localhost:11434` unless set. */
    fun ollama(configure: OllamaSpec.() -> Unit): LLMClientKt = built(OllamaSpec().apply(configure).toBuilder().build())

    /** A client of a server speaking OpenAI's chat completions API: `baseUrl` and `model` are required; see [openAi]. */
    fun openAiCompatible(configure: OpenAiCompatibleSpec.() -> Unit): LLMClientKt =
        built(OpenAiCompatibleSpec().apply(configure).toBuilder().build())

    /** An Azure OpenAI client configured in code: `endpoint`, `apiKey` and `model` are required; see [openAi]. */
    fun azureOpenAi(configure: AzureOpenAiSpec.() -> Unit): LLMClientKt =
        built(AzureOpenAiSpec().apply(configure).toBuilder().build())

    private fun built(client: LlmResult<JLlmClient>): LLMClientKt =
        LLMClientKt(client.unwrap("Invalid client configuration", cancellation = false))

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
     * Wraps an [LLMClientKt] in an [AgentKt] that can call [tools], declared with [tool] or [tools] (#1495) - or built
     * from Java with `JTool.builder` or `JTool.fromAnnotated`.
     *
     * ```kotlin
     * val agent = Llm4s.createAgent(client, tools {
     *     tool("add", "Adds two numbers") {
     *         param<Long>("a", "First")
     *         param<Long>("b", "Second")
     *         handler { args -> args.get<Long>("a") + args.get<Long>("b") }
     *     }
     * })
     * ```
     *
     * Throws [LLMException] for a tool listed twice or two tools with one name.
     */
    fun createAgent(client: LLMClientKt, tools: List<JTool>): AgentKt =
        AgentKt(JLlm4s.createAgentWithTools(client.underlying, tools).unwrap("Failed to create agent", cancellation = false))

    /**
     * Wraps an [Agent] built with `Agent.builder`, for what [createAgent] does not set: its middleware
     * (guardrails, approvals), handoffs, system prompt or runtime.
     */
    fun wrapAgent(agent: Agent): AgentKt = AgentKt(JLlm4s.wrapAgent(agent))
}
