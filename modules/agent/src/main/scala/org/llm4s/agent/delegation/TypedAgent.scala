package org.llm4s.agent.delegation

import org.llm4s.agent.{ Agent, AgentBuilder, AgentId, AgentResult, AgentRun, AgentStatus }
import org.llm4s.agent.events.TaskEnded
import org.llm4s.agent.graph.{
  Checkpoint,
  CheckpointStatus,
  Execution,
  GraphError,
  GraphRuntime,
  InterruptId,
  RunConfig,
  RunId,
  RunOrigin,
  ThreadId
}
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, PermissionMiddleware }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolArgumentValidator, ToolContext, ToolSet }
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
  streaming: Boolean,
  val inheritance: Inheritance,
  private[delegation] val subagents: Vector[TypedAgent[?, ?]]
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
    streaming: Boolean = streaming,
    inheritance: Inheritance = inheritance,
    subagents: Vector[TypedAgent[?, ?]] = subagents
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
      streaming,
      inheritance,
      subagents
    )

  /** The child's own step limit, when [[withMaxSteps]] set one: it caps any step grant (design 4.45). */
  private[delegation] def ownMaxSteps: Option[Int] = maxSteps

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

  /**
   * The client and options the child calls; by default the client and options of the agent it is bound to (a model is
   * not a capability, so it is inherited unless set). `DelegationPlan` shows which applies.
   */
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
   * What the child takes from the agent it is bound to (design 4.45), replacing [[Inheritance.none]]: the parent's
   * tools and middleware it is granted, its budget, and whether it may delegate. See [[Inheritance]].
   */
  def withInheritance(inheritance: Inheritance): TypedAgent[I, O] = copy(inheritance = inheritance)

  /**
   * Appends `agents` to the child's own subagents, in order. The child is offered them only when its inheritance
   * grants delegation (`Inheritance.delegate`) and its depth is below its parent's `DelegationConfig.maxDepth`;
   * otherwise it has no task tools (rule 15).
   */
  def withSubagents(agents: TypedAgent[?, ?]*): TypedAgent[I, O] = copy(subagents = subagents ++ agents)

  /**
   * Compiles the definition onto `runtime`, with no parent: for [[BoundAgent.run]] on a top-level thread. It calls
   * `client` with `options` unless [[withModel]] set both, and traces to `tracing` (rule 16: a child
   * never has tracing of its own; design 4.47). With no parent there is nothing to inherit:
   * a tool or middleware grant naming anything is refused, and the child's own subagents are offered only when
   * delegation is granted, under the default `DelegationConfig`. Refuses an invalid id, a blank description, a
   * budget value that is not positive, and whatever `AgentBuilder.build()` refuses for the child.
   */
  def bind(
    client: LLMClient,
    runtime: GraphRuntime,
    options: CompletionOptions = CompletionOptions(),
    tracing: Option[Tracing] = None
  ): Result[BoundAgent[I, O]] =
    bindUnder(ParentScope.standalone(client, runtime, options).copy(tracing = tracing)).map(_._1)

  /**
   * Compiles the definition as a subagent of the agent `scope` describes (design 4.45): its own tools and middleware
   * plus what its inheritance grants from the parent, every ancestor's permission rules outermost, the parent's client
   * and options unless [[withModel]] set them, and its own subagents when it may delegate. Returns it with its plan.
   */
  private[agent] def bindUnder(scope: ParentScope): Result[(BoundAgent[I, O], SubagentPlan)] =
    val modelInherited    = model.isEmpty
    val (client, options) = model.getOrElse(scope.client -> scope.options)
    val delegates         = inheritance.delegate && subagents.nonEmpty && scope.childDepth + 1 <= scope.config.maxDepth
    for
      _ <- Either.cond(
        description.trim.nonEmpty,
        (),
        ValidationError("subagents", s"subagent '$rawId': the description is blank")
      )
      _ <- AgentId
        .of(rawId)
        .left
        .map(e => ValidationError("subagents", s"subagent id '$rawId': ${e.message}"))
      _                 <- inheritance.budget.validated(rawId)
      grantedTools      <- scope.grantedTools(rawId, inheritance.tools)
      grantedMiddleware <- scope.grantedMiddleware(rawId, inheritance.middleware)
      base = Agent
        .builder(rawId, client)
        .withCompletionOptions(options)
        .withRuntime(scope.runtime)
        .withMiddleware(middleware*)
      traced = scope.tracing
        .fold(base)(base.withTracing)
        .inheriting(
          AgentBuilder.Inherited(
            scope.childDepth,
            scope.permissions,
            grantedTools,
            grantedMiddleware,
            (grantedTools ++ grantedMiddleware.flatMap(_.tools))
              .flatMap(t => scope.validators.get(t.spec.name).map(t.spec.name -> _))
              .toMap
          )
        )
      prompted  = systemPrompt.fold(traced)(traced.withSystemPrompt)
      tooled    = tools.fold(prompted)(prompted.withTools)
      harnessed = harness.fold(tooled)(tooled.withHarness)
      // under a parent, every call grants a step cap (design 4.45), which an unset own limit must not lower to the
      // default: only `withMaxSteps` caps the grant; with no parent the default applies, as for any agent
      limited = maxSteps
        .orElse(Option.when(scope.childDepth > 0)(Int.MaxValue))
        .fold(harnessed)(harnessed.withMaxSteps)
      outputted  = output.fold(_ => limited, schema => limited.withOutput(schema)(using outputCodec))
      typed      = if streaming then outputted.withStreaming() else outputted
      delegating = if delegates then typed.withSubagents(subagents*).withDelegation(scope.config) else typed
      inventory <- delegating.inventory
      agent     <- delegating.build()
    yield
      val grantedNames =
        grantedTools.map(_.spec.name).toSet ++ grantedMiddleware.flatMap(_.tools.map(_.spec.name))
      val plan = SubagentPlan(
        scope.agent,
        id,
        TaskTools.taskToolName(this),
        inventory._1.map(name => PlannedTool(name, grantedNames(name))),
        inventory._2,
        scope.permissions,
        client.getClass.getSimpleName,
        modelInherited,
        inheritance.budget,
        delegates,
        scope.childDepth,
        scope.config.maxDepth,
        agent.delegation.subagents
      )
      new BoundAgent(this, agent) -> plan

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
      false,
      Inheritance.none,
      Vector.empty
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
      false,
      Inheritance.none,
      Vector.empty
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
   * outcome's `child.threadId` (depth 0, no tool call), stays in the runtime until deleted; [[resume]] continues a
   * suspended one. A run that fails with work left to recover is `Refused(Failed)`, its `child` naming the failed run,
   * for [[recover]]. Any other failure - a cancellation included - is `Left`, which names no thread, so that thread is
   * forgotten once its run has ended (as for `Agent.run(query)`).
   *
   * For tests and one-off calls: a recoverable graph node calls the thread-id overload instead, since this one, run
   * again after a crash, starts another thread and runs the subagent - its model calls and effects - twice.
   */
  def run(input: I, config: RunConfig = RunConfig()): Result[TaskOutcome[O]] =
    val threadId = ThreadId(java.util.UUID.randomUUID().toString)
    val ref      = ChildRef(threadId, config.runId, agent.id, None, 0)
    rendered(input)
      .flatMap(query => continued(ref, config)(agent.start(threadId, query, config))) match
      case Left(error) =>
        // a Left names no thread, so nothing could recover or forget it: forget it here, as Agent.run(query) does
        AgentRun.uninterrupted(agent.forget(threadId, config)).left.foreach { e =>
          BoundAgent.logger
            .warn(s"The thread ${threadId.value} of a failed standalone subagent run was not forgotten: ${e.message}")
        }
        Left(error)
      case done => done

  /**
   * Runs the subagent on the caller-chosen top-level thread `threadId`, starting or reattaching to it as the `task`
   * tools do for a child (design 4.44, #2077), so running it again with the same id never runs the subagent twice. A
   * graph node derives `threadId` from its own replay-stable key, and calls this overload, not the fresh-thread one:
   * after a crash its replay finds the first run's thread. The thread's latest checkpoint decides:
   *
   *  - none: the subagent is started with `input` and `config`, its outcome mapped as the fresh-thread `run`'s is.
   *  - `Running` (a run that failed, or crashed, with work left): it is recovered, as [[recover]] does, re-running only
   *    failed or unstarted work. A thread whose run is live - in this runtime, or holding its claim in another - is
   *    waited on, with backoff, for up to twice the runtime's claim lifetime, then `GraphError.ThreadBusy` is `Left`.
   *  - `Suspended`: its `Suspended` outcome, nothing run; answer it with [[resume]].
   *  - `Completed` or `Failed`: the outcome its terminal checkpoint records, rebuilt as a run's is, nothing run.
   *
   * The thread is the call: one found at `threadId` is taken as this call's whatever input it was started with, so the
   * key `threadId` comes from must identify the input too. A delegated child's thread is refused (`ValidationError`):
   * its parent continues it (rule 19). Unlike the fresh-thread overload, a `Left` forgets nothing: the caller can name
   * the thread, and its next call reattaches to it.
   */
  def run(threadId: ThreadId, input: I, config: RunConfig): Result[TaskOutcome[O]] =
    val ref = ChildRef(threadId, config.runId, agent.id, None, 0)
    @scala.annotation.tailrec
    def loop(cutoff: Option[Long], waits: Int): Result[TaskOutcome[O]] =
      if Thread.currentThread().isInterrupted then Left(CancelledError(s"subagent ${agent.id.value}"))
      else
        attach(ref, input, config) match
          case BoundAgent.Step.Done(result) => result
          case BoundAgent.Step.Again        => loop(cutoff, waits)
          case BoundAgent.Step.Busy(busy) =>
            val until = cutoff.getOrElse(System.nanoTime() + (agent.runtime.claimTtl * 2).toNanos)
            ChildRunner.pause(until, waits, busy) match
              case Left(stop) => Left(stop)
              case Right(_)   => loop(Some(until), waits + 1)
    loop(None, 0)

  /** [[run]] on the caller-chosen thread `threadId`, with the default config. */
  def run(threadId: ThreadId, input: I): Result[TaskOutcome[O]] = run(threadId, input, RunConfig())

  /** The child's first user message for `input`: what a renderer or input codec throws is a refused input. */
  private def rendered(input: I): Result[String] =
    // an interrupt while it renders is a cancellation, the flag kept, as for the run itself
    CancelledError.attempt(s"subagent ${agent.id.value} input")(
      scala.util
        .Try(definition.rendered(input))
        .toEither
        .left
        .map(thrown => ValidationError("input", s"subagent '${agent.id.value}': rendering the input failed: $thrown"))
    )

  /** One read of the caller-chosen thread `ref` names, and what it calls for (see the thread-id [[run]]). */
  private def attach(ref: ChildRef, input: I, config: RunConfig): BoundAgent.Step[O] =
    val graph = agent.loop.graph
    agent.runtime.childAtRest(ref.threadId, graph, agent.configured(config)) match
      case Left(error) => BoundAgent.Step.Done(Left(error))
      case Right(None) =>
        rendered(input).fold(
          refused => BoundAgent.Step.Done(Left(refused)),
          // only a new thread is started: one another caller created since the read is read again, never given a turn
          query => admitted(ref, config)(agent.startNew(ref.threadId, query, config))
        )
      case Right(Some((checkpoint, _))) if checkpoint.origin.isDefined =>
        BoundAgent.Step.Done(
          Left(
            ValidationError(
              "threadId",
              s"subagent '${agent.id.value}': ${ref.threadId.value} is a delegated child; continue it through its parent"
            )
          )
        )
      case Right(Some((checkpoint, exec))) =>
        val at = ref.copy(runId = RunId(checkpoint.runId))
        checkpoint.status match
          case CheckpointStatus.Running => admitted(ref, config)(agent.startRecover(ref.threadId, config))
          case CheckpointStatus.Suspended =>
            BoundAgent.Step.Done(
              AgentRun
                .status(agent.runtime.suspendedResult(graph, exec), isBlock = false, agent.loop)
                .flatMap(status => AgentRun.result(ref.threadId, at.runId, agent.id, exec.state, None, status))
                .map(outcome(_, at))
            )
          case CheckpointStatus.Completed | CheckpointStatus.Failed =>
            BoundAgent.Step.Done(settled(at, checkpoint, exec))

  /**
   * What an admission on the caller-chosen thread calls for: its run's outcome, mapped as [[continued]] maps it; a read
   * again when another caller started, settled or moved the thread on since the read; a wait when a run holds it.
   */
  private def admitted(ref: ChildRef, config: RunConfig)(begin: => Result[AgentRun]): BoundAgent.Step[O] =
    begin match
      // awaited even if the interrupt came after the admission: awaiting an admitted run cancels it
      case Right(run) => BoundAgent.Step.Done(awaited(ref, config, run))
      case Left(
            _: GraphError.ThreadExists | _: GraphError.NothingToRecover | _: GraphError.IncompleteRun |
            _: GraphError.PendingInterrupts | _: GraphError.NotSuspended
          ) =>
        BoundAgent.Step.Again
      case Left(busy: GraphError.ThreadBusy) => BoundAgent.Step.Busy(busy)
      case Left(error)                       => BoundAgent.Step.Done(Left(error))

  /**
   * The outcome a settled thread's terminal checkpoint records, rebuilt as the run that settled it returned it, with
   * no run (rule 14): a report or refusal as [[outcome]] maps it, a typed answer that failed its schema
   * `Refused(OutputInvalid)`; any other recorded failure is `Left`, as it was live.
   */
  private def settled(ref: ChildRef, checkpoint: Checkpoint, exec: Execution): Result[TaskOutcome[O]] =
    checkpoint.outcome match
      case None =>
        Left(
          GraphError.RestoreRejected(
            agent.loop.graph.id,
            List(s"thread '${ref.threadId.value}' is ${checkpoint.status} with no recorded outcome")
          )
        )
      case Some(recorded) =>
        AgentRun.settledStatus(recorded, exec.state) match
          case Right(status) =>
            AgentRun.result(ref.threadId, ref.runId, agent.id, exec.state, None, status).map(outcome(_, ref))
          case Left(GraphError.OutputInvalid(all)) =>
            val usage = exec.state.get(LoopKeys.usage).toOption.getOrElse(UsageSummary())
            Right(TaskOutcome.Refused(ref, TaskRefusal.OutputInvalid(all), usage))
          case Left(error) => Left(error)

  /**
   * Answers some of the pending interrupts of the suspended standalone run `ref` names - a `TaskOutcome.Suspended`'s
   * `child` - as `Agent.resume` takes them, and continues it with `config`; its outcome maps as [[run]]'s does. A
   * delegated child's ref is refused (`ValidationError`): its parent continues it (rule 19). The runtime's refusals -
   * an answer to an interrupt that is not pending (`GraphError.InvalidResume`), a thread not suspended - come back
   * unchanged, as does a cancellation; either way the thread stays, for another call.
   */
  def resume(
    ref: ChildRef,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Result[TaskOutcome[O]] =
    standalone(ref).flatMap(_ => continued(ref, config)(agent.startResume(ref.threadId, answers, config)))

  /**
   * Continues the failed or interrupted standalone run `ref` names - a `Refused(Failed)`'s `child` - with `config`,
   * re-running only failed or unstarted work, as `Agent.recover` does; its outcome maps as [[run]]'s does. A delegated
   * child's ref is refused, as for [[resume]]; so is a thread with nothing to recover (`GraphError.NothingToRecover`).
   */
  def recover(ref: ChildRef, config: RunConfig = RunConfig()): Result[TaskOutcome[O]] =
    standalone(ref).flatMap(_ => continued(ref, config)(agent.startRecover(ref.threadId, config)))

  /**
   * `ref` itself when it names a standalone run of this subagent: no parent tool call and depth 0. A delegated child
   * is continued only through its parent (rule 19), and the runtime would refuse its thread too.
   */
  private def standalone(ref: ChildRef): Result[Unit] =
    if ref.agent != agent.id then
      Left(
        ValidationError(
          "child",
          s"subagent '${agent.id.value}': ${ref.threadId.value} is a run of '${ref.agent.value}'"
        )
      )
    else if ref.toolCallId.nonEmpty || ref.depth != 0 then
      Left(
        ValidationError(
          "child",
          s"subagent '${agent.id.value}': ${ref.threadId.value} is a delegated child; continue it through its parent"
        )
      )
    else Right(())

  /**
   * The outcome of the standalone run `begin` starts on `ref`'s thread: a refused start, or a cancellation, is `Left`;
   * a typed answer that fails its schema is `Refused(OutputInvalid)`; a run that failed with work to recover (the
   * thread's latest checkpoint is that run's, `Running`) is `Refused(Failed)`, naming that run; any other failure -
   * one whose checkpoint a concurrent continuation has since superseded included - is `Left`.
   */
  private def continued(ref: ChildRef, config: RunConfig)(begin: => Result[AgentRun]): Result[TaskOutcome[O]] =
    if Thread.currentThread().isInterrupted then Left(CancelledError(s"subagent ${agent.id.value}"))
    else begin.flatMap(awaited(ref, config, _))

  /** The outcome of the admitted standalone run `run` on `ref`'s thread, awaited: as [[continued]] maps it. */
  private def awaited(ref: ChildRef, config: RunConfig, run: AgentRun): Result[TaskOutcome[O]] =
    val runId = run.runId
    Agent.awaitBlocking(run) match
      case Right(result)                       => Right(outcome(result, ref.copy(runId = result.runId)))
      case Left(GraphError.OutputInvalid(all)) =>
        // the turn is removed, its model calls are kept: the thread's usage counts them
        // the ref names the run that failed its schema - this one - not the run the caller's ref came from
        Right(
          TaskOutcome.Refused(
            ref.copy(runId = runId),
            TaskRefusal.OutputInvalid(all),
            BoundAgent.storedUsage(agent, ref.threadId, runId, config)
          )
        )
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error)                     =>
        // classified by this run's own checkpoint: a concurrent continuation may have admitted a successor since
        agent.runtime.childAtRest(ref.threadId, agent.loop.graph, config) match
          case Right(Some((checkpoint, exec)))
              if checkpoint.runId == runId.value && checkpoint.status == CheckpointStatus.Running =>
            val usage = exec.state.get(LoopKeys.usage).toOption.getOrElse(UsageSummary())
            Right(TaskOutcome.Refused(ref.copy(runId = runId), TaskRefusal.Failed(error.message), usage))
          case _ => Left(error)

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
    answers: Option[DelegatedAnswers] = None,
    stepLimit: Option[Int] = None,
    parentTokens: Option[Long] = None
  ): Result[(TaskOutcome[O], Option[LLMError], Option[TaskEnded])] =
    val threadId = ThreadId.child(origin.parent, origin.agent, origin.call)
    val ref      = ChildRef(threadId, config.runId, agent.id, Some(ctx.toolCallId), origin.depth)
    val settle   = definition.failure == ChildFailure.Report
    val query    = AgentInput(definition.rendered(input))
    answers
      .fold(ChildRunner.run(agent, origin, query, config, ctx, settle, stepLimit, parentTokens))(answered =>
        ChildRunner.resume(agent, origin, query, answered, config, ctx, settle, stepLimit, parentTokens)
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

  /** What one read of a caller-chosen thread calls for: done, read it again, or wait for its run and read it again. */
  private enum Step[+O]:
    case Done(result: Result[TaskOutcome[O]])
    case Again
    case Busy(error: GraphError.ThreadBusy)

  private val logger = org.slf4j.LoggerFactory.getLogger(classOf[BoundAgent[?, ?]])

  /**
   * The usage the newest checkpoint of run `runId` on `threadId` records, read under `config`'s tenant - not the
   * thread's newest, which a concurrent continuation may have written since; none when the run left no checkpoint.
   */
  private[delegation] def storedUsage(agent: Agent, threadId: ThreadId, runId: RunId, config: RunConfig): UsageSummary =
    @scala.annotation.tailrec
    def newestOf(before: Option[String]): Option[Checkpoint] =
      agent.runtime.history(threadId, before, UsagePage, config).toOption match
        case Some(page) =>
          page.find(_.runId == runId.value) match
            case found @ Some(_)               => found
            case None if page.size < UsagePage => None
            case None                          => newestOf(page.lastOption.map(_.id))
        case None => None
    newestOf(None)
      .flatMap(checkpoint => agent.loop.graph.restore(checkpoint.snapshot).toOption)
      .flatMap(_.state.get(LoopKeys.usage).toOption)
      .getOrElse(UsageSummary())

  /** How many checkpoints [[storedUsage]] reads at a time. */
  private val UsagePage: Int = 16

/**
 * The agent a subagent is bound under (design 4.45): what its inheritance may draw on, and what it inherits regardless.
 *
 * @param agent the parent's id
 * @param client the parent's client, the child's unless it sets its own
 * @param options the parent's completion options, likewise
 * @param runtime the parent's runtime, always the child's (rule 16)
 * @param tracing the root's tracing, always the child's (rule 16)
 * @param tools the parent's tools a grant may name: its own, its harness's and those it was granted
 * @param middleware the parent's middleware a grant may name, by id
 * @param permissions every ancestor's permission rules, the parent's own included, outermost first
 * @param childDepth how deep the child runs
 * @param config the delegation bounds of the tree, the root's
 * @param validators the validator the parent checks each of its tools with, by name, which checks it in a child it is
 *   granted to
 */
final private[agent] case class ParentScope(
  agent: AgentId,
  client: LLMClient,
  options: CompletionOptions,
  runtime: GraphRuntime,
  tracing: Option[Tracing],
  tools: Vector[AgentTool[?]],
  middleware: Vector[AgentMiddleware],
  permissions: Vector[AncestorRules],
  childDepth: Int,
  config: DelegationConfig,
  validators: Map[String, ToolArgumentValidator] = Map.empty
):

  /** The parent's tools `grant` names for subagent `id`; a name the parent does not have is refused. */
  def grantedTools(id: String, grant: ToolGrant): Result[Vector[AgentTool[?]]] =
    val names = tools.map(_.spec.name).toSet
    def known(asked: Set[String]): Result[Unit] =
      (asked -- names).toVector.sorted match
        case Vector() => Right(())
        case missing =>
          Left(
            ValidationError(
              "inheritance",
              s"subagent '$id': agent '${agent.value}' has no tool ${missing.map(n => s"'$n'").mkString(", ")} to grant"
            )
          )
    grant match
      case ToolGrant.None              => Right(Vector.empty)
      case ToolGrant.Only(asked)       => known(asked).map(_ => tools.filter(t => asked(t.spec.name)))
      case ToolGrant.AllExcept(except) => known(except).map(_ => tools.filterNot(t => except(t.spec.name)))

  /**
   * The parent's middleware `ids` names for subagent `id`, in the parent's order; an id the parent does not have, and
   * the delegation and ancestor-permissions middleware, which the runtime installs, are refused.
   */
  def grantedMiddleware(id: String, ids: Set[MiddlewareId]): Result[Vector[AgentMiddleware]] =
    val reserved = Set(DelegationMiddleware.Id, AncestorPermissions.Id).intersect(ids)
    val missing  = ids -- middleware.map(_.id) -- reserved
    if reserved.nonEmpty then
      Left(
        ValidationError(
          "inheritance",
          s"subagent '$id': middleware ${reserved.map(m => s"'${m.value}'").toVector.sorted.mkString(", ")} cannot " +
            "be granted: delegation is granted with Inheritance.delegate, and ancestors' permissions always apply"
        )
      )
    else if middleware.exists(m => ids(m.id) && m.isInstanceOf[PermissionMiddleware]) then
      Left(
        ValidationError(
          "inheritance",
          s"subagent '$id': a PermissionMiddleware cannot be granted: every ancestor's permission rules reach a " +
            "child through ancestor-permissions already, and granting them would evaluate one policy twice"
        )
      )
    else if missing.nonEmpty then
      Left(
        ValidationError(
          "inheritance",
          s"subagent '$id': agent '${agent.value}' has no middleware " +
            s"${missing.map(m => s"'${m.value}'").toVector.sorted.mkString(", ")} to grant"
        )
      )
    else Right(middleware.filter(m => ids(m.id)))

private[agent] object ParentScope:

  /** No parent: [[TypedAgent.bind]]'s subagent runs on a top-level thread, at depth 0, with nothing to inherit, calling `client` with `options`. */
  def standalone(client: LLMClient, runtime: GraphRuntime, options: CompletionOptions): ParentScope =
    ParentScope(
      AgentId.unsafe("standalone"),
      client,
      options,
      runtime,
      None,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      0,
      DelegationConfig.default
    )
