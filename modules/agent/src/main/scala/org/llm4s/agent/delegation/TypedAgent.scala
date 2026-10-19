package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentId, AgentResult, AgentStatus }
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, RunConfig, RunOrigin, ThreadId, ToolCallId }
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.{ ToolContext, ToolSet }
import org.llm4s.agent.graph.toolloop.AgentInput
import org.llm4s.agent.harness.HarnessProfile
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, UsageSummary }
import org.llm4s.toolapi.SchemaDefinition
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * A subagent's definition (design 4.44): an agent that takes a typed input `I` and gives back a typed report `O`, run
 * on a thread of its own, with a fresh conversation, its own tools and middleware only, and nothing of its parent's
 * (rule 15). A parent offers it with `AgentBuilder.withSubagents`, which compiles it with [[bind]] at the parent's
 * `build()`; [[BoundAgent.run]] runs one with no parent.
 *
 * Only typed data crosses (rule 17): the parent sends `I`, rendered as the child's first user message - by default a
 * [[TaskRequest]]'s description as it is, any other input as its canonical JSON under one line naming the subagent
 * ([[withInputText]] replaces that) - and gets back `O`, decoded from the child's answer. For a [[TypedAgent.text]]
 * subagent the answer itself is the report; otherwise the child answers in `O`'s schema (`AgentBuilder.withOutput`).
 *
 * A definition holds clients and functions, not data: it is immutable, and every `with*` returns a new one. Its id
 * (`[a-zA-Z0-9_-]{1,52}`) and description (not blank) are checked when it is bound.
 */
@Experimental
final class TypedAgent[I, O] private (
  rawId: String,
  val description: String,
  private[delegation] val inputSchema: SchemaDefinition[I],
  private[delegation] val output: Either[String => O, SchemaDefinition[O]],
  private[delegation] val takesTaskRequest: Boolean,
  systemPrompt: Option[String],
  tools: Option[ToolSet],
  middleware: Vector[AgentMiddleware],
  harness: Option[HarnessProfile],
  model: Option[(LLMClient, CompletionOptions)],
  maxSteps: Option[Int],
  render: Option[I => String],
  val failure: ChildFailure
)(using private[delegation] val inputCodec: ReadWriter[I], private[delegation] val outputCodec: ReadWriter[O]):

  private def copy(
    systemPrompt: Option[String] = systemPrompt,
    tools: Option[ToolSet] = tools,
    middleware: Vector[AgentMiddleware] = middleware,
    harness: Option[HarnessProfile] = harness,
    model: Option[(LLMClient, CompletionOptions)] = model,
    maxSteps: Option[Int] = maxSteps,
    render: Option[I => String] = render,
    failure: ChildFailure = failure
  ): TypedAgent[I, O] =
    new TypedAgent(
      rawId,
      description,
      inputSchema,
      output,
      takesTaskRequest,
      systemPrompt,
      tools,
      middleware,
      harness,
      model,
      maxSteps,
      render,
      failure
    )

  /** The subagent's id: its agent id, and the `subagent_type` or the `task_<id>` tool name the parent model uses. */
  def id: AgentId = AgentId.unsafe(rawId)

  /** The child's system prompt; refused beside [[withHarness]], as `AgentBuilder.withSystemPrompt` is. */
  def withSystemPrompt(prompt: String): TypedAgent[I, O] = copy(systemPrompt = Some(prompt))

  /** The child's own tools, replacing any set before. It has no other tool of its parent's (rule 15). */
  def withTools(tools: ToolSet): TypedAgent[I, O] = copy(tools = Some(tools))

  /** Appends `middleware` to the child's own stack, in order. */
  def withMiddleware(middleware: AgentMiddleware*): TypedAgent[I, O] = copy(middleware = this.middleware ++ middleware)

  /** The child's own harness (`AgentBuilder.withHarness`), over the child's own thread. */
  def withHarness(profile: HarnessProfile): TypedAgent[I, O] = copy(harness = Some(profile))

  /** The client and options the child calls; by default the client of the agent it is bound to, with default options. */
  def withModel(client: LLMClient, options: CompletionOptions): TypedAgent[I, O] = copy(model = Some(client -> options))

  /** Model calls per child turn (`AgentBuilder.withMaxSteps`); at the limit the task is refused `StepLimitReached`. */
  def withMaxSteps(n: Int): TypedAgent[I, O] = copy(maxSteps = Some(n))

  /** Renders the input as the child's first user message, replacing the default (see the class description). */
  def withInputText(render: I => String): TypedAgent[I, O] = copy(render = Some(render))

  /** What the parent does when the child fails; [[ChildFailure.Report]] by default. */
  def withFailure(policy: ChildFailure): TypedAgent[I, O] = copy(failure = policy)

  /**
   * Compiles the definition onto `runtime`, calling `parentClient` unless [[withModel]] set a client. Refuses an
   * invalid id, a blank description, and whatever `AgentBuilder.build()` refuses for the child.
   */
  def bind(parentClient: LLMClient, runtime: GraphRuntime): Result[BoundAgent[I, O]] =
    if description.trim.isEmpty then Left(ValidationError("subagents", s"subagent '$rawId': the description is blank"))
    else
      val (client, options) = model.getOrElse(parentClient -> CompletionOptions())
      val base = Agent
        .builder(rawId, client)
        .withCompletionOptions(options)
        .withRuntime(runtime)
        .withMiddleware(middleware*)
      val prompted  = systemPrompt.fold(base)(base.withSystemPrompt)
      val tooled    = tools.fold(prompted)(prompted.withTools)
      val harnessed = harness.fold(tooled)(tooled.withHarness)
      val limited   = maxSteps.fold(harnessed)(harnessed.withMaxSteps)
      val typed     = output.fold(_ => limited, schema => limited.withOutput(schema)(using outputCodec))
      AgentId
        .of(rawId)
        .left
        .map(e => ValidationError("subagents", s"subagent id '$rawId': ${e.message}"))
        .flatMap(_ => typed.build())
        .map(new BoundAgent(this, _))

  /** The child's first user message for `input`. */
  private[delegation] def rendered(input: I): String =
    render.fold(
      input match
        case request: TaskRequest if takesTaskRequest => request.description
        case _ =>
          s"Task for subagent '$rawId', as JSON:\n${TaskTools.canonical(upickle.default.writeJs(input))}"
    )(_(input))

