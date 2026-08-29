package org.llm4s.rag

import org.llm4s.it.Tier
import org.llm4s.it.tags.Ollama
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, OllamaConfig }
import org.llm4s.llmconnect.provider.{ OllamaClient, OllamaEmbeddingProvider }
import org.llm4s.model.ModelRegistryService
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Try, Using }

/**
 * The RAG pipeline end to end against real components: Ollama embeddings
 * (`nomic-embed-text`), pgvector storage, hybrid keyword search, and an Ollama LLM
 * generating the answer.
 *
 * This is the only suite that exercises retrieval with a real embedding model. Every other
 * RAG test mocks the embeddings, which proves the pipeline runs but says nothing about
 * whether it retrieves the right thing - see `RAGPipelineIntegrationSpec` in `modules/rag`
 * for the mocked equivalent.
 *
 * Tier `@Ollama`: needs a local Ollama with **both** `nomic-embed-text` (embeddings) and
 * `qwen2.5:0.5b` (answers) pulled, plus PostgreSQL with pgvector on `PGVECTOR_TEST_URL`.
 * The `ollama-integration` CI job provides all three. Under `LLM4S_IT_STRICT=true`, which
 * that job sets, a missing dependency fails rather than skips.
 *
 * Non-determinism strategy: never assert on generated text. Assertions are either
 * structural (which score channels a result carries) or coarse-grained semantic (the
 * relevant document appears in the top few), never "the model said X".
 */
