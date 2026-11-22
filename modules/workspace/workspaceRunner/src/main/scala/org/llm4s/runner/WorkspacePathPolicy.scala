package org.llm4s.runner

import org.llm4s.shared.{ WorkspaceAgentException, WorkspaceSandboxConfig }

import java.nio.file.{ Files, LinkOption, Path }
import scala.util.Try

/**
 * A path the sandbox refused: the path as the caller wrote it, the protocol's error code (`PATH_ESCAPE_ATTEMPT`,
 * `PATH_NOT_ALLOWED`, `PATH_READ_ONLY`) and why.
 */
final private[runner] case class PathRefusal(path: String, code: String, message: String) {

  /** The refusal as the runner's protocol reports it. */
  def toException: WorkspaceAgentException = new WorkspaceAgentException(message, code, None)
}

/**
 * '''The path policy''' (design §4.33, #870): the one check every file operation, walk entry and command working
 * directory goes through, as a value: a [[PathRefusal]] naming the refused path, never an exception. The runner turns
 * a refusal into its protocol's error at the edge ([[PathRefusal.toException]]).
 *
 * Every check is made on the physical location an operation acts on: what the path names with each `..` applied and
 * every symbolic link resolved ([[effective]]), or for an operation on an entry itself (a delete, a snapshot of a link
 * as itself), its directory resolved plus its own name ([[entryOf]]). A link inside the workspace that points outside
 * it, or out of `allowedPaths`, or into `readOnlyPaths`, is judged where it points, so it cannot be used to escape a
 * rule; a link to nothing is judged where a write through it would create its target.
 *
 *  - '''The workspace.''' The location must be inside the workspace root (`PATH_ESCAPE_ATTEMPT`).
 *  - '''`allowedPaths`'''. With any, the location must be inside one: inside what it names, or inside the entry itself
 *    when it is a link (`PATH_NOT_ALLOWED`).
 *  - '''`readOnlyPaths`'''. A change may not be inside one, as either (`PATH_READ_ONLY`).
 *  - '''`.git`.''' A change may not name `.git` or a path inside one (`PATH_NOT_ALLOWED`, #1721): git reads its
 *    configuration, hooks and attributes there and runs the programs they name.
 *
 * Path handling is platform-sensitive (separators, case, links), so its spec runs on Windows too: `workspaceRunner` is
 * one of `project/PlatformTests.scala`'s whole modules.
 *
 * @param rootPath the workspace root, absolute and normalized
 * @param config   the sandbox whose `allowedPaths` and `readOnlyPaths` apply, each relative to the root
 */
