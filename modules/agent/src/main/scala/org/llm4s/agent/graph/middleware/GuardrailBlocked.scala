package org.llm4s.agent.graph.middleware

import org.llm4s.error.{ LLMError, NonRecoverableError }

/**
 * A guardrail refused the run's input or answer. `guardrail` is the first failing guardrail's name
 * and `reason` every failing guardrail's error, joined with `"; "`. The tool loop blocks the run with
 * it (`RunResult.Failed`, the thread usable), and `Agent` reports it as `AgentStatus.Blocked`.
 */
final case class GuardrailBlocked(guardrail: String, reason: String) extends LLMError with NonRecoverableError:
  val message: String                       = s"Guardrail '$guardrail' blocked: $reason"
  override val context: Map[String, String] = Map("guardrail" -> guardrail)
