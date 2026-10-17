package org.llm4s.agent.harness.workspace

import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.agent.harness.{ BackendRefused, ExecResult, FileContext, SandboxBackend, SandboxRunner, Text, VPath }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.shared.WorkspaceAgentInterface
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * Runs commands in a containerised workspace, for a [[SandboxBackend]] (design §4.33). It only runs: the
 * backend wraps every run in C4's `Unverifiable` guard, so a completed command replays its stored
 * [[ExecResult]] and one that may have run is refused, never run again. A command locks
 * `files/workspace/<id>` ([[WorkspaceLocks]]), so it conflicts with every file mutation in its workspace.
 *
 * The runner runs a command as a program and its arguments, never through a shell, and refuses what the
 * sandbox's command policy forbids; that refusal is a tool error. Each stream is kept to `maxOutputBytes`
 * characters by the runner while it reads, and cut again by the backend. A command the runner's timeout
 * killed is an outcome, exit code -1, recorded like any other.
 */
@Experimental
final class WorkspaceSandboxRunner private (val id: String, workspace: WorkspaceAgentInterface) extends SandboxRunner:

  def locks(workdir: VPath): Set[LockKey] = Set(WorkspaceLocks.workspaceLock(id))

  def run(
    command: String,
    workdir: VPath,
    timeout: FiniteDuration,
    maxOutputBytes: Int,
    ctx: FileContext
  ): Result[ExecResult] =
    WorkspaceCalls
      .attempt("execute")(
        workspace.executeCommand(
          command,
          Some(WorkspaceCalls.relative(workdir)),
          Some(timeout),
          None,
          // the runner bounds each stream by its encoded (JSON, UTF-8) bytes, so the budget holds while it captures
          Some(maxOutputBytes.toLong)
        )
      )
      .map(r => ExecResult(r.exitCode, r.stdout, r.stderr, r.isOutputTruncated))
      .left
      .flatMap {
        // the direct runner path reports a kill on timeout as an error; it ran, so it is an outcome
        case WorkspaceFailure(e) if e.code == "TIMEOUT" => Right(ExecResult(-1, "", e.error, truncated = false))
        // only the runner's refusals before the process starts; any other code (the transport's, an unknown one) leaves
        // the outcome unknown: a failure, so the task fails and the guard refuses an ambiguous replay
        case WorkspaceFailure(e) if WorkspaceSandboxRunner.Refusals(e.code) =>
          Left(BackendRefused("execute", Text.quoted(command), s"${e.code}: ${e.error}"))
        case other => Left(other)
      }

object WorkspaceSandboxRunner:

  /** The runner's codes for a command it refused before starting it: nothing ran. */
  private[workspace] val Refusals: Set[String] = Set(
    "SHELL_DISABLED",
    "WORKSPACE_BUSY",
    "INVALID_DIRECTORY",
    "EMPTY_COMMAND",
    "FORBIDDEN_CHARACTERS",
    "EXECUTABLE_NOT_ALLOWED",
    "EXECUTABLE_NOT_FOUND",
    "EXECUTABLE_PATH_NOT_ALLOWED",
    "ARGUMENT_NOT_ALLOWED",
    "ENVIRONMENT_NOT_ALLOWED",
    "GIT_CONFIG_NOT_ALLOWED",
    "PATH_ESCAPE_ATTEMPT",
    "PATH_NOT_ALLOWED",
    "PATH_NOT_FOUND",
    "INVALID_ARGUMENT"
  )

  /** The runner over `workspace`, named `id` in lock keys. */
  def apply(id: String, workspace: WorkspaceAgentInterface): Result[WorkspaceSandboxRunner] =
    WorkspaceLocks.checkedId(id).map(new WorkspaceSandboxRunner(_, workspace))

/** The [[SandboxBackend]] of a containerised workspace: `SandboxBackend` over a [[WorkspaceSandboxRunner]]. */
@Experimental
object WorkspaceSandboxBackend:

  /**
   * The sandbox of `workspace`, named `id` in lock keys, keeping at most `maxOutputBytes` of each stream (in
   * 1..`SandboxBackend.MaxOutputBytes`). One workspace is one tenant's: an application serving several
   * builds one runner per tenant and picks it with `SandboxBackend.resolved(_ => Set(WorkspaceLocks.AllWorkspaces))`
   * (rule 8).
   */
  def apply(
    id: String,
    workspace: WorkspaceAgentInterface,
    maxOutputBytes: Int = SandboxBackend.DefaultMaxOutputBytes
  ): Result[SandboxBackend] =
    if maxOutputBytes < 1 || maxOutputBytes > SandboxBackend.MaxOutputBytes then
      Left(ValidationError("maxOutputBytes", s"must be in 1..${SandboxBackend.MaxOutputBytes}, was $maxOutputBytes"))
    else WorkspaceSandboxRunner(id, workspace).map(SandboxBackend(_, maxOutputBytes))
