package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolOutcome }
import org.llm4s.toolapi.Schema

/**
 * The tools that offer an agent's handoffs to its model, one `handoff_to_<target>` per
 * [[LoopHandoff]]. They are stand-ins: the model node intercepts a handoff call and routes it, and
 * the call-tool path never sees them, so `execute` is never reached.
 */
private[toolloop] object HandoffTools:
  val Prefix = "handoff_to_"

  def toolName(target: AgentId): String = s"$Prefix${target.value}"

  /** The error every call of a message gets when a handoff is not its only call. */
  val MixedBatch = "A handoff must be the only tool call in a message; no call in this message was run"

  /** The result a transfer records for its call. */
  def transferred(target: AgentId): String = transferredTo(target.value)

  private[toolloop] def transferredTo(target: String): String = s"Transferred to $target"

  /** A stand-in AgentTool per handoff, so the model is offered it; ToolLoop intercepts the call. */
  def tools(handoffs: Vector[LoopHandoff]): Vector[AgentTool[ujson.Value]] =
    handoffs.map { handoff =>
      val name = toolName(handoff.target)
      val description =
        handoff.reason.fold("Hand off this query to a specialist agent.")(r =>
          s"Hand off this query to a specialist agent. $r"
        )
      val schema = Schema
        .`object`[ujson.Value]("Handoff parameters")
        .withRequiredField("reason", Schema.string("Reason for the handoff"))
      AgentTool(AgentToolSpec[ujson.Value](name, description, schema))((_, _) =>
        ToolOutcome.Error(s"'$name' is a handoff, routed by the loop; it never runs as a tool")
      )
    }
