package org.llm4s.toolapi.builtin

import org.llm4s.toolapi.ToolFunction
import org.llm4s.toolapi.builtin.core._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.http._
import org.llm4s.toolapi.builtin.shell._
import org.llm4s.types.Result

/**
 * Aggregator for built-in tools with convenient factory methods.
 *
 * Provides pre-configured tool sets for common use cases:
 *
 * - `safe()`: Core utilities, web search, and read-only HTTP (no file or shell access)
 * - `withFiles()`: Safe tools plus read-only file system access
 * - `development()`: All tools including shell with common dev commands
 *
 * @example
 * {{{
 * import org.llm4s.toolapi.ToolRegistry
 * import org.llm4s.toolapi.builtin.BuiltinTools
 *
 * // Safe tools for production use; `BuiltinTools.registry` also reports each tool's hints (read-only, ...)
 * for {
 *   tools <- BuiltinTools.withHttpSafe()
 *   registry <- BuiltinTools.registry(tools)
 * } yield registry
 *
 * // Development tools with file and shell access
 * for {
 *   tools <- BuiltinTools.developmentSafe(
 *     workingDirectory = Some("/home/user/project")
 *   )
 *   registry <- ToolRegistry.of(tools)
 * } yield registry
 * }}}
 */
object BuiltinTools {

  /**
   * The most characters of one result the built-in tools hand the model by default: an HTTP body and a read file
   * are cut there and marked `truncated`, and a shell command keeps half as many of stdout and of stderr each. As much
   * as one result of the harness file tools; a larger one would crowd the context or exceed it (#3273).
   */
  val DefaultMaxResultChars: Int = 40000

  /**
   * A registry of `tools` that reports the [[org.llm4s.toolapi.ToolHints]] of each built-in tool among them, as built
   * by its factory with its configuration ([[org.llm4s.toolapi.ToolHintSource]]): read-only for the core utilities,
   * the read-only file tools, the search tools and an `http_request` limited to safe methods. `AgentBuilder.withTools`
   * gives them to the agent, so `ApprovalMiddleware.unlessReadOnly` asks only for the tools that change something.
   * A tool not built by a built-in factory keeps the conservative `ToolHints.default`, whatever its name (#3273).
   *
   * @return `Left(ValidationError)` naming every tool name that appears more than once, as `ToolRegistry.of`
   */
  def registry(tools: Seq[ToolFunction[_]]): Result[org.llm4s.toolapi.ToolRegistry] =
    org.llm4s.toolapi.ToolRegistry.of(tools).map(new BuiltinToolHints.HintedRegistry(_))

  /**
   * Core utility tools (always safe, no external dependencies),
   * returning a Result for safe error handling.
   *
   * Includes:
   * - DateTimeTool: Get current date/time
   * - CalculatorTool: Math operations
   * - UUIDTool: Generate UUIDs
   * - JSONTool: Parse/format/query JSON
   */
  def coreSafe: Result[Seq[ToolFunction[_]]] = for {
    dateTime   <- DateTimeTool.toolSafe
    calculator <- CalculatorTool.toolSafe
    uuid       <- UUIDTool.toolSafe
    json       <- JSONTool.toolSafe
  } yield Seq(dateTime, calculator, uuid, json)

  /**
   * Safe tools for production use, returning a Result for safe error handling.
   *
   * Includes:
   * - All core utilities
   * - Read-only HTTP with localhost blocked
   *
   * Does NOT include:
   * - File system access
   * - Shell access
   * - Web search (configure separately at application edge)
   */
  def withHttpSafe(httpConfig: HttpConfig = HttpConfig.readOnly()): Result[Seq[ToolFunction[_]]] = for {
    coreTools <- coreSafe
    httpTool  <- HTTPTool.createSafe(httpConfig)
  } yield coreTools :+ httpTool

