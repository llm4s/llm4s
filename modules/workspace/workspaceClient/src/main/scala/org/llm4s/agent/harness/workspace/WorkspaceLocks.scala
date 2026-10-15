package org.llm4s.agent.harness.workspace

import org.llm4s.agent.graph.{ GraphError, StateCheck }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.agent.harness.{ BackendRefused, FileBackend, VPath }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ProcessingError, ValidationError }
import org.llm4s.shared.{ FileSnapshotResponse, WorkspaceAgentException }
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * The locks of a workspace's backends (design §4.33). A file mutation locks `files/workspace/<id>/<path>`
 * and a command `files/workspace/<id>`, so, by C2's prefix rule, a command conflicts with every edit in its
 * workspace and a batch's commands and edits apply in call order. They are under C3's
 * `FileBackend.LockRoot`, as `FileBackend.resolved` requires of a resolved backend's locks.
 */
@Experimental
object WorkspaceLocks:

  /** The root of every workspace's locks, `files/workspace`: what a resolved sandbox declares. */
  val AllWorkspaces: LockKey = FileBackend.LockRoot / "workspace"

  /** The lock of everything in the workspace `id`: what a command there holds. */
  def workspaceLock(id: String): LockKey = AllWorkspaces / id

  /** The lock a mutation of `path` in the workspace `id` holds. */
  def fileLock(id: String, path: VPath): LockKey = workspaceLock(id) / path.value

  private val ValidId = "[A-Za-z0-9._-]{1,128}".r

  /** `id`, if it can name a workspace in a lock key: 1 to 128 of `A-Z a-z 0-9 . _ -`. */
  private[workspace] def checkedId(id: String): Result[String] =
    if id.length <= 128 && ValidId.matches(id) then Right(id)
    else Left(ValidationError("workspace id", "must be 1 to 128 characters of A-Z, a-z, 0-9, '.', '_' or '-'"))

/** Calls to a `WorkspaceAgentInterface`, its exceptions sorted into refusals and failures. */
private[workspace] object WorkspaceCalls:

  /** The runner's codes for a path that is not there. */
  private val NotFound = Set("PATH_NOT_FOUND", "FILE_NOT_FOUND", "DIRECTORY_NOT_FOUND")

  /** The runner's codes for a refusal the model should see: the request, not the workspace, is at fault. */
  private val Refusals = Set(
    "NOT_A_FILE",
    "PATH_ESCAPE_ATTEMPT",
    "PATH_NOT_ALLOWED",
    "PATH_READ_ONLY",
    "SIZE_LIMIT_EXCEEDED",
    "DIRECTORY_NOT_EMPTY",
    "PARENT_NOT_A_DIRECTORY",
    "INVALID_ARGUMENT",
    "FILE_EXISTS"
  )

  /** The workspace path of `path`: `/` is the root, `.`; `/src/a.txt` is `src/a.txt`. */
  def relative(path: VPath): String = if path == VPath.root then "." else path.value.drop(1)

  /**
   * `body`'s result; a runner refusal of `path` as a [[BackendRefused]], a lost compare-and-set as
   * `GraphError.EffectReplayRefused` (it wrote nothing), and anything else - a transport failure, an I/O
   * error - as a failure whose outcome is unknown, so the task fails and `recover` probes the workspace.
   */
  def call[A](operation: String, path: VPath)(body: => A): Result[A] =
    attempt(operation)(body).left.map {
      case WorkspaceFailure(e) if NotFound(e.code)            => BackendRefused.notFound(operation, path)
      case WorkspaceFailure(e) if e.code == "NOT_A_DIRECTORY" => BackendRefused.notADirectory(operation, path)
      case WorkspaceFailure(e) if Refusals(e.code)            => BackendRefused.at(operation, path, e.error)
      case WorkspaceFailure(e) if e.code == "HASH_MISMATCH" =>
        GraphError.EffectReplayRefused(s"file.$operation", s"the workspace file changed under it: ${e.error}")
      case other => other
    }

  /** `body`, a workspace exception as a [[WorkspaceFailure]] for the caller to sort, any other throw a failure. */
  def attempt[A](operation: String)(body: => A): Result[A] =
    Try(body) match
      case Success(a)                          => Right(a)
      case Failure(e: WorkspaceAgentException) => Left(WorkspaceFailure(e))
      case Failure(e) => Left(ProcessingError(s"workspace.$operation", s"the workspace call failed: $e", Some(e)))

  /** A runner snapshot's `hash` as C4's [[StateCheck]], or a failure for a value the runner cannot have sent. */
  def stateCheck(hash: String): Result[StateCheck] =
    if hash == FileSnapshotResponse.Kind.Absent then Right(StateCheck.absent)
    else if hash == FileSnapshotResponse.Kind.Directory || Sha256.matches(hash) then
      // compared, never interpreted: the runner's digest is in StateCheck's own format
      Try(upickle.default.read[StateCheck](ujson.Str(hash))).toEither.left.map(e =>
        ProcessingError("workspace.snapshot", s"the workspace sent an unreadable hash: ${e.getMessage}", Some(e))
      )
    else Left(ProcessingError("workspace.snapshot", s"the workspace sent an unknown hash '${hash.take(80)}'"))

  private val Sha256 = "sha256:[0-9a-f]{64}".r

/** A `WorkspaceAgentException` the runner raised, before a caller sorts it; never returned to a tool as is. */
final private[workspace] case class WorkspaceFailure(exception: WorkspaceAgentException) extends LLMError:
  override val message: String       = s"${exception.code}: ${exception.error}"
  override def code: Option[String] = Some(exception.code)