final private[runner] class WorkspacePathPolicy(rootPath: Path, config: WorkspaceSandboxConfig) {
  import WorkspacePathPolicy.Access

  // The sandbox's allowedPaths and readOnlyPaths, resolved against the root once; one that leads outside the root is
  // the root's own business to refuse, so it is resolved as given and simply never matches a workspace path. Each is
  // compared by its text and, through `effective`, by what it names on disk when the check runs.
  private val allowedRoots: List[Path]  = config.allowedPaths.map(p => rootPath.resolve(p).normalize())
  private val readOnlyRoots: List[Path] = config.readOnlyPaths.map(p => rootPath.resolve(p).normalize())

  /**
   * `relativePath` as text, against the root: refused (`PATH_ESCAPE_ATTEMPT`) when the platform cannot parse it (a NUL;
   * on Windows `C:\x\f:s`, `x*`) or when `..` takes it above the root. What it names on disk is [[check]]'s.
   */
  def lexical(relativePath: String): Either[PathRefusal, Path] =
    Try(rootPath.resolve(relativePath).normalize()).toOption
      .toRight(
        PathRefusal(
          relativePath,
          "PATH_ESCAPE_ATTEMPT",
          s"Path '${relativePath.replace("\u0000", "\\0")}' is not a valid path in the workspace"
        )
      )
      .filterOrElse(
        _.startsWith(rootPath),
        PathRefusal(relativePath, "PATH_ESCAPE_ATTEMPT", s"Path '$relativePath' attempts to escape the workspace")
      )

  /**
   * What `path` names on disk: its nearest existing ancestor (itself included) with every link resolved, and the rest
   * of the path beneath that. A link that cannot be resolved (dangling) is followed by hand, so a dangling one
   * (`out -> /tmp/new-file`, whose target a write would create) is resolved to where it points, never taken as a plain
   * missing name beneath the workspace. More than 40 links (a loop) is refused.
   */
  def effective(path: Path): Either[PathRefusal, Path] = effective(path, 0)

  private def effective(path: Path, hops: Int): Either[PathRefusal, Path] =
    if (hops > 40) Left(PathRefusal(path.toString, "PATH_ESCAPE_ATTEMPT", "Too many levels of symbolic links"))
    else {
      @scala.annotation.tailrec
      def nearest(p: Path, rest: List[Path]): (Path, List[Path]) =
        if (p == null) (p, rest)
        else if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) (p, rest)
        else nearest(p.getParent, Option(p.getFileName).toList ++ rest)
      val (existing, rest) = nearest(path, Nil)
      val base: Either[PathRefusal, Path] = Option(existing) match {
        case None => Right(path.getRoot)
        case Some(e) =>
          Try(e.toRealPath()).toOption match {
            case Some(real) => Right(real)
            // it exists but does not resolve: a dangling link (or one beneath a dangling link) - follow it by hand
            case None if Files.isSymbolicLink(e) =>
              Try(Files.readSymbolicLink(e)).toOption
                .toRight(PathRefusal(e.toString, "PATH_ESCAPE_ATTEMPT", s"Link '$e' cannot be read"))
                .flatMap(target => effective(Option(e.getParent).fold(target)(_.resolve(target)).normalize(), hops + 1))
            case None =>
              Option(e.getParent).fold[Either[PathRefusal, Path]](Right(e))(parent =>
                effective(parent, hops + 1).map(_.resolve(e.getFileName))
              )
          }
      }
      base.map(b => rest.foldLeft(b)((acc, part) => acc.resolve(part)).normalize())
    }

  /**
   * The physical location of the entry `lex` names: its directory resolved through every link, plus its own name. An
   * operation on the entry itself (a delete, a snapshot of a link as itself) acts there; the root is its own entry.
   */
  def entryOf(lex: Path): Either[PathRefusal, Path] =
    if (lex == rootPath || lex.getParent == null) effective(lex)
    else effective(lex.getParent).map(_.resolve(lex.getFileName))

  /** Whether `place` is inside one of `roots`, by what each names on disk or as an entry itself. */
  private def within(place: Path, roots: List[Path]): Either[PathRefusal, Boolean] =
    roots.foldLeft[Either[PathRefusal, Boolean]](Right(false)) { (acc, r) =>
      acc.flatMap { found =>
        if (found) Right(true)
        else
          for {
            real  <- effective(r)
            entry <- entryOf(r)
          } yield place.startsWith(real) || place.startsWith(entry)
      }
    }

  /** The policy's checks on `place`, the physical location an operation on `relativePath` acts on. */
  def check(relativePath: String, place: Path, access: Access): Either[PathRefusal, Unit] =
    for {
      realRoot <- effective(rootPath)
      _ <- Either.cond(
        place.startsWith(realRoot),
        (),
        PathRefusal(relativePath, "PATH_ESCAPE_ATTEMPT", s"Path '$relativePath' leads outside the workspace")
      )
      allowed <- if (allowedRoots.isEmpty) Right(true) else within(place, allowedRoots)
      _ <- Either.cond(
        allowed,
        (),
        PathRefusal(
          relativePath,
          "PATH_NOT_ALLOWED",
          s"Path '$relativePath' is outside the sandbox's allowed paths (${config.allowedPaths.mkString(", ")})"
        )
      )
      readOnly <- if (access == Access.Write) within(place, readOnlyRoots) else Right(false)
      _ <- Either.cond(
        !readOnly,
        (),
        PathRefusal(
          relativePath,
          "PATH_READ_ONLY",
          s"Path '$relativePath' is read-only in this sandbox (${config.readOnlyPaths.mkString(", ")})"
        )
      )
      _ <- Either.cond(
        access != Access.Write || !CommandPolicy.namesGitMetadata(place, realRoot, realRoot),
        (),
        PathRefusal(
          relativePath,
          "PATH_NOT_ALLOWED",
          s"Path '$relativePath' names '.git' or a path inside one; git reads its configuration, hooks and attributes " +
            "there and runs the programs they name, so the file operations may not write it"
        )
      )
    } yield ()

  /**
   * `relativePath` for an operation that follows it (a read, a write through a link): checked on what it names on
   * disk, and returned as the lexical path the operation uses.
   */
  def resolvePath(relativePath: String, access: Access): Either[PathRefusal, Path] =
    for {
      lex   <- lexical(relativePath)
      place <- effective(lex)
      _     <- check(relativePath, place, access)
    } yield lex

  /**
   * `relativePath` for an operation on the entry itself, which never follows it (a delete, a snapshot of a link as
   * itself): checked on [[entryOf]], so a link whose target is outside the workspace can be removed, and an entry
   * reached through an aliased directory is judged where it really is.
   */
  def resolveEntry(relativePath: String, access: Access): Either[PathRefusal, Path] =
    for {
      lex   <- lexical(relativePath)
      place <- entryOf(lex)
      _     <- check(relativePath, place, access)
    } yield lex
}

private[runner] object WorkspacePathPolicy {

  /** What an operation does at a path: reads it, or changes it (writes, edits, deletes). */
  sealed trait Access
  object Access {
    case object Read  extends Access
    case object Write extends Access
  }
}
