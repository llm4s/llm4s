package org.llm4s.java

import org.llm4s.agent.{ Agent, AgentState }
import org.llm4s.toolapi.ToolRegistry

/**
 * Java-friendly wrapper around [[Agent]].
 *
 * Exposes a simplified `run(query)` entry point that returns
 * [[LlmResult]]`[`[[AgentState]]`]` so Java callers do not need to deal with
 * Scala's `Either` or `Result` types directly.
 *
 * Obtain instances via [[Llm4s.createAgent]].
 *
 * {{{
 * JAgent agent = Llm4s.createAgent(client);
 * LlmResult<AgentState> result = agent.run("Summarise the news today");
 * result.ifSuccess(state -> System.out.println(state.conversation()))
 *       .ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JAgent private[java] (private val underlying: Agent) {

  /** Runs the agent with an empty tool registry. */
  def run(query: String): LlmResult[AgentState] =
    LlmResult.from(underlying.run(query, ToolRegistry.empty))

  /** Runs the agent with an explicit [[ToolRegistry]]. */
  def run(query: String, tools: ToolRegistry): LlmResult[AgentState] =
    LlmResult.from(underlying.run(query, tools))
}
