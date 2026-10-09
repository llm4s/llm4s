package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The one path-containment rule shared by the file tools and, for the file-like arguments of a command, the
 * shell tool.
 *
 * A path is judged by where it really is, not by how it is spelled. The path is made absolute and resolved the
 * way the operating system resolves it, one component at a time from the root: a symbolic link is replaced by
 * its real target (`toRealPath`), and `..` goes to the parent of the location resolved so far. So `link/..` is
 * the parent of the link's target, not the directory holding the link, as it is for `open` and for a program the
 * shell tool starts; `..` is never removed as text before links are resolved. A tail that does not exist yet,
 * such as the target of a write, is kept as written (its `.` and `..` applied to it). Allowed and blocked entries
 * are resolved the same way, and the comparison is `Path.startsWith`, which compares whole path components:
 * `/srv/data` contains `/srv/data/x` and does not contain `/srv/data-secret/x`.
 *
 * The file tools remove `.` and `..` from the path they are given as text before calling this, and then open the
 * location it returns, so for them `data/link/../x` is `data/x`: what they judge is what they open. The shell tool
 * and `isPathAllowed` pass the path as given, because the program (or the caller) hands that spelling to the OS.
 *
 * A symbolic link that cannot be resolved (a dangling link) is refused, since where it would lead is unknown.
 *
 * '''What this narrows and what it does not.''' The tools open the resolved path, not the one that was checked,
 * so a link swapped in after the check no longer redirects the open. A directory swapped for a link between the
 * resolution and the open is a race this does not close. A hard link is not a symbolic link: a hard link inside
 * an allowed directory to a file elsewhere is the file itself as far as any path check can tell, so it is not
 * contained.
 */
private[builtin] object PathPolicy {

  /**
   * The path with every symbolic link resolved, or `Left` when it cannot be resolved (a dangling link, or no
   * existing ancestor).
   */
  def realPath(path: Path): Either[String, Path] =
    Try(path.toAbsolutePath).toEither.left
      .map(e => s"cannot make '$path' absolute: ${e.getClass.getSimpleName}")
      .flatMap { absolute =>
        Option(absolute.getRoot).toRight(s"'$absolute' has no root").flatMap { root =>
          absolute.iterator().asScala.map(_.toString).foldLeft[Either[String, Path]](Right(root)) {
            case (Right(current), ".")  => Right(current)
            case (Right(current), "..") => Right(Option(current.getParent).getOrElse(current))
            case (Right(current), name) =>
              val next = current.resolve(name)
              if (Files.exists(next, LinkOption.NOFOLLOW_LINKS))
                Try(next.toRealPath()).toEither.left.map(e => s"cannot resolve '$next': ${e.getClass.getSimpleName}")
              else Right(next)
            case (failed, _) => failed
          }
        }
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
    realPath(path).toOption.filter { real =>
      !blocked.exists(entry => isBlockedBy(entry, lexical, real)) &&
      allowed.forall(roots => roots.exists(root => isInside(root, real)))
    }
  }

  /** A configured entry as its absolute form and its real form. */
  private def entry(configured: String): Option[(Path, Path)] =
    Try(Paths.get(configured)).toOption.map { path =>
      val lexical = path.toAbsolutePath.normalize()
      (lexical, realPath(path).getOrElse(lexical))
    }

  private def isBlockedBy(configured: String, lexical: Path, real: Path): Boolean =
    entry(configured).exists { case (blockedLexical, blockedReal) =>
      lexical.startsWith(blockedLexical) || real.startsWith(blockedReal) || real.startsWith(blockedLexical)
    }

  private def isInside(configured: String, real: Path): Boolean =
    entry(configured).exists { case (_, rootReal) => real.startsWith(rootReal) }
}
