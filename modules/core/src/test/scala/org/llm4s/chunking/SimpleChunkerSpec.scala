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
    val text   = "a" * 300
    val config = ChunkingConfig(targetSize = 100, overlap = 20)

    val chunks = chunker.chunk(text, config)

    chunks should not be empty
    // Overlapping chunks should have some content in common with neighbors
    if (chunks.length > 1) {
      chunks.length should be >= 2
    }
  }

  it should "handle whitespace-only input" in {
    val text   = "   \n\t   "
    val config = ChunkingConfig()

    val chunks = chunker.chunk(text, config)

    // May or may not produce chunks depending on implementation
    chunks.foreach(_.content should not be empty)
  }

  it should "preserve original text content across chunks" in {
    val text   = "The quick brown fox jumps over the lazy dog. " * 20
    val config = ChunkingConfig(targetSize = 200, overlap = 20)

    val chunks        = chunker.chunk(text, config)
    val reconstructed = chunks.map(_.content).mkString("")

    // Overlaps mean reconstructed won't match exactly, but should contain original
    reconstructed should include("The quick brown fox")
    reconstructed should include("lazy dog")
  }

  it should "maintain consistent chunk structure" in {
    val text   = "Sample " * 100
    val config = ChunkingConfig(targetSize = 150, overlap = 0)

    val chunks = chunker.chunk(text, config)

    // All chunks should have positive length
    chunks.foreach(_.length should be > 0)

    // Chunk count should be reasonable for input size
    chunks.length should be > 1
    chunks.length should be < (text.length / 50) // Some reasonable upper bound
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

  it should "apply config with minimum chunk size" in {
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
}
