package org.llm4s.agent.harness

import org.llm4s.types.Result

import java.util.regex.Pattern

/**
 * Reads over a map of files whose directories are implicit - a path is a directory when an entry lies
 * beneath it - shared by [[StateFileBackend]] and [[StoreFileBackend]].
 */
private[harness] object FileTree:

  def isDirectory(all: Map[VPath, FileEntry], path: VPath): Boolean =
    path == VPath.root || all.keysIterator.exists(p => p != path && p.isWithin(path))

  def fileOf(operation: String, all: Map[VPath, FileEntry], path: VPath): Result[FileEntry] =
    all.get(path) match
      case Some(entry)                    => Right(entry)
      case None if isDirectory(all, path) => Left(BackendRefused.at(operation, path, "is a directory"))
      case None                           => Left(BackendRefused.at(operation, path, "not found"))

  /** `path` may be written as a file: not the root, not a directory, not beneath a file. */
  def writable(operation: String, all: Map[VPath, FileEntry], path: VPath): Result[Unit] =
    def refuse(reason: String) = Left(BackendRefused.at(operation, path, reason))
    val ancestors              = Iterator.iterate(path.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten
    if path == VPath.root then refuse("is the root directory")
    else if !all.contains(path) && isDirectory(all, path) then refuse("is a directory")
    else if ancestors.exists(all.contains) then refuse("is beneath a file")
    else Right(())

  /** `path` may be deleted: it is a file. */
  def deletable(all: Map[VPath, FileEntry], path: VPath): Result[Unit] =
    if all.contains(path) then Right(())
    else if isDirectory(all, path) then
      Left(BackendRefused.at("delete", path, "is a directory; delete the files in it"))
    else Left(BackendRefused.at("delete", path, "not found"))

  def ls(all: Map[VPath, FileEntry], path: VPath, limit: Int): Result[Listing[FileInfo]] =
    for
      _ <- Text.checkLimit("ls", path.value, limit)
      _ <-
        if all.contains(path) then Left(BackendRefused.notADirectory("ls", path))
        else if !isDirectory(all, path) then Left(BackendRefused.notFound("ls", path))
        else Right(())
      children = all.toVector.flatMap { (p, entry) =>
        p.relativeTo(path).filter(_.nonEmpty).flatMap { rel =>
          (path / rel.takeWhile(_ != '/')).toOption.map { child =>
            if rel.contains('/') then FileInfo(child, isDirectory = true, 0L, None)
            else FileInfo(child, isDirectory = false, Text.utf8Bytes(entry.content), Some(entry.modified))
          }
        }
      }.distinct
      // a writer past the backend can store `/a` and `/a/b` both: `/a` is then a file and a directory, and
      // a listing that showed it twice, with two kinds, would break its contract, so the shape is refused
      _ <- children
        .groupBy(_.path)
        .collectFirst { case (child, infos) if infos.map(_.isDirectory).distinct.size > 1 => child }
        .fold[Result[Unit]](Right(()))(child =>
          Left(
            BackendRefused.at(
              "ls",
              path,
              s"the stored tree is inconsistent: ${child.value} is both a file and a directory, written past this backend"
            )
          )
        )
    yield Listing.of(children.sortBy(_.path).iterator, limit)

  def read(all: Map[VPath, FileEntry], path: VPath, offset: Int, limit: Int): Result[FileLines] =
    for
      _     <- Text.checkLimit("read", path.value, limit)
      _     <- Either.cond(offset >= 0, (), BackendRefused.at("read", path, s"offset must be >= 0, was $offset"))
      entry <- fileOf("read", all, path)
      // one text rule for every source: a store item written past the backend can hold NUL
      _ <- Text
        .binaryReason(entry.content)
        .fold[Result[Unit]](Right(()))(r => Left(BackendRefused.at("read", path, r)))
    yield
      val lines = Text.lines(entry.content)
      FileLines(Text.page(lines, offset, limit), offset, lines.size)

  private def within(all: Map[VPath, FileEntry], under: VPath, accept: VPath => Boolean): Vector[VPath] =
    all.keysIterator.filter(p => p.isWithin(under) && accept(p)).toVector.sorted

  def find(all: Map[VPath, FileEntry], under: VPath, accept: VPath => Boolean, limit: Int): Result[Listing[VPath]] =
    Text.checkLimit("glob", under.value, limit).map(_ => Listing.of(within(all, under, accept).iterator, limit))

  def search(
    all: Map[VPath, FileEntry],
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int
  ): Result[Listing[GrepMatch]] =
    Text.checkLimit("grep", under.value, limit).flatMap { _ =>
      Text.interruptibly("grep") {
        val matches = within(all, under, accept).iterator
          .filter(p => Text.binaryReason(all(p).content).isEmpty)
          .flatMap(p => Text.matches(p, all(p).content, regex))
        Right(Listing.of(matches, limit))
      }
    }
