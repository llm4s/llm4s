package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.{ Resumed, RunContext, StateKey, ToolCallId, ToolName }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContext, ToolOutcome, ToolQuestion, ToolSet }
import org.llm4s.error.{ LLMError, NonRecoverableError }
import org.llm4s.llmconnect.model.{ Completion, Message, ToolCall }
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import scala.annotation.unused
import scala.util.Try

/**
 * Stable identifier of an [[AgentMiddleware]] in a [[MiddlewareStack]]; must match
 * `[a-zA-Z0-9_-]{1,64}`, which [[MiddlewareStack.of]] checks. Persisted in approval requests.
 */
opaque type MiddlewareId = String

object MiddlewareId:
  def apply(value: String): MiddlewareId         = value
  extension (id: MiddlewareId) def value: String = id

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope a
   * `MiddlewareId` is a `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given ReadWriter[MiddlewareId] = ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

/**
 * One model call as a model wrapper sees it: the conversation so far and the tools offered. A
 * wrapper that rewrites the request builds the next one with `withMessages` or `withTools`.
 */
final case class ModelRequest private (messages: Vector[Message], tools: ToolSet):
  def withMessages(m: Vector[Message]): ModelRequest = copy(messages = m)
  def withTools(t: ToolSet): ModelRequest            = copy(tools = t)

object ModelRequest:
  def apply(messages: Vector[Message], tools: ToolSet = ToolSet.empty): ModelRequest =
    new ModelRequest(messages, tools)

/**
 * One tool call as a tool wrapper sees it, after its arguments were validated and decoded. It is
 * read-only: a wrapper cannot change a call's arguments.
 */
final case class ToolCallRequest private (spec: AgentToolSpec[?], call: ToolCall):

  /** The call's id, typed; core's `ToolCall` keeps a string. */
  def toolCallId: ToolCallId = ToolCallId(call.id)

  /** The called tool's name, typed. */
  def toolName: ToolName = ToolName(call.name)

  def withSpec(s: AgentToolSpec[?]): ToolCallRequest = copy(spec = s)
  def withCall(c: ToolCall): ToolCallRequest         = copy(call = c)

object ToolCallRequest:
  def apply(spec: AgentToolSpec[?], call: ToolCall): ToolCallRequest = new ToolCallRequest(spec, call)

/**
 * A cross-cutting concern - approval, guardrails, logging, retry, rate limits - around an agent
 * run, its model calls and its tool calls. Every hook passes through by default; override only
 * those the concern needs.
 *
 * Middleware run as a [[MiddlewareStack]], ordered by registration and by [[runsBefore]] and
 * [[runsAfter]]: the first in stack order is the outermost wrapper and runs `beforeAgent` first;
 * unwinding, and `afterAgent`, run in reverse. A hook that throws fails the run with
 * `GraphError.MiddlewareFailed`; a thrown cancellation cancels it.
 *
 * `wrapToolCall` runs concurrently for the calls of one batch (up to `RunBudgets.maxConcurrency`),
 * on task threads, so a middleware's own state must be thread-safe.
 *
 * A wrapper should pass `ToolOutcome.Fatal(CancelledError)` through and not retry it: the call
 * was cancelled, and a retry gets the same outcome without running the tool again. It should pass a
 * question through too - a `Left(MiddlewareAsked)` from `next`, or a `ToolOutcome.Ask` - since asking
 * again only asks again.
 *
 * A middleware that asks typed questions - for review, an edit, or missing information - extends
 * [[AgentMiddleware.Asking]].
 */
trait AgentMiddleware:

  /** This middleware's identifier, unique in its stack; must match `[a-zA-Z0-9_-]{1,64}`. */
  def id: MiddlewareId

  /** Middleware this one must run outside of (wrap); each must be registered in the same stack. */
  def runsBefore: Set[MiddlewareId] = Set.empty

  /** Middleware this one must run inside of (be wrapped by); each must be registered in the same stack. */
  def runsAfter: Set[MiddlewareId] = Set.empty

  /** The state keys this middleware's `wrapToolCall` may add to a `Success` update. */
  def writes: Set[StateKey[?, ?]] = Set.empty

  /** Tools this middleware contributes; they join the loop's tool set and are validated like any other. */
  def tools: Vector[AgentTool[?]] = Vector.empty

  /** The codecs of the question this middleware asks and the answer it takes; only [[AgentMiddleware.Asking]] declares one. */
  private[graph] def declaredQuestion: Option[ToolQuestion[?, ?]] = None

  /** Sees the run's input before anything else runs: returns it, possibly changed, or `Left` to fail the run. */
  def beforeAgent(input: String, @unused context: RunContext): Result[String] = Right(input)

  /** Sees the run's final answer: returns it, possibly changed, or `Left` to fail the run. */
  def afterAgent(answer: String, @unused context: RunContext): Result[String] = Right(answer)

  /**
   * Wraps one model call. A wrapper may rewrite the request (inject a note, filter tools), call
   * `next` more than once (retry, fallback), transform its result, or return `Left`, which fails
   * the run. Only an [[AgentMiddleware.Asking]] wrapper suspends, by asking its question.
   *
   * Filtering `ModelRequest.tools` shapes what the model is offered and is not a permission
   * control: a tool the model calls anyway still runs through `wrapToolCall`, where denial belongs.
   */
  def wrapModelCall(request: ModelRequest, @unused context: RunContext)(
    next: ModelRequest => Result[Completion]
  ): Result[Completion] = next(request)

  /**
   * Wraps one tool call, after its arguments were validated. A wrapper denies with
   * `ToolOutcome.Error("Denied: ...")`, asks for approval with `NeedsApproval(reason)` (unless
   * `context.approved`), fails the run with `Fatal`, and short-circuits by not calling `next`. It
   * may call `next` more than once (retry) and transform the outcome `next` returns.
   */
  def wrapToolCall(@unused request: ToolCallRequest, @unused context: ToolContext)(
    next: () => ToolOutcome
  ): ToolOutcome = next()

object AgentMiddleware:

  /**
   * A middleware that asks typed questions of type `Q` and takes answers of type `Ans`, as
   * [[org.llm4s.agent.graph.tool.AgentTool.Asking]] does for a tool: it suspends the run for a
   * reviewer - to approve or edit an answer, or to supply missing information - instead of only
   * blocking or failing.
   *
   * A hook asks by returning [[ask]] - from `beforeAgent`, `afterAgent` or `wrapModelCall` - or
   * [[askAbout]] from `wrapToolCall`. The run suspends at the end of the superstep with a question of
   * its own, keyed by the asking task's interrupt id like any other, so several pending questions are
   * answered together or one at a time. The answer is decoded as `Ans` when the run is resumed: one
   * that does not decode is refused, the thread unchanged.
   *
   * Once answered, the hook's whole stack runs again from the outermost middleware - nothing about a
   * stack's position is checkpointed, as for approvals - and [[answered]] gives this middleware its
   * question and answer, so it continues instead of asking again. Answers given earlier in the same
   * stack run are kept while it runs again, so two asking middleware in one stack each ask once. A
   * middleware outside the asking one therefore runs twice, and must not depend on running once.
   *
   * What runs again is the asking task: `beforeAgent` re-runs the turn's input (nothing of the turn
   * is stored while it waits), `afterAgent` the final answer, `wrapToolCall` the tool call (its
   * arguments checked again), and `wrapModelCall` the model call - so a model wrapper that asks after
   * calling `next` calls the model again once answered, unless it returns a completion of its own (the
   * calls made before asking are counted in the thread's usage, and announced, while the question waits). A
   * question from a tool wrapper while the tool continues after its own question is refused, as the
   * call's error result.
   */
  abstract class Asking[Q: ReadWriter, Ans: ReadWriter] extends AgentMiddleware:

    final override private[graph] def declaredQuestion: Option[ToolQuestion[?, ?]] =
      Some(ToolQuestion(summon[ReadWriter[Q]], summon[ReadWriter[Ans]]))

    /**
     * Suspends the run with `question`; return it as the result of `beforeAgent`, `afterAgent` or
     * `wrapModelCall`. A question that does not encode is that `Left` instead.
     */
    final protected def ask(question: Q): Result[Nothing] =
      Try(upickle.default.writeJs(question)).toResult.flatMap(json => Left(MiddlewareAsked(id, json)))

    /** Suspends the tool call with `question`; return it as the result of `wrapToolCall`. */
    final protected def askAbout(question: Q): ToolOutcome = ToolOutcome.Ask(question)

    /** This middleware's question and its answer, when its hook runs again after a resume; `None` before. */
    final protected def answered(context: RunContext): Option[Resumed[Q, Ans]] =
      context.answers.get(id.value).flatMap { (question, answer) =>
        Try(Resumed(upickle.default.read[Q](question), upickle.default.read[Ans](answer))).toOption
      }

    /** [[answered]] for a tool call's context. */
    final protected def answered(context: ToolContext): Option[Resumed[Q, Ans]] = answered(context.run)

/**
 * A middleware's question on its way out of a hook, from [[AgentMiddleware.Asking.ask]]: the agent loop
 * suspends the run with it. A wrapper that sees it as `next`'s result passes it on. Outside the loop it
 * is an error: a question nobody can answer.
 */
final case class MiddlewareAsked private[graph] (middleware: MiddlewareId, question: ujson.Value)
    extends LLMError
    with NonRecoverableError:
  override val message: String = s"Middleware '${middleware.value}' asked a question"
