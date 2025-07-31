package org.llm4s.llmconnect.extractors

import org.llm4s.llmconnect.model.ExtractorError
import scala.util.Try
import java.nio.file.Files
import java.io.File
import org.apache.tika.Tika
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import scala.io.Source

object UniversalExtractor {

  def extract(inputPath: String): Either[ExtractorError, String] = {
    val file = new File(inputPath)
    if (!file.exists() || !file.isFile) {
      return Left(
        ExtractorError(
          message = s"File not found or invalid: $inputPath",
          `type` = "FileNotFound",
          path = Some(inputPath)
        )
      )
    }

    val tika     = new Tika()
    val mimeType = tika.detect(file)

    mimeType match {
      case "application/pdf" =>
        extractPDF(file).toEither.left.map(err => ExtractorError(err.getMessage, "PDF", Some(inputPath)))

      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" =>
        extractDocx(file).toEither.left.map(err => ExtractorError(err.getMessage, "DOCX", Some(inputPath)))

      case "text/plain" =>
        extractText(file).toEither.left.map(err => ExtractorError(err.getMessage, "PlainText", Some(inputPath)))

      case _ =>
        Left(
          ExtractorError(
            message = s"Unsupported file type: $mimeType",
            `type` = "UnsupportedType",
            path = Some(inputPath)
          )
        )
    }
  }

  private def extractPDF(file: File): Try[String] = Try {
    val document = PDDocument.load(file)
    try {
      val stripper = new PDFTextStripper()
      stripper.getText(document)
    } finally document.close()
  }

  private def extractDocx(file: File): Try[String] = Try {
    val document = new XWPFDocument(Files.newInputStream(file.toPath))
    try {
      val paragraphs = document.getParagraphs
      paragraphs.toArray.map(_.toString).mkString("\n")
    } finally document.close()
  }

  private def extractText(file: File): Try[String] = Try {
    Source.fromFile(file).getLines().mkString("\n")
  }
}
