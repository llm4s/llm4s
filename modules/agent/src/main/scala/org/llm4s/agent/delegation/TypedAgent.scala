package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentId, AgentResult, AgentRun, AgentStatus }
import org.llm4s.agent.events.TaskEnded
import org.llm4s.agent.graph.{ GraphError, GraphRuntime, RunConfig, RunId, RunOrigin, ThreadId, ToolCallId }
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.{ ToolContext, ToolSet }
import org.llm4s.agent.graph.toolloop.{ AgentInput, LoopKeys }
import org.llm4s.agent.harness.HarnessProfile
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, UsageSummary }
import org.llm4s.toolapi.SchemaDefinition
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.concurrent.duration.*

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
  val failure: ChildFailure,
  streaming: Boolean
)(using private[delegation] val inputCodec: ReadWriter[I], private[delegation] val outputCodec: ReadWriter[O]):

  private def copy(
    systemPrompt: Option[String] = systemPrompt,
    tools: Option[ToolSet] = tools,
    middleware: Vector[AgentMiddleware] = middleware,
    harness: Option[HarnessProfile] = harness,
    model: Option[(LLMClient, CompletionOptions)] = model,
    maxSteps: Option[Int] = maxSteps,
    render: Option[I => String] = render,
    failure: ChildFailure = failure,
    streaming: Boolean = streaming
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
      failure,
      streaming
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

  /**
   * Streams the child's model answers (`AgentBuilder.withStreaming`), so a parent subscription made with
   * `nested = true` sees the child's `AgentEvents.TextDelta`s as they arrive (design 4.47). Off by default.
   */
  def withStreaming(): TypedAgent[I, O] = copy(streaming = true)

  /** What the parent does when the child fails; [[ChildFailure.Report]] by default. */
  def withFailure(policy: ChildFailure): TypedAgent[I, O] = copy(failure = policy)

  /**
   * Compiles the definition onto `runtime`, calling `parentClient` unless [[withModel]] set a client, and tracing to
   * the parent's `tracing` (rule 16: a child never has tracing of its own; design 4.47). Refuses an invalid id, a
   * blank description, and whatever `AgentBuilder.build()` refuses for the child.
   */
  def bind(parentClient: LLMClient, runtime: GraphRuntime, tracing: Option[Tracing] = None): Result[BoundAgent[I, O]] =
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
      val outputted = output.fold(_ => limited, schema => limited.withOutput(schema)(using outputCodec))
      val traced    = tracing.fold(outputted)(outputted.withTracing)
      val typed     = if streaming then traced.withStreaming() else traced
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
      ChildFailure.Report,
      false
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
      ChildFailure.Report,
      false
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
   * report, the refusal its status or its typed answer's failure gives, or its suspension. The thread, named by the
   * outcome's `child.threadId` (depth 0, no tool call), stays in the runtime until deleted. Any other failure is `Left`,
   * which names no thread, so that thread is forgotten once its run has ended (as for `Agent.run(query)`).
   */
  def run(input: I, config: RunConfig = RunConfig()): Result[TaskOutcome[O]] =
    val threadId = ThreadId(java.util.UUID.randomUUID().toString)
    val ref      = ChildRef(threadId, config.runId, agent.id, None, 0)
    // a renderer or input codec is caller code: what it throws is a refused input, not an escaped exception
    // an interrupt while it renders is a cancellation, the flag kept, as for the run itself
    CancelledError
      .attempt(s"subagent ${agent.id.value} input")(
        scala.util
          .Try(definition.rendered(input))
          .toEither
          .left
          .map(thrown => ValidationError("input", s"subagent '${agent.id.value}': rendering the input failed: $thrown"))
      )
      .flatMap(query => agent.run(threadId, query, config)) match
      case Right(result)                       => Right(outcome(result, ref.copy(runId = result.runId)))
      case Left(GraphError.OutputInvalid(all)) =>
        // the turn is removed, its model calls are kept: the thread's usage counts them
        Right(TaskOutcome.Refused(ref, TaskRefusal.OutputInvalid(all), BoundAgent.storedUsage(agent, threadId, config)))
      case Left(error) =>
        // a Left names no thread, so nothing could recover or forget it: forget it here, as Agent.run(query) does
        AgentRun.uninterrupted(agent.forget(threadId, config)).left.foreach { e =>
          BoundAgent.logger
            .warn(s"The thread ${threadId.value} of a failed standalone subagent run was not forgotten: ${e.message}")
        }
        Left(error)

  /**
   * Runs the subagent as the child of the parent call `ctx` (design 4.44), through the child runner - or, with
   * `answers`, resumes its suspended child with them (design 4.46): the outcome, for a failed child its error, which
   * `ChildFailure.Fail` fails the parent with, and - unless the child suspended - the `TaskEnded` the call commits with
   * a terminal result (design 4.47): the child's status, roll-up, and time since its first admission. A suspended
   * child's roll-up is left for the call's run that settles it (rules 10, 11), and its call commits no `TaskEnded`.
   */
  private[delegation] def delegated(
    input: I,
    origin: RunOrigin,
    config: RunConfig,
    ctx: ToolContext,
    answers: Option[DelegatedAnswers] = None
  ): Result[(TaskOutcome[O], Option[LLMError], Option[TaskEnded])] =
    val threadId = ThreadId.child(origin.parent, origin.agent, origin.call)
    val ref      = ChildRef(threadId, config.runId, agent.id, Some(ctx.toolCallId), origin.depth)
    val settle   = definition.failure == ChildFailure.Report
    val query    = AgentInput(definition.rendered(input))
    answers
      .fold(ChildRunner.run(agent, origin, query, config, ctx, settleOnFailure = settle))(answered =>
        ChildRunner.resume(agent, origin, query, answered, config, ctx, settleOnFailure = settle)
      )
      .map { childEnd =>
        val (taskOutcome, cause, child, status) = childEnd match
          case ChildEnd.Ended(result, _) =>
            val ended = ref.copy(runId = result.runId)
            val made  = outcome(result, ended)
            val label = made match
              case TaskOutcome.Refused(_, reason, _) => Some(TaskRefusal.label(reason))
              case _: TaskOutcome.Suspended[?]       => None
              case _                                 => Some("completed")
            (made, None, ended, label)
          case ChildEnd.Failed(invalid @ GraphError.OutputInvalid(all), usage) =>
            val reason = TaskRefusal.OutputInvalid(all)
            val failed = failedRef(ref, config)
            (TaskOutcome.Refused(failed, reason, usage), Some(invalid), failed, Some(TaskRefusal.label(reason)))
          case ChildEnd.Failed(error, usage) =>
            val reason = TaskRefusal.Failed(error.message)
            val failed = failedRef(ref, config)
            (TaskOutcome.Refused(failed, reason, usage), Some(error), failed, Some(TaskRefusal.label(reason)))
        // a suspension is not the call's end: the call that settles the child commits the one TaskEnded
        (taskOutcome, cause, status.map(taskEnded(child, _, childEnd.usage, origin, config)))
      }

  /**
   * `ref` naming the run of the failed child's latest checkpoint - the run that recorded its failure, as replay names
   * it - rather than the run id the call was configured with, which no admission uses; `ref` as it is if the child
   * cannot be read.
   */
  private def failedRef(ref: ChildRef, config: RunConfig): ChildRef =
    agent.runtime
      .childAtRest(ref.threadId, agent.loop.graph, config)
      .toOption
      .flatten
      .fold(ref)((checkpoint, _) => ref.copy(runId = RunId(checkpoint.runId)))

  /**
   * The `TaskEnded` of `child`: its status label, its roll-up `usage`, and the time from its first admission -
   * `RunOrigin.startedAt` as its thread records it, stamped by the runtime, else `origin`'s - to now, by the runtime's
   * clock.
   */
  private def taskEnded(
    child: ChildRef,
    status: String,
    usage: UsageSummary,
    origin: RunOrigin,
    config: RunConfig
  ): TaskEnded =
    val startedAt = agent.runtime
      .childAtRest(child.threadId, agent.loop.graph, config)
      .toOption
      .flatten
      .flatMap(_._1.origin)
      .map(_.startedAt)
      .getOrElse(origin.startedAt)
    val elapsed = java.time.Duration.between(startedAt, agent.runtime.now()).toNanos.max(0L)
    TaskEnded(child, status, usage, elapsed.nanos)

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
      case AgentStatus.TokenLimitReached    => TaskOutcome.Refused(ref, TaskRefusal.TokenLimitReached, result.usage)
      case suspended: AgentStatus.Suspended => TaskOutcome.Suspended(ref, suspended)

  /** The report in `answer`: the text itself for a text subagent, else the answer decoded as `O`. */
  private def report(result: AgentResult, answer: String): Result[O] =
    definition.output.fold(text => Right(text(answer)), _ => result.decodeOutput[O](using definition.outputCodec))

object BoundAgent:
  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[BoundAgent[?, ?]])

  /** The usage `threadId`'s latest checkpoint records, read under `config`'s tenant; none for a thread with none. */
  private[delegation] def storedUsage(agent: Agent, threadId: ThreadId, config: RunConfig): UsageSummary =
    agent.runtime
      .history(threadId, None, 1, config)
      .toOption
      .flatMap(_.headOption)
      .flatMap(latest => agent.loop.graph.restore(latest.snapshot).toOption)
      .flatMap(_.state.get(LoopKeys.usage).toOption)
      .getOrElse(UsageSummary())
