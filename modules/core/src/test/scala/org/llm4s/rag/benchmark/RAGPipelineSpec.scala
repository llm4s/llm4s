package org.llm4s.rag.benchmark

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ KeywordIndex, VectorStoreFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGPipelineSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var _pipeline: RAGPipeline = _

  override def afterEach(): Unit =
    if (_pipeline != null) _pipeline.close()

  // Minimal mock LLM client (not used in search path, required for construction)
  private class MockLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(org.llm4s.error.ProcessingError("mock", "not used"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Left(org.llm4s.error.ProcessingError("mock", "not used"))

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 0
  }

  // Mock embedding provider returning an empty embeddings list
  private class EmptyEmbeddingProvider extends EmbeddingProvider {
    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
      Right(EmbeddingResponse(embeddings = Seq.empty, usage = None))
  }

  // Mock embedding provider returning a real (non-empty) embedding
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

  // Helpers

  private def buildPipeline(provider: EmbeddingProvider = new EmptyEmbeddingProvider): RAGPipeline = {
    val vectorStore = VectorStoreFactory
      .inMemory()
      .fold(e => fail(s"Could not create in-memory vector store: ${e.formatted}"), identity)
    val keywordIndex =
      KeywordIndex.inMemory().fold(e => fail(s"Could not create in-memory keyword index: ${e.formatted}"), identity)

    _pipeline = RAGPipeline.withStores(
      config = RAGExperimentConfig.default,
      llmClient = new MockLLMClient,
      embeddingClient = new EmbeddingClient(provider),
      vectorStore = vectorStore,
      keywordIndex = keywordIndex
    )
    _pipeline
  }

  // Tests

  "RAGPipeline.search" should "return EmbeddingError when the provider returns an empty embeddings list" in {
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

  it should "propagate the error regardless of the topK parameter" in {
    val pipeline = buildPipeline()
    val result   = pipeline.search("any query", topK = Some(10))

    result shouldBe a[Left[_, _]]
  }

  it should "return Right with empty results when embeddings are non-empty but the index is empty" in {
    val pipeline = buildPipeline(provider = new DummyEmbeddingProvider)
    val result   = pipeline.search("what is Scala?")

    result.fold(e => fail(s"Expected Right but got Left: ${e.formatted}"), results => results shouldBe empty)
  }
}
