package org.llm4s.agent.delegation

import org.llm4s.agent.events.SubagentEvents
import org.llm4s.agent.graph.{ RunConfig, RunOrigin }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  BoundedErrors,
  Operation,
  ToolContent,
  ToolContext,
  ToolErrors,
  ToolOutcome,
  ToolResource
}
import org.llm4s.agent.graph.toolloop.{ Messages, StoredMessage }
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError, ValidationError }
import org.llm4s.llmconnect.model.{ AssistantMessage, UserMessage }
import org.llm4s.toolapi.{ Schema, SchemaDefinition, StringSchema }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.Semaphore
import scala.util.Try
import scala.util.control.Exception.ultimately

/**
 * The `task` tools of an agent with subagents (design 4.44), and the prompt section that lists them. Installed by
 * `AgentBuilder.build()`, innermost in the agent's stack, so a `PermissionMiddleware` decides each call by its resource
 * `(Invoke, task:<id>)`.
 *
 *  - `task` takes `{subagent_type, description}` and covers every subagent whose input is a [[TaskRequest]].
 *  - Each other subagent has its own tool, `task_<id>`, whose arguments are its input's schema.
 *
 * A call is refused, as a tool error, past `maxTasksPerTurn` (counted in the turn's committed assistant messages up to
 * the call, across handoffs, so exact and stable under replay) or `maxDepth`; it waits for one of the parent run's
 * `maxConcurrentChildren` slots; then it runs the child through the child runner and maps its outcome.
 *
 * A child that suspends makes its call ask a [[DelegatedQuestion]] (design 4.46): the parent suspends too, listing the
 * child's interrupts under composite ids, and the call's resume, with the [[DelegatedAnswers]], resumes the child.
 *
 * @param depth how deep the agent this is installed in runs: 0 for a top-level agent
 * @param familyTaskNames the task tools of the agent's family (its handoff graph), whose calls before a handoff in the
 *   turn count towards `maxTasksPerTurn` too; this agent's own always count
 */
