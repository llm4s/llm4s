package org.llm4s.chunking

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for SimpleChunker.
 *
 * Tests the character-based chunking implementation including:
 * - Basic chunk creation
 * - Chunk size constraints
 * - Overlap handling
 * - Empty and edge case inputs
 * - Index progression
 * - Metadata preservation
 */
class SimpleChunkerSpec extends AnyFlatSpec with Matchers {

  private val chunker = SimpleChunker()

  "SimpleChunker" should "create chunks from basic text" in {
    val text   = "Hello world! This is a simple test."
    val config = ChunkingConfig(targetSize = 10, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach(_.content should not be empty)
  }

  it should "handle empty input" in {
    val text   = ""
    val config = ChunkingConfig()

    val chunks = chunker.chunk(text, config)

    chunks shouldBe empty
  }

  it should "respect target chunk size" in {
    val text   = "a" * 1000 // 1000 character string
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach { chunk =>
      chunk.content.length should be <= (config.targetSize * 2) // Allow some tolerance
    }
  }

  it should "create proper chunk indices" in {
    val text   = "a" * 500
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks.zipWithIndex.foreach { case (chunk, idx) =>
      chunk.index shouldBe idx
    }
  }

  it should "preserve metadata structure" in {
    val text   = "Sample text for testing"
    val config = ChunkingConfig()

    val chunks = chunker.chunk(text, config)

    chunks.foreach { chunk =>
      chunk.metadata shouldBe ChunkMetadata.empty
      chunk.metadata.sourceFile shouldBe None
      chunk.metadata.isCodeBlock shouldBe false
    }
  }

  it should "handle single word input" in {
    val text   = "Hello"
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should have size 1
    chunks.head.content shouldBe "Hello"
    chunks.head.index shouldBe 0
  }

  it should "handle very small target size" in {
    val text   = "Hello"
    val config = ChunkingConfig(targetSize = 2, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.head.content.length should be > 0
  }

  it should "handle overlap parameter" in {
    // Use non-homogeneous input so the content-equality check can actually
    // detect a missing or wrong overlap (all-same chars would pass trivially).
    val text   = ("abcde" * 60).take(300)
    val config = ChunkingConfig(targetSize = 100, overlap = 20)

    val chunks = chunker.chunk(text, config)

    // With 300 chars, targetSize 100, overlap 20:
    // Chunk positions: [0-99], [80-179], [160-259], [240-299]
    // Step size = targetSize - overlap = 100 - 20 = 80
    // Expected chunks: 4
    chunks should have size 4

    // Verify overlap actually works: adjacent chunks should share content
    // The last overlap characters of chunk N should match the first overlap characters of chunk N+1
    for (i <- 0 until (chunks.length - 1)) {
      val overlapFromEnd   = chunks(i).content.takeRight(config.overlap)
      val overlapFromStart = chunks(i + 1).content.take(config.overlap)
      overlapFromEnd shouldBe overlapFromStart
    }
  }

  it should "handle whitespace-only input" in {
    val text   = "   \n\t   "
    val config = ChunkingConfig()

    val chunks = chunker.chunk(text, config)

    // SimpleChunker only short-circuits on text.isEmpty; whitespace-only input is non-empty
    // so it is passed through unchanged as a single chunk.
    chunks should have size 1
    chunks.head.content shouldBe text
  }

  it should "preserve original text content across chunks" in {
    val text   = "The quick brown fox jumps over the lazy dog. " * 20
    val config = ChunkingConfig(targetSize = 200, overlap = 0)

    val chunks        = chunker.chunk(text, config)
    val reconstructed = chunks.map(_.content).mkString("")

    // With no overlap each window is disjoint, so concatenating all chunks must
    // reproduce the input exactly — not just contain a substring of it.
    reconstructed shouldBe text
  }

  it should "maintain consistent chunk structure" in {
    val text   = "Sample " * 100
    val config = ChunkingConfig(targetSize = 150, overlap = 0)

    val chunks = chunker.chunk(text, config)

    // All chunks should have positive length
    chunks.foreach(_.length should be > 0)

    // With no overlap, each window advances by exactly targetSize, so the chunk count
    // is precisely ceil(text.length / targetSize) = ceil(700 / 150) = 5
    val expectedChunks = math.ceil(text.length.toDouble / config.targetSize).toInt
    chunks.length shouldBe expectedChunks
  }

  it should "create minimum number of chunks for large overlap" in {
    val text   = "a" * 500
    val config = ChunkingConfig(targetSize = 100, overlap = 90)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
  }

  it should "handle unicode characters correctly" in {
    val text   = "Hello 世界 مرحبا мир 🌍" * 10
    val config = ChunkingConfig(targetSize = 50, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach { chunk =>
      chunk.content should not be empty
      chunk.content.length should be > 0
    }
  }

  it should "handle multiline text" in {
    val text = """First line
                 |Second line
                 |Third line
                 |Fourth line""".stripMargin * 5

    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach(_.content should not be empty)
  }

  it should "sequentially number chunks" in {
    val text   = "a" * 1000
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks.map(_.index) shouldBe (0 until chunks.length).toList
  }

  it should "handle special characters" in {
    val text   = "Special chars: !@#$%^&*()_+-=[]{}|;':\",./<>?" * 10
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach(_.content should not be empty)
  }

  it should "accept config with minimum chunk size without error" in {
    // NOTE: SimpleChunker currently passes ChunkingConfig to ChunkingUtils.chunkText
    // but minChunkSize is not enforced there — short trailing chunks are not dropped.
    // This test only verifies that passing minChunkSize doesn't throw and still produces
    // non-empty chunks. Enforcement of minChunkSize is not yet implemented in SimpleChunker.
    val text   = "a" * 500
    val config = ChunkingConfig(targetSize = 80, minChunkSize = 50, overlap = 0)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.foreach(chunk => chunk.length should be > 0)
  }

  it should "handle very large text" in {
    val text   = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " * 1000
    val config = ChunkingConfig(targetSize = 500, overlap = 50)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    chunks.length should be > 1
  }

  it should "tag every chunk with the source file via chunkWithSource" in {
    val text   = "Hello world! This is a simple test." * 20
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val chunks = chunker.chunkWithSource(text, "myfile.txt", config)

    chunks should not be empty
    chunks.foreach(_.metadata.sourceFile shouldBe Some("myfile.txt"))
  }

  it should "produce identical content chunks from chunkWithSource as from chunk" in {
    val text   = "Hello world! This is a simple test." * 20
    val config = ChunkingConfig(targetSize = 100, overlap = 0)

    val plain  = chunker.chunk(text, config)
    val tagged = chunker.chunkWithSource(text, "myfile.txt", config)

    tagged.map(_.content) shouldBe plain.map(_.content)
    tagged.map(_.index) shouldBe plain.map(_.index)
  }
}
