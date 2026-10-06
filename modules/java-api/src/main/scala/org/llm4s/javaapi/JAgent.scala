package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentResult }
import org.llm4s.core.safety.Safety
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Java-friendly wrapper around [[Agent]].
 *
 * Exposes `run(query)` and `continueConversation(previous, query)`, returning
 * [[LlmResult]]`[`[[AgentResult]]`]` so Java callers do not need to deal with
 * Scala's `Either` or `Result` types directly. A conversation is a thread of the
 * agent's in-memory runtime, kept until [[forget]] removes it.
 *
 * Obtain instances via [[Llm4s.createAgent]].
 *
 * {{{
 * JAgent agent = Llm4s.createAgent(client);
 * LlmResult<AgentResult> result = agent.run("Summarise the news today");
 * result.ifSuccess(r -> System.out.println(r.answer()))
 *       .ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JAgent private[javaapi] (private val underlying: Result[Agent]) {

  /** Runs `query` as the first turn of a new conversation. A `null` query yields a failed result. */
  def run(query: String): LlmResult[AgentResult] =
    if (query == null) LlmResult.failure(ValidationError.required("query"))
    else call(_.run(query))

  /** Runs `query` as the next turn of `previous`'s conversation. A `null` argument yields a failed result. */
  def continueConversation(previous: AgentResult, query: String): LlmResult[AgentResult] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else if (query == null) LlmResult.failure(ValidationError.required("query"))
    else call(_.continueConversation(previous, query))

  /** Removes `previous`'s conversation from the agent's runtime. A `null` argument yields a failed result. */
  def forget(previous: AgentResult): LlmResult[Void] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else call(_.forget(previous.threadId).map(_ => null))

  private def call[A](body: Agent => Result[A]): LlmResult[A] =
    LlmResult.from(underlying.flatMap(agent => Safety.safely(body(agent)).flatMap(identity)))
}
