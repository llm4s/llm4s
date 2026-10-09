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
)

object WorkspaceSandboxConfig {

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
