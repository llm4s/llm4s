package org.llm4s.extract

import org.llm4s.types.Result

import java.io.InputStream

/**
 * Extracted document content with metadata.
 *
 * Represents the result of extracting text from a document,
 * including any metadata that could be extracted (title, author, etc.)
 *
 * @param text The extracted text content
 * @param metadata Document metadata (title, author, pageCount, etc.)
 * @param format The detected document format
 */
final case class ExtractedDocument(
  text: String,
  metadata: Map[String, String] = Map.empty,
  format: DocumentFormat = DocumentFormat.Unknown
)

/**
 * Supported document formats for extraction.
 */
sealed trait DocumentFormat {
  def name: String
}

object DocumentFormat {
  case object PlainText extends DocumentFormat { val name = "text/plain"      }
  case object Markdown  extends DocumentFormat { val name = "text/markdown"   }
  case object PDF       extends DocumentFormat { val name = "application/pdf" }
  case object DOCX extends DocumentFormat {
    val name = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  }
  case object DOC     extends DocumentFormat { val name = "application/msword"       }
  case object HTML    extends DocumentFormat { val name = "text/html"                }
  case object JSON    extends DocumentFormat { val name = "application/json"         }
  case object XML     extends DocumentFormat { val name = "application/xml"          }
  case object CSV     extends DocumentFormat { val name = "text/csv"                 }
  case object Unknown extends DocumentFormat { val name = "application/octet-stream" }

  /**
   * Get DocumentFormat from MIME type string.
   */
  def fromMimeType(mimeType: String): DocumentFormat = mimeType.toLowerCase match {
    case "application/pdf"                                                         => PDF
    case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => DOCX
    case "application/msword"                                                      => DOC
    case "text/html"                                                               => HTML
    case "application/json"                                                        => JSON
    case "application/xml" | "text/xml"                                            => XML
    case "text/csv"                                                                => CSV
    case "text/markdown"                                                           => Markdown
    case mt if mt.startsWith("text/")                                              => PlainText
    case _                                                                         => Unknown
  }

  /**
   * Get DocumentFormat from file extension.
   */
  def fromExtension(filename: String): DocumentFormat = {
    val ext = filename.lastIndexOf('.') match {
      case -1 => ""
      case i  => filename.substring(i + 1).toLowerCase
    }
    ext match {
      case "pdf"             => PDF
      case "docx"            => DOCX
      case "doc"             => DOC
      case "html" | "htm"    => HTML
      case "json"            => JSON
      case "xml"             => XML
      case "csv"             => CSV
      case "md" | "markdown" => Markdown
      case "txt" | "text"    => PlainText
      case _                 => Unknown
    }
  }
}

/**
 * Service for extracting text content from documents.
 *
 * DocumentExtractor is source-agnostic - it works with raw bytes from any source
 * (filesystem, S3, HTTP, database, etc.). This allows the same extraction logic
 * to be used regardless of where the document is stored. [[extractFromPath]] is
 * the one filesystem-aware entry point, provided because loading a local file is
 * common enough that every caller would otherwise write the same three lines.
 *
 * Supported formats:
 * - Plain text files (.txt, .md, .json, .xml, .csv, .html)
 * - PDF documents (.pdf)
 * - Word documents (.docx, .doc)
 *
 * Usage:
 * {{{
 * val extractor = TikaDocumentExtractor
 *
 * // Extract from bytes (common for S3, HTTP responses)
 * val result = extractor.extract(bytes, "report.pdf")
 *
 * // Extract from stream (for large files)
 * val result = extractor.extractFromStream(inputStream, "report.pdf")
 * }}}
 */
trait DocumentExtractor {

  /**
   * Extract text content from document bytes.
   *
   * @param content Raw document bytes
   * @param filename Filename for type detection (e.g., "report.pdf").
   *                 Used to detect format if mimeType is not provided.
   * @param mimeType Optional MIME type override. If provided, skips detection.
   * @return Extracted document with text, metadata, and detected format
   */
  def extract(
    content: Array[Byte],
    filename: String,
    mimeType: Option[String] = None
  ): Result[ExtractedDocument]

  /**
   * Extract text content from an InputStream.
   *
   * Use this for large files to avoid loading the entire content into memory.
   * The caller is responsible for closing the stream after this method returns.
   *
   * @param input InputStream to read from
   * @param filename Filename for type detection
   * @param mimeType Optional MIME type override
   * @return Extracted document with text, metadata, and detected format
   */
  def extractFromStream(
    input: InputStream,
    filename: String,
    mimeType: Option[String] = None
  ): Result[ExtractedDocument]

  /**
   * Extract text content from a file on the local filesystem.
   *
   * Surrounding whitespace and a single pair of matching quotes are stripped from
   * `path` before it is resolved, so paths pasted from a shell or a prompt work.
   *
   * @param path Path to the file to read
   * @return Extracted document with text, metadata, and detected format
   */
  def extractFromPath(path: String): Result[ExtractedDocument]

  /**
   * Detect the MIME type of document content.
   *
   * @param content Raw document bytes (only first few KB are needed)
   * @param filename Filename hint for detection
   * @return Detected MIME type string
   */
  def detectMimeType(content: Array[Byte], filename: String): String

  /**
   * Check if a MIME type can be extracted to text.
   *
   * @param mimeType The MIME type to check
   * @return true if this extractor can handle the MIME type
   */
  def canExtract(mimeType: String): Boolean
}
