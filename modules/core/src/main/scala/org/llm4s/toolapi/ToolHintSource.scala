package org.llm4s.toolapi

import org.llm4s.annotation.Experimental

/**
 * A [[ToolRegistry]] that knows the [[ToolHints]] of its tools from elsewhere: an MCP server's tool annotations,
 * which `MCPToolRegistry` in `llm4s-mcp` reports for a server configured as trusted (#3080).
 *
 * `AgentBuilder.withTools(registry)` in `llm4s-agent` gives each tool of a registry that is one the hints it reports,
 * so a middleware that reads them (`ApprovalMiddleware.unlessReadOnly`) sees them. A tool it reports `None` for keeps
 * the conservative defaults, `ToolHints.default`.
 */
@Experimental
trait ToolHintSource {

  /** The hints of the tool `name`, when this source knows them; `None` leaves the defaults. */
  def toolHints(name: String): Option[ToolHints]
}
