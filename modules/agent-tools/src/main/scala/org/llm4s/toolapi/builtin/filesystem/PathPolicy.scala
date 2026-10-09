package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.{ Files, LinkOption, Path, Paths }
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The one path-containment rule shared by the file tools and, for the file-like arguments of a command, the
 * shell tool.
 *
 * A path is judged by where it really is, not by how it is spelled, and under both of the ways an operating system
 * applies `..` that comes after a symbolic link:
 *
 *   - '''physically''' ([[realPath]]), as POSIX `open` does, and so a program the shell tool starts on Linux or
 *     macOS: the path is made absolute and resolved one component at a time from the root, a symbolic link replaced
 *     by its real target (`toRealPath`) and `..` going to the parent of the location resolved so far, so `link/..`
 *     is the parent of the link's target;
 *   - '''lexically''' ([[lexicalRealPath]]), as Windows does: the Win32 path functions, and so Java's `WindowsPath`,
 *     remove `..` as text before any link is followed, so `link/..` is the directory holding the link; the links of
 *     the resulting path are then resolved as above.
 *
 * A path is allowed only when both locations are inside an allowed entry and neither is inside a blocked one. The
 * rule is the same on every platform and never weaker than the reading the OS really uses. Its cost is that a path
 * whose two readings differ, one inside and one outside, is refused even where the OS would use the inside one: with
 * `data/l -> data/a/b`, `data/l/../../x` is `data/x` on POSIX and `x` beside `data` on Windows, and it is refused
 * everywhere. Only a `..` that comes after a symbolic link can make the two readings differ.
 *
 * A tail that does not exist yet, such as the target of a write, is kept as written (its `.` and `..` applied to
 * it). Allowed and blocked entries are resolved the same way, and the comparison is `Path.startsWith`, which
 * compares whole path components: `/srv/data` contains `/srv/data/x` and does not contain `/srv/data-secret/x`.
 *
 * The file tools remove `.` and `..` from the path they are given as text before calling this, and then open the
 * location it returns, so for them `data/link/../x` is `data/x`, the two readings agree, and what they judge is what
 * they open. The shell tool and `isPathAllowed` pass the path as given, because the program (or the caller) hands
 * that spelling to the OS, which applies `..` in one of the two ways above.
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
   * The path with every symbolic link resolved and each `..` applied where POSIX applies it, to the location resolved
   * so far (the physical reading), or `Left` when it cannot be resolved (a dangling link, or no existing ancestor).
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
   * The path with `.` and `..` removed as text first, as Windows does, and then every symbolic link resolved (the
   * lexical reading), or `Left` when it cannot be resolved.
   */
  def lexicalRealPath(path: Path): Either[String, Path] =
    Try(path.toAbsolutePath.normalize()).toEither.left
      .map(e => s"cannot make '$path' absolute: ${e.getClass.getSimpleName}")
      .flatMap(realPath)

  /**
   * The physical real path of `path` ([[realPath]]) when the policy allows it, otherwise `None`. Both readings, the
   * physical and the lexical one, must be inside an allowed root, and neither may be inside a blocked one.
   *
   * @param allowed roots both readings must be inside (`None` means no root is required)
   * @param blocked roots that neither reading, nor the path's normalised spelling, may be inside; they win over
   *                `allowed`
   */
  def resolve(path: Path, allowed: Option[Seq[String]], blocked: Seq[String]): Option[Path] = {
    val readings = for {
      physical <- realPath(path)
      lexical  <- lexicalRealPath(path)
    } yield (physical, lexical)
    readings.toOption.collect {
      case (physical, lexical) if permitted(path.toAbsolutePath.normalize(), physical, lexical, allowed, blocked) =>
        physical
    }
  }

  private def permitted(
    spelled: Path,
    physical: Path,
    lexical: Path,
    allowed: Option[Seq[String]],
    blocked: Seq[String]
  ): Boolean = {
    val locations = Seq(physical, lexical)
    !blocked.exists(entry => isBlockedBy(entry, spelled +: locations)) &&
    allowed.forall(roots => locations.forall(location => roots.exists(root => isInside(root, location))))
  }

  /** A configured entry as its absolute form and its real form. */
  private def entry(configured: String): Option[(Path, Path)] =
    Try(Paths.get(configured)).toOption.map { path =>
      val lexical = path.toAbsolutePath.normalize()
      (lexical, realPath(path).getOrElse(lexical))
    }

  private def isBlockedBy(configured: String, locations: Seq[Path]): Boolean =
    entry(configured).exists { case (blockedLexical, blockedReal) =>
      locations.exists(location => location.startsWith(blockedLexical) || location.startsWith(blockedReal))
    }

  private def isInside(configured: String, real: Path): Boolean =
    entry(configured).exists { case (_, rootReal) => real.startsWith(rootReal) }
}
