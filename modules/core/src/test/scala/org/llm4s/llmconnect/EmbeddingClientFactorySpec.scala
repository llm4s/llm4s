package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.llmconnect.spi.fixtures.FixtureEmbeddings
import org.llm4s.model.ModelRegistryService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmbeddingClientFactorySpec extends AnyWordSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  "EmbeddingClient.from(provider,cfg)" should {
    "build client for voyage without throwing" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "https://api.voyage.ai",
        model = "voyage-3",
        apiKey = "vk-test"
      )
      val res = EmbeddingClient.from("voyage", cfg)
      res.isRight shouldBe true
    }

    "reject unknown provider" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "http://localhost",
        model = "m",
        apiKey = "k"
      )
      val res = EmbeddingClient.from("unknown", cfg)
      res.isLeft shouldBe true
    }

    "name the registered embedding providers when one is unknown" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "http://localhost", model = "m", apiKey = "k")

      val message = EmbeddingClient
        .from("unknown", cfg)
        .left
        .toOption
        .getOrElse(fail("expected an unknown-provider error"))
        .message

      message should include("Embedding provider 'unknown'")
      message should include("voyage")
      // The caller is pointed at the config key that named it, and at how to supply it.
      message should include("llm4s.embeddings.model")
      message should include("add the dependency that supplies it")
    }

    "resolve a provider the application registered itself" in {
      // The point of the SPI: an embedding provider in its own module is reachable
      // here without llm4s-core knowing it exists.
      given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(FixtureEmbeddings)

      val cfg = EmbeddingProviderConfig(baseUrl = "http://fixture", model = "m", apiKey = "k")
      val client = EmbeddingClient
        .from("fixtureembed", cfg)
        .getOrElse(fail("expected the registered fixture provider to resolve"))

      val response = client
        .embed(EmbeddingRequest(input = Seq("hello"), model = EmbeddingModelConfig("m", dimensions = 2)))
        .getOrElse(fail("expected the fixture provider to embed"))

      // The config reached the provider the descriptor built, not a stale one.
      response.metadata.get("baseUrl") shouldBe Some("http://fixture")
    }

    "keep compiling for a caller that passes the model registry explicitly" in {
      // `from` gained a second contextual parameter, and this is the call shape that would
      // break if a partial `using` list were not allowed: Scala 3 infers the remainder, and
      // `ProviderRegistry`'s given lives in its own companion, so it is always in scope.
      val cfg     = EmbeddingProviderConfig(baseUrl = "https://api.voyage.ai", model = "voyage-3", apiKey = "vk-test")
      val service = summon[ModelRegistryService]

      EmbeddingClient.from("voyage", cfg)(using service).isRight shouldBe true
    }

    "fold an alias onto the provider that declares it" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "https://api.voyage.ai", model = "voyage-3", apiKey = "vk-test")

      EmbeddingClient.from("voyageai", cfg).isRight shouldBe true
    }
  }
}
