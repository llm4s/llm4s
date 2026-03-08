package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin._
import org.llm4s.toolapi.builtin.filesystem._
import org.llm4s.toolapi.builtin.http._
import org.llm4s.toolapi.builtin.shell._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating the built-in tools module.
 *
 * The built-in tools provide ready-to-use utilities for common agent tasks:
 * - Core utilities: DateTime, Calculator, UUID, JSON
 * - File system: Read, Write, List, Info
 * - HTTP: Make HTTP requests with domain/method restrictions
 * - Shell: Execute commands with allowlist
 * - Search: Web search via DuckDuckGo
 *
 * Tools come in pre-configured bundles:
 * - `BuiltinTools.coreSafe` - Just the core utilities (always safe)
 * - `BuiltinTools.withHttpSafe()` - Core + network (web search, HTTP)
 * - `BuiltinTools.withFilesSafe()` - Safe + read-only file access
 * - `BuiltinTools.developmentSafe()` - All tools for development environments
 *
 * @example
 * {{{
 * sbt "samples/runMain org.llm4s.samples.toolapi.BuiltinToolsExample"
 * }}}
 */
object BuiltinToolsExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Built-in Tools Example ===\n")

    // 1. Core utilities - always safe, no external dependencies
    demonstrateCoreTools()

    // 2. File system tools with custom configuration
    demonstrateFileTools()

    // 3. Shell tools with allowlist
    demonstrateShellTools()

    // 4. Tool bundles for different use cases
    demonstrateToolBundles()
  }

  private def demonstrateCoreTools(): Unit = {
    logger.info("--- Core Utilities ---")

    val result = for {
      tools <- BuiltinTools.coreSafe
    } yield {
      val registry = new ToolRegistry(tools)

      // DateTime tool
      logger.info("Getting current date/time...")
      registry.execute(
        ToolCallRequest(
          functionName = "get_current_datetime",
          arguments = ujson.Obj("timezone" -> "America/New_York")
        )
      ) match {
        case Right(result) => logger.info("DateTime result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("DateTime failed: {}", error)
      }

      // Calculator tool
      logger.info("\nPerforming calculation...")
      registry.execute(
        ToolCallRequest(
          functionName = "calculator",
          arguments = ujson.Obj("operation" -> "sqrt", "a" -> 144.0)
        )
      ) match {
        case Right(result) => logger.info("Calculator result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("Calculator failed: {}", error)
      }

      // UUID tool
      logger.info("\nGenerating UUIDs...")
      registry.execute(
        ToolCallRequest(
          functionName = "generate_uuid",
          arguments = ujson.Obj("count" -> 3)
        )
      ) match {
        case Right(result) => logger.info("UUID result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("UUID failed: {}", error)
      }

      // JSON tool
      logger.info("\nParsing and querying JSON...")
      registry.execute(
        ToolCallRequest(
          functionName = "json_tool",
          arguments = ujson.Obj(
            "operation" -> "query",
            "json"      -> """{"users": [{"name": "Alice", "age": 30}, {"name": "Bob", "age": 25}]}""",
            "path"      -> "users[0].name"
          )
        )
      ) match {
        case Right(result) => logger.info("JSON query result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("JSON query failed: {}", error)
      }

      logger.info("")
    }

    result.left.foreach(err => logger.error("Failed to load core tools: {}", err.formatted))
  }

  private def demonstrateFileTools(): Unit = {
    logger.info("--- File System Tools ---")

    // Configure file access with explicit allowed paths
    val fileConfig = FileConfig(
      allowedPaths = Some(Seq("/tmp", System.getProperty("user.home"))),
      blockedPaths = Seq("/etc", "/var")
    )

    val writeConfig = WriteConfig(
      allowedPaths = Seq("/tmp"),
      allowOverwrite = true
    )

    val result = for {
      tools <- BuiltinTools.customSafe(
        fileConfig = Some(fileConfig),
        writeConfig = Some(writeConfig)
      )
    } yield {
      val registry = new ToolRegistry(tools)

      // List directory
      logger.info("Listing /tmp directory...")
      registry.execute(
        ToolCallRequest(
          functionName = "list_directory",
          arguments = ujson.Obj("path" -> "/tmp", "max_entries" -> 5)
        )
      ) match {
        case Right(result) => logger.info("Directory listing: {}", result.render(indent = 2))
        case Left(error)   => logger.error("List failed: {}", error)
      }

      // Write a file
      val testFile = s"/tmp/llm4s-sample-${System.currentTimeMillis()}.txt"
      logger.info(s"\nWriting to $testFile...")
      registry.execute(
        ToolCallRequest(
          functionName = "write_file",
          arguments = ujson.Obj("path" -> testFile, "content" -> "Hello from llm4s!")
        )
      ) match {
        case Right(result) => logger.info("Write result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("Write failed: {}", error)
      }

      // Read it back
      logger.info(s"\nReading $testFile...")
      registry.execute(
        ToolCallRequest(
          functionName = "read_file",
          arguments = ujson.Obj("path" -> testFile)
        )
      ) match {
        case Right(result) => logger.info("Read result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("Read failed: {}", error)
      }

      // Get file info
      logger.info(s"\nGetting file info for $testFile...")
      registry.execute(
        ToolCallRequest(
          functionName = "file_info",
          arguments = ujson.Obj("path" -> testFile)
        )
      ) match {
        case Right(result) => logger.info("File info: {}", result.render(indent = 2))
        case Left(error)   => logger.error("File info failed: {}", error)
      }

      // Cleanup
      new java.io.File(testFile).delete()
      logger.info("")
    }

    result.left.foreach(err => logger.error("Failed to load file tools: {}", err.formatted))
  }

  private def demonstrateShellTools(): Unit = {
    logger.info("--- Shell Tools ---")

    // Read-only shell with safe commands
    val config = ShellConfig.readOnly()
    val result = for {
      tools <- BuiltinTools.customSafe(
        shellConfig = Some(config)
      )
    } yield {
      val registry = new ToolRegistry(tools)

      // Execute 'pwd' command
      logger.info("Executing 'pwd' command...")
      registry.execute(
        ToolCallRequest(
          functionName = "shell_command",
          arguments = ujson.Obj("command" -> "pwd")
        )
      ) match {
        case Right(result) => logger.info("Shell result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("Shell failed: {}", error)
      }

      // Execute 'date' command
      logger.info("\nExecuting 'date' command...")
      registry.execute(
        ToolCallRequest(
          functionName = "shell_command",
          arguments = ujson.Obj("command" -> "date")
        )
      ) match {
        case Right(result) => logger.info("Shell result: {}", result.render(indent = 2))
        case Left(error)   => logger.error("Shell failed: {}", error)
      }

      // Try a blocked command
      logger.info("\nTrying blocked 'rm' command (should fail)...")
      registry.execute(
        ToolCallRequest(
          functionName = "shell_command",
          arguments = ujson.Obj("command" -> "rm -rf /tmp/test")
        )
      ) match {
        case Right(result) => logger.info("Unexpected success: {}", result)
        case Left(error)   => logger.info("Correctly blocked: {}", error)
      }

      logger.info("")
    }

    result.left.foreach(err => logger.error("Failed to load shell tools: {}", err.formatted))
  }

  private def demonstrateToolBundles(): Unit = {
    logger.info("--- Tool Bundles ---")

    // Core - always safe
    BuiltinTools.coreSafe.fold(
      err => logger.error("Core tools failed: {}", err.formatted),
      tools => logger.info("Core tools ({}): {}", tools.size, tools.map(_.name).mkString(", "))
    )

    // Safe - core + network
    BuiltinTools
      .withHttpSafe()
      .fold(
        err => logger.error("Safe tools failed: {}", err.formatted),
        tools => logger.info("Safe tools ({}): {}", tools.size, tools.map(_.name).mkString(", "))
      )

    // With files - safe + file read
    BuiltinTools
      .withFilesSafe()
      .fold(
        err => logger.error("File tools failed: {}", err.formatted),
        tools => logger.info("File tools ({}): {}", tools.size, tools.map(_.name).mkString(", "))
      )

    // Development - everything
    BuiltinTools
      .developmentSafe()
      .fold(
        err => logger.error("Development tools failed: {}", err.formatted),
        tools => logger.info("Development tools ({}): {}", tools.size, tools.map(_.name).mkString(", "))
      )

    // Custom configuration
    BuiltinTools
      .customSafe(
        httpConfig = Some(HttpConfig.readOnly()),
        fileConfig = Some(FileConfig()),
        writeConfig = None, // No write access
        shellConfig = Some(ShellConfig.readOnly())
      )
      .fold(
        err => logger.error("Custom tools failed: {}", err.formatted),
        tools => logger.info("Custom tools ({}): {}", tools.size, tools.map(_.name).mkString(", "))
      )

    logger.info("\n=== Example Complete ===")
  }
}
