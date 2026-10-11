package org.llm4s.shared

import org.llm4s.shared.WireDurations.wholeSecondsRW
import upickle.default.{ ReadWriter, macroRW }

import scala.concurrent.duration.*

/**
 * Explicit sandbox configuration for workspace operations.
 *
 * Describes allowed/blocked paths, resource limits, shell access, and timeouts.
 * When provided, this config is validated at startup and enforced by the runner.
 *
 * '''Phase 1:''' Config structure, validation, and documentation.
 * '''Phase 2:''' Enforcement in runner and tools.
 * '''Phase 3:''' Advanced policies (profiles, allowlists).
 *
 * @param limits               Resource limits (file size, directory entries, search results, output size)
 * @param excludePatterns      Glob patterns for paths to exclude from explore/search (e.g. node_modules, .git)
 * @param shellAllowed         Whether `executeCommand` is allowed (false = read-only file ops only)
 * @param defaultCommandTimeout Default timeout for shell commands (more than zero, at most an hour)
 * @param readOnlyPaths        Paths under workspace root that are read-only (writes denied)
 * @param allowedPaths         If non-empty, only these paths are accessible; if empty, whole workspace
 * @param networkAllowed       Documentation: whether network access from commands is assumed (Phase 2: enforce)
 * @param allowedCommands      Set of bare executable names permitted by executeCommand.
 *                             Defaults to [[WorkspaceSandboxConfig.ReadOnlyCommands]].
 *                             Use [[WorkspaceSandboxConfig.ReadWriteCommands]] to also permit
 *                             write-capable commands (cp, mv, rm, mkdir, …).
 */
final case class WorkspaceSandboxConfig(
  limits: WorkspaceLimits = WorkspaceSandboxConfig.DefaultLimits,
  excludePatterns: List[String] = WorkspaceSandboxConfig.DefaultExclusions,
  shellAllowed: Boolean = true,
  // The JSON key keeps its name and whole-second value, so client and runner versions interoperate
  @upickle.implicits.key("defaultCommandTimeoutSeconds") defaultCommandTimeout: FiniteDuration = 30.seconds,
  readOnlyPaths: List[String] = Nil,
  allowedPaths: List[String] = Nil,
  networkAllowed: Boolean = false,
  allowedCommands: Set[String] = WorkspaceSandboxConfig.ReadOnlyCommands
) {

  /**
   * This config with more programs on its allowlist, as the runner's `WORKSPACE_EXTRA_COMMANDS` adds them.
   *
   * Each name must be a bare program name (letters, digits, `.`, `_`, `+`, `-`), not a Windows batch file (`.bat`,
   * `.cmd`, which Windows runs through cmd.exe) and not one of [[WorkspaceSandboxConfig.NeverAllowedCommands]], in any
   * case, with or without a `.exe` / `.com` extension and with or without a version (`BASH.EXE` is `bash`,
   * `python3.12` is `python`).
   *
   * '''An added program is trusted code execution.''' It is held to the same checks as the built-in ones (no shell,
   * forbidden characters, path arguments inside the workspace, the environment allowlist), but it has no per-program
   * option rules, so it can do whatever its own arguments let it - and a build tool such as `sbt` runs the code in the
   * workspace by design. The denylist only stops the obvious ways to run anything (shells, interpreters, launchers);
   * it is not a boundary. Add only what the agent needs.
   *
   * A config that does not run commands at all (`shellAllowed = false`, the locked profile) refuses any name, rather
   * than report programs added that can never run (#1761).
   *
   * @param names program names separated by commas or whitespace
   * @return the widened config, or why a name was refused
   */
  def withExtraCommands(names: String): Either[String, WorkspaceSandboxConfig] =
    WorkspaceSandboxConfig.parseCommandNames(names).flatMap { extra =>
      if (extra.nonEmpty && !shellAllowed)
        Left(
          s"${WorkspaceSandboxConfig.ExtraCommandsEnvVar} names ${extra.toSeq.sorted.mkString(", ")}, but this sandbox " +
            "does not run commands at all (shellAllowed=false, the locked profile), so they could never run; unset " +
            s"${WorkspaceSandboxConfig.ExtraCommandsEnvVar} or choose a profile that runs commands"
        )
      else Right(copy(allowedCommands = allowedCommands ++ extra))
    }
}

object WorkspaceSandboxConfig {

  /**
   * The runner's environment variable naming programs to add to its profile's allowlist, separated by commas or
   * whitespace (for example `sbt`); see [[WorkspaceSandboxConfig.withExtraCommands]].
   */
  val ExtraCommandsEnvVar: String = "WORKSPACE_EXTRA_COMMANDS"

