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
 * [[LlmResult]]`[`[[JAgentResult]]`]` so Java callers do not need to deal with
 * Scala's `Either` or `Result` types directly, nor read a Scala value: a [[JAgentResult]]'s accessors
 * return `String`s, JDK types and this package's own types. A conversation is a thread of the
 * agent's in-memory runtime, kept until [[forget]] removes it.
 *
 * `run`, `continueConversation`, `resume` and `recover` block the calling thread and never throw.
 * Interrupting that thread cancels the turn: the call returns a failed result whose error is a
 * [[org.llm4s.error.CancelledError CancelledError]], with the interrupt flag still set, once the
 * turn has ended (it waits up to 5 seconds for the turn to end, for a provider that ignores its
 * interrupt), and the
 * conversation's thread is left for `recover`. A thread already interrupted starts no turn.
 * `InterruptedException` is never thrown, so the methods declare no checked exception and `javac`
 * rejects a `catch (InterruptedException e)` around them - test the result for a `CancelledError`
 * instead. To cancel a turn without interrupting a thread, `stream` it and call [[AgentStream.cancel]].
 *
 * A turn whose tools need approval, whose tools or middleware ask a question, or whose task a static
 * breakpoint holds, ends `SUSPENDED`: its status's `pending()` - or [[JAgent.pending]] - lists what it
 * waits for, and `resume(threadId, answers)` answers some or all of it and continues. A turn that
 * failed or was cancelled continues with `recover(threadId)`. Both block, as `run` does.
 *
 * {{{
 * LlmResult<JAgentResult> turn = agent.run("Deploy the release");
 * List<Answer> answers = new ArrayList<>();
 * for (PendingInterrupt p : turn.get().status().pending()) answers.add(Answer.approve(p.id()));
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
 * LlmResult<JAgentResult> result = agent.run("Summarise the news today");
 * result.ifSuccess(r -> System.out.println(r.answer().orElse("(" + r.status().kind() + ")")))
 *       .ifFailure(e -> System.err.println(e.getMessage()));
 * }}}
 */
final class JAgent private[javaapi] (private val underlying: Result[Agent]) {

  /**
   * Runs `query` as the first turn of a new conversation. A `null` query yields a failed result.
   *
   * Blocks the calling thread until the turn ends. If that thread is interrupted while it waits, the
   * turn is cancelled: the result is a failure whose error is a
   * [[org.llm4s.error.CancelledError CancelledError]], returned once the turn has ended (it waits up to
   * 5 seconds for the turn to end), and the thread's interrupt flag is left set. A turn that had
   * already begun committing its outcome is not cancelled, and that outcome is returned, the flag still
   * set. A thread already interrupted starts no turn. The conversation's id is not in a failed result, so a failed or
   * cancelled turn's conversation is forgotten. `InterruptedException` is never thrown, and the
   * method declares none.
   */
  def run(query: String): LlmResult[JAgentResult] =
    if (query == null) LlmResult.failure(ValidationError.required("query"))
    else turn("JAgent.run")(_.run(query))

  /**
   * Runs `query` as the next turn of `previous`'s conversation - only its `threadId()` is read. A `null`
   * argument yields a failed result.
   * Blocks and handles an interrupt as [[run]] does: the turn is cancelled and the result is a
   * `CancelledError`, the interrupt flag left set, never an `InterruptedException`; the conversation's
   * thread is left for [[recover]].
   */
  def continueConversation(previous: JAgentResult, query: String): LlmResult[JAgentResult] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else if (query == null) LlmResult.failure(ValidationError.required("query"))
    else turn("JAgent.continueConversation")(_.run(ThreadId(previous.threadId), query))

  /** Removes `previous`'s conversation from the agent's runtime. A `null` argument yields a failed result. */
  def forget(previous: JAgentResult): LlmResult[Void] =
    if (previous == null) LlmResult.failure(ValidationError.required("previous"))
    else forgetThread(previous.threadId)

  /**
   * Removes the conversation on `threadId` from the agent's runtime - one you named for `stream`, say,
   * whose turn failed, so no result carries its id. Forgetting an unknown thread succeeds; a thread
   * whose turn is still running is refused (`GraphError.ThreadBusy`), and nothing is removed. A `null`
   * argument yields a failed result.
   */
  def forget(threadId: String): LlmResult[Void] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else forgetThread(threadId)

  private def forgetThread(threadId: String): LlmResult[Void] =
    call("JAgent.forget")(_.forget(ThreadId(threadId)).map(_ => null))

