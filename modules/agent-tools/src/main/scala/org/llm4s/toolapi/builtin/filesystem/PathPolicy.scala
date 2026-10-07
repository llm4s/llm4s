package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.annotation.tailrec
import scala.util.Try

/**
 * The one path-containment rule shared by the file tools and, for the file-like arguments of a command, the
 * shell tool.
 *
 * A path is judged by where it really is, not by how it is spelled. The path is made absolute and normalised
 * (`.` and `..` removed), then every symbolic link is resolved (`toRealPath`); a tail that does not exist yet,
 * such as the target of a write, is kept as written after the nearest existing ancestor has been resolved.
 * Allowed and blocked entries are resolved the same way, and the comparison is `Path.startsWith`, which compares
 * whole path components: `/srv/data` contains `/srv/data/x` and does not contain `/srv/data-secret/x`.
 *
 * A symbolic link that cannot be resolved (a dangling link) is refused, since where it would lead is unknown.
 *
 * '''What this narrows and what it does not.''' The tools open the resolved path, not the one that was checked,
 * so a link swapped in after the check no longer redirects the open. A directory swapped for a link between the
 * resolution and the open is a race this does not close.
 */
private[builtin] object PathPolicy {

  /**
   * The path with every symbolic link resolved, or `Left` when it cannot be resolved (a dangling link, or no
   * existing ancestor).
   */
  def realPath(path: Path): Either[String, Path] = {
    val lexical = path.toAbsolutePath.normalize()

    @tailrec
    def go(current: Path, tail: List[Path]): Either[String, Path] =
      if (current == null) Left("no existing ancestor")
      else if (Files.exists(current, LinkOption.NOFOLLOW_LINKS))
        Try(current.toRealPath()).toEither.left
          .map(e => s"cannot resolve '$current': ${e.getClass.getSimpleName}")
          .map(base => tail.foldLeft(base)((resolved, name) => resolved.resolve(name)))
      else go(current.getParent, current.getFileName :: tail)

    go(lexical, Nil)
  }

  /**
   * The real path of `path` when the policy allows it, otherwise `None`.
   *
   * @param allowed roots the real path must be inside (`None` means no root is required)
   * @param blocked roots the path must not be inside, judged on both its spelling and its real location; they win
   *                over `allowed`
   */
  def resolve(path: Path, allowed: Option[Seq[String]], blocked: Seq[String]): Option[Path] = {
    val lexical = path.toAbsolutePath.normalize()
    realPath(lexical).toOption.filter { real =>
      !blocked.exists(entry => isBlockedBy(entry, lexical, real)) &&
      allowed.forall(roots => roots.exists(root => isInside(root, real)))
    }
  }

  /** A configured entry as its absolute form and its real form. */
  private def entry(configured: String): Option[(Path, Path)] =
    Try(Paths.get(configured).toAbsolutePath.normalize()).toOption.map(lexical =>
      (lexical, realPath(lexical).getOrElse(lexical))
    )

  private def isBlockedBy(configured: String, lexical: Path, real: Path): Boolean =
    entry(configured).exists { case (blockedLexical, blockedReal) =>
      lexical.startsWith(blockedLexical) || real.startsWith(blockedReal) || real.startsWith(blockedLexical)
    }

  private def isInside(configured: String, real: Path): Boolean =
    entry(configured).exists { case (_, rootReal) => real.startsWith(rootReal) }
}
