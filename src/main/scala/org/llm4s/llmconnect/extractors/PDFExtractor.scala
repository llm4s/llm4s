package org.llm4s.llmconnect.extractors

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

import java.io.File

object PDFExtractor {
  def extractText(path: String): String = {
    val doc = PDDocument.load(new File(path))
    try {
      val stripper = new PDFTextStripper()
      stripper.getText(doc)
    } finally doc.close()
  }
}
