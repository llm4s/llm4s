package org.llm4s.runner

import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

/**
 * What an allowlisted command may be given: its options, its path arguments and its environment (#1715).
 *
 * The allowlist in [[org.llm4s.shared.WorkspaceSandboxConfig.allowedCommands]] names programs. A program on it can
 * still write, delete or run other programs through its own options (`find -delete`, `find -exec`, `git -c`,
 * `sort -o`, `uniq in out`) or read and write outside the workspace through a path argument (`cat /etc/passwd`).
 * [[WorkspaceAgentInterfaceImpl.executeCommand]] therefore runs three more checks after the allowlist:
 *
 *  1. '''Environment''' (`ENVIRONMENT_NOT_ALLOWED`). Only locale and display variables may be set:
 *     `LANG`, `LANGUAGE`, `LC_*`, `TZ`, `TERM`, `COLUMNS`, `LINES`, `NO_COLOR`. Anything else could make an
 *     allowlisted program run another one (`GIT_EXTERNAL_DIFF`, `GIT_CONFIG_*`, `PAGER`, `LD_PRELOAD`,
 *     `GCONV_PATH`, `PATH`, `HOME` for a `~/.gitconfig`).
 *  2. '''Options''' (`ARGUMENT_NOT_ALLOWED`). Each program has the options it refuses; `git` instead allows only a
 *     set of read subcommands, refuses every global option bar a few harmless ones, and refuses the subcommand
 *     options that write a file or run a program; `uniq` takes at most one operand; `hostname` takes none. Options
 *     are matched the way the program parses them, erring towards refusal:
 *     - every argument is scanned, including those after `--`, because an option that takes a value may consume
 *       the `--` itself (`sort -k -- -o out`);
 *     - a short option is refused anywhere in a cluster (`-ro out`), attached value or not;
 *     - a long option is refused under any abbreviation of at least one letter (`--outp`), since GNU programs and
 *       `git` accept an unambiguous prefix, and with or without `=value`.
 *  3. '''Paths''' (`PATH_ESCAPE_ATTEMPT`, the code the file operations use). Every argument - and the value of a
 *     `--name=value` option and every tail of a short option, so the `/etc/x` in `--file=/etc/x` and `-f/etc/x` -
 *     is resolved the way the kernel would resolve it from the real working directory: component by component,
 *     following each symbolic link where it is met, so `link/..` goes to the parent of the link's target, not back
 *     to the directory holding the link. The result must lie inside the real workspace root, and so must the lexical
 *     reading Win32 uses - `.` and `..` removed as text first, then the links of the result resolved - so with
 *     `l` -> `a/b`, `l/../../x` (`a/x` physically, `../x` textually) is refused on every platform. An argument that
 *     does not name an existing file is judged the same way, so `../x` and `/tmp/x` are refused even when they do not
 *     exist yet. Programs that only
 *     print their arguments (`echo`, `pwd`, `whoami`, `hostname`) are not checked. For `cp`, which writes through
 *     a link it finds at the name it writes, the names it will write are checked too (see [[cpDestinationRefusal]]).
 *     An argument over [[MaxArgumentLength]] characters, or paths costing more than [[MaxPathSteps]] lookups, are
 *     refused with `ARGUMENT_NOT_ALLOWED` rather than walked. Where the platform cannot parse an argument as a path
 *     (Windows: `HEAD:src/x`, `..\*`), the part before the first character a path cannot hold is judged; one that
 *     starts with a separator but has no such part (`\\?\C:\x`) is refused, as is, on Windows, a drive-relative
 *     path on another drive (`D:x`). Because Win32 removes `..` as text before it opens a name or matches a wildcard,
 *     such an argument is also refused when it has a `..` component after that character (`x*\..\..\f`), or when,
 *     with each such character replaced by `_`, it leads outside. An argument holding a NUL character, or on Windows a
 *     `"` (which the argv parser and cmd.exe delete, so `"..\x` opens `..\x`), is refused (`ARGUMENT_NOT_ALLOWED`), and
 *     a check that fails with an exception refuses the command rather than throwing it. A wildcard in the last
 *     component (`.*`, `.?`) can match the `..` entry, but `dir` only lists that entry and `type` / `findstr` cannot
 *     read a directory, so it is not refused; a wildcard is not allowed in an earlier component.
 *
 * The path rule cannot tell a path from text that looks like one: a `grep` pattern or an option value that starts
 * with `/` or has a `..` component is refused too (write `[/]api` for `/api`). A relative value without `..` can only
 * leave the workspace through a link, so `--since=2024/01/01` and `--grep=feat/x` run.
 *
 * '''Limits.''' The checks cover what a command is given, not what a program reads by itself. A recursive walk
 * (`ls -R`, `grep -r`, `find`, `diff -r`) is checked where it starts; `ls -L`, `grep -R`/`-S`, `find -L`,
 * `cp -L`/`-H` and `chmod -L`/`-H`, which follow links they meet, are refused, but `diff -r` follows links inside the
 * tree it walks. `git` reads the repository's own configuration and runs its hooks, so a repository whose
 * `.git/config` sets `core.fsmonitor`, `diff.external` or a filter or textconv driver, or whose `.git/hooks` has a
 * `post-index-change` hook, makes `git status` or `git diff` run that program; where the agent can write files (the
 * `writeFile` operation, or `cp`/`mv` in [[org.llm4s.shared.WorkspaceSandboxConfig.ReadWriteCommands]]) it can write
 * them (#1721). The checks run before the program starts and do not see a link a concurrent command makes.
 */
