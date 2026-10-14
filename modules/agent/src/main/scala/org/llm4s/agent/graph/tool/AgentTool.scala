package org.llm4s.agent.graph.tool

import org.llm4s.agent.graph.{ EffectKey, IdempotencyKey, RunContext, StateKey, StateUpdate, ThreadState, ToolCallId }
import org.llm4s.error.LLMError
import org.llm4s.toolapi.{ SchemaDefinition, ToolFunction, ToolHints }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.annotation.unused
import scala.concurrent.duration.{ Duration, FiniteDuration }

/**
 * What a tool tells the model and the loop: its name, description and argument schema, how its
 * arguments decode, an optional check on the decoded value, and the question it may ask.
 *
 * `name` must match `[a-zA-Z0-9_-]{1,64}`: `apply` throws `IllegalArgumentException` otherwise, and
 * [[ToolSet.of]] refuses one too, so a spec built another way is still checked.
 *
 * Arguments are validated against [[argumentSchema]], the schema rendered non-strict, which is what
 * core's Anthropic, Gemini and Vertex AI clients send. A call from a client that sends tools strict
 * (OpenAI and the OpenAI-compatible ones) carries every property, which that schema also accepts.
 *
 * @param validateDecoded a check on the decoded arguments; `Left` becomes an error the model sees
 * @param question the question this tool may ask; set by [[AgentTool.Asking]]
 * @param hints what the tool is likely to do, for a middleware to read; see [[ToolHints]]
 * @param timeout how long one run of the tool may take; see [[withTimeout]]
 * @param resourcesOf what a call touches, from its decoded arguments; see [[withResources]]
 * @param locksOf the locks a call holds, from its decoded arguments, when the tool declares any; see [[withLocks]]
 */
