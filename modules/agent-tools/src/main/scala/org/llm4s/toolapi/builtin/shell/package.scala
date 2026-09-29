package org.llm4s.toolapi.builtin

/**
 * Shell tools for executing system commands.
 *
 * These tools provide safe shell access with configurable
 * command allowlists and execution limits.
 *
 * == Configuration ==
 *
 * All shell tools require explicit command allowlisting via [[ShellConfig]]:
 *
 * - Allowed commands list (required for any execution)
 * - Working directory configuration
 * - Timeout limits
 * - Output size limits
 *
 * == Available Tools ==
 *
 * - [[ShellTool]]: Execute shell commands (requires explicit allowlist)
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.shell._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Read-only shell (ls, cat, etc.)
 * val readOnlyShell = ShellTool.create(ShellConfig.readOnly())
 *
 * // Development shell with common dev tools
 * val devShell = ShellTool.create(ShellConfig.development(
 *   workingDirectory = Some("/path/to/project")
 * ))
 *
 * // Custom restricted shell
 * val customShell = ShellTool.create(ShellConfig(
 *   allowedCommands = Seq("git", "npm"),
 *   timeoutMs = 60000
 * ))
 *
 * val tools = new ToolRegistry(Seq(devShell))
 * }}}
 */
package object shell
