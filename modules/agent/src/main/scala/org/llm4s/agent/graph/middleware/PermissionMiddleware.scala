package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.tool.{ Glob, Operation, ToolContext, ToolOutcome, ToolResource }
import org.llm4s.annotation.Experimental

/** What a [[PermissionRule]] decides for a resource it matches. */
@Experimental
enum PermissionDecision:
  case Allow, Deny, Ask

/** Decides `operation`s on targets `target` matches. In a [[PermissionMiddleware]], the first matching rule wins. */
@Experimental
final case class PermissionRule(operation: Operation, target: Glob, decision: PermissionDecision):

  /** Whether this rule decides `resource`: the same operation, and a target the glob matches. */
  def matches(resource: ToolResource): Boolean = resource.operation == operation && target.matches(resource.target)

object PermissionRule:
  def allow(operation: Operation, pattern: String): PermissionRule =
    PermissionRule(operation, Glob(pattern), PermissionDecision.Allow)
  def deny(operation: Operation, pattern: String): PermissionRule =
    PermissionRule(operation, Glob(pattern), PermissionDecision.Deny)
  def ask(operation: Operation, pattern: String): PermissionRule =
    PermissionRule(operation, Glob(pattern), PermissionDecision.Ask)

/**
 * Ordered permission rules over each tool call's resources (design §5.6), checked after the call's
 * arguments were validated and decoded, on `ToolCallRequest.resources`.
 *
 * For each resource the first rule that matches it decides, and a resource no rule matches is
 * **denied**. A call runs only if every resource is allowed: a denied one gives
 * `ToolOutcome.Error("Denied: <operation> <target>")` (the first, in resource order); otherwise one to
 * ask about gives `NeedsApproval`, unless the call is already approved. Approval is one flag per call:
 * once a reviewer approves it - whichever middleware or tool asked - every `Ask` passes. An edited call
 * is checked again on its new arguments, so a deny rule still refuses it.
 *
 * Register it first, so it wraps every other middleware and a denied call is refused before anything
 * else asks for approval of it. An `Execute` rule limits which commands the agent may ask to run, not
 * what a command does: a sandbox constrains that itself.
 */
@Experimental
final class PermissionMiddleware(rules: Seq[PermissionRule], val id: MiddlewareId = MiddlewareId("permissions"))
    extends AgentMiddleware:

  private val ordered = rules.toVector

  /** The decision for `resource`: the first matching rule's, or `Deny` when none matches. */
  def decide(resource: ToolResource): PermissionDecision =
    ordered.find(_.matches(resource)).fold(PermissionDecision.Deny)(_.decision)

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    val decided = request.resources.map(r => r -> decide(r))
    decided.collectFirst { case (r, PermissionDecision.Deny) => r } match
      case Some(denied) => ToolOutcome.Error(s"Denied: ${PermissionMiddleware.describe(denied)}")
      case None =>
        val asked = decided.collect { case (r, PermissionDecision.Ask) => r }
        if asked.isEmpty || context.approved then next()
        else
          ToolOutcome.NeedsApproval(s"Permission required: ${asked.map(PermissionMiddleware.describe).mkString(", ")}")

object PermissionMiddleware:
  private def describe(resource: ToolResource): String = s"${resource.operation.label} ${resource.target}"
