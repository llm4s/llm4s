package org.llm4s.rag

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * `RAG.buildWithEmbeddings`, the entry point `llm4s-java-rag` builds its pipelines with: a client the caller already
 * has, and its model, used as given, with no embedding provider resolved through a registry.
 */
class RAGBuildWithEmbeddingsSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  final private class RecordingProvider extends EmbeddingProvider {
    val requests: ListBuffer[EmbeddingRequest] = ListBuffer.empty

    override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
      requests += request
      Right(EmbeddingResponse(request.input.map(t => Seq(t.length.toDouble, 1.0))))
    }
  }

  private val model = EmbeddingModelConfig("caller-model", 2)

  "RAG.buildWithEmbeddings" should "embed with the caller's client and model, resolving no provider" in {
    val provider = new RecordingProvider
    // a provider no registry knows: building through `RAG.build` would fail with "not registered"
    val config = RAGConfig.default.copy(embeddingProvider = ProviderId("no-such-provider"))
    val rag = RAG
      .buildWithEmbeddings(config, new EmbeddingClient(provider), model)
      .fold(e => fail(s"could not build the RAG: ${e.formatted}"), identity)
    try {
      rag.ingestText("Scala runs on the JVM.", "doc-1") shouldBe Right(1)
      rag.query("JVM").map(_.map(_.metadata("docId"))) shouldBe Right(Seq("doc-1"))
      provider.requests.map(_.model).toSet shouldBe Set(model)
    } finally rag.close()
  }

  it should "fail as RAG.build does when a setting cannot be built" in {
    val config = RAGConfig.default.withLLMReranking
    RAG.buildWithEmbeddings(config, new EmbeddingClient(new RecordingProvider), model).isLeft shouldBe true
  }
}
