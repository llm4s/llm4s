package org.llm4s.toolapi

/**
 * Built-in tools for common agent operations.
 *
 * This package provides production-ready tools that can be used out of the box:
 *
 * == Core Utilities (No API Keys Required) ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.core._
 *
 * val tools = new ToolRegistry(Seq(
 *   DateTimeTool.tool,
 *   CalculatorTool.tool,
 *   UUIDTool.tool,
 *   JSONTool.tool
 * ))
 * }}}
 *
 * == File System Tools ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 *
 * val fileTools = new ToolRegistry(Seq(
 *   ReadFileTool.create(FileConfig(
 *     maxFileSize = 512 * 1024,
 *     allowedPaths = Some(Seq("/tmp"))
 *   )),
 *   ListDirectoryTool.tool,
 *   FileInfoTool.tool
 * ))
 * }}}
 *
 * == HTTP Tools ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.http._
 *
 * val httpTools = new ToolRegistry(Seq(
 *   HTTPTool.create(HttpConfig(
 *     timeoutMs = 10000,
 *     allowedDomains = Some(Seq("api.example.com"))
 *   ))
 * ))
 * }}}
 *
 * == Shell Tools ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.shell._
 *
 * // Read-only shell (safe commands)
 * val shellTools = new ToolRegistry(Seq(
 *   ShellTool.create(ShellConfig.readOnly())
 * ))
 *
 * // Development shell with common dev tools
 * val devShellTools = new ToolRegistry(Seq(
 *   ShellTool.create(ShellConfig.development())
 * ))
 * }}}
 *
 * == Search Tools ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.search._
 *
 * val searchTools = new ToolRegistry(Seq(
 *   DuckDuckGoSearchTool.tool
 * ))
 * }}}
 *
 * == All Built-in Tools ==
 *
 * {{{
 * import org.llm4s.toolapi.builtin.BuiltinTools
 *
 * // Get all safe tools (no shell, restricted filesystem)
 * val tools = BuiltinTools.withHttpSafe()
 *
 * // Get all tools with custom config
 * val allTools = BuiltinTools.all(
 *   fileConfig = FileConfig(allowedPaths = Some(Seq("/tmp")))
 * )
 * }}}
 *
 * @see [[org.llm4s.toolapi.builtin.core]] for core utility tools
 * @see [[org.llm4s.toolapi.builtin.filesystem]] for file system tools
 * @see [[org.llm4s.toolapi.builtin.http]] for HTTP tools
 * @see [[org.llm4s.toolapi.builtin.shell]] for shell tools
 * @see [[org.llm4s.toolapi.builtin.search]] for search tools
 */
package object builtin
