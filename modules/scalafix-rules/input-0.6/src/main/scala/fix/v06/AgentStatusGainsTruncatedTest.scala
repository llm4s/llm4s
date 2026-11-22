/*
rule = AgentStatusGainsTruncated
*/
package fix.v06

import org.llm4s.agent.{ AgentResult, AgentStatus }

// What AgentStatusGainsTruncated reports: a match on AgentStatus with no catch-all, which needs a case for Truncated.
object AgentStatusGainsTruncatedTest {
  def describe(status: AgentStatus): String = status match { // assert: AgentStatusGainsTruncated.agentStatusTruncated
    case AgentStatus.Completed(answer)          => answer
    case AgentStatus.Blocked(guardrail, reason) => s"$guardrail: $reason"
    case AgentStatus.StepLimitReached           => "step limit"
    case AgentStatus.TokenLimitReached          => "token budget"
    case _: AgentStatus.Suspended               => "waiting"
  }

  def inOption(result: Option[AgentResult]): String = result.map(_.status) match { // assert: AgentStatusGainsTruncated.agentStatusTruncated
    case Some(AgentStatus.Completed(answer)) => answer
    case Some(AgentStatus.StepLimitReached)  => "step limit"
    case None                                => "none"
  }

  val label: PartialFunction[AgentStatus, String] = { // assert: AgentStatusGainsTruncated.agentStatusTruncated
    case AgentStatus.Completed(_)     => "completed"
    case AgentStatus.StepLimitReached => "step_limit_reached"
  }

  // a catch-all covers Truncated: not reported
  def answered(status: AgentStatus): Boolean = status match {
    case AgentStatus.Completed(_) => true
    case _                        => false
  }

  def typed(status: AgentStatus): String = status match {
    case AgentStatus.Completed(answer) => answer
    case other: AgentStatus            => other.productPrefix
  }

  // a match on something else: not reported
  def other(n: Int): String = n match {
    case 1 => "one"
  }
}
