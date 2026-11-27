package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError

import java.io.File
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{ Files, NoSuchFileException, Path }
import java.util.Locale
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try, Using }

/**
 * Load all documents from a directory.
 *
 * Supports recursive directory traversal and file filtering by extension.
 * Each file is loaded using FileLoader, inheriting its version and hint detection.
 *
 * Only regular files are read: a FIFO, device or socket is skipped, even when a link points to it. With
 * `confineToRoot` (the default), a symbolic link is followed only when its real path is inside the root
 * directory's real path, so a link to `/etc` or to a secret elsewhere is skipped. A directory reached twice through
 * links is listed once. Each file is read from the real path checked when the directory was listed, not by
 * following its link again, so a link retargeted afterwards is not followed; a file whose checked path no longer
 * resolves to itself is a failure. Java cannot open a file relative to a held directory, so a directory inside the
 * root replaced by a link in the instant between that recheck and the open is the one race left.
 *
 * A directory that cannot be listed (permissions, an I/O error), or an entry whose real path or attributes cannot be
 * read, ends the load with a [[LoadResult.ListingFailure]], after the documents that were listed: what that part of
 * the tree holds is unknown, so `RAG.sync` returns the error and deletes nothing, rather than treat its documents as
 * gone. An entry that no longer exists when it is resolved, such as a link to nothing, is skipped.
 *
 * @param path Path to the directory
 * @param extensions File extensions to include (without leading dot)
 * @param recursive Whether to recurse into subdirectories
 * @param metadata Additional metadata to attach to all documents
 * @param maxDepth Maximum recursion depth (0 = current directory only)
 * @param confineToRoot Whether to skip a symbolic link whose real path is outside the root directory
 */
final case class DirectoryLoader(
  path: Path,
  extensions: Set[String] = DirectoryLoader.defaultExtensions,
  recursive: Boolean = true,
  metadata: Map[String, String] = Map.empty,
  maxDepth: Int = 10,
  confineToRoot: Boolean = true
) extends DocumentLoader {

  def load(): Iterator[LoadResult] = {
    val dir = path.toFile

    if (!dir.exists()) {
      return Iterator(
        LoadResult.listingFailure(
          path.toString,
          ProcessingError("load", s"Directory not found: $path")
        )
      )
    }

    if (!dir.isDirectory) {
      return Iterator(
        LoadResult.listingFailure(
          path.toString,
          ProcessingError("load", s"Not a directory: $path")
        )
      )
    }

    val listing = listFiles()
    val documents = listing.files.iterator.flatMap { case (file, real) =>
      val fileMetadata = metadata + ("directory" -> dir.getName)
      // Read from the real path checked when listing, never by following `file` again (#2724)
      FileLoader(file, fileMetadata).loadFrom(real)
    }
    // After the documents that were listed: a part of the tree that could not be listed is not an empty part, so
    // RAG.sync must delete nothing for this source (#2998)
    documents ++ listing.failure.iterator
  }

  /**
   * Every matching regular file under the root, depth first, entries in name order, with its checked real path; and,
   * when a directory could not be listed or an entry's real path or attributes could not be read, the
   * [[LoadResult.ListingFailure]] naming them. An entry that is gone by the time it is resolved (deleted since the
   * listing, or a link to nothing) is skipped, as a file that no longer exists.
   */
  private def listFiles(): DirectoryLoader.Listing =
    Try(path.toRealPath()) match {
      case Failure(error) =>
        DirectoryLoader.Listing(Seq.empty, DirectoryLoader.failure(path, Seq(path -> error)))
      case Success(rootReal) =>
        def inside(real: Path): Boolean = !confineToRoot || real.startsWith(rootReal)

        // Deduplicated: a directory is listed twice, once when its depth is found and once when it is walked
        val errors = scala.collection.mutable.LinkedHashMap[Path, Throwable]()

        // Each entry of `dir` that is inside the root, with the real path it would be read from (links followed)
        // and that path's attributes
        def entries(dir: Path): Vector[(Path, Path, BasicFileAttributes)] =
          Using(Files.list(dir))(_.iterator().asScala.toVector.sortBy(_.getFileName.toString)) match {
            case Failure(error) =>
              errors.getOrElseUpdate(dir, error)
              Vector.empty
            case Success(listed) =>
              listed.flatMap { e =>
                Try {
                  val real = e.toRealPath()
                  (e, real, Files.readAttributes(real, classOf[BasicFileAttributes]))
                } match {
                  case Success(resolved @ (_, real, _)) => Option.when(inside(real))(resolved)
                  case Failure(_: NoSuchFileException)  => None // gone since the listing, or a link to nothing
                  case Failure(error) =>
                    errors.getOrElseUpdate(e, error)
                    None
                }
              }
          }

        def subdirs(dir: Path): Vector[(Path, Path)] =
          entries(dir).collect { case (entry, real, attrs) if attrs.isDirectory => entry -> real }

        // The shallowest depth each real directory is reachable at, breadth first, so a directory reached through a
        // deep link is still walked as deep as its shallowest path allows; each real directory is listed once here
        val shallowest = scala.collection.mutable.Map[Path, Int](rootReal -> 0)
        @tailrec
        def level(frontier: Vector[Path], depth: Int): Unit =
          if (frontier.nonEmpty && depth < maxDepth) {
            val next = frontier.flatMap(subdirs).collect {
              case (entry, real) if !shallowest.contains(real) =>
                shallowest(real) = depth + 1
                entry
            }
            level(next, depth + 1)
          }
        if (recursive) level(Vector(path), 0)

        // Depth first, entering a real directory once, at its shallowest depth: link loops end there
        val walked = scala.collection.mutable.Set[Path](rootReal)
        def walk(dir: Path, depth: Int): Seq[(Path, Path)] = {
          val resolved = entries(dir)
          val files = resolved.collect {
            case (entry, real, attrs) if attrs.isRegularFile && matchesExtension(entry) => entry -> real
          }
          val subdirFiles = resolved.collect {
            case (entry, real, attrs)
                if attrs.isDirectory && shallowest.get(real).contains(depth + 1) && walked.add(real) =>
              walk(entry, depth + 1)
          }.flatten
          files ++ subdirFiles
        }

        val files = walk(path, 0)
        DirectoryLoader.Listing(files, DirectoryLoader.failure(path, errors.toSeq))
    }

  private def matchesExtension(file: Path): Boolean = {
    val name = file.getFileName.toString.toLowerCase(Locale.ROOT)
    extensions.exists(ext => name.endsWith(s".$ext"))
  }

  override def estimatedCount: Option[Int] = {
    val dir = path.toFile
    if (dir.exists() && dir.isDirectory) {
      val listing = listFiles()
      // Unknown, not the part that could be listed
      Option.when(listing.failure.isEmpty)(listing.files.size)
    } else {
      None
    }
  }

  def description: String = s"DirectoryLoader($path, ${extensions.mkString(",")})"

  /** Add an extension to filter */
  def withExtension(ext: String): DirectoryLoader =
    copy(extensions = extensions + ext.stripPrefix("."))

  /** Set extensions (replaces existing) */
  def withExtensions(exts: Set[String]): DirectoryLoader =
    copy(extensions = exts.map(_.stripPrefix(".")))

  /** Enable/disable recursive traversal */
  def withRecursive(r: Boolean): DirectoryLoader =
    copy(recursive = r)

  /** Set maximum recursion depth */
  def withMaxDepth(depth: Int): DirectoryLoader =
    copy(maxDepth = depth)

  /** Whether to skip symbolic links whose real path is outside the root directory (default true) */
  def withConfineToRoot(confine: Boolean): DirectoryLoader =
    copy(confineToRoot = confine)

  /** Add metadata to all documents */
  def withMetadata(meta: Map[String, String]): DirectoryLoader =
    copy(metadata = metadata ++ meta)
}

