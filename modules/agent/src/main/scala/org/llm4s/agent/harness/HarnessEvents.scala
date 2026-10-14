package org.llm4s.agent.harness

import org.llm4s.agent.graph.EventType
import org.llm4s.annotation.Experimental
import upickle.default.ReadWriter

/**
 * The events the harness middleware send, each an [[org.llm4s.agent.graph.EventType]] to match a `StreamEvent` with,
 * as [[org.llm4s.agent.events.AgentEvents]] are.
 *
 * Durable (stored, replayed, no content): [[TodosUpdated]]. Live (current subscribers only): [[TodosProgress]].
 */
@Experimental
object HarnessEvents:

  /** The plan changed: its ids and statuses, committed with the `write_todos` call that changed it. */
  val TodosUpdated: EventType[org.llm4s.agent.harness.TodosUpdated] = EventType("harness.todos_updated", 1)

  /** The plan changed: the whole list, content included, delivered live as the `write_todos` call returns. */
  val TodosProgress: EventType[org.llm4s.agent.harness.TodosProgress] = EventType("harness.todos_progress", 1)

/** A todo's id and status, without its content. */
@Experimental
final case class TodoMark(id: String, status: TodoStatus) derives ReadWriter

/** The plan after a change, as ids and statuses in order; durable, so it carries no content. */
@Experimental
final case class TodosUpdated(todos: Vector[TodoMark]) derives ReadWriter

/** The plan after a change, content included; live only. */
@Experimental
final case class TodosProgress(todos: Vector[Todo]) derives ReadWriter
