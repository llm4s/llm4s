package org.llm4s.agent.harness

import org.llm4s.agent.graph.EventType
import org.llm4s.annotation.Experimental
import upickle.default.ReadWriter

/**
 * The events the harness middleware send, each an [[org.llm4s.agent.graph.EventType]] to match a `StreamEvent` with,
 * as [[org.llm4s.agent.events.AgentEvents]] are.
 *
 * Durable (stored, replayed, no content): [[TodosUpdated]], [[DocumentsReported]]. Live (current subscribers only):
 * [[TodosProgress]].
 */
@Experimental
object HarnessEvents:

  /** The plan changed: its ids and statuses, committed with the `write_todos` call that changed it. */
  val TodosUpdated: EventType[org.llm4s.agent.harness.TodosUpdated] = EventType("harness.todos_updated", 1)

  /** The plan changed: the whole list, content included, delivered live as the `write_todos` call returns. */
  val TodosProgress: EventType[org.llm4s.agent.harness.TodosProgress] = EventType("harness.todos_progress", 1)

  /**
   * What a run's read of its skills (`SkillsMiddleware`) or instruction files left out or cut: a file not listed, cut,
   * malformed or unreadable. Committed with the run's first model step, and again by a recovered run, which reads
   * again; paths and the reader's own words only, never a file's content.
   */
  val DocumentsReported: EventType[org.llm4s.agent.harness.DocumentsReported] =
    EventType("harness.documents_reported", 1)

/** A todo's id and status, without its content. */
@Experimental
final case class TodoMark(id: String, status: TodoStatus) derives ReadWriter

/** The plan after a change, as ids and statuses in order; durable, so it carries no content. */
@Experimental
final case class TodosUpdated(todos: Vector[TodoMark]) derives ReadWriter

/** The plan after a change, content included; live only. */
@Experimental
final case class TodosProgress(todos: Vector[Todo]) derives ReadWriter

/**
 * One file a read of documents through a backend left out or changed: `path`, what happened (`kind`), the 1-based
 * `line` it concerns when there is one, and `detail` in the reader's own words - never the file's content.
 */
@Experimental
final case class DocumentIssue(path: VPath, kind: DocumentIssue.Kind, line: Option[Int], detail: String)
    derives ReadWriter

@Experimental
object DocumentIssue:

  /**
   * `NotListed`: left out by a count or size limit. `Cut`: shown, cut at a size limit. `Malformed`: not in the
   * expected format. `Unreadable`: the backend refused it. `Shadowed`: replaced by a later one of the same name.
   * `NotScanned`: a folder held more entries than were looked at.
   */
  enum Kind derives ReadWriter:
    case NotListed, Cut, Malformed, Unreadable, Shadowed, NotScanned

/**
 * The issues of one run's read by the middleware `middleware`: at most 32, in the order found, and the number
 * `omitted` beyond them. Durable, so it carries no content.
 */
@Experimental
final case class DocumentsReported(middleware: String, issues: Vector[DocumentIssue], omitted: Int) derives ReadWriter
