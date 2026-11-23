package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.extract.TikaDocumentExtractor

import java.io.File
import scala.util.Try
import java.nio.file.Path

/**
 * Load a single file as a document.
 *
 * Supports all file types handled by TikaDocumentExtractor:
 * - Text files (.txt, .md, .json, .xml, .html)
 * - PDF documents
 * - Word documents (.docx)
 *
 * Automatically detects appropriate chunking hints based on file extension.
 * Includes version information (content hash + file timestamp) for sync operations.
 *
 * @param path Path to the file
 * @param metadata Additional metadata to attach
 */
final case class FileLoader(
  path: Path,
  metadata: Map[String, String] = Map.empty
) extends DocumentLoader {

  def load(): Iterator[LoadResult] = {
    val file = path.toFile

    // A missing path, or one that is not a file, names no document (so RAG.sync skips its
    // deletion pass for the run, the safe default); a file that exists but cannot be extracted
    // names the document it is indexed as, so sync keeps that document's indexed version.
    if (!file.exists()) {
      Iterator(
        LoadResult.failure(
          path.toString,
          ProcessingError("load", s"File not found: $path")
        )
      )
    } else if (!file.isFile) {
      Iterator(
        LoadResult.failure(
          path.toString,
          ProcessingError("load", s"Not a file: $path")
        )
      )
    } else {
      Iterator(loadFile(file))
    }
  }

  /**
   * Loads the document named by `path`, reading its bytes from `real`, the link-free real path a caller has already
   * checked. Used by [[DirectoryLoader]] so that a link retargeted after the check is never followed again: the
   * read goes to the checked target, and fails if `real` no longer resolves to itself (a component was replaced by
   * a link) or is no longer a regular file.
   */
  private[loader] def loadFrom(real: Path): Iterator[LoadResult] =
    if (
      Try(real.toRealPath()).toOption.contains(real) &&
      java.nio.file.Files.isRegularFile(real, java.nio.file.LinkOption.NOFOLLOW_LINKS)
    ) Iterator(loadFile(path.toFile, real.toFile))
    else
      Iterator(
        LoadResult.failure(
          path.toString,
          ProcessingError("load", s"Changed since it was listed: $path")
        )
      )

  private def loadFile(file: File): LoadResult = loadFile(file, file)

  /** `file` names the document; `content` is where its bytes, size and modification time are read. */
  private def loadFile(file: File, content: File): LoadResult =
    TikaDocumentExtractor.extractFromPath(content.getAbsolutePath) match {
      case Left(error) =>
        LoadResult.failure(
          file.getAbsolutePath,
          ProcessingError("extract", error.message),
          documentId = file.getAbsolutePath // the id loadFile gives the document on success
        )

      case Right(document) =>
        val text = document.text
        val version = DocumentVersion.fromContent(
          text,
          Some(content.lastModified())
        )

        val doc = Document(
          id = file.getAbsolutePath, // Use absolute path as stable ID
          content = text,
          metadata = metadata ++ Map(
            "source"       -> file.getName,
            "path"         -> file.getAbsolutePath,
            "extension"    -> getExtension(file.getName),
            "lastModified" -> content.lastModified().toString,
            "size"         -> content.length().toString
          ),
          hints = Some(detectHints(file)),
          version = Some(version)
        )
        LoadResult.success(doc)
    }

  private def detectHints(file: File): DocumentHints = {
    val ext = getExtension(file.getName).toLowerCase
    ext match {
      case "md" | "markdown"                                                               => DocumentHints.markdown
      case "scala" | "java" | "py" | "js" | "ts" | "go" | "rs" | "c" | "cpp" | "h" | "hpp" => DocumentHints.code
      case _                                                                               => DocumentHints.prose
    }
  }

  private def getExtension(name: String): String =
    name.lastIndexOf('.') match {
      case -1 => ""
      case i  => name.substring(i + 1)
    }

  override def estimatedCount: Option[Int] = Some(1)

  def description: String = s"FileLoader($path)"
}

object FileLoader {

  def apply(path: String): FileLoader =
    FileLoader(new File(path).toPath)

  def apply(path: String, metadata: Map[String, String]): FileLoader =
    FileLoader(new File(path).toPath, metadata)

  def apply(file: File): FileLoader =
    FileLoader(file.toPath)
}
