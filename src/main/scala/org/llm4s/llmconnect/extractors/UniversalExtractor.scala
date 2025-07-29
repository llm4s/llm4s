package org.llm4s.llmconnect.extractors

object UniversalExtractor {
  def extract(pathOrUrl: String): String =
    if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
      WebExtractor.extractText(pathOrUrl)
    } else if (pathOrUrl.endsWith(".pdf")) {
      PDFExtractor.extractText(pathOrUrl)
    } else if (pathOrUrl.endsWith(".docx")) {
      DocxExtractor.extractText(pathOrUrl)
    } else if (pathOrUrl.endsWith(".xlsx")) {
      ExcelExtractor.extractText(pathOrUrl)
    } else if (pathOrUrl.endsWith(".txt")) {
      TextExtractor.extractText(pathOrUrl)
    } else {
      throw new RuntimeException(s"Unsupported file type: $pathOrUrl")
    }
}
