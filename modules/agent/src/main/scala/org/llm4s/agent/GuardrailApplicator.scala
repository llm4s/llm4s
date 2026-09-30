package org.llm4s.agent

import org.llm4s.agent.guardrails.{ CompositeGuardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.types.Result

/**
 * Stateless helpers for applying input and output guardrail chains.
 *
 * Guardrails use a '''fail-fast''' security posture: the first failure
 * short-circuits evaluation and returns `Left` immediately.  Running subsequent
 * guardrails after one has already rejected the value would waste cycles and
 * could surface false-positive results that mask the root rejection.
 *
 * == Why output validation extracts only the last assistant message ==
 *
 * An agent run may produce several intermediate assistant messages
 * (e.g. "let me look that up", followed by tool results, followed by the
 * final answer).  Only the last assistant message represents the final answer
 * that is delivered to the caller.  Validating earlier intermediate messages
 * would reject legitimate tool-use flows where the LLM acknowledges partial
 * progress.  The empty string is used as a fallback so guardrails that check
 * for content length (e.g. `LengthCheck`) still fire correctly when the
 * conversation has no assistant turn at all.
 *
 * @see [[org.llm4s.agent.guardrails.InputGuardrail]]
 * @see [[org.llm4s.agent.guardrails.OutputGuardrail]]
 * @see [[CompositeGuardrail]]
 */
private[agent] object GuardrailApplicator {

  /**
   * Validates `query` against the supplied input guardrails.
   *
   * Returns `Right(query)` unchanged when `guardrails` is empty so that
   * callers do not need to special-case the no-guardrail path.
   *
   * @param query      The user input to validate.
   * @param guardrails Guardrails to apply; short-circuits on first failure.
   * @return `Right(query)` if all guardrails pass; `Left` on first failure.
   */
  def validateInput(
    query: String,
    guardrails: Seq[InputGuardrail]
  ): Result[String] =
    if (guardrails.isEmpty) {
      Right(query)
    } else {
      CompositeGuardrail.all(guardrails).validate(query)
    }

  /**
   * Validates the final assistant response inside `state` against the supplied
   * output guardrails.
   *
   * Returns `Right(state)` unchanged when `guardrails` is empty.  When
   * guardrails are present, the last `Assistant`-role message is extracted and
   * validated; an empty string is used when no assistant message exists so that
   * length-based guardrails still trigger correctly.
   *
   * @param state      Agent state whose last assistant message is validated.
   * @param guardrails Guardrails to apply; short-circuits on first failure.
   * @return `Right(state)` if all guardrails pass; `Left` on first failure.
   */
  def validateOutput(
    state: AgentState,
    guardrails: Seq[OutputGuardrail]
  ): Result[AgentState] =
    if (guardrails.isEmpty) {
      Right(state)
    } else {
      val finalMessage = state.conversation.messages
        .findLast(_.role == MessageRole.Assistant)
        .map(_.content)
        .getOrElse("")

      CompositeGuardrail.all(guardrails).validate(finalMessage).map(_ => state)
    }
}
