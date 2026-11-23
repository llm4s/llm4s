package org.llm4s.agent.graph.middleware

import org.llm4s.annotation.Stable
import org.llm4s.error.{ LLMError, NonRecoverableError }

/**
 * A guardrail refused the run's input or answer. `guardrail` is the first failing guardrail's name
 * and `reason` every failing guardrail's error, joined with `"; "`. The tool loop blocks the run with
 * it (`RunResult.Failed`, the thread usable), and `Agent` reports it as `AgentStatus.Blocked`.
 *
 * `message` names the guardrail and never holds `reason`, which may quote the text the guardrail
 * refused (#2793): the message is what the durable `TaskFailed` and `RunFailed` events, a run queue
 * entry's failure, logs and traces record. Read `reason` from this error, or from
 * `AgentStatus.Blocked`. It is stored in one place, the blocked run's closing checkpoint
 * (`RunOutcome.Blocked`), from which a settled turn's status is rebuilt.
 */
@Stable
final case class GuardrailBlocked(guardrail: String, reason: String) extends LLMError with NonRecoverableError:
  val message: String                       = s"Guardrail '$guardrail' blocked"
  override val context: Map[String, String] = Map("guardrail" -> guardrail)
