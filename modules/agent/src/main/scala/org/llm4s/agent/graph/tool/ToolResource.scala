package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental

/** What a tool call does to a [[ToolResource]]; permission rules match on it. */
@Experimental
enum Operation:
  case Read, Write, Delete, Execute, Network, Invoke

  /** The lower-case name used in denial and approval messages: `write`, `execute`. */
  def label: String = toString.toLowerCase

/**
 * One thing a tool call touches, as its tool declares with [[AgentToolSpec.withResources]]: the
 * `operation`, and the `target` a permission rule's [[Glob]] matches - a tool name, a path or a URL.
 * A tool that declares none touches `ToolResource(Operation.Invoke, <its name>)`.
 */
@Experimental
final case class ToolResource(operation: Operation, target: String)
