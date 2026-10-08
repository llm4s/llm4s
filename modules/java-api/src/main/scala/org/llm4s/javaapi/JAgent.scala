package org.llm4s.javaapi

import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, StreamEvent, ThreadId }
import org.llm4s.core.safety.Safety
import org.llm4s.error.{ CancelledError, ValidationError }
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
 * `run` and `continueConversation` block the calling thread and never throw. Interrupting that
 * thread returns a failed result whose error is a [[org.llm4s.error.CancelledError CancelledError]],
 * with the interrupt flag still set, while the run itself carries on; `InterruptedException` is
 * never thrown, so the methods declare no checked exception and `javac` rejects a
 * `catch (InterruptedException e)` around them - test the result for a `CancelledError` instead.
 *
 * A turn whose tools need approval, or ask a question, ends `Suspended`: [[JAgent.pending]] lists what
 * it waits for, and `resume(threadId, answers)` answers some or all of it and continues. A turn that
 * failed or was cancelled continues with `recover(threadId)`. Both block, as `run` does.
 *
 * {{{
 * LlmResult<AgentResult> turn = agent.run("Deploy the release");
 * List<Answer> answers = new ArrayList<>();
 * for (PendingInterrupt p : JAgent.pending(turn.get())) answers.add(Answer.approve(p.id()));
 * if (!answers.isEmpty()) turn = agent.resume(turn.get().threadId(), answers);
 * }}}
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

  /**
   * Runs `query` as the first turn of a new conversation. A `null` query yields a failed result.
   *
   * Blocks the calling thread until the turn ends. If that thread is interrupted while it waits, the
   * result is a failure whose error is a [[org.llm4s.error.CancelledError CancelledError]] and the
   * thread's interrupt flag is left set; the run itself is not stopped. `InterruptedException` is
   * never thrown, and the method declares none.
   */
  def run(query: String): LlmResult[AgentResult] =
    if (query == null) LlmResult.failure(ValidationError.required("query"))
    else call("JAgent.run")(_.run(query))

  /**
   * Runs `query` as the next turn of `previous`'s conversation. A `null` argument yields a failed result.
   * Blocks and reports an interrupt as [[run]] does: a `CancelledError` result, the interrupt flag
   * left set, never an `InterruptedException`.
   */
  def continueConversation(previous: AgentResult, query: String): LlmResult[AgentResult] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else if (query == null) LlmResult.failure(ValidationError.required("query"))
    else call("JAgent.continueConversation")(_.continueConversation(previous, query))

  /** Removes `previous`'s conversation from the agent's runtime. A `null` argument yields a failed result. */
  def forget(previous: AgentResult): LlmResult[Void] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else call("JAgent.forget")(_.forget(previous.threadId).map(_ => null))

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
   * [[stream]]. Build each answer with [[Answer.approve]], [[Answer.reject]], [[Answer.edit]] or
   * [[Answer.reply]], e.g. `List.of(Answer.approve(id))`; for an id answered twice, the last answer
   * counts. A `null` or malformed answer is a failed result, and `listener` hears nothing.
   */
  def streamResume(
    threadId: ThreadId,
    answers: java.util.List[Answer],
    listener: AgentStreamListener
  ): LlmResult[AgentStream] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (answers == null) LlmResult.failure(ValidationError.required("answers"))
    else
      decoded(answers).fold(
        LlmResult.failure,
        byId =>
          streaming(listener)((agent, onEnd, l) => agent.streamResumeEnding(threadId, byId, RunConfig(), onEnd)(l))
      )

  /**
   * Answers some of `threadId`'s pending approvals and questions - read them with [[JAgent.pending]] -
   * and continues the turn, returning its result; unanswered ones stay pending, so the result can be
   * `Suspended` again. Build each answer with [[Answer.approve]], [[Answer.reject]], [[Answer.edit]] or
   * [[Answer.reply]]; for an id answered twice, the last answer counts. A `null` or malformed answer,
   * an answer to an id the thread does not wait for, or a thread that is not suspended is a failed
   * result. Blocks and reports an interrupt as [[run]] does: a `CancelledError` result, the interrupt
   * flag left set, and the turn carries on.
   */
  def resume(threadId: ThreadId, answers: java.util.List[Answer]): LlmResult[AgentResult] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (answers == null) LlmResult.failure(ValidationError.required("answers"))
    else decoded(answers).fold(LlmResult.failure, byId => call("JAgent.resume")(_.resume(threadId, byId)))

  /**
   * Continues `threadId`'s failed or cancelled turn - a `stream` that was cancelled, a run that failed
   * on a provider error - re-running only the work that did not finish, and returns its result. A
   * thread with nothing to recover is a failed result. Blocks and reports an interrupt as [[run]] does.
   */
  def recover(threadId: ThreadId): LlmResult[AgentResult] =
    if (threadId.value == null) LlmResult.failure(ValidationError.required("threadId"))
    else call("JAgent.recover")(_.recover(threadId))

  /** `answers` by interrupt id, the last answer to an id winning; or the first answer that is not one. */
  private def decoded(answers: java.util.List[Answer]): Result[Map[InterruptId, ujson.Value]] =
    answers.asScala.foldLeft[Result[Map[InterruptId, ujson.Value]]](Right(Map.empty)) { (byId, answer) =>
      for {
        sofar <- byId
        pair  <- Option(answer).toRight(ValidationError.required("answer")).flatMap(_.underlying)
      } yield sofar + pair
    }

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
      call("JAgent.stream") { agent =>
        val buffer = AgentEventBuffer(AgentStream.BufferSize)
        start(agent, () => buffer.end(), buffer.listener).map(AgentStream.start(_, buffer, listener))
      }

  /**
   * `body` as a result that never throws. An `InterruptedException` escaping it - the runtime answers
   * an interrupt with `Left(CancelledError)` itself - becomes a `CancelledError` named `operation`,
   * with the interrupt flag restored, so no caller meets a checked exception the method does not
   * declare (#1591).
   */
  private def call[A](operation: String)(body: Agent => Result[A]): LlmResult[A] =
    LlmResult.from(
      CancelledError.attempt(operation)(underlying.flatMap(agent => Safety.safely(body(agent)).flatMap(identity)))
    )
}

object JAgent {

  /**
   * What `result`'s turn waits for: for a `Suspended` turn, its pending approvals, then its questions,
   * as [[PendingInterrupt]]s; an empty list for a turn that completed, was blocked, reached its step
   * limit, or a `null` result. The list is unmodifiable. Answer them with [[Answer]] and continue with
   * [[JAgent.resume]] or [[JAgent.streamResume]].
   */
  def pending(result: AgentResult): java.util.List[PendingInterrupt] =
    if (result == null) java.util.List.of() else PendingInterrupt.of(result.status)
}
