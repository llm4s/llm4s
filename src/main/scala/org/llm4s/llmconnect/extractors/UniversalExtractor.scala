package org.llm4s.llmconnect.extractors

import org.llm4s.llmconnect.model.ExtractorError
import org.slf4j.LoggerFactory

import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.{ Try, Success, Failure, Using }

import org.apache.tika.Tika
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

object UniversalExtractor {

  private val logger = LoggerFactory.getLogger(getClass)

  def extract(inputPath: String): Either[ExtractorError, String] = {
    val file = new File(inputPath)

    if (!file.exists() || !file.isFile) {
      val error = ExtractorError(
        message = s"File not found or invalid: $inputPath",
        `type` = "FileNotFound",
        path = Some(inputPath)
      )
      logger.error(s"\n[FileNotFound] ${error.message}")
      return Left(error)
    }

    val tika     = new Tika()
    val mimeType = tika.detect(file)
    logger.info(s"\nDetected MIME type: $mimeType")

    mimeType match {
      case "application/pdf" =>
        extractPDF(file) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            val err = ExtractorError(ex.getMessage, "PDF", Some(inputPath))
            logger.error(s"\n[PDF ExtractorError] ${err.message}")
            Left(err)
        }

      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" =>
        extractDocx(file) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            val err = ExtractorError(ex.getMessage, "DOCX", Some(inputPath))
            logger.error(s"[DOCX ExtractorError] ${err.message}")
            Left(err)
        }

      case "text/plain" =>
        extractText(file) match {
          case Success(text) => Right(text)
          case Failure(ex) =>
            val err = ExtractorError(ex.getMessage, "PlainText", Some(inputPath))
            logger.error(s"[PlainText ExtractorError] ${err.message}")
            Left(err)
        }

      case _ =>
        val error = ExtractorError(
          message = s"Unsupported file type: $mimeType",
          `type` = "UnsupportedType",
          path = Some(inputPath)
        )
        logger.warn(s"[UnsupportedType] ${error.message}")
        Left(error)
    }
  }

  private def extractPDF(file: File): Try[String] = Try {
    val document = Loader.loadPDF(file)
    try
      new PDFTextStripper().getText(document)
    finally
      document.close()
  }

  private def extractDocx(file: File): Try[String] = Try {
    val document = new XWPFDocument(Files.newInputStream(file.toPath))
    try
      document.getParagraphs.toArray.map(_.toString).mkString("\n")
    finally
      document.close()
  }

  private def extractText(file: File): Try[String] = Try {
    Using.resource(Source.fromFile(file, StandardCharsets.UTF_8.name()))(source => source.getLines().mkString("\n"))
  }
}
