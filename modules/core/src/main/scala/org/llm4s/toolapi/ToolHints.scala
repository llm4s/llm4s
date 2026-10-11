package org.llm4s.toolapi

import org.llm4s.annotation.Experimental

/**
 * What a tool is likely to do, with the meanings of the MCP tool annotations and their conservative
 * defaults. These are hints a middleware reads (for example to ask approval for a tool that is not
 * read-only), not guarantees: nothing checks that a tool behaves as it says.
 *
 * It lives in `llm4s-core` so that both `llm4s-agent`, whose tool contract carries it, and `llm4s-mcp`,
 * which reads it from a server's tool annotations, can see it: neither module depends on the other.
 *
 * @param readOnly MCP `readOnlyHint`: the tool does not modify its environment
 * @param destructive MCP `destructiveHint`: the tool may perform destructive updates rather than
 *                    only additive ones; meaningful only when it is not `readOnly`
 * @param idempotent MCP `idempotentHint`: calling it again with the same arguments has no further
 *                   effect; meaningful only when it is not `readOnly`
 * @param openWorld MCP `openWorldHint`: the tool may interact with an open world of external
 *                  entities, such as the web, rather than a closed domain
 */
@Experimental
final case class ToolHints private (readOnly: Boolean, destructive: Boolean, idempotent: Boolean, openWorld: Boolean):
  def withReadOnly(v: Boolean): ToolHints    = copy(readOnly = v)
  def withDestructive(v: Boolean): ToolHints = copy(destructive = v)
  def withIdempotent(v: Boolean): ToolHints  = copy(idempotent = v)
  def withOpenWorld(v: Boolean): ToolHints   = copy(openWorld = v)

@Experimental
object ToolHints:
  def apply(
    readOnly: Boolean = false,
    destructive: Boolean = true,
    idempotent: Boolean = false,
    openWorld: Boolean = true
  ): ToolHints = new ToolHints(readOnly, destructive, idempotent, openWorld)

  /** Not read-only, destructive, not idempotent, open-world: the MCP defaults. */
  val default: ToolHints = ToolHints()
