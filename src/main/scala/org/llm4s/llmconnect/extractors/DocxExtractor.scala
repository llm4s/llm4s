package org.llm4s.llmconnect.extractors

import org.apache.poi.xwpf.usermodel.XWPFDocument

import java.io.FileInputStream
import scala.jdk.CollectionConverters._

object DocxExtractor {
  def extractText(path: String): String = {
    val doc = new XWPFDocument(new FileInputStream(path))
    try
      doc.getParagraphs.asScala.map(_.getText).mkString("\n")
    finally
      doc.close()
  }
}
