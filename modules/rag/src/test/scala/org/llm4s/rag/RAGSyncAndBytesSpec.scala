package org.llm4s.rag

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.loader._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for RAG sync, byte ingestion, and async operations.
 */
class RAGSyncAndBytesSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  class MockEmbeddingProvider(dimensions: Int = 3) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = request.input.map { text =>
        val hash = text.hashCode.abs
        (0 until dimensions).map(i => ((hash + i) % 100) / 100.0).toSeq
      }
      Right(EmbeddingResponse(embeddings = embeddings))
    }
  }

  class MockLLMClient extends LLMClient {
    var responseOverride: Option[String] = None

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "mock",
          created = 0L,
          content = responseOverride.getOrElse("Mock answer."),
          model = "mock",
          message = AssistantMessage(responseOverride.getOrElse("Mock answer.")),
          usage = Some(TokenUsage(10, 5, 15))
        )
      )

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def createMockRAG(
    config: RAGConfig = RAGConfig.default,
    withLLM: Boolean = false
  ): Result[RAG] = {
    val mockLLM = new MockLLMClient()
    val cfg     = if (withLLM) config.copy(llmClient = Some(mockLLM)) else config
    val client  = new EmbeddingClient(new MockEmbeddingProvider())
    RAG.buildWithClient(cfg, client)
  }

  // ==========================================================================
  // Byte Ingestion Tests
  // ==========================================================================

  "RAG.ingestBytes" should "ingest plain text bytes" in {
    val rag = createMockRAG().toOption.get
    try {
      val content = "Hello, this is some text content for testing.".getBytes("UTF-8")
      val result  = rag.ingestBytes(content, "test.txt", "doc-1")
      result.isRight shouldBe true
      result.toOption.get should be > 0
      rag.documentCount should be > 0
    } finally rag.close()
  }

  it should "include format metadata" in {
    val rag = createMockRAG().toOption.get
    try {
      val content = "Text content.".getBytes("UTF-8")
      val result  = rag.ingestBytes(content, "test.txt", "doc-1", Map("source" -> "test"))
      result.isRight shouldBe true
    } finally rag.close()
  }

  "RAG.ingestBytesMultiple" should "ingest multiple documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val docs = Iterator(
        ("Doc one content.".getBytes("UTF-8"), "doc1.txt", "doc-1", Map.empty[String, String]),
        ("Doc two content.".getBytes("UTF-8"), "doc2.txt", "doc-2", Map.empty[String, String])
      )

      val result = rag.ingestBytesMultiple(docs)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.successful shouldBe 2
      stats.failed shouldBe 0
      stats.totalAttempted shouldBe 2
    } finally rag.close()
  }

  it should "handle partial failures gracefully" in {
    val rag = createMockRAG().toOption.get
    try {
      val docs = Iterator(
        ("Valid text content.".getBytes("UTF-8"), "good.txt", "doc-1", Map.empty[String, String]),
        // Empty content will still be processed (not a binary format failure)
        ("Some other content.".getBytes("UTF-8"), "good2.txt", "doc-2", Map.empty[String, String])
      )

      val result = rag.ingestBytesMultiple(docs)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.totalAttempted shouldBe 2
    } finally rag.close()
  }

  it should "handle empty iterator" in {
    val rag = createMockRAG().toOption.get
    try {
      val result = rag.ingestBytesMultiple(Iterator.empty)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.totalAttempted shouldBe 0
      stats.successful shouldBe 0
      stats.failed shouldBe 0
    } finally rag.close()
  }

  // ==========================================================================
  // Sync Tests
  // ==========================================================================

  "RAG.sync" should "add new documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content for document one."),
          Document(id = "doc-2", content = "Content for document two.")
        )
      )

      val result = rag.sync(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.added shouldBe 2
      stats.updated shouldBe 0
      stats.deleted shouldBe 0
      stats.unchanged shouldBe 0
    } finally rag.close()
  }

  it should "detect unchanged documents on second sync" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content one."),
          Document(id = "doc-2", content = "Content two.")
        )
      )

      rag.sync(loader)
      val result = rag.sync(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.added shouldBe 0
      stats.unchanged shouldBe 2
    } finally rag.close()
  }

  it should "detect changed documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader1 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Original content.")
        )
      )
      rag.sync(loader1)

      val loader2 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Updated content with changes.")
        )
      )
      val result = rag.sync(loader2)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.updated shouldBe 1
      stats.added shouldBe 0
    } finally rag.close()
  }

  it should "detect deleted documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader1 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content one."),
          Document(id = "doc-2", content = "Content two.")
        )
      )
      rag.sync(loader1)

      // Second sync only has doc-1
      val loader2 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content one.")
        )
      )
      val result = rag.sync(loader2)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.unchanged shouldBe 1
      stats.deleted shouldBe 1
    } finally rag.close()
  }

  it should "handle mixed operations" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader1 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content one."),
          Document(id = "doc-2", content = "Content two.")
        )
      )
      rag.sync(loader1)

      // doc-1 unchanged, doc-2 deleted, doc-3 added
      val loader2 = TextLoader(
        Seq(
          Document(id = "doc-1", content = "Content one."),
          Document(id = "doc-3", content = "Content three.")
        )
      )
      val result = rag.sync(loader2)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.unchanged shouldBe 1
      stats.deleted shouldBe 1
      stats.added shouldBe 1
    } finally rag.close()
  }

  // ==========================================================================
  // Refresh Tests
  // ==========================================================================

  "RAG.refresh" should "clear and re-ingest all documents" in {
    val rag = createMockRAG().toOption.get
    try {
      // First ingest
      val loader = TextLoader(Seq(Document(id = "doc-1", content = "Content.")))
      rag.ingest(loader)

      // Refresh with same loader
      val result = rag.refresh(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.added shouldBe 1
      stats.updated shouldBe 0
      stats.deleted shouldBe 0
      stats.unchanged shouldBe 0
    } finally rag.close()
  }

  // ==========================================================================
  // DeleteDocument Tests
  // ==========================================================================

  "RAG.deleteDocument" should "remove document and unregister from registry" in {
    val rag = createMockRAG().toOption.get
    try {
      rag.ingestText("Some content.", "doc-to-delete")
      rag.deleteDocument("doc-to-delete")

      // After deletion, needsUpdate should see it as new
      val doc    = Document(id = "doc-to-delete", content = "Some content.")
      val result = rag.needsUpdate(doc)
      result.isRight shouldBe true
      result.toOption.get shouldBe true
    } finally rag.close()
  }

  // ==========================================================================
  // NeedsUpdate Tests
  // ==========================================================================

  "RAG.needsUpdate" should "return true for new documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val doc    = Document(id = "new-doc", content = "Content.")
      val result = rag.needsUpdate(doc)
      result shouldBe Right(true)
    } finally rag.close()
  }

  it should "return false for unchanged documents after sync" in {
    val rag = createMockRAG().toOption.get
    try {
      val doc    = Document(id = "synced-doc", content = "Content.")
      val loader = TextLoader(Seq(doc))
      rag.sync(loader)

      val result = rag.needsUpdate(doc)
      result shouldBe Right(false)
    } finally rag.close()
  }

  it should "return true for changed documents" in {
    val rag = createMockRAG().toOption.get
    try {
      val doc    = Document(id = "doc-1", content = "Original content.")
      val loader = TextLoader(Seq(doc))
      rag.sync(loader)

      val changedDoc = Document(id = "doc-1", content = "Changed content.")
      val result     = rag.needsUpdate(changedDoc)
      result shouldBe Right(true)
    } finally rag.close()
  }

  // ==========================================================================
  // IngestChunks Tests
  // ==========================================================================

  "RAG.ingestChunks" should "ingest pre-chunked content" in {
    val rag = createMockRAG().toOption.get
    try {
      val chunks = Seq("Chunk one.", "Chunk two.", "Chunk three.")
      val result = rag.ingestChunks("doc-chunked", chunks)
      result.isRight shouldBe true
      result.toOption.get shouldBe 3
      rag.chunkCount shouldBe 3
    } finally rag.close()
  }

  it should "handle empty chunk list" in {
    val rag = createMockRAG().toOption.get
    try {
      val result = rag.ingestChunks("doc-empty", Seq.empty)
      result shouldBe Right(0)
    } finally rag.close()
  }

  it should "accept metadata" in {
    val rag = createMockRAG().toOption.get
    try {
      val result = rag.ingestChunks("doc-meta", Seq("Chunk."), Map("key" -> "value"))
      result.isRight shouldBe true
    } finally rag.close()
  }

  // ==========================================================================
  // Stats Tests
  // ==========================================================================

  "RAG.stats" should "return correct counts after ingestion" in {
    val rag = createMockRAG().toOption.get
    try {
      rag.ingestText("Document one content.", "doc-1")
      rag.ingestText("Document two content.", "doc-2")

      val stats = rag.stats
      stats.isRight shouldBe true
      val s = stats.toOption.get
      s.documentCount shouldBe 2
      s.chunkCount should be >= 2
    } finally rag.close()
  }

  "RAG.clear" should "reset all counts to zero" in {
    val rag = createMockRAG().toOption.get
    try {
      rag.ingestText("Content.", "doc-1")
      rag.clear()
      rag.documentCount shouldBe 0
      rag.chunkCount shouldBe 0
    } finally rag.close()
  }

  // ==========================================================================
  // DocumentLoader Ingest Tests
  // ==========================================================================

  "RAG.ingest with DocumentLoader" should "skip empty documents when configured" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(skipEmptyDocuments = true))
    val rag    = createMockRAG(config = config).toOption.get
    try {
      val loader = TextLoader(
        Seq(
          Document(id = "doc-empty", content = ""),
          Document(id = "doc-whitespace", content = "   "),
          Document(id = "doc-valid", content = "Valid content.")
        )
      )

      val result = rag.ingest(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.successful shouldBe 1
      stats.skipped shouldBe 2
    } finally rag.close()
  }

  it should "fail fast when configured" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(failFast = true))
    val rag    = createMockRAG(config = config).toOption.get
    try {
      // Create a loader that yields a failure
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.success(Document(id = "doc-ok", content = "Content.")),
          LoadResult.failure("bad-source", org.llm4s.error.ProcessingError("test", "Simulated failure")),
          LoadResult.success(Document(id = "doc-skipped", content = "Should not be processed."))
        )
        override def estimatedCount: Option[Int] = Some(3)
        override def description: String         = "Fail-fast test loader"
      }

      val result = rag.ingest(loader)
      result.isLeft shouldBe true
    } finally rag.close()
  }

  it should "continue on error when not fail-fast" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(failFast = false))
    val rag    = createMockRAG(config = config).toOption.get
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.success(Document(id = "doc-1", content = "Content one.")),
          LoadResult.failure("bad-source", org.llm4s.error.ProcessingError("test", "Simulated")),
          LoadResult.success(Document(id = "doc-2", content = "Content two."))
        )
        override def estimatedCount: Option[Int] = Some(3)
        override def description: String         = "Error recovery test loader"
      }

      val result = rag.ingest(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.successful shouldBe 2
      stats.failed shouldBe 1
      stats.errors should have size 1
    } finally rag.close()
  }

  it should "handle skipped results" in {
    val rag = createMockRAG().toOption.get
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.success(Document(id = "doc-1", content = "Content.")),
          LoadResult.skipped("skipped-source", "Not relevant")
        )
        override def estimatedCount: Option[Int] = Some(2)
        override def description: String         = "Skipped test loader"
      }

      val result = rag.ingest(loader)
      result.isRight shouldBe true
      val stats = result.toOption.get
      stats.successful shouldBe 1
      stats.skipped shouldBe 1
    } finally rag.close()
  }
}
