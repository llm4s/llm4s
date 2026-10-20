package org.llm4s.toolapi

import org.llm4s.annotation.Stable

/**
 * The tool call a [[ToolFunction]] handler is running, when its caller knows it: read as
 * [[SafeParameterExtractor.call]]. An agent supplies it for every call it runs; plain [[ToolRegistry]] tool calling,
 * outside an agent, has no call to describe and leaves it `None`.
 *
 * A tool with an external side effect passes `idempotencyKey` to the system it calls (an `Idempotency-Key` header, a
 * client token, a unique column), because an agent may run the same call more than once - a retrying middleware,
 * `recover` after a failure or crash, an approval - and it is the same in every run of one call. The runtime runs a
 * tool at least once, never exactly once; the key de-duplicates only where the external system honours it.
 *
 * Growth-prone: build it with `ToolCallInfo(...)` and change it with the `with*` setters.
 *
 * @param toolCallId     the id the model gave the call
 * @param idempotencyKey the same in every run of this call, and different for every other call
 */
@Stable
final case class ToolCallInfo private (toolCallId: String, idempotencyKey: String) {
  def withToolCallId(toolCallId: String): ToolCallInfo         = copy(toolCallId = toolCallId)
  def withIdempotencyKey(idempotencyKey: String): ToolCallInfo = copy(idempotencyKey = idempotencyKey)
}

object ToolCallInfo {

  /** Creates a [[ToolCallInfo]]. */
  def apply(toolCallId: String, idempotencyKey: String): ToolCallInfo = new ToolCallInfo(toolCallId, idempotencyKey)
}
