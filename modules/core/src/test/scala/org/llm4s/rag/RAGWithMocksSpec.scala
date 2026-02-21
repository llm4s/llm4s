package org.llm4s.rag

import org.llm4s.error.{ ConfigurationError, ProcessingError }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.rag.loader._
import org.llm4s.rag.permissions._
import org.llm4s.types.Result
import org.llm4s.vectorstore._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import scala.collection.mutable

/**
 * Comprehensive tests for the RAG class using mock implementations.
 *
 * These tests verify RAG functionality without requiring external services
 * (embedding APIs, LLM APIs, databases).
 */
class RAGWithMocksSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // ==========================================================================
  // Mock Implementations
  // ==========================================================================

  /**
   * Mock EmbeddingProvider that returns deterministic embeddings.
   * Each text gets a unique embedding based on its hash.
   */
  class MockEmbeddingProvider(dimensions: Int = 3) extends EmbeddingProvider {
    var embedCalls: Int                       = 0
    var lastRequest: Option[EmbeddingRequest] = None

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      embedCalls += 1
      lastRequest = Some(request)

      val embeddings = request.input.map { text =>
        // Generate deterministic embedding based on text hash
        val hash = text.hashCode.abs
        (0 until dimensions).map(i => ((hash + i) % 100) / 100.0).toSeq
      }

      Right(
        EmbeddingResponse(
          embeddings = embeddings,
          usage = Some(EmbeddingUsage(request.input.map(_.length).sum, request.input.map(_.length).sum))
        )
      )
    }

    def reset(): Unit = {
      embedCalls = 0
      lastRequest = None
    }
  }

  /**
   * Mock LLMClient that returns deterministic responses.
   */
  class MockLLMClient extends LLMClient {
    var completeCalls: Int                     = 0
    var lastConversation: Option[Conversation] = None
    var responseOverride: Option[String]       = None

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = {
      completeCalls += 1
      lastConversation = Some(conversation)

      val content = responseOverride.getOrElse("This is a mock answer based on the provided context.")

      Right(
        Completion(
          id = s"mock-completion-${System.currentTimeMillis()}",
          created = System.currentTimeMillis() / 1000,
          content = content,
          model = "mock-model",
          message = AssistantMessage(contentOpt = Some(content)),
          usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int = 4096

    override def getReserveCompletion(): Int = 1024

    def reset(): Unit = {
      completeCalls = 0
      lastConversation = None
      responseOverride = None
    }
  }

  /**
   * Mock VectorStore that stores records in memory.
   */
  class MockVectorStore extends VectorStore {
    private val records: mutable.Map[String, VectorRecord] = mutable.Map.empty
    var upsertCalls: Int                                   = 0
    var searchCalls: Int                                   = 0

    override def upsert(record: VectorRecord): Result[Unit] = {
      upsertCalls += 1
      records(record.id) = record
      Right(())
    }

    override def upsertBatch(batch: Seq[VectorRecord]): Result[Unit] = {
      upsertCalls += 1
      batch.foreach(r => records(r.id) = r)
      Right(())
    }

    override def search(
      queryVector: Array[Float],
      topK: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[ScoredRecord]] = {
      searchCalls += 1
      // Simple mock: return all records with fake scores
      val results = records.values
        .take(topK)
        .zipWithIndex
        .map { case (record, idx) =>
          ScoredRecord(record, 1.0 - (idx * 0.1))
        }
        .toSeq
      Right(results)
    }

    override def get(id: String): Result[Option[VectorRecord]] =
      Right(records.get(id))

    override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] =
      Right(ids.flatMap(records.get))

    override def delete(id: String): Result[Unit] = {
      records.remove(id)
      Right(())
    }

    override def deleteBatch(ids: Seq[String]): Result[Unit] = {
      ids.foreach(records.remove)
      Right(())
    }

    override def deleteByPrefix(prefix: String): Result[Long] = {
      val toDelete = records.keys.filter(_.startsWith(prefix)).toSeq
      toDelete.foreach(records.remove)
      Right(toDelete.length.toLong)
    }

    override def deleteByFilter(filter: MetadataFilter): Result[Long] =
      Right(0L)

    override def count(filter: Option[MetadataFilter]): Result[Long] =
      Right(records.size.toLong)

    override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
      Right(records.values.toSeq.drop(offset).take(limit))

    override def clear(): Result[Unit] = {
      records.clear()
      Right(())
    }

    override def stats(): Result[VectorStoreStats] = {
      val dims = records.headOption.map(_._2.embedding.length).toSet
      Right(VectorStoreStats(records.size.toLong, dims, None))
    }

    override def close(): Unit = ()

    def recordCount: Int = records.size

    def reset(): Unit = {
      records.clear()
      upsertCalls = 0
      searchCalls = 0
    }
  }

  /**
   * Mock KeywordIndex that stores documents in memory.
   */
  class MockKeywordIndex extends KeywordIndex {
    private val docs: mutable.Map[String, KeywordDocument] = mutable.Map.empty
    var indexCalls: Int                                    = 0
    var searchCalls: Int                                   = 0

    override def index(doc: KeywordDocument): Result[Unit] = {
      indexCalls += 1
      docs(doc.id) = doc
      Right(())
    }

    override def indexBatch(batch: Seq[KeywordDocument]): Result[Unit] = {
      indexCalls += 1
      batch.foreach(d => docs(d.id) = d)
      Right(())
    }

    override def search(
      query: String,
      topK: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[KeywordSearchResult]] = {
      searchCalls += 1
      // Simple mock: return docs containing query terms
      val queryLower = query.toLowerCase
      val results = docs.values
        .filter(_.content.toLowerCase.contains(queryLower))
        .take(topK)
        .zipWithIndex
        .map { case (doc, idx) =>
          KeywordSearchResult(doc.id, doc.content, 10.0 - idx, doc.metadata)
        }
        .toSeq
      Right(results)
    }

    override def searchWithHighlights(
      query: String,
      topK: Int,
      snippetLength: Int,
      filter: Option[MetadataFilter]
    ): Result[Seq[KeywordSearchResult]] =
      search(query, topK, filter)

    override def get(id: String): Result[Option[KeywordDocument]] =
      Right(docs.get(id))

    override def delete(id: String): Result[Unit] = {
      docs.remove(id)
      Right(())
    }

    override def deleteBatch(ids: Seq[String]): Result[Unit] = {
      ids.foreach(docs.remove)
      Right(())
    }

    override def deleteByPrefix(prefix: String): Result[Long] = {
      val toDelete = docs.keys.filter(_.startsWith(prefix)).toSeq
      toDelete.foreach(docs.remove)
      Right(toDelete.length.toLong)
    }

    override def count(): Result[Long] =
      Right(docs.size.toLong)

    override def clear(): Result[Unit] = {
      docs.clear()
      Right(())
    }

    override def stats(): Result[KeywordIndexStats] =
      Right(KeywordIndexStats(docs.size.toLong))

    override def close(): Unit = ()

    def docCount: Int = docs.size

    def reset(): Unit = {
      docs.clear()
      indexCalls = 0
      searchCalls = 0
    }
  }

  /**
   * Mock EmbeddingProvider that returns empty embeddings list.
   * Used to test empty embeddings error handling.
   */
  class EmptyEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(
        EmbeddingResponse(
          embeddings = Seq.empty,
          usage = Some(EmbeddingUsage(0, 0))
        )
      )
  }

  /**
   * Mock EmbeddingProvider that returns mismatched embeddings count.
   * Used to test cardinality validation.
   */
  class MismatchedEmbeddingProvider(returnCount: Int) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = (0 until returnCount).map(i => (0 until 3).map(j => ((i + j) % 100) / 100.0).toSeq)
      Right(
        EmbeddingResponse(
          embeddings = embeddings,
          usage = Some(EmbeddingUsage(request.input.map(_.length).sum, request.input.map(_.length).sum))
        )
      )
    }
  }

  // ==========================================================================
  // Test Fixtures
  // ==========================================================================

  private var mockEmbeddingProvider: MockEmbeddingProvider = _
  private var mockLLMClient: MockLLMClient                 = _
  private var tempDir: File                                = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    mockEmbeddingProvider = new MockEmbeddingProvider()
    mockLLMClient = new MockLLMClient()

    // Create temp directory for file tests
    tempDir = Files.createTempDirectory("rag-test").toFile
  }

  override def afterEach(): Unit = {
    // Clean up temp directory
    def deleteRecursively(file: File): Unit = {
      if (file.isDirectory) {
        Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
      }
      file.delete()
    }
    if (tempDir != null && tempDir.exists()) {
      deleteRecursively(tempDir)
    }
    super.afterEach()
  }

  /**
   * Create a RAG instance using mock components.
   */
  private def createMockRAG(
    withLLM: Boolean = false,
    config: RAGConfig = RAGConfig.default
  ): Result[RAG] = {
    val effectiveConfig = if (withLLM) config.copy(llmClient = Some(mockLLMClient)) else config
    val embeddingClient = new EmbeddingClient(mockEmbeddingProvider)

    // Use the package-private buildWithClient to inject our mock
    RAG.buildWithClient(effectiveConfig, embeddingClient)
  }

  /**
   * Create a RAG instance with our mock stores directly injected.
   * This requires using reflection or a test-only constructor.
   * For now, we'll test through the normal build path.
   */
  private def createTestFile(name: String, content: String): File = {
    val file = new File(tempDir, name)
    Files.write(file.toPath, content.getBytes)
    file
  }

  // ==========================================================================
  // RAG.build Tests
  // ==========================================================================

  "RAG.build" should "create RAG with default configuration" in {
    val result = createMockRAG()
    result.isRight shouldBe true
  }

  it should "create RAG with custom embedding provider" in {
    val config = RAGConfig.default.withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-small")
    val result = createMockRAG(config = config)
    result.isRight shouldBe true
  }

  it should "create RAG with LLM client" in {
    val result = createMockRAG(withLLM = true)
    result.isRight shouldBe true
  }

  it should "fail when LLM reranking requested without LLM client" in {
    val config = RAGConfig.default.withLLMReranking
    val result = RAG.build(
      config,
      resolveEmbeddingProvider = _ =>
        Right(
          EmbeddingProviderConfig(
            apiKey = "test-key",
            baseUrl = "http://localhost",
            model = "test-model"
          )
        )
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "fail when Cohere reranking requested without config" in {
    val config = RAGConfig.default.withCohereReranking()
    val result = RAG.build(
      config,
      resolveEmbeddingProvider = _ =>
        Right(
          EmbeddingProviderConfig(
            apiKey = "test-key",
            baseUrl = "http://localhost",
            model = "test-model"
          )
        ),
      resolveRerankerConfig = () => Right(None)
    )
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  // ==========================================================================
  // Ingestion Tests
  // ==========================================================================

  "RAG.ingestText" should "ingest raw text content" in {
    val rag = createMockRAG().toOption.get

    val result = rag.ingestText("This is test content for ingestion.", "doc-1")

    result.isRight shouldBe true
    result.toOption.get should be > 0
    rag.documentCount shouldBe 1
    rag.chunkCount should be > 0
  }

  it should "attach metadata to chunks" in {
    val rag = createMockRAG().toOption.get

    val result = rag.ingestText(
      "Test content",
      "doc-1",
      Map("source" -> "test", "category" -> "unit-test")
    )

    result.isRight shouldBe true
  }

  "RAG.ingestChunks" should "ingest pre-chunked content" in {
    val rag = createMockRAG().toOption.get

    val chunks = Seq("Chunk 1 content", "Chunk 2 content", "Chunk 3 content")
    val result = rag.ingestChunks("doc-1", chunks)

    result.isRight shouldBe true
    result.toOption.get shouldBe 3
    rag.chunkCount shouldBe 3
  }

  "RAG.ingest(path)" should "ingest a text file" in {
    val rag  = createMockRAG().toOption.get
    val file = createTestFile("test.txt", "This is test file content for RAG ingestion.")

    val result = rag.ingest(file.getAbsolutePath)

    result.isRight shouldBe true
    rag.documentCount shouldBe 1
  }

  it should "ingest a directory of files" in {
    val rag = createMockRAG().toOption.get
    createTestFile("doc1.txt", "Content of document 1")
    createTestFile("doc2.txt", "Content of document 2")
    createTestFile("doc3.md", "# Markdown document")

    val result = rag.ingest(tempDir.getAbsolutePath)

    result.isRight shouldBe true
    rag.documentCount should be >= 2
  }

  "RAG.ingest(loader)" should "ingest from TextLoader" in {
    val rag = createMockRAG().toOption.get

    val loader = TextLoader.fromPairs(
      "doc1" -> "First document content",
      "doc2" -> "Second document content",
      "doc3" -> "Third document content"
    )

    val result = rag.ingest(loader)

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.successful shouldBe 3
    stats.failed shouldBe 0
  }

  it should "handle loader failures gracefully" in {
    val rag = createMockRAG().toOption.get

    // Create a loader that has some failures
    val loader = new DocumentLoader {
      override def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(Document("doc1", "Content 1")),
        LoadResult.failure("doc2", ProcessingError("test", "Simulated failure")),
        LoadResult.success(Document("doc3", "Content 3"))
      )
      override def estimatedCount: Option[Int] = Some(3)
      override def description: String         = "TestLoader"
    }

    val result = rag.ingest(loader)

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.successful shouldBe 2
    stats.failed shouldBe 1
    stats.errors should have size 1
  }

  it should "skip empty documents when configured" in {
    val config = RAGConfig.default.withLoadingConfig(LoadingConfig(skipEmptyDocuments = true))
    val rag    = createMockRAG(config = config).toOption.get

    val loader = TextLoader.fromPairs(
      "doc1" -> "Content",
      "doc2" -> "",
      "doc3" -> "   "
    )

    val result = rag.ingest(loader)

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.successful shouldBe 1
    stats.skipped shouldBe 2
  }

  it should "fail fast when configured" in {
    val config = RAGConfig.default.failOnLoadError(fail = true)
    val rag    = createMockRAG(config = config).toOption.get

    val loader = new DocumentLoader {
      override def load(): Iterator[LoadResult] = Iterator(
        LoadResult.success(Document("doc1", "Content 1")),
        LoadResult.failure("doc2", ProcessingError("test", "Simulated failure")),
        LoadResult.success(Document("doc3", "Content 3"))
      )
      override def estimatedCount: Option[Int] = Some(3)
      override def description: String         = "TestLoader"
    }

    val result = rag.ingest(loader)

    result.isLeft shouldBe true
  }

  // ==========================================================================
  // Query Tests
  // ==========================================================================

  "RAG.query" should "search for relevant chunks" in {
    val rag = createMockRAG().toOption.get

    // Ingest some documents
    rag.ingestText("The quick brown fox jumps over the lazy dog.", "doc1")
    rag.ingestText("Machine learning is a subset of artificial intelligence.", "doc2")
    rag.ingestText("Natural language processing enables computers to understand text.", "doc3")

    val result = rag.query("artificial intelligence")

    result.isRight shouldBe true
    val results = result.toOption.get
    results should not be empty
  }

  it should "respect topK parameter" in {
    val rag = createMockRAG().toOption.get

    // Ingest multiple documents
    (1 to 10).foreach(i => rag.ingestText(s"Document $i content about various topics.", s"doc$i"))

    val result = rag.query("document", topK = Some(3))

    result.isRight shouldBe true
    val results = result.toOption.get
    results.size should be <= 3
  }

  // ==========================================================================
  // Embedding Batch Tests
  // ==========================================================================

  "RAG.embedBatch" should "return EmbeddingError when provider returns empty embeddings list" in {
    val emptyProvider   = new EmptyEmbeddingProvider()
    val embeddingClient = new EmbeddingClient(emptyProvider)

    val config = RAGConfig.default
    val rag    = RAG.buildWithClient(config, embeddingClient).toOption.get

    // embedBatch is private, so we test it through ingestText with a provider that returns empty
    val result = rag.ingestText("Test content", "doc1")

    result.fold(
      {
        case embErr: EmbeddingError =>
          embErr.message should include("empty embeddings")
          embErr.provider should not be "unknown"
          embErr.provider shouldBe "text-embedding-3-small"
        case other =>
          fail(s"Expected EmbeddingError but got: ${other.getClass.getSimpleName}: ${other.message}")
      },
      _ => fail("Expected EmbeddingError for empty embeddings")
    )
  }

  it should "return EmbeddingError when embeddings count doesn't match batch size" in {
    val mismatchedProvider = new MismatchedEmbeddingProvider(returnCount = 1)
    val embeddingClient    = new EmbeddingClient(mismatchedProvider)

    val config = RAGConfig.default
    val rag    = RAG.buildWithClient(config, embeddingClient).toOption.get

    // Try to ingest multiple chunks but provider only returns 1 embedding
    val result = rag.ingestChunks(
      "doc1",
      Seq("Chunk 1", "Chunk 2")
    )

    result.fold(
      {
        case embErr: EmbeddingError =>
          embErr.message should include("2 texts")
          embErr.message should include("1 embedding")
          embErr.provider should not be "unknown"
          embErr.provider shouldBe "text-embedding-3-small"
        case other =>
          fail(s"Expected EmbeddingError but got: ${other.getClass.getSimpleName}: ${other.message}")
      },
      _ => fail("Expected EmbeddingError for mismatched embeddings count")
    )
  }

  it should "succeed when embeddings count matches batch size" in {
    val rag = createMockRAG().toOption.get

    val result = rag.ingestChunks(
      "doc1",
      Seq("Chunk 1", "Chunk 2", "Chunk 3")
    )

    result.fold(
      error => fail(s"Expected success but got error: ${error.message}"),
      count => count shouldBe 3
    )
  }

  "RAG.queryWithAnswer" should "fail without LLM client" in {
    val rag = createMockRAG(withLLM = false).toOption.get
    rag.ingestText("Some content", "doc1")

    val result = rag.queryWithAnswer("What is the content?")

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "generate answer with LLM client" in {
    val rag = createMockRAG(withLLM = true).toOption.get
    rag.ingestText("The answer to life is 42.", "doc1")

    val result = rag.queryWithAnswer("What is the answer to life?")

    result.isRight shouldBe true
    val answer = result.toOption.get
    answer.answer should not be empty
    answer.question shouldBe "What is the answer to life?"
  }

  // ==========================================================================
  // Sync Tests
  // ==========================================================================

  "RAG.sync" should "add new documents" in {
    val rag = createMockRAG().toOption.get

    val loader = TextLoader.fromPairs(
      "doc1" -> "Content 1",
      "doc2" -> "Content 2"
    )

    val result = rag.sync(loader)

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.added shouldBe 2
    stats.updated shouldBe 0
    stats.deleted shouldBe 0
  }

  it should "detect unchanged documents" in {
    val rag = createMockRAG().toOption.get

    val loader = TextLoader.fromPairs("doc1" -> "Content 1")

    // First sync
    rag.sync(loader)

    // Second sync with same content
    val result = rag.sync(loader)

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.added shouldBe 0
    stats.unchanged shouldBe 1
  }

  "RAG.needsUpdate" should "return true for new documents" in {
    val rag = createMockRAG().toOption.get
    val doc = Document("new-doc", "New content")

    val result = rag.needsUpdate(doc)

    result.isRight shouldBe true
    result.toOption.get shouldBe true
  }

  "RAG.deleteDocument" should "remove document from store" in {
    val rag = createMockRAG().toOption.get
    rag.ingestText("Content to delete", "doc-to-delete")
    rag.documentCount shouldBe 1

    val result = rag.deleteDocument("doc-to-delete")

    result.isRight shouldBe true
  }

  // ==========================================================================
  // Statistics Tests
  // ==========================================================================

  "RAG.stats" should "return correct statistics" in {
    val rag = createMockRAG().toOption.get

    rag.ingestText("Document 1 content", "doc1")
    rag.ingestText("Document 2 content", "doc2")

    val result = rag.stats

    result.isRight shouldBe true
    val stats = result.toOption.get
    stats.documentCount shouldBe 2
    stats.chunkCount should be >= 2
  }

  "RAG.documentCount" should "track ingested documents" in {
    val rag = createMockRAG().toOption.get

    rag.documentCount shouldBe 0

    rag.ingestText("Content 1", "doc1")
    rag.documentCount shouldBe 1

    rag.ingestText("Content 2", "doc2")
    rag.documentCount shouldBe 2
  }

  "RAG.chunkCount" should "track indexed chunks" in {
    val rag = createMockRAG().toOption.get

    rag.chunkCount shouldBe 0

    rag.ingestChunks("doc1", Seq("Chunk 1", "Chunk 2", "Chunk 3"))
    rag.chunkCount shouldBe 3
  }

  // ==========================================================================
  // Lifecycle Tests
  // ==========================================================================

  "RAG.clear" should "remove all indexed data" in {
    val rag = createMockRAG().toOption.get

    rag.ingestText("Content 1", "doc1")
    rag.ingestText("Content 2", "doc2")
    rag.documentCount should be > 0

    val result = rag.clear()

    result.isRight shouldBe true
    rag.documentCount shouldBe 0
    rag.chunkCount shouldBe 0
  }

  "RAG.close" should "not throw exception" in {
    val rag = createMockRAG().toOption.get

    noException should be thrownBy rag.close()
  }

  // ==========================================================================
  // Permission-Aware API Tests
  // ==========================================================================

  "RAG.hasPermissions" should "return false without SearchIndex" in {
    val rag = createMockRAG().toOption.get
    rag.hasPermissions shouldBe false
  }

  "RAG.searchIndex" should "return None without SearchIndex" in {
    val rag = createMockRAG().toOption.get
    rag.searchIndex shouldBe None
  }

  "RAG.queryWithPermissions" should "fail without SearchIndex" in {
    val rag = createMockRAG().toOption.get

    val result = rag.queryWithPermissions(
      UserAuthorization.Admin,
      CollectionPattern.All,
      "test query"
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  "RAG.ingestWithPermissions" should "fail without SearchIndex" in {
    val rag = createMockRAG().toOption.get

    val result = rag.ingestWithPermissions(
      CollectionPath.unsafe("test"),
      "doc-1",
      "Content"
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  "RAG.deleteFromCollection" should "fail without SearchIndex" in {
    val rag = createMockRAG().toOption.get

    val result = rag.deleteFromCollection(
      CollectionPath.unsafe("test"),
      "doc-1"
    )

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  // ==========================================================================
  // Configuration Tests
  // ==========================================================================

  "RAG.config" should "expose the configuration" in {
    val config = RAGConfig.default.withTopK(10).withSystemPrompt("Custom prompt")
    val rag    = createMockRAG(config = config).toOption.get

    rag.config.topK shouldBe 10
    rag.config.systemPrompt shouldBe Some("Custom prompt")
  }

  // ==========================================================================
  // RAG.embedQuery empty-embedding path Tests
  // ==========================================================================

  "RAG.query" should "return EmbeddingError when the embedding provider returns an empty list" in {
    val emptyEmbeddingClient = new EmbeddingClient(new EmptyEmbeddingProvider)
    val result = RAG
      .buildWithClient(RAGConfig.default, emptyEmbeddingClient)
      .flatMap(_.query("what is Scala?"))

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

  // ==========================================================================
  // RAG.builder Tests
  // ==========================================================================

  "RAG.builder" should "return RAGConfig.default" in {
    RAG.builder() shouldBe RAGConfig.default
  }

  // ==========================================================================
  // RAGConfigOps Tests
  // ==========================================================================

  "RAGConfigOps.build" should "be accessible via implicit" in {
    import RAG.RAGConfigOps

    val config = RAGConfig.default
    val ops    = new RAGConfigOps(config)

    // Verify the implicit class exists
    ops shouldBe a[RAG.RAGConfigOps]
  }
}
