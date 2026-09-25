package org.llm4s.rag

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * `RAGConfig.embeddingProvider` is resolved through the [[ProviderRegistry]], so
 * the set of embedding providers RAG can use is whatever is registered - not a
 * list `llm4s-rag` keeps.
 */
class RAGEmbeddingResolutionSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** A provider `llm4s-rag` knows nothing about, recording what it was built with and asked. */
  final private class FakeEmbeddings extends EmbeddingProviderDescriptor:
    val built: mutable.Buffer[EmbeddingProviderConfig] = mutable.Buffer.empty
    val requests: mutable.Buffer[EmbeddingRequest]     = mutable.Buffer.empty

    val id: ProviderId                             = ProviderId("fake")
    override val aliases: Set[String]              = Set("phony")
    override val configSpec                        = EmbeddingConfigSpec(defaultModel = Some("fake-default"))
    override val modelDimensions: Map[String, Int] = Map("fake-default" -> 4, "fake-big" -> 6)

    def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      built += config
      Right(
        new EmbeddingProvider:
          def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
            requests += request
            Right(EmbeddingResponse(embeddings = request.input.map(_ => Seq.fill(request.model.dimensions)(0.5))))
      )

  private def resolver(model: String = ""): String => Result[EmbeddingProviderConfig] =
    _ => Right(EmbeddingProviderConfig(baseUrl = "http://fake", model = model, apiKey = "k"))

  private def ingestOnce(rag: RAG): Unit =
    rag.ingestText("Some text worth embedding.", "doc-1").isRight shouldBe true
    rag.close()

  "RAG.build" should "reach an embedding provider that only the registry knows about" in {
    val fake               = new FakeEmbeddings
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(fake)
    val rag                = RAG.build(RAGConfig().withEmbeddings("fake", "fake-big"), resolver()).value

    ingestOnce(rag)
    fake.built.map(_.model) shouldBe Seq("fake-big")
    fake.requests.head.model.name shouldBe "fake-big"
    fake.requests.head.model.dimensions shouldBe 6
  }

  it should "fold an alias onto the provider's canonical id before asking for its config" in {
    val fake               = new FakeEmbeddings
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(fake)
    val asked              = mutable.Buffer.empty[String]
    val recording: String => Result[EmbeddingProviderConfig] = id =>
      asked += id
      resolver()(id)

    RAG.build(RAGConfig().withEmbeddings("Phony"), recording).value.close()
    asked shouldBe Seq("fake")
  }

  it should "fail with the registry's own error for a provider that is not registered" in {
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(new FakeEmbeddings)
    val error              = RAG.build(RAGConfig().withEmbeddings("ollama", "nomic-embed-text"), resolver()).left.value

    error shouldBe a[ConfigurationError]
    error.message should include("Embedding provider 'ollama' is not registered")
    error.message should include("fake")
  }

  it should "take the model from the resolved provider config when the RAG config names none" in {
    val fake               = new FakeEmbeddings
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(fake)

    ingestOnce(RAG.build(RAGConfig().withEmbeddings("fake"), resolver(model = "fake-big")).value)
    fake.built.map(_.model) shouldBe Seq("fake-big")
    fake.requests.head.model.dimensions shouldBe 6
  }

  it should "fall back to the provider's default model when neither names one" in {
    val fake               = new FakeEmbeddings
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(fake)

    ingestOnce(RAG.build(RAGConfig().withEmbeddings("fake"), resolver()).value)
    fake.built.map(_.model) shouldBe Seq("fake-default")
    fake.requests.head.model.dimensions shouldBe 4
  }

  it should "fail naming the provider when no model can be found anywhere" in {
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(new EmbeddingProviderDescriptor:
      val id: ProviderId = ProviderId("modelless")
      def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
        Left(ConfigurationError("unreachable"))
    )
    val error = RAG.build(RAGConfig().withEmbeddings("modelless"), resolver()).left.value

    error shouldBe a[ConfigurationError]
    error.message should include("No embedding model for provider 'modelless'")
  }

  it should "prefer explicitly configured dimensions over the provider's" in {
    val fake               = new FakeEmbeddings
    given ProviderRegistry = ProviderRegistry.ofEmbeddings(fake)

    ingestOnce(RAG.build(RAGConfig().withEmbeddings("fake", "fake-big", 3), resolver()).value)
    fake.requests.head.model.dimensions shouldBe 3
  }
}
