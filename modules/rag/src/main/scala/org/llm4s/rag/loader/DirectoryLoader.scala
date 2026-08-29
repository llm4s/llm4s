package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError

import java.io.File
import java.nio.file.Path

/**
 * Load all documents from a directory.
 *
 * Supports recursive directory traversal and file filtering by extension.
 * Each file is loaded using FileLoader, inheriting its version and hint detection.
 *
 * @param path Path to the directory
 * @param extensions File extensions to include (without leading dot)
 * @param recursive Whether to recurse into subdirectories
 * @param metadata Additional metadata to attach to all documents
 * @param maxDepth Maximum recursion depth (0 = current directory only)
 */
final case class DirectoryLoader(
  path: Path,
  extensions: Set[String] = DirectoryLoader.defaultExtensions,
  recursive: Boolean = true,
  metadata: Map[String, String] = Map.empty,
  maxDepth: Int = 10
) extends DocumentLoader {

  def load(): Iterator[LoadResult] = {
    val dir = path.toFile

    if (!dir.exists()) {
      return Iterator(
        LoadResult.failure(
          path.toString,
          ProcessingError("load", s"Directory not found: $path")
        )
      )
    }

    if (!dir.isDirectory) {
      return Iterator(
        LoadResult.failure(
          path.toString,
          ProcessingError("load", s"Not a directory: $path")
        )
      )
    }

    listFiles(dir, 0).iterator.flatMap { file =>
      val fileMetadata = metadata + ("directory" -> dir.getName)
      FileLoader(file.toPath, fileMetadata).load()
    }
  }

  private def listFiles(dir: File, depth: Int): Seq[File] = {
    if (depth > maxDepth) return Seq.empty

    val entries = Option(dir.listFiles()).map(_.toSeq).getOrElse(Seq.empty)

    val files = entries.filter(f => f.isFile && extensions.exists(ext => f.getName.toLowerCase.endsWith(s".$ext")))

    val subdirFiles =
      if (recursive) {
        entries.filter(_.isDirectory).flatMap(listFiles(_, depth + 1))
      } else {
        Seq.empty
      }

    files ++ subdirFiles
  }

  override def estimatedCount: Option[Int] = {
    val dir = path.toFile
    if (dir.exists() && dir.isDirectory) {
      Some(listFiles(dir, 0).size)
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

  /** Add metadata to all documents */
  def withMetadata(meta: Map[String, String]): DirectoryLoader =
    copy(metadata = metadata ++ meta)
}

object DirectoryLoader {

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
