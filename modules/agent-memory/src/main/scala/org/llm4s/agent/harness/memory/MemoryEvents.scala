package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.EventType
import org.llm4s.annotation.Experimental
import upickle.default.ReadWriter

/**
 * The durable events of [[SemanticMemoryMiddleware]], each an [[org.llm4s.agent.graph.EventType]] to match a
 * `StreamEvent` with. They carry counts and names only, never memory content or an error message (rule 9).
 */
@Experimental
object MemoryEvents:

  /** A turn was recorded: committed with the turn's final answer. */
  val Recorded: EventType[MemoryRecorded] = EventType("memory.recorded", 1)

  /** Old memories were consolidated after a turn. */
  val Consolidated: EventType[MemoryConsolidated] = EventType("memory.consolidated", 1)

  /** A memory operation failed and was skipped; the turn went on. */
  val Skipped: EventType[MemorySkipped] = EventType("memory.skipped", 1)

/** The turn's recording stored `added` memories, and evicted `evicted` to keep the store within its bounds. */
@Experimental
final case class MemoryRecorded(added: Int, evicted: Int) derives ReadWriter

/** `groups` groups were consolidated: `removed` memories replaced by one summary each, `evicted` evicted. */
@Experimental
final case class MemoryConsolidated(groups: Int, removed: Int, evicted: Int) derives ReadWriter

/**
 * `operation` (`memory.recall`, `memory.record`, `memory.consolidate` or `memory.resolve`) failed with an error of type
 * `error` (its class's simple name), and was skipped.
 */
@Experimental
final case class MemorySkipped(operation: String, error: String) derives ReadWriter
