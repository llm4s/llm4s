package org.llm4s.agent.graph.tool

import org.llm4s.agent.graph.{ RunContext, StateKey, StateUpdate, ThreadState, ToolCallId }
import org.llm4s.error.LLMError
import org.llm4s.toolapi.{ SchemaDefinition, ToolFunction }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.annotation.unused

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
 */
final case class AgentToolSpec[A] private (
  name: String,
  description: String,
  schema: SchemaDefinition[A],
  validateDecoded: A => Result[Unit],
  question: Option[ToolQuestion[?, ?]],
  hints: ToolHints
)(using val codec: ReadWriter[A]):

  /** Adds a check on the decoded arguments, replacing any earlier one. */
  def withValidation(check: A => Result[Unit]): AgentToolSpec[A] = copy(validateDecoded = check)(using codec)

  /** Declares what the tool is likely to do, replacing any earlier hints. */
  def withHints(hints: ToolHints): AgentToolSpec[A] = copy(hints = hints)(using codec)

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
    new AgentToolSpec(name, description, schema, _ => Right(()), None, ToolHints.default)

/**
 * What a tool is likely to do, with the meanings of the MCP tool annotations and their conservative
 * defaults. These are hints a middleware reads (for example to ask approval for a tool that is not
 * read-only), not guarantees: nothing checks that a tool behaves as it says.
 *
 * @param readOnly MCP `readOnlyHint`: the tool does not modify its environment
 * @param destructive MCP `destructiveHint`: the tool may perform destructive updates rather than
 *                    only additive ones; meaningful only when it is not `readOnly`
 * @param idempotent MCP `idempotentHint`: calling it again with the same arguments has no further
 *                   effect; meaningful only when it is not `readOnly`
 * @param openWorld MCP `openWorldHint`: the tool may interact with an open world of external
 *                  entities, such as the web, rather than a closed domain
 */
final case class ToolHints private (readOnly: Boolean, destructive: Boolean, idempotent: Boolean, openWorld: Boolean):
  def withReadOnly(v: Boolean): ToolHints    = copy(readOnly = v)
  def withDestructive(v: Boolean): ToolHints = copy(destructive = v)
  def withIdempotent(v: Boolean): ToolHints  = copy(idempotent = v)
  def withOpenWorld(v: Boolean): ToolHints   = copy(openWorld = v)

object ToolHints:
  def apply(
    readOnly: Boolean = false,
    destructive: Boolean = true,
    idempotent: Boolean = false,
    openWorld: Boolean = true
  ): ToolHints = new ToolHints(readOnly, destructive, idempotent, openWorld)

  /** Not read-only, destructive, not idempotent, open-world: the MCP defaults. */
  val default: ToolHints = ToolHints()

/** The codecs of a tool's question and of the answer it takes. */
final case class ToolQuestion[Q, Ans](questionCodec: ReadWriter[Q], answerCodec: ReadWriter[Ans])

/**
 * What a tool call knows: the run, the call's id, the thread state it may read, and whether the
 * call has been approved. Built with `ToolContext(...)`; a field added later gets a default there
 * and a `with*` setter, so existing callers keep compiling.
 */
final case class ToolContext private (run: RunContext, toolCallId: ToolCallId, state: ThreadState, approved: Boolean):
  def withRun(r: RunContext): ToolContext         = copy(run = r)
  def withToolCallId(id: ToolCallId): ToolContext = copy(toolCallId = id)
  def withState(s: ThreadState): ToolContext      = copy(state = s)
  def withApproved(a: Boolean): ToolContext       = copy(approved = a)

object ToolContext:
  def apply(run: RunContext, toolCallId: ToolCallId, state: ThreadState, approved: Boolean = false): ToolContext =
    new ToolContext(run, toolCallId, state, approved)

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
  final private[tool] class FromToolFunction(val function: ToolFunction[?, ?]) extends AgentTool[ujson.Value]:
    val spec: AgentToolSpec[ujson.Value] = AgentToolSpec.unchecked[ujson.Value](
      function.name,
      function.description,
      function.schema.asInstanceOf[SchemaDefinition[ujson.Value]]
    )

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
