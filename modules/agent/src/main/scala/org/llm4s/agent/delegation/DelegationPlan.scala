package org.llm4s.agent.delegation

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.middleware.{ MiddlewareId, PermissionRule }
import org.llm4s.annotation.Experimental

/**
 * What an agent's subagents run with (design 4.45), as `Agent.delegation` gives it: a plain data value, built by
 * `AgentBuilder.build()` from the same inputs the subagents are compiled from, so it shows what runs. Its `toString`
 * is one line per subagent and nested subagent, for logs.
 *
 * @param subagents every subagent of every agent of the family, in the order each agent declares them
 */
@Experimental
final case class DelegationPlan private[agent] (subagents: Vector[SubagentPlan]):

  /** The subagent `id` of the family's agent `parent`; a nested subagent is found in its parent's `subagents`. */
  def find(parent: AgentId, id: AgentId): Option[SubagentPlan] = subagents.find(s => s.parent == parent && s.id == id)

  override def toString: String =
    if subagents.isEmpty then "DelegationPlan(no subagents)"
    else ("DelegationPlan:" +: subagents.flatMap(_.lines("  "))).mkString("\n")

object DelegationPlan:

  /** The plan of an agent without subagents. */
  val empty: DelegationPlan = DelegationPlan(Vector.empty)

/**
 * One subagent's plan (design 4.45).
 *
 * @param parent the agent that delegates to it
 * @param id the subagent's id
 * @param toolName the parent's tool that runs it: `task`, or `task_<id>`
 * @param tools every tool the child's model is offered, its own (its tools, its harness's, its middleware's and its
 *   own task tools) and those granted from its parent (granted tools and granted middleware's tools)
 * @param middleware the child's middleware ids in stack order, outermost first: `ancestor-permissions` when an
 *   ancestor has permission rules, then the granted middleware, its harness's and its own, then `delegation` when it
 *   may delegate
 * @param ancestorRules the permission rules of every ancestor, outermost first, that `ancestor-permissions` applies
 * @param model the client the child calls, by its class name (a client exposes no model name)
 * @param modelInherited whether that client and its options are the parent's (no `TypedAgent.withModel`)
 * @param budget the budget granted; each limit is also capped by the parent's remainder when the task is called
 * @param delegates whether the child has task tools of its own: delegation granted, subagents declared, and room
 *   under `maxDepth`
 * @param depth how deep the child runs: 1 below a top-level agent
 * @param maxDepth the deepest delegation may nest
 * @param subagents the child's own subagents' plans, when it delegates
 */
@Experimental
final case class SubagentPlan private[agent] (
  parent: AgentId,
  id: AgentId,
  toolName: String,
  tools: Vector[PlannedTool],
  middleware: Vector[MiddlewareId],
  ancestorRules: Vector[AncestorRules],
  model: String,
  modelInherited: Boolean,
  budget: TaskBudget,
  delegates: Boolean,
  depth: Int,
  maxDepth: Int,
  subagents: Vector[SubagentPlan]
):

  private[delegation] def lines(indent: String): Vector[String] =
    val toolList = tools.map(t => if t.granted then s"${t.name} (granted)" else t.name).mkString(", ")
    val rules    = ancestorRules.map(a => s"${a.agent.value}: ${a.rules.size} rules").mkString(", ")
    val head =
      s"$indent${parent.value} -> ${id.value} via $toolName: tools [$toolList]; middleware [${middleware.mkString(", ")}]" +
        (if ancestorRules.isEmpty then "" else s"; ancestor rules [$rules]") +
        s"; model $model${if modelInherited then " (inherited)" else ""}; $budget" +
        s"; ${if delegates then "delegates" else "does not delegate"}; depth $depth of $maxDepth"
    head +: subagents.flatMap(_.lines(indent + "  "))

/** A tool a subagent's model is offered, and whether it was granted from the parent rather than the child's own. */
@Experimental
final case class PlannedTool private[agent] (name: String, granted: Boolean)

/** The permission rules of one ancestor's `PermissionMiddleware`, which a subagent's calls must also pass. */
@Experimental
final case class AncestorRules private[agent] (agent: AgentId, rules: Vector[PermissionRule])
