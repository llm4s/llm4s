package org.llm4s.agent

import org.llm4s.agent.graph.StateUpdate
import org.llm4s.agent.graph.tool.{ AgentTool, AgentToolSpec, ToolOutcome }
import org.llm4s.agent.graph.toolloop.{ HandoffRequest, ToolLoop }
import org.llm4s.error.ValidationError
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import upickle.default.*

/** The result a handoff tool gives the model: the same JSON the model has always seen for a handoff call. */
private[agent] case class HandoffResult(handoff_requested: Boolean, handoff_id: String, reason: String)

private[agent] object HandoffResult {
  implicit val handoffResultRW: ReadWriter[HandoffResult] = macroRW[HandoffResult]
}

/**
 * The tools that make [[Handoff]]s callable: one `handoff_to_<id>` tool per handoff. Calling one records a
 * [[HandoffRequest]] naming the handoff by its stable id (never by a reference to the target agent), which ends the
 * tool loop after its batch; the [[Agent]] then resolves the id against the handoffs it was given and runs the target.
 */
private[agent] object HandoffTools {

  /**
   * One tool per handoff, or a `ValidationError` listing every problem: an id outside `[a-zA-Z0-9_-]{1,52}`, an id
   * used twice, or a handoff tool name that is already a registered tool.
   */
  def create(handoffs: Seq[Handoff], registeredToolNames: Set[String]): Result[Vector[AgentTool[ujson.Value]]] = {
    def quoted(ids: Seq[String]): String = ids.map(id => s"'$id'").mkString(", ")
    val ids                              = handoffs.map(_.id)
    val invalid                          = ids.filterNot(Handoff.isValidId).distinct
    val duplicates                       = ids.distinct.filter(id => ids.count(_ == id) > 1)
    val clashing                         = handoffs.map(_.handoffId).distinct.filter(registeredToolNames.contains)
    val problems = List(
      Option.when(invalid.nonEmpty)(s"invalid handoff ids: ${quoted(invalid)}"),
      Option.when(duplicates.nonEmpty)(s"duplicate handoff ids: ${quoted(duplicates)}"),
      Option.when(clashing.nonEmpty)(s"handoff tool names already registered as tools: ${quoted(clashing)}")
    ).flatten
    if (problems.nonEmpty) Left(ValidationError("handoffs", problems))
    else Right(handoffs.toVector.map(toolFor))
  }

  private def toolFor(handoff: Handoff): AgentTool[ujson.Value] = {
    val description = handoff.transferReason.fold(
      "Hand off this query to a specialist agent."
    )(reason => s"Hand off this query to a specialist agent. $reason")

    val schema = Schema
      .`object`[ujson.Value]("Handoff parameters")
      .withRequiredField("reason", Schema.string("Reason for the handoff"))

    AgentTool(AgentToolSpec[ujson.Value](handoff.handoffId, description, schema), Set(ToolLoop.handoff)) { (args, _) =>
      val reason = args.objOpt.flatMap(_.get("reason")).flatMap(_.strOpt).getOrElse("No reason provided")
      ToolOutcome.Success(
        writeJs(HandoffResult(handoff_requested = true, handoff_id = handoff.handoffId, reason = reason)),
        StateUpdate.update(ToolLoop.handoff, HandoffRequest(handoff.id, reason))
      )
    }
  }
}
