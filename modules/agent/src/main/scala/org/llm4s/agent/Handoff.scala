package org.llm4s.agent

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * A transfer an agent's model may make to another agent of its family. `build()` compiles the root
 * and every agent reachable through handoffs into one graph, keyed by agent id. The model is
 * offered the handoff as a tool named `handoff_to_<id>`; calling it, as the only call of its
 * message, moves the conversation to the target, which stays active - the next turn starts with it
 * - until it hands off again.
 *
 * The target is either a builder ([[Handoff.to]], [[Handoff.of]]), which brings that agent into the
 * family, or an agent id alone ([[Handoff.toId]]), which names an agent some builder of the family
 * defines. Builders are immutable, so a cycle - the specialist handing back to the agent that
 * handed to it - names the earlier agent by id:
 *
 * {{{
 * val physics = Agent.builder("physics", client)
 *   .withSystemPrompt("You are a physicist.")
 *   .withHandoffs(Handoff.toId("triage", "Questions that are not about physics"))
 * val triage = Agent.builder("triage", client)
 *   .withHandoffs(Handoff.to("physics", physics, "Requires physics expertise"))
 *   .build()
 * }}}
 *
 * @param id the target's agent id, `[a-zA-Z0-9_-]{1,52}`; `build()` refuses one that differs from
 *   `target`'s, and an id-only target no builder of the family defines
 * @param target the agent to hand off to, or `None` when it is named by `id` alone
 * @param transferReason offered to the model in the handoff tool's description
 * @param preserveContext when `false`, the target is sent only the user's last question before the
 *   transfer and the transfer onwards; the stored history is unchanged either way
 */
final case class Handoff(
  id: String,
  target: Option[AgentBuilder],
  transferReason: Option[String] = None,
  preserveContext: Boolean = true
):

  /** The handoff tool's name: `handoff_to_<id>`, stable across processes. */
  def handoffId: String = s"${Handoff.ToolPrefix}$id"

  /** Human-readable name for this handoff. */
  def handoffName: String =
    transferReason
      .map(reason => s"Handoff: $reason")
      .getOrElse(s"Handoff to $id")

object Handoff:

  private[agent] val ToolPrefix = "handoff_to_"

  private val IdPattern = "[a-zA-Z0-9_-]{1,52}".r

  private[agent] def isValidId(id: String): Boolean = IdPattern.matches(id)

  /**
   * Validated construction: `Left(ValidationError)` unless `id` matches `[a-zA-Z0-9_-]{1,52}`.
   * `preserveContext = false` sends the target only the user's last question and the transfer.
   */
  def of(
    id: String,
    target: AgentBuilder,
    reason: Option[String] = None,
    preserveContext: Boolean = true
  ): Result[Handoff] =
    if isValidId(id) then Right(Handoff(id, Some(target), reason, preserveContext))
    else Left(ValidationError("handoff.id", s"'$id' must match [a-zA-Z0-9_-]{1,52}"))

  /** A handoff with default settings; throws `IllegalArgumentException` for an invalid id. */
  def to(id: String, target: AgentBuilder): Handoff =
    unsafe(of(id, target))

  /**
   * A handoff with a reason; throws `IllegalArgumentException` for an invalid id. With
   * `preserveContext = false` the target is sent only the user's last question and the transfer.
   */
  def to(id: String, target: AgentBuilder, reason: String, preserveContext: Boolean = true): Handoff =
    unsafe(of(id, target, Some(reason), preserveContext))

  /**
   * A handoff to the agent with id `id`, defined by another builder of the family - the way back
   * of a cycle. Throws `IllegalArgumentException` for an invalid id; `build()` refuses an id no
   * builder of the family defines.
   */
  def toId(id: String, reason: Option[String] = None, preserveContext: Boolean = true): Handoff =
    unsafe(
      if isValidId(id) then Right(Handoff(id, None, reason, preserveContext))
      else Left(ValidationError("handoff.id", s"'$id' must match [a-zA-Z0-9_-]{1,52}"))
    )

  /** A handoff with a reason to the agent with id `id`, defined by another builder of the family. */
  def toId(id: String, reason: String): Handoff = toId(id, Some(reason))

  private def unsafe(r: Result[Handoff]): Handoff =
    r.fold(e => throw new IllegalArgumentException(e.message), identity)