  /**
   * Programs [[WorkspaceSandboxConfig.withExtraCommands]] refuses to add: shells, interpreters, and launchers that run
   * another program or code named in their arguments (#1756, #1761, #1792). Any of them would let a command run
   * anything, which is what running commands without a shell prevents.
   *
   * A name is matched case-insensitively, without a `.exe` / `.com` extension and without a trailing version
   * (`Python3.12.EXE` is `python`). The list is a speed bump, not a boundary: no list names every program that can run
   * another, so every name added with `WORKSPACE_EXTRA_COMMANDS` must be one the host trusts to run any code.
   */
  val NeverAllowedCommands: Set[String] = Set(
    // Shells
    "sh",
    "bash",
    "zsh",
    "dash",
    "ksh",
    "csh",
    "tcsh",
    "fish",
    "busybox",
    "toybox",
    "cmd",
    "cmd.exe",
    "powershell",
    "powershell.exe",
    "pwsh",
    "pwsh.exe",
    // cmd.exe built-ins that run a program or a batch file (#1792)
    "call",
    "start",
    // Interpreters
    "python",
    "python2",
    "python3",
    "pythonw",
    "py",
    "perl",
    "ruby",
    "irb",
    "node",
    "nodejs",
    "deno",
    "bun",
    "php",
    "lua",
    "tclsh",
    "wish",
    "awk",
    "gawk",
    "mawk",
    "nawk",
    "java",
    "jshell",
    "jrunscript",
    "make",
    "gmake",
    "expect",
    // Launchers: run the program named in their arguments
    "env",
    "xargs",
    "nohup",
    "nice",
    "timeout",
    "exec",
    "eval",
    "setsid",
    "stdbuf",
    "chroot",
    "ionice",
    "flock",
    "watch",
    "script",
    "time",
    "systemd-run",
    "parallel",
    "taskset",
    "chrt",
    // Debuggers, remote and container tools
    "strace",
    "ltrace",
    "gdb",
    "ssh",
    "docker",
    "podman",
    // Privilege and namespace tools
    "sudo",
    "su",
    "runuser",
    "doas",
    "pkexec",
    "nsenter",
    "unshare",
    // Windows script and program hosts
    "wsl",
    "cscript",
    "wscript",
    "mshta",
    "rundll32"
  )

  private val CommandName = "[A-Za-z0-9._+-]+".r

  /** A version a program name may end in (`python3.12`, `perl5.36`, `lua5.4`, `gdb-12`), ignored by the denylist. */
  private val TrailingVersion = "[-.]?[0-9][0-9.]*$".r

  /** Extensions of the files Windows runs through cmd.exe, outside the runner's cmd.exe checks (BatBadBut, #1761). */
  private val BatchExtensions = Seq(".bat", ".cmd")

  /** Whether `name` is on [[NeverAllowedCommands]], in any case, with any `.exe` / `.com` extension or version. */
  private def neverAllowed(name: String): Boolean = {
    val key         = programKey(name, windows = true)
    val unversioned = TrailingVersion.replaceFirstIn(key, "")
    NeverAllowedCommands.contains(key) || (unversioned.nonEmpty && NeverAllowedCommands.contains(unversioned))
  }

  private def parseCommandNames(names: String): Either[String, Set[String]] = {
    val parsed = Option(names).getOrElse("").split("[,\\s]+").toSeq.map(_.trim).filter(_.nonEmpty)
    parsed
      .collectFirst {
        case name if !CommandName.matches(name) || name.startsWith(".") || name.startsWith("-") =>
          s"'$name' is not a bare program name"
        case name if BatchExtensions.exists(programKey(name, windows = true).endsWith) =>
          s"'$name' is a Windows batch file, which Windows runs through cmd.exe, so it cannot be added to the allowlist"
        case name if neverAllowed(name) =>
          s"'$name' runs other programs, so it cannot be added to the allowlist"
      }
      .toLeft(parsed.toSet)
  }

  /**
   * The name an allowlist entry or a command's first word is matched by (#1790, #1761). On Windows, case does not
   * matter and `sort.exe` or `SORT.COM` is `sort`, so the runner's per-program rules for `sort` apply to it;
   * elsewhere the name is matched as written.
   */
  private[llm4s] def programKey(name: String, windows: Boolean): String =
    if (!windows) name
    else {
      val lower = name.toLowerCase(java.util.Locale.ROOT)
      Seq(".exe", ".com").find(ext => lower.length > ext.length && lower.endsWith(ext)) match {
        case Some(ext) => lower.dropRight(ext.length)
        case None      => lower
      }
    }

