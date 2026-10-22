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
  /**
   * Sets the `readOnlyHint`.
   *
   * @param v `true` if the tool does not modify its environment
   * @return new ToolHints with readOnly set; this is a hint, not a guarantee
   */
  def withReadOnly(v: Boolean): ToolHints = copy(readOnly = v)

  /**
   * Sets the `destructiveHint`. Meaningful only when the tool is not read-only.
   *
   * @param v `true` if the tool may perform destructive updates rather than only additive ones
   * @return new ToolHints with destructive set; this is a hint, not a guarantee
   */
  def withDestructive(v: Boolean): ToolHints = copy(destructive = v)

  /**
   * Sets the `idempotentHint`. Meaningful only when the tool is not read-only.
   *
   * @param v `true` if calling the tool again with the same arguments has no further effect
   * @return new ToolHints with idempotent set; this is a hint, not a guarantee
   */
  def withIdempotent(v: Boolean): ToolHints = copy(idempotent = v)

  /**
   * Sets the `openWorldHint`.
   *
   * @param v `true` if the tool may interact with an open world of external entities, such as the web,
   *          rather than a closed domain
   * @return new ToolHints with openWorld set; this is a hint, not a guarantee
   */
  def withOpenWorld(v: Boolean): ToolHints = copy(openWorld = v)

/**
 * Factory and default for [[ToolHints]]. The defaults are the conservative MCP ones: a tool that does not
 * say what it does is treated as not read-only, destructive, not idempotent and open-world.
 */
@Experimental
object ToolHints:
  /**
   * Creates a [[ToolHints]]. Each default assumes the less safe case, so that a middleware asking approval for
   * tools that are not read-only does not skip a tool that never declared otherwise.
   *
   * @param readOnly `readOnlyHint`: the tool does not modify its environment; defaults to `false`, so the tool
   *                 is assumed to modify it
   * @param destructive `destructiveHint`: the tool may perform destructive updates rather than only additive
   *                    ones; meaningful only when the tool is not read-only; defaults to `true`, so updates are
   *                    assumed destructive
   * @param idempotent `idempotentHint`: calling it again with the same arguments has no further effect;
   *                   meaningful only when the tool is not read-only; defaults to `false`, so repeating a call
   *                   is assumed to have a further effect
   * @param openWorld `openWorldHint`: the tool may interact with an open world of external entities, such as
   *                  the web, rather than a closed domain; defaults to `true`, so the tool is assumed to reach
   *                  external entities
   * @return the hints; they are hints, not guarantees
   */
  def apply(
    readOnly: Boolean = false,
    destructive: Boolean = true,
    idempotent: Boolean = false,
    openWorld: Boolean = true
  ): ToolHints = new ToolHints(readOnly, destructive, idempotent, openWorld)

  /** Not read-only, destructive, not idempotent, open-world: the MCP defaults. */
  val default: ToolHints = ToolHints()
