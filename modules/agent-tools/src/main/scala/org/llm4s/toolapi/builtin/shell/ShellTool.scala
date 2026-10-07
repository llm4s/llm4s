package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding
import upickle.default._

import java.io.File
import java.nio.file.{ Path, Paths }
import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.duration.{ DurationLong, FiniteDuration }
import scala.util.Try

/**
 * Shell command execution result.
 */
case class ShellResult(
  command: String,
  exitCode: Int,
  stdout: String,
  stderr: String,
  @upickle.implicits.key("executionTimeMs") executionTime: FiniteDuration,
  truncated: Boolean,
  timedOut: Boolean
)

object ShellResult {
  import org.llm4s.util.DurationJson.millisRW
  implicit val shellResultRW: ReadWriter[ShellResult] = macroRW[ShellResult]
}

/**
 * Tool for executing shell commands under a strict allowlist.
 *
 * IMPORTANT: This tool requires an explicit allowlist of commands for safety.
 * It will not execute any command whose first token is not in the allowlist.
 *
 * == Execution model ==
 *
 * Commands are tokenized with shell-style quoting (see [[CommandTokenizer]]) and
 * executed directly via [[ProcessBuilder]] '''without invoking a shell'''. As a
 * consequence:
 *
 *   - No glob expansion (`*`, `?`, `[abc]` are passed literally to the program).
 *   - No variable substitution (`$VAR`, `${VAR}`, `$(...)`, backticks).
 *   - No command chaining or redirection (`&&`, `;`, `|`, `<`, `>`, `&`).
 *   - Quoted arguments are honoured: `echo "hello world"` runs `echo` with one
 *     argument `hello world`, not two.
 *
 * Combined with the allowlist, this means LLM-supplied input that contains
 * shell metacharacters cannot escape into a shell — characters such as `&&`
 * survive tokenization as literal argument bytes and are handed to the
 * allowlisted program, which generally treats them as harmless input.
 *
 * == Environment and files ==
 *
 * A command receives a scrubbed environment (see [[ShellConfig]]): only the variables named in
 * `inheritedEnvironment`, plus `environment`. The files a command names are not checked unless
 * [[ShellConfig.pathPolicy]] is set; then every file-like argument must pass the same containment rule as the
 * file tools. `file -C`, `-m` and `-f` (which write a file or read a list of files) and `wc --files0-from` are
 * refused whatever the policy.
 *
 * == Features ==
 *
 *   - Command allowlist for security
 *   - Configurable working directory
 *   - Optional path policy for file arguments
 *   - Scrubbed environment
 *   - Timeout support
 *   - Output size limits
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
        s"Timeout: ${config.timeout.toMillis}ms. " +
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
  ): Either[String, ShellResult] =
    CommandTokenizer.tokenize(command).flatMap { tokens =>
      tokens.headOption match {
        case None =>
          Left("Command cannot be empty")
        case Some(baseCommand) if !config.isCommandAllowed(baseCommand) =>
          Left(s"Command '$baseCommand' is not allowed. Allowed: ${config.allowedCommands.mkString(", ")}")
        case Some(baseCommand) =>
          refusal(baseCommand.trim, tokens.drop(1), config) match {
            case Some(reason) => Left(reason)
            case None         => runProcess(tokens, command, config)
          }
      }
    }

  /** Commands whose arguments are not file names, so the path policy does not look at them. */
  private val NoFileArguments = Set("echo", "pwd", "date", "whoami", "which")

  /** Flags that make an otherwise read-only command write a file or read a list of files, by command. */
  private val DeniedShortFlags = Map("file" -> Set('C', 'm', 'f'))
  private val DeniedLongFlags = Map(
    "file" -> Set("--compile", "--magic-file", "--files-from"),
    "wc"   -> Set("--files0-from")
  )

  /** Why the command may not run, or `None`. */
  private def refusal(command: String, args: Seq[String], config: ShellConfig): Option[String] =
    deniedFlag(command, args)
      .map(flag => s"Flag '$flag' is not allowed for '$command'")
      .orElse(config.pathPolicy.flatMap(policy => pathRefusal(command, args, config, policy)))

  private def flagsOf(args: Seq[String]): Seq[String] =
    args.takeWhile(_ != "--").filter(arg => arg.startsWith("-") && arg.length > 1)

  private def deniedFlag(command: String, args: Seq[String]): Option[String] = {
    val shortDenied = DeniedShortFlags.getOrElse(command, Set.empty[Char])
    val longDenied  = DeniedLongFlags.getOrElse(command, Set.empty[String])
    flagsOf(args).find { flag =>
      if (flag.startsWith("--")) longDenied.exists(denied => flag == denied || flag.startsWith(denied + "="))
      else flag.drop(1).exists(shortDenied.contains)
    }
  }

  /**
   * Hold the file-like arguments of a command to the path policy: the working directory and every argument that is
   * not a flag must be allowed, and a flag that carries a path, or makes `ls` follow links, is refused.
   */
  private def pathRefusal(
    command: String,
    args: Seq[String],
    config: ShellConfig,
    policy: FileConfig
  ): Option[String] =
    if (NoFileArguments.contains(command)) None
    else {
      val base = Try(config.workingDirectory.fold(Paths.get(""))(Paths.get(_)).toAbsolutePath.normalize()).toOption
      base match {
        case None =>
          Some("Invalid working directory")
        case Some(dir) if !policy.isPathAllowed(dir) =>
          Some("The working directory is outside the allowed paths")
        case Some(dir) =>
          argumentRefusal(command, args.toList, flagsEnded = false, dir, policy)
      }
    }

  @tailrec
  private def argumentRefusal(
    command: String,
    args: List[String],
    flagsEnded: Boolean,
    base: Path,
    policy: FileConfig
  ): Option[String] =
    args match {
      case Nil                         => None
      case "--" :: rest if !flagsEnded => argumentRefusal(command, rest, flagsEnded = true, base, policy)
      case arg :: rest =>
        val isFlag  = !flagsEnded && arg.startsWith("-") && arg.length > 1
        val refused = if (isFlag) flagRefusal(command, arg) else pathArgumentRefusal(arg, base, policy)
        if (refused.isDefined) refused else argumentRefusal(command, rest, flagsEnded, base, policy)
    }

  private def flagRefusal(command: String, flag: String): Option[String] =
    if (flag.contains("/") || flag.contains("\\"))
      Some(s"Flag '$flag' carries a path, which the path policy cannot check")
    else if (command == "ls" && flag.startsWith("--dereference"))
      Some(s"Flag '$flag' follows links, which the path policy does not allow")
    else if (command == "ls" && !flag.startsWith("--") && flag.drop(1).exists(c => c == 'L' || c == 'H'))
      Some(s"Flag '$flag' follows links, which the path policy does not allow")
    else None

  private def pathArgumentRefusal(arg: String, base: Path, policy: FileConfig): Option[String] =
    Try(base.resolve(arg)).toOption match {
      case None                                      => Some(s"Invalid path argument '$arg'")
      case Some(path) if !policy.isPathAllowed(path) => Some(s"Argument '$arg' is outside the allowed paths")
      case Some(_)                                   => None
    }

  private def runProcess(
    tokens: Seq[String],
    originalCommand: String,
    config: ShellConfig
  ): Either[String, ShellResult] = {
    val startTime = System.currentTimeMillis()

    Try {
      val processBuilder = new ProcessBuilder(tokens: _*)
        .redirectErrorStream(false)

      // Set working directory if configured
      config.workingDirectory.foreach(dir => processBuilder.directory(new File(dir)))

      // The command gets only the inherited variables that were named, then the configured ones
      val environment = processBuilder.environment()
      config.inheritedEnvironment.foreach { names =>
        val kept = names.flatMap(name => Option(environment.get(name)).map(name -> _))
        environment.clear()
        kept.foreach { case (k, v) => environment.put(k, v) }
      }
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
      val completed = process.waitFor(DurationRounding.ceilMillis(config.timeout), TimeUnit.MILLISECONDS)

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
        command = originalCommand,
        exitCode = exitCode,
        stdout = stdout,
        stderr = stderr,
        executionTime = (endTime - startTime).millis,
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
