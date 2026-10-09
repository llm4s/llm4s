package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.tool.{ ToolContext, ToolOutcome }

/**
 * Asks for approval before a tool call runs. `requires` returns the reason to ask, or `None` to
 * let the call through; a call that is already approved always passes. A tool's own approval
 * request is unaffected.
 */
final class ApprovalMiddleware(
  requires: ToolCallRequest => Option[String],
  val id: MiddlewareId = MiddlewareId("approval")
) extends AgentMiddleware:

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    if context.approved then next()
    else requires(request).fold(next())(ToolOutcome.NeedsApproval(_))

object ApprovalMiddleware:

  /** Asks for every tool whose hints are not `readOnly`. */
  def unlessReadOnly: ApprovalMiddleware =
    new ApprovalMiddleware(request =>
      Option.when(!request.spec.hints.readOnly)(s"tool '${request.spec.name}' is not read-only")
    )
