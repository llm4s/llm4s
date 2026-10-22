package org.llm4s.mcp

import org.llm4s.toolapi.ToolCallInfo

/**
 * The tool call an MCP `tools/call` request carries in its `_meta` (#1740): the agent's idempotency key and the
 * model's call id, under an `llm4s.org/` prefix, as the MCP specification's `_meta` naming rules allow. The
 * llm4s client sends them whenever its tool runs as an agent's call; the llm4s server hands them to the tool as
 * `SafeParameterExtractor.call`. They are metadata, never arguments, so a server that validates arguments strictly
 * and a tool that does not know them are unaffected; a server that ignores `_meta` runs the call without
 * de-duplicating it.
 */
private[mcp] object MCPToolCallMeta {

  val IdempotencyKey: String = "llm4s.org/idempotencyKey"
  val ToolCallId: String     = "llm4s.org/toolCallId"

  /** The `_meta` object for `call`. */
  def toMeta(call: ToolCallInfo): ujson.Obj =
    ujson.Obj(IdempotencyKey -> call.idempotencyKey, ToolCallId -> call.toolCallId)

  /**
   * The call a request's `params._meta` describes: `None` without an idempotency key, which is what makes it one. A
   * missing call id falls back to `requestId`.
   */
  def fromParams(params: Option[ujson.Value], requestId: String): Option[ToolCallInfo] =
    for
      meta <- params.flatMap(_.objOpt).flatMap(_.get("_meta")).flatMap(_.objOpt)
      key  <- meta.get(IdempotencyKey).flatMap(_.strOpt).filter(_.nonEmpty)
    yield ToolCallInfo(meta.get(ToolCallId).flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(requestId), key)
}
