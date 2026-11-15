/*
rule = PositionalPatternsGainFields
*/
package fix.v1

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.RunEvent
import org.llm4s.error.{ LLMError, ServiceError }
import org.llm4s.trace.TraceEvent

object PositionalPatternsGainFieldsTest {
  def describe(error: LLMError): String = error match {
    case ServiceError(message, status, provider, requestId) => s"$provider $status $message $requestId"
    case _                                                  => ""
  }

  def failed(event: RunEvent): Option[String] = event match {
    case RunEvent.RunFailed(message, _) => Some(message)
    case RunEvent.RunCancelled(_)       => Some("cancelled")
    case _                              => None
  }

  def pending(status: AgentStatus): Int = status match {
    case AgentStatus.Suspended(approvals, questions, _, _) => approvals.size + questions.size
    case _                                                 => 0
  }

  def custom(event: TraceEvent): String = event match {
    case TraceEvent.CustomEvent(name, _, _) => name
    case _                                  => ""
  }
}
