package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, StreamEvent, ThreadId }
import org.llm4s.core.safety.Safety
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.jdk.CollectionConverters.*

/**
 * Java-friendly wrapper around [[Agent]].
 *
 * Exposes `run(query)` and `continueConversation(previous, query)`, returning
 * [[LlmResult]]`[`[[AgentResult]]`]` so Java callers do not need to deal with
 * Scala's `Either` or `Result` types directly. A conversation is a thread of the
 * agent's in-memory runtime, kept until [[forget]] removes it.
 *
 * `stream`, `streamResume` and `streamRecover` run a turn on a thread you name and hand its events
 * to an [[AgentStreamListener]] as they happen, returning an [[AgentStream]] at once - to await the
 * turn's result, or to cancel it. From Java a thread id is a `String`.
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

  /**
   * Runs `query` as one turn on `threadId` - a new conversation, or the next turn of one - handing
   * every event of the turn to `listener`, then its result or error; see [[AgentStreamListener]].
   * Returns at once with the running [[AgentStream]]. A refused start (a blank query, a busy thread,
   * a `null` argument) is a failed result, and `listener` hears nothing.
   */
  def stream(threadId: ThreadId, query: String, listener: AgentStreamListener): LlmResult[AgentStream] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (query == null) LlmResult.failure(ValidationError.required("query"))
    else streaming(listener)((agent, onEnd, l) => agent.streamEnding(threadId, query, RunConfig(), Nil, onEnd)(l))

  /**
   * Answers some of `threadId`'s pending approvals and questions and continues, as a stream; see
   * [[stream]]. Build each answer with the suspended result's `approve`, `reject`, `edit` or `reply`,
   * e.g. `List.of(result.approve(id))`; for an id answered twice, the last answer counts.
   */
  def streamResume(
    threadId: ThreadId,
    answers: java.util.List[(InterruptId, ujson.Value)],
    listener: AgentStreamListener
  ): LlmResult[AgentStream] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (answers == null) LlmResult.failure(ValidationError.required("answers"))
    else
      streaming(listener)((agent, onEnd, l) =>
        agent.streamResumeEnding(threadId, answers.asScala.toMap, RunConfig(), onEnd)(l)
      )

  /** Continues `threadId`'s failed or cancelled turn, as a stream; see [[stream]]. */
  def streamRecover(threadId: ThreadId, listener: AgentStreamListener): LlmResult[AgentStream] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else streaming(listener)((agent, onEnd, l) => agent.streamRecoverEnding(threadId, RunConfig(), onEnd)(l))

  /**
   * Starts the turn with a buffer's listener, ending the buffer when the turn's subscription ends - so
   * the stream ends even for a turn that commits no terminal event - and delivers the buffer's events
   * to `listener` on the stream's own thread. The buffer's listener never blocks, so a slow `listener`
   * loses live events (`StreamEvent.LiveGap`) rather than holding the turn up.
   */
  private def streaming(listener: AgentStreamListener)(
    start: (Agent, () => Unit, StreamEvent => Unit) => Result[AgentRun]
  ): LlmResult[AgentStream] =
    if (listener == null) LlmResult.failure(ValidationError.required("listener"))
    else
      call { agent =>
        val buffer = AgentEventBuffer(AgentStream.BufferSize)
        start(agent, () => buffer.end(), buffer.listener).map(AgentStream.start(_, buffer, listener))
      }

  private def call[A](body: Agent => Result[A]): LlmResult[A] =
    LlmResult.from(underlying.flatMap(agent => Safety.safely(body(agent)).flatMap(identity)))
}