final private[agent] class DelegationMiddleware(
  subagents: Vector[BoundAgent[?, ?]],
  config: DelegationConfig,
  depth: Int,
  familyTaskNames: Set[String] = Set.empty
) extends AgentMiddleware:

  val id: MiddlewareId = DelegationMiddleware.Id

  /** Each run incarnation's slots; weakly keyed, so a finished run's semaphore goes with it. */
  private val slots =
    java.util.Collections.synchronizedMap(new java.util.WeakHashMap[AnyRef, Semaphore]())

  private val taskShaped = subagents.filter(_.definition.takesTaskRequest)

  private val toolNames: Set[String] = TaskTools.names(subagents.map(_.definition))

  /** The task tools whose calls in the turn count towards `maxTasksPerTurn`: this agent's and its family's. */
  private val turnTaskNames: Set[String] = toolNames ++ familyTaskNames

  override val tools: Vector[AgentTool[?]] =
    val shared =
      Option.when(taskShaped.nonEmpty)(
        TaskTool(TaskTools.taskSpec(taskShaped.map(_.definition)), config.maxReportBytes)(
          (args, ctx) => sharedTask(args, None, ctx),
          (args, answers, ctx) => sharedTask(args, Some(answers), ctx)
        )
      )
    shared.toVector ++ subagents.filterNot(_.definition.takesTaskRequest).map(typedTool(_))

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    Right(Vector(PromptSection(id.value, TaskTools.section(subagents.map(_.definition)), cacheable = true)))

  /** A tool error, cut to `maxReportBytes` (the loop cuts it too: the task tools are `BoundedErrors`). */
  private def error(message: String): ToolOutcome =
    ToolOutcome.Error(ToolErrors.bounded(message, config.maxReportBytes))

  /** A call of the shared `task` tool, or with `answers` its resume. */
  private def sharedTask(args: TaskTools.TaskCall, answers: Option[DelegatedAnswers], ctx: ToolContext): ToolOutcome =
    taskShaped.find(_.id.value == args.subagent_type) match
      case None        => error(s"no subagent '${args.subagent_type}' takes a task description")
      case Some(bound) => viaRequest(bound, args.description, answers, ctx)

  private def viaRequest[I, O](
    bound: BoundAgent[I, O],
    description: String,
    answers: Option[DelegatedAnswers],
    ctx: ToolContext
  ): ToolOutcome =
    TaskTools.fromRequest(TaskRequest(description))(using bound.definition.inputCodec) match
      case Left(problem) => error(problem)
      case Right(input)  => delegate(bound, input, answers, ctx)

  private def typedTool[I, O](bound: BoundAgent[I, O]): AgentTool[I] =
    TaskTool(TaskTools.typedSpec(bound.definition), config.maxReportBytes)(
      (input, ctx) => delegate(bound, input, None, ctx),
      (input, answers, ctx) => delegate(bound, input, Some(answers), ctx)
    )

  /**
   * One run of a task call - its first, or with `answers` the resume of its suspended child: the same checks, which a
   * resume passes as its first run did (they read the same committed messages), the same slot, then the child.
   */
  private def delegate[I, O](
    bound: BoundAgent[I, O],
    input: I,
    answers: Option[DelegatedAnswers],
    ctx: ToolContext
  ): ToolOutcome =
    val name     = bound.id.value
    val position = TaskTools.positionInTurn(ctx, turnTaskNames)
    if position > config.maxTasksPerTurn then
      error(
        s"task for subagent '$name' refused: this turn's task calls are over maxTasksPerTurn = ${config.maxTasksPerTurn}"
      )
    else if depth + 1 > config.maxDepth then
      error(s"task for subagent '$name' refused: it would nest deeper than maxDepth = ${config.maxDepth}")
    else
      val slot = slots.computeIfAbsent(ctx.run.incarnation, _ => new Semaphore(config.maxConcurrentChildren, true))
      CancelledError.attempt(s"task $name")(Right(slot.acquire())) match
        case Left(cancelled) => ToolOutcome.Fatal(cancelled)
        case Right(_)        => ultimately(slot.release())(run(bound, input, answers, ctx))

  private def run[I, O](
    bound: BoundAgent[I, O],
    input: I,
    answers: Option[DelegatedAnswers],
    ctx: ToolContext
  ): ToolOutcome =
    val name   = bound.id.value
    val origin = RunOrigin(ctx.run.position.threadId, ctx.idempotencyKey, bound.id, Instant.now(), depth + 1)
    bound.delegated(input, origin, childConfig(ctx.run.config), ctx, answers) match
      case Left(error) => DelegationMiddleware.notRun(name, error, config.maxReportBytes)
      case Right((outcome, cause, ended)) =>
        val result =
          DelegationMiddleware.toolOutcome(name, bound.definition.failure, config.maxReportBytes, outcome, cause)(using
            bound.definition.outputCodec
          )
        // committed with the call's terminal result only (design 4.47): a report or a tool error commits once and never
        // runs again; a Fatal fails the task, which discards it, and the replay sends it; a suspension (none built)
        // waits for the call that settles the child
        result match
          case _: ToolOutcome.Success | _: ToolOutcome.Error => ended.foreach(SubagentEvents.TaskEnded.emit(ctx.run, _))
          case _                                             => ()
        result

  /**
   * The child's config: its own run id, the parent's tenant and principal (rule 16), the parent's budgets without the
   * timeout - the parent's cancellation reaches the child, its deadline included - and the delegation's child retention.
   */
  private def childConfig(parent: RunConfig): RunConfig =
    RunConfig(
      tenantId = parent.tenantId,
      principal = parent.principal,
      budgets = parent.budgets.withTimeout(None),
      childRetention = config.childRetention
    )

/**
 * A `task` tool: its errors, whoever raises them, are cut by the loop to `maxErrorBytes` (`maxReportBytes`). It asks a
 * [[DelegatedQuestion]] when its child suspends, and `answer` resumes the child with the [[DelegatedAnswers]].
 */
final private class TaskTool[A](base: AgentToolSpec[A], val maxErrorBytes: Int)(
  run: (A, ToolContext) => ToolOutcome,
  answer: (A, DelegatedAnswers, ToolContext) => ToolOutcome
) extends AgentTool.Asking[A, DelegatedQuestion, DelegatedAnswers](base)
    with BoundedErrors:
  def execute(args: A, context: ToolContext): ToolOutcome = run(args, context)

  def resume(args: A, question: DelegatedQuestion, answers: DelegatedAnswers, context: ToolContext): ToolOutcome =
    answer(args, answers, context)

