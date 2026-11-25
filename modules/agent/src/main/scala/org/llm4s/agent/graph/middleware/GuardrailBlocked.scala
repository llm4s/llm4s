package org.llm4s.agent.graph.middleware

import org.llm4s.annotation.Experimental
import org.llm4s.error.LLMError

/**
 * A guardrail refused the run's input or answer. `guardrail` is the first failing guardrail's name
 * and `reason` every failing guardrail's error, joined with `"; "`. The tool loop blocks the run with
 * it (`RunResult.Failed`, the thread usable), and `Agent` reports it as `AgentStatus.Blocked`.
 *
 * `reason` is `None` in one case (#2854): the query was refused by an admission stage before the turn was
 * stored, and the run's input step, which has only the refusing guardrail's name, raised the block. The
 * reason is then with whoever ran the admission: `Agent.run` and `Agent.start` put it on their result.
 *
 * A middleware that is not a guardrail blocks a turn the same way with `AgentMiddleware.block(reason)` (#2795):
 * `guardrail` is then the middleware's id.
 *
 * `message` names the guardrail and never holds `reason`, which may quote the text the guardrail
 * refused (#2793): the message is what the durable `TaskFailed` and `RunFailed` events, a run queue
 * entry's failure, logs and traces record. Read `reason` from this error, or from
 * `AgentStatus.Blocked` on the result of the run that was blocked. It is stored nowhere (#2853): the
 * blocked run's closing checkpoint records the guardrail's name (`RunOutcome.Blocked`), and a status
 * rebuilt from it has no reason.
 */
@Experimental
final case class GuardrailBlocked(guardrail: String, reason: Option[String]) extends LLMError:
  val message: String                       = s"Guardrail '$guardrail' blocked"
  override val context: Map[String, String] = Map("guardrail" -> guardrail)
