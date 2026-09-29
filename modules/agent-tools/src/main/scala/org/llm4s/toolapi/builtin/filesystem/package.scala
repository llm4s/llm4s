package org.llm4s.toolapi.builtin

/**
 * File system tools for reading, writing, and managing files.
 *
 * These tools provide safe access to the file system with configurable
 * path restrictions and size limits.
 *
 * == Configuration ==
 *
 * All file system tools accept [[FileConfig]] for read operations or
 * [[WriteConfig]] for write operations. These configurations allow:
 *
 * - Path allowlists and blocklists for security
 * - File size limits
 * - Symbolic link handling
 *
 * == Available Tools ==
 *
 * - [[ReadFileTool]]: Read file contents
 * - [[WriteFileTool]]: Write content to files (requires explicit path allowlist)
 * - [[ListDirectoryTool]]: List directory contents
 * - [[FileInfoTool]]: Get file metadata
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.builtin.filesystem._
 * import org.llm4s.toolapi.ToolRegistry
 *
 * // Read-only tools with default config (blocked: /etc, /var, /sys, /proc, /dev)
 * for {
 *   readFile <- ReadFileTool.toolSafe
 *   listDir  <- ListDirectoryTool.toolSafe
 *   fileInfo <- FileInfoTool.toolSafe
 * } yield new ToolRegistry(Seq(readFile, listDir, fileInfo))
 *
 * // Or use the convenience val
 * readOnlyToolsSafe.map(tools => new ToolRegistry(tools))
 *
 * // Tools with custom restrictions
 * val config = FileConfig(
 *   maxFileSize = 512 * 1024,
 *   allowedPaths = Some(Seq("/tmp", "/home/user/workspace"))
 * )
 *
 * for {
 *   readFile <- ReadFileTool.createSafe(config)
 *   listDir  <- ListDirectoryTool.createSafe(config)
 * } yield new ToolRegistry(Seq(readFile, listDir))
 *
 * // Write tool with explicit allowlist
 * val writeConfig = WriteConfig(
 *   allowedPaths = Seq("/tmp/output"),
 *   allowOverwrite = true
 * )
 *
 * for {
 *   writeTool <- WriteFileTool.createSafe(writeConfig)
 * } yield new ToolRegistry(Seq(writeTool))
 * }}}
 */
package object filesystem {

  /**
   * All file system tools with default configuration (read-only, excludes write),
   * returning a Result for safe error handling.
   */
  val readOnlyToolsSafe: org.llm4s.types.Result[Seq[org.llm4s.toolapi.ToolFunction[_, _]]] = for {
    readFile <- ReadFileTool.toolSafe
    listDir  <- ListDirectoryTool.toolSafe
    fileInfo <- FileInfoTool.toolSafe
  } yield Seq(readFile, listDir, fileInfo)

}
