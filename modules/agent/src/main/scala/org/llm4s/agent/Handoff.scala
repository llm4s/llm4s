package org.llm4s.agent

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
 *       targetAgent = specialistAgent,
 *       transferReason = Some("Requires physics expertise"),
 *       preserveContext = true
 *     )
 *   )
 * )
 * ```
 *
 * @param targetAgent The agent to hand off to
 * @param transferReason Optional reason for the handoff (shown to LLM in tool description)
 * @param preserveContext Whether to transfer conversation history (default: true)
 * @param transferSystemMessage Whether to transfer system message (default: false)
 */
case class Handoff(
  targetAgent: Agent,
  transferReason: Option[String] = None,
  preserveContext: Boolean = true,
  transferSystemMessage: Boolean = false
) {

  /**
   * Generate a unique identifier for this handoff.
   * Used for tool naming and logging.
   */
  def handoffId: String = {
    val targetId = Integer.toHexString(targetAgent.hashCode())
    s"handoff_to_agent_$targetId"
  }

  /**
   * Generate a human-readable name for this handoff.
   */
  def handoffName: String =
    transferReason
      .map(reason => s"Handoff: $reason")
      .getOrElse(s"Handoff to agent ${Integer.toHexString(targetAgent.hashCode())}")
}

object Handoff {

  /**
   * Create a simple handoff with default settings.
   */
  def to(targetAgent: Agent): Handoff =
    Handoff(targetAgent, None, preserveContext = true, transferSystemMessage = false)

  /**
   * Create a handoff with a reason.
   */
  def to(targetAgent: Agent, reason: String): Handoff =
    Handoff(targetAgent, Some(reason), preserveContext = true, transferSystemMessage = false)
}