  /**
   * Safe tools plus read-only file system access,
   * returning a Result for safe error handling.
   *
   * Includes:
   * - All safe tools
   * - Read-only file system tools (read, list, info)
   *
   * Does NOT include:
   * - File writing
   * - Shell access
   */
  def withFilesSafe(
    fileConfig: FileConfig = FileConfig(),
    httpConfig: HttpConfig = HttpConfig.readOnly()
  ): Result[Seq[ToolFunction[_]]] = for {
    safeTools <- withHttpSafe(httpConfig)
    readFile  <- ReadFileTool.createSafe(fileConfig)
    listDir   <- ListDirectoryTool.createSafe(fileConfig)
    fileInfo  <- FileInfoTool.createSafe(fileConfig)
  } yield safeTools ++ Seq(readFile, listDir, fileInfo)

  /**
   * Development tools with full read/write and shell access,
   * returning a Result for safe error handling.
   *
   * Includes:
   * - All core utilities
   * - Full HTTP access
   * - File system read/write
   * - Shell with common dev commands
   *
   * WARNING: These tools have significant access to the system.
   * Use only in trusted development environments.
   *
   * @param workingDirectory Optional working directory for file/shell operations
   * @param fileAllowedPaths Allowed paths for file write operations
   */
  def developmentSafe(
    workingDirectory: Option[String] = None,
    fileAllowedPaths: Seq[String] = Seq("/tmp")
  ): Result[Seq[ToolFunction[_]]] = {
    val fileConfig = FileConfig(
      allowedPaths = workingDirectory.map(Seq(_))
    )
    val writeConfig = WriteConfig(
      allowedPaths = fileAllowedPaths ++ workingDirectory.toSeq,
      allowOverwrite = true
    )
    val shellConfig = ShellConfig.development(workingDirectory)

    for {
      coreTools <- coreSafe
      httpTool  <- HTTPTool.toolSafe
      readFile  <- ReadFileTool.createSafe(fileConfig)
      listDir   <- ListDirectoryTool.createSafe(fileConfig)
      fileInfo  <- FileInfoTool.createSafe(fileConfig)
      writeFile <- WriteFileTool.createSafe(writeConfig)
      shell     <- ShellTool.createSafe(shellConfig)
    } yield coreTools ++ Seq(httpTool, readFile, listDir, fileInfo, writeFile, shell)
  }

  /**
   * Custom tool set with full configuration control,
   * returning a Result for safe error handling.
   *
   * @param fileConfig Configuration for read operations
   * @param writeConfig Optional write configuration (if None, write tool is not included)
   * @param httpConfig HTTP configuration
   * @param shellConfig Optional shell configuration (if None, shell tool is not included)
   */
  def customSafe(
    fileConfig: Option[FileConfig] = None,
    writeConfig: Option[WriteConfig] = None,
    httpConfig: Option[HttpConfig] = None,
    shellConfig: Option[ShellConfig] = None
  ): Result[Seq[ToolFunction[_]]] = {
    def optionalTool[T](
      config: Option[T]
    )(build: T => Result[ToolFunction[_]]): Result[Seq[ToolFunction[_]]] =
      config match {
        case Some(cfg) => build(cfg).map(Seq(_))
        case None      => Right(Seq.empty)
      }

    def optionalTools[T](
      config: Option[T]
    )(build: T => Result[Seq[ToolFunction[_]]]): Result[Seq[ToolFunction[_]]] =
      config match {
        case Some(cfg) => build(cfg)
        case None      => Right(Seq.empty)
      }

    for {
      coreTools <- coreSafe
      httpTools <- optionalTool(httpConfig)(cfg => HTTPTool.createSafe(cfg))
      fileTools <- optionalTools(fileConfig) { cfg =>
        for {
          readFile <- ReadFileTool.createSafe(cfg)
          listDir  <- ListDirectoryTool.createSafe(cfg)
          fileInfo <- FileInfoTool.createSafe(cfg)
        } yield Seq(readFile, listDir, fileInfo)
      }
      writeTools <- optionalTool(writeConfig)(cfg => WriteFileTool.createSafe(cfg))
      shellTools <- optionalTool(shellConfig)(cfg => ShellTool.createSafe(cfg))
    } yield coreTools ++ httpTools ++ fileTools ++ writeTools ++ shellTools
  }

}
