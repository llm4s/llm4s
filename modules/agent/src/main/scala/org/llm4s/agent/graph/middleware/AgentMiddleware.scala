package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.{ Resumed, RunContext, StateKey, StateUpdate, ToolCallId, ToolName }
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  ToolContext,
  ToolOutcome,
  ToolQuestion,
  ToolResource,
  ToolSet
}
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NonRecoverableError }
import org.llm4s.llmconnect.model.{ Completion, Message, SystemMessage, ToolCall }
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import scala.annotation.unused
import scala.reflect.ClassTag
import scala.util.Try

/**
 * Stable identifier of an [[org.llm4s.agent.graph.middleware.AgentMiddleware AgentMiddleware]] in a [[org.llm4s.agent.graph.middleware.MiddlewareStack MiddlewareStack]]; must match
 * `[a-zA-Z0-9_-]{1,64}`, which [[org.llm4s.agent.graph.middleware.MiddlewareStack.of MiddlewareStack.of]] checks. Persisted in approval requests.
 */
opaque type MiddlewareId = String

@Experimental
object MiddlewareId:
  def apply(value: String): MiddlewareId         = value
  extension (id: MiddlewareId) def value: String = id

  /** The id of the middleware that runs `AgentBuilder.withInputGuardrails` and `withOutputGuardrails`. */
  val AgentGuardrails: MiddlewareId = "agent-guardrails"

  /** The id of the middleware that offers an agent's subagents (`AgentBuilder.withSubagents`, a harness's). */
  val Delegation: MiddlewareId = "delegation"

  /** The id of the middleware that applies a subagent's ancestors' permission rules. */
  val AncestorPermissions: MiddlewareId = "ancestor-permissions"

  /** The id the response format's prompt section is listed under (`AgentBuilder.withOutput`). */
  val ResponseFormat: MiddlewareId = "response-format"

  /**
   * The ids the agent uses itself, each with what it is reserved for (#2795): a middleware given to
   * `AgentBuilder.withMiddleware` with one of them is refused by `build()`, whether or not the agent installs its own
   * under that id. A constraint may name one (`MiddlewareRef(MiddlewareId.Delegation)`).
   */
  val reserved: Map[MiddlewareId, String] = Map(
    AgentGuardrails     -> "the guardrails given to AgentBuilder.withInputGuardrails and withOutputGuardrails",
    Delegation          -> "the middleware that offers an agent's subagents",
    AncestorPermissions -> "the middleware that applies a subagent's ancestors' permission rules",
    ResponseFormat      -> "the response format's prompt section (AgentBuilder.withOutput)"
  )

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope a
   * `MiddlewareId` is a `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given ReadWriter[MiddlewareId] = ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)

/**
 * Names the middleware an ordering constraint is about ([[AgentMiddleware.runsBefore]], [[AgentMiddleware.runsAfter]]):
 * the one with an id, `MiddlewareRef(id)`, or every middleware of a type, `MiddlewareRef.ofType[GuardrailMiddleware]`
 * (#2795). Name another middleware by its type when you can: its id is its user's to choose.
 *
 * A reference is required unless it is made optional with [[ifPresent]]: a required one that matches nothing in the
 * stack is refused, so a mistyped id cannot silently leave an order unarranged; an optional one applies when the
 * other is there and is ignored when it is not, which is how a reusable middleware says "outside the guardrails, if
 * there are any".
 */
@Experimental
final case class MiddlewareRef private (
  private[middleware] val target: Either[MiddlewareId, Class[?]],
  optional: Boolean
):

  /** This reference, applied only when the stack has the middleware it names; no error when it has none. */
  def ifPresent: MiddlewareRef = copy(optional = true)

  /** Whether `middleware` is one this reference names. */
  private[middleware] def matches(middleware: AgentMiddleware): Boolean =
    target.fold(_ == middleware.id, _.isInstance(middleware))

  /** Whether it names a type: a middleware is never ordered against itself by its own type. */
  private[middleware] def byType: Boolean = target.isRight

  /** What a required reference that matched nothing is reported as. */
  private[middleware] def missing: String = target.fold(
    id => s"unknown middleware '${id.value}'",
    kind => s"a middleware of type ${kind.getSimpleName}, and the stack has none"
  )

  override def toString: String =
    val named = target.fold(id => s"'${id.value}'", kind => s"type ${kind.getSimpleName}")
    if optional then s"MiddlewareRef($named, if present)" else s"MiddlewareRef($named)"

@Experimental
object MiddlewareRef:

  /** The middleware whose id is `id`. */
  def apply(id: MiddlewareId): MiddlewareRef = new MiddlewareRef(Left(id), optional = false)

  /** Every middleware of type `M` in the stack - a subclass too - whatever id each was given. */
  def ofType[M <: AgentMiddleware](using tag: ClassTag[M]): MiddlewareRef =
    new MiddlewareRef(Right(tag.runtimeClass), optional = false)

/**
 * One model call as a model wrapper sees it: the system prompt, the conversation so far and the tools offered. A
 * wrapper that rewrites the request builds the next one with `withMessages`, `withTools` or `withSystemPrompt`.
 *
 * The system prompt is a field of its own (#2795), never one of `messages`, so a wrapper that prunes, summarises or
 * replaces the messages cannot drop it by accident. It is the prompt as assembled for this request: the agent's,
 * then every middleware's prompt sections, and the response format's. [[sentMessages]] is what the model receives.
 *
 * @param messages the history being sent, without the system prompt; never stored from here
 * @param tools the tools offered
 * @param systemPrompt the assembled system prompt; `None` when the agent has none and no section was added
 */
@Experimental
final case class ModelRequest private (messages: Vector[Message], tools: ToolSet, systemPrompt: Option[String]):
  def withMessages(m: Vector[Message]): ModelRequest = copy(messages = m)
  def withTools(t: ToolSet): ModelRequest            = copy(tools = t)

  /** This request with `prompt` as its system prompt. */
  def withSystemPrompt(prompt: String): ModelRequest = copy(systemPrompt = Some(prompt))

  /** This request with `prompt` as its system prompt, or none. */
  def withSystemPrompt(prompt: Option[String]): ModelRequest = copy(systemPrompt = prompt)

  /**
   * What the model is sent: the system prompt as a `SystemMessage` first, then `messages`. A blank prompt sends no
   * system message.
   */
  def sentMessages: Vector[Message] = systemPrompt.filter(_.trim.nonEmpty).map(SystemMessage(_)).toVector ++ messages

@Experimental
object ModelRequest:
  def apply(
    messages: Vector[Message],
    tools: ToolSet = ToolSet.empty,
    systemPrompt: Option[String] = None
  ): ModelRequest =
    new ModelRequest(messages, tools, systemPrompt)

/**
 * What a model wrapper returns: the model's `completion`, and an `update` to keys the wrapper declares in its
 * `writes`, committed with the model step's task. A wrapper adds its writes with [[addUpdate]], which keeps those of
 * the wrappers inside it - the combined update applies innermost first - and changes the completion with
 * [[withCompletion]], which keeps the update. A wrapper that answers without calling `next` builds its own with
 * `ModelResponse(completion)` or `ModelResponse(completion, update)`. Only the response a wrapper returns counts: the
 * updates of a response it discarded, calling `next` again, are never committed.
 *
 * Every hook returns its update the same way (#2795): built with the result, read with `update`, added to with
 * `addUpdate` - here, on [[BoundaryResult]] and on a tool wrapper's `ToolOutcome`. Nothing replaces an update.
 */
@Experimental
final class ModelResponse private (
  val completion: Completion,
  private[graph] val parts: Vector[(Option[MiddlewareId], StateUpdate)]
):

  /** The writes of every wrapper that contributed to this response, innermost first. */
  def update: StateUpdate = parts.foldLeft(StateUpdate.empty)((acc, part) => acc.combine(part._2))

  /** This response with `c` as its completion, keeping its update. */
  def withCompletion(c: Completion): ModelResponse = new ModelResponse(c, parts)

  /** This response with `u` applied after its update; `u` belongs to the wrapper that returns the response. */
  def addUpdate(u: StateUpdate): ModelResponse =
    if u.isEmpty then this else new ModelResponse(completion, parts :+ (None -> u))

  /** The parts no wrapper has claimed yet, attributed to `owner`: the stack does this as each wrapper returns. */
  private[graph] def claimedBy(owner: MiddlewareId): ModelResponse =
    new ModelResponse(completion, parts.map((o, u) => o.orElse(Some(owner)) -> u))

  override def toString: String = s"ModelResponse($completion, $update)"

@Experimental
object ModelResponse:
  /** `completion` with `update`, which belongs to the wrapper that returns the response; none by default. */
  def apply(completion: Completion, update: StateUpdate = StateUpdate.empty): ModelResponse =
    new ModelResponse(completion, Vector.empty).addUpdate(update)

/**
 * What `beforeAgent` and `afterAgent` return: the `text` - the run's input, or its final answer - possibly changed,
 * and an `update` to keys the middleware declares in its `writes`. `beforeAgent`'s commits with the turn's input,
 * so the turn's first model call sees it; `afterAgent`'s with the turn's final answer. Built with its update,
 * `BoundaryResult(text, update)`, and added to with [[addUpdate]], as every hook's result is.
 */
@Experimental
final case class BoundaryResult private (text: String, update: StateUpdate):

  /** This result with `t` as its text, keeping its update. */
  def withText(t: String): BoundaryResult = BoundaryResult(t, update)

  /** This result with `u` applied after its update. */
  def addUpdate(u: StateUpdate): BoundaryResult = BoundaryResult(text, update.combine(u))

@Experimental
object BoundaryResult:
  def apply(text: String, update: StateUpdate = StateUpdate.empty): BoundaryResult = new BoundaryResult(text, update)

/**
 * Content a middleware adds to the system prompt of a model request, built for the request and never stored.
 * `id` is unique within one request; `cacheable` content is placed before the rest, so a provider's prompt cache
 * sees a stable prefix.
 */
@Experimental
final case class PromptSection private (id: String, text: String, cacheable: Boolean):
  def withId(i: String): PromptSection         = PromptSection(i, text, cacheable)
  def withText(t: String): PromptSection       = PromptSection(id, t, cacheable)
  def withCacheable(c: Boolean): PromptSection = PromptSection(id, text, c)

@Experimental
object PromptSection:
  def apply(id: String, text: String, cacheable: Boolean = false): PromptSection =
    new PromptSection(id, text, cacheable)

  /**
   * The tokens `chars` characters of section text are estimated at - one per four, rounded up - the estimate the
   * agent bounds its sections by (`maxPromptSectionTokens`), and a middleware bounds its own section by.
   */
  def estimatedTokens(chars: Long): Long = (chars + 3) / 4

  /** The most characters a section of `tokens` estimated tokens holds: cut a section's text to this to bound it. */
  def charsFor(tokens: Int): Long = tokens.toLong * 4

/**
 * One tool call as a tool wrapper sees it, after its arguments were validated and decoded. It is
 * read-only: a wrapper cannot change a call's tool, arguments or resources, so it has no setters and `wrapToolCall`'s
 * `next` takes no request (#2795) - the arguments a tool runs with are the ones that were validated, and the
 * resources a permission was decided on are the ones the call touches. A test builds one with
 * `ToolCallRequest(spec, call)`. `resources` are what the call touches, from its decoded arguments and its tool's
 * `AgentToolSpec.withResources`; never empty, since a call that declares none touches its tool's `defaultResource`.
 */
@Experimental
final case class ToolCallRequest private (spec: AgentToolSpec[?], call: ToolCall, resources: Vector[ToolResource]):

  /** The call's id, typed; core's `ToolCall` keeps a string. */
  def toolCallId: ToolCallId = ToolCallId(call.id)

  /** The called tool's name, typed. */
  def toolName: ToolName = ToolName(call.name)

@Experimental
object ToolCallRequest:

  /** A request whose call touches `resources`, or the tool's `defaultResource` when that is empty. */
  def apply(spec: AgentToolSpec[?], call: ToolCall, resources: Vector[ToolResource] = Vector.empty): ToolCallRequest =
    new ToolCallRequest(spec, call, orDefault(spec, resources))

  private def orDefault(spec: AgentToolSpec[?], resources: Vector[ToolResource]): Vector[ToolResource] =
    if resources.isEmpty then Vector(spec.defaultResource) else resources

/**
 * A cross-cutting concern - approval, guardrails, logging, retry, rate limits - around an agent
 * run, its model calls and its tool calls. Every hook passes through by default; override only
 * those the concern needs.
 *
 * Middleware run as a stack, ordered by registration and by [[runsBefore]] and
 * [[runsAfter]]: the first in stack order is the outermost wrapper and runs `beforeAgent` first;
 * unwinding, and `afterAgent`, run in reverse.
 *
 * Failure follows one model for every hook (#2795). A hook fails by value: a `Left` from a hook that returns
 * `Result`, a `ToolOutcome.Fatal` from `wrapToolCall`, which returns what a tool returns. A hook that throws has
 * failed with that value holding `GraphError.MiddlewareFailed`, which names the middleware; a thrown cancellation
 * cancels the run. What a failure does depends on where the hook runs:
 *
 *   - at a turn's boundary (`beforeAgent`, `afterAgent`) it blocks the turn: nothing of the turn is kept, the
 *     thread stays usable, there is nothing to recover, and `agent.run` returns the error as its `Left` - or, for a
 *     [[block]], ends the turn `AgentStatus.Blocked`;
 *   - inside a step (`promptSections`, `wrapModelCall`, `wrapToolCall`) it fails the run: the thread keeps its
 *     last checkpoint, and `recover` runs the step again.
 *
 * `ToolOutcome.Error` is not a failure: it is a tool call's result, which the model reads.
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
 * [[org.llm4s.agent.graph.middleware.AgentMiddleware.Asking AgentMiddleware.Asking]].
 */
@Experimental
trait AgentMiddleware:

  /** This middleware's identifier, unique in its stack; must match `[a-zA-Z0-9_-]{1,64}`. */
  def id: MiddlewareId

  /**
   * Middleware this one must run outside of (wrap): the stack is arranged so. Each is named by id or by type
   * ([[MiddlewareRef]]) and must be in the same stack, unless the reference is optional (`ifPresent`).
   */
  def runsBefore: Set[MiddlewareRef] = Set.empty

  /** Middleware this one must run inside of (be wrapped by), named and arranged as [[runsBefore]]'s are. */
  def runsAfter: Set[MiddlewareRef] = Set.empty

  /**
   * Where this middleware must run relative to `other`, when both are present; `None`, the default, when it does not
   * matter. A requirement is checked, never arranged: `AgentBuilder.build()` refuses a
   * stack in which this middleware does not run outside `other`, and a [[org.llm4s.agent.graph.middleware.Placement.OutsideAtBoundary Placement.OutsideAtBoundary]] is checked
   * across an agent family too, which [[runsBefore]] cannot arrange. Register the two in the right order, or name
   * `other` in [[runsBefore]]. `other` is the middleware itself, so match it by its type, as a [[MiddlewareRef]]
   * names one, or by `other.id`.
   */
  def placement(@unused other: AgentMiddleware): Option[Placement] = None

  /**
   * The state keys this middleware's hooks may write: an update from `beforeAgent`, `wrapModelCall` or
   * `afterAgent`, or one `wrapToolCall` adds to a `Success`. A hook's write to any other key fails the run.
   */
  def writes: Set[StateKey[?, ?]] = Set.empty

  /** Tools this middleware contributes; they join the loop's tool set and are validated like any other. */
  def tools: Vector[AgentTool[?]] = Vector.empty

  /**
   * Ends the turn as blocked by this middleware: return it from `beforeAgent` or `afterAgent`. The turn ends
   * `AgentStatus.Blocked(id, reason)` - an outcome of `agent.run`, not its `Left` - exactly as a guardrail's block
   * does with the guardrail's name: an input block stores nothing of the turn, an output block removes it, the
   * thread stays usable, and a durable `GuardrailBlocked` event names this middleware and never the reason. Any other
   * `Left` from a boundary hook blocks the turn too, but reaches the caller as the `Left` of `agent.run`.
   *
   * It blocks only at a boundary: returned from `promptSections` or `wrapModelCall` it is a failure of the run like
   * any other `Left`.
   */
  final protected def block(reason: String): Result[Nothing] = Left(GuardrailBlocked(id.value, reason))

  /** The codecs of the question this middleware asks and the answer it takes; only [[org.llm4s.agent.graph.middleware.AgentMiddleware.Asking AgentMiddleware.Asking]] declares one. */
  private[graph] def declaredQuestion: Option[ToolQuestion[?, ?]] = None

  /**
   * Sees the run's input before anything else runs: returns it, possibly changed, with an update to this
   * middleware's `writes` that the turn's first model call sees. A `Left` blocks the turn; [[block]] blocks it as an
   * outcome, `AgentStatus.Blocked`.
   */
  def beforeAgent(input: String, @unused context: ModelContext): Result[BoundaryResult] = Right(BoundaryResult(input))

  /**
   * Sees the run's final answer: returns it, possibly changed, with an update to this middleware's `writes`. A `Left`
   * blocks the turn, removing it; [[block]] blocks it as an outcome, `AgentStatus.Blocked`.
   */
  def afterAgent(answer: String, @unused context: ModelContext): Result[BoundaryResult] = Right(BoundaryResult(answer))

  /**
   * Content for the system prompt of each model request: collected before every model step from the committed
   * state, in stack order, and never stored. Each middleware bounds its own; the agent bounds them all together
   * (`maxPromptSectionTokens`). `Left` fails the run.
   */
  def promptSections(@unused context: ModelContext): Result[Vector[PromptSection]] = Right(Vector.empty)

  /**
   * Wraps one model call. A wrapper may rewrite the request (inject a note, filter tools), call
   * `next` more than once (retry, fallback), transform its response, add an update to its `writes`
   * ([[org.llm4s.agent.graph.middleware.ModelResponse!.addUpdate ModelResponse.addUpdate]]), or return `Left`, which fails the run. Only an
   * [[org.llm4s.agent.graph.middleware.AgentMiddleware.Asking AgentMiddleware.Asking]] wrapper suspends, by asking its question. Every call `next` makes is
   * counted in the thread's usage, whichever response the wrapper returns.
   *
   * Filtering `ModelRequest.tools` shapes what the model is offered and is not a permission
   * control: a tool the model calls anyway still runs through `wrapToolCall`, where denial belongs.
   */
  def wrapModelCall(request: ModelRequest, @unused context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] = next(request)

  /**
   * Wraps one tool call, after its arguments were validated. A wrapper denies with
   * `ToolOutcome.Error("Denied: ...")`, asks for approval with `NeedsApproval(reason)` (unless
   * `context.approved`), fails the run with `Fatal` - its `Left`: the run fails with `GraphError.ToolFailed` - and
   * short-circuits by not calling `next`. It may call `next` more than once (retry) and transform the outcome `next`
   * returns, adding an update to its `writes` with `ToolOutcome.addUpdate`. `next` takes no request: the call is
   * read-only.
   */
  def wrapToolCall(@unused request: ToolCallRequest, @unused context: ToolContext)(
    next: () => ToolOutcome
  ): ToolOutcome = next()

/**
 * Where a middleware must run relative to another ([[org.llm4s.agent.graph.middleware.AgentMiddleware.placement AgentMiddleware.placement]]): a position its correctness depends
 * on, which the stack checks rather than leaving to the documentation.
 */
@Experimental
enum Placement:

  /** Why the placement is required, for the refusal. */
  def reason: String

  /** This middleware must wrap the other in one agent's stack: every hook of this one runs outside the other's. */
  case Outside(reason: String)

  /**
   * As `Outside`, and across an agent family too, for a requirement about `beforeAgent` and `afterAgent`: the root
   * agent's boundary hooks run outside the active agent's own, so this middleware on a handoff target is refused when
   * the root has the other, whatever the order.
   */
  case OutsideAtBoundary(reason: String)

@Experimental
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

    /** [[answered]] for a boundary or model hook's context. */
    final protected def answered(context: ModelContext): Option[Resumed[Q, Ans]] = answered(context.run)

/**
 * A middleware's question on its way out of a hook, from [[org.llm4s.agent.graph.middleware.AgentMiddleware.Asking.ask AgentMiddleware.Asking.ask]]: the agent loop
 * suspends the run with it. A wrapper that sees it as `next`'s result passes it on. Outside the loop it
 * is an error: a question nobody can answer.
 */
final private[agent] case class MiddlewareAsked private[graph] (middleware: MiddlewareId, question: ujson.Value)
    extends LLMError
    with NonRecoverableError:
  override val message: String = s"Middleware '${middleware.value}' asked a question"