private[agent] object DelegationMiddleware:
  val Id: MiddlewareId = MiddlewareId("delegation")

  /**
   * What the parent model sees for subagent `name`'s `outcome`: its report as JSON, or past `maxReportBytes` a tool
   * error; a refusal (made with or without a child) as a tool error under `ChildFailure.Report`, or `Fatal` with the
   * child's error `cause` - else a `ProcessingError` with the same text - under `Fail`.
   * Every tool message is at most `maxReportBytes` in UTF-8: a refusal's reason is cut to fit, an `OutputInvalid`
   * keeping its first errors and saying how many it dropped. The full reason stays in the child's `RunOutcome` and in
   * the typed `TaskOutcome`. A suspended child is the call's [[DelegatedQuestion]] (design 4.46): its interrupts.
   */
  def toolOutcome[O: ReadWriter](
    name: String,
    failure: ChildFailure,
    maxReportBytes: Int,
    outcome: TaskOutcome[O],
    cause: Option[LLMError]
  ): ToolOutcome =
    def refused(prefix: String, reason: TaskRefusal): ToolOutcome = failure match
      case ChildFailure.Report => ToolOutcome.Error(within(prefix, reason, maxReportBytes))
      case ChildFailure.Fail   => ToolOutcome.Fatal(cause.getOrElse(ProcessingError("task", prefix + reason.describe)))
    outcome match
      case TaskOutcome.Reported(report, child, _) =>
        val json  = upickle.default.writeJs(report)
        val bytes = ToolErrors.storedText(json).getBytes(StandardCharsets.UTF_8).length
        if bytes > maxReportBytes then
          ToolOutcome.Error(
            cut(
              s"subagent '$name' report over $maxReportBytes bytes ($bytes); child thread ${child.threadId.value}",
              maxReportBytes
            )
          )
        else ToolOutcome.Success(ToolContent.Json(json))
      case TaskOutcome.Refused(child, reason, _) =>
        refused(s"subagent '$name' (child thread ${child.threadId.value}) ", reason)
      case TaskOutcome.NotStarted(_, reason) =>
        refused(s"subagent '$name' was not started: ", reason)
      case TaskOutcome.Suspended(child, status) =>
        ToolOutcome.Ask(DelegatedQuestion(child, ChildInterrupt.of(status)))

  /**
   * What the parent model sees when the child runner refused or failed the call itself, `error`: a `ValidationError`
   * - the call's child thread was started with other arguments (an edited call run again), or a thread at its id is not
   * its child - is a tool error, bounded by `maxReportBytes`, since running the call again cannot change it; anything
   * else (a cancellation, a claim held past the wait, corrupt stored state) is `Fatal`, so the run fails and `recover`
   * runs the call again.
   */
  def notRun(name: String, error: LLMError, maxReportBytes: Int): ToolOutcome = error match
    case refused: ValidationError =>
      ToolOutcome.Error(cut(s"subagent '$name' was not run: ${refused.message}", maxReportBytes))
    case other => ToolOutcome.Fatal(other)

  /**
   * `prefix` and `reason`'s description, its tool message at most `max` bytes: whole when it fits; an `OutputInvalid`
   * with as many of its first errors as fit and the count of those dropped; anything else cut.
   */
  private[delegation] def within(prefix: String, reason: TaskRefusal, max: Int): String =
    val full = prefix + reason.describe
    if ToolErrors.wrapped(full) <= max then full
    else
      reason match
        case TaskRefusal.OutputInvalid(errors) =>
          (errors.size - 1 to 0 by -1).iterator
            .map { kept =>
              val dropped = errors.size - kept
              val noun    = if dropped == 1 then "error" else "errors"
              s"${prefix}its answer did not match its output schema: ${errors.take(kept).mkString("; ")} " +
                s"(and $dropped more $noun dropped)"
            }
            .find(ToolErrors.wrapped(_) <= max)
            .getOrElse(cut(full, max))
        case _ => cut(full, max)

  /** `text`, cut on a code point and marked, so its stored error message is at most `max` bytes. */
  private[delegation] def cut(text: String, max: Int): String = ToolErrors.bounded(text, max)

