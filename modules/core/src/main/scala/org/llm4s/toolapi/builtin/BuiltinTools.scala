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
 * // Safe tools for production use
 * for {
 *   tools <- BuiltinTools.safe()
 * } yield new ToolRegistry(tools)
 *
 * // Development tools with file and shell access
 * for {
 *   tools <- BuiltinTools.development(
 *     workingDirectory = Some("/home/user/project")
 *   )
 * } yield new ToolRegistry(tools)
 * }}}
 */
object BuiltinTools {

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
  def coreSafe: Result[Seq[ToolFunction[_, _]]] = for {
    dateTime   <- DateTimeTool.toolSafe
    calculator <- CalculatorTool.toolSafe
    uuid       <- UUIDTool.toolSafe
    json       <- JSONTool.toolSafe
  } yield Seq(dateTime, calculator, uuid, json)

  /**
   * Core utility tools (always safe, no external dependencies).
   *
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use coreSafe which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  def core: Seq[ToolFunction[_, _]] =
    coreSafe match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"BuiltinTools.core failed: ${e.formatted}")
    }

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
  def withHttpSafe(httpConfig: HttpConfig = HttpConfig.readOnly()): Result[Seq[ToolFunction[_, _]]] = for {
    coreTools <- coreSafe
    httpTool  <- HTTPTool.createSafe(httpConfig)
  } yield coreTools :+ httpTool

  /**
   * Safe tools for production use.
   *
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use withHttpSafe() which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  def safe(httpConfig: HttpConfig = HttpConfig.readOnly()): Seq[ToolFunction[_, _]] =
    withHttpSafe(httpConfig) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"BuiltinTools.safe failed: ${e.formatted}")
    }

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
  ): Result[Seq[ToolFunction[_, _]]] = for {
    safeTools <- withHttpSafe(httpConfig)
    readFile  <- ReadFileTool.createSafe(fileConfig)
    listDir   <- ListDirectoryTool.createSafe(fileConfig)
    fileInfo  <- FileInfoTool.createSafe(fileConfig)
  } yield safeTools ++ Seq(readFile, listDir, fileInfo)

  /**
   * Safe tools plus read-only file system access.
   *
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use withFilesSafe() which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  def withFiles(
    fileConfig: FileConfig = FileConfig(),
    httpConfig: HttpConfig = HttpConfig.readOnly()
  ): Seq[ToolFunction[_, _]] =
    withFilesSafe(fileConfig, httpConfig) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"BuiltinTools.withFiles failed: ${e.formatted}")
    }

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
  ): Result[Seq[ToolFunction[_, _]]] = {
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
   * Development tools with full read/write and shell access.
   *
   * WARNING: These tools have significant access to the system.
   * Use only in trusted development environments.
   *
   * @param workingDirectory Optional working directory for file/shell operations
   * @param fileAllowedPaths Allowed paths for file write operations
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use developmentSafe() which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  def development(
    workingDirectory: Option[String] = None,
    fileAllowedPaths: Seq[String] = Seq("/tmp")
  ): Seq[ToolFunction[_, _]] =
    developmentSafe(workingDirectory, fileAllowedPaths) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"BuiltinTools.development failed: ${e.formatted}")
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
  ): Result[Seq[ToolFunction[_, _]]] = {
    def optionalTool[T](
      config: Option[T]
    )(build: T => Result[ToolFunction[_, _]]): Result[Seq[ToolFunction[_, _]]] =
      config match {
        case Some(cfg) => build(cfg).map(Seq(_))
        case None      => Right(Seq.empty)
      }

    def optionalTools[T](
      config: Option[T]
    )(build: T => Result[Seq[ToolFunction[_, _]]]): Result[Seq[ToolFunction[_, _]]] =
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

  /**
   * Custom tool set with full configuration control.
   *
   * @param fileConfig Configuration for read operations
   * @param writeConfig Optional write configuration (if None, write tool is not included)
   * @param httpConfig HTTP configuration
   * @param shellConfig Optional shell configuration (if None, shell tool is not included)
   * @throws IllegalStateException if any tool initialization fails
   */
  @deprecated("Use customSafe() which returns Result[Seq[ToolFunction]] for safe error handling", "0.2.9")
  def custom(
    fileConfig: Option[FileConfig] = None,
    writeConfig: Option[WriteConfig] = None,
    httpConfig: Option[HttpConfig] = None,
    shellConfig: Option[ShellConfig] = None
  ): Seq[ToolFunction[_, _]] =
    customSafe(fileConfig, writeConfig, httpConfig, shellConfig) match {
      case Right(t) => t
      case Left(e)  => throw new IllegalStateException(s"BuiltinTools.custom failed: ${e.formatted}")
    }
}
