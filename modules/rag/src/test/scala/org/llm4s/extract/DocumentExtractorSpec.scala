package org.llm4s.extract

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets

class DocumentExtractorSpec extends AnyFlatSpec with Matchers {

  val extractor: DocumentExtractor = TikaDocumentExtractor

  // ========== Plain Text Extraction ==========

  "TikaDocumentExtractor" should "extract plain text content" in {
    val content  = "Hello, World!".getBytes(StandardCharsets.UTF_8)
    val filename = "test.txt"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text shouldBe "Hello, World!"
      doc.format shouldBe DocumentFormat.PlainText
      (doc.metadata should contain).key("filename")
    }
  }

  it should "extract markdown content" in {
    val content  = "# Heading\n\nSome **bold** text.".getBytes(StandardCharsets.UTF_8)
    val filename = "readme.md"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("Heading")
      doc.text should include("bold")
      // Format detected from extension
    }
  }

  it should "extract JSON content as text" in {
    val content  = """{"key": "value", "number": 42}""".getBytes(StandardCharsets.UTF_8)
    val filename = "data.json"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("key")
      doc.text should include("value")
      doc.format shouldBe DocumentFormat.JSON
    }
  }

  it should "extract XML content as text" in {
    val content  = "<root><item>content</item></root>".getBytes(StandardCharsets.UTF_8)
    val filename = "data.xml"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map(doc => doc.text should include("content"))
  }

  it should "extract CSV content as text" in {
    val content  = "name,age\nAlice,30\nBob,25".getBytes(StandardCharsets.UTF_8)
    val filename = "data.csv"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("Alice")
      doc.text should include("30")
    }
  }

  // ========== MIME Type Detection ==========

  it should "detect MIME type from bytes and filename" in {
    val textContent = "plain text".getBytes(StandardCharsets.UTF_8)

    val mime = extractor.detectMimeType(textContent, "test.txt")
    mime should startWith("text/")
  }

  it should "detect PDF MIME type" in {
    // PDF magic bytes (%PDF-)
    val pdfHeader = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d)

    val mime = extractor.detectMimeType(pdfHeader, "document.pdf")
    mime shouldBe "application/pdf"
  }

  // ========== canExtract ==========

  it should "report ability to extract text formats" in {
    extractor.canExtract("text/plain") shouldBe true
    extractor.canExtract("text/html") shouldBe true
    extractor.canExtract("text/markdown") shouldBe true
    extractor.canExtract("application/json") shouldBe true
    extractor.canExtract("application/xml") shouldBe true
    extractor.canExtract("application/pdf") shouldBe true
    extractor.canExtract("application/vnd.openxmlformats-officedocument.wordprocessingml.document") shouldBe true
  }

  it should "report inability to extract binary formats" in {
    extractor.canExtract("image/png") shouldBe false
    extractor.canExtract("audio/mp3") shouldBe false
    extractor.canExtract("video/mp4") shouldBe false
  }

  // ========== MIME Type Override ==========

  it should "use provided MIME type instead of detection" in {
    val content  = "plain text content".getBytes(StandardCharsets.UTF_8)
    val filename = "unknown.xyz" // Extension doesn't match

    // With explicit MIME type
    val result = extractor.extract(content, filename, Some("text/plain"))

    result.isRight shouldBe true
    result.map(doc => doc.text shouldBe "plain text content")
  }

  // ========== Metadata ==========

  it should "include filename in metadata" in {
    val content  = "test content".getBytes(StandardCharsets.UTF_8)
    val filename = "document.txt"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map(doc => doc.metadata.get("filename") shouldBe Some("document.txt"))
  }

  it should "include byte length in metadata" in {
    val content  = "test content".getBytes(StandardCharsets.UTF_8)
    val filename = "document.txt"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map(doc => doc.metadata.get("byteLength") shouldBe Some(content.length.toString))
  }

  // ========== Error Handling ==========

  it should "handle empty content gracefully" in {
    val content  = Array.empty[Byte]
    val filename = "empty.txt"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map(doc => doc.text shouldBe "")
  }

  it should "handle non-UTF8 text gracefully" in {
    // Some Latin-1 encoded text (not valid UTF-8)
    val content  = Array[Byte](0xe4.toByte, 0xf6.toByte, 0xfc.toByte) // äöü in Latin-1
    val filename = "latin1.txt"

    // Should still produce some output (possibly with replacement characters)
    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
  }

  // ========== Stream-based Extraction ==========

  it should "extract text from InputStream" in {
    val content  = "Hello from stream!".getBytes(StandardCharsets.UTF_8)
    val stream   = new java.io.ByteArrayInputStream(content)
    val filename = "test.txt"

    val result = extractor.extractFromStream(stream, filename)

    result.isRight shouldBe true
    result.map(doc => doc.text shouldBe "Hello from stream!")
  }

  it should "extract UTF-8 content from InputStream" in {
    val content  = "Bonjour le monde! 你好世界".getBytes(StandardCharsets.UTF_8)
    val stream   = new java.io.ByteArrayInputStream(content)
    val filename = "unicode.txt"

    val result = extractor.extractFromStream(stream, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("Bonjour")
      doc.text should include("你好世界")
    }
  }

  it should "handle empty InputStream" in {
    val stream   = new java.io.ByteArrayInputStream(Array.empty[Byte])
    val filename = "empty.txt"

    val result = extractor.extractFromStream(stream, filename)

    result.isRight shouldBe true
    result.map(doc => doc.text shouldBe "")
  }

  it should "use provided MIME type for stream extraction" in {
    val content  = "plain text content".getBytes(StandardCharsets.UTF_8)
    val stream   = new java.io.ByteArrayInputStream(content)
    val filename = "unknown.xyz"

    val result = extractor.extractFromStream(stream, filename, Some("text/plain"))

    result.isRight shouldBe true
    result.map(doc => doc.text shouldBe "plain text content")
  }

  // ========== HTML Extraction ==========

  it should "extract text from HTML content" in {
    val content  = "<html><body><h1>Title</h1><p>Paragraph text.</p></body></html>".getBytes(StandardCharsets.UTF_8)
    val filename = "page.html"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("Title")
      doc.text should include("Paragraph")
      doc.format shouldBe DocumentFormat.HTML
    }
  }

  // ========== RST/Restructured Text ==========

  it should "extract RST content as plain text" in {
    val content =
      """
        |Title
        |=====
        |
        |This is a paragraph.
        |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    val filename = "readme.rst"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("Title")
      doc.text should include("paragraph")
    }
  }

  // ========== Large Content ==========

  it should "handle large text content" in {
    val largeContent = ("x" * 10000).getBytes(StandardCharsets.UTF_8)
    val filename     = "large.txt"

    val result = extractor.extract(largeContent, filename)

    result.isRight shouldBe true
    result.map(doc => doc.text.length shouldBe 10000)
  }

  // ========== Special Characters ==========

  it should "handle content with special characters" in {
    val content  = "Special: \t\n\r &<>\"'".getBytes(StandardCharsets.UTF_8)
    val filename = "special.txt"

    val result = extractor.extract(content, filename)

    result.isRight shouldBe true
    result.map { doc =>
      doc.text should include("\t")
      doc.text should include("&")
    }
  }

  // ========== canExtract edge cases ==========

  it should "handle unknown MIME types in canExtract" in {
    extractor.canExtract("application/unknown") shouldBe false
    extractor.canExtract("custom/type") shouldBe false
  }

  it should "handle text subtypes in canExtract" in {
    extractor.canExtract("text/x-python") shouldBe true
    extractor.canExtract("text/x-java-source") shouldBe true
    extractor.canExtract("text/css") shouldBe true
  }
}

class DocumentFormatSpec extends AnyFlatSpec with Matchers {

  "DocumentFormat.fromMimeType" should "convert PDF MIME type" in {
    DocumentFormat.fromMimeType("application/pdf") shouldBe DocumentFormat.PDF
  }

  it should "convert DOCX MIME type" in {
    DocumentFormat.fromMimeType(
      "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    ) shouldBe DocumentFormat.DOCX
  }

  it should "convert text MIME types" in {
    DocumentFormat.fromMimeType("text/plain") shouldBe DocumentFormat.PlainText
    DocumentFormat.fromMimeType("text/html") shouldBe DocumentFormat.HTML
    DocumentFormat.fromMimeType("text/csv") shouldBe DocumentFormat.CSV
    DocumentFormat.fromMimeType("text/markdown") shouldBe DocumentFormat.Markdown
  }

  it should "convert JSON and XML" in {
    DocumentFormat.fromMimeType("application/json") shouldBe DocumentFormat.JSON
    DocumentFormat.fromMimeType("application/xml") shouldBe DocumentFormat.XML
    DocumentFormat.fromMimeType("text/xml") shouldBe DocumentFormat.XML
  }

  it should "return Unknown for unrecognized types" in {
    DocumentFormat.fromMimeType("application/octet-stream") shouldBe DocumentFormat.Unknown
    DocumentFormat.fromMimeType("image/png") shouldBe DocumentFormat.Unknown
  }

  "DocumentFormat.fromExtension" should "detect format from file extension" in {
    DocumentFormat.fromExtension("report.pdf") shouldBe DocumentFormat.PDF
    DocumentFormat.fromExtension("document.docx") shouldBe DocumentFormat.DOCX
    DocumentFormat.fromExtension("readme.md") shouldBe DocumentFormat.Markdown
    DocumentFormat.fromExtension("data.json") shouldBe DocumentFormat.JSON
    DocumentFormat.fromExtension("page.html") shouldBe DocumentFormat.HTML
    DocumentFormat.fromExtension("file.txt") shouldBe DocumentFormat.PlainText
  }

  it should "handle case insensitivity" in {
    DocumentFormat.fromExtension("REPORT.PDF") shouldBe DocumentFormat.PDF
    DocumentFormat.fromExtension("Document.DOCX") shouldBe DocumentFormat.DOCX
  }

  it should "return Unknown for unrecognized extensions" in {
    DocumentFormat.fromExtension("file.xyz") shouldBe DocumentFormat.Unknown
    DocumentFormat.fromExtension("noextension") shouldBe DocumentFormat.Unknown
  }
}