final case class AgentToolSpec[A] private (
  name: String,
  description: String,
  schema: SchemaDefinition[A],
  validateDecoded: A => Result[Unit],
  question: Option[ToolQuestion[?, ?]],
  hints: ToolHints,
  timeout: Option[FiniteDuration],
  resourcesOf: A => Vector[ToolResource],
  locksOf: Option[A => Set[LockKey]]
)(using val codec: ReadWriter[A]):

  /** Adds a check on the decoded arguments, replacing any earlier one. */
  def withValidation(check: A => Result[Unit]): AgentToolSpec[A] = copy(validateDecoded = check)(using codec)

  /** Declares what the tool is likely to do, replacing any earlier hints. */
  def withHints(hints: ToolHints): AgentToolSpec[A] = copy(hints = hints)(using codec)

  /**
   * Bounds each run of the tool - its `execute`, or its `resume` after a question - to `timeout`. A run
   * past it is interrupted, and the call's outcome is `ToolOutcome.Error("Tool '<name>' timed out after
   * <n> ms")`, an error the model sees; a wrapper that retries gets a fresh bound. The tool runs on a
   * thread of its own, which is joined before the call goes on, so a tool that ignores its interrupt
   * holds its batch past the timeout: block interruptibly. A cancelled run is still a cancellation,
   * which records nothing. `timeout` must be positive.
   */
  def withTimeout(timeout: FiniteDuration): AgentToolSpec[A] =
    require(timeout > Duration.Zero, s"tool '$name': timeout must be positive, was $timeout")
    copy(timeout = Some(timeout))(using codec)

  /**
   * Declares what a call touches, from its decoded arguments, replacing the default
   * `Vector(ToolResource(Operation.Invoke, name))`; an empty vector is checked as that default. A
   * `PermissionMiddleware` reads them from `ToolCallRequest.resources`. A function that throws refuses
   * the call as invalid arguments.
   *
   * They are the call's whole envelope, over every run of it: what `execute` may touch, and what `resume`
   * may touch after a question, whatever the answer. The runtime checks them; it cannot see what the
   * tool then does. So a tool whose answer chooses its target (asking for a missing destination)
   * declares a resource covering every target it may pick (`Write /out/&#42;&#42;`), and refuses an answer
   * outside it as an error.
   */
  def withResources(resources: A => Vector[ToolResource]): AgentToolSpec[A] =
    copy(resourcesOf = resources)(using codec)

  /**
   * Declares the locks a call holds, from its decoded arguments; by default it holds none. When one
   * model message has several calls, a call whose locks conflict with an earlier call's (one
   * [[LockKey]] a prefix of the other) waits for it: it runs in a later superstep, in call order, and
   * sees the state the earlier call committed. Calls without conflicting locks run concurrently, as
   * before. Each further round of calls costs two supersteps of `RunBudgets.maxSupersteps`. A function
   * that throws gives the call the root lock, which conflicts with every lock.
   *
   * As with [[withResources]], they cover every run of the call, `resume` included: a tool whose answer
   * chooses its target locks a key above every target it may pick, and an approval edit may narrow the
   * locks but not take one they do not cover (it is refused).
   */
  def withLocks(locks: A => Set[LockKey]): AgentToolSpec[A] = copy(locksOf = Some(locks))(using codec)

  /** The resource a call of this tool touches when it declares none: invoking the tool. */
  def defaultResource: ToolResource = ToolResource(Operation.Invoke, name)

  /** Declares the question this tool asks and the answer it takes; only [[AgentTool.Asking]] calls it. */
  private[tool] def withQuestion[Q: ReadWriter, Ans: ReadWriter]: AgentToolSpec[A] =
    copy(question = Some(ToolQuestion(summon[ReadWriter[Q]], summon[ReadWriter[Ans]])))(using codec)

  /**
   * The schema arguments are validated against, `schema.toJsonSchema(strict = false)`, rendered
   * once: only the required properties are required, so a call that omits an optional one is
   * accepted, as core's non-strict clients allow. [[ToolSet.of]] checks its keywords. Do not mutate it.
   */
  lazy val argumentSchema: ujson.Value = schema.toJsonSchema(false)

  private lazy val strictSchema: ujson.Value = schema.toJsonSchema(true)

  /**
   * The tool definition in OpenAI's strict format, the shape of `ToolFunction.toOpenAITool(true)`,
   * for callers that send definitions themselves; core's clients receive [[ToolSet.toolFunctions]]
   * and render their own from `schema`. Its parameters are a fresh copy each time, so a caller that
   * edits one definition cannot change another.
   */
  def toolDefinition: ujson.Value =
    ujson.Obj(
      "type" -> ujson.Str("function"),
      "function" -> ujson.Obj(
        "name"        -> ujson.Str(name),
        "description" -> ujson.Str(description),
        "parameters"  -> ujson.copy(strictSchema),
        "strict"      -> ujson.Bool(true)
      )
    )

object AgentToolSpec:
  private val ValidName = "[a-zA-Z0-9_-]{1,64}".r

  private[tool] def isValidName(name: String): Boolean = ValidName.matches(name)

  /** Throws `IllegalArgumentException` for a name that does not match `[a-zA-Z0-9_-]{1,64}`. */
  def apply[A: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[A]
  ): AgentToolSpec[A] =
    require(isValidName(name), s"Invalid tool name '$name': must match [a-zA-Z0-9_-]{1,64}")
    unchecked(name, description, schema)

  /** Builds a spec without checking its name; [[ToolSet.of]] reports an invalid one. */
  private[tool] def unchecked[A: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[A]
  ): AgentToolSpec[A] =
    new AgentToolSpec(
      name,
      description,
      schema,
      _ => Right(()),
      None,
      ToolHints.default,
      None,
      _ => Vector(ToolResource(Operation.Invoke, name)),
      None
    )

/** The codecs of a tool's question and of the answer it takes. */
final case class ToolQuestion[Q, Ans](questionCodec: ReadWriter[Q], answerCodec: ReadWriter[Ans])

