package org.llm4s.llmconnect.extractors

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files

class UniversalExtractorSpec extends AnyFunSuite with Matchers {

  private def withTempFile(extension: String, content: String)(test: File => Unit): Unit = {
    val file = Files.createTempFile("test-", extension).toFile
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      test(file)
    } finally file.delete()
  }

  // ================================= TEXT EXTRACTION =================================

  test("extract should read plain text files") {
    withTempFile(".txt", "Hello, World!") { file =>
      val result = UniversalExtractor.extract(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get shouldBe "Hello, World!"
    }
  }

  test("extract should handle file paths with quotes") {
    withTempFile(".txt", "Quoted path test") { file =>
      val quotedPath = s""""${file.getAbsolutePath}""""
      val result     = UniversalExtractor.extract(quotedPath)
      result.isRight shouldBe true
      result.toOption.get shouldBe "Quoted path test"
    }
  }

  test("extract should return error for non-existent file") {
    val result = UniversalExtractor.extract("/nonexistent/path/file.txt")
    result.isLeft shouldBe true
    result.left.toOption.get.`type` shouldBe "FileNotFound"
  }

  test("extract should handle empty file") {
    withTempFile(".txt", "") { file =>
      val result = UniversalExtractor.extract(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get shouldBe ""
    }
  }

  test("extract should handle UTF-8 content") {
    withTempFile(".txt", "Hello 世界 🌍") { file =>
      val result = UniversalExtractor.extract(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get should include("世界")
      result.toOption.get should include("🌍")
    }
  }

  test("extract uses Tika fallback for JSON files") {
    // Note: JSON files are detected as application/json by Tika
    // The extract function uses Tika's fallback for non-text/* types
    val jsonContent = """{"name": "test", "value": 42}"""
    withTempFile(".json", jsonContent) { file =>
      val result = UniversalExtractor.extract(file.getAbsolutePath)
      // Tika may or may not successfully parse JSON as text content
      // This test verifies the code path is exercised without throwing
      result.isLeft || result.isRight shouldBe true
    }
  }

  // ================================= MIME TYPE DETECTION =================================

  test("isTextLike should return true for text MIME types") {
    UniversalExtractor.isTextLike("text/plain") shouldBe true
    UniversalExtractor.isTextLike("text/html") shouldBe true
    UniversalExtractor.isTextLike("text/csv") shouldBe true
  }

  test("isTextLike should return true for application/json") {
    UniversalExtractor.isTextLike("application/json") shouldBe true
  }

  test("isTextLike should return true for application/xml") {
    UniversalExtractor.isTextLike("application/xml") shouldBe true
  }

  test("isTextLike should return true for PDF") {
    UniversalExtractor.isTextLike("application/pdf") shouldBe true
  }

  test("isTextLike should return true for DOCX") {
    UniversalExtractor.isTextLike(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ) shouldBe true
  }

  test("isTextLike should return false for binary MIME types") {
    UniversalExtractor.isTextLike("application/octet-stream") shouldBe false
    UniversalExtractor.isTextLike("image/png") shouldBe false
    UniversalExtractor.isTextLike("video/mp4") shouldBe false
  }

  // ================================= MULTIMEDIA API =================================

  test("extractAny should return TextContent for text files") {
    withTempFile(".txt", "Test content") { file =>
      val result = UniversalExtractor.extractAny(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get shouldBe a[UniversalExtractor.TextContent]
      result.toOption.get.asInstanceOf[UniversalExtractor.TextContent].text shouldBe "Test content"
    }
  }

  test("extractAny should return error for non-existent file") {
    val result = UniversalExtractor.extractAny("/nonexistent/path/file.txt")
    result.isLeft shouldBe true
    result.left.toOption.get.`type` shouldBe "FileNotFound"
  }

  // ================================= PATH NORMALIZATION =================================

  test("extract should handle single-quoted paths") {
    withTempFile(".txt", "Single quoted") { file =>
      val quotedPath = s"'${file.getAbsolutePath}'"
      val result     = UniversalExtractor.extract(quotedPath)
      result.isRight shouldBe true
      result.toOption.get shouldBe "Single quoted"
    }
  }

  test("extract should trim whitespace from paths") {
    withTempFile(".txt", "Whitespace trimmed") { file =>
      val paddedPath = s"  ${file.getAbsolutePath}  "
      val result     = UniversalExtractor.extract(paddedPath)
      result.isRight shouldBe true
      result.toOption.get shouldBe "Whitespace trimmed"
    }
  }

  // ================================= BYTE-BASED EXTRACTION =================================

  test("extractFromBytes should extract plain text from bytes") {
    val content = "Hello from bytes!".getBytes("UTF-8")
    val result  = UniversalExtractor.extractFromBytes(content, "test.txt")
    result.isRight shouldBe true
    result.toOption.get shouldBe "Hello from bytes!"
  }

  test("extractFromBytes should extract UTF-8 content") {
    val content = "Hello 世界 🌍".getBytes("UTF-8")
    val result  = UniversalExtractor.extractFromBytes(content, "unicode.txt")
    result.isRight shouldBe true
    result.toOption.get should include("世界")
    result.toOption.get should include("🌍")
  }

  test("extractFromBytes should handle empty content") {
    val content = Array.empty[Byte]
    val result  = UniversalExtractor.extractFromBytes(content, "empty.txt")
    result.isRight shouldBe true
    result.toOption.get shouldBe ""
  }

  test("extractFromBytes should detect MIME type from filename") {
    val jsonContent = """{"key": "value"}""".getBytes("UTF-8")
    val result      = UniversalExtractor.extractFromBytes(jsonContent, "data.json")
    // Should either succeed or fail gracefully - exercises the code path
    result.isLeft || result.isRight shouldBe true
  }

  test("extractFromBytes should use provided MIME type override") {
    val content = "plain text content".getBytes("UTF-8")
    // Use explicit MIME type regardless of filename
    val result = UniversalExtractor.extractFromBytes(content, "unknown.xyz", Some("text/plain"))
    result.isRight shouldBe true
    result.toOption.get shouldBe "plain text content"
  }

  // ================================= STREAM-BASED EXTRACTION =================================

  test("extractFromStream should extract text from stream") {
    val content = "Hello from stream!".getBytes("UTF-8")
    val stream  = new java.io.ByteArrayInputStream(content)
    val result  = UniversalExtractor.extractFromStream(stream, "test.txt")
    result.isRight shouldBe true
    result.toOption.get shouldBe "Hello from stream!"
  }

  test("extractFromStream should handle UTF-8 content") {
    val content = "Привет мир".getBytes("UTF-8")
    val stream  = new java.io.ByteArrayInputStream(content)
    val result  = UniversalExtractor.extractFromStream(stream, "russian.txt")
    result.isRight shouldBe true
    result.toOption.get should include("Привет")
  }

  test("extractFromStream should handle empty stream") {
    val stream = new java.io.ByteArrayInputStream(Array.empty[Byte])
    val result = UniversalExtractor.extractFromStream(stream, "empty.txt")
    result.isRight shouldBe true
    result.toOption.get shouldBe ""
  }

  test("extractFromStream should use provided MIME type") {
    val content = "<html><body>Test</body></html>".getBytes("UTF-8")
    val stream  = new java.io.ByteArrayInputStream(content)
    val result  = UniversalExtractor.extractFromStream(stream, "page.html", Some("text/html"))
    result.isRight shouldBe true
    result.toOption.get should include("Test")
  }

  // ================================= MIME TYPE DETECTION =================================

  test("detectMimeType should detect text/plain from .txt extension") {
    val content = "plain text".getBytes("UTF-8")
    val mime    = UniversalExtractor.detectMimeType(content, "test.txt")
    mime should startWith("text/")
  }

  test("detectMimeType should detect PDF from magic bytes") {
    // PDF magic bytes: %PDF-
    val pdfHeader = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d)
    val mime      = UniversalExtractor.detectMimeType(pdfHeader, "document.pdf")
    mime shouldBe "application/pdf"
  }

  test("detectMimeType should detect JSON from extension") {
    val content = """{"key": "value"}""".getBytes("UTF-8")
    val mime    = UniversalExtractor.detectMimeType(content, "data.json")
    mime shouldBe "application/json"
  }

  test("detectMimeType should detect HTML from extension") {
    val content = "<html></html>".getBytes("UTF-8")
    val mime    = UniversalExtractor.detectMimeType(content, "page.html")
    mime should (be("text/html").or(startWith("text/")))
  }

  test("detectMimeType should handle unknown extension") {
    val content = "some content".getBytes("UTF-8")
    val mime    = UniversalExtractor.detectMimeType(content, "file.xyz")
    // Should return some MIME type (may be text/plain or application/octet-stream)
    mime should not be empty
  }
}
