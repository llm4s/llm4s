package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, PromptSection }
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, LockKey, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * A plan for the agent: a `write_todos` tool that replaces the thread's todo list, and a prompt section that shows
 * the list to the model on every model step (design §4.30).
 *
 *   - **The tool** takes the whole list - `content`, `status` (`pending`, `in_progress`, `done`) and, to keep an
 *     item, its `id` - and stores it in [[TodoMiddleware.key]]. An item without an id keeps the id of an unclaimed
 *     todo with the same content, or takes a fresh one; an id is never reused within a thread. A list of more than
 *     `maxTodos` items, an item whose content is empty or over `maxTodoChars` code points, an id given twice, or an
 *     id the list does not have, is refused as a tool error the model sees. The tool locks `todos`, so two calls in
 *     one model message run in turn, the second seeing the first's list.
 *   - **The sections**: `todos-guide`, cacheable, on how to use the tool; and `todos`, the current plan, while it is
 *     not empty. Both are bounded by the limits whatever the stored list holds: at most `maxTodos` todos are shown,
 *     each cut to `maxTodoChars` code points.
 *   - **Events**: each change emits a durable [[HarnessEvents.TodosUpdated]] (ids and statuses) and a live
 *     [[HarnessEvents.TodosProgress]] (the whole list).
 *
 * The plan guides the model only: it never routes, filters tools, or changes a permission. `write_todos` is a tool
 * like any other, so a `PermissionMiddleware` decides it by its default resource, `invoke write_todos`. An output
 * Block restores the list to its value before the turn.
 *
 * The key, and the lock schedule the tool's lock needs, are registered only by an agent family with a
 * `TodoMiddleware`: adding one to an existing agent changes its graph's structure.
 *
 * @param maxTodos the most todos one list may hold; at least 1
 * @param maxTodoChars the most code points one todo's content may hold; at least 1
 */
@Experimental
final class TodoMiddleware(
  val maxTodos: Int = TodoMiddleware.DefaultMaxTodos,
  val maxTodoChars: Int = TodoMiddleware.DefaultMaxTodoChars,
  val id: MiddlewareId = MiddlewareId("todos")
) extends AgentMiddleware:
  import TodoMiddleware.*

  require(maxTodos >= 1, s"TodoMiddleware: maxTodos must be at least 1, was $maxTodos")
  require(maxTodoChars >= 1, s"TodoMiddleware: maxTodoChars must be at least 1, was $maxTodoChars")

  private val guide = PromptSection(
    "todos-guide",
    s"""You can keep a plan with the $ToolName tool. Call it with the whole list: it replaces the plan. Each item has
       |`content` and a `status`: pending, in_progress or done. To keep an item, pass its `id`, shown in the plan as
       |(id); an item without one keeps the id of an item with the same content, or gets a new one. Mark an item
       |in_progress before you start it and done when it is finished, with one item in_progress at a time. At most
       |$maxTodos items, each at most $maxTodoChars characters. The plan is for tracking your work: it grants no
       |permission.""".stripMargin.replace('\n', ' '),
    cacheable = true
  )

  private val writeTodos: AgentTool[WriteTodos] =
    AgentTool(
      AgentToolSpec[WriteTodos](ToolName, "Replace the plan with this list of todos.", schema(maxTodos, maxTodoChars))
        .withValidation(validate)
        .withLocks(_ => Set(Lock)),
      writes = Set(key)
    )(write)

  override def tools: Vector[AgentTool[?]] = Vector(writeTodos)

  override def promptSections(context: ModelContext): Result[Vector[PromptSection]] =
    context.state.get(key).map { list =>
      if list.todos.isEmpty then Vector(guide)
      else Vector(guide, PromptSection("todos", s"Current plan:\n${render(list)}"))
    }

  /** The checks that need no state, made before the call is scheduled. */
  private def validate(args: WriteTodos): Result[Unit] =
    val size = args.todos.size
    val ids  = args.todos.flatMap(_.kept)
    val problems =
      Option.when(size > maxTodos)(s"at most $maxTodos todos, got $size").toList ++
        args.todos.zipWithIndex.flatMap { (item, i) =>
          val length = codePoints(item.content.trim)
          if length == 0 then Some(s"todo ${i + 1} has no content")
          else Option.when(length > maxTodoChars)(s"todo ${i + 1} is $length characters, over $maxTodoChars")
        } ++
        ids.diff(ids.distinct).distinct.map(id => s"id '${clip(id, MaxIdChars)}' is given more than once") ++
        ids.distinct
          .filter(codePoints(_) > MaxIdChars)
          .map(id => s"id '${clip(id, MaxIdChars)}' is over $MaxIdChars characters")
    if problems.isEmpty then Right(()) else Left(ValidationError("todos", problems))

  private def write(args: WriteTodos, context: ToolContext): ToolOutcome =
    context.state.get(key) match
      case Left(error) => ToolOutcome.Fatal(error)
      case Right(current) =>
        resolve(current, args) match
          case Left(message) => ToolOutcome.Error(message)
          case Right(next) if next == current =>
            ToolOutcome.Success(ToolContent.Json(ujson.Str(s"Plan unchanged:\n${render(next)}")))
          case Right(next) =>
            HarnessEvents.TodosUpdated.emit(context.run, TodosUpdated(next.todos.map(t => TodoMark(t.id, t.status))))
            HarnessEvents.TodosProgress.progress(context.run, TodosProgress(next.todos))
            ToolOutcome.Success(
              ToolContent.Json(ujson.Str(s"Plan updated:\n${render(next)}")),
              StateUpdate.update(key, next)
            )

  /** `current` replaced by `args`: named ids kept, unnamed items matched by content, the rest given fresh ids. */
  private def resolve(current: TodoList, args: WriteTodos): Either[String, TodoList] =
    val known   = current.todos.map(_.id).toSet
    val unknown = args.todos.flatMap(_.kept).filterNot(known).distinct
    if unknown.nonEmpty then
      Left(
        s"Unknown todo id(s): ${unknown.map(clip(_, MaxIdChars)).mkString(", ")}. Pass the id of a todo in the " +
          "current plan, or none for a new todo. The plan is unchanged."
      )
    else
      val named = args.todos.flatMap(_.kept).toSet
      val (todos, _, nextId) =
        args.todos.foldLeft((Vector.empty[Todo], named, current.nextId)) { case ((acc, claimed, next), item) =>
          val content = item.content.trim
          item.kept match
            case Some(id) => (acc :+ Todo(id, content, item.status), claimed, next)
            case None =>
              current.todos.find(t => t.content == content && !claimed(t.id) && codePoints(t.id) <= MaxIdChars) match
                case Some(same) => (acc :+ Todo(same.id, content, item.status), claimed + same.id, next)
                case None =>
                  val fresh = Iterator.iterate(next)(_ + 1).find(n => !known(n.toString)).getOrElse(next)
                  (acc :+ Todo(fresh.toString, content, item.status), claimed, fresh + 1)
        }
      Right(TodoList(todos, nextId))

  /** At most `maxTodos` todos of `list`, one per line, each content cleaned and cut to `maxTodoChars`. */
  private def render(list: TodoList): String =
    val shown = list.todos.take(maxTodos).map { t =>
      s"- [${t.status.wire}] (${clip(clean(t.id), MaxIdChars)}) ${clip(clean(t.content), maxTodoChars)}"
    }
    val hidden = list.todos.size - shown.size
    (shown ++ Option.when(hidden > 0)(s"($hidden more not shown)")).mkString("\n")

