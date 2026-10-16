package org.llm4s.agent.harness

import org.llm4s.types.Result

import java.util.regex.Pattern

/**
 * Reads over a map of files whose directories are implicit - a path is a directory when an entry lies
 * beneath it - shared by [[StateFileBackend]] and [[StoreFileBackend]]. Every map they hand it has been
 * through [[validated]], so its operations take the tree as well-formed.
 */
private[harness] object FileTree:

  /** The bounds a backend keeps on what it stores, which [[validated]] checks stored state against. */
  final case class Bounds(maxFiles: Int, maxEntryBytes: Long, maxTotalBytes: Long)

  /**
   * `all`, if it is a tree this backend could have written; otherwise a refusal of kind `Corrupt` naming
   * the problem. A backend bounds and shapes what it writes itself, but another declared writer of its
   * state key, or a direct user of its store, may have stored anything, so every operation that uses the
   * stored files runs this first, at the one point the backend reads them. In order, each check bounded
   * by the ones before it:
   *
   *  - at most `maxFiles` entries, by the map's size alone, before anything is scanned;
   *  - each entry's content at most `maxEntryBytes` as a JSON string, and all entries (paths and
   *    contents) at most `maxTotalBytes`, counted only until a bound is passed;
   *  - each entry text under the one text rule (no NUL, no unpaired surrogate);
   *  - no path both a file and a directory: no entry beneath another, and none at the root, which is
   *    always a directory.
   *
   * Keys are already `VPath`s: the state key's reader validates them, and the store backend refuses a
   * key that is not one before it builds the map. A delete does not run this, so the entry a refusal
   * names can be deleted, and the tree repaired.
   */
  def validated(operation: String, all: Map[VPath, FileEntry], bounds: Bounds): Result[Map[VPath, FileEntry]] =
    if all.size > bounds.maxFiles then
      Left(
        corrupt(operation, None, s"holds ${all.size} files, more than the limit of ${bounds.maxFiles}; delete some")
      )
    else
      val entries = all.iterator
      var total   = 0L
      var problem = Option.empty[BackendRefused]
      while problem.isEmpty && entries.hasNext do
        val (path, entry) = entries.next()
        val ancestor      = Iterator.iterate(path.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten
        if path == VPath.root then
          problem = Some(corrupt(operation, Some(path), "holds a file at the root, which is always a directory"))
        else if Text.jsonBytesOver(entry.content, math.min(bounds.maxEntryBytes, bounds.maxTotalBytes - total)) then
          problem = Some(
            corrupt(
              operation,
              Some(path),
              s"is larger than the limit of ${bounds.maxEntryBytes} bytes a file, ${bounds.maxTotalBytes} in all"
            )
          )
        else
          total += Text.jsonBytes(path.value) + Text.jsonBytes(entry.content)
          problem = Text
            .binaryReason(entry.content)
            .map(reason => corrupt(operation, Some(path), reason))
            .orElse(
              ancestor
                .find(all.contains)
                .map(file =>
                  corrupt(operation, Some(file), s"is both a file and a directory (${path.value} is beneath it)")
                )
            )
            .orElse(
              Option.when(total > bounds.maxTotalBytes)(
                corrupt(operation, Some(path), s"takes the files past their limit of ${bounds.maxTotalBytes} bytes")
              )
            )
      problem.toLeft(all)

  /** A refusal of stored state: `path` names the entry to delete, where there is one. */
  def corrupt(operation: String, path: Option[VPath], problem: String): BackendRefused =
    path.fold(
      BackendRefused(operation, "files", s"the stored files are corrupt: $problem", BackendRefused.Kind.Corrupt)
    )(p =>
      BackendRefused.at(
        operation,
        p,
        s"the stored files are corrupt: $problem; delete it to repair them",
        BackendRefused.Kind.Corrupt
      )
    )

  def isDirectory(all: Map[VPath, FileEntry], path: VPath): Boolean =
    path == VPath.root || all.keysIterator.exists(p => p != path && p.isWithin(path))

  def fileOf(operation: String, all: Map[VPath, FileEntry], path: VPath): Result[FileEntry] =
    all.get(path) match
      case Some(entry)                    => Right(entry)
      case None if isDirectory(all, path) => Left(BackendRefused.at(operation, path, "is a directory"))
      case None                           => Left(BackendRefused.notFound(operation, path))

  /** `path` may be written as a file: not the root, not a directory, not beneath a file. */
  def writable(operation: String, all: Map[VPath, FileEntry], path: VPath): Result[Unit] =
    def refuse(reason: String) = Left(BackendRefused.at(operation, path, reason))
    val ancestors              = Iterator.iterate(path.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten
    if path == VPath.root then refuse("is the root directory")
    else if !all.contains(path) && isDirectory(all, path) then refuse("is a directory")
    else if ancestors.exists(all.contains) then refuse("is beneath a file")
    else Right(())

  /** `path` may be deleted: it is a file. Run on the stored map as it is, so a corrupt entry can go. */
  def deletable(all: Map[VPath, FileEntry], path: VPath): Result[Unit] =
    if all.contains(path) then Right(())
    else if isDirectory(all, path) then
      Left(BackendRefused.at("delete", path, "is a directory; delete the files in it"))
    else Left(BackendRefused.notFound("delete", path))

  def ls(all: Map[VPath, FileEntry], path: VPath, limit: Int): Result[Listing[FileInfo]] =
    for
      _ <- Text.checkLimitAt("ls", path, limit)
      _ <-
        if all.contains(path) then Left(BackendRefused.notADirectory("ls", path))
        else if !isDirectory(all, path) then Left(BackendRefused.notFound("ls", path))
        else Right(())
    yield
      // each child once (the tree is valid, so a child is a file or a directory, never both); sizes are
      // counted only for the children listed
      val children = all.toVector
        .flatMap { (p, entry) =>
          p.relativeTo(path).filter(_.nonEmpty).flatMap { rel =>
            (path / rel.takeWhile(_ != '/')).toOption.map { child =>
              if rel.contains('/') then (child, None) else (child, Some(entry))
            }
          }
        }
        .distinctBy(_._1)
      Listing.of(
        children.sortBy(_._1).iterator.map {
          case (child, None) => FileInfo(child, isDirectory = true, 0L, None)
          case (child, Some(entry)) =>
            FileInfo(child, isDirectory = false, Text.utf8Bytes(entry.content), Some(entry.modified))
        },
        limit
      )

  def read(all: Map[VPath, FileEntry], path: VPath, offset: Int, limit: Int): Result[FileLines] =
    for
      _     <- Text.checkLimitAt("read", path, limit)
      _     <- Either.cond(offset >= 0, (), BackendRefused.at("read", path, s"offset must be >= 0, was $offset"))
      entry <- fileOf("read", all, path)
    yield
      val lines = Text.lines(entry.content)
      FileLines(Text.page(lines, offset, limit), offset, lines.size)

  private def within(all: Map[VPath, FileEntry], under: VPath, accept: VPath => Boolean): Vector[VPath] =
    all.keysIterator.filter(p => p.isWithin(under) && accept(p)).toVector.sorted

  def find(all: Map[VPath, FileEntry], under: VPath, accept: VPath => Boolean, limit: Int): Result[Listing[VPath]] =
    Text.checkLimitAt("glob", under, limit).map(_ => Listing.of(within(all, under, accept).iterator, limit))

  def search(
    all: Map[VPath, FileEntry],
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int
  ): Result[Listing[GrepMatch]] =
    Text.checkLimitAt("grep", under, limit).flatMap { _ =>
      Text.interruptibly("grep") {
        val matches = within(all, under, accept).iterator.flatMap(p => Text.matches(p, all(p).content, regex))
        Right(Listing.of(matches, limit))
      }
    }
