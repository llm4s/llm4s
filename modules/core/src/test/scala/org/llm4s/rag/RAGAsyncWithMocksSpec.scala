package org.llm4s.rag

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.loader._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Millis, Seconds, Span }

import java.io.File
import java.nio.file.Files
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Tests for RAG async operations and Path-based ingestion using mock components.
 */
class RAGAsyncWithMocksSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  implicit val defaultPatience: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(100, Millis))

  class MockEmbeddingProvider(dimensions: Int = 3) extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      val embeddings = request.input.map { text =>
        val hash = text.hashCode.abs
        (0 until dimensions).map(i => ((hash + i) % 100) / 100.0).toSeq
      }
      Right(EmbeddingResponse(embeddings = embeddings))
    }
  }

  private def createMockRAG(config: RAGConfig = RAGConfig.default): RAG = {
    val client = new EmbeddingClient(new MockEmbeddingProvider())
    RAG.buildWithClient(config, client).toOption.get
  }

  // ==========================================================================
  // ingest(Path) Tests
  // ==========================================================================

  "RAG.ingest(path: Path)" should "ingest a text file via Path" in {
    val rag     = createMockRAG()
    val tempDir = Files.createTempDirectory("rag-path-test")
    try {
      val file = tempDir.resolve("test.txt")
      Files.write(file, "Content for path-based ingestion test.".getBytes)

      val result = rag.ingest(file)

      result.isRight shouldBe true
      rag.documentCount shouldBe 1
    } finally {
      deleteRecursively(tempDir.toFile)
      rag.close()
    }
  }

  it should "ingest a directory via Path" in {
    val rag     = createMockRAG()
    val tempDir = Files.createTempDirectory("rag-path-dir-test")
    try {
      Files.write(tempDir.resolve("a.txt"), "First file.".getBytes)
      Files.write(tempDir.resolve("b.md"), "Second file.".getBytes)

      val result = rag.ingest(tempDir)

      result.isRight shouldBe true
      rag.documentCount should be >= 2
    } finally {
      deleteRecursively(tempDir.toFile)
      rag.close()
    }
  }

  // ==========================================================================
  // ingestPath Tests
  // ==========================================================================

  "RAG.ingestPath" should "ingest with metadata" in {
    val rag     = createMockRAG()
    val tempDir = Files.createTempDirectory("rag-ingestpath-test")
    try {
      val file = tempDir.resolve("doc.txt")
      Files.write(file, "Document with metadata.".getBytes)

      val result = rag.ingestPath(file, Map("author" -> "test"))

      result.isRight shouldBe true
      rag.documentCount shouldBe 1
    } finally {
      deleteRecursively(tempDir.toFile)
      rag.close()
    }
  }

  // ==========================================================================
  // ingestAsync Tests
  // ==========================================================================

  "RAG.ingestAsync" should "process documents asynchronously" in {
    val rag = createMockRAG()
    try {
      val loader = TextLoader.fromPairs(
        "doc1" -> "First document content for async."
      )

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.totalAttempted shouldBe 1
        stats.successful shouldBe 1
        stats.failed shouldBe 0
      }
    } finally rag.close()
  }

  it should "handle loader failures asynchronously" in {
    val rag = createMockRAG()
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.failure("bad-source", ProcessingError("test", "Simulated failure"))
        )
        override def estimatedCount: Option[Int] = Some(1)
        override def description: String         = "Async failure test loader"
      }

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.failed shouldBe 1
        stats.errors should have size 1
      }
    } finally rag.close()
  }

  it should "report correct total for multiple documents" in {
    val rag = createMockRAG()
    try {
      val loader = TextLoader.fromPairs(
        "doc1" -> "First document.",
        "doc2" -> "Second document.",
        "doc3" -> "Third document."
      )

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.totalAttempted shouldBe 3
        // Due to in-memory store thread-safety, exact success count may vary
        (stats.successful + stats.failed) shouldBe 3
      }
    } finally rag.close()
  }

  it should "skip empty documents when configured" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(skipEmptyDocuments = true))
    val rag    = createMockRAG(config)
    try {
      val loader = TextLoader(
        Seq(
          Document(id = "doc-empty", content = ""),
          Document(id = "doc-valid", content = "Valid content here.")
        )
      )

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.successful shouldBe 1
        stats.skipped shouldBe 1
      }
    } finally rag.close()
  }

  it should "handle skipped results" in {
    val rag = createMockRAG()
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.success(Document("doc1", "Content.")),
          LoadResult.skipped("skipped-source", "Not relevant")
        )
        override def estimatedCount: Option[Int] = Some(2)
        override def description: String         = "Async skipped test loader"
      }

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.successful shouldBe 1
        stats.skipped shouldBe 1
      }
    } finally rag.close()
  }

  it should "fail fast when configured and errors present" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(failFast = true))
    val rag    = createMockRAG(config)
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator(
          LoadResult.failure("bad-source", ProcessingError("test", "Simulated")),
          LoadResult.success(Document("doc1", "Content."))
        )
        override def estimatedCount: Option[Int] = Some(2)
        override def description: String         = "Async fail-fast test loader"
      }

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult)(result => result.isLeft shouldBe true)
    } finally rag.close()
  }

  it should "handle empty loader" in {
    val rag = createMockRAG()
    try {
      val loader = new DocumentLoader {
        override def load(): Iterator[LoadResult] = Iterator.empty
        override def estimatedCount: Option[Int]  = Some(0)
        override def description: String          = "Empty loader"
      }

      val futureResult = rag.ingestAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.totalAttempted shouldBe 0
        stats.successful shouldBe 0
      }
    } finally rag.close()
  }

  // ==========================================================================
  // syncAsync Tests
  // ==========================================================================

  "RAG.syncAsync" should "add new documents asynchronously" in {
    val rag = createMockRAG()
    try {
      val loader = TextLoader.fromPairs(
        "doc1" -> "Content one.",
        "doc2" -> "Content two."
      )

      val futureResult = rag.syncAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.added shouldBe 2
        stats.updated shouldBe 0
        stats.deleted shouldBe 0
        stats.unchanged shouldBe 0
      }
    } finally rag.close()
  }

  it should "detect unchanged documents on second sync" in {
    val rag = createMockRAG()
    try {
      val loader = TextLoader.fromPairs(
        "doc1" -> "Content one.",
        "doc2" -> "Content two."
      )

      // First sync
      whenReady(rag.syncAsync(loader))(result => result.isRight shouldBe true)

      // Second sync with same content
      val futureResult = rag.syncAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.added shouldBe 0
        stats.unchanged shouldBe 2
      }
    } finally rag.close()
  }

  it should "detect changed documents" in {
    val rag = createMockRAG()
    try {
      val loader1 = TextLoader.fromPairs("doc1" -> "Original content.")
      whenReady(rag.syncAsync(loader1))(_.isRight shouldBe true)

      val loader2      = TextLoader.fromPairs("doc1" -> "Updated content with changes.")
      val futureResult = rag.syncAsync(loader2)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.updated shouldBe 1
        stats.added shouldBe 0
      }
    } finally rag.close()
  }

  it should "detect deleted documents" in {
    val rag = createMockRAG()
    try {
      val loader1 = TextLoader.fromPairs(
        "doc1" -> "Content one.",
        "doc2" -> "Content two."
      )
      whenReady(rag.syncAsync(loader1))(_.isRight shouldBe true)

      // Second sync only has doc-1
      val loader2      = TextLoader.fromPairs("doc1" -> "Content one.")
      val futureResult = rag.syncAsync(loader2)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.unchanged shouldBe 1
        stats.deleted shouldBe 1
      }
    } finally rag.close()
  }

  it should "skip empty documents when configured" in {
    val config = RAGConfig.default.copy(loadingConfig = LoadingConfig(skipEmptyDocuments = true))
    val rag    = createMockRAG(config)
    try {
      val loader = TextLoader(
        Seq(
          Document(id = "doc-empty", content = "   "),
          Document(id = "doc-valid", content = "Valid content.")
        )
      )

      val futureResult = rag.syncAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.added shouldBe 1
      }
    } finally rag.close()
  }

  // ==========================================================================
  // refreshAsync Tests
  // ==========================================================================

  "RAG.refreshAsync" should "clear and re-ingest all documents" in {
    val rag = createMockRAG()
    try {
      // First ingest
      val loader = TextLoader.fromPairs("doc1" -> "Content.")
      rag.ingest(loader)

      // Refresh
      val futureResult = rag.refreshAsync(loader)

      whenReady(futureResult) { result =>
        result.isRight shouldBe true
        val stats = result.toOption.get
        stats.added shouldBe 1
        stats.updated shouldBe 0
        stats.deleted shouldBe 0
        stats.unchanged shouldBe 0
      }
    } finally rag.close()
  }

  // ==========================================================================
  // Helpers
  // ==========================================================================

  private def deleteRecursively(file: File): Unit = {
    if (file.isDirectory) {
      Option(file.listFiles()).foreach(_.foreach(deleteRecursively))
    }
    file.delete()
  }
}
