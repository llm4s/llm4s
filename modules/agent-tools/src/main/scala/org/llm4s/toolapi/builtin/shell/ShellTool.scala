package org.llm4s.toolapi.builtin.shell

import org.llm4s.toolapi._
import org.llm4s.toolapi.builtin.filesystem.{ FileConfig, PathPolicy }
import org.llm4s.types.Result
import org.llm4s.util.DurationRounding
import upickle.default._

import java.io.File
import java.nio.file.{ Path, Paths }
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{ DurationLong, FiniteDuration }
import scala.collection.mutable
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
 * tail of a short-option cluster (`-fx`, `-ifx`), within a budget of file-system lookups for the whole command.
 * `file -C`, `-m`, `-M` and `-f`, `date -f` and `-r`, `wc --files0-from`, and `sort -o`, `--output`, `-T`,
 * `--temporary-directory`, `--compress-program` and `--files0-from` (which write a file, run a program or read one
 * the command does not name) are refused whatever the policy: anywhere in a short-option
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
   * is not on the read-only list, but is often added to it: `-o` / `--output` write a file, `-T` /
   * `--temporary-directory` write temporary files to a directory of the caller's choosing (which the path policy
   * would check, but which nothing checks without one), `--compress-program` runs a program and `--files0-from`
   * reads the names of the files to sort from a file.
   */
  private val DeniedShortFlags =
    Map("file" -> Set('C', 'm', 'M', 'f'), "date" -> Set('f', 'r'), "sort" -> Set('o', 'T'))
  private val DeniedLongFlags = Map(
    "file" -> Set("--compile", "--magic-file", "--files-from"),
    "date" -> Set("--file", "--reference"),
    "wc"   -> Set("--files0-from"),
    "sort" -> Set("--output", "--temporary-directory", "--compress-program", "--files0-from")
  )

  private val OnWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")

  /**
   * Options whose value is text, never opened as a path, by command: `sort -t` / `--field-separator` take a separator
   * character. A value attached to one is not read as options or checked as a path (`sort -to` sets the separator to
   * `o`, and `sort -t/` to `/`). The letters before it in a cluster still are, and so is a value given as the next
   * argument (`sort -t /`), because an option before `-t` may have taken `-t` as its value and left the next
   * argument an operand (`sort --random-source -t /etc/passwd` reads `/etc/passwd`).
   */
  private val TextShortFlags = if (OnWindows) Map.empty[String, Set[Char]] else Map("sort" -> Set('t'))
  private val TextLongFlags  = Map("sort" -> Set("--field-separator"))

  /**
   * On Windows, `sort` may be Windows' own `sort.exe`, which writes its output with `/O[UTPUT] file` and its
   * temporary files with `/T[EMPORARY] dir`, its switches in either case; or a GNU `sort` found earlier on the
   * `PATH`. So there a cluster holding `o` or `t` in either case is refused (`-t` included, rather than guessing which
   * program runs), and so is a `/` switch starting with one, as the workspace runner's command policy does.
   */
  private val WindowsDeniedShortFlags = Map("sort" -> Set('o', 'O', 't', 'T'))

  private def windowsSwitch(command: String, args: Seq[String]): Option[String] =
    WindowsDeniedShortFlags
      .get(command)
      .filter(_ => OnWindows)
      .flatMap(denied => args.find(arg => arg.length > 1 && arg.startsWith("/") && denied.contains(arg.charAt(1))))

  /**
   * The part of a flag that the option rules read: a short-option cluster up to and including a text option
   * (`-rto` gives `-rt`), and a text long option's name without its value (`--field-separator=o`).
   */
  private def optionPart(program: String, flag: String): String =
    if (flag.startsWith("--")) {
      val name = flag.takeWhile(_ != '=')
      if (TextLongFlags.getOrElse(program, Set.empty[String]).exists(option => namesLongOption(name, option))) name
      else flag
    } else {
      val text = TextShortFlags.getOrElse(program, Set.empty[Char])
      val at   = flag.indexWhere(text.contains, 1)
      if (at < 0) flag else flag.substring(0, at + 1)
    }

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
      .orElse(windowsSwitch(program, args))
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
    val shortDenied = DeniedShortFlags.getOrElse(command, Set.empty[Char]) ++
      (if (OnWindows) WindowsDeniedShortFlags.getOrElse(command, Set.empty[Char]) else Set.empty[Char])
    val longDenied = DeniedLongFlags.getOrElse(command, Set.empty[String])
    flagsOf(args).find { flag =>
      val part = optionPart(command, flag)
      if (part.startsWith("--")) longDenied.exists(denied => namesLongOption(part, denied))
      else part.drop(1).exists(shortDenied.contains)
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
   * The checks share one `PathChecks`, so the whole command costs at most `MaxPathSteps` lookups.
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
      case None => Some("Invalid working directory")
      case Some(dir) =>
        val checks = new PathChecks(dir, policy.entries)
        checks.check(dir) match {
          case Unchecked => Some(tooCostly(program))
          case Refused   => Some("The working directory is outside the allowed paths")
          case Allowed if NoFileArguments.contains(executable) => None
          case Allowed =>
            if (args.exists(_.length > MaxArgumentLength)) Some(tooCostly(program))
            else argumentRefusal(program, args.filter(_ != "--"), checks)
        }
    }
  }

  /** The longest argument checked against the path policy; a longer one is refused rather than walked (`PATH_MAX`). */
  private val MaxArgumentLength = 4096

  /**
   * The file-system lookups (about two per path component, one for each reading of `..`) the path policy may spend
   * on one command, as the workspace runner's command policy allows. An ordinary command spends a few hundred; the
   * cap stops a crafted one (thousands of options, each with thousands of tails to check) from holding the caller
   * for minutes before the command starts, which `ShellConfig.timeout` would not cover.
   */
  private val MaxPathSteps = 20000

  private def tooCostly(program: String): String =
    s"The arguments of '$program' are too long or too many to check against the path policy " +
      s"(at most $MaxArgumentLength characters an argument and $MaxPathSteps path lookups a command)"

  /**
   * The longest single name (path component) any supported file system allows, in its own unit: 255 bytes on ext4,
   * XFS, Btrfs and APFS, 255 UTF-16 code units on NTFS and HFS+, 1023 bytes on OpenZFS 2.3+ with `longname=on`, and
   * 1024 bytes on Linux FUSE (rclone, s3fs, virtiofs; `FUSE_NAME_MAX`). The assumption the skip below rests on is that
   * no supported file system allows a single name of more than this many bytes or UTF-16 code units.
   */
  private[shell] val MaxNameLength = 1024

  /**
   * How many plain name characters a tail must begin with to be skipped: one over [[MaxNameLength]]. A combining
   * character after the run may compose with its last character, but never into nothing, so the name keeps at least
   * `MaxNameLength + 1` units in every encoding and normal form.
   */
  private[shell] val MinUnnameableRun = MaxNameLength + 1

  /** Characters no supported file system or path parser treats specially, and that no other spelling folds to. */
  private def isPlainNameChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-_+=,@".indexOf(c.toInt) >= 0

  /**
   * For each start in `value`, whether `value.substring(start)` cannot name anything, so need not be checked: it
   * holds no separator (`/` or `\`), so it is one path component, and it begins with at least `MinUnnameableRun`
   * ASCII letters, digits and `-_+=,@`. Each of those takes one byte and one UTF-16 code unit, so the name is over
   * every supported file system's limit for a component ([[MaxNameLength]]) in either unit, whatever follows them. No
   * shorter name can stand for it: Windows changes none of these characters (it drops a trailing `.` or space, reads
   * `:` as a stream and `~` in short names, all after them), and a name that a case-insensitive or
   * normalisation-insensitive file system matches to them is no shorter in the unit that file system counts (`ß`, two
   * bytes in UTF-8, folds to `ss`; the Kelvin sign, three bytes, to `k`; NTFS and HFS+ fold one UTF-16 unit to one).
   * So the program cannot open it, and it can lead nowhere. Checking it would cost lookups like any other value, and
   * a flag of 4096 letters has thousands of such tails (#1723). Computed once a value, so that a long flag is not
   * scanned again for each of its tails.
   */
  private[shell] def unnameable(value: String): Int => Boolean = {
    val lastSeparator = value.lastIndexWhere(c => c == '/' || c == '\\')
    // How many plain characters run from each index
    val plainRun = value.scanRight(0)((c, run) => if (isPlainNameChar(c)) run + 1 else 0)
    start => start > lastSeparator && plainRun(start) >= MinUnnameableRun
  }

  /** What a check of one path found. */
  sealed private trait Verdict
  private case object Allowed   extends Verdict
  private case object Refused   extends Verdict // outside the allowed paths, or not a valid path
  private case object Unchecked extends Verdict // the command's lookup budget ran out first

  /**
   * The path checks of one command: against `entries`, resolved once for the command, from `base`, each distinct
   * value checked once, and all of them within `MaxPathSteps` lookups.
   */
  final private class PathChecks(base: Path, entries: PathPolicy.Entries) {
    private var remaining = MaxPathSteps
    private val seen      = mutable.HashMap.empty[String, Verdict]

    def check(path: Path): Verdict = {
      // Two readings (physical and lexical), each a lookup per component and one for the root
      remaining -= 2 * (path.getNameCount + 1)
      if (remaining < 0) Unchecked
      else if (PathPolicy.resolve(path, entries).isDefined) Allowed
      else Refused
    }

    /** `value` resolved against the working directory, as the program will open it. */
    def checkValue(value: String): Verdict =
      seen.getOrElse(
        value, {
          val verdict = Try(base.resolve(value)).toOption.fold[Verdict](Refused)(check)
          if (verdict != Unchecked) seen.update(value, verdict)
          verdict
        }
      )
  }

  private def isFlag(arg: String): Boolean = arg.startsWith("-") && arg.length > 1

  /**
   * Every argument but `--` is checked both ways: as a flag when it looks like one, and as a path. A `--` may be
   * consumed as an option's argument (see `flagsOf`), and an option's argument may itself be a file
   * (`grep -f -x`), so neither its position nor its leading `-` settles which one the program will take it for.
   * A flag's attached values (see `attachedValues`) are checked as paths too.
   */
  private def argumentRefusal(program: String, args: Seq[String], checks: PathChecks): Option[String] =
    args.iterator
      .flatMap { arg =>
        val asFlag = if (isFlag(arg)) flagRefusal(program, arg) else None
        asFlag
          .orElse(valueRefusal(program, arg, None, Iterator.single(0).filterNot(unnameable(arg)), checks))
          .orElse(valueRefusal(program, arg, Some(arg), attachedValues(program, arg), checks))
      }
      .nextOption()

  /**
   * The first refusal among the values of `arg` that start at `starts`: `arg` itself as an argument (`flag` empty),
   * or the values attached to the flag `arg`.
   */
  private def valueRefusal(
    program: String,
    arg: String,
    flag: Option[String],
    starts: Iterator[Int],
    checks: PathChecks
  ): Option[String] =
    starts
      .map { start =>
        val value = arg.substring(start)
        (value, checks.checkValue(value))
      }
      .collectFirst {
        case (_, Unchecked) => tooCostly(program)
        case (value, Refused) =>
          flag.fold(s"Argument '$value' is outside the allowed paths, or not a valid path")(f =>
            s"Flag '$f' carries the value '$value', which is outside the allowed paths, or not a valid path"
          )
      }

  /**
   * Where the values a flag may carry attached to it start, which the program may open as a path (#1723), without
   * modelling which options take a value:
   *
   *  - a long option contributes the value after its `=` (`--file=lout`, and under any abbreviation, `--fil=lout`);
   *  - a short option contributes every tail of its cluster after the dash (`-iflout` gives `iflout`, `flout`, `lout`,
   *    ...), since any letter in it may be an option that takes the rest as its value (`-f lout` attached as
   *    `-flout`, after other options as `-iflout`).
   *
   * The value of a text option (see `TextShortFlags`) is left out, and so is a tail that cannot name anything (see
   * `unnameable`). A tail that names nothing stays inside the working directory and passes, so the check refuses
   * only a tail that reaches outside the allowed paths: a link out, a blocked file, or `..`. A value that is text to
   * the program (`grep -e..`) is refused when it happens to name such a file; that over-blocking is the price of not
   * modelling each program's options.
   */
  private def attachedValues(program: String, arg: String): Iterator[Int] =
    if (!isFlag(arg)) Iterator.empty
    else if (arg.startsWith("--")) {
      val part = optionPart(program, arg)
      val at   = part.indexOf('=')
      if (at < 0 || at == arg.length - 1 || unnameable(arg)(at + 1)) Iterator.empty else Iterator.single(at + 1)
    } else {
      Iterator.range(1, optionPart(program, arg).length).filterNot(unnameable(arg))
    }

  private def flagRefusal(command: String, flag: String): Option[String] =
    if (optionPart(command, flag).exists(c => c == '/' || c == '\\'))
      Some(s"Flag '$flag' carries a path, which the path policy cannot check")
    else if (command == "ls" && LsDereferenceOptions.exists(option => namesLongOption(flag, option)))
      Some(s"Flag '$flag' follows links, which the path policy does not allow")
    else if (command == "ls" && !flag.startsWith("--") && flag.drop(1).exists(c => c == 'L' || c == 'H'))
      Some(s"Flag '$flag' follows links, which the path policy does not allow")
    else None

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