/**
 * What a tool call knows: the run, the call's id, the call's [[IdempotencyKey]], the thread state
 * it may read, and whether the call has been approved. Built with `ToolContext(...)`; a field added
 * later gets a default there and a `with*` setter, so existing callers keep compiling.
 *
 * A tool with an external side effect passes `idempotencyKey` to the system it calls (an
 * `Idempotency-Key` header, a client token, a unique column), because the runtime may run the
 * same call more than once: a retrying wrapper, `recover` after a failure, cancellation or crash,
 * and an approval each run it again with the same key. A call can even run twice at once: a worker
 * paused past its claim's lease may still be running it when another runtime takes the thread over
 * and runs it too, with the same key. A system that can fence writers can refuse the stale worker:
 * `run.position.fencingToken` is the run's claim token, which grows with every claim on the thread,
 * so the system keeps the highest token it has seen and refuses a lower one.
 */
final case class ToolContext private (
  run: RunContext,
  toolCallId: ToolCallId,
  idempotencyKey: IdempotencyKey,
  state: ThreadState,
  approved: Boolean,
  /**
   * The phase of the call this context runs: `None` for `execute` (also when an approval or a
   * wrapper runs it again), or the key of one answered resume (design §4.24). Set by the loop only.
   */
  private[graph] val phase: Option[IdempotencyKey]
):
  def withRun(r: RunContext): ToolContext                  = copy(run = r)
  def withToolCallId(id: ToolCallId): ToolContext          = copy(toolCallId = id)
  def withIdempotencyKey(key: IdempotencyKey): ToolContext = copy(idempotencyKey = key)
  def withState(s: ThreadState): ToolContext               = copy(state = s)
  def withApproved(a: Boolean): ToolContext                = copy(approved = a)

  /**
   * The key of one external effect of this phase of the call - `operation`, such as `file.edit`, on
   * what `discriminator` names (a path) - for [[org.llm4s.agent.graph.ReplayGuard]] (design §4.24). In
   * `execute` its base is [[idempotencyKey]], so it is the same in every run of `execute`: a retrying
   * wrapper, `recover`, and a re-run after approval. An answered `resume` is another phase of the call,
   * keyed by the call, the phase that asked, the question and the answer: the same in every run of that
   * resume (`recover` of a failed one included), different from `execute` and from every other resume
   * of the call, a resume that asks again included.
   */
  def effectKey(operation: String, discriminator: String*): EffectKey =
    run.effectKeyUnder(phase.getOrElse(idempotencyKey), operation, discriminator)

object ToolContext:
  def apply(
    run: RunContext,
    toolCallId: ToolCallId,
    idempotencyKey: IdempotencyKey,
    state: ThreadState,
    approved: Boolean = false
  ): ToolContext =
    new ToolContext(run, toolCallId, idempotencyKey, state, approved, None)

  /** The context of an answered resume of the call, whose effect keys are its phase's (see `effectKey`). */
  private[graph] def resuming(
    run: RunContext,
    toolCallId: ToolCallId,
    idempotencyKey: IdempotencyKey,
    state: ThreadState,
    approved: Boolean,
    phase: IdempotencyKey
  ): ToolContext =
    new ToolContext(run, toolCallId, idempotencyKey, state, approved, Some(phase))

/** What a tool call produced, as data. */
enum ToolOutcome:

  /** The call's result for the model, and an update to keys the tool declares in `writes`. */
  case Success(content: ujson.Value, update: StateUpdate = StateUpdate.empty)

  /** A tool-level failure: the model sees `message`; the run continues. */
  case Error(message: String)

  /** The call needs approval before it runs; the loop suspends at its approval node. */
  case NeedsApproval(reason: String)

  /** A question for the caller, of the type the tool declares; the answer arrives through `resume`. */
  case Ask[Q](question: Q)

  /** An infrastructure failure: the run fails with `GraphError.ToolFailed`. */
  case Fatal(error: LLMError)

/**
 * A tool the agent loop can call: typed arguments, validated against [[AgentToolSpec.argumentSchema]]
 * and decoded with its codec before `execute` runs. A tool writes only the state keys in `writes`.
 */
trait AgentTool[A]:
  def spec: AgentToolSpec[A]

  /** The state keys this tool's `Success` updates may touch. */
  def writes: Set[StateKey[?, ?]] = Set.empty

  def execute(args: A, context: ToolContext): ToolOutcome

  /** Continues a call after its question was answered; only [[AgentTool.Asking]] takes answers. */
  private[tool] def resumeErased(
    @unused args: A,
    @unused question: Any,
    @unused answer: Any,
    @unused context: ToolContext
  ): ToolOutcome =
    ToolOutcome.Error(s"tool '${spec.name}' does not take answers")

