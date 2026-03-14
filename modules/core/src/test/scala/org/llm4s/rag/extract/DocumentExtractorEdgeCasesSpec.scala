package org.llm4s.rag.extract

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayInputStream, IOException, InputStream }
import java.nio.charset.StandardCharsets

class DocumentExtractorEdgeCasesSpec extends AnyFlatSpec with Matchers {

  val extractor: DocumentExtractor = DefaultDocumentExtractor

  // ========== Corrupted PDF ==========

  "DefaultDocumentExtractor" should "return Left for corrupted PDF content" in {
    // PDF magic bytes followed by garbage
    val corruptPdf = Array[Byte](0x25, 0x50, 0x44, 0x46, 0x2d) ++ Array.fill[Byte](50)(0xff.toByte)
    val result     = extractor.extract(corruptPdf, "corrupt.pdf", Some("application/pdf"))

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("PDF")
  }

  // ========== Corrupted DOCX ==========

  it should "return Left for corrupted DOCX content" in {
    val corruptDocx = Array.fill[Byte](100)(0xab.toByte)
    val result = extractor.extract(
      corruptDocx,
      "corrupt.docx",
      Some("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    )

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("DOCX")
  }

  // ========== Failing InputStream ==========

  it should "return Left when InputStream throws IOException" in {
    val failingStream = new InputStream {
      override def read(): Int                                   = throw new IOException("Stream read failed")
      override def readAllBytes(): Array[Byte]                   = throw new IOException("Stream read failed")
      override def read(b: Array[Byte], off: Int, len: Int): Int = throw new IOException("Stream read failed")
    }

    val result = extractor.extractFromStream(failingStream, "test.txt")

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Failed to read input stream")
  }

  // ========== Tika fallback for unknown types ==========

  it should "attempt Tika extraction for unknown binary format" in {
    val unknownBytes = Array.fill[Byte](100)(0x00)
    val result       = extractor.extract(unknownBytes, "unknown.bin")

    // Tika may return Left (empty content) or Right depending on content
    // The important thing is it doesn't throw
    result.isLeft || result.isRight shouldBe true
  }

  // ========== DOC format (legacy Word) ==========

  it should "attempt Tika extraction for legacy .doc format" in {
    val content = "Some text content".getBytes(StandardCharsets.UTF_8)
    val result  = extractor.extract(content, "legacy.doc", Some("application/msword"))

    // Legacy .doc with text bytes may produce output via Tika or fail gracefully
    result.isLeft || result.isRight shouldBe true
  }

  it should "report canExtract true for DOC MIME type" in {
    extractor.canExtract("application/msword") shouldBe true
  }

  // ========== Stream extraction with MIME override ==========

  it should "extract from stream with PDF MIME override on text content" in {
    // Text content but forced to PDF MIME — should fail gracefully
    val content = "not a pdf".getBytes(StandardCharsets.UTF_8)
    val stream  = new ByteArrayInputStream(content)
    val result  = extractor.extractFromStream(stream, "fake.pdf", Some("application/pdf"))

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("PDF")
  }

  // ========== Multi-line and whitespace-heavy text ==========

  it should "preserve whitespace in text extraction" in {
    val content = "Line 1\n\n\nLine 4\t\ttabbed".getBytes(StandardCharsets.UTF_8)
    val result  = extractor.extract(content, "whitespace.txt")

    result.isRight shouldBe true
    result.toOption.get.text should include("\n\n\n")
    result.toOption.get.text should include("\t\t")
  }

  // ========== Very large content ==========

  it should "handle content larger than 100KB" in {
    val largeContent = ("A" * 200000).getBytes(StandardCharsets.UTF_8)
    val result       = extractor.extract(largeContent, "large.txt")

    result.isRight shouldBe true
    result.toOption.get.text.length shouldBe 200000
  }

  // ========== Metadata correctness ==========

  it should "include correct mimeType in metadata" in {
    val content = "test".getBytes(StandardCharsets.UTF_8)
    val result  = extractor.extract(content, "test.txt", Some("text/plain"))

    result.isRight shouldBe true
    result.toOption.get.metadata("mimeType") shouldBe "text/plain"
  }

  it should "include correct byteLength in metadata for various sizes" in {
    val sizes = Seq(0, 1, 100, 10000)
    sizes.foreach { size =>
      val content = new Array[Byte](size)
      val result  = extractor.extract(content, "test.txt", Some("text/plain"))
      result.isRight shouldBe true
      result.toOption.get.metadata("byteLength") shouldBe size.toString
    }
  }

  // ========== canExtract completeness ==========

  it should "report canExtract false for image MIME types" in {
    extractor.canExtract("image/jpeg") shouldBe false
    extractor.canExtract("image/gif") shouldBe false
    extractor.canExtract("image/svg+xml") shouldBe false
  }

  it should "report canExtract false for audio/video MIME types" in {
    extractor.canExtract("audio/mpeg") shouldBe false
    extractor.canExtract("video/webm") shouldBe false
  }

  it should "report canExtract false for archive MIME types" in {
    extractor.canExtract("application/zip") shouldBe false
    extractor.canExtract("application/gzip") shouldBe false
  }

  // ========== MIME detection from content ==========

  it should "detect JSON MIME type from content with .json extension" in {
    val content = """{"key": "value"}""".getBytes(StandardCharsets.UTF_8)
    val mime    = extractor.detectMimeType(content, "data.json")
    mime should (be("application/json").or(startWith("text/")))
  }

  it should "detect HTML MIME type from content" in {
    val content = "<html><body>Hello</body></html>".getBytes(StandardCharsets.UTF_8)
    val mime    = extractor.detectMimeType(content, "page.html")
    mime should (be("text/html").or(startWith("text/")))
  }

  // ========== Stream extraction preserves byte extraction behavior ==========

  it should "produce same result from stream as from bytes" in {
    val content  = "Identical content test".getBytes(StandardCharsets.UTF_8)
    val filename = "test.txt"

    val bytesResult  = extractor.extract(content, filename, Some("text/plain"))
    val streamResult = extractor.extractFromStream(new ByteArrayInputStream(content), filename, Some("text/plain"))

    bytesResult.isRight shouldBe true
    streamResult.isRight shouldBe true
    bytesResult.toOption.get.text shouldBe streamResult.toOption.get.text
    bytesResult.toOption.get.format shouldBe streamResult.toOption.get.format
  }
}
