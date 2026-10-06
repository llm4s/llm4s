package org.llm4s.samples.util

import org.llm4s.agent.{ AgentResult, AgentStatus }
import org.llm4s.error.ExecutionError
import org.llm4s.types.Result

/**
 * Helpers for samples that report an agent run honestly: a run that did not complete has no
 * answer, and its status says why.
 */
object AgentResults {

  /** Why a run ended without an answer; `Completed` has none. */
  def describe(status: AgentStatus): String = status match {
    case AgentStatus.Completed(_)            => "completed"
    case AgentStatus.Blocked(guardrail, why) => s"blocked by guardrail $guardrail: $why"
    case AgentStatus.StepLimitReached        => "stopped at the step limit before reaching an answer"
    case AgentStatus.Suspended(approvals, qs) =>
      s"suspended with ${approvals.size} pending approval(s) and ${qs.size} pending question(s)"
  }

  /** The answer when the run completed, otherwise a description of its status. */
  def answerOrStatus(result: AgentResult): String =
    result.answer.getOrElse(s"(no answer: run ${describe(result.status)})")

  /** The answer when the run completed, otherwise an error naming its status. */
  def requireCompleted(result: AgentResult): Result[String] =
    result.answer.toRight(ExecutionError(s"Agent run ${describe(result.status)}", "agent.run"))
}
