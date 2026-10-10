package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.FileConfig
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding
import upickle.default._

import java.io.File
import java.nio.file.{ Path, Paths }
import java.util.Locale
import java.util.concurrent.TimeUnit
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
 * [[ShellConfig.pathPolicy]] is set; then every file-like argument must pass that `FileConfig`'s
 * `isPathAllowed`: the file tools' allowed and blocked entries and real-location rule, with the argument judged as
 * the program will hand it to the OS, `..` and all. A `..` after a link is read both ways: physically, as POSIX
 * applies it (the link target's parent), and lexically, as Windows applies it (the directory holding the link), and
 * both locations must be allowed. The file tools, which open the path themselves, remove `..` as text first.
 * A value attached to a flag is checked as a path too: the value after `=` of a long option (`--file=x`), and every
 * tail of a short-option cluster (`-fx`, `-ifx`). `file -C`, `-m`, `-M` and `-f`, `date -f` and `-r`,
 * `wc --files0-from`, and `sort -o`, `--output`, `--compress-program` and `--files0-from` (which write a file, run a
 * program or read one the command does not name) are refused whatever the policy: anywhere in a short-option
 * cluster, attached value or not, under any abbreviation of the long form, with or without `=value`, wherever they
 * appear, `--` or not before them, and however the program is spelled (`/usr/bin/file`, `FILE`, `file.exe`).
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

  /**
   * Flags that make an otherwise read-only command write a file, run a program, or read a file it does not name as an
   * argument, by command. `file -M` is Apple's form of `-m` (magic files, whose lines it echoes in warnings). `sort`
   * is not on the read-only list, but is often added to it: `-o` / `--output` write a file, `--compress-program`
   * runs a program and `--files0-from` reads the names of the files to sort from a file.
   */
  private val DeniedShortFlags = Map("file" -> Set('C', 'm', 'M', 'f'), "date" -> Set('f', 'r'), "sort" -> Set('o'))
  private val DeniedLongFlags = Map(
    "file" -> Set("--compile", "--magic-file", "--files-from"),
    "date" -> Set("--file", "--reference"),
    "wc"   -> Set("--files0-from"),
    "sort" -> Set("--output", "--compress-program", "--files0-from")
  )

  /** Endings Windows adds to a program name when it looks one up (`file` runs `file.exe`). */
  private val ExecutableSuffixes = Seq(".exe", ".com", ".bat", ".cmd")

  /**
   * The program a command names, as the option rules match it: its last path component with either separator
   * (`/usr/bin/file`, `C:\tools\file.exe`), lower-cased, with trailing dots and spaces and an executable suffix
   * removed. Windows and macOS's default file system find a program whatever the case of its name, and Windows adds
   * `.exe` itself, so `FILE -C` and `file.exe -C` run `file -C`. Elsewhere this only refuses more.
   */
  private def programName(command: String): String = {
    val last    = command.substring(command.lastIndexWhere(c => c == '/' || c == '\\') + 1)
    val trimmed = last.reverse.dropWhile(c => c == '.' || c == ' ').reverse.toLowerCase(Locale.ROOT)
    ExecutableSuffixes
      .find(suffix => trimmed.endsWith(suffix) && trimmed.length > suffix.length)
      .fold(trimmed)(suffix => trimmed.dropRight(suffix.length))
  }

  /** Why the command may not run, or `None`. */
  private def refusal(command: String, args: Seq[String], config: ShellConfig): Option[String] = {
    // As spelled: only an exact name is exempt from the path policy (`ECHO` may be another program)
    val executable = Try(Paths.get(command).getFileName.toString).getOrElse(command)
    val program    = programName(command)
    deniedFlag(program, args)
      .map(flag => s"Flag '$flag' is not allowed for '$command'")
      .orElse(config.pathPolicy.flatMap(policy => pathRefusal(executable, program, args, config, policy)))
  }

  /**
   * Every argument that looks like a flag, `--` or not before it. A `--` does not reliably end the options: when it
   * follows an option that takes an argument (`file -F -- -f list`), `getopt` reads it as that argument and goes on
   * reading options. So a flag-looking argument is checked wherever it is, which also refuses a file whose name
   * looks like a denied flag; that is the price of not modelling each program's options.
   */
  private def flagsOf(args: Seq[String]): Seq[String] =
    args.filter(arg => arg != "--" && arg.startsWith("-") && arg.length > 1)

  private def deniedFlag(command: String, args: Seq[String]): Option[String] = {
    val shortDenied = DeniedShortFlags.getOrElse(command, Set.empty[Char])
    val longDenied  = DeniedLongFlags.getOrElse(command, Set.empty[String])
    flagsOf(args).find { flag =>
      if (flag.startsWith("--")) longDenied.exists(denied => namesLongOption(flag, denied))
      else flag.drop(1).exists(shortDenied.contains)
    }
  }

  /**
   * Whether `flag` may select the long option `option` (spelled with its leading `--`). GNU `getopt_long` accepts
   * any unambiguous prefix of a long option name (`date --fil` is `date --file`), and a value may follow `=`, so
   * every non-empty prefix of the name counts. This also refuses some prefixes the program would reject as
   * ambiguous, which costs nothing.
   */
  private def namesLongOption(flag: String, option: String): Boolean = {
    val name = flag.takeWhile(_ != '=')
    name.length > 2 && option.startsWith(name)
  }

  /** The long options that make `ls` follow links. */
  private val LsDereferenceOptions =
    Seq("--dereference", "--dereference-command-line", "--dereference-command-line-symlink-to-dir")

  /**
   * Hold the file-like arguments of a command to the path policy: the working directory, every argument and every
   * value attached to a flag must be allowed, and a flag that carries a path, or makes `ls` follow links, is refused.
   */
  private def pathRefusal(
    executable: String,
    program: String,
    args: Seq[String],
    config: ShellConfig,
    policy: FileConfig
  ): Option[String] = {
    // Not normalised: the policy reads a `..` after a link both as POSIX (physical) and as Windows (lexical) does
    val base = Try(config.workingDirectory.fold(Paths.get(""))(Paths.get(_)).toAbsolutePath).toOption
    base match {
      case None =>
        Some("Invalid working directory")
      case Some(dir) if !policy.isPathAllowed(dir) =>
        Some("The working directory is outside the allowed paths")
      case Some(_) if NoFileArguments.contains(executable) => None
      case Some(dir) =>
        argumentRefusal(program, args.filter(_ != "--"), dir, policy)
    }
  }

  /**
   * The longest flag whose attached values are checked one by one; a longer one is refused under a path policy
   * rather than walked (`PATH_MAX` on Linux).
   */
  private val MaxCheckedFlagLength = 4096

  private def isFlag(arg: String): Boolean = arg.startsWith("-") && arg.length > 1

  /**
   * Every argument but `--` is checked both ways: as a flag when it looks like one, and as a path. A `--` may be
   * consumed as an option's argument (see `flagsOf`), and an option's argument may itself be a file
   * (`grep -f -x`), so neither its position nor its leading `-` settles which one the program will take it for.
   * A flag's attached values (see [[attachedValues]]) are checked as paths too.
   */
  private def argumentRefusal(command: String, args: Seq[String], base: Path, policy: FileConfig): Option[String] =
    args.iterator
      .flatMap { arg =>
        if (isFlag(arg) && arg.length > MaxCheckedFlagLength)
          Some(s"Flag '${arg.take(32)}...' is too long to check against the path policy")
        else {
          val asFlag = if (isFlag(arg)) flagRefusal(command, arg) else None
          asFlag
            .orElse(pathArgumentRefusal(arg, base, policy))
            .orElse(attachedValues(arg).flatMap(value => attachedValueRefusal(arg, value, base, policy)).nextOption())
        }
      }
      .nextOption()

  /**
   * The values a flag may carry attached to it, which the program may open as a path (#1723), without modelling
   * which options take a value:
   *
   *  - a long option contributes the value after its `=` (`--file=lout`, and under any abbreviation, `--fil=lout`);
   *  - a short option contributes every tail of its cluster after the dash (`-iflout` gives `iflout`, `flout`, `lout`,
   *    ...), since any letter in it may be an option that takes the rest as its value (`-f lout` attached as
   *    `-flout`, after other options as `-iflout`).
   *
   * A tail that names nothing stays inside the working directory and passes, so the check refuses only a tail that
   * reaches outside the allowed paths: a link out, a blocked file, or `..`. A value that is text to the program
   * (`grep -e..`) is refused when it happens to name such a file; that over-blocking is the price of not modelling
   * each program's options.
   */
  private def attachedValues(arg: String): Iterator[String] =
    if (!isFlag(arg)) Iterator.empty
    else if (arg.startsWith("--")) {
      val at = arg.indexOf('=')
      if (at < 0 || at == arg.length - 1) Iterator.empty else Iterator.single(arg.substring(at + 1))
    } else Iterator.range(1, arg.length).map(arg.substring)

  private def attachedValueRefusal(flag: String, value: String, base: Path, policy: FileConfig): Option[String] =
    Try(base.resolve(value)).toOption match {
      case None => Some(s"Flag '$flag' carries the value '$value', which is not a valid path")
      case Some(path) if !policy.isPathAllowed(path) =>
        Some(s"Flag '$flag' carries the value '$value', which is outside the allowed paths")
      case Some(_) => None
    }

  private def flagRefusal(command: String, flag: String): Option[String] =
    if (flag.contains("/") || flag.contains("\\"))
      Some(s"Flag '$flag' carries a path, which the path policy cannot check")
    else if (command == "ls" && LsDereferenceOptions.exists(option => namesLongOption(flag, option)))
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
