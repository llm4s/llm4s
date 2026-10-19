package org.llm4s.agent.delegation

import org.llm4s.agent.graph.{ RunConfig, RunOrigin }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.agent.graph.tool.{
  AgentTool,
  AgentToolSpec,
  Operation,
  ToolContent,
  ToolContext,
  ToolOutcome,
  ToolResource
}
import org.llm4s.agent.graph.toolloop.{ Messages, StoredMessage }
import org.llm4s.error.{ CancelledError, ProcessingError }
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
 * the call, so exact and stable under replay) or `maxDepth`; it waits for one of the parent run's
 * `maxConcurrentChildren` slots; then it runs the child through the child runner and maps its outcome.
 *
 * @param depth how deep the agent this is installed in runs: 0 for a top-level agent
 */
final private[agent] class DelegationMiddleware(
  subagents: Vector[BoundAgent[?, ?]],
  config: DelegationConfig,
  depth: Int
) extends AgentMiddleware:

  val id: MiddlewareId = DelegationMiddleware.Id

  /** Each run incarnation's slots; weakly keyed, so a finished run's semaphore goes with it. */
  private val slots =
    java.util.Collections.synchronizedMap(new java.util.WeakHashMap[AnyRef, Semaphore]())

  private val taskShaped = subagents.filter(_.definition.takesTaskRequest)

  private val toolNames: Set[String] = TaskTools.names(subagents.map(_.definition))

  override val tools: Vector[AgentTool[?]] =
    val shared =
      Option.when(taskShaped.nonEmpty)(
        AgentTool(TaskTools.taskSpec(taskShaped.map(_.definition))) { (args, ctx) =>
          taskShaped.find(_.id.value == args.subagent_type) match
            case None        => ToolOutcome.Error(s"no subagent '${args.subagent_type}' takes a task description")
            case Some(bound) => viaRequest(bound, args.description, ctx)
        }
      )
    shared.toVector ++ subagents.filterNot(_.definition.takesTaskRequest).map(typedTool(_))

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    Right(Vector(PromptSection(id.value, TaskTools.section(subagents.map(_.definition)), cacheable = true)))

  private def viaRequest[I, O](bound: BoundAgent[I, O], description: String, ctx: ToolContext): ToolOutcome =
    TaskTools.fromRequest(TaskRequest(description))(using bound.definition.inputCodec) match
      case Left(problem) => ToolOutcome.Error(problem)
      case Right(input)  => delegate(bound, input, ctx)

  private def typedTool[I, O](bound: BoundAgent[I, O]): AgentTool[I] =
    AgentTool(TaskTools.typedSpec(bound.definition))((input, ctx) => delegate(bound, input, ctx))

  private def delegate[I, O](bound: BoundAgent[I, O], input: I, ctx: ToolContext): ToolOutcome =
    val name     = bound.id.value
    val position = TaskTools.positionInTurn(ctx, toolNames)
    if position > config.maxTasksPerTurn then
      ToolOutcome.Error(
        s"task for subagent '$name' refused: this turn's task calls are over maxTasksPerTurn = ${config.maxTasksPerTurn}"
      )
    else if depth + 1 > config.maxDepth then
      ToolOutcome.Error(s"task for subagent '$name' refused: it would nest deeper than maxDepth = ${config.maxDepth}")
    else
      val slot = slots.computeIfAbsent(ctx.run.incarnation, _ => new Semaphore(config.maxConcurrentChildren, true))
      CancelledError.attempt(s"task $name")(Right(slot.acquire())) match
        case Left(cancelled) => ToolOutcome.Fatal(cancelled)
        case Right(_)        => ultimately(slot.release())(run(bound, input, ctx))

  private def run[I, O](bound: BoundAgent[I, O], input: I, ctx: ToolContext): ToolOutcome =
    val name   = bound.id.value
    val origin = RunOrigin(ctx.run.position.threadId, ctx.idempotencyKey, bound.id, Instant.now(), depth + 1)
    bound.delegated(input, origin, childConfig(ctx.run.config), ctx) match
      case Left(error) => ToolOutcome.Fatal(error)
      case Right((TaskOutcome.Reported(report, child, _), _)) =>
        val json  = upickle.default.writeJs(report)(using bound.definition.outputCodec)
        val bytes = json.render().getBytes(StandardCharsets.UTF_8).length
        if bytes > config.maxReportBytes then
          ToolOutcome.Error(
            s"subagent '$name' report over ${config.maxReportBytes} bytes ($bytes); child thread ${child.threadId.value}"
          )
        else ToolOutcome.Success(ToolContent.Json(json))
      case Right((TaskOutcome.Refused(child, reason, _), cause)) =>
        val message = s"subagent '$name' (child thread ${child.threadId.value}) ${reason.describe}"
        bound.definition.failure match
          case ChildFailure.Report => ToolOutcome.Error(message)
          case ChildFailure.Fail   => ToolOutcome.Fatal(cause.getOrElse(ProcessingError("task", message)))
      case Right((TaskOutcome.Suspended(child, _), _)) =>
        ToolOutcome.Error(
          s"subagent '$name' suspended (child thread ${child.threadId.value}): it waits for approvals or answers, " +
            "which cannot be given through the parent yet"
        )

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

private[agent] object DelegationMiddleware:
  val Id: MiddlewareId = MiddlewareId("delegation")

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
   * the turn's count when the call is not found.
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
