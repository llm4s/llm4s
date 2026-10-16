package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental

/**
 * A lock a tool call holds while it runs, as its tool declares with [[AgentToolSpec.withLocks]]: a
 * path of segments, such as `files/state/files`, `workspace/<id>` or `workspace/<id>/src/a.scala`. Two keys
 * conflict when one is a prefix of the other, segment by segment, so a workspace-wide lock conflicts
 * with every file lock in that workspace, and a directory's with every lock beneath it; `a/b` does
 * not conflict with `a/bc`. The key with no segments is the root, and conflicts with every key.
 *
 * Calls of one model message whose locks conflict run one after another, in call order, each in its
 * own superstep, so each sees the state the earlier one committed; see [[AgentToolSpec.withLocks]].
 * A batch records its calls' keys rendered (`render`), which `LockKey(rendered)` reads back exactly.
 */
opaque type LockKey = Vector[String]

@Experimental
object LockKey:

  /** The key of the parts' `/`-separated segments, in order; empty segments are dropped. */
  def apply(path: String, more: String*): LockKey = segmentsOf(path +: more)

  private def segmentsOf(parts: Seq[String]): Vector[String] =
    parts.iterator.flatMap(_.split('/')).filter(_.nonEmpty).toVector

  extension (key: LockKey)

    /** The key's segments, outermost first; empty for the root. */
    def segments: Vector[String] = key

    /** This key with `path`'s `/`-separated segments appended. */
    def /(path: String): LockKey = key ++ segmentsOf(Seq(path))

    /** Whether one of the two keys is a prefix of the other, segment by segment. */
    def conflictsWith(other: LockKey): Boolean = key.startsWith(other) || other.startsWith(key)

    /** The segments joined with `/`. */
    def render: String = key.mkString("/")
