package org.llm4s.llmconnect.extractors

import org.llm4s.llmconnect.model.ExtractorError
import org.slf4j.LoggerFactory

import java.io.{ ByteArrayInputStream, File, InputStream }
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }

import org.apache.tika.Tika
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Extracts text and multimedia content from files of various formats.
 *
 * MIME type detection is performed by Apache Tika. Supported formats include:
 *  - PDF (via PDFBox)
 *  - DOCX (via Apache POI)
 *  - Plain text and other text types
 *  - Images (via ImageIO)
 *  - Unknown types (Tika fallback)
 *
 * Three entry points are provided:
 *  - `extract` -- text-only extraction from a file path
 *  - `extractAny` -- multimedia-aware extraction returning an [[Extracted]] ADT
 *  - `extractFromBytes` / `extractFromStream` -- source-agnostic text extraction from raw bytes
 */
object UniversalExtractor {

  private val logger = LoggerFactory.getLogger(getClass)
  private val tika   = new Tika()

  // ----- MIME constants (single source of truth) -----
  private val PdfMime  = "application/pdf"
  private val DocxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

  /** ADT representing extracted content from a file, discriminated by media type. */
  sealed trait Extracted

  /** Extracted text content (from PDF, DOCX, plain text, etc.). */
  final case class TextContent(text: String) extends Extracted

  /** Extracted image content. */
  final case class ImageContent(image: BufferedImage) extends Extracted

  /** Extracted audio content as mono PCM samples with a sample rate. */
  final case class AudioContent(samples: Array[Float], sampleRate: Int) extends Extracted

  /** Extracted video content as a sequence of frames at a given frame rate. */
  final case class VideoContent(frames: Seq[BufferedImage], fps: Int) extends Extracted

  // ----- Path normalization (quotes/whitespace) -----
  private def normalizeInputPath(raw: String): File = {
    val s = raw.trim
      .stripPrefix("\"")
      .stripSuffix("\"")
      .stripPrefix("'")
      .stripSuffix("'")
    new File(s).getAbsoluteFile
  }

  /**
   * Check whether a MIME type represents text-extractable content (PDF, DOCX, text types, JSON, XML).
   *
   * @param mime the MIME type string to check
   * @return true if the MIME type can be processed as text
   */
  def isTextLike(mime: String): Boolean =
    mime == PdfMime ||
      mime == DocxMime ||
      mime.startsWith("text/") ||
      mime == "application/json" ||
      mime == "application/xml"

  /**
   * Extract text content from a file at the given path.
   *
   * Supports PDF, DOCX, plain text, and Tika-parseable formats. Returns an error
   * for files that cannot be found or whose MIME type is unsupported.
   *
   * @param inputPath path to the file (quotes and whitespace are trimmed)
   * @return extracted text or an [[ExtractorError]]
   */
  def extract(inputPath: String): Either[ExtractorError, String] = {
    val file = normalizeInputPath(inputPath)
    if (!file.exists() || !file.isFile) {
      val error = ExtractorError(
        message =
          s"File not found or invalid: ${file.getPath} (exists=${file.exists()}, isFile=${file.isFile}, isDir=${file.isDirectory})",
        `type` = "FileNotFound",
        path = Some(file.getPath)
      )
      logger.error(s"[FileNotFound] ${error.message}")
      return Left(error)
    }

    // Use MIME detection for more reliable type identification
    val mimeType = tika.detect(file)
    logger.info(s"[Extract] Processing file: ${file.getPath} (MIME: $mimeType)")

    mimeType match {
      case PdfMime =>
        extractPDF(file) match {
          case Success(text) =>
            Right(text)
          case Failure(ex) =>
            logger.error(s"[PDF] Extraction failed for ${file.getPath}: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"PDF extraction failed: ${ex.getMessage}",
                `type` = "PDFError",
                path = Some(file.getPath)
              )
            )
        }

      case DocxMime =>
        extractDocx(file) match {
          case Success(text) =>
            Right(text)
          case Failure(ex) =>
            logger.error(s"[DOCX] Extraction failed for ${file.getPath}: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"DOCX extraction failed: ${ex.getMessage}",
                `type` = "DocxError",
                path = Some(file.getPath)
              )
            )
        }