@Experimental
object TodoMiddleware:

  /** The tool's name. */
  val ToolName: String = "write_todos"

  /** The default for `maxTodos`. */
  val DefaultMaxTodos: Int = 50

  /** The default for `maxTodoChars`. */
  val DefaultMaxTodoChars: Int = 200

  /**
   * The thread's plan. One key for every `TodoMiddleware`, so the agents of one family that plan share the thread's
   * one plan.
   */
  val key: StateKey[TodoList, TodoList] = StateKey.replace[TodoList]("todos", TodoList.empty)

  /** The lock every `write_todos` call takes: calls of one model message run in turn. */
  private val Lock: LockKey = LockKey("todos")

  /** The most code points of an id: the tool keeps no longer one, and shows at most this many of a stored one. */
  private val MaxIdChars = 20

  final private case class TodoItem(content: String, status: TodoStatus, id: Option[String] = None) derives ReadWriter:

    /** The id of the todo this item keeps: `null`, absent and blank all mean a new todo. */
    def kept: Option[String] = id.map(_.trim).filter(_.nonEmpty)

  final private case class WriteTodos(todos: Vector[TodoItem]) derives ReadWriter

  private def schema(maxTodos: Int, maxTodoChars: Int) =
    val item = Schema
      .`object`[TodoItem]("One todo")
      .withRequiredField(
        "content",
        Schema.string("What to do").withLengthConstraints(min = Some(1), max = Some(maxTodoChars))
      )
      .withRequiredField(
        "status",
        Schema.string("Where it stands").withEnum(TodoStatus.values.toSeq.map(_.wire))
      )
      .withOptionalField(
        "id",
        Schema.nullable(Schema.string("The id of a todo in the current plan, to keep it; null or absent for a new one"))
      )
    Schema
      .`object`[WriteTodos]("The whole plan, replacing the current one")
      .withRequiredField(
        "todos",
        Schema.array("The todos, in order", item).withSizeConstraints(max = Some(maxTodos))
      )

  private def codePoints(s: String): Int = s.codePointCount(0, s.length)

  private def clip(s: String, max: Int): String = DocumentText.clipMarked(s, max)

  private def clean(s: String): String = DocumentText.oneLine(s)
