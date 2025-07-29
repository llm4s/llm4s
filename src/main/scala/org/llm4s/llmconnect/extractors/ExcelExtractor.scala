package org.llm4s.llmconnect.extractors

import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.io.FileInputStream
import scala.jdk.CollectionConverters._

object ExcelExtractor {
  def extractText(path: String): String = {
    val workbook = new XSSFWorkbook(new FileInputStream(path))
    try
      workbook
        .iterator()
        .asScala
        .flatMap { sheet =>
          sheet.iterator().asScala.map(row => row.cellIterator().asScala.map(_.toString).mkString(" | "))
        }
        .mkString("\n")
    finally
      workbook.close()
  }
}
