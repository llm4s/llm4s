package org.llm4s.agent.graph.tool

import org.llm4s.agent.graph.{ RunContext, StateKey, StateUpdate, ThreadState }
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
 * @param strict whether the schema is rendered in strict mode (every property required, no others)
 * @param validateDecoded a check on the decoded arguments; `Left` becomes an error the model sees
 * @param question the question this tool may ask; set by [[AgentTool.Asking]]
 */
final case class AgentToolSpec[A] private (
  name: String,
  description: String,
  schema: SchemaDefinition[A],
  strict: Boolean,
  validateDecoded: A => Result[Unit],
  question: Option[ToolQuestion[?, ?]]
)(using val codec: ReadWriter[A]):

  /** Adds a check on the decoded arguments, replacing any earlier one. */
  def withValidation(check: A => Result[Unit]): AgentToolSpec[A] = copy(validateDecoded = check)(using codec)

  /** Declares the question this tool asks and the answer it takes; see [[AgentTool.Asking]]. */
  def withQuestion[Q: ReadWriter, Ans: ReadWriter]: AgentToolSpec[A] =
    copy(question = Some(ToolQuestion(summon[ReadWriter[Q]], summon[ReadWriter[Ans]])))(using codec)

  /** The schema sent to the provider, `schema.toJsonSchema(strict)`, rendered once; arguments are validated against it. */
  lazy val providerSchema: ujson.Value = schema.toJsonSchema(strict)

  /** The provider-facing tool definition, in the shape of `ToolFunction.toOpenAITool(strict)`. */
  def toolDefinition: ujson.Value =
    ujson.Obj(
      "type" -> ujson.Str("function"),
      "function" -> ujson.Obj(
        "name"        -> ujson.Str(name),
        "description" -> ujson.Str(description),
        "parameters"  -> providerSchema,
        "strict"      -> ujson.Bool(strict)
      )
    )

object AgentToolSpec:
  private val ValidName = "[a-zA-Z0-9_-]{1,64}".r

  private[tool] def isValidName(name: String): Boolean = ValidName.matches(name)

  /** Throws `IllegalArgumentException` for a name that does not match `[a-zA-Z0-9_-]{1,64}`. */
  def apply[A: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[A],
    strict: Boolean = true
  ): AgentToolSpec[A] =
    require(isValidName(name), s"Invalid tool name '$name': must match [a-zA-Z0-9_-]{1,64}")
    unchecked(name, description, schema, strict)

  /** Builds a spec without checking its name; [[ToolSet.of]] reports an invalid one. */
  private[tool] def unchecked[A: ReadWriter](
    name: String,
    description: String,
    schema: SchemaDefinition[A],
    strict: Boolean
  ): AgentToolSpec[A] =
    new AgentToolSpec(name, description, schema, strict, _ => Right(()), None)

/** The codecs of a tool's question and of the answer it takes. */
final case class ToolQuestion[Q, Ans](questionCodec: ReadWriter[Q], answerCodec: ReadWriter[Ans])

/**
 * What a tool call knows: the run, the call's id, the thread state it may read, and whether the
 * call has been approved.
 */
final case class ToolContext(run: RunContext, toolCallId: String, state: ThreadState, approved: Boolean)

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
 * A tool the agent loop can call: typed arguments, validated against [[AgentToolSpec.providerSchema]]
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
   * `SchemaDefinition[ujson.Value]` - the type parameter is a phantom) with `strict = true`, and
   * arguments arrive as raw JSON. `Right(json)` becomes `Success(json)`, `Left(error)` an `Error`
   * with its formatted message. It writes no state and never asks. Its name is not checked here;
   * [[ToolSet.of]] refuses an invalid one.
   */
  def fromToolFunction(tool: ToolFunction[?, ?]): AgentTool[ujson.Value] =
    val spec = AgentToolSpec.unchecked[ujson.Value](
      tool.name,
      tool.description,
      tool.schema.asInstanceOf[SchemaDefinition[ujson.Value]],
      strict = true
    )
    AgentTool(spec) { (args, _) =>
      tool.execute(args) match
        case Right(json) => ToolOutcome.Success(json)
        case Left(error) => ToolOutcome.Error(error.getFormattedMessage)
    }

  /**
   * A tool that asks questions of type `Q` and takes answers of type `Ans`. Its spec is `base`
   * with that question declared, so the loop encodes `Ask(q)` and decodes the answer with these
   * codecs, then calls `resume`.
   */
  abstract class Asking[A, Q: ReadWriter, Ans: ReadWriter](base: AgentToolSpec[A]) extends AgentTool[A]:
    final val spec: AgentToolSpec[A] = base.withQuestion[Q, Ans]

    /** Continues the call `args` after `question` was answered with `answer`. */
    def resume(args: A, question: Q, answer: Ans, context: ToolContext): ToolOutcome

    // The loop decodes question and answer with this spec's codecs, so the casts hold.
    final override private[tool] def resumeErased(
      args: A,
      question: Any,
      answer: Any,
      context: ToolContext
    ): ToolOutcome =
      resume(args, question.asInstanceOf[Q], answer.asInstanceOf[Ans], context)
