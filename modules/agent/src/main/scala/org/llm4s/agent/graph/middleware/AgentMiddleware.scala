package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.{ RunContext, StateKey, ToolCallId, ToolName }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.llmconnect.model.{ Completion, Message, ToolCall }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.annotation.unused

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
 * was cancelled, and a retry gets the same outcome without running the tool again.
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

  /** Sees the run's input before anything else runs: returns it, possibly changed, or `Left` to fail the run. */
  def beforeAgent(input: String, @unused context: RunContext): Result[String] = Right(input)

  /** Sees the run's final answer: returns it, possibly changed, or `Left` to fail the run. */
  def afterAgent(answer: String, @unused context: RunContext): Result[String] = Right(answer)

  /**
   * Wraps one model call. A wrapper may rewrite the request (inject a note, filter tools), call
   * `next` more than once (retry, fallback), transform its result, or return `Left`, which fails
   * the run. A model wrapper cannot suspend.
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