object AgentTool:

  /** A tool from a spec and a function; it never asks questions. */
  def apply[A](spec: AgentToolSpec[A], writes: Set[StateKey[?, ?]] = Set.empty)(
    run: (A, ToolContext) => ToolOutcome
  ): AgentTool[A] =
    val (s, w) = (spec, writes)
    new AgentTool[A]:
      val spec: AgentToolSpec[A]                              = s
      override val writes: Set[StateKey[?, ?]]                = w
      def execute(args: A, context: ToolContext): ToolOutcome = run(args, context)

  /**
   * Adapts a core `ToolFunction`: the spec takes its name, description and schema (as a
   * `SchemaDefinition[ujson.Value]` - the type parameter is a phantom), and arguments arrive as
   * raw JSON. `Right(json)` becomes `Success(json)`, `Left(error)` an `Error` with its formatted
   * message. It writes no state and never asks. Its name is not checked here; [[ToolSet.of]]
   * refuses an invalid one.
   */
  def fromToolFunction(tool: ToolFunction[?, ?]): AgentTool[ujson.Value] = new FromToolFunction(tool)

  /**
   * As `fromToolFunction(tool)`, with `hints` declared on the spec: for a tool whose hints are known
   * from elsewhere, such as the annotations an MCP server advertises for it (`llm4s-mcp` reads them).
   */
  def fromToolFunction(tool: ToolFunction[?, ?], hints: ToolHints): AgentTool[ujson.Value] =
    new FromToolFunction(tool, hints)

  /**
   * Continues `tool`'s call `args` with `question` answered by `answer`, both decoded with the codecs
   * of `tool.spec.question`; the loop's entry point to [[AgentTool.resumeErased]].
   */
  private[graph] def resumeWith[A](
    tool: AgentTool[A],
    args: A,
    question: Any,
    answer: Any,
    context: ToolContext
  ): ToolOutcome =
    tool.resumeErased(args, question, answer, context)

  /** Keeps the original function, so [[ToolSet.toolFunctions]] hands it back unchanged. */
  final private[tool] class FromToolFunction(val function: ToolFunction[?, ?], hints: ToolHints = ToolHints.default)
      extends AgentTool[ujson.Value]:
    val spec: AgentToolSpec[ujson.Value] = AgentToolSpec
      .unchecked[ujson.Value](
        function.name,
        function.description,
        function.schema.asInstanceOf[SchemaDefinition[ujson.Value]]
      )
      .withHints(hints)

    def execute(args: ujson.Value, context: ToolContext): ToolOutcome =
      function.execute(args) match
        case Right(json) => ToolOutcome.Success(json)
        case Left(error) => ToolOutcome.Error(error.getFormattedMessage)

  /**
   * A tool that asks questions of type `Q` and takes answers of type `Ans`. Its spec is `base`
   * with that question declared, so the loop encodes `Ask(q)` and decodes the answer with these
   * codecs, then calls `resume`.
   */
  abstract class Asking[A, Q: ReadWriter, Ans: ReadWriter](base: AgentToolSpec[A]) extends AgentTool[A]:
    final val spec: AgentToolSpec[A] = base.withQuestion[Q, Ans]

    /** Continues the call `args` after `question` was answered with `answer`. */
    def resume(args: A, question: Q, answer: Ans, context: ToolContext): ToolOutcome

    /** Asks `question`, of the declared type; the answer arrives through `resume`. */
    final protected def ask(question: Q): ToolOutcome = ToolOutcome.Ask(question)

    // The loop decodes question and answer with this spec's codecs, so the casts hold.
    final override private[tool] def resumeErased(
      args: A,
      question: Any,
      answer: Any,
      context: ToolContext
    ): ToolOutcome =
      resume(args, question.asInstanceOf[Q], answer.asInstanceOf[Ans], context)
