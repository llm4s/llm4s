package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentThread }
import org.llm4s.core.safety.Safety
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.ToolRegistry

/**
 * Java-friendly wrapper around [[Agent]].
 *
 * Exposes a simplified `run(query)` entry point that returns
 * [[LlmResult]]`[`[[AgentThread]]`]` so Java callers do not need to deal with
 * Scala's `Either` or `Result` types directly.
 *
 * Obtain instances via [[Llm4s.createAgent]].
 *
 * {{{
 * JAgent agent = Llm4s.createAgent(client);
 * LlmResult<AgentThread> result = agent.run("Summarise the news today");
 * result.ifSuccess(state -> System.out.println(state.conversation()))
 *       .ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JAgent private[javaapi] (private val underlying: Agent) {

  /** Runs the agent with an empty tool registry. A `null` query yields a failed result. */
  def run(query: String): LlmResult[AgentThread] =
    run(query, ToolRegistry.empty)

  /** Runs the agent with an explicit [[ToolRegistry]]. */
  def run(query: String, tools: ToolRegistry): LlmResult[AgentThread] =
    if (query == null) LlmResult.failure(ValidationError.required("query"))
    else if (tools == null) LlmResult.failure(ValidationError.required("tools"))
    else LlmResult.from(Safety.safely(underlying.run(query, tools)).flatMap(identity))
}
