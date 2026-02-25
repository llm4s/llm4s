package org.llm4s.rag.benchmark

import org.llm4s.chunking.ChunkerFactory
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.rag.evaluation.EvaluationError
import org.llm4s.types.Result
import org.llm4s.vectorstore._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * Tests for RAGPipeline focusing on indexing, search, and lifecycle behaviour.
 * All external dependencies (embedding provider, LLM, vector/keyword stores)
 * are replaced with lightweight in-process test doubles so no network or DB
 * is required.
 */
class RAGPipelineSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // =========================================================================
  // Test doubles
  // =========================================================================

  class MockEmbeddingProvider(dimensions: Int = 4) extends EmbeddingProvider {
    var callCount: Int = 0
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      callCount += 1
      val embeddings = request.input.map { text =>
        val h = text.hashCode.abs
        (0 until dimensions).map(i => ((h + i) % 100) / 100.0).toSeq
      }
      Right(EmbeddingResponse(embeddings = embeddings, usage = Some(EmbeddingUsage(10, 10))))
    }
  }

  class FailingEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Left(EvaluationError("embedding provider unavailable"))
  }

  /** Returns an empty embeddings list — used to exercise the EmbeddingError path. */
  private class EmptyEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = Seq.empty, usage = None))
  }

  /** Returns real (non-empty) embeddings without needing a network call. */
  private class DummyEmbeddingProvider(dimensions: Int = 4) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = request.input.map { text =>
        val hash = text.hashCode.abs
        (0 until dimensions).map(i => ((hash + i) % 100) / 100.0).toSeq
      }
      Right(
        EmbeddingResponse(
          embeddings = embeddings,
          usage = Some(EmbeddingUsage(totalTokens = 10, promptTokens = 10))
        )
      )
    }
  }

  class MockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Right(
        Completion(
          id = "mock",
          created = 0L,
          content = "Answer derived from provided context.",
          model = "mock-model",
          message = AssistantMessage(contentOpt = Some("Answer derived from provided context.")),
          usage = Some(TokenUsage(50, 25, 75))
        )
      )
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** In-memory VectorStore — records stored in a mutable map. */
  class MockVectorStore extends VectorStore {
    val records: mutable.Map[String, VectorRecord]     = mutable.Map.empty
    override def upsert(r: VectorRecord): Result[Unit] = { records(r.id) = r; Right(()) }
    override def upsertBatch(b: Seq[VectorRecord]): Result[Unit] = {
      b.foreach(r => records(r.id) = r); Right(())
    }
    override def search(
      q: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] =
      Right(
        records.values
          .take(topK)
          .zipWithIndex
          .map { case (r, i) => ScoredRecord(r, 1.0 - i * 0.1) }
          .toSeq
      )
    override def get(id: String): Result[Option[VectorRecord]] = Right(records.get(id))
    override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
      Right(ids.flatMap(records.get))
    override def delete(id: String): Result[Unit] = { records.remove(id); Right(()) }
    override def deleteBatch(ids: Seq[String]): Result[Unit] = {
      ids.foreach(records.remove); Right(())
    }
    override def deleteByPrefix(prefix: String): Result[Long] = {
      val del = records.keys.filter(_.startsWith(prefix)).toSeq
      del.foreach(records.remove)
      Right(del.size.toLong)
    }
    override def deleteByFilter(filter: MetadataFilter): Result[Long] = Right(0L)
    override def count(filter: Option[MetadataFilter]): Result[Long]  = Right(records.size.toLong)
    override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
      Right(records.values.toSeq.drop(offset).take(limit))
    override def clear(): Result[Unit] = { records.clear(); Right(()) }
    override def stats(): Result[VectorStoreStats] = {
      val dims = records.headOption.map(_._2.embedding.length).toSet
      Right(VectorStoreStats(records.size.toLong, dims, None))
    }
    override def close(): Unit = ()
  }

  /** In-memory KeywordIndex — documents stored in a mutable map. */
  class MockKeywordIndex extends KeywordIndex {
    val docs: mutable.Map[String, KeywordDocument]       = mutable.Map.empty
    override def index(d: KeywordDocument): Result[Unit] = { docs(d.id) = d; Right(()) }
    override def indexBatch(b: Seq[KeywordDocument]): Result[Unit] = {
      b.foreach(d => docs(d.id) = d); Right(())
    }
    override def search(
      query: String,
      topK: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[KeywordSearchResult]] =
      Right(
        docs.values
          .filter(_.content.toLowerCase.contains(query.toLowerCase))
          .take(topK)
          .zipWithIndex
          .map { case (d, i) => KeywordSearchResult(d.id, d.content, 10.0 - i, d.metadata) }
          .toSeq
      )
    override def searchWithHighlights(
      query: String,
      topK: Int,
      snippetLength: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[KeywordSearchResult]] = search(query, topK, filter)
    override def get(id: String): Result[Option[KeywordDocument]] = Right(docs.get(id))
    override def delete(id: String): Result[Unit]                 = { docs.remove(id); Right(()) }
    override def deleteBatch(ids: Seq[String]): Result[Unit] = {
      ids.foreach(docs.remove); Right(())
    }
    override def deleteByPrefix(prefix: String): Result[Long] = {
      val del = docs.keys.filter(_.startsWith(prefix)).toSeq
      del.foreach(docs.remove)
      Right(del.size.toLong)
    }
    override def count(): Result[Long]              = Right(docs.size.toLong)
    override def clear(): Result[Unit]              = { docs.clear(); Right(()) }
    override def stats(): Result[KeywordIndexStats] = Right(KeywordIndexStats(docs.size.toLong))
    override def close(): Unit                      = ()
  }

  // =========================================================================
  // Fixtures
  // =========================================================================

  /** Tracks pipelines created by buildPipeline for cleanup in afterEach. */
  private var _pipeline: RAGPipeline = _

  private var vectorStore: MockVectorStore             = _
  private var keywordIndex: MockKeywordIndex           = _
  private var embeddingProvider: MockEmbeddingProvider = _
  private var llmClient: MockLLMClient                 = _
  private var embeddingClient: EmbeddingClient         = _
  private val defaultConfig: RAGExperimentConfig       = RAGExperimentConfig.default

  override def beforeEach(): Unit = {
    super.beforeEach()
    _pipeline = null
    vectorStore = new MockVectorStore()
    keywordIndex = new MockKeywordIndex()
    embeddingProvider = new MockEmbeddingProvider()
    llmClient = new MockLLMClient()
    embeddingClient = new EmbeddingClient(embeddingProvider)
  }

  override def afterEach(): Unit = {
    if (_pipeline != null) _pipeline.close()
    super.afterEach()
  }

  /** Creates a pipeline backed by lightweight in-memory mock stores. */
  private def mkPipeline(
    cfg: RAGExperimentConfig = defaultConfig,
    ec: EmbeddingClient = embeddingClient
  ): RAGPipeline =
    RAGPipeline.withStores(cfg, llmClient, ec, vectorStore, keywordIndex)

  /**
   * Creates a pipeline backed by the real in-memory store implementations.
   * Used by EmbeddingError tests where exact error types matter.
   * Stores the result in _pipeline so afterEach can close it.
   */
  private def buildPipeline(provider: EmbeddingProvider = new EmptyEmbeddingProvider): RAGPipeline = {
    val vs =
      VectorStoreFactory
        .inMemory()
        .fold(e => fail(s"Could not create in-memory vector store: ${e.formatted}"), identity)
    val ki =
      KeywordIndex.inMemory().fold(e => fail(s"Could not create in-memory keyword index: ${e.formatted}"), identity)
    _pipeline = RAGPipeline.withStores(
      config = RAGExperimentConfig.default,
      llmClient = new MockLLMClient,
      embeddingClient = new EmbeddingClient(provider),
      vectorStore = vs,
      keywordIndex = ki
    )
    _pipeline
  }

  private val contentWithText = "Scala is a statically typed language for the JVM."

  // =========================================================================
  // Construction / configuration
  // =========================================================================

  "RAGPipeline.withStores" should "expose the config passed at construction" in {
    mkPipeline().config.name shouldBe "default"
  }

  it should "expose the injected LLM and embedding clients" in {
    val p = mkPipeline()
    p.llmClient shouldBe llmClient
    p.embeddingClient shouldBe embeddingClient
  }

  it should "work with Sentence chunking strategy" in {
    val cfg = defaultConfig.copy(name = "s", chunkingStrategy = ChunkerFactory.Strategy.Sentence)
    mkPipeline(cfg = cfg).config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Sentence
  }

  it should "work with Simple chunking strategy" in {
    val cfg = defaultConfig.copy(name = "s", chunkingStrategy = ChunkerFactory.Strategy.Simple)
    mkPipeline(cfg = cfg).config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Simple
  }

  it should "work with Markdown chunking strategy" in {
    val cfg = defaultConfig.copy(name = "m", chunkingStrategy = ChunkerFactory.Strategy.Markdown)
    mkPipeline(cfg = cfg).config.chunkingStrategy shouldBe ChunkerFactory.Strategy.Markdown
  }

  // =========================================================================
  // Initial state
  // =========================================================================

  "RAGPipeline.getDocumentCount" should "be 0 before any indexing" in {
    mkPipeline().getDocumentCount shouldBe 0
  }

  "RAGPipeline.getChunkCount" should "be 0 before any indexing" in {
    mkPipeline().getChunkCount shouldBe 0
  }

  // =========================================================================
  // indexDocument
  // =========================================================================

  "RAGPipeline.indexDocument" should "succeed with empty content and return 0 chunks" in {
    val result = mkPipeline().indexDocument("doc1", "")
    result.isRight shouldBe true
    result.toOption.get shouldBe 0
  }

  it should "return a positive chunk count for non-empty content" in {
    val result = mkPipeline().indexDocument("doc1", contentWithText)
    result.isRight shouldBe true
    result.toOption.get should be > 0
  }

  it should "write records to the vector store" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    vectorStore.records should not be empty
  }

  it should "write documents to the keyword index" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    keywordIndex.docs should not be empty
  }

  it should "embed docId and chunkIndex in record metadata" in {
    val p = mkPipeline()
    p.indexDocument("my-doc", contentWithText)
    vectorStore.records.values.foreach { r =>
      r.metadata("docId") shouldBe "my-doc"
      r.metadata.contains("chunkIndex") shouldBe true
    }
  }

  it should "return Left when the embedding provider fails" in {
    val badClient = new EmbeddingClient(new FailingEmbeddingProvider())
    val result    = mkPipeline(ec = badClient).indexDocument("doc1", contentWithText)
    result.isLeft shouldBe true
  }

  it should "update the chunk count after successful indexing" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    p.getChunkCount should be > 0
  }

  // =========================================================================
  // indexDocuments
  // =========================================================================

  "RAGPipeline.indexDocuments" should "accumulate chunk count across multiple documents" in {
    val p = mkPipeline()
    val docs = Seq(
      ("doc1", "Content about Scala programming language", Map.empty[String, String]),
      ("doc2", "Content about functional programming paradigm", Map.empty[String, String])
    )
    val result = p.indexDocuments(docs)
    result.isRight shouldBe true
    result.toOption.get should be > 0
  }

  it should "return Left and short-circuit on the first embedding failure" in {
    val badClient = new EmbeddingClient(new FailingEmbeddingProvider())
    val p         = mkPipeline(ec = badClient)
    val docs = Seq(
      ("doc1", "Content 1", Map.empty[String, String]),
      ("doc2", "Content 2", Map.empty[String, String])
    )
    p.indexDocuments(docs).isLeft shouldBe true
  }

  it should "return Right(0) for an empty document list" in {
    mkPipeline().indexDocuments(Seq.empty) shouldBe Right(0)
  }

  // =========================================================================
  // search — mock-store tests
  // =========================================================================

  "RAGPipeline.search" should "return Right after documents are indexed" in {
    val p = mkPipeline()
    p.indexDocument("doc1", "Scala is a language for the JVM.")
    p.search("Scala language").isRight shouldBe true
  }

  it should "return Right with empty results when nothing is indexed" in {
    val result = mkPipeline().search("any query")
    result.isRight shouldBe true
  }

  it should "respect the topK parameter" in {
    val p = mkPipeline()
    (1 to 6).foreach(i => p.indexDocument(s"doc$i", s"Document $i content about a topic."))
    val results = p.search("document content", topK = Some(2)).toOption.get
    results.size should be <= 2
  }

  it should "return Left when the embedding provider fails during search" in {
    val badClient = new EmbeddingClient(new FailingEmbeddingProvider())
    val p         = mkPipeline(ec = badClient)
    p.search("any query").isLeft shouldBe true
  }

  // =========================================================================
  // search — EmbeddingError path (uses real in-memory stores)
  // =========================================================================

  it should "return EmbeddingError when the provider returns an empty embeddings list" in {
    val pipeline = buildPipeline()
    val result   = pipeline.search("what is Scala?")

    result.fold(
      {
        case embErr: EmbeddingError =>
          embErr.message should include("empty embeddings")
          embErr.provider should not be "unknown"
          embErr.provider shouldBe "text-embedding-3-small"
        case other =>
          fail(s"Expected EmbeddingError but got: ${other.getClass.getSimpleName}: ${other.message}")
      },
      _ => fail("Expected Left(EmbeddingError) but got Right")
    )
  }

  it should "propagate the EmbeddingError regardless of the topK parameter" in {
    val pipeline = buildPipeline()
    val result   = pipeline.search("any query", topK = Some(10))

    result shouldBe a[Left[_, _]]
  }

  it should "return Right with empty results when embeddings are non-empty but the index is empty" in {
    val pipeline = buildPipeline(provider = new DummyEmbeddingProvider)
    val result   = pipeline.search("what is Scala?")

    result.fold(e => fail(s"Expected Right but got Left: ${e.formatted}"), results => results shouldBe empty)
  }

  // =========================================================================
  // answer
  // =========================================================================

  "RAGPipeline.answer" should "return a RAGAnswer preserving the original question" in {
    val p = mkPipeline()
    p.indexDocument("doc1", "Paris is the capital of France.")
    val result = p.answer("What is the capital of France?")
    result.isRight shouldBe true
    result.toOption.get.question shouldBe "What is the capital of France?"
  }

  it should "return a non-empty answer" in {
    val p = mkPipeline()
    p.indexDocument("doc1", "Scala was created by Martin Odersky in 2004.")
    val ragAnswer = p.answer("Who created Scala?").toOption.get
    ragAnswer.answer should not be empty
  }

  it should "populate contexts in the RAGAnswer" in {
    val p = mkPipeline()
    p.indexDocument("doc1", "FP stands for Functional Programming.")
    val ragAnswer = p.answer("What does FP stand for?").toOption.get
    ragAnswer.contexts should not be null
  }

  it should "return Left when the embedding provider fails" in {
    val badClient = new EmbeddingClient(new FailingEmbeddingProvider())
    mkPipeline(ec = badClient).answer("any question").isLeft shouldBe true
  }

  // =========================================================================
  // clear
  // =========================================================================

  "RAGPipeline.clear" should "reset the chunk count to zero" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    p.getChunkCount should be > 0
    p.clear()
    p.getChunkCount shouldBe 0
  }

  it should "reset the document count to zero" in {
    val p = mkPipeline()
    p.indexDocuments(
      Seq(
        ("doc1", "Content A", Map.empty[String, String]),
        ("doc2", "Content B", Map.empty[String, String])
      )
    )
    p.getDocumentCount should be > 0
    p.clear()
    p.getDocumentCount shouldBe 0
  }

  it should "empty the vector store" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    vectorStore.records should not be empty
    p.clear()
    vectorStore.records shouldBe empty
  }

  it should "empty the keyword index" in {
    val p = mkPipeline()
    p.indexDocument("doc1", contentWithText)
    keywordIndex.docs should not be empty
    p.clear()
    keywordIndex.docs shouldBe empty
  }

  it should "return Right" in {
    mkPipeline().clear().isRight shouldBe true
  }

  // =========================================================================
  // RAGAnswer case class
  // =========================================================================

  "RAGAnswer" should "hold all four fields" in {
    val a = RAGAnswer(
      question = "Q?",
      answer = "A.",
      contexts = Seq("ctx1", "ctx2"),
      searchResults = Seq.empty
    )
    a.question shouldBe "Q?"
    a.answer shouldBe "A."
    a.contexts shouldBe Seq("ctx1", "ctx2")
    a.searchResults shouldBe empty
  }
}
