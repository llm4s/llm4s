package org.llm4s.runner

import java.nio.file.{ Files, Path, Paths }
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.Try

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
 *  3. '''Paths''' (`PATH_ESCAPE_ATTEMPT`, the code the file operations use). Every argument - and, inside an option,
 *     every tail after its dash, so the `/etc/x` in `--file=/etc/x` and `-f/etc/x` - is resolved the way the kernel
 *     would resolve it from the real working directory: component by component, following each symbolic link
 *     where it is met, so `link/..` goes to the parent of the link's target, not back to the directory holding the
 *     link. The result must lie inside the real workspace root. An argument that does not name an existing file is
 *     judged the same way, so `../x` and `/tmp/x` are refused even when they do not exist yet. Programs that only
 *     print their arguments (`echo`, `pwd`, `whoami`, `hostname`) are not checked.
 *
 * The path rule cannot tell a path from text that looks like one: a `grep` pattern that starts with `/` or has a
 * `..` component is refused too (write `[/]api` for `/api`).
 *
 * '''Limits.''' The checks cover what a command is given, not what a program reads by itself. A recursive walk
 * (`ls -R`, `grep -r`, `find`, `diff -r`) is checked where it starts; `ls -L`, `grep -R` and `find -L`, which
 * follow every link they meet, are refused, but `diff -r` follows links inside the tree it walks. `git` reads the
 * repository's own configuration, so a repository whose `.git/config` sets `core.fsmonitor`, `diff.external` or a
 * filter or textconv driver makes `git status` or `git diff` run that program; where the agent can write files
 * (the `writeFile` operation, or `cp`/`mv` in [[org.llm4s.shared.WorkspaceSandboxConfig.ReadWriteCommands]]) it
 * can write that configuration.
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
   */
  final private case class Options(
    short: Set[Char] = Set.empty,
    long: Set[String] = Set.empty,
    exact: Set[String] = Set.empty,
    longAllowed: Set[String] = Set.empty
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
    // Follows every link while recursing.
    "grep" -> Options(short = Set('R'), long = Set("--dereference-recursive")),
    // Reads the names of the files to count from a file.
    "wc" -> Options(long = Set("--files0-from")),
    // Writes a file, runs a program, reads the names of the files to sort from a file.
    "sort" -> Options(short = Set('o'), long = Set("--output", "--compress-program", "--files0-from")),
    // Sets the host name.
    "hostname" -> Options(short = Set('F', 'b'), long = Set("--file", "--boot"))
  )

  /** `find`'s leading options in one cluster (`-HL` on BSD); one with `L` follows every link. */
  private val FindFollowCluster = "-[HLPEXdsx]*L[HLPEXdsx]*".r

  /** Programs whose arguments are not file names, so the path rule is not applied. */
  private val PrintOnly: Set[String] = Set("echo", "pwd", "whoami", "hostname")

  /** cmd.exe built-ins and Windows programs whose options are `/X` switches rather than paths. */
  private val WindowsSwitchPrograms: Set[String] = Set("dir", "findstr", "copy", "move", "sort")

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
    "--no-abbrev"
  )

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
   */
  def refusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Path,
    realRoot: Path,
    environment: Map[String, String]
  ): Option[Refusal] =
    environmentRefusal(environment, isWindows)
      .orElse(optionRefusal(program, args, isWindows))
      .orElse(pathRefusal(program, args, isWindows, workDir, realRoot))

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
   * a value, which is not an operand; after `--`, and for `-` alone, every argument is one.
   */
  private def uniqRefusal(args: Seq[String]): Option[Refusal] = {
    val valueShort = Set('f', 's', 'w')
    val valueLong  = Seq("--skip-fields", "--skip-chars", "--check-chars")

    @tailrec
    def operands(rest: List[String], afterDashes: Boolean, found: List[String]): List[String] =
      rest match {
        case Nil                        => found.reverse
        case arg :: tail if afterDashes => operands(tail, afterDashes, arg :: found)
        case "--" :: tail               => operands(tail, afterDashes = true, found)
        case "-" :: tail                => operands(tail, afterDashes, "-" :: found)
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
    args
      .find { arg =>
        if (arg == "--") false
        else if (arg.startsWith("--")) !GitBranchLong.contains(arg.takeWhile(_ != '='))
        else if (arg.startsWith("-") && arg.length > 1) !arg.drop(1).forall(GitBranchShort.contains)
        else !listing
      }
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

  private def pathRefusal(
    program: String,
    args: Seq[String],
    isWindows: Boolean,
    workDir: Path,
    realRoot: Path
  ): Option[Refusal] =
    if (PrintOnly.contains(program)) None
    else {
      val switches = isWindows && WindowsSwitchPrograms.contains(program)
      args.iterator.flatMap(arg => candidates(arg, switches).map(arg -> _)).collectFirst {
        case (arg, candidate) if !inside(workDir, candidate, realRoot) =>
          Refusal(
            PathEscapeAttempt,
            s"Argument '$arg' of '$program' names a location outside the workspace" +
              (if (candidate != arg) s" ('$candidate')" else "") +
              ". Paths are resolved from the working directory, following symbolic links, and must stay inside " +
              "the workspace root."
          )
      }
    }

  /**
   * The strings in `arg` that the program might open as a path: the whole argument, or for an option every tail
   * after its leading dash (which covers `--file=/x`, `-f/x` and an attached value of any length).
   */
  private def candidates(arg: String, windowsSwitches: Boolean): Iterator[String] = {
    val isOption = arg.length > 1 && (arg.startsWith("-") || (windowsSwitches && arg.startsWith("/")))
    if (isOption) Iterator.range(1, arg.length).map(arg.substring) else Iterator.single(arg)
  }

  /** True when `arg`, resolved from `workDir` as the kernel would, stays inside `realRoot`. */
  private[runner] def inside(workDir: Path, arg: String, realRoot: Path): Boolean =
    physicalPath(workDir, arg) match {
      case None                 => true  // not a path on this platform (e.g. a ':' on Windows): nothing can be opened
      case Some(None)           => false // a loop of symbolic links, or one that cannot be read
      case Some(Some(resolved)) => canonical(resolved).startsWith(realRoot)
    }

  /**
   * Resolves `arg` from `base` (a real path) one component at a time, following each symbolic link where it is met,
   * as the kernel does. A component that does not exist is taken as a directory that a command such as `mkdir -p`
   * would create, so a later `..` climbs back and a link met after it is still followed. `None` when `arg` is not a
   * path here, `Some(None)` when a link cannot be resolved.
   */
  private[runner] def physicalPath(base: Path, arg: String): Option[Option[Path]] =
    parse(arg).map { path =>
      val (start, names) = split(base, path)
      walk(start, names, hops = 0)
    }

  /**
   * `arg` as a path. Where the platform rejects it (a `:` past the drive letter on Windows, as in `HEAD:src/x` or
   * an alternate data stream `C:\x\f:s`), the part before that `:` is what a program could open.
   */
  private def parse(arg: String): Option[Path] =
    Try(Paths.get(arg)).toOption.orElse {
      val colon = arg.indexOf(':', 2)
      if (colon < 0) None else Try(Paths.get(arg.substring(0, colon))).toOption
    }

  private def split(base: Path, path: Path): (Path, List[String]) =
    if (path.getRoot != null) {
      val absolute = path.toAbsolutePath
      (absolute.getRoot, absolute.iterator.asScala.map(_.toString).toList)
    } else (base, path.iterator.asScala.map(_.toString).toList)

  private val MaxLinkHops = 40

  @tailrec
  private def walk(current: Path, names: List[String], hops: Int): Option[Path] =
    names match {
      case Nil                => Some(current)
      case ("" | ".") :: rest => walk(current, rest, hops)
      case ".." :: rest       => walk(Option(current.getParent).getOrElse(current), rest, hops)
      case name :: rest =>
        val next = current.resolve(name)
        if (Files.isSymbolicLink(next)) {
          if (hops >= MaxLinkHops) None
          else
            Try(Files.readSymbolicLink(next)).toOption match {
              case None => None
              case Some(target) =>
                val (start, targetNames) = split(current, target)
                walk(start, targetNames ++ rest, hops + 1)
            }
        } else walk(next, rest, hops)
    }

  /**
   * `path` with its deepest existing ancestor replaced by that ancestor's real path. The walk has already followed
   * symbolic links; this also settles what Java does not report as a link (a Windows junction), letter case on a
   * case-insensitive file system and Windows short names, so the comparison with the real root is like for like.
   */
  private def canonical(path: Path): Path = {
    val normalized = path.normalize()
    Iterator
      .iterate(normalized)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p))
      .flatMap(ancestor => Try(ancestor.toRealPath().resolve(ancestor.relativize(normalized))).toOption)
      .getOrElse(normalized)
  }
}
