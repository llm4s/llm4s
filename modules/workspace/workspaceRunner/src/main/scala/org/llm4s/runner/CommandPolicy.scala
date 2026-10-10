package org.llm4s.runner

import java.io.File
import java.text.Normalizer
import java.util.Locale
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
 *     bar the separator `sort -t` really takes (parsed as sort parses its options, see [[sortCandidates]]) -
 *     is resolved the way the kernel would resolve it from the real working directory: component by component,
 *     following each symbolic link where it is met, so `link/..` goes to the parent of the link's target, not back
 *     to the directory holding the link. The result must lie inside the real workspace root, and so must the lexical
 *     reading Win32 uses - `.` and `..` removed as text first, then the links of the result resolved - so with
 *     `l` -> `a/b`, `l/../../x` (`a/x` physically, `../x` textually) is refused on every platform. An argument that
 *     does not name an existing file is judged the same way, so `../x` and `/tmp/x` are refused even when they do not
 *     exist yet. On POSIX, an operand of `rm` or `unlink`, or a source of `mv`, whose last component is itself a
 *     symbolic link is judged by the directory holding it instead, so an agent can remove or rename a link that points
 *     out of the workspace; the destination of `mv` is not, and neither is an operand with a trailing `/` (see
 *     [[linkOperands]] and [[namesLinkItself]]). Programs that only
 *     print their arguments (`echo`, `pwd`, `whoami`, `hostname`) are not checked. For `cp`, which writes through
 *     a link it finds at the name it writes, the names it will write are checked too (see [[cpDestinationRefusal]]).
 *     An `mv` or `cp` of several sources, which runs one operation after another while every path is checked before
 *     the first, is refused (`ARGUMENT_NOT_ALLOWED`) when one path goes through a name another source's operation
 *     creates, replaces or removes, or when `mv` moves the working directory (see [[sequenceRefusal]], #1776).
 *     An argument over [[MaxArgumentLength]] characters, or paths costing more than [[MaxPathSteps]] lookups, are
 *     refused with `ARGUMENT_NOT_ALLOWED` rather than walked. Where the platform cannot parse an argument as a path
 *     (Windows: `HEAD:src/x`, `..\*`), the part before the first character a path cannot hold is judged; one that
 *     starts with a separator but has no such part (`\\?\C:\x`) is refused, as is, on Windows, a drive-relative
 *     path on another drive (`D:x`). Because Win32 removes `..` as text before it opens a name or matches a wildcard,
 *     such an argument is also refused when it has a `..` component after that character (`x*\..\..\f`), or when,
 *     with each such character replaced by `_`, it leads outside. An argument holding a NUL character, or on Windows a
 *     `"` (which the argv parser and cmd.exe delete, so `"..\x` opens `..\x`), or, for a built-in run through
 *     `cmd.exe /c`, a character cmd.exe splits the argument at (`a.txt,..\x`, see [[cmdSyntaxRefusal]]), is refused
 *     (`ARGUMENT_NOT_ALLOWED`), and
 *     a check that fails with an exception refuses the command rather than throwing it. A wildcard in the last
 *     component (`.*`, `.?`) can match the `..` entry, but `dir` only lists that entry and `type` / `findstr` cannot
 *     read a directory, so it is not refused; a wildcard is not allowed in an earlier component.
 *
 * The path rule cannot tell a path from text that looks like one: a `grep` pattern or an option value that starts
 * with `/` or has a `..` component is refused too (write `[/]api` for `/api`). A relative value without `..` can only
 * leave the workspace through a link, so `--since=2024/01/01` and `--grep=feat/x` run.
 *
 * '''Windows.''' The policy refuses what it cannot reason about rather than modelling it (see [[windowsFormRefusal]],
 * [[findstrRefusal]], [[windowsSortRefusal]]): device names and components with a trailing `.` or space; for
 * programs that are not built-ins, a leading `@` or `~`, the characters `{ } [ ] ' ( )`, a leading `/`, and wildcards
 * anywhere but in a last component with a literal character; `findstr /F` and a `/D:` list; `sort` `/O`, `/T`,
 * `-o`, `-T`; and an argument to `sort` or `findstr` starting with `/` that is not exactly a switch of the native
 * tool (#1738). A path candidate of the form `/x/...` is also judged as drive `x:`'s path, as MSYS2 reads it, and
 * on a Windows host `sort` and `findstr` are refused when a build outside the system directory would run (see
 * [[shadowedToolRefusal]]). Over-blocking there is accepted.
 *
 * '''git.''' git searches upwards for its repository, so the runner confines it to the workspace
 * ([[confineGit]]), a `.git` that is not a directory inside the workspace is refused ([[gitRepositoryRefusal]]), and so
 * is an argument starting with `:` (pathspec magic, index paths), on every platform. git is refused outright when the
 * workspace root's parent path holds the path-list separator, which `GIT_CEILING_DIRECTORIES` cannot escape
 * ([[gitCeilingRefusal]]).
 *
 * '''Limits.''' The checks cover what a command is given, not what a program reads by itself. A recursive walk
 * (`ls -R`, `grep -r`, `find`, `diff -r`) is checked where it starts; `ls -L`, `grep -R`/`-S`, `find -L`,
 * `cp -L`/`-H` and `chmod -L`/`-H`, which follow links they meet, are refused, but `diff -r` follows links inside the
 * tree it walks. `git` reads the repository's own configuration and runs its hooks, so a repository whose
 * `.git/config` sets `core.fsmonitor`, `diff.external` or a filter or textconv driver, or whose `.git/hooks` has a
 * `post-index-change` hook, makes `git status` or `git diff` run that program; where the agent can write files (the
 * `writeFile` operation, or `cp`/`mv` in [[org.llm4s.shared.WorkspaceSandboxConfig.ReadWriteCommands]]) it can write
 * them (#1721), as it can a `.git/config` `core.worktree`, a `.git/commondir` or a `.git/objects/info/alternates` that
 * points git at files outside. The checks run before the program starts and do not see a link a concurrent command makes.
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
   * @param textLong    long options whose value after `=` is text, never opened as a path (`sort --field-separator=/`
   *                    on Windows; on POSIX `sort` is parsed by [[sortCandidates]] instead)
   */
  final private case class Options(
    short: Set[Char] = Set.empty,
    long: Set[String] = Set.empty,
    exact: Set[String] = Set.empty,
    longAllowed: Set[String] = Set.empty,
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
    // `-t` / `--field-separator` take a separator character, not a path (see `sortCandidates`).
    "sort" -> Options(
      short = Set('o'),
      long = Set("--output", "--compress-program", "--files0-from"),
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
   * @param spelledRoot the workspace root as configured (absolute, links not resolved); only its parent's spelling is
   *                    checked, for git (see [[gitCeilingRefusal]])
   */
  def refusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Path,
    realRoot: Path,
    environment: Map[String, String],
    spelledWorkDir: Option[Path] = None,
    spelledRoot: Option[Path] = None
  ): Option[Refusal] =
    // Fails closed: a file-system call that throws (a path the platform cannot resolve) refuses the command
    // rather than escaping `executeCommand` as a raw exception.
    Try {
      environmentRefusal(environment, isWindows)
        .orElse(nulRefusal(program, args))
        .orElse(if (isWindows) quoteRefusal(program, args) else None)
        .orElse(if (isWindows && WindowsBuiltins.contains(program)) cmdSyntaxRefusal(program, args) else None)
        .orElse(optionRefusal(program, args, isWindows))
        .orElse(
          if (program == "git")
            gitCeilingRefusal(realRoot +: spelledRoot.toSeq).orElse(gitRepositoryRefusal(workDir, realRoot))
          else None
        )
        .orElse(pathRefusal(program, args, isWindows, Bases(workDir, spelledWorkDir.getOrElse(workDir)), realRoot))
        .orElse(if (isWindows) windowsFormRefusal(program, args) else None)
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

  /**
   * cmd.exe built-ins that have no program of their own, so the runner starts them as `cmd.exe /c <builtin> args`.
   * Lower-case, as the executable is matched on Windows.
   */
  val WindowsBuiltins: Set[String] = Set(
    "echo",
    "dir",
    "type",
    "copy",
    "move",
    "del",
    "ren",
    "md",
    "rd",
    "set",
    "cls",
    "ver",
    "vol",
    "date",
    "time",
    "pause",
    "call"
  )

  /**
   * Characters cmd.exe treats as delimiters or syntax inside an argument that `ProcessBuilder` leaves unquoted: it
   * quotes an argument for `cmd.exe` only when it holds a space, a tab, `"`, `<` or `>`. cmd.exe splits a built-in's
   * arguments on `,`, `;`, `=`, VT, FF and 0xFF (NBSP in the OEM code pages) as well as on space and tab, so
   * `type a.txt,..\x` types `a.txt` and then `..\x`, while the path rule judged `a.txt,..\x` as one name inside the
   * workspace. A control character (a line feed ends the command line), any other Unicode space, `(` and `)`
   * (command blocks), `@` (echo suppression) and `!` (delayed expansion, where the registry enables it) are refused
   * too, rather than reasoning about each cmd.exe context.
   */
  private def isCmdSyntax(c: Char): Boolean =
    c == ',' || c == ';' || c == '=' || c == '(' || c == ')' || c == '@' || c == '!' || c == 'ÿ' ||
      Character.isISOControl(c) || (c != ' ' && Character.isSpaceChar(c))

  /** `echo` only prints its line, so its text may hold the pure delimiters and parentheses (`Hello, world`). */
  private val EchoText: Set[Char] = Set(',', '=', '(', ')')

  /**
   * A routed built-in's argument holding a character cmd.exe would split it at or parse (see [[isCmdSyntax]]): the
   * built-in would act on names other than the one the path rule checked (`type a.txt,..\outside\f`).
   */
  private def cmdSyntaxRefusal(program: String, args: Seq[String]): Option[Refusal] = {
    val refused: Char => Boolean =
      if (program == "echo") c => isCmdSyntax(c) && !EchoText.contains(c) else isCmdSyntax
    args.iterator
      .flatMap(arg => arg.find(refused).map(arg -> _))
      .nextOption()
      .map { case (arg, c) =>
        notAllowed(
          program,
          arg.map(ch => if (Character.isISOControl(ch)) '?' else ch),
          f"cmd.exe runs '$program' and splits or parses its arguments at U+${c.toInt}%04X, so the command would " +
            "act on names other than the one checked."
        )
      }
  }

  // ---- Windows: forms the policy cannot reason about are refused

  /**
   * Win32 device names. A path component naming one opens the device, not a file, whatever directory precedes it and
   * whatever extension follows (`sub\nul.txt`), so it bypasses the path rule.
   */
  private val WindowsDevices: Set[String] =
    Set("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ++
      (for {
        prefix <- Seq("COM", "LPT")
        digit  <- ('0' to '9') ++ Seq('¹', '²', '³')
      } yield s"$prefix$digit")

  /** `name` with the trailing dots and spaces Win32 strips removed. */
  private def win32Trimmed(name: String): String = {
    val end = name.lastIndexWhere(c => c != '.' && c != ' ')
    name.substring(0, end + 1)
  }

  private def isWindowsDevice(component: String): Boolean = {
    val base = win32Trimmed(win32Trimmed(component).takeWhile(_ != '.'))
    WindowsDevices.contains(base.toUpperCase(java.util.Locale.ROOT))
  }

  /** A component Win32 would open under another name, its trailing dots or spaces removed (`outside.` is `outside`). */
  private def hasTrailingDotOrSpace(component: String): Boolean =
    component.nonEmpty && component != "." && component != ".." && (component.endsWith(".") || component.endsWith(" "))

  /**
   * Characters a program that is not a cmd.exe built-in may expand, under the MSYS2 / Cygwin / Git-for-Windows
   * runtime that parses a Windows command line itself: braces and brackets (glob), `'` and parentheses (its quoting
   * and glob syntax). A leading `@` names a response file whose lines become arguments, and a leading `~` a home
   * directory.
   */
  private val RuntimeExpanded: Set[Char] = Set('{', '}', '[', ']', '\'', '(', ')')

  private def isWildcard(c: Char): Boolean = c == '*' || c == '?'

  /**
   * On Windows, an argument whose meaning depends on what the program or its runtime does with it, which the path
   * rule cannot follow (#1715). Applies to every program; the path checks still run afterwards.
   *
   *  - Every program but the print-only ones: a path component that is a device name ([[WindowsDevices]], any case,
   *    any extension, trailing dots and spaces ignored), or that ends in `.` or a space other than `.` and `..`
   *    (Win32 strips them, so `outside.` opens `outside`).
   *  - Programs that are not built-ins, which may run under a runtime that expands their command line (MSYS2,
   *    Cygwin, Git for Windows): a leading `@` (a response file) or `~` (a home directory); any of `{ } [ ] ' ( )`;
   *    a string the program might open as a path starting with `/` (the runtime's own root, not the workspace
   *    drive's), except for the `/X` switches of `findstr` and `sort` (which [[optionRefusal]] has already held to
   *    the native tool's switches, #1738); and a wildcard anywhere but in the last
   *    component, in an absolute string or one with a `..` component, or in a last component with no literal
   *    character but `.` (`*`, `.*`, `??`, `*.*` can match `..`, which `FindFirstFile` and a runtime's glob may
   *    return).
   */
  private def windowsFormRefusal(program: String, args: Seq[String]): Option[Refusal] = {
    val builtin                              = WindowsBuiltins.contains(program)
    val switches                             = WindowsSwitchPrograms.contains(program)
    val options                              = ProgramOptions.getOrElse(program, Options())
    def components(s: String): Array[String] = s.split(Array('/', '\\', ':'))

    val runtimeForm: Option[Refusal] =
      if (builtin) None
      else
        args.iterator
          .flatMap { arg =>
            if (arg.startsWith("@"))
              Some(
                notAllowed(program, arg, "on Windows a leading '@' can name a response file whose lines are arguments.")
              )
            else if (arg.startsWith("~"))
              Some(notAllowed(program, arg, "on Windows a leading '~' can be expanded to a home directory."))
            else
              arg
                .find(RuntimeExpanded.contains)
                .map(c =>
                  notAllowed(
                    program,
                    arg,
                    s"on Windows the program's runtime may expand '$c' (glob or quoting) into names other than the one checked."
                  )
                )
          }
          .nextOption()

    def candidateForm(candidate: String): Option[String] = {
      val parts = components(candidate)
      if (!PrintOnly.contains(program) && parts.exists(isWindowsDevice))
        Some("it names a Windows device (CON, PRN, AUX, NUL, COM0-9, LPT0-9, CONIN$, CONOUT$), not a file.")
      else if (!PrintOnly.contains(program) && parts.exists(hasTrailingDotOrSpace))
        Some(
          "Windows removes a trailing '.' or space from a name, so it would open a different name from the one checked."
        )
      else if (builtin) None
      else if (!switches && candidate.startsWith("/"))
        Some(
          "on Windows a program's runtime (MSYS2, Cygwin) may read a leading '/' from its own root, not the workspace drive."
        )
      else if (!candidate.exists(isWildcard)) None
      else {
        val names = candidate.split(Array('/', '\\'))
        val absolute =
          candidate.startsWith("/") || candidate.startsWith("\\") ||
            (candidate.length > 1 && candidate.charAt(1) == ':' && candidate.charAt(0).isLetter)
        val wildEarly = names.dropRight(1).exists(_.exists(isWildcard))
        val noLiteral = names.lastOption.forall(_.forall(c => isWildcard(c) || c == '.'))
        if (absolute || wildEarly || noLiteral || names.exists(parentComponent))
          Some(
            "on Windows a wildcard is allowed only in the last component of a relative path without '..', with a " +
              "literal character other than '.' (a wildcard can match '..')."
          )
        else None
      }
    }

    runtimeForm.orElse(
      candidates(args, options, switches)
        .flatMap { case (arg, candidate) =>
          candidateForm(candidate).map(notAllowed(program, arg, _))
        }
        .nextOption()
    )
  }

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
      case "git"                  => gitRefusal(args)
      case "uniq"                 => uniqRefusal(args)
      case "sort" if isWindows    => windowsSortRefusal(args).orElse(refusedOption(program, args))
      case "findstr" if isWindows => findstrRefusal(args)
      case "hostname"             => hostnameRefusal(args).orElse(refusedOption(program, args))
      case "find"                 => findFollowRefusal(args).orElse(refusedOption(program, args))
      case _                      => refusedOption(program, args)
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

  /**
   * Windows `sort.exe` writes its output with `/O[UTPUT] file` and its temporary files with `/T[EMPORARY] dir`;
   * a GNU `sort` earlier on the `PATH` does so with `-o` and `-T` (and `-t`, its field separator, is refused here
   * too rather than guessing which program runs). Any switch or cluster naming one is refused.
   */
  private def windowsSortRefusal(args: Seq[String]): Option[Refusal] =
    args
      .find { arg =>
        val writes = (c: Char) => c.toLower == 'o' || c.toLower == 't'
        (arg.length > 1 && arg.startsWith("/") && writes(arg.charAt(1))) ||
        (arg.length > 1 && arg.startsWith("-") && !arg.startsWith("--") && arg.drop(1).exists(writes)) ||
        (arg.length > 3 && arg.startsWith("--") && "--temporary-directory".startsWith(arg.takeWhile(_ != '=')))
      }
      .map(notAllowed("sort", _, "it writes the output or temporary files to a file or directory."))
      .orElse(
        args
          .find(arg => arg.startsWith("/") && !isNativeSortSwitch(arg))
          .map(notAllowed("sort", _, notANativeSwitch("sort.exe", NativeSortSwitches)))
      )

  /**
   * The switches of the native Windows `sort.exe`, matched whole and ignoring case (#1738). Documented in the Windows
   * Commands reference (https://learn.microsoft.com/windows-server/administration/windows-commands/sort) and in
   * `sort /?`: `/R[EVERSE]`, `/+n`, `/L[OCALE] locale`, `/M[EMORY] kilobytes`, `/REC[ORD_MAXIMUM] characters`,
   * `/T[EMPORARY] dir` and `/O[UTPUT] file` (both refused by [[windowsSortRefusal]]); and two that `sort.exe` accepts
   * but does not document, `/C[ASE_SENSITIVE]` and `/UNIQUE` (https://ss64.com/nt/sort.html). The value of `/L`, `/M`
   * and `/REC` is the next argument, which is judged as any other argument.
   */
  private val NativeSortSwitches: Seq[String] =
    "/R /REVERSE /+n /L /LOCALE /M /MEMORY /REC /RECORD_MAXIMUM /C /CASE_SENSITIVE /UNIQUE".split(' ').toSeq

  private val NativeSortSwitchNames: Set[String] = NativeSortSwitches.filter(_ != "/+n").map(_.drop(1)).toSet

  private def isNativeSortSwitch(arg: String): Boolean = {
    val name = arg.drop(1).toUpperCase(java.util.Locale.ROOT)
    NativeSortSwitchNames.contains(name) ||
    (name.length > 1 && name.charAt(0) == '+' && name.drop(1).forall(c => c >= '0' && c <= '9'))
  }

  /**
   * The native Windows `findstr.exe`'s switches (https://learn.microsoft.com/windows-server/administration/windows-commands/findstr):
   * the flags `/B /E /L /R /S /I /X /V /N /M /O /P`, which may be combined (`/SIN`), `/OFF[LINE]`, and the switches
   * that take a value after `:` - `/C:string`, `/G:file`, `/D:dir`, `/A:color` and `/F:file` (refused by
   * [[findstrRefusal]]) - which may follow flags (`/IC:x`).
   */
  private val FindstrFlags: Set[Char] = "BELRSIXVNMOP".toSet

  private val FindstrValueSwitches: Set[Char] = "CGDAF".toSet

  private def isNativeFindstrSwitch(arg: String): Boolean = {
    val body    = arg.drop(1).toUpperCase(java.util.Locale.ROOT)
    val letters = body.takeWhile(_ != ':')
    if (body == "OFF" || body == "OFFLINE") true
    else if (letters.isEmpty) false
    else if (letters.length == body.length) letters.forall(FindstrFlags.contains)
    else
      letters.init.forall(FindstrFlags.contains) && FindstrValueSwitches.contains(letters.last) &&
      (letters.last != 'A' || body.drop(letters.length + 1).matches("[0-9A-F]{1,2}"))
  }

  private def notANativeSwitch(tool: String, switches: Seq[String]): String =
    s"on Windows an argument starting with '/' must be a switch of the native $tool (${switches.mkString(" ")}); " +
      "another build of the program earlier on the PATH (MSYS2, Cygwin, Git for Windows) may open it as a path from " +
      "its own root (`/c/...` is C:\\...)."

  /**
   * Windows `findstr` takes switches after `/` or `-`, several letters to a switch (`/SIN`). `/F:file` reads the
   * list of files to search from a file, which the path rule cannot see into, so a switch with `F` in its letters is
   * refused (bar `/OFF[LINE]`). `/D:dir1;dir2` searches a list of directories, so a `/D:` value holding a list
   * separator is refused; a single directory is held to the workspace by the path rule. An argument starting with `/`
   * that is not one of the native switches ([[isNativeFindstrSwitch]]) is refused (#1738): the path rule judges only
   * the tails of a switch, and a non-native `findstr` may open `/c/...` as `C:\...`.
   */
  private def findstrRefusal(args: Seq[String]): Option[Refusal] =
    args.iterator
      .flatMap { arg =>
        if (arg.length < 2 || !(arg.startsWith("/") || arg.startsWith("-")))
          Option.when(arg == "/")(notAllowed("findstr", arg, notANativeSwitch("findstr.exe", FindstrSwitchList)))
        else {
          val letters = arg.drop(1).takeWhile(_ != ':').toUpperCase(java.util.Locale.ROOT)
          val value   = arg.dropWhile(_ != ':').drop(1)
          val offline = letters == "OFF" || letters == "OFFLINE"
          if (!offline && letters.contains('F'))
            Some(notAllowed("findstr", arg, "/F reads the names of the files to search from a file."))
          else if (letters.contains('D') && (value.contains(',') || value.contains(';')))
            Some(notAllowed("findstr", arg, "/D takes a list of directories; give it a single directory."))
          else if (arg.startsWith("/") && !isNativeFindstrSwitch(arg))
            Some(notAllowed("findstr", arg, notANativeSwitch("findstr.exe", FindstrSwitchList)))
          else None
        }
      }
      .nextOption()

  private val FindstrSwitchList: Seq[String] =
    "/B /E /L /R /S /I /X /V /N /M /O /P /OFF[LINE] /C:string /G:file /D:dir /A:color".split(' ').toSeq

  /**
   * The programs whose native Windows build is in the system directory and whose `/` arguments the policy reads as
   * that build's switches (#1738), with the executable file `CreateProcess` looks for (it appends `.exe` to a name
   * with no extension and does not consult `PATHEXT`).
   */
  val NativeSwitchTools: Map[String, String] = Map("sort" -> "sort.exe", "findstr" -> "findstr.exe")

  /**
   * On a Windows host, a refusal when `program` would not run the system directory's build (#1738). `ProcessBuilder`
   * hands a bare name to `CreateProcess` with no application name, which searches, in order, the directory the
   * runner's own executable (`java.exe`) was loaded from, the runner's current directory, the system directory, the
   * 16-bit system directory, the Windows directory and then `PATH`
   * (https://learn.microsoft.com/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw). So a build
   * earlier on `PATH` (MSYS2, Cygwin) does not run while the system directory has the tool, but one in either of the
   * first two directories (`searchedBefore`) does, and its `/c/...` arguments would be paths. A host whose system
   * directory lacks the tool (some minimal images) reaches `PATH`; the switch checks still hold there, since every
   * `/` argument must be a native switch.
   */
  def shadowedToolRefusal(program: String, searchedBefore: Seq[Path]): Option[Refusal] =
    NativeSwitchTools.get(program).flatMap { file =>
      searchedBefore
        .map(_.resolve(file))
        .find(candidate => Try(Files.exists(candidate)).getOrElse(true))
        .map(found =>
          Refusal(
            "EXECUTABLE_NOT_ALLOWED",
            s"'$program' would run '$found' rather than the Windows system directory's build, whose switches the " +
              "workspace policy checks; remove it from the runner's Java or working directory."
          )
        )
    }

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
        else if (subArgs.exists(_.startsWith(":")))
          subArgs
            .find(_.startsWith(":"))
            .map(
              notAllowed(
                s"git $subcommand",
                _,
                "an argument starting with ':' is pathspec magic (':/', ':(top)') or an index path, which git resolves " +
                  "from the repository's top level rather than the working directory."
              )
            )
        else
          GitSubcommandOptions
            .get(subcommand)
            .flatMap(options => firstRefused(options, subArgs))
            .map(notAllowed(s"git $subcommand", _, "it writes a file or runs another program."))
    }
  }

  /**
   * Confines a `git` process to a repository inside the workspace: `GIT_CEILING_DIRECTORIES` is the real workspace
   * root's parent, so git stops looking for a repository at the root, and every other `GIT_*` variable the runner's own
   * environment carries is removed. Without it, a workspace that is a subdirectory of a larger repository runs git on
   * that repository, and `git show HEAD:secret`, `git diff` and `git status` read files outside the workspace.
   *
   * The caller cannot set a `GIT_*` variable (only locale variables are allowed), but the runner's environment could
   * carry one that points git at a repository elsewhere (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_COMMON_DIR`,
   * `GIT_OBJECT_DIRECTORY`, `GIT_ALTERNATE_OBJECT_DIRECTORIES`, `GIT_INDEX_FILE`, `GIT_NAMESPACE`,
   * `GIT_DISCOVERY_ACROSS_FILESYSTEM`), adds configuration (`GIT_CONFIG`, `GIT_CONFIG_GLOBAL`, `GIT_CONFIG_SYSTEM`,
   * `GIT_CONFIG_PARAMETERS`, `GIT_CONFIG_COUNT` with `GIT_CONFIG_KEY_n` / `GIT_CONFIG_VALUE_n`) or names a program to
   * run (`GIT_EXEC_PATH`, `GIT_EXTERNAL_DIFF`, `GIT_PAGER`). None is needed by the read subcommands the policy allows,
   * so all are removed rather than listed. `GIT_CONFIG_NOSYSTEM` is not set: the system and global configuration
   * belong to whoever runs the runner, not to the agent, and a container image may need them (`safe.directory`).
   * Names are matched ignoring case, as Windows does.
   */
  def confineGit(environment: java.util.Map[String, String], realRoot: Path): Unit = {
    val names = environment.keySet.asScala.toList
    names.filter(_.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_")).foreach(environment.remove)
    Option(realRoot.getParent).foreach(parent => environment.put("GIT_CEILING_DIRECTORIES", parent.toString))
  }

  /**
   * `GIT_CEILING_DIRECTORIES` is a list split on the platform's path-list separator (`:` on POSIX, `;` on Windows)
   * with no way to escape one, so a workspace root whose parent's path holds the separator (`/tmp/x:y/ws`) would set
   * the ceilings `/tmp/x` and `y/...`, neither of them the parent, and git would climb into a repository above the
   * workspace. Rather than run git unconfined, such a workspace refuses it. The real parent (the ceiling [[confineGit]]
   * sets) and the configured one are both checked, for the separator of the host git runs on.
   */
  private def gitCeilingRefusal(roots: Seq[Path]): Option[Refusal] = {
    val separator = File.pathSeparatorChar
    roots
      .flatMap(root => Option(root.getParent))
      .map(_.toString)
      .find(_.contains(separator))
      .map { parent =>
        Refusal(
          PathEscapeAttempt,
          s"git cannot be confined to this workspace: its parent directory '$parent' holds the path-list separator " +
            s"'$separator', which GIT_CEILING_DIRECTORIES cannot escape, so git could use a repository outside the " +
            "workspace. Move the workspace to a path without it."
        )
      }
  }

  /**
   * git looks for its repository in the working directory and then each directory above it. The runner sets
   * `GIT_CEILING_DIRECTORIES` to the workspace root's parent, so git never uses a repository whose top level lies
   * above the workspace (where `git show HEAD:secret` and `git diff` read files outside it). Inside the workspace, the
   * nearest `.git` between the working directory and the root must be a directory, and its real path must lie inside
   * the workspace: a `.git` file (`gitdir: path`), a link, or a Windows junction (which Java reports as a directory)
   * points git at a repository elsewhere, whose content and configuration it would then read.
   */
  private def gitRepositoryRefusal(workDir: Path, realRoot: Path): Option[Refusal] =
    Iterator
      .iterate(workDir)(_.getParent)
      .takeWhile(dir => dir != null && dir.startsWith(realRoot))
      .map(_.resolve(".git"))
      .find(Files.exists(_, LinkOption.NOFOLLOW_LINKS))
      .filterNot { dotGit =>
        Files.isDirectory(dotGit, LinkOption.NOFOLLOW_LINKS) &&
        Try(dotGit.toRealPath()).toOption.exists(_.startsWith(realRoot))
      }
      .map { dotGit =>
        Refusal(
          PathEscapeAttempt,
          s"'$dotGit' is not a directory inside the workspace: a .git file, link or junction points git at a " +
            "repository that may lie outside the workspace."
        )
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
      val strings =
        if (program == "sort" && !isWindows) sortCandidates(args) else indexedCandidates(args, options, switches)
      // An operand that names a link itself is judged by the directory holding it, not by where the link leads.
      val links: Set[Int] =
        if (isWindows) Set.empty
        else linkOperands(program, args).filter(i => namesLinkItself(workDir, args(i), realRoot, budget))
      strings
        .collect { case (i, arg, candidate) if !links.contains(i) => (arg, candidate) }
        .map { case (arg, candidate) =>
          val judged =
            verdict(workDir, candidate, realRoot, budget)
              .orElse(unparseableVerdict(workDir, candidate, isWindows, realRoot, budget))
              .orElse(
                if (isWindows) msysDrivePath(candidate).flatMap(verdict(workDir, _, realRoot, budget)) else None
              )
          (arg, candidate, judged)
        }
        .collectFirst {
          case (arg, candidate, Some(Outside)) => escape(program, arg, candidate)
          case (_, _, Some(TooCostly))         => tooCostly(program)
        }
        .orElse(if (program == "cp") cpDestinationRefusal(args, isWindows, workDir, realRoot, budget) else None)
        .orElse(sequenceRefusal(program, args, isWindows, workDir, budget))
    }

  /**
   * The strings in `args` that the program might open as a path, each with the argument it came from.
   *
   *  - A plain argument is itself a candidate (on Windows, a `/X` switch of [[WindowsSwitchPrograms]] contributes
   *    the tails after its slash instead, so the value of `/G:file` is checked).
   *  - A long option `--name=value` contributes itself and its value - not the tails of its name, so text such as
   *    `--since=2024/01/01` or `--grep=feat/x` is not read as the absolute path `/01/01`. A value given as the
   *    next argument is a plain argument.
   *  - A short option contributes itself and every tail after its dash, which covers an attached value at any
   *    position (`-f/x`, `-rf/x`) and a program that opens the whole argument as a file (BSD programs stop reading
   *    options at their first operand, so `cat a.txt -f` opens `-f`).
   *  - After a bare `--`, every argument contributes itself and all its tails, because an option may have consumed
   *    the `--` as its value, and an operand that looks like an option is opened as a file.
   *
   * No argument is exempt here: an option's value given as the next argument is a plain argument, whatever the
   * option. POSIX `sort`, whose `-t` value is a separator, is parsed instead by [[sortCandidates]] (#1763).
   */
  private def candidates(args: Seq[String], options: Options, switches: Boolean): Iterator[(String, String)] =
    indexedCandidates(args, options, switches).map { case (_, arg, candidate) => (arg, candidate) }

  /** [[candidates]], each with the index in `args` of the argument it came from. */
  private def indexedCandidates(
    args: Seq[String],
    options: Options,
    switches: Boolean
  ): Iterator[(Int, String, String)] = {
    // In `/G:file` the `G:` names the switch, so the first tail `G:file` is not a path on drive G: (`file`, the
    // next-but-one tail, is checked; so is `D:file` in `/G:D:file`).
    def plain(arg: String): Iterator[String] =
      if (switches && arg.length > 1 && arg.startsWith("/"))
        tails(arg).filterNot(tail => tail.length == arg.length - 1 && SwitchWithValue.matches(tail))
      else Iterator.single(arg)
    def textLong(name: String): Boolean = name.length > 3 && options.textLong.exists(_.startsWith(name))

    @tailrec
    def loop(
      rest: List[(String, Int)],
      afterDashes: Boolean,
      found: List[(Int, String, () => Iterator[String])]
    ): List[(Int, String, () => Iterator[String])] =
      rest match {
        case Nil               => found.reverse
        case ("--", _) :: tail => loop(tail, afterDashes = true, found)
        case (arg, i) :: tail if afterDashes =>
          val all = () => plain(arg) ++ (if (arg.startsWith("-")) tails(arg) else Iterator.empty)
          loop(tail, afterDashes, (i, arg, all) :: found)
        case (arg, i) :: tail if arg.startsWith("--") && arg.length > 2 =>
          val name     = arg.takeWhile(_ != '=')
          val hasValue = arg.length > name.length
          val value    = if (hasValue && !textLong(name)) Iterator.single(arg.drop(name.length + 1)) else Iterator.empty
          val all      = () => Iterator.single(arg) ++ value.filter(_.nonEmpty)
          loop(tail, afterDashes, (i, arg, all) :: found)
        case (arg, i) :: tail if arg.length > 1 && arg.startsWith("-") =>
          loop(tail, afterDashes, (i, arg, () => tails(arg) ++ Iterator.single(arg)) :: found)
        case (arg, i) :: tail => loop(tail, afterDashes, (i, arg, () => plain(arg)) :: found)
      }

    loop(args.toList.zipWithIndex, afterDashes = false, Nil).iterator.flatMap { case (i, arg, all) =>
      all().map(candidate => (i, arg, candidate))
    }
  }

  /**
   * The Windows path an MSYS2 / Cygwin runtime reads `candidate` as when it has the form `/x` or `/x/rest`: drive
   * `x`'s root (`/c/Users` is `c:/Users`), not a root-relative path on the working directory's drive (#1738). On
   * Windows such a candidate is judged under both readings, so a program built on such a runtime cannot open a
   * path the root-relative reading kept inside.
   */
  private[runner] def msysDrivePath(candidate: String): Option[String] =
    Option.when(
      candidate.length >= 2 && candidate.charAt(0) == '/' && isAsciiLetter(candidate.charAt(1)) &&
        (candidate.length == 2 || candidate.charAt(2) == '/')
    )(s"${candidate.charAt(1)}:/${candidate.drop(3)}")

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  /** Every tail of `arg` after its first character (`-rf/x` gives `rf/x`, `f/x`, `/x`, `x`). */
  private def tails(arg: String): Iterator[String] = Iterator.range(1, arg.length).map(arg.substring)

  // ---- getopt: which argument is an option's value, as the program's own parser reads them (#1763)

  /** Whether a long option takes a value: never, always (`--key=2` or `--key 2`), or only after `=` (`--check=quiet`). */
  sealed private trait Arity
  private case object NoValue       extends Arity
  private case object RequiredValue extends Arity
  private case object OptionalValue extends Arity

  /**
   * One implementation's options, as its `getopt` / `getopt_long` reads them: a short option that takes a value takes
   * the rest of its cluster (`-Tdir`, `-rTdir`), or else the next argument, whatever it is (`-T -t`, `-T --`); a long
   * one that requires a value takes the text after `=`, or else the next argument; a long option may be abbreviated to
   * any prefix that selects only it.
   *
   * @param valueShort short options that take a value
   * @param flagShort  short options that take none
   * @param long       long options by name (without `--`)
   * @param permute    whether options may follow operands (GNU and FreeBSD `getopt_long`); otherwise the first operand
   *                   ends the options (macOS `getopt`, and GNU programs run with `POSIXLY_CORRECT` set)
   * @param takesNext  whether a short value option at the end of its cluster takes the next argument (GNU sort's
   *                   obsolete `-y` takes it only when it is all digits)
   */
  final private case class Getopt(
    valueShort: Set[Char],
    flagShort: Set[Char],
    long: Map[String, Arity],
    permute: Boolean = true,
    takesNext: (Char, String) => Boolean = (_, _) => true
  ) {

    /** The long option `name` selects, if exactly one: the exact name, or the only one it abbreviates. */
    def resolve(name: String): Option[(String, Arity)] =
      long.get(name).map(name -> _).orElse {
        val matches = long.filter { case (full, _) => full.startsWith(name) }
        if (name.nonEmpty && matches.size == 1) matches.headOption else None
      }
  }

  /** What one argument is to a program's option parser. */
  sealed private trait Role

  /** The `--` that ends the options (not one an option took as its value). */
  private case object EndOfOptions extends Role

  private case object Operand extends Role

  /** A short-option cluster; `valueAt` is the index in the argument of the first letter that takes a value. */
  final private case class ShortOptions(valueAt: Option[Int]) extends Role

  /** A long option, by its full name when it selects exactly one, with the text after its `=`. */
  final private case class LongOption(name: Option[String], attached: Option[String]) extends Role

  /** The value of the option before it, given as the next argument; `option` is `-T` or `--temporary-directory`. */
  final private case class OptionValue(option: String) extends Role

  /**
   * Each argument's role, and whether the parse is exact: `false` when an option is one the table does not know, is
   * an ambiguous abbreviation, or is missing its value or given one it does not take. The program stops with an error
   * then, but a version with an option the table lacks might not, so nothing that depends on the parse is exempted.
   */
  final private case class Parse(roles: Vector[Role], exact: Boolean)

  /** `args` as `spec`'s parser reads them, left to right, each option's value consumed exactly once. */
  private def parseOptions(args: IndexedSeq[String], spec: Getopt): Parse = {
    @tailrec
    def loop(i: Int, optionsEnded: Boolean, roles: Vector[Role], exact: Boolean): Parse =
      if (i >= args.length) Parse(roles, exact)
      else {
        val arg  = args(i)
        val next = args.lift(i + 1)
        if (optionsEnded) loop(i + 1, optionsEnded, roles :+ Operand, exact)
        else if (arg == "--") loop(i + 1, optionsEnded = true, roles :+ EndOfOptions, exact)
        else if (arg.startsWith("--")) {
          val body     = arg.drop(2)
          val name     = body.takeWhile(_ != '=')
          val attached = if (body.length > name.length) Some(body.drop(name.length + 1)) else None
          spec.resolve(name) match {
            case Some((full, RequiredValue)) if attached.isEmpty && next.nonEmpty =>
              loop(i + 2, optionsEnded, roles :+ LongOption(Some(full), None) :+ OptionValue(s"--$full"), exact)
            case Some((full, arity)) =>
              val error = (arity == RequiredValue && attached.isEmpty) || (arity == NoValue && attached.nonEmpty)
              loop(i + 1, optionsEnded, roles :+ LongOption(Some(full), attached), exact && !error)
            case None => loop(i + 1, optionsEnded, roles :+ LongOption(None, attached), exact = false)
          }
        } else if (arg.length > 1 && arg.startsWith("-")) {
          val valueAt = arg.indexWhere(spec.valueShort.contains, 1)
          val letters = if (valueAt < 0) arg.drop(1) else arg.substring(1, valueAt)
          val known   = letters.forall(spec.flagShort.contains)
          val role    = ShortOptions(Option.when(valueAt >= 0)(valueAt))
          if (valueAt >= 0 && valueAt == arg.length - 1) {
            val option = arg.charAt(valueAt)
            next match {
              case Some(value) if spec.takesNext(option, value) =>
                loop(i + 2, optionsEnded, roles :+ role :+ OptionValue(s"-$option"), exact && known)
              case Some(_) => loop(i + 1, optionsEnded, roles :+ role, exact && known)
              case None    => loop(i + 1, optionsEnded, roles :+ role, exact = false)
            }
          } else loop(i + 1, optionsEnded, roles :+ role, exact && known)
        } else loop(i + 1, optionsEnded || !spec.permute, roles :+ Operand, exact)
      }

    loop(0, optionsEnded = false, Vector.empty, exact = true)
  }

  // ---- sort

  private val SortFlags: Set[Char] = "bcCdfghiMmnRrsuVz".toSet

  /** GNU sort's long options; BSD sort (FreeBSD, macOS) has them all. */
  private val SortLong: Map[String, Arity] =
    Seq(
      "debug",
      "dictionary-order",
      "general-numeric-sort",
      "help",
      "human-numeric-sort",
      "ignore-case",
      "ignore-leading-blanks",
      "ignore-nonprinting",
      "merge",
      "month-sort",
      "numeric-sort",
      "random-sort",
      "reverse",
      "stable",
      "unique",
      "version",
      "version-sort",
      "zero-terminated"
    ).map(_ -> NoValue).toMap ++
      Seq(
        "batch-size",
        "buffer-size",
        "compress-program",
        "field-separator",
        "files0-from",
        "key",
        "output",
        "parallel",
        "random-source",
        "sort",
        "temporary-directory"
      ).map(_ -> RequiredValue) ++
      Seq("check" -> OptionalValue)

  /** GNU sort: `-y` (obsolete, ignored) takes a value, but only an attached one or a next argument of digits. */
  private val GnuSort = Getopt(
    valueShort = "kSoTty".toSet,
    flagShort = SortFlags,
    long = SortLong,
    takesNext = (option, next) => option != 'y' || next.forall(c => c >= '0' && c <= '9')
  )

  /** BSD sort has no `-y`, and adds its algorithm options and a literal `--check=silent|quiet`. */
  private val BsdSort = Getopt(
    valueShort = "kSoTt".toSet,
    flagShort = SortFlags,
    long = SortLong ++ Seq("heapsort", "mergesort", "mmap", "qsort", "radixsort").map(_ -> NoValue) +
      ("check=silent|quiet" -> OptionalValue)
  )

  /**
   * POSIX `sort`'s candidate paths: as [[candidates]], except that the value of `-t` / `--field-separator` is a
   * separator, not a path, and is not checked - but only when it really is that value (#1763).
   *
   * `sort -T -t /etc/passwd` reads `/etc/passwd`: `-T` takes `-t` as its directory, so the next argument is an
   * operand. So the arguments are parsed as sort parses them, each option's value consumed once ([[parseOptions]]):
   * attached (`-Tdir`, `-rTdir`), separate (`-T dir`, `--temporary-directory dir`, `--temp dir`) or after `=`
   * (`--temporary-directory=dir`), up to the `--` that ends the options. They are parsed four ways - by GNU and by
   * BSD sort's option table, each with and without permutation (`POSIXLY_CORRECT`, which the runner's own environment
   * may carry, makes GNU sort read every argument after its first operand as a file) - and an argument contributes
   * the candidates of its role in each. Every operand and every other option's value is checked whole. The separator is
   * left out only when all four parses are exact ([[Parse.exact]]) and no argument starts with `+`: BSD sort rewrites
   * the obsolete `+POS1 -POS2` into `-k` before it parses options, even inside another option's value, which can
   * remove the `-t` an exact parse found (`sort -T +0 -1t /etc/passwd`). Otherwise the separator is checked too, and
   * an operand that looks like an option contributes its tails, as after `--`.
   */
  private def sortCandidates(args: Seq[String]): Iterator[(Int, String, String)] = {
    val argv = args.toVector
    val parses = for {
      spec    <- Seq(GnuSort, BsdSort)
      permute <- Seq(true, false)
    } yield parseOptions(argv, spec.copy(permute = permute))
    val separatorIsText = parses.forall(_.exact) && !argv.exists(a => a.length > 1 && a.startsWith("+"))

    def ofRole(arg: String, role: Role): Iterator[String] = role match {
      case EndOfOptions => Iterator.empty
      case Operand =>
        Iterator.single(arg) ++ (if (!separatorIsText && arg.startsWith("-")) tails(arg) else Iterator.empty)
      // `-rt/`: the letters up to and including `t` (`rt/`, `t/`), not the separator after it
      case ShortOptions(Some(at)) if separatorIsText && arg.charAt(at) == 't' =>
        Iterator.range(1, at + 1).map(arg.substring) ++ Iterator.single(arg)
      case ShortOptions(_) => tails(arg) ++ Iterator.single(arg)
      case LongOption(name, attached) =>
        val separator = separatorIsText && name.contains("field-separator")
        Iterator.single(arg) ++ attached.filter(value => value.nonEmpty && !separator)
      case OptionValue("-t" | "--field-separator") if separatorIsText => Iterator.empty
      case OptionValue(_)                                             => Iterator.single(arg)
    }

    argv.indices.iterator.flatMap { i =>
      parses.map(_.roles(i)).distinct.iterator.flatMap(ofRole(argv(i), _)).distinct.map(c => (i, argv(i), c))
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
  private def physicalPath(
    base: Path,
    arg: String,
    budget: Budget,
    visit: (Path, String) => Unit = NoVisit
  ): Option[Either[Verdict, Path]] =
    parse(arg) match {
      case Some(path) =>
        Some(split(base, path).flatMap { case (start, names) =>
          walk(start, names, hops = 0, missing = false, budget, visit)
        })
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

  /** A [[walk]] that records nothing. */
  private val NoVisit: (Path, String) => Unit = (_, _) => ()

  /**
   * `missing` is true once the walk is below a component that does not exist: nothing below it can be a link, so no
   * file-system call is made until a `..` climbs back. `visit` is told of every name looked up in a directory that
   * exists, as `(directory, name)`, links' targets included (see [[sequenceRefusal]]).
   */
  @tailrec
  private def walk(
    current: Path,
    names: List[String],
    hops: Int,
    missing: Boolean,
    budget: Budget,
    visit: (Path, String) => Unit
  ): Either[Verdict, Path] =
    names match {
      case Nil                  => Right(current)
      case _ if !budget.spend() => Left(TooCostly)
      case ("" | ".") :: rest   => walk(current, rest, hops, missing, budget, visit)
      case ".." :: rest =>
        walk(Option(current.getParent).getOrElse(current), rest, hops, missing = false, budget, visit)
      case name :: rest if missing => walk(current.resolve(name), rest, hops, missing, budget, visit)
      case name :: rest =>
        visit(current, name)
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
                    walk(start, targetNames ++ rest, hops + 1, missing = false, budget, visit)
                }
            }
        } else walk(next, rest, hops, missing = !Files.exists(next, LinkOption.NOFOLLOW_LINKS), budget, visit)
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

  /** GNU cp: `-S` / `--suffix` and `-t` / `--target-directory` take a value. */
  private val GnuCp = Getopt(
    valueShort = Set('S', 't'),
    flagShort = "abdfHilLnprsTuvxPRZ".toSet,
    long = Seq(
      "archive",
      "attributes-only",
      "copy-contents",
      "debug",
      "dereference",
      "force",
      "interactive",
      "keep-directory-symlink",
      "link",
      "no-clobber",
      "no-dereference",
      "no-target-directory",
      "one-file-system",
      "parents",
      "path",
      "recursive",
      "remove-destination",
      "strip-trailing-slashes",
      "symbolic-link",
      "verbose",
      "help",
      "version"
    ).map(_ -> (NoValue: Arity)).toMap ++
      Seq("no-preserve", "sparse", "suffix", "target-directory").map(_ -> RequiredValue) ++
      Seq("backup", "context", "preserve", "reflink", "update").map(_ -> OptionalValue)
  )

  /** macOS cp: no option takes a value (`-S` is a flag), and its `getopt` stops at the first operand. */
  private val BsdCp =
    Getopt(valueShort = Set.empty, flagShort = "acfHiLlNnPpRrSsvXx".toSet, long = Map.empty, permute = false)

  /**
   * `cp`'s operands and how it copies. The operands - a `-t` / `--target-directory` value included - are those of
   * either parse, GNU's or macOS's ([[parseOptions]]), so an option's value is consumed once and a `--` it took as
   * its value (`cp -S -- -R src dst`) does not end the options (#1763). Which flags are set is read from every
   * argument wherever it stands, operands and values included, which only adds checks: `-R` after a `--` that `-S`
   * consumed is still seen as recursive.
   */
  private def cpCommand(args: Seq[String]): CpCommand = {
    // Any abbreviation of at least one letter: `--rec` is `--recursive`, `--pat` is `--path` (GNU's `--parents`).
    def long(name: String, full: String): Boolean = name.length > 2 && full.startsWith(name)

    val argv = args.toVector
    val operands = Seq(GnuCp, BsdCp)
      .flatMap { spec =>
        val roles = parseOptions(argv, spec).roles
        argv.indices.flatMap { i =>
          val arg = argv(i)
          roles(i) match {
            case Operand                                  => Some(i -> arg)
            case OptionValue("-t" | "--target-directory") => Some(i -> arg)
            case ShortOptions(Some(at)) if arg.charAt(at) == 't' && at < arg.length - 1 =>
              Some(i -> arg.substring(at + 1))
            case LongOption(Some("target-directory"), Some(dir)) => Some(i -> dir)
            case _                                               => None
          }
        }
      }
      .distinct
      .sortBy(_._1)
      .map(_._2)
      .toList

    val flags                   = args.filter(arg => arg.length > 1 && arg.startsWith("-") && arg != "--")
    val (longFlags, shortFlags) = flags.partition(_.startsWith("--"))
    val names                   = longFlags.map(_.takeWhile(_ != '='))
    val clusters                = shortFlags.map(_.drop(1))
    val recursive =
      names.exists(n => long(n, "--recursive") || long(n, "--archive")) ||
        clusters.exists(_.exists(c => c == 'R' || c == 'r' || c == 'a'))
    CpCommand(
      operands = operands,
      recursive = recursive,
      keepsLinks = recursive || names.exists(long(_, "--no-dereference")) ||
        clusters.exists(_.exists(c => c == 'P' || c == 'd')),
      parents = names.exists(n => long(n, "--parents") || long(n, "--path"))
    )
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
   *    the same name, nor several sources and one that names a directory's contents. "The same name" is the name
   *    the destination's file system sees: names are compared after case folding and Unicode normalisation
   *    ([[folded]]), as macOS (APFS, HFS+) and Windows (NTFS) compare them - there `cp -P a/b/x c/X d` makes the link
   *    `d/x`, and macOS cp then opens `d/X`, which is that link, and writes `c/X` through it to where it points. On
   *    Windows a name holding `~`, which may be another name's 8.3 short name, matches any.
   *
   * These checks run before `cp` starts; a link made at a destination name while it runs (by another command) is
   * not seen.
   */
  private def cpDestinationRefusal(
    args: Seq[String],
    isWindows: Boolean,
    workDir: Bases,
    realRoot: Path,
    budget: Budget
  ): Option[Refusal] = {
    val command  = cpCommand(args)
    val operands = command.operands.toVector

    // As the destination's file system compares them: `x` and `X`, or `café` composed and decomposed, are one name
    // on macOS and Windows (#1775 review); on Windows a `~` may make a name another's short name.
    val names       = operands.flatMap(lastName)
    val foldedNames = names.map(folded)
    val sameName =
      foldedNames.distinct.size < names.size ||
        (names.size >= 2 && foldedNames.contains(None)) ||
        (isWindows && names.size >= 2 && names.exists(_.contains('~')))
    val clash =
      command.keepsLinks && operands.size >= 3 && (sameName || operands.exists(copiesContents))

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
          "A cp that copies links (-R, -a, -P, -d) may not have two sources with the same name (letter case and " +
            "Unicode normalisation ignored, as macOS and Windows file systems compare names), or several sources " +
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

  // ---- rm, mv, unlink: removing or renaming a link that points out of the workspace (#1730)

  /** GNU rm (and uutils): no option takes a separate value; `--interactive` and `--preserve-root` take one after `=`. */
  private val GnuRm = Getopt(
    valueShort = Set.empty,
    flagShort = "dfiIrRv".toSet,
    long = Seq("dir", "force", "one-file-system", "no-preserve-root", "recursive", "verbose", "help", "version")
      .map(_ -> (NoValue: Arity))
      .toMap ++ Seq("interactive", "preserve-root").map(_ -> OptionalValue)
  )

  /** macOS and FreeBSD rm: no long options, and `getopt` stops at the first operand. */
  private val BsdRm =
    Getopt(valueShort = Set.empty, flagShort = "dfiIPRrvWx".toSet, long = Map.empty, permute = false)

  /** GNU mv: `-S` / `--suffix` and `-t` / `--target-directory` take a value. */
  private val GnuMv = Getopt(
    valueShort = Set('S', 't'),
    flagShort = "bfinTuvZ".toSet,
    long = Seq(
      "context",
      "debug",
      "exchange",
      "force",
      "interactive",
      "no-clobber",
      "no-copy",
      "no-target-directory",
      "strip-trailing-slashes",
      "verbose",
      "help",
      "version"
    ).map(_ -> (NoValue: Arity)).toMap ++
      Seq("suffix", "target-directory").map(_ -> RequiredValue) ++
      Seq("backup", "update").map(_ -> OptionalValue)
  )

  /** macOS and FreeBSD mv: no option takes a value, no long options, and `getopt` stops at the first operand. */
  private val BsdMv = Getopt(valueShort = Set.empty, flagShort = "fhinv".toSet, long = Map.empty, permute = false)

  /** GNU unlink has only `--help` and `--version`; macOS unlink takes no option, only a `--`. */
  private val GnuUnlink =
    Getopt(valueShort = Set.empty, flagShort = Set.empty, long = Map("help" -> NoValue, "version" -> NoValue))
  private val BsdUnlink = Getopt(valueShort = Set.empty, flagShort = Set.empty, long = Map.empty, permute = false)

  /**
   * The indices of the arguments of `rm`, `mv` or `unlink` that the program removes or renames as names, without
   * following a link at their last component: every operand of `rm` and `unlink`, every source of `mv`. `rm`, `mv`
   * and `unlink` act on a link itself (`unlink(2)`, `rename(2)`; `rm` reads the operand with `lstat`), so such an
   * operand may name a link that points out of the workspace; [[namesLinkItself]] decides whether it does.
   *
   * Erring towards the existing refusal, nothing is returned unless the arguments are parsed exactly by every
   * implementation's option table - GNU's (with and without `POSIXLY_CORRECT`) and macOS / FreeBSD's
   * ([[parseOptions]]) - and each of those parses agrees the argument is an operand and, for `mv`, not the
   * destination. So a GNU-only form (`rm --force`, `mv -t dir`, `mv -T`, `mv --target-directory=dir`) keeps the
   * link refused, as does an option a table lacks. An argument starting with `-` is never returned. Nothing is
   * returned for a recursive `rm` (`-r`, `-R`, any abbreviation of `--recursive`): GNU and BSD `rm -r` remove the
   * link and not what it points to, but not every implementation has been verified to, and removing a link needs no
   * `-r`.
   *
   * The destination of `mv` is never returned: `mv a outlink` moves `a` into the directory the link points to, so the
   * destination stays held to the path rule, which follows the link. In each parse it is the last operand, or there is
   * none when a target directory is given (and then the target directory is an option value, checked as a path).
   */
  private def linkOperands(program: String, args: Seq[String]): Set[Int] = {
    val specs = program match {
      case "rm"     => Seq(GnuRm, BsdRm)
      case "mv"     => Seq(GnuMv, BsdMv)
      case "unlink" => Seq(GnuUnlink, BsdUnlink)
      case _        => Seq.empty
    }
    val argv = args.toVector
    val parses = for {
      spec    <- specs
      permute <- if (spec.permute) Seq(true, false) else Seq(false)
    } yield parseOptions(argv, spec.copy(permute = permute))

    def recursive: Boolean =
      argv.exists { arg =>
        if (arg.startsWith("--")) {
          val name = arg.takeWhile(_ != '=')
          name.length > 2 && "--recursive".startsWith(name)
        } else arg.length > 1 && arg.startsWith("-") && arg.drop(1).exists(c => c == 'r' || c == 'R')
      }

    /** The operands `program` acts on as names in one parse: for `mv`, the sources. */
    def named(parse: Parse): Set[Int] = {
      val operands = argv.indices.filter(i => parse.roles(i) == Operand)
      if (program != "mv") operands.toSet
      else {
        val targetDirectory = argv.indices.exists { i =>
          parse.roles(i) match {
            case OptionValue("-t" | "--target-directory")      => true
            case ShortOptions(Some(at))                        => argv(i).charAt(at) == 't'
            case LongOption(Some("target-directory"), Some(_)) => true
            case _                                             => false
          }
        }
        if (targetDirectory) operands.toSet else operands.dropRight(1).toSet
      }
    }

    if (parses.isEmpty || !parses.forall(_.exact) || (program == "rm" && recursive)) Set.empty
    else parses.map(named).reduce(_ intersect _).filterNot(i => argv(i).startsWith("-"))
  }

  /**
   * Whether `arg` names a symbolic link itself, in a directory inside the workspace, so that removing or renaming it
   * touches only the link wherever it points (a dangling link, a link to a link, or a link out of the workspace):
   *
   *  - its last component is a name, not `.` or `..`, and it has no trailing `/`: with one (`outlink/`, `outlink/.`)
   *    the kernel follows the link, so `mv outlink/ x` moves the directory it points to and `rm -r outlink/` empties it;
   *  - the directory holding it - the argument without its last component, or the working directory - resolves inside
   *    the workspace under both readings of the path rule (the kernel's, following links, and the lexical one, `..`
   *    removed as text), and both readings name the same real directory;
   *  - in that real directory, the last component is a symbolic link (`lstat`, not following it).
   *
   * Only called on POSIX: on Windows a link or junction is removed by `del` or `rd`, which the policy does not model,
   * so there such an operand keeps the path rule and is refused when it leads outside.
   */
  private def namesLinkItself(workDir: Bases, arg: String, realRoot: Path, budget: Budget): Boolean = {
    val cut    = arg.lastIndexOf('/')
    val name   = arg.substring(cut + 1)
    val parent = if (cut < 0) "." else if (cut == 0) "/" else arg.substring(0, cut)
    def real(reading: Option[Either[Verdict, Path]]): Option[Path] =
      reading.flatMap(_.toOption).flatMap(canonical(_, budget))
    name.nonEmpty && name != "." && name != ".." && {
      val physical = real(physicalPath(workDir.physical, parent, budget))
      physical.nonEmpty && physical == real(lexicalPath(workDir.lexical, parent)) &&
      physical.exists(dir => dir.startsWith(realRoot) && Files.isSymbolicLink(dir.resolve(name)))
    }
  }

  // ---- mv, cp of several sources: one operation changing the names a later one resolves (#1776)

  /**
   * One operation of an `mv` or `cp` of several sources: the source, by its index in the arguments, and the
   * destination directory it goes into.
   */
  final private case class Transfer(source: Int, sourceArg: String, destination: String)

  /**
   * A directory entry an operation creates, replaces or removes: `name` in the real directory `dir`. `None` is any
   * name; with `prefix`, any name that starts with `name` (a backup such as `name~` or `name.~1~`).
   */
  final private case class Changed(dir: Path, name: Option[String], prefix: Boolean)

  /**
   * Every operation of `mv` or `cp` that has two sources or more, under any parse of its arguments (GNU's table, with
   * and without `POSIXLY_CORRECT`, and macOS / FreeBSD's), and every argument any parse reads as a path - each operand
   * and each `-t` / `--target-directory` value - with its index. The parses are joined rather than intersected: an
   * argument that is a source in one parse and the destination in another counts as both, which only adds checks.
   */
  private def transfers(program: String, args: Seq[String]): (Seq[Transfer], Seq[(Int, String)]) = {
    val specs = program match {
      case "mv" => Seq(GnuMv, BsdMv)
      case "cp" => Seq(GnuCp, BsdCp)
      case _    => Seq.empty
    }
    val argv = args.toVector
    val shapes = for {
      spec    <- specs
      permute <- if (spec.permute) Seq(true, false) else Seq(false)
    } yield {
      val parse    = parseOptions(argv, spec.copy(permute = permute))
      val operands = argv.indices.filter(i => parse.roles(i) == Operand).map(i => i -> argv(i))
      val targets = argv.indices.flatMap { i =>
        val arg = argv(i)
        parse.roles(i) match {
          case OptionValue("-t" | "--target-directory") => Some(i -> arg)
          case ShortOptions(Some(at)) if arg.charAt(at) == 't' && at < arg.length - 1 =>
            Some(i -> arg.substring(at + 1))
          case LongOption(Some("target-directory"), Some(dir)) => Some(i -> dir)
          case _                                               => None
        }
      }
      val (sources, destinations) =
        if (targets.nonEmpty) (operands, targets) else (operands.dropRight(1), operands.takeRight(1))
      (sources, destinations, operands ++ targets)
    }
    val moves = shapes.filter(_._1.size >= 2).flatMap { case (sources, destinations, _) =>
      for {
        (i, source) <- sources
        (_, dest)   <- destinations
      } yield Transfer(i, source, dest)
    }
    (moves.distinct, shapes.flatMap(_._3).distinct)
  }

  /**
   * An argument split into the directory holding its last component and that component's name, a trailing separator
   * removed; `None` for the name when it is `.` or `..`, which a program would not create.
   */
  private def directoryAndName(arg: String, isWindows: Boolean): (String, Option[String]) = {
    def separator(c: Char): Boolean = c == '/' || (isWindows && c == '\\')
    val trimmed                     = arg.reverse.dropWhile(separator).reverse
    val cut                         = trimmed.lastIndexWhere(separator)
    val name                        = trimmed.substring(cut + 1)
    val directory = if (cut < 0) "." else if (cut == 0) trimmed.substring(0, 1) else trimmed.substring(0, cut)
    (directory, Option.when(name.nonEmpty && name != "." && name != "..")(name))
  }

  /**
   * A name as a case- and normalisation-insensitive file system may compare it, erring towards matching; `None` when
   * the folding does not settle, which callers take to match any name.
   *
   * Each pass decomposes (NFKD), upper-cases and lower-cases. Decomposing, as APFS does before it case-folds, matters
   * for the Greek iota subscript: NFKC composes U+0345 into its letter, and Java upper-cases `ᾳ` to `ΑΙ`, emitting the
   * iota before any mark that follows, so `ᾳ̃` and `α̃ι` - one name on APFS, where NFD puts the subscript after the
   * tilde - folded apart, and `cp -P a/b/zᾼ͂ c/zᾷ d` wrote `c/zᾷ` through the link it had just made (#1775 review).
   * One pass is not idempotent either - `ẞ` (U+1E9E) lower-cases to `ß`, which only a second pass upper-cases to `SS`
   * - so the pass is repeated until the name stops changing: over every code point and its upper, lower and (K)NF(K)D
   * forms that takes at most two changes (`CommandPolicyFoldedSpec`), so a name still changing after [[FoldPasses]] is
   * unexpected and treated as unknown. Over the 3.1 million pairs of names APFS took for one in a brute-force search,
   * this gives each pair one key.
   */
  private[runner] def folded(name: String): Option[String] = {
    def pass(s: String): String =
      Normalizer.normalize(
        Normalizer.normalize(s, Normalizer.Form.NFKD).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT),
        Normalizer.Form.NFKD
      )
    Iterator
      .iterate(name)(pass)
      .sliding(2)
      .take(FoldPasses)
      .collectFirst { case Seq(before, after) if before == after => after }
  }

  /** The most passes [[folded]] makes before giving up on a name. */
  private val FoldPasses = 4

  /**
   * `mv` and `cp` handle several sources one at a time, so each operation changes the file system the next one
   * resolves its paths in, while every path is checked before the command starts (#1776). With `sub/l` -> `../outside`
   * (inside the workspace while in `sub`), `mv sub/l l/secret.txt .` first moves the link to `./l`, where it points
   * out, and then moves `outside/secret.txt` in through it; with `evil` -> outside (a link the agent may move, #1730)
   * and an empty directory `d`, `mv evil d/evil/secret.txt d` does the same. `cp -P`, `-d`, `-R` and `-a` copy a link
   * as a link and GNU cp then reads a later source through the copy.
   *
   * So for an `mv` or `cp` of two sources or more, under any parse of its arguments ([[transfers]]), each source's
   * operation is taken to change these directory entries:
   *
   *  - in the destination directory (both readings of its path, links followed), the source's last name, and any name
   *    starting with it (`--backup` keeps the entry it replaces as `name~` or `name.~1~`); any name at all when that
   *    name is not known (`.`, `..`, a `cp -R src/.` that copies a directory's contents, or `cp --parents`);
   *  - for `mv`, the source's own entry in the directory holding it, which the move removes.
   *
   * Every other path argument - each other source, the destination, a `-t` value - is then walked as the path rule
   * walks it, under both readings, and the command is refused (`ARGUMENT_NOT_ALLOWED`) when that walk looks up, in the
   * same directory, a name one of those entries matches - including through a link's target, and comparing names
   * case-insensitively and after Unicode normalisation, as macOS and Windows file systems do (on Windows, a name with
   * a `~`, which may be a short name, matches any). An `mv` is also refused when the working directory lies inside a
   * source it moves, since a relative path's `..` would then climb from the source's new place. Order is ignored:
   * an argument before the source is checked too, which only adds refusals. A command whose destination is not an
   * existing directory is left to the program, which then refuses to run any operation.
   *
   * A path changes meaning between two operations only when one of them changes an entry that path looks up (or moves
   * the working directory), so this covers the reordering whatever each entry becomes. `rm` only removes entries, so a
   * later operand through one fails rather than leading elsewhere; `mkdir` and `touch` create directories and files,
   * never links, and the path rule already treats a missing component as a directory to be made; `chmod` changes no
   * name. Their operands are checked as before.
   */
  private def sequenceRefusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Bases,
    budget: Budget
  ): Option[Refusal] = {
    val (moves, paths) = transfers(program, args)
    if (moves.isEmpty) None
    else {
      def readings(arg: String): Seq[Path] =
        Seq(physicalPath(workDir.physical, arg, budget), lexicalPath(workDir.lexical, arg))
          .flatMap(_.flatMap(_.toOption))
          .flatMap(canonical(_, budget))
          .distinct

      // `cp --parents` creates the source's whole path under the destination, so its first name is not the last one
      val parents = program == "cp" && cpCommand(args).parents

      val changed: Seq[(Int, Changed)] = moves.flatMap { move =>
        val (directory, name) = directoryAndName(move.sourceArg, isWindows)
        val created = {
          val unknown = parents || (program == "cp" && copiesContents(move.sourceArg))
          readings(move.destination)
            .filter(Files.isDirectory(_))
            .map(dir => Changed(dir, if (unknown) None else name, prefix = true))
        }
        val removed =
          if (program != "mv") Nil
          else readings(directory).filter(Files.isDirectory(_)).map(dir => Changed(dir, name, prefix = false))
        (created ++ removed).map(move.source -> _)
      }

      def matches(change: Changed, dir: Path, name: String): Boolean = {
        val named = change.name.forall { n =>
          (folded(name), folded(n)) match {
            case (Some(looked), Some(entry)) => if (change.prefix) looked.startsWith(entry) else looked == entry
            case _                           => true // a name that does not fold settles nothing: take it to match
          }
        } || (isWindows && (name.contains('~') || change.name.exists(_.contains('~'))))
        named && Try(Files.isSameFile(dir, change.dir)).getOrElse(false)
      }

      /**
       * The entries the program looks up to reach `arg`, under both readings; `Left` when out of budget.
       *
       * The lexical reading is walked from the root (`split(absolute, absolute)`), not from the working directory, so
       * it looks up every directory above the working directory too. That is what refuses a relative path from a
       * working directory that an earlier operation writes into: from `d/sub`, `cp -R ../../src/sub l/secret.txt
       * ../../d` first merges `src/sub` - holding `l` -> outside - into `d/sub`, and GNU cp then reads `l/secret.txt`
       * through the copied link. The physical walk of `l/secret.txt` starts at the working directory and never looks
       * up `sub` in `d`; the lexical walk does, and `sub` in `d` is a name the first operation creates. Keep this walk
       * starting at the root (the spec pins it).
       */
      def lookups(arg: String): Either[Verdict, Seq[(Path, String)]] = {
        val seen     = scala.collection.mutable.ListBuffer.empty[(Path, String)]
        val visit    = (dir: Path, name: String) => { seen += (dir -> name); () }
        val physical = physicalPath(workDir.physical, arg, budget, visit)
        val lexical = lexicalPath(workDir.lexical, arg).flatMap(_.toOption).map { absolute =>
          split(absolute, absolute).flatMap { case (start, names) =>
            walk(start, names, hops = 0, missing = false, budget, visit)
          }
        }
        if (Seq(physical, lexical).flatten.contains(Left(TooCostly))) Left(TooCostly) else Right(seen.toList)
      }

      val movedAround: Option[Refusal] =
        if (program != "mv") None
        else
          moves.iterator.collectFirst {
            case move
                if readings(move.sourceArg).exists(moved => workDir.physical.startsWith(moved)) ||
                  lexicalPath(workDir.lexical, move.sourceArg)
                    .flatMap(_.toOption)
                    .exists(moved => workDir.lexical.normalize().startsWith(moved)) =>
              Refusal(
                ArgumentNotAllowed,
                s"'mv' would move '${move.sourceArg}', which holds the working directory, and then resolve its other " +
                  "paths from the working directory's new place; the paths are checked before the command runs. " +
                  "Run the command from another working directory, or move one source at a time."
              )
          }

      movedAround.orElse {
        paths.iterator
          .map { case (j, arg) => (j, arg, lookups(arg)) }
          .flatMap {
            case (_, _, Left(_)) => Some(tooCostly(program))
            case (j, arg, Right(entries)) =>
              entries.iterator
                .flatMap { case (dir, name) =>
                  changed.collectFirst {
                    case (i, change) if i != j && matches(change, dir, name) =>
                      Refusal(
                        ArgumentNotAllowed,
                        s"'$program' with several sources would reach '$arg' through '${dir.resolve(name)}', a name " +
                          s"its operation on '${args(i)}' creates, replaces or removes first; the paths are checked " +
                          s"before the command runs, so the later path could then lead elsewhere. " +
                          s"Run one '$program' per source."
                      )
                  }
                }
                .nextOption()
          }
          .nextOption()
      }
    }
  }
}
