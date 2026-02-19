package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for ChunkerFactory and related strategy types.
 *
 * Tests the factory for chunker creation including:
 * - Strategy creation and enumeration
 * - Strategy string parsing
 * - Factory method creation for each strategy
 * - Strategy names and properties
 * - Edge cases and invalid inputs
 */
class ChunkerFactorySpec extends AnyFlatSpec with Matchers {

  "ChunkerFactory.Strategy" should "have all expected strategies" in {
    val strategies = ChunkerFactory.Strategy.all

    strategies should contain(ChunkerFactory.Strategy.Simple)
    strategies should contain(ChunkerFactory.Strategy.Sentence)
    strategies should contain(ChunkerFactory.Strategy.Semantic)
    strategies should contain(ChunkerFactory.Strategy.Markdown)
    strategies should have size 4
  }

  it should "have proper names" in {
    ChunkerFactory.Strategy.Simple.name shouldBe "simple"
    ChunkerFactory.Strategy.Sentence.name shouldBe "sentence"
    ChunkerFactory.Strategy.Semantic.name shouldBe "semantic"
    ChunkerFactory.Strategy.Markdown.name shouldBe "markdown"
  }

  it should "parse string to strategy correctly - simple" in {
    ChunkerFactory.Strategy.fromString("simple") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("Simple") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("SIMPLE") shouldBe Some(ChunkerFactory.Strategy.Simple)
  }

  it should "parse string to strategy correctly - sentence" in {
    ChunkerFactory.Strategy.fromString("sentence") shouldBe Some(ChunkerFactory.Strategy.Sentence)
    ChunkerFactory.Strategy.fromString("Sentence") shouldBe Some(ChunkerFactory.Strategy.Sentence)
  }

  it should "parse string to strategy correctly - semantic" in {
    ChunkerFactory.Strategy.fromString("semantic") shouldBe Some(ChunkerFactory.Strategy.Semantic)
    ChunkerFactory.Strategy.fromString("Semantic") shouldBe Some(ChunkerFactory.Strategy.Semantic)
  }

  it should "parse string to strategy correctly - markdown" in {
    ChunkerFactory.Strategy.fromString("markdown") shouldBe Some(ChunkerFactory.Strategy.Markdown)
    ChunkerFactory.Strategy.fromString("Markdown") shouldBe Some(ChunkerFactory.Strategy.Markdown)
  }

  it should "return None for invalid strategy string" in {
    ChunkerFactory.Strategy.fromString("invalid") shouldBe None
    ChunkerFactory.Strategy.fromString("unknown") shouldBe None
    ChunkerFactory.Strategy.fromString("") shouldBe None
    ChunkerFactory.Strategy.fromString("   ") shouldBe None
  }

  it should "handle whitespace in strategy string parsing" in {
    ChunkerFactory.Strategy.fromString("  simple  ") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("\tsentence\n") shouldBe Some(ChunkerFactory.Strategy.Sentence)
  }

  "ChunkerFactory" should "create simple chunker" in {
    val chunker = ChunkerFactory.simple()

    chunker shouldBe a[SimpleChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create sentence chunker" in {
    val chunker = ChunkerFactory.sentence()

    chunker shouldBe a[SentenceChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "create markdown chunker" in {
    val chunker = ChunkerFactory.markdown()

    chunker shouldBe a[MarkdownChunker]
    chunker shouldBe a[DocumentChunker]
  }

  it should "all created chunkers implement DocumentChunker interface" in {
    val chunkers = Seq(
      ChunkerFactory.simple(),
      ChunkerFactory.sentence(),
      ChunkerFactory.markdown()
    )

    chunkers.foreach(chunker => chunker shouldBe a[DocumentChunker])
  }

  it should "create independent instances" in {
    val chunker1 = ChunkerFactory.simple()
    val chunker2 = ChunkerFactory.simple()

    // Different instances (though may have same type)
    chunker1 should not be theSameInstanceAs(chunker2)
  }

  it should "simple chunker handle basic text" in {
    val chunker = ChunkerFactory.simple()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("Hello world", config)

    chunks should not be empty
  }

  it should "sentence chunker handle basic text" in {
    val chunker = ChunkerFactory.sentence()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("Hello world. This is a sentence.", config)

    chunks should not be empty
  }

  it should "markdown chunker handle basic text" in {
    val chunker = ChunkerFactory.markdown()
    val config  = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk("# Header\n\nParagraph text", config)

    chunks should not be empty
  }

  "DocumentChunk" should "have length property" in {
    val chunk = DocumentChunk("Hello World", 0)

    chunk.length shouldBe 11
  }

  it should "detect empty chunks" in {
    val emptyChunk    = DocumentChunk("", 0)
    val nonEmptyChunk = DocumentChunk("content", 1)

    emptyChunk.isEmpty shouldBe true
    emptyChunk.nonEmpty shouldBe false

    nonEmptyChunk.isEmpty shouldBe false
    nonEmptyChunk.nonEmpty shouldBe true
  }

  it should "preserve metadata" in {
    val metadata = ChunkMetadata.empty.withSource("test.txt").withHeading("Introduction")
    val chunk    = DocumentChunk("content", 0, metadata)

    chunk.metadata.sourceFile shouldBe Some("test.txt")
    chunk.metadata.headings should contain("Introduction")
  }

  it should "support default metadata" in {
    val chunk = DocumentChunk("content", 0)

    chunk.metadata shouldBe ChunkMetadata.empty
  }

  "ChunkMetadata" should "add headings" in {
    val metadata = ChunkMetadata.empty
      .withHeading("Chapter 1")
      .withHeading("Section 1.1")

    metadata.headings shouldBe Seq("Chapter 1", "Section 1.1")
  }

  it should "set source file" in {
    val metadata = ChunkMetadata.empty.withSource("document.md")

    metadata.sourceFile shouldBe Some("document.md")
  }

  it should "set offsets" in {
    val metadata = ChunkMetadata.empty.withOffsets(0, 100)

    metadata.startOffset shouldBe Some(0)
    metadata.endOffset shouldBe Some(100)
  }

  it should "mark as code block" in {
    val metadata = ChunkMetadata.empty.asCodeBlock(Some("python"))

    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe Some("python")
  }

  it should "mark as code block without language" in {
    val metadata = ChunkMetadata.empty.asCodeBlock()

    metadata.isCodeBlock shouldBe true
    metadata.language shouldBe None
  }

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

  "ChunkerFactory.Strategy all variants" should "be enumerable" in {
    val all = ChunkerFactory.Strategy.all
    all should have size 4

    (all.map(_.name) should contain).allOf("simple", "sentence", "semantic", "markdown")
  }

  it should "roundtrip through string conversion" in {
    ChunkerFactory.Strategy.all.foreach { strategy =>
      ChunkerFactory.Strategy.fromString(strategy.name) shouldBe Some(strategy)
    }
  }
}
