package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for DocumentChunk data class and ChunkMetadata.
 *
 * Tests comprehensive metadata handling including:
 * - Metadata builder pattern methods
 * - Heading hierarchy
 * - Source and offset tracking
 * - Code block marking
 * - DocumentChunk properties and operations
 * - Empty metadata initialization
 */
class DocumentChunkSpec extends AnyFlatSpec with Matchers {

  "DocumentChunk" should "create with minimal parameters" in {
    val chunk = DocumentChunk("Hello world", 0)

    chunk.content shouldBe "Hello world"
    chunk.index shouldBe 0
    chunk.metadata shouldBe ChunkMetadata.empty
  }

  it should "create with custom metadata" in {
    val metadata = ChunkMetadata.empty.withSource("file.txt")
    val chunk    = DocumentChunk("Content", 1, metadata)

    chunk.content shouldBe "Content"
    chunk.index shouldBe 1
    chunk.metadata shouldBe metadata
    chunk.metadata.sourceFile shouldBe Some("file.txt")
  }

  it should "calculate length correctly" in {
    DocumentChunk("", 0).length shouldBe 0
    DocumentChunk("Hello", 0).length shouldBe 5
    DocumentChunk("Hello World!", 0).length shouldBe 12
  }

  it should "calculate length for unicode content" in {
    DocumentChunk("Hello 世界", 0).length should be > 5
    // Emojis are UTF-16 surrogate pairs, so 3 emojis = 6 chars
    DocumentChunk("🌍🌎🌏", 0).length shouldBe 6
  }

  it should "detect empty content" in {
    DocumentChunk("", 0).isEmpty shouldBe true
    DocumentChunk("", 0).nonEmpty shouldBe false
  }

  it should "detect non-empty content" in {
    DocumentChunk(" ", 0).isEmpty shouldBe false
    DocumentChunk("x", 0).isEmpty shouldBe false
    DocumentChunk("content", 0).nonEmpty shouldBe true
  }

  it should "preserve sequential index" in {
    val chunks = Seq(
      DocumentChunk("chunk1", 0),
      DocumentChunk("chunk2", 1),
      DocumentChunk("chunk3", 2)
    )

    chunks.zipWithIndex.foreach { case (chunk, idx) =>
      chunk.index shouldBe idx
    }
  }

  it should "handle large content" in {
    val largeContent = "a" * 100000
    val chunk        = DocumentChunk(largeContent, 0)

    chunk.length shouldBe 100000
    chunk.nonEmpty shouldBe true
  }

  "ChunkMetadata" should "initialize as empty" in {
    val metadata = ChunkMetadata.empty

    metadata.sourceFile shouldBe None
    metadata.startOffset shouldBe None
    metadata.endOffset shouldBe None
    metadata.headings shouldBe empty
    metadata.isCodeBlock shouldBe false
    metadata.language shouldBe None
  }

  it should "support builder pattern for headings" in {
    val metadata = ChunkMetadata.empty
      .withHeading("Chapter 1")
      .withHeading("Section 1.1")
      .withHeading("Subsection 1.1.1")

    metadata.headings.length shouldBe 3
    metadata.headings shouldBe Seq("Chapter 1", "Section 1.1", "Subsection 1.1.1")
  }

  it should "maintain heading order" in {
    val metadata = ChunkMetadata.empty
      .withHeading("First")
      .withHeading("Second")
      .withHeading("Third")

    metadata.headings shouldBe Seq("First", "Second", "Third")
  }

  it should "support builder pattern for source file" in {
    val metadata1 = ChunkMetadata.empty.withSource("document.txt")
    metadata1.sourceFile shouldBe Some("document.txt")

    val metadata2 = metadata1.withSource("other.md")
    metadata2.sourceFile shouldBe Some("other.md")
  }

  it should "support builder pattern for offsets" in {
    val metadata = ChunkMetadata.empty.withOffsets(100, 200)

    metadata.startOffset shouldBe Some(100)
    metadata.endOffset shouldBe Some(200)
  }

  it should "support builder pattern for code block marking" in {
    val metadata = ChunkMetadata.empty.asCodeBlock(Some("python"))

    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe Some("python")
  }

  it should "mark code block without language" in {
    val metadata = ChunkMetadata.empty.asCodeBlock()

    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe None
  }

  it should "support chainable builder calls" in {
    val metadata = ChunkMetadata.empty
      .withSource("readme.md")
      .withHeading("Installation")
      .withHeading("Steps")
      .withOffsets(0, 500)
      .asCodeBlock(Some("bash"))

    metadata.sourceFile shouldBe Some("readme.md")
    metadata.headings shouldBe Seq("Installation", "Steps")
    metadata.startOffset shouldBe Some(0)
    metadata.endOffset shouldBe Some(500)
    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe Some("bash")
  }

  it should "handle multiple heading additions without duplication" in {
    val metadata = ChunkMetadata.empty
      .withHeading("Chapter")
      .withHeading("Chapter") // Same heading added twice

    metadata.headings.length shouldBe 2
    metadata.headings shouldBe Seq("Chapter", "Chapter")
  }

  it should "create case class copy with updated fields" in {
    val original = ChunkMetadata.empty
      .withSource("file.txt")
      .withHeading("Chapter 1")

    val updated = original.copy(isCodeBlock = true)

    updated.sourceFile shouldBe Some("file.txt")
    updated.headings shouldBe Seq("Chapter 1")
    updated.isCodeBlock shouldBe true
  }

