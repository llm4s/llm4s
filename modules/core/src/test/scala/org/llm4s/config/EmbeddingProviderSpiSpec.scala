package org.llm4s.config

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor, ProviderRegistry }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The end-to-end claim of the embedding half of the SPI (#1131): an embedding
 * provider `llm4s-core` knows nothing about is '''configurable''', not merely
 * registrable.
 *
 * `EmbeddingClientFactorySpec` covers the client half - a registered descriptor
 * builds a client. This covers the half that was still missing afterwards: the
 * same descriptor's `llm4s.embeddings.<id>` section is read, its own defaults
 * are applied, and its own errors surface. Before this, a third-party provider
 * could be resolved and then had nothing to be resolved *with*, because
 * `EmbeddingsConfigLoader` held a typed section per built-in provider.
 */
class EmbeddingProviderSpiSpec extends AnyWordSpec with Matchers with EitherValues {

  /** A provider nothing in core mentions, with a spec exercising every default. */
  private object FixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId                = ProviderId("fixturecloud")
    override val aliases: Set[String] = Set("fixture-cloud")

    override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
      requiresApiKey = true,
      defaultBaseUrl = Some("https://fixture.example/v1"),
      defaultModel = Some("fixture-default-model"),
      apiKeyEnv = Some("FIXTURE_API_KEY")
    )

    def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }

  private given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(FixtureEmbeddings)

  private def load(hocon: String): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

  "an embedding provider core knows nothing about" should {

    "be configurable through its own section" in {
      val (provider, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixturecloud/fixture-large"
          |    fixturecloud { apiKey = "fk-test", baseUrl = "https://tenant.fixture.example/v1" }
          |  }
          |}""".stripMargin
      ).value

      provider shouldBe "fixturecloud"
      config shouldBe EmbeddingProviderConfig(
        baseUrl = "https://tenant.fixture.example/v1",
        model = "fixture-large",
        apiKey = "fk-test"
      )
    }

    "fall back to the defaults its descriptor declares" in {
      // The section carries only what the descriptor requires; base URL and model are
      // its own defaults, which core has no way to know.
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    provider = "fixturecloud"
          |    fixturecloud { apiKey = "fk-test" }
          |  }
          |}""".stripMargin
      ).value

      config.baseUrl shouldBe "https://fixture.example/v1"
      config.model shouldBe "fixture-default-model"
    }

    "prefer the unified EMBEDDING_MODEL over the section's model" in {
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixturecloud/from-unified"
          |    fixturecloud { apiKey = "fk", model = "from-section" }
          |  }
          |}""".stripMargin
      ).value

      config.model shouldBe "from-unified"
    }

    "be reachable by an alias it declares" in {
      val (provider, _) = load(
        """llm4s {
          |  embeddings {
          |    model = "fixture-cloud/m"
          |    fixturecloud { apiKey = "fk" }
          |  }
          |}""".stripMargin
      ).value

      // Reported under the canonical id, not the alias the user typed.
      provider shouldBe "fixturecloud"
    }

    "surface its own requirements as errors" in {
      val error = load(
        """llm4s { embeddings { model = "fixturecloud/m" } }"""
      ).left.value.message

      error should include("Missing fixturecloud embeddings apiKey")
      // The error names both the config path and the environment variable the descriptor
      // declared - neither of which core could have known.
      error should include("llm4s.embeddings.fixturecloud.apiKey")
      error should include("FIXTURE_API_KEY")
    }
  }

  "a provider that is registered for chat only" should {
    "not be configurable as an embedding provider" in {
      val error = load("""llm4s { embeddings { model = "anthropic/whatever" } }""").left.value.message

      error should include("Embedding provider 'anthropic'")
      error should include("is not registered")
    }
  }

  "an unregistered provider" should {

    "be reported against llm4s.embeddings.model when selected that way" in {
      val error = load("""llm4s { embeddings { model = "nosuch/whatever" } }""").left.value.message

      error should include("(from llm4s.embeddings.model)")
    }

    "be reported against llm4s.embeddings.provider when selected the legacy way" in {
      // The two settings are alternatives, so naming the wrong one sends the reader to a key
      // they never set - and `llm4s.embeddings.model` being absent is exactly why the legacy
      // path was taken.
      val error = load("""llm4s { embeddings { provider = "nosuch" } }""").left.value.message

      error should include("(from llm4s.embeddings.provider)")
      (error should not).include("llm4s.embeddings.model")
    }
  }

  "the defaults that used to be duplicated" should {

    "apply with no section present at all" in {
      // An absent `llm4s.embeddings.<id>` must read as an empty section, not as a
      // config failure - a provider whose defaults cover everything needs no section.
      val (provider, config) = load("""llm4s { embeddings { model = "ollama/nomic-embed-text" } }""").value

      provider shouldBe "ollama"
      config.model shouldBe "nomic-embed-text"
    }

    "come from the descriptor rather than reference.conf" in {
      // Ollama's base URL and model were stated twice - in reference.conf and as
      // `DefaultOllamaEmbeddingBaseUrl` in the loader - with nothing keeping them in step.
      // Neither the section nor reference.conf is consulted here.
      val (_, config) = load("""llm4s { embeddings { model = "ollama/nomic-embed-text" } }""").value

      config.baseUrl shouldBe "http://localhost:11434"
      config.apiKey shouldBe "not-required"
    }

    "still let a section override them" in {
      val (_, config) = load(
        """llm4s {
          |  embeddings {
          |    model = "ollama/mxbai-embed-large"
          |    ollama { baseUrl = "http://gpu-box:11434" }
          |  }
          |}""".stripMargin
      ).value

      config.baseUrl shouldBe "http://gpu-box:11434"
      config.model shouldBe "mxbai-embed-large"
    }
  }
}
