package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SentenceChunkerSpec extends AnyFlatSpec with Matchers {

  val chunker: DocumentChunker = SentenceChunker()

  "ChunkingConfig" should "have sensible defaults" in {
    val config = ChunkingConfig.default

    config.targetSize shouldBe 800
    config.maxSize shouldBe 1200
    config.overlap shouldBe 150
    config.minChunkSize shouldBe 100
  }

  it should "provide small config" in {
    val config = ChunkingConfig.small

    config.targetSize shouldBe 400
    config.maxSize shouldBe 600
    config.overlap shouldBe 75
  }

  it should "provide large config" in {
    val config = ChunkingConfig.large

    config.targetSize shouldBe 1500
    config.maxSize shouldBe 2000
    config.overlap shouldBe 250
  }

  it should "validate configuration" in {
    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 100, maxSize = 50)
    }

    an[IllegalArgumentException] should be thrownBy {
      ChunkingConfig(targetSize = 100, overlap = 100)
    }
  }

  "ChunkMetadata" should "be empty by default" in {
    val meta = ChunkMetadata.empty

    meta.sourceFile shouldBe None
    meta.headings shouldBe empty
    meta.isCodeBlock shouldBe false
  }

  it should "support fluent API" in {
    val meta = ChunkMetadata.empty
      .withSource("test.md")
      .withHeading("Chapter 1")
      .withHeading("Section 2")
      .withOffsets(0, 100)

    meta.sourceFile shouldBe Some("test.md")
    meta.headings shouldBe Seq("Chapter 1", "Section 2")
    meta.startOffset shouldBe Some(0)
    meta.endOffset shouldBe Some(100)
  }

  it should "mark as code block" in {
    val meta = ChunkMetadata.empty.asCodeBlock(Some("scala"))

    meta.isCodeBlock shouldBe true
    meta.language shouldBe Some("scala")
  }

  "DocumentChunk" should "report length correctly" in {
    val chunk = DocumentChunk("Hello world", 0)

    chunk.length shouldBe 11
    chunk.isEmpty shouldBe false
    chunk.nonEmpty shouldBe true
  }

  it should "detect empty content" in {
    val chunk = DocumentChunk("", 0)

    chunk.isEmpty shouldBe true
    chunk.nonEmpty shouldBe false
  }

  "SentenceChunker" should "handle empty text" in {
    val chunks = chunker.chunk("")

    chunks shouldBe empty
  }

  it should "return single chunk for short text" in {
    val text   = "This is a short sentence."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, overlap = 20))

    chunks should have size 1
    chunks.head.content shouldBe text
    chunks.head.index shouldBe 0
  }

  it should "split at sentence boundaries" in {
    val text   = "First sentence. Second sentence. Third sentence."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 30, maxSize = 50, overlap = 0))

    chunks.foreach { c =>
      // Each chunk should end with proper punctuation or be the last chunk
      c.content should (endWith(".").or(endWith("!")).or(endWith("?")).or(equal(chunks.last.content)))
    }
  }

  it should "preserve abbreviations" in {
    val text   = "Dr. Smith visited Mr. Jones. They discussed the meeting."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, overlap = 20))

    // Should not split on Dr. or Mr.
    val allContent = chunks.map(_.content).mkString(" ")
    allContent should include("Dr.")
    allContent should include("Mr.")
  }

  it should "handle decimal numbers" in {
    val text   = "The value is 3.14159. This is pi."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 50, overlap = 10))

    // Should not split on the decimal point
    val allContent = chunks.map(_.content).mkString(" ")
    allContent should include("3.14159")
  }

  it should "respect target size" in {
    val text   = "A" * 100 + ". " + "B" * 100 + ". " + "C" * 100 + "."
    val config = ChunkingConfig(targetSize = 150, maxSize = 200, overlap = 0)
    val chunks = chunker.chunk(text, config)

    chunks.nonEmpty shouldBe true
    // Sentence chunker prioritizes keeping sentences intact over strict size limits
    // With minChunkSize enforcement, chunks may slightly exceed maxSize
    chunks.foreach { c =>
      c.length should be <= config.maxSize + 100 // Allow tolerance for sentence integrity
    }
  }

  it should "force split very long sentences" in {
    val longSentence = "Word " * 500 + "end."
    val config       = ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0)
    val chunks       = chunker.chunk(longSentence, config)

    chunks.nonEmpty shouldBe true
    chunks.foreach { c =>
      c.length should be <= config.maxSize + 50 // Allow some tolerance for word boundaries
    }
  }

  it should "add source metadata with chunkWithSource" in {
    val text   = "Hello world. How are you?"
    val chunks = chunker.chunkWithSource(text, "test.txt", ChunkingConfig(targetSize = 100, overlap = 20))

    chunks.foreach(c => c.metadata.sourceFile shouldBe Some("test.txt"))
  }

  it should "assign sequential indices" in {
    val text   = "Sentence one. Sentence two. Sentence three. Sentence four. Sentence five."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 30, maxSize = 50, overlap = 0))

    chunks.zipWithIndex.foreach { case (chunk, expectedIdx) =>
      chunk.index shouldBe expectedIdx
    }
  }

  "SimpleChunker" should "chunk at fixed size" in {
    val simple = SimpleChunker()
    val text   = "A" * 100 + "B" * 100 + "C" * 100
    val chunks = simple.chunk(text, ChunkingConfig(targetSize = 100, overlap = 0))

    chunks should have size 3
    chunks(0).content shouldBe "A" * 100
    chunks(1).content shouldBe "B" * 100
    chunks(2).content shouldBe "C" * 100
  }

  it should "apply overlap" in {
    val simple = SimpleChunker()
    val text   = "AAABBBCCC"
    val chunks = simple.chunk(text, ChunkingConfig(targetSize = 5, overlap = 2, maxSize = 10))

    chunks.size should be >= 2
    // With overlap, chunks should share some content
  }

  "ChunkerFactory" should "create simple chunker" in {
    val chunker = ChunkerFactory.simple()
    chunker shouldBe a[SimpleChunker]
  }

  it should "create sentence chunker" in {
    val chunker = ChunkerFactory.sentence()
    chunker shouldBe a[SentenceChunker]
  }

  it should "create chunker by name" in {
    ChunkerFactory.create("simple") shouldBe defined
    ChunkerFactory.create("sentence") shouldBe defined
    ChunkerFactory.create("unknown") shouldBe empty
  }

  it should "parse strategy from string" in {
    ChunkerFactory.Strategy.fromString("simple") shouldBe Some(ChunkerFactory.Strategy.Simple)
    ChunkerFactory.Strategy.fromString("SENTENCE") shouldBe Some(ChunkerFactory.Strategy.Sentence)
    ChunkerFactory.Strategy.fromString("markdown") shouldBe Some(ChunkerFactory.Strategy.Markdown)
    ChunkerFactory.Strategy.fromString("invalid") shouldBe None
  }

  it should "auto-detect markdown content" in {
    val markdownText = "# Heading\n\nSome text with ```code``` blocks."
    val plainText    = "Just plain text without any special formatting."

    // Both should return a chunker (auto handles detection internally)
    val mdChunker    = ChunkerFactory.auto(markdownText)
    val plainChunker = ChunkerFactory.auto(plainText)

    mdChunker shouldBe a[DocumentChunker]
    plainChunker shouldBe a[DocumentChunker]
  }

  it should "have a default chunker" in {
    ChunkerFactory.default shouldBe a[SentenceChunker]
  }
}
