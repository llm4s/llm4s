package org.llm4s.rag.loader

import org.llm4s.error.ProcessingError
import org.llm4s.extract.TikaDocumentExtractor

import java.io.File
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

  private def loadFile(file: File): LoadResult =
    TikaDocumentExtractor.extractFromPath(file.getAbsolutePath) match {
      case Left(error) =>
        LoadResult.failure(
          file.getAbsolutePath,
          ProcessingError("extract", error.message)
        )

      case Right(document) =>
        val content = document.text
        val version = DocumentVersion.fromContent(
          content,
          Some(file.lastModified())
        )

        val doc = Document(
          id = file.getAbsolutePath, // Use absolute path as stable ID
          content = content,
          metadata = metadata ++ Map(
            "source"       -> file.getName,
            "path"         -> file.getAbsolutePath,
            "extension"    -> getExtension(file.getName),
            "lastModified" -> file.lastModified().toString,
            "size"         -> file.length().toString
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
