package org.llm4s.javaapi

import org.llm4s.agent.graph.Checkpointer
import org.llm4s.error.ValidationError
import org.llm4s.trace.Tracing

/**
 * An agent under construction, from [[Llm4s.agentBuilder]] (#2476): its system prompt, tools, guardrails, context window
 * and streaming, set from Java with Java types only.
 *
 * {{{
 * LlmResult<JAgent> agent = Llm4s.agentBuilder("support", client)
 *     .systemPrompt("You are a support assistant.")
 *     .tools(tools)
 *     .inputGuardrails(List.of(JGuardrails.length(1, 10_000)))
 *     .outputGuardrails(List.of(JGuardrails.json()))
 *     .contextWindow(JContextWindow.lastMessages(20))
 *     .streaming(true)
 *     .build();
 * }}}
 *
 * Immutable: every method returns a new builder, and each setting replaces the one set before. No method throws: a
 * `null` argument, and anything [[build]] cannot make an agent of, is a failed result of `build()`.
 */
final class JAgentBuilder private[javaapi] (token: FacadeToken, spec: AgentBuilds.Spec) {

  FacadeToken.check(token, "JAgentBuilder")

  /** The system prompt, sent first on every model call of every turn. */
  def systemPrompt(prompt: String): JAgentBuilder =
    next(spec.copy(systemPrompt = Option(prompt).toRight(ValidationError.required("systemPrompt")).map(Some(_))))

  /** The tools the model may call, defined with [[JTool.builder]] or [[JTool.fromAnnotated]]. */
  def tools(tools: java.util.List[JTool]): JAgentBuilder =
    next(spec.copy(tools = AgentBuilds.elements(tools, "tools", "a tool")))

  /**
   * Guardrails run in order on each turn's query before the model sees it; one that fails ends the turn `BLOCKED`, and
   * the turn stores nothing.
   */
  def inputGuardrails(guardrails: java.util.List[? <: JGuardrail]): JAgentBuilder =
    next(spec.copy(input = AgentBuilds.elements(guardrails, "inputGuardrails", "a guardrail")))

  /**
   * Guardrails run in order on each turn's final answer; one that fails ends the turn `BLOCKED`, and the answer is not
   * kept.
   */
  def outputGuardrails(guardrails: java.util.List[? <: JOutputGuardrail]): JAgentBuilder =
    next(spec.copy(output = AgentBuilds.elements(guardrails, "outputGuardrails", "a guardrail")))

  /** What the agent sends the model of a conversation on each call; without one, it sends the whole conversation. */
  def contextWindow(window: JContextWindow): JAgentBuilder =
    next(
      spec.copy(contextWindow =
        Option(window).toRight(ValidationError.required("contextWindow")).flatMap(_.config).map(Some(_))
      )
    )

  /**
   * Whether the agent's model calls stream: then a [[JAgent.stream]] carries the answer's text deltas
   * (`AgentEvents.TextDelta()`). Off by default.
   */
  def streaming(streaming: Boolean): JAgentBuilder = next(spec.copy(streaming = streaming))

  /** The most model calls and tool rounds one turn may take before it ends `STEP_LIMIT_REACHED`; at least 1, 50 unset. */
  def maxSteps(maxSteps: Int): JAgentBuilder = next(spec.copy(maxSteps = Some(maxSteps)))

  /**
   * Where the agent keeps its threads between turns: an `InMemoryCheckpointer` unless set, or a durable store such as
   * `llm4s-agent-checkpoint-sqlite`'s or `-postgres`'s.
   */
  def checkpointer(checkpointer: Checkpointer): JAgentBuilder =
    next(spec.copy(checkpointer = Option(checkpointer).toRight(ValidationError.required("checkpointer")).map(Some(_))))

  /** Where the agent reports its runs, model calls and tool calls; none unless set. */
  def tracing(tracing: Tracing): JAgentBuilder =
    next(spec.copy(tracing = Option(tracing).toRight(ValidationError.required("tracing")).map(Some(_))))

  /**
   * The agent, or a failed result naming the first problem: a `null` id, client or setting, a blank or invalid id, a
   * `null` element in a list, two tools with one name, an invalid guardrail or context window, or tools the agent cannot
   * offer together.
   */
  def build(): LlmResult[JAgent] = FacadeInternals.result(AgentBuilds.build(spec))

  private def next(spec: AgentBuilds.Spec): JAgentBuilder = new JAgentBuilder(FacadeToken.INSTANCE, spec)
}
