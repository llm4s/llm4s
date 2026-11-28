package org.llm4s.agent.graph.middleware

import org.llm4s.annotation.Experimental
import org.llm4s.error.{ ContentFilteredError, LLMError, ValidationError }

import scala.concurrent.duration.FiniteDuration

/**
 * A guardrail could not decide: it failed with `cause`, an error that is not a refusal - a judge's provider call
 * hit a rate limit, timed out or was refused authentication (#3247). [[GuardrailMiddleware]] returns it in place of a
 * [[GuardrailBlocked]] when no guardrail of the list refused, so an outage is not reported as an unsafe answer.
 *
 * It fails closed but is not a block: nothing is let through, and the run fails with it, as a cancellation does,
 * leaving its checkpoint `Running`. An output guardrail's failure keeps the turn, so `recover` runs the turn's
 * output guardrails again; an input guardrail's failure at admission starts no turn. `Agent.run` returns it as its
 * `Left`, never as `AgentStatus.Blocked`, and the run's durable `TaskFailed` and `RunFailed` events record its
 * message, never a `GuardrailBlocked` event.
 *
 * A refusal is a [[org.llm4s.error.ValidationError]] (every built-in guardrail's, a judge's low or unreadable score
 * included), a `ContentFilteredError` or a [[GuardrailBlocked]]; any other error is this. `message` names the
 * guardrail and `cause`'s message.
 */
@Experimental
final case class GuardrailUnavailable(guardrail: String, cause: LLMError) extends LLMError:
  val message: String                             = s"Guardrail '$guardrail' could not decide: ${cause.message}"
  override val context: Map[String, String]       = Map("guardrail" -> guardrail)
  override def isRetryable: Boolean               = cause.isRetryable
  override def retryAfter: Option[FiniteDuration] = cause.retryAfter

@Experimental
object GuardrailUnavailable:

  /** `true` when `error` is a guardrail's refusal, `false` when the guardrail could not decide (see the class). */
  private[agent] def isRefusal(error: LLMError): Boolean = error match
    case _: ValidationError | _: ContentFilteredError | _: GuardrailBlocked => true
    case _                                                                  => false
