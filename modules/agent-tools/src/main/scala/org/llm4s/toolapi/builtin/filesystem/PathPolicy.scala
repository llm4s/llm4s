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
  def resolve(path: Path, allowed: Option[Seq[String]], blocked: Seq[String]): Option[Path] =
    resolve(path, prepare(allowed, blocked))

  /**
   * As the `resolve` above, with the configured entries resolved once, by
   * [[prepare]], for a caller that checks many paths in a row (the arguments of one shell command).
   */
  def resolve(path: Path, entries: Entries): Option[Path] =
    readings(path).filter(permits(_, entries)).map(_.physical)

  /**
   * The three locations the rule compares for a path: its absolute, normalised spelling, its physical reading
   * ([[realPath]]) and its lexical reading ([[lexicalRealPath]]).
   */
  final case class Readings(spelled: Path, physical: Path, lexical: Path) {

    /** The lookups [[childReadings]] spends: one per distinct directory the child is looked up in. */
    def probes: Int = if (physical == lexical) 1 else 2
  }

  /** The readings of `path`, or `None` when it cannot be resolved (see [[realPath]]). */
  def readings(path: Path): Option[Readings] =
    (for {
      spelled  <- Try(path.toAbsolutePath.normalize()).toEither
      physical <- realPath(path)
      lexical  <- lexicalRealPath(path)
    } yield Readings(spelled, physical, lexical)).toOption

  /** Whether the policy allows a path with these readings (both readings allowed, none of the three blocked). */
  def permits(readings: Readings, entries: Entries): Boolean =
    permitted(readings.spelled, readings.physical, readings.lexical, entries)

  /**
   * Whether `name` is one plain path component on every platform, so that `dir.resolve(name)` adds exactly that
   * component to `dir` and the OS looks it up in `dir` as written: not empty, `.` or `..`; no `/` or `\`; and none of
   * what Windows reads specially - `:` (an alternate data stream, or a drive-relative path such as `C:x`), a trailing
   * `.` or space (which Windows drops), `*?"<>|` and control characters (invalid), or a name beginning with a reserved
   * device name (`CON`, `PRN`, `AUX`, `NUL`, `COM`, `LPT`, matched as a prefix, whatever the case, which also covers
   * `nul.txt` and `COM1`). These are refused on every platform, not only Windows, so the answer does not depend on
   * where it runs. A name that is not one only loses the shortcut of [[childReadings]]; it is checked in full.
   */
  def isSingleName(name: String): Boolean =
    name.nonEmpty && name != "." && name != ".." &&
      !name.exists(c => c < ' ' || c == '\u007f' || "/\\:*?\"<>|".indexOf(c.toInt) >= 0) &&
      !name.endsWith(".") && !name.endsWith(" ") && {
        val upper = name.toUpperCase(java.util.Locale.ROOT)
        !ReservedDevicePrefixes.exists(prefix => upper.startsWith(prefix))
      }

  private val ReservedDevicePrefixes = Seq("CON", "PRN", "AUX", "NUL", "COM", "LPT")

  /** Whether anything (a file, a directory, a link, dangling or not) is at `path`, as [[realPath]] asks it. */
  def entryExists(path: Path): Boolean = Files.exists(path, LinkOption.NOFOLLOW_LINKS)

  /** What [[childReadings]] found. */
  sealed trait Child
  object Child {

    /** Nothing is at the child in either reading: its readings are the parent's with the name appended. */
    final case class Absent(readings: Readings) extends Child

    /** Something is there (or the name is not one plain component, or not a valid path): resolve it in full. */
    case object Resolve extends Child
  }

  /**
   * The readings of `parent / name` from the parent's, with one lookup per reading ([[Readings.probes]]) instead of
   * one or two per component of the whole path - the cheap path for the many short-option tails of a shell command.
   *
   * '''Why it is exact.''' When `name` is one plain component ([[isSingleName]]) and nothing is at
   * `parent.physical / name` or at `parent.lexical / name` (asked with `exists` - the same `Files.exists`, without
   * following links, that [[realPath]] asks), the result is `Absent(r)` with `r` equal to `readings(dir.resolve(name))`
   * for the absolute `dir` whose readings `parent` are:
   *
   *  - `dir.resolve(name)` has `dir`'s components followed by `name`, so [[realPath]] folds over `dir`'s components
   *    first, reaching `parent.physical`, then takes `name`: nothing is at `parent.physical / name`, so it keeps it as
   *    written, and the physical reading is `parent.physical / name`;
   *  - `name` is neither `.` nor `..`, so normalising `dir / name` normalises `dir` and keeps `name`, and
   *    [[lexicalRealPath]] gives `parent.lexical / name` the same way;
   *  - the spelling is `parent.spelled / name` for the same reason.
   *
   * So [[permits]] on `r` is exactly what [[resolve]] decides for `dir.resolve(name)` - not an approximation, and
   * whatever the length of the name: no assumption about how long a name a file system allows is made. A name that
   * exists, a link (dangling or not) included, is not answered here (`Resolve`), so a link is always resolved in full.
   * Where `exists` cannot tell (a name the file system cannot hold, a directory the process cannot search), it says
   * `false`, and [[realPath]] treats the name the same way. `exists` throwing, or a name that is not a valid path,
   * gives `Resolve`, and the full check then refuses what it cannot read. A file created between this lookup and the
   * program's open is the same race as for any path checked before a command runs.
   */
  def childReadings(parent: Readings, name: String, exists: Path => Boolean = entryExists): Child =
    if (!isSingleName(name)) Child.Resolve
    else
      Try {
        val physical = parent.physical.resolve(name)
        val lexical  = parent.lexical.resolve(name)
        val present  = exists(physical) || (lexical != physical && exists(lexical))
        if (present) Child.Resolve else Child.Absent(Readings(parent.spelled.resolve(name), physical, lexical))
      }.getOrElse(Child.Resolve)

  /**
   * The allowed and blocked entries of a policy, each as its absolute form and its real form (an entry that is not
   * a valid path is left out, so it allows and blocks nothing). They are resolved when this is built: a link
   * changed afterwards is not seen, so build one for each batch of checks, not once for good.
   */
  final class Entries private[PathPolicy] (
    private[PathPolicy] val allowed: Option[Seq[(Path, Path)]],
    private[PathPolicy] val blocked: Seq[(Path, Path)]
  )

  /** The entries of a policy, resolved now (see [[Entries]]). */
  def prepare(allowed: Option[Seq[String]], blocked: Seq[String]): Entries =
    new Entries(allowed.map(_.flatMap(entry)), blocked.flatMap(entry))

  private def permitted(spelled: Path, physical: Path, lexical: Path, entries: Entries): Boolean = {
    val locations = Seq(physical, lexical)
    !entries.blocked.exists { case (blockedLexical, blockedReal) =>
      (spelled +: locations).exists(location => location.startsWith(blockedLexical) || location.startsWith(blockedReal))
    } &&
    entries.allowed.forall(roots =>
      locations.forall(location => roots.exists { case (_, rootReal) => location.startsWith(rootReal) })
    )
  }

  /** A configured entry as its absolute form and its real form. */
  private def entry(configured: String): Option[(Path, Path)] =
    Try(Paths.get(configured)).toOption.map { path =>
      val lexical = path.toAbsolutePath.normalize()
      (lexical, realPath(path).getOrElse(lexical))
    }
}