  /**
   * Runs `query` as one turn on `threadId` - a new conversation, or the next turn of one - handing
   * every event of the turn to `listener`, then its result or error; see [[AgentStreamListener]].
   * Returns at once with the running [[AgentStream]]. A refused start (a blank query, a busy thread,
   * a `null` argument) is a failed result, and `listener` hears nothing.
   */
  def stream(threadId: String, query: String, listener: AgentStreamListener): LlmResult[AgentStream] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (query == null) LlmResult.failure(ValidationError.required("query"))
    else
      streaming(listener)((agent, onEnd, l) =>
        agent.streamEnding(ThreadId(threadId), query, RunConfig(), Nil, onEnd)(l)
      )

  /**
   * Answers some of `threadId`'s pending approvals, questions and breakpoints and continues, as a stream;
   * see [[stream]]. Build each answer with [[Answer.approve]], [[Answer.reject]], [[Answer.edit]],
   * [[Answer.reply]] or [[Answer.proceed]], e.g. `List.of(Answer.approve(id))`; for an id answered twice, the last answer
   * counts. A `null` or malformed answer is a failed result, and `listener` hears nothing.
   */
  def streamResume(
    threadId: String,
    answers: java.util.List[Answer],
    listener: AgentStreamListener
  ): LlmResult[AgentStream] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (answers == null) LlmResult.failure(ValidationError.required("answers"))
    else
      decoded(answers).fold(
        LlmResult.failure,
        byId =>
          streaming(listener)((agent, onEnd, l) =>
            agent.streamResumeEnding(ThreadId(threadId), byId, RunConfig(), onEnd)(l)
          )
      )

  /**
   * Answers some of `threadId`'s pending approvals, questions and breakpoints - read them with
   * `result.status().pending()` -
   * and continues the turn, returning its result; unanswered ones stay pending, so the result can be
   * `Suspended` again. Build each answer with [[Answer.approve]], [[Answer.reject]], [[Answer.edit]],
   * [[Answer.reply]] or [[Answer.proceed]]; for an id answered twice, the last answer counts. A `null` or malformed answer,
   * an empty answers list (`GraphError.InvalidResume`), an answer to an id the thread does not wait
   * for, or a thread that is not suspended is a failed result. Blocks and handles an interrupt as [[run]] does: the
   * turn is cancelled, the result is a `CancelledError` with the interrupt flag left set, and the
   * thread is left for [[recover]].
   */
  def resume(threadId: String, answers: java.util.List[Answer]): LlmResult[JAgentResult] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else if (answers == null) LlmResult.failure(ValidationError.required("answers"))
    else decoded(answers).fold(LlmResult.failure, byId => turn("JAgent.resume")(_.resume(ThreadId(threadId), byId)))

  /**
   * Continues `threadId`'s failed or cancelled turn - a `stream` that was cancelled, a run that failed
   * on a provider error - re-running only the work that did not finish, and returns its result. A
   * thread with nothing to recover is a failed result. Blocks and handles an interrupt as [[run]] does:
   * the recovered turn is cancelled and the thread is left for another `recover`.
   */
  def recover(threadId: String): LlmResult[JAgentResult] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else turn("JAgent.recover")(_.recover(ThreadId(threadId)))

  /** `answers` by interrupt id, the last answer to an id winning; or the first answer that is not one. */
  private def decoded(answers: java.util.List[Answer]): Result[Map[InterruptId, ujson.Value]] =
    answers.asScala.foldLeft[Result[Map[InterruptId, ujson.Value]]](Right(Map.empty)) { (byId, answer) =>
      for {
        sofar <- byId
        pair  <- Option(answer).toRight(ValidationError.required("answer")).flatMap(_.underlying)
      } yield sofar + pair
    }

  /** Continues `threadId`'s failed or cancelled turn, as a stream; see [[stream]]. */
  def streamRecover(threadId: String, listener: AgentStreamListener): LlmResult[AgentStream] =
    if (threadId == null) LlmResult.failure(ValidationError.required("threadId"))
    else streaming(listener)((agent, onEnd, l) => agent.streamRecoverEnding(ThreadId(threadId), RunConfig(), onEnd)(l))

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

  /** [[call]] for a turn: its result as a [[JAgentResult]]. */
  private def turn(operation: String)(body: Agent => Result[AgentResult]): LlmResult[JAgentResult] =
    call(operation)(agent => body(agent).map(JAgentResult.of))

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
   * What `result`'s turn waits for: for a `SUSPENDED` turn, its pending approvals, then its tool questions,
   * middleware questions and breakpoints, as [[PendingInterrupt]]s; an empty list for a turn that completed, was blocked, reached its step
   * limit, or a `null` result. The list is unmodifiable. A shortcut for `result.status().pending()`.
   * Answer them with [[Answer]] and continue with [[JAgent.resume]] or [[JAgent.streamResume]].
   */
  def pending(result: JAgentResult): java.util.List[PendingInterrupt] =
    if (result == null) java.util.List.of() else result.status.pending
}
