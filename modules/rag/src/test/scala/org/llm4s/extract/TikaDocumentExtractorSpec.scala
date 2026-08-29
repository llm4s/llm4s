package org.llm4s.extract

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class TikaDocumentExtractorSpec extends AnyFunSuite with Matchers {

  private def withTempFile(extension: String, content: String)(test: File => Unit): Unit = {
    val file = Files.createTempFile("test-", extension).toFile
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      test(file)
    } finally file.delete()
  }

  // ================================= PATH-BASED EXTRACTION =================================

  test("extractFromPath should read plain text files") {
    withTempFile(".txt", "Hello, World!") { file =>
      val result = TikaDocumentExtractor.extractFromPath(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get.text shouldBe "Hello, World!"
      result.toOption.get.format shouldBe DocumentFormat.PlainText
    }
  }

  test("extractFromPath should carry filename and MIME type in the metadata") {
    withTempFile(".txt", "Hello, World!") { file =>
      val doc = TikaDocumentExtractor.extractFromPath(file.getAbsolutePath).toOption.get
      doc.metadata("filename") shouldBe file.getName
      doc.metadata("mimeType") should startWith("text/")
      doc.metadata("byteLength") shouldBe "13"
    }
  }

  test("extractFromPath should return an error for a non-existent file") {
    val result = TikaDocumentExtractor.extractFromPath("/nonexistent/path/file.txt")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("File not found")
  }

  test("extractFromPath should return an error for a directory") {
    val dir    = Files.createTempDirectory("extract-").toFile
    val result = TikaDocumentExtractor.extractFromPath(dir.getAbsolutePath)
    try {
      result.isLeft shouldBe true
      result.left.toOption.get.message should include("not a regular file")
    } finally dir.delete()
  }

  test("extractFromPath should handle an empty file") {
    withTempFile(".txt", "") { file =>
      val result = TikaDocumentExtractor.extractFromPath(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get.text shouldBe ""
    }
  }

  test("extractFromPath should handle UTF-8 content") {
    withTempFile(".txt", "Hello 世界 🌍") { file =>
      val result = TikaDocumentExtractor.extractFromPath(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get.text should include("世界")
      result.toOption.get.text should include("🌍")
    }
  }

  test("extractFromPath should read JSON as text") {
    val jsonContent = """{"name": "test", "value": 42}"""
    withTempFile(".json", jsonContent) { file =>
      val result = TikaDocumentExtractor.extractFromPath(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get.text shouldBe jsonContent
      result.toOption.get.format shouldBe DocumentFormat.JSON
    }
  }

  // ================================= PATH NORMALIZATION =================================

  test("extractFromPath should handle double-quoted paths") {
    withTempFile(".txt", "Quoted path test") { file =>
      val result = TikaDocumentExtractor.extractFromPath(s""""${file.getAbsolutePath}"""")
      result.isRight shouldBe true
      result.toOption.get.text shouldBe "Quoted path test"
    }
  }

  test("extractFromPath should handle single-quoted paths") {
    withTempFile(".txt", "Single quoted") { file =>
      val result = TikaDocumentExtractor.extractFromPath(s"'${file.getAbsolutePath}'")
      result.isRight shouldBe true
      result.toOption.get.text shouldBe "Single quoted"
    }
  }

  test("extractFromPath should trim whitespace from paths") {
    withTempFile(".txt", "Whitespace trimmed") { file =>
      val result = TikaDocumentExtractor.extractFromPath(s"  ${file.getAbsolutePath}  ")
      result.isRight shouldBe true
      result.toOption.get.text shouldBe "Whitespace trimmed"
    }
  }

  test("normalizePath should strip only a matching pair of quotes") {
    // Compared against the unquoted form rather than a literal: normalizePath resolves to an
    // absolute path, and what that looks like is platform-specific.
    val plain = TikaDocumentExtractor.normalizePath("/tmp/a.txt")
    TikaDocumentExtractor.normalizePath("\"/tmp/a.txt\"") shouldBe plain
    TikaDocumentExtractor.normalizePath("'/tmp/a.txt'") shouldBe plain
    TikaDocumentExtractor.normalizePath("  /tmp/a.txt  ") shouldBe plain

    // An apostrophe in the filename is part of the name, not a quote to strip.
    TikaDocumentExtractor.normalizePath("/tmp/rory's.txt").getName shouldBe "rory's.txt"
    TikaDocumentExtractor.normalizePath("\"/tmp/rory's.txt\"").getName shouldBe "rory's.txt"
  }

  // ================================= canExtract =================================

  test("canExtract should return true for text MIME types") {
    TikaDocumentExtractor.canExtract("text/plain") shouldBe true
    TikaDocumentExtractor.canExtract("text/html") shouldBe true
    TikaDocumentExtractor.canExtract("text/csv") shouldBe true
  }

  test("canExtract should return true for application/json") {
    TikaDocumentExtractor.canExtract("application/json") shouldBe true
  }

  test("canExtract should return true for application/xml") {
    TikaDocumentExtractor.canExtract("application/xml") shouldBe true
  }

  test("canExtract should return true for PDF") {
    TikaDocumentExtractor.canExtract("application/pdf") shouldBe true
  }

  test("canExtract should return true for DOCX and legacy DOC") {
    TikaDocumentExtractor.canExtract(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ) shouldBe true
    TikaDocumentExtractor.canExtract("application/msword") shouldBe true
  }

  test("canExtract should return false for binary MIME types") {
    TikaDocumentExtractor.canExtract("application/octet-stream") shouldBe false
    TikaDocumentExtractor.canExtract("image/png") shouldBe false
    TikaDocumentExtractor.canExtract("video/mp4") shouldBe false
  }

  // ================================= BYTE-BASED EXTRACTION =================================

  test("extract should extract plain text from bytes") {
    val content = "Hello from bytes!".getBytes("UTF-8")
    val result  = TikaDocumentExtractor.extract(content, "test.txt")
    result.isRight shouldBe true
    result.toOption.get.text shouldBe "Hello from bytes!"
  }

  test("extract should extract UTF-8 content from bytes") {
    val content = "Hello 世界 🌍".getBytes("UTF-8")
    val result  = TikaDocumentExtractor.extract(content, "unicode.txt")
    result.isRight shouldBe true
    result.toOption.get.text should include("世界")
    result.toOption.get.text should include("🌍")
  }

  test("extract should handle empty content") {
    val result = TikaDocumentExtractor.extract(Array.empty[Byte], "empty.txt")
    result.isRight shouldBe true
    result.toOption.get.text shouldBe ""
  }

  test("extract should detect JSON from the filename") {
    val jsonContent = """{"key": "value"}""".getBytes("UTF-8")
    val result      = TikaDocumentExtractor.extract(jsonContent, "data.json")
    result.isRight shouldBe true
    result.toOption.get.format shouldBe DocumentFormat.JSON
  }

  test("extract should use the provided MIME type override") {
    val content = "plain text content".getBytes("UTF-8")
    val result  = TikaDocumentExtractor.extract(content, "unknown.xyz", Some("text/plain"))
    result.isRight shouldBe true
    result.toOption.get.text shouldBe "plain text content"
  }

  // ================================= STREAM-BASED EXTRACTION =================================

  test("extractFromStream should extract text from a stream") {
    val stream = new java.io.ByteArrayInputStream("Hello from stream!".getBytes("UTF-8"))
    val result = TikaDocumentExtractor.extractFromStream(stream, "test.txt")
    result.isRight shouldBe true
    result.toOption.get.text shouldBe "Hello from stream!"
  }

  test("extractFromStream should handle UTF-8 content") {
    val stream = new java.io.ByteArrayInputStream("Привет мир".getBytes("UTF-8"))
    val result = TikaDocumentExtractor.extractFromStream(stream, "russian.txt")
    result.isRight shouldBe true
    result.toOption.get.text should include("Привет")
  }

  test("extractFromStream should handle an empty stream") {
    val stream = new java.io.ByteArrayInputStream(Array.empty[Byte])
    val result = TikaDocumentExtractor.extractFromStream(stream, "empty.txt")
    result.isRight shouldBe true
    result.toOption.get.text shouldBe ""
  }

  test("extractFromStream should use the provided MIME type") {
    val stream = new java.io.ByteArrayInputStream("<html><body>Test</body></html>".getBytes("UTF-8"))
    val result = TikaDocumentExtractor.extractFromStream(stream, "page.html", Some("text/html"))
    result.isRight shouldBe true
    result.toOption.get.text should include("Test")
  }

  // ================================= MIME TYPE DETECTION =================================

  test("detectMimeType should detect text/plain from .txt extension") {
    val mime = TikaDocumentExtractor.detectMimeType("plain text".getBytes("UTF-8"), "test.txt")
    mime should startWith("text/")
  }

  test("detectMimeType should detect PDF from magic bytes") {
    // PDF magic bytes: %PDF-
    val pdfHeader = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d)
    TikaDocumentExtractor.detectMimeType(pdfHeader, "document.pdf") shouldBe "application/pdf"
  }

  test("detectMimeType should detect JSON from extension") {
    val content = """{"key": "value"}""".getBytes("UTF-8")
    TikaDocumentExtractor.detectMimeType(content, "data.json") shouldBe "application/json"
  }

  test("detectMimeType should detect HTML from extension") {
    val mime = TikaDocumentExtractor.detectMimeType("<html></html>".getBytes("UTF-8"), "page.html")
    mime should (be("text/html").or(startWith("text/")))
  }

  test("detectMimeType should handle an unknown extension") {
    val mime = TikaDocumentExtractor.detectMimeType("some content".getBytes("UTF-8"), "file.xyz")
    mime should not be empty
  }

  test("detectMimeType should detect from a file on disk") {
    withTempFile(".txt", "on disk")(file => TikaDocumentExtractor.detectMimeType(file) should startWith("text/"))
  }
}
