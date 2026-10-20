package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.tool.{ Glob, Operation, ToolContext, ToolOutcome, ToolResource }
import org.llm4s.annotation.Experimental

import scala.annotation.tailrec

/** What a [[PermissionRule]] decides for a resource it matches. */
@Experimental
enum PermissionDecision:
  case Allow, Deny, Ask

/** Decides `operation`s on targets `target` matches. In a [[PermissionMiddleware]], the first matching rule wins. */
@Experimental
final case class PermissionRule(operation: Operation, target: Glob, decision: PermissionDecision):

  /** Whether this rule decides `resource`: the same operation, and a target the glob matches. */
  def matches(resource: ToolResource): Boolean = resource.operation == operation && target.matches(resource.target)

  /** Whether this rule decides every target of `resource`'s envelope: the same operation, and a glob covering it. */
  def covers(resource: ToolResource): Boolean =
    resource.operation == operation && target.covers(Glob(resource.target))

  /** Whether this rule decides some target of `resource`'s envelope: the same operation, and an overlapping glob. */
  def overlaps(resource: ToolResource): Boolean =
    resource.operation == operation && target.overlaps(Glob(resource.target))

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

  /** The rules, in order: a subagent's calls must also pass them (design 4.45, `AncestorPermissions`). */
  private[agent] def orderedRules: Vector[PermissionRule] = ordered

  /**
   * The decision for `resource`: for a concrete target, the first matching rule's, or `Deny` when none
   * matches. A target is read as a [[Glob]], so one written as a pattern - an envelope such as `/out/&#42;&#42;`,
   * declared by a tool whose answer picks the path - is decided for every target in it: the rules are read
   * in order, and the envelope is denied as soon as a `Deny` rule reaches any of its targets, asked about
   * once an `Ask` rule reaches some, and allowed only when an `Allow` rule covers all that remain; if no
   * rule covers the rest, some target is unmatched, so it is denied. An `Allow` that covers only part does
   * not settle it, so a later `Deny` reaching the rest still denies (conservatively, even when the earlier
   * allow already decided the targets they share). For a concrete target, reaching and covering are both
   * matching, so this is first match wins.
   */
  def decide(resource: ToolResource): PermissionDecision =
    @tailrec def scan(rules: List[PermissionRule], asked: Boolean): PermissionDecision = rules match
      case Nil => PermissionDecision.Deny
      case rule :: rest =>
        if !rule.overlaps(resource) then scan(rest, asked)
        else
          rule.decision match
            case PermissionDecision.Deny => PermissionDecision.Deny
            case PermissionDecision.Ask =>
              if rule.covers(resource) then PermissionDecision.Ask else scan(rest, asked = true)
            case PermissionDecision.Allow =>
              if rule.covers(resource) then if asked then PermissionDecision.Ask else PermissionDecision.Allow
              else scan(rest, asked)
    scan(ordered.toList, asked = false)

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
