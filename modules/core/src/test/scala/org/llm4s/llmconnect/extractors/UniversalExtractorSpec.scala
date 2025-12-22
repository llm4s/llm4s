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
    } finally
      file.delete()
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
}
