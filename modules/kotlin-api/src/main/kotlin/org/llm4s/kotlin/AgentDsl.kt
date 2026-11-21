package org.llm4s.kotlin

import org.llm4s.javaapi.JAgentBuilder
import org.llm4s.javaapi.JContextWindow
import org.llm4s.javaapi.JGuardrail
import org.llm4s.javaapi.JOutputGuardrail
import org.llm4s.javaapi.JTool
import org.llm4s.javaapi.Llm4s as JLlm4s

/**
 * An agent called [id] over [client], configured in Kotlin (#2534): its system prompt, tools, input and output
 * guardrails, context window and streaming. The block is the Java facade's `Llm4s.agentBuilder(id, client)`
 * ([JAgentBuilder]), so each setting means what it means there.
 *
 * ```kotlin
 * val agent = agent("support", client) {
 *     systemPrompt("You are a support assistant. Answer in JSON.")
 *     tools {
 *         tool("add", "Adds two numbers") {
 *             param<Long>("a", "First")
 *             param<Long>("b", "Second")
 *             handler { args -> args.get<Long>("a") + args.get<Long>("b") }
 *         }
 *     }
 *     inputGuardrails(JGuardrails.length(1, 10_000), JGuardrails.profanity())
 *     outputGuardrails(JGuardrails.json())
 *     contextWindow(lastMessages = 20)
 *     streaming()
 * }.getOrThrow()
 * ```
 *
 * The agent's turns run with [AgentKt]'s suspend functions and [kotlinx.coroutines.flow.Flow]s. Errors are values, as
 * for the Java builder: anything the agent cannot be built from - a blank id, an invalid guardrail
 * (`JGuardrails.length(5, 1)`) or window (`lastMessages = 0`), two tools with one name, a tool declared in [AgentSpec.tools]
 * that cannot be built - is a failed [Result] holding an [LLMException] naming the first problem, never a throw. A
 * setting given twice replaces the first. An answer-only guardrail (`JGuardrails.json()`, a [JOutputGuardrail]) is not
 * a [JGuardrail], so [AgentSpec.inputGuardrails] does not compile with it.
 */
fun agent(id: String, client: LLMClientKt, configure: AgentSpec.() -> Unit): Result<AgentKt> =
    AgentSpec(JLlm4s.agentBuilder(id, client.underlying)).apply(configure).build()

/** [agent] with nothing configured: no system prompt, no tools, no guardrails, the whole conversation sent. */
fun agent(id: String, client: LLMClientKt): Result<AgentKt> = agent(id, client) {}

/** The receiver of [agent]: each call sets one setting of the agent, replacing what an earlier call set. */
@Llm4sDsl
class AgentSpec internal constructor(private var builder: JAgentBuilder) {
    private var failure: LLMException? = null

    /** The system prompt, sent first on every model call of every turn. */
    fun systemPrompt(prompt: String) {
        builder = builder.systemPrompt(prompt)
    }

    /** The tools the model may call: built with [tool] or [tools], or from Java with `JTool.builder` or `JTool.fromAnnotated`. */
    fun tools(tools: List<JTool>) {
        builder = builder.tools(tools)
    }

    /** The tools the model may call, as [tools] with a list. */
    fun tools(vararg tools: JTool) {
        tools(tools.toList())
    }

    /**
     * The tools the model may call, declared as in the top-level [org.llm4s.kotlin.tools] block. A tool that cannot be
     * built (a blank name, no handler) makes [agent]'s result a failure rather than throwing.
     */
    fun tools(configure: ToolsSpec.() -> Unit) {
        try {
            tools(org.llm4s.kotlin.tools(configure))
        } catch (e: LLMException) {
            failure = failure ?: e
        }
    }

    /**
     * Guardrails run in order on each turn's query before the model sees it; one that fails ends the turn `BLOCKED`
     * (`status().kind()`), naming itself in `status().guardrail()`, and the turn stores nothing. Built with
     * `JGuardrails`: `length`, `profanity` and `regex`; `json()` checks answers only, so it is not accepted here.
     */
    fun inputGuardrails(guardrails: List<JGuardrail>) {
        builder = builder.inputGuardrails(guardrails)
    }

    /** The input guardrails, as [inputGuardrails] with a list. */
    fun inputGuardrails(vararg guardrails: JGuardrail) {
        inputGuardrails(guardrails.toList())
    }

    /**
     * Guardrails run in order on each turn's final answer; one that fails ends the turn `BLOCKED`, and the answer is not
     * kept. Any of `JGuardrails` - `json()` too.
     */
    fun outputGuardrails(guardrails: List<JOutputGuardrail>) {
        builder = builder.outputGuardrails(guardrails)
    }

    /** The output guardrails, as [outputGuardrails] with a list. */
    fun outputGuardrails(vararg guardrails: JOutputGuardrail) {
        outputGuardrails(guardrails.toList())
    }

    /**
     * Sends each model call at most the [lastMessages] most recent messages of the conversation, dropping the oldest
     * first (`JContextWindow.lastMessages`). The system prompt is always sent and not counted, the current turn is
     * always sent whole, and a tool call is never sent without its result. Only what is sent is pruned: a result's
     * `messages()` still has the whole conversation. Below 1 makes [agent]'s result a failure.
     */
    fun contextWindow(lastMessages: Int) {
        builder = builder.contextWindow(JContextWindow.lastMessages(lastMessages))
    }

    /**
     * Whether the agent's model calls stream: then [AgentKt.stream] carries the answer's text deltas
     * (`AgentEvents.TextDelta()`). Off unless set.
     */
    fun streaming(enabled: Boolean) {
        builder = builder.streaming(enabled)
    }

    /** Makes the agent's model calls stream, as `streaming(true)`. */
    fun streaming() {
        streaming(true)
    }

    internal fun build(): Result<AgentKt> {
        failure?.let { return Result.failure(it) }
        val built = builder.build()
        return if (built.isSuccess) Result.success(AgentKt(built.get()))
        else Result.failure(built.getError().toKotlin("Failed to build agent", cancellation = false))
    }
}