object DirectoryLoader {

  /** The files a listing found, and the failure to report when part of the tree could not be listed. */
  final private case class Listing(files: Seq[(Path, Path)], failure: Option[LoadResult])

  /** Shown in a listing failure's message; the rest are counted. */
  private val shownErrors = 5

  /** One [[LoadResult.ListingFailure]] for the root, naming each path that could not be listed or resolved. */
  private def failure(root: Path, errors: Seq[(Path, Throwable)]): Option[LoadResult] =
    Option.when(errors.nonEmpty) {
      val shown = errors.take(shownErrors).map { case (p, e) =>
        val detail = Option(e.getMessage).filterNot(_ == p.toString).fold("")(m => s": $m")
        s"$p (${e.getClass.getSimpleName}$detail)"
      }
      val more = if (errors.size > shownErrors) s", and ${errors.size - shownErrors} more" else ""
      LoadResult.listingFailure(
        root.toString,
        ProcessingError("list", s"Could not list ${errors.size} path(s) under $root: ${shown.mkString(", ")}$more")
      )
    }

  /** Default supported file extensions */
  val defaultExtensions: Set[String] = Set(
    "txt",
    "md",
    "markdown",
    "pdf",
    "docx",
    "json",
    "xml",
    "html",
    "htm",
    "rst"
  )

  def apply(path: String): DirectoryLoader =
    DirectoryLoader(new File(path).toPath)

  def apply(path: String, extensions: Set[String]): DirectoryLoader =
    DirectoryLoader(new File(path).toPath, extensions)

  def apply(file: File): DirectoryLoader =
    DirectoryLoader(file.toPath)

  /** Load only markdown files */
  def markdown(path: String): DirectoryLoader =
    DirectoryLoader(new File(path).toPath, Set("md", "markdown"))

  /** Load only code files */
  def code(path: String): DirectoryLoader =
    DirectoryLoader(
      new File(path).toPath,
      Set("scala", "java", "py", "js", "ts", "go", "rs", "c", "cpp", "h", "hpp")
    )

  /** Load only text files */
  def text(path: String): DirectoryLoader =
    DirectoryLoader(new File(path).toPath, Set("txt"))

  /** Load only PDF files */
  def pdf(path: String): DirectoryLoader =
    DirectoryLoader(new File(path).toPath, Set("pdf"))

  /** Load only Word documents */
  def docx(path: String): DirectoryLoader =
    DirectoryLoader(new File(path).toPath, Set("docx"))
}
