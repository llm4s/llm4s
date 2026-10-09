package org.llm4s.agent.memory

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable.ListBuffer

/**
 * A memory store knows which side of retrieval it is on: it embeds the memories it keeps as documents and the
 * text it searches with as a query. A model that embeds the two differently is only searched correctly if
 * each text was embedded on its own side.
 */
class MemoryEmbeddingPurposeSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService =
    Llm4sConfig.modelRegistryService().fold(e => fail(s"no model registry: ${e.message}"), identity)

  private val modelConfig = EmbeddingModelConfig("purpose-test-model", 3)

  /** Records every request it is asked, and answers with the same vector for every text. */
  final private class RecordingProvider extends EmbeddingProvider {
    val requests: ListBuffer[EmbeddingRequest] = ListBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      Right(EmbeddingResponse(request.input.map(_ => Seq(1.0, 0.0, 0.0))))
    }

    def purposes: List[(Seq[String], InputPurpose)] = requests.map(r => (r.input, r.purpose)).toList
  }

  private def serviceOver(provider: RecordingProvider): LLMEmbeddingService =
    LLMEmbeddingService(new EmbeddingClient(provider), modelConfig)

  private def memory(content: String): Memory =
    Memory(MemoryId.generate(), content, MemoryType.Conversation, Map.empty, Instant.now())

  private def unwrap[A](result: Result[A]): A =
    result.fold(e => fail(s"unexpected error: ${e.message}"), identity)

  "LLMEmbeddingService" should "embed a query as a query and everything else as documents" in {
    val provider = new RecordingProvider
    val service  = serviceOver(provider)

    unwrap(service.embedQuery("what do I like?")).length shouldBe 3
    unwrap(service.embed("I like tea")).length shouldBe 3
    unwrap(service.embedBatch(Seq("a", "b"))).length shouldBe 2

    provider.purposes shouldBe List(
      (Seq("what do I like?"), InputPurpose.Query),
      (Seq("I like tea"), InputPurpose.Document),
      (Seq("a", "b"), InputPurpose.Document)
    )
  }

  it should "send nothing for an empty batch, whatever the purpose" in {
    val provider = new RecordingProvider
    unwrap(serviceOver(provider).embedBatch(Seq.empty)) shouldBe empty
    provider.requests shouldBe empty
  }

  "EmbeddingService.embedQuery" should "delegate to embed unless a service overrides it" in {
    val seen = ListBuffer.empty[String]
    val service = new EmbeddingService {
      override def embed(text: String): Result[Array[Float]] = { seen += text; Right(Array(1f, 0f, 0f)) }
      override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] = Right(texts.map(_ => Array(1f, 0f, 0f)))
      override def dimensions: Int                                           = 3
    }

    unwrap(service.embedQuery("q")).toList shouldBe List(1f, 0f, 0f)
    seen.toList shouldBe List("q")
  }

  "VectorMemoryStore.search" should "embed the query as a query and the stored memory as a document" in {
    val provider = new RecordingProvider
    val store    = unwrap(VectorMemoryStore.inMemory(serviceOver(provider)))

    unwrap(store.store(memory("I like tea")))
    unwrap(store.search("what do I like?", 5, MemoryFilter.All)) should not be empty

    provider.purposes shouldBe List(
      (Seq("I like tea"), InputPurpose.Document),
      (Seq("what do I like?"), InputPurpose.Query)
    )
    store.close()
  }

  "EmbeddingMemoryStore.search" should "embed the query as a query" in {
    val provider = new RecordingProvider
    val store0   = EmbeddingMemoryStore.empty(serviceOver(provider))

    // This store searches memories that already carry an embedding; it only embeds the query.
    val store = unwrap(store0.store(memory("I like tea").withEmbedding(Array(1f, 0f, 0f))))
    unwrap(store.search("what do I like?", 5, MemoryFilter.All)) should not be empty

    provider.purposes shouldBe List((Seq("what do I like?"), InputPurpose.Query))
  }
}
