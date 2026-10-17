package org.llm4s.agent.graph.tool

import org.llm4s.annotation.Experimental

import java.util.Locale

/** What a tool call does to a [[ToolResource]]; permission rules match on it. */
@Experimental
enum Operation:
  case Read, Write, Delete, Execute, Network, Invoke

  /**
   * The lower-case name used in denial and approval messages: `write`, `execute`. Lower-cased in the root
   * locale, so it is the same under every default locale (in Turkish, `INVOKE` would otherwise lose its dot).
   */
  def label: String = toString.toLowerCase(Locale.ROOT)

/**
 * One thing a tool call touches, as its tool declares with [[AgentToolSpec.withResources]]: the
 * `operation`, and the `target` a permission rule's [[Glob]] matches - a tool name, a path or a URL.
 * A tool that declares none touches `ToolResource(Operation.Invoke, <its name>)`.
 */
@Experimental
final case class ToolResource(operation: Operation, target: String)
