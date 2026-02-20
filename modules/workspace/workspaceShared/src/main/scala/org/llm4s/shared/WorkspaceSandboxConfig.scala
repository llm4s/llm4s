package org.llm4s.shared

import upickle.default.{ ReadWriter, macroRW }

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
 * @param defaultCommandTimeoutSeconds Default timeout for shell commands
 * @param readOnlyPaths        Paths under workspace root that are read-only (writes denied)
 * @param allowedPaths         If non-empty, only these paths are accessible; if empty, whole workspace
 * @param networkAllowed       Documentation: whether network access from commands is assumed (Phase 2: enforce)
 */
final case class WorkspaceSandboxConfig(
  limits: WorkspaceLimits = WorkspaceSandboxConfig.DefaultLimits,
  excludePatterns: List[String] = WorkspaceSandboxConfig.DefaultExclusions,
  shellAllowed: Boolean = true,
  defaultCommandTimeoutSeconds: Int = 30,
  readOnlyPaths: List[String] = Nil,
  allowedPaths: List[String] = Nil,
  networkAllowed: Boolean = false
)

object WorkspaceSandboxConfig {

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
    defaultCommandTimeoutSeconds = 10,
    readOnlyPaths = Nil,
    allowedPaths = Nil,
    networkAllowed = false
  )

  /** Default permissive sandbox (current behavior) */
  val Permissive: WorkspaceSandboxConfig = WorkspaceSandboxConfig()

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
      _ <- check(config.defaultCommandTimeoutSeconds > 0, "defaultCommandTimeoutSeconds must be positive")
      _ <- check(config.defaultCommandTimeoutSeconds <= 3600, "defaultCommandTimeoutSeconds must be <= 3600")
    } yield ()
  }
}