private[runner] object CommandPolicy {

  /** A refused command: the structured error code and a message naming the argument. */
  final case class Refusal(code: String, message: String)

  val ArgumentNotAllowed    = "ARGUMENT_NOT_ALLOWED"
  val PathEscapeAttempt     = "PATH_ESCAPE_ATTEMPT"
  val EnvironmentNotAllowed = "ENVIRONMENT_NOT_ALLOWED"

  /**
   * The options a program refuses.
   *
   * @param short       letters refused anywhere in a short-option cluster (`-o`, `-ro`, `-ofile`)
   * @param long        long options refused under any abbreviation of at least one letter, with or without `=value`
   * @param exact       arguments refused when they are exactly this (`find`'s single-dash primaries)
   * @param longAllowed long options that are themselves a prefix of a refused one (`--text` of `--textconv`)
   * @param textShort   short options whose value is text, never opened as a path (`sort -t/`)
   * @param textLong    long options whose value is text, never opened as a path (`sort --field-separator=/`)
   */
  final private case class Options(
    short: Set[Char] = Set.empty,
    long: Set[String] = Set.empty,
    exact: Set[String] = Set.empty,
    longAllowed: Set[String] = Set.empty,
    textShort: Set[Char] = Set.empty,
    textLong: Set[String] = Set.empty
  )

  private val ProgramOptions: Map[String, Options] = Map(
    // Deletes, runs a program, writes a file, reads starting points from a file, follows every link.
    "find" -> Options(
      exact = Set(
        "-delete",
        "-exec",
        "-execdir",
        "-ok",
        "-okdir",
        "-fprint",
        "-fprint0",
        "-fprintf",
        "-fls",
        "-files0-from",
        "-follow"
      )
    ),
    // Follows every link while listing.
    "ls" -> Options(short = Set('L'), long = Set("--dereference")),
    // Follows every link while recursing (`-S` on BSD grep).
    "grep" -> Options(short = Set('R', 'S'), long = Set("--dereference-recursive")),
    // Reads the names of the files to count from a file.
    "wc" -> Options(long = Set("--files0-from")),
    // Writes a file, runs a program, reads the names of the files to sort from a file.
    // `-t` / `--field-separator` take a separator character, not a path.
    "sort" -> Options(
      short = Set('o'),
      long = Set("--output", "--compress-program", "--files0-from"),
      textShort = Set('t'),
      textLong = Set("--field-separator")
    ),
    // Follow links met inside the tree (`-L`, `--dereference`) or on the command line (`-H`) and copy what they
    // point at, or make links (`-s`, `--symbolic-link`).
    "cp" -> Options(short = Set('L', 'H', 's'), long = Set("--dereference", "--symbolic-link")),
    // Follow links met inside the tree (`-L`) or on the command line (`-H`) and change what they point at.
    "chmod" -> Options(short = Set('L', 'H'), long = Set("--dereference")),
    // Sets the host name.
    "hostname" -> Options(short = Set('F', 'b'), long = Set("--file", "--boot"))
  )

  /** `find`'s leading options in one cluster (`-HL` on BSD); one with `L` follows every link. */
  private val FindFollowCluster = "-[HLPEXdsx]*L[HLPEXdsx]*".r

  /** Programs whose arguments are not file names, so the path rule is not applied. */
  private val PrintOnly: Set[String] = Set("echo", "pwd", "whoami", "hostname")

  /** cmd.exe built-ins and Windows programs whose options are `/X` switches rather than paths. */
  private val WindowsSwitchPrograms: Set[String] = Set("dir", "findstr", "copy", "move", "sort")

  /** A switch's letter and its value (`G:file`), which would otherwise parse as a path relative to drive G:. */
  private val SwitchWithValue = "(?s)[A-Za-z]:([^\\\\/].*)?".r

  // ---- git

  /** Global options git may be given before the subcommand; everything else (`-c`, `-C`, `--git-dir`, ...) is refused. */
  private val GitGlobalAllowed: Set[String] =
    Set("--version", "--no-pager", "--no-optional-locks", "--literal-pathspecs", "--no-replace-objects")

  /** The git subcommands that only read. `branch` only lists (see [[gitBranchRefusal]]). */
  val GitReadSubcommands: Set[String] =
    Set("status", "log", "show", "diff", "ls-files", "ls-tree", "grep", "blame", "rev-parse", "branch")

  // `--output` writes a file; `--ext-diff`, `--textconv` and `--show-signature` run a program.
  private val GitDiffOptions = Options(
    long = Set("--output", "--ext-diff", "--textconv", "--show-signature"),
    longAllowed = Set("--text")
  )

  private val GitSubcommandOptions: Map[String, Options] = Map(
    "log"  -> GitDiffOptions,
    "show" -> GitDiffOptions,
    "diff" -> GitDiffOptions,
    // `-O` / `--open-files-in-pager` runs a pager program.
    "grep" -> Options(short = Set('O'), long = Set("--open-files-in-pager", "--textconv"), longAllowed = Set("--text")),
    "blame" -> Options(long = Set("--textconv"))
  )

  private val GitBranchShort: Set[Char] = Set('a', 'r', 'v', 'l')
  private val GitBranchLong: Set[String] = Set(
    "--all",
    "--remotes",
    "--verbose",
    "--list",
    "--show-current",
    "--color",
    "--no-color",
    "--column",
    "--no-column",
    "--sort",
    "--contains",
    "--no-contains",
    "--merged",
    "--no-merged",
    "--points-at",
    "--ignore-case",
    "--abbrev",
    "--no-abbrev",
    "--format"
  )

  /** `git branch` options that take the next argument as their value (a commit, a sort key or a format). */
  private val GitBranchValueLong: Set[String] =
    Set("--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--sort", "--format")

  // ---- environment

  private val AllowedEnvironment: Set[String] = Set("LANG", "LANGUAGE", "TZ", "TERM", "COLUMNS", "LINES", "NO_COLOR")

  /**
   * The first reason to refuse a command, if any.
   *
   * @param program     the executable as matched against the allowlist (lower-cased on Windows)
   * @param args        the arguments after the executable
   * @param isWindows   whether the runner is on Windows (cmd.exe `/X` switches, case-insensitive variables)
   * @param workDir     the real path of the working directory
   * @param realRoot    the real path of the workspace root
   * @param environment the variables the caller asked to set
   * @param spelledWorkDir the working directory as the process is given it (absolute, links not resolved), from
   *                    which the lexical reading applies `..` as text; `workDir` when not given
   */
  def refusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Path,
    realRoot: Path,
    environment: Map[String, String],
    spelledWorkDir: Option[Path] = None
  ): Option[Refusal] =
    // Fails closed: a file-system call that throws (a path the platform cannot resolve) refuses the command
    // rather than escaping `executeCommand` as a raw exception.
    Try {
      environmentRefusal(environment, isWindows)
        .orElse(nulRefusal(program, args))
        .orElse(if (isWindows) quoteRefusal(program, args) else None)
        .orElse(optionRefusal(program, args, isWindows))
        .orElse(pathRefusal(program, args, isWindows, Bases(workDir, spelledWorkDir.getOrElse(workDir)), realRoot))
    }.fold(
      e =>
        Some(
          Refusal(
            ArgumentNotAllowed,
            s"The arguments of '$program' could not be checked against the workspace " +
              s"(${e.getClass.getSimpleName}: ${e.getMessage})."
          )
        ),
      identity
    )

  /** A NUL character ends a C string, so the program would open a different name from the one checked. */
  private def nulRefusal(program: String, args: Seq[String]): Option[Refusal] =
    args
      .find(_.contains('\u0000'))
      .map(arg => notAllowed(program, arg.replace("\u0000", "\\0"), "it contains a NUL character."))

  /**
   * On Windows the C runtime's argv parser and cmd.exe delete `"` as a quote toggle, so the program opens a name
   * other than the one checked: `"..\x` opens `..\x`, and a leading `"` makes `"C:\x` look relative. A Windows file
   * name cannot hold `"`, and the tokenizer has already removed the command's own quoting, so any argument still
   * holding one is refused. The working directory and environment values never reach a command line (they go to
   * `CreateProcess` as the directory and the environment block, and only locale variables may be set).
   */
  private def quoteRefusal(program: String, args: Seq[String]): Option[Refusal] =
    args
      .find(_.contains('"'))
      .map(notAllowed(program, _, "on Windows the program removes '\"' as a quote and would open a different name."))

  private def environmentRefusal(environment: Map[String, String], isWindows: Boolean): Option[Refusal] =
    environment.keys.toSeq.sorted
      .find { name =>
        val key = if (isWindows) name.toUpperCase(java.util.Locale.ROOT) else name
        !(AllowedEnvironment.contains(key) || key.startsWith("LC_"))
      }
      .map { name =>
        Refusal(
          EnvironmentNotAllowed,
          s"Environment variable '$name' may not be set for a command: it can make an allowed program run " +
            s"another one or read another configuration. Allowed: ${AllowedEnvironment.toSeq.sorted.mkString(", ")}, LC_*."
        )
      }

  // ---- options

  private def optionRefusal(program: String, args: Seq[String], isWindows: Boolean): Option[Refusal] =
    program match {
      case "git"               => gitRefusal(args)
      case "uniq"              => uniqRefusal(args)
      case "sort" if isWindows => windowsSortRefusal(args).orElse(refusedOption(program, args))
      case "hostname"          => hostnameRefusal(args).orElse(refusedOption(program, args))
      case "find"              => findFollowRefusal(args).orElse(refusedOption(program, args))
      case _                   => refusedOption(program, args)
    }

  private def notAllowed(program: String, arg: String, why: String): Refusal =
    Refusal(ArgumentNotAllowed, s"Argument '$arg' is not allowed for '$program': $why")

  private val WritesOrRuns =
    "it can write or delete files, run another program, follow links out of the workspace, or read files the " +
      "command does not name."

  private def refusedOption(program: String, args: Seq[String]): Option[Refusal] =
    ProgramOptions
      .get(program)
      .flatMap(options => firstRefused(options, args))
      .map(notAllowed(program, _, WritesOrRuns))

  /** The first argument that selects one of `options`, scanning every argument (`--` included). */
  private def firstRefused(options: Options, args: Seq[String]): Option[String] =
    args.find(arg => selects(options, arg))

  private def selects(options: Options, arg: String): Boolean =
    options.exact.contains(arg) || {
      if (arg.startsWith("--") && arg.length > 2) {
        val name = arg.takeWhile(_ != '=')
        !options.longAllowed.contains(name) && options.long.exists(denied => denied.startsWith(name))
      } else if (arg.startsWith("-") && !arg.startsWith("--") && arg.length > 1)
        arg.drop(1).exists(options.short.contains)
      else false
    }

  private def findFollowRefusal(args: Seq[String]): Option[Refusal] =
    args.find(arg => FindFollowCluster.matches(arg)).map(notAllowed("find", _, "it follows every link it meets."))

  private def windowsSortRefusal(args: Seq[String]): Option[Refusal] =
    args
      .find(arg => arg.length > 1 && arg.startsWith("/") && arg.charAt(1).toLower == 'o')
      .map(notAllowed("sort", _, "it writes the output to a file."))

  private def hostnameRefusal(args: Seq[String]): Option[Refusal] =
    args.find(arg => !arg.startsWith("-")).map(notAllowed("hostname", _, "with an operand it sets the host name."))

  /**
   * `uniq` writes its second operand, so it may have at most one. `-f`, `-s` and `-w` (and their long forms) take
   * a value, which is not an operand; after `--`, for `-` alone, and after the first operand (BSD uniq does not
   * permute, so `uniq a.txt -s` writes a file named `-s`), every argument is one.
   */
  private def uniqRefusal(args: Seq[String]): Option[Refusal] = {
    val valueShort = Set('f', 's', 'w')
    val valueLong  = Seq("--skip-fields", "--skip-chars", "--check-chars")

    @tailrec
    def operands(rest: List[String], afterDashes: Boolean, found: List[String]): List[String] =
      rest match {
        case Nil                        => found.reverse
        case arg :: tail if afterDashes => operands(tail, afterDashes, arg :: found)
        // BSD uniq does not permute: once it has an operand, every later argument is one (`uniq a.txt -s`).
        case arg :: tail if found.nonEmpty => operands(tail, afterDashes = true, arg :: found)
        case "--" :: tail                  => operands(tail, afterDashes = true, found)
        case "-" :: tail                   => operands(tail, afterDashes, "-" :: found)
        case arg :: tail if arg.startsWith("--") =>
          val takesNext = !arg.contains('=') && valueLong.exists(_.startsWith(arg))
          operands(if (takesNext) tail.drop(1) else tail, afterDashes, found)
        case arg :: tail if arg.startsWith("-") =>
          val cluster   = arg.drop(1)
          val valueAt   = cluster.indexWhere(valueShort.contains)
          val takesNext = valueAt >= 0 && valueAt == cluster.length - 1
          operands(if (takesNext) tail.drop(1) else tail, afterDashes, found)
        case arg :: tail => operands(tail, afterDashes, arg :: found)
      }

    operands(args.toList, afterDashes = false, Nil)
      .drop(1)
      .headOption
      .map(notAllowed("uniq", _, "uniq writes its second operand; give it at most one file."))
  }

  private def gitRefusal(args: Seq[String]): Option[Refusal] = {
    val (globals, rest) = args.span(_.startsWith("-"))
    globals.find(g => !GitGlobalAllowed.contains(g)) match {
      case Some(global) =>
        Some(
          notAllowed(
            "git",
            global,
            "git global options can run programs or point git at another repository; allowed: " +
              GitGlobalAllowed.toSeq.sorted.mkString(", ") + "."
          )
        )
      case None if globals.contains("--version") || rest.isEmpty => None
      case None =>
        val subcommand = rest.head
        val subArgs    = rest.tail
        if (!GitReadSubcommands.contains(subcommand))
          Some(
            notAllowed(
              "git",
              subcommand,
              "only these subcommands, which read the repository, are allowed: " +
                GitReadSubcommands.toSeq.sorted.mkString(", ") + "."
            )
          )
        else if (subcommand == "branch") gitBranchRefusal(subArgs)
        else
          GitSubcommandOptions
            .get(subcommand)
            .flatMap(options => firstRefused(options, subArgs))
            .map(notAllowed(s"git $subcommand", _, "it writes a file or runs another program."))
    }
  }

  /**
   * `git branch` creates, renames, copies or deletes a branch unless it is listing, so only listing options are
   * allowed, and a name only when `--list` / `-l` makes it a pattern.
   */
  private def gitBranchRefusal(args: Seq[String]): Option[Refusal] = {
    val listing = args.exists(a => a == "--list" || (a.startsWith("-") && !a.startsWith("--") && a.contains('l')))

    /** The first argument that is neither a listing option, an option's value, nor a `--list` pattern. */
    @tailrec
    def firstRefused(rest: List[String]): Option[String] =
      rest match {
        case Nil          => None
        case "--" :: tail => firstRefused(tail)
        case arg :: tail if arg.startsWith("--") =>
          val name = arg.takeWhile(_ != '=')
          if (!GitBranchLong.contains(name)) Some(arg)
          else if (!arg.contains('=') && GitBranchValueLong.contains(name)) firstRefused(tail.drop(1))
          else firstRefused(tail)
        case arg :: tail if arg.startsWith("-") && arg.length > 1 =>
          if (arg.drop(1).forall(GitBranchShort.contains)) firstRefused(tail) else Some(arg)
        case arg :: tail => if (listing) firstRefused(tail) else Some(arg)
      }

    firstRefused(args.toList)
      .map(
        notAllowed(
          "git branch",
          _,
          "git branch may only list branches (options -a, -r, -v, -l / --list and the listing filters); a branch name " +
            "is allowed only as a --list pattern."
        )
      )
  }

  // ---- paths

  /** The longest argument the path rule resolves; a longer one is refused rather than walked (`PATH_MAX`). */
  val MaxArgumentLength = 4096

  /**
   * The file-system lookups (path components, link hops, directory entries) the path rule may spend on one command.
   * An argument costs about one per component, so ordinary commands use a few hundred; the cap stops a crafted
   * argument (an option whose every tail is a long path) from holding the request thread for seconds.
   */
  val MaxPathSteps = 20000

  /** What is left of [[MaxPathSteps]] for one command. */
  final private class Budget(private var remaining: Int) {
    def spend(): Boolean = { remaining -= 1; remaining >= 0 }
    def left: Int        = math.max(remaining, 0)
  }

  /** Why a path is refused. */
  sealed private trait Verdict
  private case object Outside   extends Verdict // leads outside the workspace, or through a link that cannot be read
  private case object TooCostly extends Verdict // could not be checked within the budget

  private def escape(program: String, arg: String, candidate: String): Refusal =
    Refusal(
      PathEscapeAttempt,
      s"Argument '$arg' of '$program' names a location outside the workspace" +
        (if (candidate != arg) s" ('$candidate')" else "") +
        ". Paths are resolved from the working directory, following symbolic links, and must stay inside " +
        "the workspace root."
    )

  private def tooCostly(program: String): Refusal =
    Refusal(
      ArgumentNotAllowed,
      s"The arguments of '$program' are too long or deep to check against the workspace " +
        s"(at most $MaxArgumentLength characters an argument and $MaxPathSteps path lookups a command)."
    )

  /**
   * Where a relative path starts: `physical`, the real working directory, for the kernel's reading, and `lexical`,
   * the working directory as the process is given it, for the reading that removes `..` as text (Windows).
   */
  final private case class Bases(physical: Path, lexical: Path)

  private def pathRefusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Bases,
    realRoot: Path
  ): Option[Refusal] =
    if (PrintOnly.contains(program)) None
    else if (args.exists(_.length > MaxArgumentLength)) Some(tooCostly(program))
    else {
      val budget   = new Budget(MaxPathSteps)
      val options  = ProgramOptions.getOrElse(program, Options())
      val switches = isWindows && WindowsSwitchPrograms.contains(program)
      candidates(args, options, switches)
        .map { case (arg, candidate) =>
          val judged =
            verdict(workDir, candidate, realRoot, budget)
              .orElse(unparseableVerdict(workDir, candidate, isWindows, realRoot, budget))
          (arg, candidate, judged)
        }
        .collectFirst {
          case (arg, candidate, Some(Outside)) => escape(program, arg, candidate)
          case (_, _, Some(TooCostly))         => tooCostly(program)
        }
        .orElse(if (program == "cp") cpDestinationRefusal(args, workDir, realRoot, budget) else None)
    }

  /**
   * The strings in `args` that the program might open as a path, each with the argument it came from.
   *
   *  - A plain argument is itself a candidate (on Windows, a `/X` switch of [[WindowsSwitchPrograms]] contributes
   *    the tails after its slash instead, so the value of `/G:file` is checked).
   *  - A long option `--name=value` contributes itself and its value - not the tails of its name, so text such as
   *    `--since=2024/01/01` or `--grep=feat/x` is not read as the absolute path `/01/01`. A value given as the
   *    next argument is a plain argument.
   *  - A short option contributes every tail after its dash, which covers an attached value at any position
   *    (`-f/x`, `-rf/x`).
   *  - A program's text options ([[Options.textShort]], [[Options.textLong]]) contribute no value: `sort -t/` and
   *    `sort -t /` take a separator, not a path. In a cluster, the tails up to and including the text option are
   *    still checked, so a value-taking option before it (`-Tt`) is still held to the workspace; the next argument
   *    is exempt only after a bare `-t`.
   *  - After a bare `--`, every argument contributes itself and all its tails, because an option may have consumed
   *    the `--` as its value, and an operand that looks like an option is opened as a file.
   */
  private def candidates(args: Seq[String], options: Options, switches: Boolean): Iterator[(String, String)] = {
    def tails(arg: String): Iterator[String] = Iterator.range(1, arg.length).map(arg.substring)
    // In `/G:file` the `G:` names the switch, so the first tail `G:file` is not a path on drive G: (`file`, the
    // next-but-one tail, is checked; so is `D:file` in `/G:D:file`).
    def plain(arg: String): Iterator[String] =
      if (switches && arg.length > 1 && arg.startsWith("/"))
        tails(arg).filterNot(tail => tail.length == arg.length - 1 && SwitchWithValue.matches(tail))
      else Iterator.single(arg)
    def textLong(name: String): Boolean = name.length > 3 && options.textLong.exists(_.startsWith(name))

    @tailrec
    def loop(
      rest: List[String],
      afterDashes: Boolean,
      skipNext: Boolean,
      found: List[(String, () => Iterator[String])]
    ): List[(String, () => Iterator[String])] =
      rest match {
        case Nil                   => found.reverse
        case _ :: tail if skipNext => loop(tail, afterDashes, skipNext = false, found)
        case "--" :: tail          => loop(tail, afterDashes = true, skipNext = false, found)
        case arg :: tail if afterDashes =>
          val all = () => plain(arg) ++ (if (arg.startsWith("-")) tails(arg) else Iterator.empty)
          loop(tail, afterDashes, skipNext = false, (arg, all) :: found)
        case arg :: tail if arg.startsWith("--") && arg.length > 2 =>
          val name     = arg.takeWhile(_ != '=')
          val hasValue = arg.length > name.length
          val text     = textLong(name)
          val value    = if (hasValue && !text) Iterator.single(arg.drop(name.length + 1)) else Iterator.empty
          val all      = () => Iterator.single(arg) ++ value.filter(_.nonEmpty)
          loop(tail, afterDashes, skipNext = text && !hasValue, (arg, all) :: found)
        case arg :: tail if arg.length > 1 && arg.startsWith("-") =>
          val cluster = arg.drop(1)
          val textAt  = cluster.indexWhere(options.textShort.contains)
          val all     = () => if (textAt < 0) tails(arg) else Iterator.range(1, textAt + 2).map(arg.substring)
          loop(tail, afterDashes, skipNext = cluster.length == 1 && textAt == 0, (arg, all) :: found)
        case arg :: tail => loop(tail, afterDashes, skipNext = false, (arg, () => plain(arg)) :: found)
      }

    loop(args.toList, afterDashes = false, skipNext = false, Nil).iterator.flatMap { case (arg, all) =>
      all().map(arg -> _)
    }
  }

  /**
   * `None` when `arg` stays inside `realRoot` under both readings: physical, resolved from `workDir.physical` as the
   * kernel would, following each link where it is met; and lexical, its `.` and `..` removed as text from
   * `workDir.lexical` first and the links of the result then resolved, as Win32 does. The two differ only for a `..`
   * after a link (`l/../..` with `l` -> a deeper directory), and such a path is refused unless both are inside.
   */
  private def verdict(workDir: Bases, arg: String, realRoot: Path, budget: Budget): Option[Verdict] =
    judge(physicalPath(workDir.physical, arg, budget), realRoot, budget)
      .orElse(judge(lexicalPath(workDir.lexical, arg), realRoot, budget))

  private def judge(reading: Option[Either[Verdict, Path]], realRoot: Path, budget: Budget): Option[Verdict] =
    reading match {
      case None                  => None // not a path on this platform (e.g. a ':' on Windows): nothing can be opened
      case Some(Left(refused))   => Some(refused)
      case Some(Right(resolved)) => outsideOf(resolved, realRoot, budget)
    }

  /**
   * An argument a Windows program is given whole although the platform cannot parse it as a path (a wildcard, a `"`
   * or a `:` past the drive letter in it). [[parse]] judges the part before the first such character, but Win32
   * removes `.` and `..` as text before it opens a name or matches a wildcard, so `x*\..\..\outside` opens
   * `..\outside` while its prefix `x` is inside. Such an argument is refused when it has a `..` component after that
   * character, or when, with each such character replaced by `_`, it leads outside. Applies on Windows, and anywhere
   * the platform rejects the argument.
   */
  private def unparseableVerdict(
    workDir: Bases,
    arg: String,
    isWindows: Boolean,
    realRoot: Path,
    budget: Budget
  ): Option[Verdict] = {
    val cut = arg.indices.find(i => notInPath(arg.charAt(i), i))
    if (!((isWindows && cut.nonEmpty) || Try(Paths.get(arg)).isFailure)) None
    else {
      val after = arg.substring(cut.getOrElse(0))
      if (after.split(Array('/', '\\')).exists(parentComponent)) Some(Outside)
      else {
        val replaced  = arg.zipWithIndex.map { case (c, i) => if (notInPath(c, i)) '_' else c }.mkString
        val sanitised = if (isWindows) replaced.replace('\\', '/') else replaced
        verdict(workDir, sanitised, realRoot, budget)
      }
    }
  }

  /** `..`, or a component Win32 may trim to it (trailing dots and spaces: `.. `, `...`). */
  private def parentComponent(name: String): Boolean =
    name.forall(c => c == '.' || c == ' ') && name.count(_ == '.') >= 2

  /** `None` when the walked path `resolved`, canonicalised, lies inside `realRoot`. */
  private def outsideOf(resolved: Path, realRoot: Path, budget: Budget): Option[Verdict] =
    canonical(resolved, budget) match {
      case None                                    => Some(TooCostly)
      case Some(real) if real.startsWith(realRoot) => None
      case Some(_)                                 => Some(Outside)
    }

  /**
   * Resolves `arg` from `base` (a real path) one component at a time, following each symbolic link where it is met,
   * as the kernel does. A component that does not exist is taken as a directory that a command such as `mkdir -p`
   * would create, so a later `..` climbs back and a link met after it is still followed. `None` when `arg` is not a
   * path here; `Left(Outside)` when a link cannot be resolved, `Left(TooCostly)` when the budget runs out.
   */
  private def physicalPath(base: Path, arg: String, budget: Budget): Option[Either[Verdict, Path]] =
    parse(arg) match {
      case Some(path) =>
        Some(split(base, path).flatMap { case (start, names) => walk(start, names, hops = 0, missing = false, budget) })
      // Starts like an absolute path, but the platform cannot parse even its leading part: a Windows device or
      // NT-namespace name (`\\?\C:\x`, `\??\C:\x`) that a program would still open.
      case None if arg.startsWith("/") || arg.startsWith("\\") => Some(Left(Outside))
      case None                                                => None
    }

  /** [[physicalPath]]'s lexical counterpart: `.` and `..` removed as text from `base`, no link followed. */
  private def lexicalPath(base: Path, arg: String): Option[Either[Verdict, Path]] =
    parse(arg) match {
      case Some(path) =>
        Some(split(base, path).map { case (start, names) =>
          names.foldLeft(start) {
            case (current, "" | ".") => current
            case (current, "..")     => Option(current.getParent).getOrElse(current)
            case (current, name)     => current.resolve(name)
          }
        })
      case None if arg.startsWith("/") || arg.startsWith("\\") => Some(Left(Outside))
      case None                                                => None
    }

  /**
   * Characters a Windows path cannot hold (a `:` past the drive letter, wildcards, redirection characters). A
   * Windows program may still open the part before one: the file behind `HEAD:src/x` or an alternate data stream
   * `C:\x\f:s`, or the directory a wildcard such as `..\*` lists.
   */
  private def notInPath(c: Char, at: Int): Boolean =
    (c == ':' && at != 1) || c == '*' || c == '?' || c == '<' || c == '>' || c == '|' || c == '"' || c == '\u0000'

  /**
   * `arg` as a path. Where the platform rejects it, the part before the first character a path cannot hold is what
   * a program could open; `None` when there is no such part.
   */
  private def parse(arg: String): Option[Path] =
    Try(Paths.get(arg)).toOption.orElse {
      val cut = arg.indices.find(i => notInPath(arg.charAt(i), i)).getOrElse(arg.length)
      if (cut == 0 || cut == arg.length) None else Try(Paths.get(arg.substring(0, cut))).toOption
    }

  /**
   * The walk's start and the names to walk. A relative path starts at `base`. A rooted one is made absolute against
   * `base`, as the program, whose working directory `base` is, would: on Windows a root-relative `\x` takes `base`'s
   * drive and a drive-relative `C:x` on `base`'s drive starts at `base`. A drive-relative path on another drive
   * starts at that drive's own working directory, which the runner does not know (and `toAbsolutePath` throws
   * `IOError` for a drive that does not exist), so it is refused.
   */
  private def split(base: Path, path: Path): Either[Verdict, (Path, List[String])] =
    if (path.getRoot == null) Right((base, path.iterator.asScala.map(_.toString).toList))
    else
      Try(base.resolve(path)).toOption.filter(_.isAbsolute) match {
        case Some(absolute) => Right((absolute.getRoot, absolute.iterator.asScala.map(_.toString).toList))
        case None           => Left(Outside)
      }

  private val MaxLinkHops = 40

  /**
   * `missing` is true once the walk is below a component that does not exist: nothing below it can be a link, so no
   * file-system call is made until a `..` climbs back.
   */
  @tailrec
  private def walk(
    current: Path,
    names: List[String],
    hops: Int,
    missing: Boolean,
    budget: Budget
  ): Either[Verdict, Path] =
    names match {
      case Nil                  => Right(current)
      case _ if !budget.spend() => Left(TooCostly)
      case ("" | ".") :: rest   => walk(current, rest, hops, missing, budget)
      case ".." :: rest =>
        walk(Option(current.getParent).getOrElse(current), rest, hops, missing = false, budget)
      case name :: rest if missing => walk(current.resolve(name), rest, hops, missing, budget)
      case name :: rest =>
        val next = current.resolve(name)
        if (Files.isSymbolicLink(next)) {
          if (hops >= MaxLinkHops) Left(Outside)
          else
            Try(Files.readSymbolicLink(next)).toOption match {
              case None => Left(Outside)
              case Some(target) =>
                split(current, target) match {
                  case Left(refused) => Left(refused)
                  case Right((start, targetNames)) =>
                    walk(start, targetNames ++ rest, hops + 1, missing = false, budget)
                }
            }
        } else walk(next, rest, hops, missing = !Files.exists(next, LinkOption.NOFOLLOW_LINKS), budget)
    }

  /**
   * `path` with its deepest existing ancestor replaced by that ancestor's real path. The walk has already followed
   * symbolic links; this also settles what Java does not report as a link (a Windows junction), letter case on a
   * case-insensitive file system and Windows short names, so the comparison with the real root is like for like.
   */
  private def canonical(path: Path, budget: Budget): Option[Path] = {
    val normalized = path.normalize()
    val ancestors  = Iterator.iterate(normalized)(_.getParent).takeWhile(_ != null)
    ancestors
      .map(p => if (budget.spend()) Some(p) else None)
      .find(p => p.forall(Files.exists(_)))
      .getOrElse(Some(normalized))
      .map(ancestor => Try(ancestor.toRealPath().resolve(ancestor.relativize(normalized))).getOrElse(normalized))
  }

  // ---- cp: writing through a link at the destination

  /** What `cp`'s options say about how it copies, and its operands (a `-t` / `--target-directory` value included). */
  final private case class CpCommand(operands: List[String], recursive: Boolean, keepsLinks: Boolean, parents: Boolean)

  private def cpCommand(args: Seq[String]): CpCommand = {
    // Any abbreviation of at least one letter: `--t=dst` is `--target-directory=dst`.
    def long(name: String, full: String): Boolean = name.length > 2 && full.startsWith(name)

    @tailrec
    def loop(rest: List[String], afterDashes: Boolean, command: CpCommand): CpCommand =
      rest match {
        case Nil                        => command.copy(operands = command.operands.reverse)
        case arg :: tail if afterDashes => loop(tail, afterDashes, command.copy(operands = arg :: command.operands))
        case "--" :: tail               => loop(tail, afterDashes = true, command)
        case arg :: tail if arg.startsWith("--") && arg.length > 2 =>
          val name = arg.takeWhile(_ != '=')
          val target =
            if (long(name, "--target-directory") && arg.length > name.length) List(arg.drop(name.length + 1)) else Nil
          val recursive = long(name, "--recursive") || long(name, "--archive")
          loop(
            tail,
            afterDashes,
            command.copy(
              operands = target ++ command.operands,
              recursive = command.recursive || recursive,
              keepsLinks = command.keepsLinks || recursive || long(name, "--no-dereference"),
              parents = command.parents || long(name, "--parents")
            )
          )
        case arg :: tail if arg.length > 1 && arg.startsWith("-") =>
          val cluster   = arg.drop(1)
          val targetAt  = cluster.indexOf('t')
          val target    = if (targetAt >= 0 && targetAt < cluster.length - 1) List(cluster.drop(targetAt + 1)) else Nil
          val recursive = cluster.exists(c => c == 'R' || c == 'r' || c == 'a')
          loop(
            tail,
            afterDashes,
            command.copy(
              operands = target ++ command.operands,
              recursive = command.recursive || recursive,
              keepsLinks = command.keepsLinks || recursive || cluster.exists(c => c == 'P' || c == 'd')
            )
          )
        case arg :: tail => loop(tail, afterDashes, command.copy(operands = arg :: command.operands))
      }

    loop(args.toList, afterDashes = false, CpCommand(Nil, recursive = false, keepsLinks = false, parents = false))
  }

  private def lastName(arg: String): Option[String] =
    parse(arg).flatMap(p => Option(p.getFileName)).map(_.toString).filterNot(n => n == "." || n == "..")

  /** `src/`, `src/.`: BSD `cp -R` copies the directory's contents into the target rather than the directory. */
  private def copiesContents(arg: String): Boolean =
    arg.endsWith("/") || arg.endsWith("\\") || lastName(arg).isEmpty

  /**
   * `cp` opens an existing destination file and writes through it, so a symbolic link already at the name it
   * writes - `dst/secret.txt` -> a file outside - sends the copy outside although every argument lies inside
   * (`cp src/secret.txt dst/`, `cp -R src/. dst`). The arguments themselves have been checked; this checks the
   * names `cp` will write:
   *
   *  - Any operand may be the target (an option's separate value is counted as an operand, which only adds
   *    checks), so for every pair of operands the name the source takes under the target is resolved, links
   *    followed, and must stay inside the workspace. A source naming a directory's contents (`src/`, `src/.`) adds
   *    the target itself; `--parents` adds the target joined with the whole source path.
   *  - A recursive copy also writes below those names, so each that is an existing directory is searched, without
   *    following links, for a link that leads outside.
   *  - A copy that keeps links (`-R`, `-a`, `-P`, `-d`) of several sources could copy a link from one source and
   *    then write a file of the same name from another through it, so such a copy may not have two sources with
   *    the same name, nor several sources and one that names a directory's contents.
   *
   * These checks run before `cp` starts; a link made at a destination name while it runs (by another command) is
   * not seen.
   */
  private def cpDestinationRefusal(
    args: Seq[String],
    workDir: Bases,
    realRoot: Path,
    budget: Budget
  ): Option[Refusal] = {
    val command  = cpCommand(args)
    val operands = command.operands.toVector

    val names = operands.flatMap(lastName)
    val clash =
      command.keepsLinks && operands.size >= 3 && (names.distinct.size < names.size || operands.exists(copiesContents))

    lazy val destinations: Vector[String] =
      (for {
        t <- operands.indices
        s <- operands.indices if s != t
        target = operands(t)
        source = operands(s)
        destination <-
          lastName(source).map(n => s"$target/$n").toList ++
            (if (copiesContents(source)) List(target) else Nil) ++
            (if (command.parents) List(s"$target/$source") else Nil)
      } yield destination).distinct.toVector

    if (clash)
      Some(
        Refusal(
          ArgumentNotAllowed,
          "A cp that copies links (-R, -a, -P, -d) may not have two sources with the same name, or several sources " +
            "and one that names a directory's contents: one source's link and another's file would land at one name, " +
            "and cp would write the file through the link. Copy them one at a time."
        )
      )
    else
      destinations.iterator
        .map(d => d -> physicalPath(workDir.physical, d, budget))
        .flatMap {
          case (_, None)                  => None
          case (_, Some(Left(TooCostly))) => Some(tooCostly("cp"))
          case (d, Some(Left(Outside)))   => Some(destinationEscape(d, None))
          case (d, Some(Right(resolved))) =>
            val outside =
              outsideOf(resolved, realRoot, budget).orElse(judge(lexicalPath(workDir.lexical, d), realRoot, budget))
            if (outside.contains(TooCostly)) Some(tooCostly("cp"))
            else if (outside.nonEmpty) Some(destinationEscape(d, None))
            else if (command.recursive && Files.isDirectory(resolved))
              outboundLinkUnder(resolved, realRoot, budget) match {
                case Left(_)          => Some(tooCostly("cp"))
                case Right(Some(out)) => Some(destinationEscape(d, Some(out)))
                case Right(None)      => None
              }
            else None
        }
        .nextOption()
  }

  private def destinationEscape(destination: String, link: Option[Path]): Refusal =
    Refusal(
      PathEscapeAttempt,
      link match {
        case None =>
          s"'cp' would write '$destination', which leads outside the workspace through a symbolic link; cp writes " +
            "through a link it finds at the name it writes."
        case Some(l) =>
          s"'cp' would copy into '$destination', which holds the symbolic link '$l' leading outside the workspace; " +
            "cp writes through a link it finds at the name it writes."
      }
    )

  /**
   * The first symbolic link below `dir` that leads outside `realRoot`, searching without following links. `Left`
   * when the search runs out of budget or meets a directory it cannot list (where `cp` could still write).
   */
  private def outboundLinkUnder(dir: Path, realRoot: Path, budget: Budget): Either[Verdict, Option[Path]] = {
    @tailrec
    def loop(pending: List[Path]): Either[Verdict, Option[Path]] =
      pending match {
        case Nil => Right(None)
        case current :: rest =>
          val listed = Try(Using.resource(Files.newDirectoryStream(current)) { stream =>
            stream.iterator().asScala.take(budget.left + 1).toList
          }).toOption
          listed match {
            case None                                                  => Left(Outside)
            case Some(entries) if !entries.forall(_ => budget.spend()) => Left(TooCostly)
            case Some(entries) =>
              val outbound = entries.find { entry =>
                Files.isSymbolicLink(entry) &&
                Try(Files.readSymbolicLink(entry)).toOption.forall { target =>
                  val dir = entry.getParent
                  verdict(Bases(dir, dir), target.toString, realRoot, budget).nonEmpty
                }
              }
              outbound match {
                case Some(link) => Right(Some(link))
                case None =>
                  loop(entries.filter(e => Files.isDirectory(e, LinkOption.NOFOLLOW_LINKS)) ++ rest)
              }
          }
      }

    loop(List(dir))
  }
}
