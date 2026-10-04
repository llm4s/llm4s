package org.llm4s.agent

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Represents a handoff to another agent.
 *
 * Handoffs provide a simpler alternative to DAG-based orchestration
 * for common delegation patterns. The LLM decides when to invoke a handoff
 * by calling a generated handoff tool.
 *
 * Example:
 * ```scala
 * val generalAgent = new Agent(client)
 * val specialistAgent = new Agent(client)
 *
 * generalAgent.run(
 *   "Explain quantum entanglement",
 *   tools,
 *   handoffs = Seq(
 *     Handoff(
 *       id = "physics",
 *       targetAgent = specialistAgent,
 *       transferReason = Some("Requires physics expertise"),
 *       preserveContext = true
 *     )
 *   )
 * )
 * ```
 *
 * @param id Stable, caller-chosen identifier, `[a-zA-Z0-9_-]{1,52}`, unique within one handoffs list;
 * the handoff tool is named `handoff_to_<id>`
 * @param targetAgent The agent to hand off to
 * @param transferReason Optional reason for the handoff (shown to LLM in tool description)
 * @param preserveContext Whether to transfer conversation history (default: true)
 * @param transferSystemMessage Whether to transfer system message (default: false)
 */
case class Handoff(
  id: String,
  targetAgent: Agent,
  transferReason: Option[String] = None,
  preserveContext: Boolean = true,
  transferSystemMessage: Boolean = false
) {

  /** The handoff tool's name: `handoff_to_<id>`, stable across processes. */
  def handoffId: String = s"${Handoff.ToolPrefix}$id"

  /** Human-readable name for this handoff. */
  def handoffName: String =
    transferReason
      .map(reason => s"Handoff: $reason")
      .getOrElse(s"Handoff to $id")
}

object Handoff {

  private[agent] val ToolPrefix = "handoff_to_"

  private val IdPattern = "[a-zA-Z0-9_-]{1,52}".r

  /** Validated construction: `Left(ValidationError)` unless `id` matches `[a-zA-Z0-9_-]{1,52}`. */
  def of(id: String, targetAgent: Agent, reason: Option[String] = None): Result[Handoff] =
    if (IdPattern.matches(id)) Right(Handoff(id, targetAgent, reason))
    else Left(ValidationError("handoff.id", s"'$id' must match [a-zA-Z0-9_-]{1,52}"))

  /** Create a handoff with default settings; throws `IllegalArgumentException` for an invalid id. */
  def to(id: String, targetAgent: Agent): Handoff =
    unsafe(of(id, targetAgent))

  /** Create a handoff with a reason; throws `IllegalArgumentException` for an invalid id. */
  def to(id: String, targetAgent: Agent, reason: String): Handoff =
    unsafe(of(id, targetAgent, Some(reason)))

  private def unsafe(r: Result[Handoff]): Handoff =
    r.fold(e => throw new IllegalArgumentException(e.message), identity)
}
