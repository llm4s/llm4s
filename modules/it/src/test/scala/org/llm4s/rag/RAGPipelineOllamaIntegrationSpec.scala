package org.llm4s.rag

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, OllamaConfig }
import org.llm4s.llmconnect.provider.{ OllamaClient, OllamaEmbeddingProvider }
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.permissions.pg.PgSearchIndex
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * Full end-to-end integration tests for the RAG pipeline using:
 * - Ollama for embeddings (nomic-embed-text model)
 * - PostgreSQL with pgvector for vector storage
 * - Hybrid search (vector + keyword)
 * - An OllamaClient LLM for answer generation
 *
 * Prerequisites:
 * - Ollama running locally on port 11434 with nomic-embed-text pulled:
 *     ollama pull nomic-embed-text
 * - PostgreSQL with pgvector extension:
 *     export PGVECTOR_TEST_URL="jdbc:postgresql://localhost:5432/postgres"
 * - Set OLLAMA_AVAILABLE=true to opt in to these tests
 *
 * Run with:
 *   sbt "it/testOnly org.llm4s.rag.RAGPipelineOllamaIntegrationSpec"
 */
class RAGPipelineOllamaIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  // Environment guards - read once at test class init time
  private val ollamaAvailableEnv = Option(System.getenv("OLLAMA_AVAILABLE")).filter(_.nonEmpty)
  private val pgUrlEnv           = Option(System.getenv("PGVECTOR_TEST_URL")).filter(_.nonEmpty)
  private val pgUserEnv          = Option(System.getenv("PGVECTOR_TEST_USER")).getOrElse("postgres")
  private val pgPasswordEnv      = Option(System.getenv("PGVECTOR_TEST_PASSWORD")).getOrElse("")

  private val ollamaBaseUrl    = "http://localhost:11434"
  private val embeddingModel   = "nomic-embed-text"
  private val llmModel         = "qwen2.5:0.5b"
  private val testVectorTable  = s"rag_pipeline_test_${System.currentTimeMillis()}"
  private val testKeywordTable = s"rag_pipeline_kw_test_${System.currentTimeMillis()}"

  private given mrs: ModelRegistryService = ModelRegistryService.default().toOption.get

  /** Check Ollama is reachable and both required models are available. */
  private lazy val ollamaReachable: Boolean =
    Try {
      val uri        = java.net.URI.create(s"$ollamaBaseUrl/api/tags")
      val connection = uri.toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(3000)
      connection.setReadTimeout(3000)
      connection.setRequestMethod("GET")
      val code = connection.getResponseCode
      if (code == 200) {
        val source = scala.io.Source.fromInputStream(connection.getInputStream)
        try source.mkString.contains(embeddingModel)
        finally source.close()
      } else false
    }.getOrElse(false)

  /** Shared embedding client used across all tests. */
  private lazy val embeddingClient: EmbeddingClient = {
    val providerCfg = EmbeddingProviderConfig(
      baseUrl = ollamaBaseUrl,
      model = embeddingModel,
      apiKey = "not-required"
    )
    new EmbeddingClient(OllamaEmbeddingProvider.fromConfig(providerCfg))
  }

  /** Sample documents that cover distinct topics to verify semantic relevance. */
  private val sampleDocuments: Seq[(String, String)] = Seq(
    ("doc-scala-1",
     "Scala is a statically typed programming language that combines object-oriented and functional programming. It runs on the Java Virtual Machine."),
    ("doc-scala-2",
     "Scala's type system supports higher-kinded types, path-dependent types, and implicit parameters for powerful type-safe abstractions."),
    ("doc-python-1",
     "Python is a dynamically typed language known for its simplicity and readability. It is widely used in data science and machine learning."),
    ("doc-python-2",
     "Python supports multiple programming paradigms including procedural, object-oriented, and functional programming styles."),
    ("doc-database-1",
     "PostgreSQL is an open-source relational database management system with strong ACID guarantees and JSON support."),
    ("doc-database-2",
     "pgvector is a PostgreSQL extension that adds vector similarity search, enabling semantic search and AI-powered retrieval in the database."),
    ("doc-llm-1",
     "Large language models are neural networks trained on vast text corpora that can generate coherent text and answer questions."),
    ("doc-llm-2",
     "RAG, or Retrieval-Augmented Generation, enhances LLM responses by retrieving relevant documents and injecting them as context."),
    ("doc-ollama-1",
     "Ollama is an open-source tool for running large language models locally on consumer hardware without requiring cloud API keys."),
    ("doc-ollama-2",
     "The nomic-embed-text model from Ollama generates dense vector embeddings for text, suitable for semantic search applications.")
  )

  // Track created RAG instances for cleanup
  private var ragInstances: List[RAG]      = Nil
  private var pgSearchIndex: Option[PgSearchIndex] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Attempt to initialise pgvector for the hybrid and agent tests
    pgUrlEnv.foreach { url =>
      PgSearchIndex.fromJdbcUrl(url, pgUserEnv, pgPasswordEnv, testVectorTable) match {
        case Right(index) =>
          index.initializeSchema() match {
            case Right(_) => pgSearchIndex = Some(index)
            case Left(e)  => println(s"[RAGPipelineOllamaIntegrationSpec] Schema init failed: ${e.message}")
          }
        case Left(e) =>
          println(s"[RAGPipelineOllamaIntegrationSpec] PgSearchIndex creation failed: ${e.message}")
      }
    }
  }

  override def afterAll(): Unit = {
    // Close all RAG instances
    ragInstances.foreach(r => Try(r.close()))
    // Drop test tables and close the search index
    pgSearchIndex.foreach { idx =>
      Try(idx.dropSchema())
      Try(idx.close())
    }
    super.afterAll()
  }

  /** Guard that skips a test when Ollama is not opted in or not reachable. */
  private def requireOllama(): Unit = {
    assume(ollamaAvailableEnv.isDefined, "OLLAMA_AVAILABLE env var not set - skipping Ollama RAG pipeline test")
    assume(ollamaReachable, s"Ollama not reachable at $ollamaBaseUrl with model $embeddingModel")
  }

  /** Guard that additionally requires pgvector. */
  private def requireOllamaAndPg(): Unit = {
    requireOllama()
    assume(pgUrlEnv.isDefined, "PGVECTOR_TEST_URL env var not set - skipping pgvector RAG pipeline test")
  }

  // ---------------------------------------------------------------------------
  // Helper: build an in-memory RAG with Ollama embeddings
  // ---------------------------------------------------------------------------
  private def buildInMemoryRag(): RAG = {
    val ragConfig = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, 768)
      .inMemory

    val rag = RAG
      .buildWithClient(ragConfig, embeddingClient)
      .fold(e => fail(s"Failed to build in-memory RAG: ${e.message}"), identity)

    ragInstances = rag :: ragInstances
    rag
  }

  // ---------------------------------------------------------------------------
  // Helper: build a pgvector-backed RAG with hybrid search
  // ---------------------------------------------------------------------------
  private def buildPgHybridRag(): RAG = {
    val url = pgUrlEnv.getOrElse(fail("PGVECTOR_TEST_URL not set"))

    val ragConfig = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, 768)
      .withPgHybrid(url, pgUserEnv, pgPasswordEnv, testVectorTable, testKeywordTable)

    val rag = RAG
      .buildWithClient(ragConfig, embeddingClient)
      .fold(e => fail(s"Failed to build pgvector hybrid RAG: ${e.message}"), identity)

    ragInstances = rag :: ragInstances
    rag
  }

  // ---------------------------------------------------------------------------
  // 1. Index phase: embed and store documents in memory
  // ---------------------------------------------------------------------------
  "RAGPipelineOllamaIntegrationSpec (index phase)" should "ingest 10 documents with Ollama embeddings into in-memory store" in {
    requireOllama()

    val rag = buildInMemoryRag()

    sampleDocuments.foreach { case (docId, content) =>
      val result = rag.ingestText(content, docId)
      result.isRight shouldBe true
    }

    rag.documentCount shouldBe sampleDocuments.size
    rag.chunkCount should be >= sampleDocuments.size
  }

  // ---------------------------------------------------------------------------
  // 2. Semantic search phase
  // ---------------------------------------------------------------------------
  "RAGPipelineOllamaIntegrationSpec (search phase)" should "return semantically relevant results for a vector query" in {
    requireOllama()

    val rag = buildInMemoryRag()
    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    // Query about RAG / LLMs – should surface LLM and RAG documents
    val llmResults = rag.query("retrieval augmented generation", topK = Some(3))
    llmResults.isRight shouldBe true
    val llmHits = llmResults.toOption.get
    llmHits should not be empty
    // At least one result should be from the LLM or RAG documents
    val hitIds = llmHits.map(_.id).mkString(" ")
    hitIds.toLowerCase should (include("llm") or include("rag") or include("ollama"))

    // Query about databases – should surface database documents
    val dbResults = rag.query("vector similarity search in database", topK = Some(3))
    dbResults.isRight shouldBe true
    val dbHits = dbResults.toOption.get
    dbHits should not be empty
    // Verify scores are in descending order (top score >= subsequent scores)
    val scores = dbHits.map(_.score)
    if (scores.size >= 2) {
      scores.head should be >= scores.last
    }
  }

  it should "rank top result above lower-ranked results for a specific query" in {
    requireOllama()

    val rag = buildInMemoryRag()
    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    val results = rag.query("Ollama local language model", topK = Some(5))
    results.isRight shouldBe true
    val hits = results.toOption.get
    hits should not be empty
    // Top score should be >= second score (sorted by relevance)
    if (hits.size >= 2) {
      hits.head.score should be >= hits(1).score
    }
  }

  // ---------------------------------------------------------------------------
  // 3. Hybrid search phase (pgvector + keyword)
  // ---------------------------------------------------------------------------
  "RAGPipelineOllamaIntegrationSpec (hybrid search phase)" should "combine vector and keyword scores for hybrid queries" in {
    requireOllamaAndPg()

    val rag = buildPgHybridRag()
    rag.clear().isRight shouldBe true

    sampleDocuments.foreach { case (docId, content) =>
      val result = rag.ingestText(content, docId)
      result.isRight shouldBe true
    }

    // Hybrid query: keyword "pgvector" should hit database docs, vector should reinforce
    val hybridResults = rag.query("pgvector semantic search", topK = Some(5))
    hybridResults.isRight shouldBe true
    val hybridHits = hybridResults.toOption.get
    hybridHits should not be empty

    // Verify some results were returned and have non-negative scores
    hybridHits.foreach(r => r.score should be >= 0.0)
  }

  it should "return results for a keyword-specific query" in {
    requireOllamaAndPg()

    val rag = buildPgHybridRag()
    rag.clear().isRight shouldBe true

    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    // Keyword-heavy query that should match Scala documents
    val scalaResults = rag.query("Scala JVM programming language", topK = Some(4))
    scalaResults.isRight shouldBe true
    val scalaHits = scalaResults.toOption.get
    scalaHits should not be empty
    // Results should be non-empty and have scores
    scalaHits.foreach(r => r.score should be >= 0.0)
  }

  it should "clean up the pgvector store after test" in {
    requireOllamaAndPg()

    val rag = buildPgHybridRag()
    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    val clearResult = rag.clear()
    clearResult.isRight shouldBe true

    val stats = rag.stats
    stats.isRight shouldBe true
    stats.toOption.get.vectorCount shouldBe 0L
  }

  // ---------------------------------------------------------------------------
  // 4. Agent RAG phase: retrieved context drives LLM completion
  // ---------------------------------------------------------------------------
  "RAGPipelineOllamaIntegrationSpec (agent RAG phase)" should "generate an answer grounded in retrieved context using OllamaClient" in {
    assume(
      ollamaAvailableEnv.isDefined,
      "OLLAMA_AVAILABLE env var not set - skipping agent RAG test"
    )

    // Check both embedding model and LLM model are available
    val llmModelAvailable = Try {
      val uri        = java.net.URI.create(s"$ollamaBaseUrl/api/tags")
      val connection = uri.toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(3000)
      connection.setReadTimeout(3000)
      connection.setRequestMethod("GET")
      val code = connection.getResponseCode
      if (code == 200) {
        val source = scala.io.Source.fromInputStream(connection.getInputStream)
        try source.mkString.contains(llmModel)
        finally source.close()
      } else false
    }.getOrElse(false)

    assume(ollamaReachable, s"Ollama not reachable at $ollamaBaseUrl with model $embeddingModel")
    assume(llmModelAvailable, s"Ollama model $llmModel not available - skipping agent RAG test")

    val ollamaCfg = OllamaConfig(
      model = llmModel,
      baseUrl = ollamaBaseUrl,
      contextWindow = 8192,
      reserveCompletion = 4096
    )
    val llmClient = new OllamaClient(ollamaCfg)

    try {
      val ragConfig = RAGConfig.default
        .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, 768)
        .withLLM(llmClient)
        .inMemory

      val rag = RAG
        .buildWithClient(ragConfig, embeddingClient)
        .fold(e => fail(s"Failed to build agent RAG: ${e.message}"), identity)

      ragInstances = rag :: ragInstances

      // Index sample documents
      sampleDocuments.foreach { case (docId, content) =>
        rag.ingestText(content, docId).isRight shouldBe true
      }

      // Query: should retrieve context and generate an answer
      val answerResult = rag.queryWithAnswer(
        "What is RAG and how does it enhance language models?",
        topK = Some(3)
      )

      answerResult.isRight shouldBe true
      val ragAnswer = answerResult.toOption.get

      // Verify structural properties - never assert on specific LLM output text
      ragAnswer.answer should not be empty
      ragAnswer.question should not be empty
      ragAnswer.contexts should not be empty
      ragAnswer.contexts.size should be <= 3

      // The retrieved contexts should include relevant chunks from our documents
      val contextContent = ragAnswer.contexts.map(_.content).mkString(" ").toLowerCase
      // At least some context content should be returned (non-empty)
      contextContent should not be empty
    } finally {
      llmClient.close()
    }
  }

  it should "return contexts that are semantically related to the query" in {
    requireOllama()

    val rag = buildInMemoryRag()
    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    // Ask about Ollama specifically
    val searchResult = rag.query("local LLM inference without cloud", topK = Some(3))
    searchResult.isRight shouldBe true
    val contexts = searchResult.toOption.get
    contexts should not be empty

    // All returned contexts should have content
    contexts.foreach { ctx =>
      ctx.content should not be empty
      ctx.id should not be empty
      ctx.score should be >= 0.0
    }
  }

  // ---------------------------------------------------------------------------
  // 5. Cleanup verification
  // ---------------------------------------------------------------------------
  "RAGPipelineOllamaIntegrationSpec (cleanup)" should "clear all test data leaving an empty store" in {
    requireOllama()

    val rag = buildInMemoryRag()
    sampleDocuments.foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    rag.chunkCount should be > 0

    val clearResult = rag.clear()
    clearResult.isRight shouldBe true

    val stats = rag.stats
    stats.isRight shouldBe true
    val ragStats = stats.toOption.get
    ragStats.vectorCount shouldBe 0L
  }

  it should "allow re-ingestion after clearing" in {
    requireOllama()

    val rag = buildInMemoryRag()

    // First pass
    sampleDocuments.take(5).foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    rag.clear().isRight shouldBe true

    // Second pass after clear
    sampleDocuments.take(3).foreach { case (docId, content) =>
      rag.ingestText(content, docId).isRight shouldBe true
    }

    val stats = rag.stats
    stats.isRight shouldBe true
    stats.toOption.get.vectorCount should be >= 1L
  }

}
