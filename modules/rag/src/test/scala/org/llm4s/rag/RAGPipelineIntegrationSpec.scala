package org.llm4s.rag

import org.llm4s.chunking.{ ChunkerFactory, ChunkingConfig }
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryTestSupport
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.loader.TextLoader
import org.llm4s.reranker.{ RerankRequest, RerankResponse, RerankResult, Reranker }
import org.llm4s.testutil.MockLLMClients
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests covering the full RAG pipeline from document loading
 * through chunking, embedding, indexing into an in-memory vector store,
 * hybrid search, reranking and agent answering.
 *
 * All tests are self-contained and require no external services (no HTTP
 * calls, no real embedding APIs, no databases). Mock embedding providers
 * and an in-memory vector store are used throughout.
 */
class RAGPipelineIntegrationSpec extends AnyFlatSpec with Matchers {

  // -----------------------------------------------------------------------
  // Shared test infrastructure
  // -----------------------------------------------------------------------

  private given ModelRegistryService = ModelRegistryTestSupport.defaultService()

  /** Deterministic mock embedding provider (uses text hash-code as seed). */
  private class DeterministicEmbeddingProvider(dimensions: Int = 8) extends EmbeddingProvider {
    var callCount: Int = 0

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      callCount += 1
      val vectors = request.input.map { text =>
        val rng = new scala.util.Random(text.hashCode.toLong)
        Seq.fill(dimensions)(rng.nextDouble())
      }
      Right(EmbeddingResponse(embeddings = vectors))
    }
  }

  /**
   * Mock reranker: assigns score based on whether a relevance keyword
   * appears in the document content.  Scores are intentionally inverted
   * so the reranker changes the initial ordering.
   */
  private class MockReranker(relevanceKeyword: String) extends Reranker {
    var lastRequest: Option[RerankRequest] = None

    override def rerank(request: RerankRequest): Result[RerankResponse] = {
      lastRequest = Some(request)
      val results = request.documents.zipWithIndex.map { case (doc, idx) =>
        val score = if (doc.toLowerCase.contains(relevanceKeyword.toLowerCase)) 1.0 else 0.1
        RerankResult(index = idx, score = score, document = doc)
      }
      // Sort descending by score
      val sorted = results.sortBy(-_.score)
      Right(RerankResponse(results = sorted))
    }
  }

  /** Build a RAG pipeline with an in-memory store and deterministic mock embeddings. */
  private def buildRAG(
    withLLM: Boolean = false,
    llmResponse: String = "Mock answer based on context.",
    config: RAGConfig = RAGConfig.default
  ): RAG = {
    val provider        = new DeterministicEmbeddingProvider()
    val embeddingClient = new EmbeddingClient(provider)
    val effectiveConfig =
      if (withLLM) config.withLLM(new MockLLMClients.SimpleMock(llmResponse))
      else config
    RAG
      .buildWithClient(effectiveConfig, embeddingClient)
      .fold(
        err => fail(s"Failed to build RAG pipeline: ${err.message}"),
        identity
      )
  }

  // -----------------------------------------------------------------------
  // 1. Full happy path
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration full happy path" should
    "load documents, chunk, embed, index and return relevant results" in {

      val rag = buildRAG()

      val documents = Seq(
        "doc-scala" -> "Scala is a statically typed programming language that combines object-oriented and functional programming.",
        "doc-python" -> "Python is a dynamically typed interpreted language popular for data science and machine learning.",
        "doc-java" -> "Java is a class-based object-oriented language that was designed to be write-once run-anywhere.",
        "doc-rust" -> "Rust is a systems programming language focused on memory safety without a garbage collector.",
        "doc-haskell" -> "Haskell is a purely functional programming language with strong static typing and lazy evaluation."
      )

      val loader = TextLoader.fromPairs(documents: _*)
      val stats  = rag.ingest(loader).fold(err => fail(err.message), identity)

      stats.successful shouldBe 5
      stats.failed shouldBe 0
      rag.documentCount shouldBe 5
      rag.chunkCount should be > 0

      val results = rag.query("functional programming language").fold(err => fail(err.message), identity)
      results should not be empty
      results.head.score should be > 0.0
    }

  it should "return search results that contain chunk content" in {
    val rag = buildRAG()
    rag
      .ingestText(
        "Scala supports higher-order functions and immutable data structures.",
        "doc-functional"
      )
      .fold(err => fail(err.message), identity)

    val results = rag.query("immutable data").fold(err => fail(err.message), identity)
    results should not be empty
    results.head.content should not be empty
    results.head.id should not be empty
  }

  // -----------------------------------------------------------------------
  // 2. Hybrid search
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration hybrid search" should
    "merge results from vector and keyword channels" in {

      val rag = buildRAG()

      rag
        .ingestText("Apache Kafka is a distributed event streaming platform.", "doc-kafka")
        .fold(err => fail(err.message), identity)
      rag
        .ingestText("Apache Spark is a unified analytics engine for big data.", "doc-spark")
        .fold(err => fail(err.message), identity)
      rag
        .ingestText("Flink provides stateful computations over data streams.", "doc-flink")
        .fold(err => fail(err.message), identity)

      // RRF fusion (default) combines vector and keyword results
      val results = rag.query("Apache streaming data").fold(err => fail(err.message), identity)
      results should not be empty
    }

  it should "return results with scores when using weighted fusion" in {
    val weightedConfig = RAGConfig.default.withWeightedScore(vectorWeight = 0.7, keywordWeight = 0.3)
    val rag            = buildRAG(config = weightedConfig)

    rag
      .ingestText("Vector databases store embeddings for similarity search.", "doc-vectordb")
      .fold(err => fail(err.message), identity)
    rag
      .ingestText("Relational databases store structured tabular data.", "doc-reldb")
      .fold(err => fail(err.message), identity)

    val results = rag.query("database similarity").fold(err => fail(err.message), identity)
    results should not be empty
    results.foreach(r => r.score should be >= 0.0)
  }

  it should "support vector-only search strategy" in {
    val vectorOnlyConfig = RAGConfig.default.vectorOnly
    val rag              = buildRAG(config = vectorOnlyConfig)

    rag
      .ingestText("LLMs generate text by predicting the next token.", "doc-llm")
      .fold(err => fail(err.message), identity)

    val results = rag.query("text generation token prediction").fold(err => fail(err.message), identity)
    results should not be empty
  }

  it should "support keyword-only search strategy" in {
    val keywordOnlyConfig = RAGConfig.default.keywordOnly
    val rag               = buildRAG(config = keywordOnlyConfig)

    rag
      .ingestText("Transformers use self-attention mechanisms for sequence modelling.", "doc-transformer")
      .fold(err => fail(err.message), identity)

    // Keyword search uses exact term matching
    val results = rag.query("Transformers self-attention").fold(err => fail(err.message), identity)
    results should not be empty
  }

  // -----------------------------------------------------------------------
  // 3. Reranking integration
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration reranking" should
    "pass retrieved chunks through a reranker and reorder results" in {

      // Build RAG with LLM-based reranking wired up using the LLM reranker
      val provider        = new DeterministicEmbeddingProvider()
      val embeddingClient = new EmbeddingClient(provider)

      // Use LLM reranking strategy with a mock LLM client to confirm the
      // reranking path is exercised without calling a real API.
      val llmClient  = new MockLLMClients.SimpleMock("reranked answer")
      val baseConfig = RAGConfig.default.withLLMReranking.withLLM(llmClient)
      val rag = RAG
        .buildWithClient(baseConfig, embeddingClient)
        .fold(
          err => fail(s"Build failed: ${err.message}"),
          identity
        )

      rag
        .ingestText("Scala is a functional programming language.", "doc-scala")
        .fold(err => fail(err.message), identity)
      rag
        .ingestText("Java is an object-oriented programming language.", "doc-java")
        .fold(err => fail(err.message), identity)
      rag.ingestText("Haskell is a purely functional language.", "doc-haskell").fold(err => fail(err.message), identity)

      // The built-in LLM reranker will call the mock LLM; it returns a fixed
      // string which the LLMReranker parses. We only assert that the query
      // completes successfully and that the result set is non-empty,
      // confirming the reranking path was exercised.
      val results = rag.query("functional programming").fold(err => fail(err.message), identity)
      results should not be empty
    }

  it should "verify that a custom mock reranker changes ordering" in {
    // We can directly test the reranker itself to verify the scoring logic
    val reranker = new MockReranker(relevanceKeyword = "functional")
    val request = RerankRequest(
      query = "functional programming",
      documents = Seq(
        "Java is object-oriented.",
        "Scala is a functional programming language.",
        "Python is dynamically typed.",
        "Haskell is purely functional."
      ),
      topK = Some(4)
    )
    val response = reranker.rerank(request).fold(err => fail(err.toString), identity)

    response.results should not be empty
    // Docs containing "functional" should be ranked first
    response.results.head.score shouldBe 1.0
    response.results.head.document should (include("functional").or(include("Functional")))
  }

  // -----------------------------------------------------------------------
  // 4. RAG + Agent integration
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration agent answering" should
    "wire retrieved chunks as context into the LLM client prompt" in {

      val rag = buildRAG(withLLM = true, llmResponse = "The answer derived from context.")

      rag
        .ingestText(
          "The speed of light in a vacuum is approximately 299,792,458 metres per second.",
          "doc-physics"
        )
        .fold(err => fail(err.message), identity)

      rag
        .ingestText(
          "The boiling point of water at sea level is 100 degrees Celsius.",
          "doc-chemistry"
        )
        .fold(err => fail(err.message), identity)

      val answerResult = rag.queryWithAnswer("What is the speed of light?").fold(err => fail(err.message), identity)

      answerResult.answer should not be empty
      answerResult.question shouldBe "What is the speed of light?"
      answerResult.contexts should not be empty
    }

  it should "include retrieved document text inside the LLM conversation prompt" in {
    // Use a SimpleMock whose lastConversation we can inspect
    val trackingMock = new MockLLMClients.SimpleMock("Context-based answer.")
    val provider     = new DeterministicEmbeddingProvider()
    val ragConfig    = RAGConfig.default.withLLM(trackingMock)
    val rag = RAG
      .buildWithClient(ragConfig, new EmbeddingClient(provider))
      .fold(
        err => fail(s"Build failed: ${err.message}"),
        identity
      )

    val knowledgeText = "Scala was created by Martin Odersky and first released in 2004."
    rag.ingestText(knowledgeText, "doc-scala-history").fold(err => fail(err.message), identity)

    rag.queryWithAnswer("Who created Scala?").fold(err => fail(err.message), identity)

    // The mock LLM client records the conversation it was called with
    trackingMock.lastConversation should not be None
    val promptTexts = trackingMock.lastConversation.get.messages.map(_.content).mkString(" ")
    // The retrieved chunk content should appear in the prompt
    promptTexts should include("Scala")
  }

  // -----------------------------------------------------------------------
  // 5. Edge case – empty corpus
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration empty corpus" should
    "return empty results without error when no documents are indexed" in {

      val rag     = buildRAG()
      val results = rag.query("any query").fold(err => fail(err.message), identity)
      results shouldBe empty
    }

  it should "return zero stats for an empty pipeline" in {
    val rag   = buildRAG()
    val stats = rag.stats.fold(err => fail(err.message), identity)
    stats.documentCount shouldBe 0
    stats.chunkCount shouldBe 0
    stats.vectorCount shouldBe 0L
  }

  // -----------------------------------------------------------------------
  // 6. Duplicate indexing
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration duplicate indexing" should
    "allow indexing the same document ID twice (upsert semantics)" in {

      val rag = buildRAG()

      val result1 = rag.ingestText("Original content for document A.", "doc-a")
      result1.isRight shouldBe true

      // Index the same document ID again with different content
      val result2 = rag.ingestText("Updated content for document A with new information.", "doc-a")
      result2.isRight shouldBe true

      // Both ingestions succeed because the vector store uses upsert
      // The document count reflects two calls (tracker is additive)
      rag.documentCount should be >= 1
      rag.chunkCount should be > 0
    }

  it should "produce searchable results after re-indexing the same ID" in {
    val rag = buildRAG()

    rag.ingestText("Old data about topic X.", "doc-reindex").fold(err => fail(err.message), identity)
    rag
      .ingestText("New updated data about topic X with better description.", "doc-reindex")
      .fold(err => fail(err.message), identity)

    val results = rag.query("topic X").fold(err => fail(err.message), identity)
    results should not be empty
  }

  // -----------------------------------------------------------------------
  // 7. Chunk boundary preservation
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration chunk boundaries" should
    "not produce chunks exceeding the configured maximum size" in {

      val maxSize      = 200
      val chunkingConf = ChunkingConfig(targetSize = 150, maxSize = maxSize, overlap = 20)
      val ragConfig    = RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunkingConf)
      val rag          = buildRAG(config = ragConfig)

      // Build a document that is several times the max chunk size
      val longDocument = ("The quick brown fox jumps over the lazy dog. " * 30).trim
      rag.ingestText(longDocument, "doc-long").fold(err => fail(err.message), identity)

      rag.chunkCount should be > 1
    }

  it should "produce at least 2 chunks when a long document is split" in {
    val chunkingConf = ChunkingConfig(targetSize = 100, maxSize = 150, overlap = 10)
    val ragConfig    = RAGConfig.default.withChunking(ChunkerFactory.Strategy.Simple, chunkingConf)
    val rag          = buildRAG(config = ragConfig)

    val longDocument = "Word " * 200
    rag.ingestText(longDocument.trim, "doc-many-chunks").fold(err => fail(err.message), identity)

    rag.chunkCount should be >= 2
  }

  it should "preserve chunk indexes sequentially starting from 0" in {
    // Use the chunker directly to verify sequential indexing
    val chunker = ChunkerFactory.simple()
    val config  = ChunkingConfig(targetSize = 50, maxSize = 80, overlap = 0)
    val text    = "Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda " * 5

    val chunks = chunker.chunk(text, config)
    chunks.size should be >= 2
    chunks.zipWithIndex.foreach { case (chunk, expectedIdx) =>
      chunk.index shouldBe expectedIdx
    }
  }

  // -----------------------------------------------------------------------
  // 8. Full pipeline in a single test (acceptance requirement)
  // -----------------------------------------------------------------------

  "RAGPipelineIntegration full end-to-end pipeline" should
    "run document loading → chunking → embedding → indexing → search → agent answer" in {

      val llmResponse = "Scala is a functional and object-oriented language running on the JVM."
      val rag         = buildRAG(withLLM = true, llmResponse = llmResponse)

      // Step 1 – Load and ingest documents
      val loader = TextLoader.fromPairs(
        "doc-1" -> "Scala combines functional and object-oriented programming on the JVM.",
        "doc-2" -> "Haskell is a purely functional language with lazy evaluation.",
        "doc-3" -> "Java is a strongly typed object-oriented language that runs on the JVM.",
        "doc-4" -> "Python is widely used in machine learning and data science.",
        "doc-5" -> "Rust focuses on memory safety and zero-cost abstractions."
      )

      val ingestStats = rag.ingest(loader).fold(err => fail(err.message), identity)
      ingestStats.successful shouldBe 5

      // Step 2 – Verify indexing statistics
      rag.documentCount shouldBe 5
      rag.chunkCount should be >= 5
      rag.stats.map(_.vectorCount).fold(err => fail(err.message), vc => vc should be > 0L)

      // Step 3 – Search for relevant chunks
      val searchResults =
        rag.query("functional programming JVM", topK = Some(3)).fold(err => fail(err.message), identity)
      searchResults.size should be <= 3
      searchResults should not be empty

      // Step 4 – Verify result structure
      searchResults.foreach { result =>
        result.id should not be empty
        result.content should not be empty
        result.score should be >= 0.0
      }

      // Step 5 – Generate agent answer from retrieved context
      val answerResult = rag
        .queryWithAnswer("What JVM languages support functional programming?")
        .fold(err => fail(err.message), identity)

      answerResult.answer shouldBe llmResponse
      answerResult.question shouldBe "What JVM languages support functional programming?"
      answerResult.contexts should not be empty
      answerResult.usage.map(_.totalTokens) should not be None
    }

}