  /**
   * Read-only command allowlist: safe, non-destructive commands suitable for
   * inspection and navigation. This is the default for new sandbox configs.
   *
   * The list names programs; the runner also refuses the options through which some of them would write, delete
   * or run another program (`find -exec`, `git -c`, `sort -o`, a second `uniq` operand, git subcommands other than
   * reads), path arguments that lead outside the workspace, and environment variables other than locale ones.
   * See `docs/reference/workspace-sandbox.md#command-policy`.
   */
  val ReadOnlyCommands: Set[String] = Set(
    // POSIX / common Unix
    "ls",
    "cat",
    "grep",
    "pwd",
    "echo",
    "git",
    "find",
    "head",
    "tail",
    "wc",
    "sort",
    "uniq",
    "diff",
    "whoami",
    "hostname",
    // Windows equivalents / built-ins
    "dir",
    "type",
    "findstr"
  )

  /**
   * Read-write command allowlist: extends [[ReadOnlyCommands]] with commands
   * that can create, modify, or delete files. Opt-in; use when the agent
   * legitimately needs write access to the workspace.
   */
  val ReadWriteCommands: Set[String] = ReadOnlyCommands ++ Set(
    // POSIX write commands
    "cp",
    "mv",
    "mkdir",
    "rm",
    "touch",
    "chmod",
    // Windows write commands
    "copy",
    "move"
  )

  val DefaultLimits: WorkspaceLimits = WorkspaceLimits(
    maxFileSize = 1048576L, // 1MB
    maxDirectoryEntries = 500,
    maxSearchResults = 100,
    maxOutputSize = 1048576L // 1MB
  )

  val DefaultExclusions: List[String] = List(
    "**/node_modules/**",
    "**/.git/**",
    "**/dist/**",
    "**/build/**",
    "**/.venv/**",
    "**/target/**",
    "**/__pycache__/**",
    "**/vendor/**"
  )

  /** Locked-down sandbox: read-only file ops, no shell, strict limits */
  val LockedDown: WorkspaceSandboxConfig = WorkspaceSandboxConfig(
    limits = DefaultLimits,
    excludePatterns = DefaultExclusions,
    shellAllowed = false,
    defaultCommandTimeout = 10.seconds,
    readOnlyPaths = Nil,
    allowedPaths = Nil,
    networkAllowed = false,
    allowedCommands = ReadOnlyCommands
  )

  /** Default permissive sandbox (current behavior). Allows both read-only and write commands. */
  val Permissive: WorkspaceSandboxConfig = WorkspaceSandboxConfig(allowedCommands = ReadWriteCommands)

  implicit val rw: ReadWriter[WorkspaceSandboxConfig] = macroRW

  /**
   * Parse a sandbox profile name into a concrete config.
   *
   * Valid names (case-insensitive, trimmed):
   *   - permissive or empty string  -> [[Permissive]]
   *   - locked or locked-down       -> [[LockedDown]]
   *
   * Unknown names return Left with an error message instead of silently
   * falling back, so that typos like strict do not weaken the sandbox.
   */
  def fromProfileName(name: String): Either[String, WorkspaceSandboxConfig] = {
    val normalized = if (name == null) "" else name.trim.toLowerCase
    normalized match {
      case "" | "permissive"        => Right(Permissive)
      case "locked" | "locked-down" => Right(LockedDown)
      case other                    => Left("Unknown sandbox profile: '" + other + "'")
    }
  }

  /**
   * Validates the config; returns Left with error message if invalid.
   */
  def validate(config: WorkspaceSandboxConfig): Either[String, Unit] = {
    def check(cond: Boolean, msg: String): Either[String, Unit] =
      if (cond) Right(()) else Left(msg)

    for {
      _ <- check(config.limits.maxFileSize > 0, "limits.maxFileSize must be positive")
      _ <- check(config.limits.maxDirectoryEntries > 0, "limits.maxDirectoryEntries must be positive")
      _ <- check(config.limits.maxSearchResults > 0, "limits.maxSearchResults must be positive")
      _ <- check(config.limits.maxOutputSize > 0, "limits.maxOutputSize must be positive")
      _ <- check(config.defaultCommandTimeout > Duration.Zero, "defaultCommandTimeout must be positive")
      _ <- check(config.defaultCommandTimeout <= 1.hour, "defaultCommandTimeout must be at most 1 hour")
    } yield ()
  }
}