      case mt if mt.startsWith("text/") =>
        extractText(file) match {
          case Success(text) =>
            Right(text)
          case Failure(ex) =>
            logger.error(s"[Text] Read failed for ${file.getPath}: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"Text read failed: ${ex.getMessage}",
                `type` = "TextError",
                path = Some(file.getPath)
              )
            )
        }

      case other =>
        // Try Tika as fallback for unknown types
        Try(tika.parseToString(file)) match {
          case Success(text) if text.trim.nonEmpty =>
            logger.info(s"[Tika] Successfully extracted from unknown type: $other")
            Right(text)
          case _ =>
            logger.error(s"[UnknownType] No text extractor for MIME type: $other")
            Left(
              ExtractorError(
                message = s"Unsupported file type: $other",
                `type` = "UnsupportedType",
                path = Some(file.getPath)
              )
            )
        }
    }
  }

  /**
   * Extract content from a file, returning the appropriate [[Extracted]] subtype
   * based on MIME type (text, image, audio, or video).
   *
   * @param inputPath path to the file (quotes and whitespace are trimmed)
   * @return extracted content as an [[Extracted]] ADT variant, or an [[ExtractorError]]
   */
  def extractAny(inputPath: String): Either[ExtractorError, Extracted] = {
    val file = normalizeInputPath(inputPath)
    if (!file.exists() || !file.isFile) {
      val error = ExtractorError(
        message = s"File not found: ${file.getPath}",
        `type` = "FileNotFound",
        path = Some(file.getPath)
      )
      logger.error(s"[FileNotFound] ${error.message}")
      return Left(error)
    }

    val mimeType = tika.detect(file)
    logger.info(s"[ExtractAny] Processing: ${file.getPath} (MIME: $mimeType)")

    mimeType match {
      case PdfMime =>
        extractPDF(file) match {
          case Success(text) => Right(TextContent(text))
          case Failure(ex) =>
            logger.error(s"[PDF] Failed: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"PDF extraction failed: ${ex.getMessage}",
                `type` = "PDFError",
                path = Some(file.getPath)
              )
            )
        }

      case DocxMime =>
        extractDocx(file) match {
          case Success(text) => Right(TextContent(text))
          case Failure(ex) =>
            logger.error(s"[DOCX] Failed: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"DOCX extraction failed: ${ex.getMessage}",
                `type` = "DocxError",
                path = Some(file.getPath)
              )
            )
        }

      case mt if mt.startsWith("text/") =>
        extractText(file) match {
          case Success(text) => Right(TextContent(text))
          case Failure(ex) =>
            logger.error(s"[Text] Failed: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"Text read failed: ${ex.getMessage}",
                `type` = "TextError",
                path = Some(file.getPath)
              )
            )
        }

      case mt if mt.startsWith("image/") =>
        extractImage(file)

      case mt if mt.startsWith("audio/") =>
        unsupported("Audio", file, s"Audio extraction not yet implemented (MIME: $mt)")

      case mt if mt.startsWith("video/") =>
        unsupported("Video", file, s"Video extraction not yet implemented (MIME: $mt)")

      case other =>
        Try(tika.parseToString(file)) match {
          case Success(text) if text.trim.nonEmpty =>
            logger.info(s"[Tika] Extracted text from: $other")
            Right(TextContent(text))
          case _ =>
            logger.error(s"[UnknownType] No extractor for: $other")
            Left(
              ExtractorError(
                message = s"Unsupported MIME type: $other",
                `type` = "UnsupportedType",
                path = Some(file.getPath)
              )
            )
        }
    }
  }

  // ================================= EXTRACTION HELPERS =================================
  private def extractPDF(file: File): Try[String] = Try {
    Using.resource(Loader.loadPDF(file)) { doc =>
      val stripper = new PDFTextStripper()
      stripper.getText(doc)
    }
  }

  private def extractDocx(file: File): Try[String] = Try {
    Using.resource(new XWPFDocument(Files.newInputStream(file.toPath))) { doc =>
      doc.getParagraphs.asScala.map(_.getText).mkString("\n")
    }
  }

  private def extractText(file: File): Try[String] = Try {
    Source.fromFile(file, StandardCharsets.UTF_8.name()).mkString
  }

  private def extractImage(file: File): Either[ExtractorError, Extracted] =
    Try(ImageIO.read(file)) match {
      case Success(img) if img != null =>
        logger.info(s"[Image] Loaded: ${img.getWidth}x${img.getHeight}")
        Right(ImageContent(img))
      case Success(_) =>
        logger.error(s"[Image] ImageIO returned null for: ${file.getPath}")
        Left(
          ExtractorError(
            message = "ImageIO could not read the image file",
            `type` = "ImageError",
            path = Some(file.getPath)
          )
        )
      case Failure(ex) =>
        logger.error(s"[Image] Read failed: ${ex.getMessage}", ex)
        Left(
          ExtractorError(
            message = s"Image read failed: ${ex.getMessage}",
            `type` = "ImageError",
            path = Some(file.getPath)
          )
        )
    }

  private def unsupported(kind: String, file: File, msg: String): Either[ExtractorError, Nothing] = {
    logger.warn(s"[$kind] $msg")
    Left(
      ExtractorError(
        message = msg,
        `type` = s"${kind}Unsupported",
        path = Some(file.getPath)
      )
    )
  }

  // ================================= BYTE-BASED API =================================

  /**
   * Extract text from raw bytes.
   *
   * This method enables source-agnostic document extraction - the same extraction
   * logic can be used for documents from S3, HTTP responses, databases, etc.
   *
   * @param content Raw document bytes
   * @param filename Filename for MIME type detection (e.g., "report.pdf")
   * @param mimeType Optional explicit MIME type (skips detection if provided)
   * @return Extracted text content or an error
   */
  def extractFromBytes(
    content: Array[Byte],
    filename: String,
    mimeType: Option[String] = None
  ): Either[ExtractorError, String] = {
    val detectedMime = mimeType.getOrElse(tika.detect(content, filename))
    logger.info(s"[ExtractFromBytes] Processing: $filename (MIME: $detectedMime)")

    detectedMime match {
      case PdfMime =>
        extractPDFFromBytes(content) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            logger.error(s"[PDF] Extraction failed for $filename: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"PDF extraction failed: ${ex.getMessage}",
                `type` = "PDFError",
                path = Some(filename)
              )
            )
        }

      case DocxMime =>
        extractDocxFromBytes(content) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            logger.error(s"[DOCX] Extraction failed for $filename: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"DOCX extraction failed: ${ex.getMessage}",
                `type` = "DocxError",
                path = Some(filename)
              )
            )
        }

      case mt if mt.startsWith("text/") =>
        extractTextFromBytes(content) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            logger.error(s"[Text] Read failed for $filename: ${ex.getMessage}", ex)
            Left(
              ExtractorError(
                message = s"Text read failed: ${ex.getMessage}",
                `type` = "TextError",
                path = Some(filename)
              )
            )
        }

      case other =>
        // Try Tika as fallback
        extractWithTikaFromBytes(content) match {
          case Success(text) if text.trim.nonEmpty =>
            logger.info(s"[Tika] Successfully extracted from unknown type: $other")
            Right(text)
          case _ =>
            logger.error(s"[UnknownType] No text extractor for MIME type: $other")
            Left(
              ExtractorError(
                message = s"Unsupported file type: $other",
                `type` = "UnsupportedType",
                path = Some(filename)
              )
            )
        }
    }
  }

  /**
   * Extract text from an InputStream.
   *
   * Note: This reads the entire stream into memory for processing.
   * The caller is responsible for closing the stream after this method returns.
   *
   * @param input InputStream to read from
   * @param filename Filename for MIME type detection
   * @param mimeType Optional explicit MIME type
   * @return Extracted text content or an error
   */
  def extractFromStream(
    input: InputStream,
    filename: String,
    mimeType: Option[String] = None
  ): Either[ExtractorError, String] =
    Try(input.readAllBytes()) match {
      case Success(bytes) => extractFromBytes(bytes, filename, mimeType)
      case Failure(ex) =>
        logger.error(s"[Stream] Failed to read input stream: ${ex.getMessage}", ex)
        Left(
          ExtractorError(
            message = s"Failed to read input stream: ${ex.getMessage}",
            `type` = "StreamError",
            path = Some(filename)
          )
        )
    }

  /**
   * Detect MIME type from bytes and filename.
   *
   * @param content Raw document bytes (first few KB are sufficient)
   * @param filename Filename hint for detection
   * @return Detected MIME type string
   */
  def detectMimeType(content: Array[Byte], filename: String): String =
    tika.detect(content, filename)

  // ================================= BYTE-BASED EXTRACTION HELPERS =================================

  private def extractPDFFromBytes(content: Array[Byte]): Try[String] = Try {
    Using.resource(Loader.loadPDF(content)) { doc =>
      val stripper = new PDFTextStripper()
      stripper.getText(doc)
    }
  }

  private def extractDocxFromBytes(content: Array[Byte]): Try[String] = Try {
    Using.resource(new ByteArrayInputStream(content)) { bis =>
      Using.resource(new XWPFDocument(bis))(doc => doc.getParagraphs.asScala.map(_.getText).mkString("\n"))
    }
  }

  private def extractTextFromBytes(content: Array[Byte]): Try[String] =
    Try(new String(content, StandardCharsets.UTF_8))

  private def extractWithTikaFromBytes(content: Array[Byte]): Try[String] = Try {
    Using.resource(new ByteArrayInputStream(content))(bis => tika.parseToString(bis))
  }
}
