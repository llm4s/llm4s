package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._

import java.io.File
import java.util.concurrent.TimeUnit
import scala.util.Try

/**
 * Shell command execution result.
 */
case class ShellResult(
  command: String,
  exitCode: Int,
  stdout: String,
  stderr: String,
  executionTimeMs: Long,
  truncated: Boolean,
  timedOut: Boolean
)

object ShellResult {
  implicit val shellResultRW: ReadWriter[ShellResult] = macroRW[ShellResult]
}

/**
 * Tool for executing shell commands.
 *
 * IMPORTANT: This tool requires an explicit allowlist of commands for safety.
 * It will not execute any command that is not in the allowlist.
 *
 * Features:
 * - Command allowlist for security
 * - Configurable working directory
 * - Timeout support
 * - Output size limits
 *
 * @example
 * {{{{
 * import org.llm4s.toolapi.builtin.shell._
 *
 * // Read-only shell (safe commands)
 * val readOnlyShell = ShellTool.create(ShellConfig.readOnly())
 *
 * // Development shell (common dev tools)
 * val devShell = ShellTool.create(ShellConfig.development(
 *   workingDirectory = Some("/home/user/project")
 * ))
 *
 * val tools = new ToolRegistry(Seq(devShell))
 * agent.run("List files in the current directory", tools)
 * }}}}
 */
object ShellTool {

  private def createSchema = Schema
    .`object`[Map[String, Any]]("Shell command parameters")
    .withProperty(
      Schema.property(
        "command",
        Schema.string("The shell command to execute")
      )
    )

  /**
   * Create a shell tool with the given configuration, returning a Result for safe error handling.
   *
   * @param config Shell configuration with required allowedCommands
   */
  def createSafe(config: ShellConfig): Result[ToolFunction[Map[String, Any], ShellResult]] =
    ToolBuilder[Map[String, Any], ShellResult](
      name = "shell_command",
      description = s"Execute shell commands. " +
        s"Allowed commands: ${config.allowedCommands.mkString(", ")}. " +
        s"Timeout: ${config.timeoutMs}ms. " +
        config.workingDirectory.map(d => s"Working directory: $d").getOrElse(""),
      schema = createSchema
    ).withHandler { extractor =>
      for {
        command <- extractor.getString("command")
        result  <- executeCommand(command, config)
      } yield result
    }.buildSafe()

  private def executeCommand(
    command: String,
    config: ShellConfig
  ): Either[String, ShellResult] = {
    // Security check - validate command is allowed
    val baseCommand = command.trim.split("\\s+").headOption.getOrElse("")
    if (!config.isCommandAllowed(baseCommand)) {
      Left(s"Command '$baseCommand' is not allowed. Allowed: ${config.allowedCommands.mkString(", ")}")
    } else {
      runProcess(command, config)
    }
  }

  private def runProcess(
    command: String,
    config: ShellConfig
  ): Either[String, ShellResult] = {
    val startTime = System.currentTimeMillis()

    Try {
      val processBuilder = new ProcessBuilder("sh", "-c", command)
        .redirectErrorStream(false)

      // Set working directory if configured
      config.workingDirectory.foreach(dir => processBuilder.directory(new File(dir)))

      // Add environment variables
      val environment = processBuilder.environment()
      config.environment.foreach { case (k, v) => environment.put(k, v) }

      val process = processBuilder.start()

      // Read stdout and stderr in parallel threads with size limits
      val stdoutBuilder             = new StringBuilder
      val stderrBuilder             = new StringBuilder
      @volatile var stdoutTruncated = false
      @volatile var stderrTruncated = false

      val stdoutReader = new Thread(() =>
        readStreamSafely(
          process.getInputStream,
          stdoutBuilder,
          config.maxOutputSize,
          () => stdoutTruncated = true
        )
      )

      val stderrReader = new Thread(() =>
        readStreamSafely(
          process.getErrorStream,
          stderrBuilder,
          config.maxOutputSize,
          () => stderrTruncated = true
        )
      )

      stdoutReader.start()
      stderrReader.start()

      // Wait for process to complete with timeout
      val completed = process.waitFor(config.timeoutMs, TimeUnit.MILLISECONDS)

      val (exitCode, timedOut) = if (!completed) {
        process.destroyForcibly()
        (-1, true)
      } else {
        (process.exitValue(), false)
      }

      // Wait for readers to finish (with small timeout to avoid hanging)
      stdoutReader.join(500)
      stderrReader.join(500)

      val endTime = System.currentTimeMillis()

      val truncated = stdoutTruncated || stderrTruncated
      val stdout = if (stdoutTruncated) {
        stdoutBuilder.toString + "\n... (truncated)"
      } else {
        stdoutBuilder.toString
      }
      val stderr = if (stderrTruncated) {
        stderrBuilder.toString + "\n... (truncated)"
      } else {
        stderrBuilder.toString
      }

      ShellResult(
        command = command,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        executionTimeMs = endTime - startTime,
        truncated = truncated,
        timedOut = timedOut
      )
    }.toEither.left.map(e => s"Command execution failed: ${e.getMessage}")
  }

  /**
   * Safely read from an input stream with size limiting.
   * Silently handles IOException (e.g., when stream is closed due to process destruction).
   */
  private def readStreamSafely(
    is: java.io.InputStream,
    builder: StringBuilder,
    maxSize: Int,
    onTruncate: () => Unit
  ): Unit = {
    val result = scala.util.Try {
      val buffer = new Array[Byte](1024)
      var read   = 0
      while ({ read = is.read(buffer); read != -1 } && builder.length < maxSize) {
        val toAdd = Math.min(read, maxSize - builder.length)
        builder.append(new String(buffer, 0, toAdd))
        if (toAdd < read) onTruncate()
      }
      // Drain remaining input to prevent blocking
      while (is.read(buffer) != -1) onTruncate()
      is.close()
    }
    // Silently ignore IOException - stream may be closed when process is destroyed
    result.failed.foreach {
      case _: java.io.IOException => ()
      case e                      => throw e
    }
  }
}