object TypedAgent:

  /**
   * A subagent taking `I`, described by `input` - the schema of its own `task_<id>` tool - and reporting `O`, in the
   * schema `output`, which the child answers in. An `I` of [[TaskRequest]], with its derived codec, is offered through
   * the shared `task` tool instead.
   */
  def apply[I: ReadWriter, O: ReadWriter](
    id: String,
    description: String,
    input: SchemaDefinition[I],
    output: SchemaDefinition[O]
  ): TypedAgent[I, O] =
    val takesTaskRequest = summon[ReadWriter[I]].eq(summon[ReadWriter[TaskRequest]])
    new TypedAgent[I, O](
      id,
      description,
      input,
      Right(output),
      takesTaskRequest,
      None,
      None,
      Vector.empty,
      None,
      None,
      None,
      None,
      ChildFailure.Report
    )

  /**
   * A subagent taking a [[TaskRequest]] and reporting its answer as text, offered through the shared `task` tool, in
   * the shape Deep Agents uses: `{subagent_type, description}`.
   */
  def text(id: String, description: String): TypedAgent[TaskRequest, String] =
    new TypedAgent[TaskRequest, String](
      id,
      description,
      TaskTools.requestSchema,
      Left(identity),
      true,
      None,
      None,
      Vector.empty,
      None,
      None,
      None,
      None,
      ChildFailure.Report
    )

/**
 * A [[TypedAgent]] compiled onto a runtime by [[TypedAgent.bind]]: what the `task` tools run, and what [[run]] runs
 * with no parent - from a graph node, or a test.
 */
@Experimental
final class BoundAgent[I, O] private[delegation] (val definition: TypedAgent[I, O], private[agent] val agent: Agent):

  /** The subagent's id. */
  def id: AgentId = agent.id

  /**
   * Runs the subagent once, on a new top-level thread of its runtime, with `config`, and returns its outcome: its
   * report, the refusal its status or its typed answer's failure gives, or its suspension. Any other failure is `Left`.
   * The thread, named by the outcome's `child.threadId` (depth 0, no tool call), stays in the runtime until deleted.
   */
  def run(input: I, config: RunConfig = RunConfig()): Result[TaskOutcome[O]] =
    val threadId = ThreadId(java.util.UUID.randomUUID().toString)
    val ref      = ChildRef(threadId, config.runId, agent.id, ToolCallId(""), 0)
    agent.run(threadId, definition.rendered(input), config) match
      case Right(result) => Right(outcome(result, ref.copy(runId = result.runId)))
      case Left(GraphError.OutputInvalid(all)) =>
        Right(TaskOutcome.Refused(ref, TaskRefusal.OutputInvalid(all), UsageSummary()))
      case Left(error) => Left(error)

  /**
   * Runs the subagent as the child of the parent call `ctx` (design 4.44), through the child runner: the outcome, and
   * for a failed child its error, which `ChildFailure.Fail` fails the parent with.
   */
  private[delegation] def delegated(
    input: I,
    origin: RunOrigin,
    config: RunConfig,
    ctx: ToolContext
  ): Result[(TaskOutcome[O], Option[LLMError])] =
    val threadId = ThreadId.child(origin.parent, origin.agent, origin.call)
    val ref      = ChildRef(threadId, config.runId, agent.id, ctx.toolCallId, origin.depth)
    ChildRunner.run(agent, origin, AgentInput(definition.rendered(input)), config, ctx).map {
      case ChildEnd.Ended(result, _) => outcome(result, ref.copy(runId = result.runId)) -> None
      case ChildEnd.Failed(GraphError.OutputInvalid(all), usage) =>
        TaskOutcome.Refused(ref, TaskRefusal.OutputInvalid(all), usage) -> None
      case ChildEnd.Failed(error, usage) =>
        TaskOutcome.Refused(ref, TaskRefusal.Failed(error.message), usage) -> Some(error)
    }

  private def outcome(result: AgentResult, ref: ChildRef): TaskOutcome[O] =
    result.status match
      case AgentStatus.Completed(answer) =>
        report(result, answer) match
          case Right(value) => TaskOutcome.Reported(value, ref, result.usage)
          case Left(problem) =>
            TaskOutcome.Refused(ref, TaskRefusal.OutputInvalid(Vector(problem.message)), result.usage)
      case AgentStatus.Blocked(guardrail, reason) =>
        TaskOutcome.Refused(ref, TaskRefusal.Blocked(guardrail, reason), result.usage)
      case AgentStatus.StepLimitReached     => TaskOutcome.Refused(ref, TaskRefusal.StepLimitReached, result.usage)
      case suspended: AgentStatus.Suspended => TaskOutcome.Suspended(ref, suspended)

  /** The report in `answer`: the text itself for a text subagent, else the answer decoded as `O`. */
  private def report(result: AgentResult, answer: String): Result[O] =
    definition.output.fold(text => Right(text(answer)), _ => result.decodeOutput[O](using definition.outputCodec))
