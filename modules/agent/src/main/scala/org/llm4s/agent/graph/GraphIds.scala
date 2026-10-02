package org.llm4s.agent.graph

/** Stable identifier of a node in a compiled graph; persisted in snapshots. */
opaque type NodeId = String

/** Identifier of one scheduled node execution; unique within a thread's history. */
opaque type TaskId = String

/** Stable identifier of a static or dynamic join barrier; persisted in snapshots. */
opaque type JoinId = String

/** Stable identifier of a [[StateKey]]; persisted in snapshots. */
opaque type StateKeyId = String

object NodeId:
  def apply(value: String): NodeId         = value
  extension (id: NodeId) def value: String = id

object TaskId:
  def apply(value: String): TaskId         = value
  extension (id: TaskId) def value: String = id

object JoinId:
  def apply(value: String): JoinId         = value
  extension (id: JoinId) def value: String = id

object StateKeyId:
  def apply(value: String): StateKeyId         = value
  extension (id: StateKeyId) def value: String = id
