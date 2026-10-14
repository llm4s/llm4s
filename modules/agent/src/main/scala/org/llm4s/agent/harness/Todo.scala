package org.llm4s.agent.harness

import org.llm4s.annotation.Experimental
import upickle.default.ReadWriter

/** Where a [[Todo]] stands. On the wire, and in the `write_todos` schema: `pending`, `in_progress`, `done`. */
@Experimental
enum TodoStatus:
  case Pending, InProgress, Done

  /** The name the tool's schema offers and the stored value uses. */
  def wire: String = this match
    case Pending    => "pending"
    case InProgress => "in_progress"
    case Done       => "done"

@Experimental
object TodoStatus:

  /** The status named `name` on the wire, if any. */
  def fromWire(name: String): Option[TodoStatus] = values.find(_.wire == name)

  given ReadWriter[TodoStatus] =
    upickle.default
      .readwriter[String]
      .bimap(_.wire, name => fromWire(name).getOrElse(throw upickle.core.Abort(s"unknown todo status '$name'")))

/**
 * One item of an agent's plan. `id` is assigned by the `write_todos` tool and stays with the item while the model
 * keeps it in the list; it is never given to another item of the same thread.
 */
@Experimental
final case class Todo private (id: String, content: String, status: TodoStatus):
  def withContent(c: String): Todo    = Todo(id, c, status)
  def withStatus(s: TodoStatus): Todo = Todo(id, content, s)

@Experimental
object Todo:
  def apply(id: String, content: String, status: TodoStatus): Todo = new Todo(id, content, status)

  final private case class Wire(id: String, content: String, status: TodoStatus) derives ReadWriter

  given ReadWriter[Todo] =
    summon[ReadWriter[Wire]].bimap[Todo](t => Wire(t.id, t.content, t.status), w => Todo(w.id, w.content, w.status))

/**
 * A thread's plan, as [[TodoMiddleware.key]] stores it: the `todos` in order, and `nextId`, the number the next new
 * todo's id takes, so no id is reused within the thread.
 */
@Experimental
final case class TodoList private (todos: Vector[Todo], nextId: Long):
  def withTodos(t: Vector[Todo]): TodoList = TodoList(t, nextId)
  def withNextId(n: Long): TodoList        = TodoList(todos, n)

@Experimental
object TodoList:
  def apply(todos: Vector[Todo], nextId: Long): TodoList = new TodoList(todos, nextId)

  /** No todos; the first new one takes id `1`. */
  val empty: TodoList = TodoList(Vector.empty, 1L)

  final private case class Wire(todos: Vector[Todo], nextId: Long) derives ReadWriter

  given ReadWriter[TodoList] =
    summon[ReadWriter[Wire]].bimap[TodoList](l => Wire(l.todos, l.nextId), w => TodoList(w.todos, w.nextId))