@Ollama
class RAGPipelineOllamaIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private given ModelRegistryService = ModelRegistryService.default().toOption.get

  private val ollamaBaseUrl  = "http://localhost:11434"
  private val embeddingModel = "nomic-embed-text"
  private val embeddingDims  = 768
  private val llmModel       = "qwen2.5:0.5b"

  private val pgUrl      = sys.env.get("PGVECTOR_TEST_URL")
  private val pgUser     = sys.env.getOrElse("PGVECTOR_USER", "postgres")
  private val pgPassword = sys.env.getOrElse("PGVECTOR_PASSWORD", "postgres")

  private val vectorTable  = "rag_pipeline_ollama_vectors"
  private val keywordTable = "rag_pipeline_ollama_keywords"

  /** True when Ollama is up and serving `model`. */
  private def ollamaServes(model: String): Boolean =
    Try {
      val connection = java.net.URI
        .create(s"$ollamaBaseUrl/api/tags")
        .toURL
        .openConnection()
        .asInstanceOf[java.net.HttpURLConnection]
      connection.setConnectTimeout(3000)
      connection.setReadTimeout(3000)
      connection.setRequestMethod("GET")
      if (connection.getResponseCode == 200) {
        val source = scala.io.Source.fromInputStream(connection.getInputStream)
        try source.mkString.contains(model)
        finally source.close()
      } else false
    }.getOrElse(false)

  private lazy val embeddingsAvailable: Boolean = ollamaServes(embeddingModel)
  private lazy val llmAvailable: Boolean        = ollamaServes(llmModel)

  private lazy val embeddingClient: EmbeddingClient =
    new EmbeddingClient(
      OllamaEmbeddingProvider.fromConfig(
        EmbeddingProviderConfig(baseUrl = ollamaBaseUrl, model = embeddingModel, apiKey = "not-required")
      )
    )

  /** Ten documents over five topics, two documents each, so retrieval has to discriminate. */
  private val corpus: Seq[(String, String)] = Seq(
    "doc-scala-1" ->
      "Scala is a statically typed programming language that combines object-oriented and functional programming. It runs on the Java Virtual Machine.",
    "doc-scala-2" ->
      "Scala's type system supports higher-kinded types, path-dependent types, and implicit parameters for powerful type-safe abstractions.",
    "doc-python-1" ->
      "Python is a dynamically typed language known for its simplicity and readability. It is widely used in data science and machine learning.",
    "doc-python-2" ->
      "Python supports multiple programming paradigms including procedural, object-oriented, and functional programming styles.",
    "doc-database-1" ->
      "PostgreSQL is an open-source relational database management system with strong ACID guarantees and JSON support.",
    "doc-database-2" ->
      "pgvector is a PostgreSQL extension that adds vector similarity search, enabling semantic search and AI-powered retrieval in the database.",
    "doc-llm-1" ->
      "Large language models are neural networks trained on vast text corpora that can generate coherent text and answer questions.",
    "doc-llm-2" ->
      "RAG, or Retrieval-Augmented Generation, enhances LLM responses by retrieving relevant documents and injecting them as context.",
    "doc-ollama-1" ->
      "Ollama is an open-source tool for running large language models locally on consumer hardware without requiring cloud API keys.",
    "doc-ollama-2" ->
      "The nomic-embed-text model from Ollama generates dense vector embeddings for text, suitable for semantic search applications."
  )

  /**
   * One in-memory pipeline, ingested once, shared by every read-only search test.
   *
   * Embedding ten documents is a real network round trip per document; rebuilding per test
   * multiplied that by the number of tests for no added coverage.
   */
  private var sharedRag: Option[RAG] = None

  /** Instances to close in `afterAll`, including the per-test ones. */
  private var openRags: List[RAG] = Nil

  private def register(rag: RAG): RAG = { openRags = rag :: openRags; rag }

  private def buildInMemoryRag(): RAG = {
    val config = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, embeddingDims)
      .inMemory
    register(
      RAG
        .buildWithClient(config, embeddingClient)
        .fold(e => fail(s"Failed to build in-memory RAG: ${e.message}"), identity)
    )
  }

  private def buildPgHybridRag(): RAG = {
    val url = pgUrl.getOrElse(fail("PGVECTOR_TEST_URL not set"))
    val config = RAGConfig.default
      .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, embeddingDims)
      .withPgHybrid(url, pgUser, pgPassword, vectorTable, keywordTable)
    register(
      RAG
        .buildWithClient(config, embeddingClient)
        .fold(e => fail(s"Failed to build pgvector hybrid RAG: ${e.message}"), identity)
    )
  }

  private def ingestCorpus(rag: RAG): Unit =
    corpus.foreach { case (id, text) =>
      rag.ingestText(text, id).fold(e => fail(s"Failed to ingest $id: ${e.message}"), _ => ())
    }

  override def beforeAll(): Unit = {
    super.beforeAll()
    if (embeddingsAvailable) {
      val rag = buildInMemoryRag()
      ingestCorpus(rag)
      sharedRag = Some(rag)
    }
  }

  override def afterAll(): Unit = {
    openRags.foreach(rag => Try(rag.close()))
    dropOwnTables()
    super.afterAll()
  }

  /**
   * Drop exactly the two tables this suite created, whether or not a test got that far.
   *
   * Deliberately plain JDBC rather than `PgSearchIndex.dropSchema()`, which is the wrong
   * tool twice over: it drops the shared `llm4s_collections` and `llm4s_principals` tables
   * (CASCADE) that this suite never creates and `PgSearchIndexSpec` depends on, and it does
   * not drop the vector or keyword table that `withPgHybrid` actually created. Against a
   * shared database that combination is a cross-suite failure that only shows up
   * intermittently; against CI's throwaway container it hides entirely.
   */
  private def dropOwnTables(): Unit =
    pgUrl.foreach { url =>
      Try {
        Using.resource(java.sql.DriverManager.getConnection(url, pgUser, pgPassword)) { conn =>
          Using.resource(conn.createStatement()) { stmt =>
            stmt.execute(s"DROP TABLE IF EXISTS $vectorTable")
            stmt.execute(s"DROP TABLE IF EXISTS $keywordTable")
          }
        }
      }
    }

  private def requireEmbeddings(): RAG = {
    Tier.require(embeddingsAvailable, s"Ollama at $ollamaBaseUrl is not serving $embeddingModel")
    sharedRag.getOrElse(fail("shared pipeline was not built despite Ollama being available"))
  }

  private def requirePg(): Unit = {
    Tier.require(embeddingsAvailable, s"Ollama at $ollamaBaseUrl is not serving $embeddingModel")
    Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL is not set, so pgvector is unavailable")
  }

  /** Chunk ids are `<docId>-chunk-<n>`; assertions are about documents, not chunks. */
  private def docIdOf(chunkId: String): String = chunkId.replaceFirst("-chunk-\\d+$", "")

  // ---------------------------------------------------------------------------
  // 1. Index phase
  // ---------------------------------------------------------------------------

  "RAG with Ollama embeddings (index phase)" should
    "embed and index every document in the corpus" in {
      val rag = requireEmbeddings()

      rag.documentCount shouldBe corpus.size
      rag.chunkCount should be >= corpus.size
      rag.stats.fold(e => fail(e.message), _.vectorCount) shouldBe rag.chunkCount.toLong
    }

  // ---------------------------------------------------------------------------
  // 2. Semantic search phase
  //
  // The point of this suite: with real embeddings, retrieval has to put the topically
  // relevant document near the top. Asserted as top-3 containment rather than top-1, so a
  // model update that reorders near-ties does not turn this red.
  // ---------------------------------------------------------------------------

  "RAG with Ollama embeddings (search phase)" should
    "rank the retrieval-augmented-generation document among the top hits for its own topic" in {
      val rag = requireEmbeddings()

      val hits = rag
        .query("retrieval augmented generation for language models", topK = Some(3))
        .fold(e => fail(e.message), identity)

      hits should not be empty
      (hits.map(r => docIdOf(r.id)) should contain).oneOf("doc-llm-2", "doc-llm-1", "doc-ollama-1")
      hits.map(_.score) shouldBe hits.map(_.score).sorted(Ordering[Double].reverse)
    }

  it should "rank the pgvector document among the top hits for a vector-database query" in {
    val rag = requireEmbeddings()

    val hits = rag
      .query("vector similarity search inside a relational database", topK = Some(3))
      .fold(e => fail(e.message), identity)

    hits should not be empty
    (hits.map(r => docIdOf(r.id)) should contain).oneOf("doc-database-2", "doc-database-1")
    hits.map(_.score) shouldBe hits.map(_.score).sorted(Ordering[Double].reverse)
  }

  it should "return distinct chunks with content, honouring topK" in {
    val rag = requireEmbeddings()

    val hits = rag.query("Ollama local language model", topK = Some(5)).fold(e => fail(e.message), identity)

    hits.size should be <= 5
    hits.map(_.id).distinct.size shouldBe hits.size
    hits.foreach { hit =>
      hit.id should not be empty
      hit.content should not be empty
      hit.vectorScore should be(defined)
    }
  }

  // ---------------------------------------------------------------------------
  // 3. Hybrid search phase (pgvector + Postgres full-text)
  // ---------------------------------------------------------------------------

  "RAG with Ollama embeddings (hybrid phase)" should
    "reach documents through both the vector and the keyword channel" in {
      requirePg()

      val rag = buildPgHybridRag()
      rag.clear().fold(e => fail(e.message), identity)
      ingestCorpus(rag)

      // "pgvector" is a rare term, so the keyword channel should find it outright while the
      // vector channel reaches the same topic by similarity.
      val hits = rag.query("pgvector semantic search", topK = Some(5)).fold(e => fail(e.message), identity)

      hits should not be empty
      // A hybrid search that silently degraded to one channel would carry only one score.
      hits.exists(_.vectorScore.isDefined) shouldBe true
      hits.exists(_.keywordScore.isDefined) shouldBe true
      hits.map(r => docIdOf(r.id)) should contain("doc-database-2")
    }

  it should "clear the pgvector store on request" in {
    requirePg()

    val rag = buildPgHybridRag()
    ingestCorpus(rag)
    rag.stats.fold(e => fail(e.message), _.vectorCount) should be > 0L

    rag.clear().fold(e => fail(e.message), identity)
    rag.stats.fold(e => fail(e.message), _.vectorCount) shouldBe 0L
  }

  // ---------------------------------------------------------------------------
  // 4. Agent RAG phase
  // ---------------------------------------------------------------------------

  "RAG with Ollama embeddings (agent phase)" should
    "answer from the retrieved context using a local model" in {
      Tier.require(embeddingsAvailable, s"Ollama at $ollamaBaseUrl is not serving $embeddingModel")
      Tier.require(llmAvailable, s"Ollama at $ollamaBaseUrl is not serving $llmModel")

      val llmClient = new OllamaClient(
        OllamaConfig(model = llmModel, baseUrl = ollamaBaseUrl, contextWindow = 8192, reserveCompletion = 4096)
      )

      try {
        val config = RAGConfig.default
          .withEmbeddings(EmbeddingProvider.Ollama, embeddingModel, embeddingDims)
          .withLLM(llmClient)
          .inMemory
        val rag = register(
          RAG.buildWithClient(config, embeddingClient).fold(e => fail(s"Failed to build: ${e.message}"), identity)
        )
        ingestCorpus(rag)

        val answer = rag
          .queryWithAnswer("What is RAG and how does it enhance language models?", topK = Some(3))
          .fold(e => fail(e.message), identity)

        // Structural only: a local 0.5b model's wording is not something to assert on.
        answer.answer.trim should not be empty
        answer.question shouldBe "What is RAG and how does it enhance language models?"
        answer.contexts should have size 3
        answer.contexts.foreach(_.content should not be empty)
        (answer.contexts.map(c => docIdOf(c.id)) should contain).oneOf("doc-llm-2", "doc-llm-1", "doc-ollama-1")
      } finally llmClient.close()
    }

  // ---------------------------------------------------------------------------
  // 5. Lifecycle
  // ---------------------------------------------------------------------------

  "RAG with Ollama embeddings (lifecycle)" should
    "support clear followed by re-ingestion" in {
      Tier.require(embeddingsAvailable, s"Ollama at $ollamaBaseUrl is not serving $embeddingModel")

      // A dedicated pipeline: this test mutates, and the shared one is read-only.
      val rag = buildInMemoryRag()
      ingestCorpus(rag)
      rag.stats.fold(e => fail(e.message), _.vectorCount) should be > 0L

      rag.clear().fold(e => fail(e.message), identity)
      rag.stats.fold(e => fail(e.message), _.vectorCount) shouldBe 0L
      rag.query("anything at all").fold(e => fail(e.message), identity) shouldBe empty

      corpus.take(3).foreach { case (id, text) =>
        rag.ingestText(text, id).fold(e => fail(e.message), _ => ())
      }
      rag.stats.fold(e => fail(e.message), _.vectorCount) should be >= 3L
    }
}