/** The `task` tools' specs, shared by the middleware and by the parent's graph version, which covers them. */
private[agent] object TaskTools:

  /** The `task` tool's arguments. */
  final case class TaskCall(subagent_type: String, description: String) derives ReadWriter

  val TaskToolName: String = "task"

  /** The schema of a [[TaskRequest]]: one required `description`. */
  val requestSchema: SchemaDefinition[TaskRequest] =
    Schema
      .`object`[TaskRequest]("A task for a subagent")
      .withRequiredField(
        "description",
        Schema.string("The task, self-contained: everything the subagent needs, since it sees nothing else")
      )

  def taskToolName(definition: TypedAgent[?, ?]): String =
    if definition.takesTaskRequest then TaskToolName else s"task_${definition.id.value}"

  def names(definitions: Vector[TypedAgent[?, ?]]): Set[String] = definitions.map(taskToolName).toSet

  def taskSpec(taskShaped: Vector[TypedAgent[?, ?]]): AgentToolSpec[TaskCall] =
    val schema = Schema
      .`object`[TaskCall]("A task for one of the subagents")
      .withRequiredField(
        "subagent_type",
        StringSchema("The subagent to run the task").withEnum(taskShaped.map(_.id.value))
      )
      .withRequiredField(
        "description",
        Schema.string("The task, self-contained: everything the subagent needs, since it sees nothing else")
      )
    AgentToolSpec(
      TaskToolName,
      "Delegates a bounded, self-contained task to a subagent, which works on it alone, with a fresh conversation, " +
        "and returns only its report. Independent tasks issued in one message run in parallel.",
      schema
    ).withResources(args => Vector(ToolResource(Operation.Invoke, s"task:${args.subagent_type}")))

  def typedSpec[I](definition: TypedAgent[I, ?]): AgentToolSpec[I] =
    AgentToolSpec(taskToolName(definition), definition.description, definition.inputSchema)(using
      definition.inputCodec
    ).withResources(_ => Vector(ToolResource(Operation.Invoke, s"task:${definition.id.value}")))

  /** Every tool spec delegation contributes for `definitions`, in the order the middleware offers them. */
  def specs(definitions: Vector[TypedAgent[?, ?]]): Vector[AgentToolSpec[?]] =
    val (shaped, typed) = definitions.partition(_.takesTaskRequest)
    Option.when(shaped.nonEmpty)(taskSpec(shaped)).toVector ++ typed.map(d => typedSpec(d))

  /** The prompt section listing each subagent's id, tool and description. */
  def section(definitions: Vector[TypedAgent[?, ?]]): String =
    val lines = definitions.map { d =>
      val how =
        if d.takesTaskRequest then s"""`$TaskToolName` with subagent_type "${d.id.value}""""
        else s"`${taskToolName(d)}`"
      s"- ${d.id.value} ($how): ${d.description}"
    }
    ("## Subagents" +: "You can delegate a bounded, self-contained task to a subagent. It works on it alone and returns only its report." +: lines)
      .mkString("\n")

  /** A [[TaskRequest]] as an `I` whose codec is [[TaskRequest]]'s. */
  def fromRequest[I: ReadWriter](request: TaskRequest): Either[String, I] =
    Try(upickle.default.read[I](upickle.default.writeJs(request))).toEither.left.map(e =>
      s"the task does not decode: $e"
    )

  /**
   * The call's position among the task calls of the current turn's assistant messages, from 1, counting every call
   * of `names` from the turn's user message up to and including this one (the last call with its id); one more than
   * the turn's count when the call is not found. A handoff stays in the turn, so the calls before it count too (#2067):
   * `names` are the task tools of the agent's whole family, whichever agent made the call.
   */
  def positionInTurn(ctx: ToolContext, names: Set[String]): Int =
    val history = ctx.state.get(Messages.key).getOrElse(Vector.empty)
    val turn = history.lastIndexWhere { case StoredMessage(_, _: UserMessage) => true; case _ => false } match
      case -1 => history
      case at => history.drop(at)
    val calls = turn.flatMap {
      case StoredMessage(_, assistant: AssistantMessage) => assistant.toolCalls.filter(c => names(c.name))
      case _                                             => Vector.empty
    }
    calls.lastIndexWhere(_.id == ctx.toolCallId.value) match
      case -1 => calls.size + 1
      case at => at + 1

  /** `json` with object members sorted by key, recursively, rendered compactly. */
  def canonical(json: ujson.Value): String =
    def sorted(value: ujson.Value): ujson.Value = value match
      case ujson.Obj(members) => ujson.Obj.from(members.toSeq.sortBy(_._1).map((k, v) => k -> sorted(v)))
      case ujson.Arr(items)   => ujson.Arr.from(items.map(sorted))
      case other              => other
    ujson.write(sorted(json))
