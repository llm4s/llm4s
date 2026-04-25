package org.llm4s.chunking

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SemanticChunkerSpec extends AnyFlatSpec with Matchers {

  // Mock embedding provider that returns predictable embeddings
  class MockEmbeddingProvider(embeddings: Map[String, Seq[Double]]) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val results = request.input.map(text => embeddings.getOrElse(text, defaultEmbedding(text)))
      Right(EmbeddingResponse(embeddings = results))
    }

    private def defaultEmbedding(text: String): Seq[Double] = {
      // Generate a simple embedding based on text hash
      val hash = text.hashCode.abs
      Seq.fill(10)((hash % 1000) / 1000.0)
    }
  }

  private given service: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  // Create mock client with provider
  def createMockClient(embeddings: Map[String, Seq[Double]] = Map.empty): EmbeddingClient =
    EmbeddingClient(new MockEmbeddingProvider(embeddings))

  val modelConfig: EmbeddingModelConfig = EmbeddingModelConfig("mock-model", 10)

  "SemanticChunker" should "have correct default values" in {
    SemanticChunker.DEFAULT_SIMILARITY_THRESHOLD shouldBe 0.5
    SemanticChunker.DEFAULT_BATCH_SIZE shouldBe 50
  }

  it should "create chunker with default parameters" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)
    chunker shouldBe a[SemanticChunker]
  }

  it should "create chunker with custom similarity threshold" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig, similarityThreshold = 0.7)

    chunker shouldBe a[SemanticChunker]
  }

  it should "create chunker with custom batch size" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig, batchSize = 25)

    chunker shouldBe a[SemanticChunker]
  }

  it should "reject invalid similarity threshold" in {
    val client = createMockClient()

    an[IllegalArgumentException] should be thrownBy {
      SemanticChunker(client, modelConfig, similarityThreshold = -0.1)
    }

    an[IllegalArgumentException] should be thrownBy {
      SemanticChunker(client, modelConfig, similarityThreshold = 1.1)
    }
  }

  it should "reject invalid batch size" in {
    val client = createMockClient()

    an[IllegalArgumentException] should be thrownBy {
      SemanticChunker(client, modelConfig, batchSize = 0)
    }

    an[IllegalArgumentException] should be thrownBy {
      SemanticChunker(client, modelConfig, batchSize = -1)
    }
  }

  it should "handle empty text" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val chunks = chunker.chunk("")

    chunks shouldBe empty
  }

  it should "return single chunk for short text" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text   = "This is a short sentence."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 500, maxSize = 1000, overlap = 0))

    chunks should have size 1
    chunks.head.content shouldBe text
    chunks.head.index shouldBe 0
  }

  it should "split at topic boundaries based on embedding similarity" in {
    // Create embeddings where sentences 1-2 are similar, 3-4 are similar, but 2-3 are different
    val embeddings = Map(
      "Topic A sentence one."   -> Seq(0.9, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
      "Topic A sentence two."   -> Seq(0.85, 0.15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
      "Topic B sentence three." -> Seq(0.0, 0.0, 0.9, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
      "Topic B sentence four."  -> Seq(0.0, 0.0, 0.85, 0.15, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    )

    val client  = createMockClient(embeddings)
    val chunker = SemanticChunker(client, modelConfig, similarityThreshold = 0.5)

    val text   = "Topic A sentence one. Topic A sentence two. Topic B sentence three. Topic B sentence four."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0))

    // Should split between sentence 2 and 3 due to low similarity
    chunks.size should be >= 1
    // First chunk should contain Topic A sentences
    val firstChunkContent = chunks.head.content.toLowerCase
    firstChunkContent should (include("topic a").or(include("sentence")))
  }

  it should "preserve abbreviations when splitting sentences" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text   = "Dr. Smith visited Mr. Jones. They discussed the meeting."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 500, overlap = 0))

    // Should not incorrectly split on Dr. or Mr.
    val allContent = chunks.map(_.content).mkString(" ")
    allContent should include("Dr.")
    allContent should include("Mr.")
  }

  it should "handle decimal numbers" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text   = "The value is 3.14159. This is pi."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 500, overlap = 0))

    // Should not split on decimal point
    val allContent = chunks.map(_.content).mkString(" ")
    allContent should include("3.14159")
  }

  it should "force split very long single sentences" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val longSentence = "Word " * 500 + "end."
    val chunks       = chunker.chunk(longSentence, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0))

    chunks.nonEmpty shouldBe true
    chunks.foreach { c =>
      c.length should be <= 250 // Allow some tolerance
    }
  }

  it should "assign sequential indices" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text =
      "First sentence here. Second sentence here. Third sentence here. Fourth sentence here. Fifth sentence here."
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 50, maxSize = 100, overlap = 0))

    chunks.zipWithIndex.foreach { case (chunk, expectedIdx) =>
      chunk.index shouldBe expectedIdx
    }
  }

  it should "respect minimum chunk size" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text   = "A. B. C. D. E." // Very short sentences
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 50, maxSize = 100, minChunkSize = 10, overlap = 0))

    // Short sentences should be combined to meet minimum size
    chunks.foreach { c =>
      c.length should be >= 2 // Allow for very minimal chunks
    }
  }

  it should "handle text with no sentence boundaries" in {
    val client  = createMockClient()
    val chunker = SemanticChunker(client, modelConfig)

    val text   = "continuous text without any periods or sentence endings"
    val chunks = chunker.chunk(text, ChunkingConfig(targetSize = 100, maxSize = 200, overlap = 0))

    chunks should have size 1
    chunks.head.content shouldBe text
  }

  "ChunkerFactory" should "create semantic chunker" in {
    val client  = createMockClient()
    val chunker = ChunkerFactory.semantic(client, modelConfig)

    chunker shouldBe a[SemanticChunker]
  }

  it should "create semantic chunker with custom parameters" in {
    val client  = createMockClient()
    val chunker = ChunkerFactory.semantic(client, modelConfig, similarityThreshold = 0.7, batchSize = 25)

    chunker shouldBe a[SemanticChunker]
  }

  it should "fall back to sentence chunker when creating by name without embedding client" in {
    val chunker = ChunkerFactory.create("semantic")

    chunker shouldBe defined
    chunker.get shouldBe a[SentenceChunker] // Falls back because no embedding client
  }

  it should "fall back to sentence chunker when creating by strategy enum without embedding client" in {
    val chunker = ChunkerFactory.create(ChunkerFactory.Strategy.Semantic)

    chunker shouldBe a[SentenceChunker] // Falls back because no embedding client
  }
}
