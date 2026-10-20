package org.llm4s.agent.delegation

import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  Placement,
  PermissionDecision,
  PermissionMiddleware,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome, ToolResource }

/**
 * Every ancestor's permission rules, outermost in a subagent's stack (design 4.45, rule 15): permissions only narrow.
 * A call runs only if each ancestor's rules allow each of its resources - each level decided as its
 * `PermissionMiddleware` decides, first match wins and an unmatched resource denied - and then the child's own
 * middleware. A `Deny` at any level denies (`Denied: ...`, naming the level's agent); otherwise an `Ask` at any level
 * asks once, and the approval covers every level, the child's own included, since approval is one flag per call and an
 * edited call is decided again by every level. The child's own `PermissionMiddleware` rules are read too, for their
 * denials only, so a call the child denies is denied before an ancestor asks a reviewer to approve it (their asks are
 * the child's own middleware's, inside). An ancestor without a `PermissionMiddleware` adds no level. It must run
 * outside every other middleware of the stack ([[placement]]).
 */
final private[agent] class AncestorPermissions(
  val levels: Vector[AncestorRules],
  own: Vector[AncestorRules] = Vector.empty
) extends AgentMiddleware:

  val id: MiddlewareId = AncestorPermissions.Id

  private val deciders = levels.map(level => level -> new PermissionMiddleware(level.rules))

  /** The child's own rules, read for their denials only: their asks are its own middleware's, which run inside. */
  private val ownDeciders = own.map(level => level -> new PermissionMiddleware(level.rules))

  /**
   * Outside every other middleware of the child's stack: one ordered before it (by `runsBefore`, say) would see, and
   * could act on, a call an ancestor denies. `build()` refuses such a stack.
   */
  override def placement(other: AgentMiddleware): Option[Placement] =
    Some(
      Placement.Outside("every ancestor's permission rules decide a subagent's call before its own middleware sees it")
    )

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    val decided =
      for
        resource         <- request.resources
        (level, decider) <- deciders
      yield (resource, level, decider.decide(resource))
    val ownDenied =
      for
        resource         <- request.resources
        (level, decider) <- ownDeciders
        if decider.decide(resource) == PermissionDecision.Deny
      yield (resource, level, PermissionDecision.Deny)
    // a denial at any level, the child's own included, wins before any level asks
    (decided ++ ownDenied).collectFirst { case (resource, level, PermissionDecision.Deny) => resource -> level } match
      case Some((resource, level)) =>
        ToolOutcome.Error(s"Denied: ${AncestorPermissions.describe(resource)} (by agent '${level.agent.value}')")
      case None =>
        val asked = decided.collect { case (resource, _, PermissionDecision.Ask) => resource }.distinct
        if asked.isEmpty || context.approved then next()
        else
          ToolOutcome.NeedsApproval(
            s"Permission required: ${asked.map(AncestorPermissions.describe).mkString(", ")}"
          )

private[agent] object AncestorPermissions:
  val Id: MiddlewareId = MiddlewareId("ancestor-permissions")

  private def describe(resource: ToolResource): String = s"${resource.operation.label} ${resource.target}"