  it should "handle edge case offsets" in {
    val metadata1 = ChunkMetadata.empty.withOffsets(0, 0)
    metadata1.startOffset shouldBe Some(0)
    metadata1.endOffset shouldBe Some(0)

    val metadata2 = ChunkMetadata.empty.withOffsets(1000000, 2000000)
    metadata2.startOffset shouldBe Some(1000000)
    metadata2.endOffset shouldBe Some(2000000)
  }

  it should "handle empty heading sequences" in {
    val metadata = ChunkMetadata.empty
    metadata.headings shouldBe empty
    metadata.headings.length shouldBe 0
  }

  it should "support query of heading hierarchy" in {
    val metadata = ChunkMetadata.empty
      .withHeading("Part I")
      .withHeading("Chapter 1")
      .withHeading("Section 1.1")

    metadata.headings.size shouldBe 3
    metadata.headings.head shouldBe "Part I"
    metadata.headings.last shouldBe "Section 1.1"
  }

  "ChunkMetadata default field values" should "be sensible" in {
    val metadata = ChunkMetadata()

    metadata.sourceFile shouldBe None
    metadata.startOffset shouldBe None
    metadata.endOffset shouldBe None
    metadata.headings should be(empty)
    metadata.isCodeBlock shouldBe false
    metadata.language shouldBe None
  }

  it should "support constructor with all parameters" in {
    val metadata = ChunkMetadata(
      sourceFile = Some("test.scala"),
      startOffset = Some(10),
      endOffset = Some(100),
      headings = Seq("Header1", "Header2"),
      isCodeBlock = true,
      language = Some("scala")
    )

    metadata.sourceFile shouldBe Some("test.scala")
    metadata.startOffset shouldBe Some(10)
    metadata.endOffset shouldBe Some(100)
    metadata.headings shouldBe Seq("Header1", "Header2")
    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe Some("scala")
  }

  "DocumentChunk with metadata" should "preserve all metadata fields" in {
    val metadata = ChunkMetadata.empty
      .withSource("guide.md")
      .withHeading("API Reference")
      .withOffsets(500, 1000)
      .asCodeBlock(Some("json"))

    val chunk = DocumentChunk("{ \"example\": true }", 5, metadata)

    chunk.metadata.sourceFile shouldBe Some("guide.md")
    chunk.metadata.headings shouldBe Seq("API Reference")
    chunk.metadata.startOffset shouldBe Some(500)
    chunk.metadata.endOffset shouldBe Some(1000)
    chunk.metadata.isCodeBlock shouldBe true
    chunk.metadata.language shouldBe Some("json")
  }

  it should "handle chunks with identical metadata" in {
    val metadata = ChunkMetadata.empty.withSource("file.txt")
    val chunk1   = DocumentChunk("content1", 0, metadata)
    val chunk2   = DocumentChunk("content2", 1, metadata)

    chunk1.metadata shouldBe chunk2.metadata
    (chunk1 should not).equal(chunk2) // Different content and index
  }

  "ChunkMetadata immutability patterns" should "maintain immutability" in {
    val metadata1 = ChunkMetadata.empty
    val metadata2 = metadata1.withSource("file.txt")

    // Original should be unchanged
    metadata1.sourceFile shouldBe None
    metadata2.sourceFile shouldBe Some("file.txt")
  }

  it should "prevent mutable operations on headings" in {
    val metadata = ChunkMetadata.empty.withHeading("Chapter 1")
    val headings = metadata.headings

    // Adding to the returned sequence shouldn't affect the metadata
    val newHeadings = headings :+ "Chapter 2"

    metadata.headings should have size 1
    newHeadings should have size 2
  }
}

class ChunkingConfigSpec extends AnyFlatSpec with Matchers {

  "ChunkingConfig" should "have sensible defaults" in {
    val config = ChunkingConfig()

    config.targetSize shouldBe 800
    config.maxSize shouldBe 1200
    config.overlap shouldBe 150
    config.minChunkSize shouldBe 100
    config.preserveCodeBlocks shouldBe true
    config.preserveHeadings shouldBe true
  }

  it should "validate targetSize is positive" in {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = -1)
    }
  }

  it should "validate maxSize >= targetSize" in {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 100, maxSize = 50)
    }
  }

  it should "validate overlap is non-negative and less than targetSize" in {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 100, overlap = -1)
    }

    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 100, overlap = 150)
    }
  }

  it should "validate minChunkSize is non-negative" in {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(minChunkSize = -1)
    }
  }

  it should "accept valid overlap at boundaries" in {
    val config1 = ChunkingConfig(targetSize = 100, overlap = 0)
    config1.overlap shouldBe 0

    val config2 = ChunkingConfig(targetSize = 100, overlap = 99)
    config2.overlap shouldBe 99
  }

  it should "support custom configuration" in {
    val config = ChunkingConfig(
      targetSize = 500,
      maxSize = 1000,
      overlap = 100,
      minChunkSize = 50,
      preserveCodeBlocks = false,
      preserveHeadings = false
    )

    config.targetSize shouldBe 500
    config.maxSize shouldBe 1000
    config.overlap shouldBe 100
    config.minChunkSize shouldBe 50
    config.preserveCodeBlocks shouldBe false
    config.preserveHeadings shouldBe false
  }
}
