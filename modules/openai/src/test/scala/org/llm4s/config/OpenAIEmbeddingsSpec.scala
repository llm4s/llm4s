package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.provider.OpenAIEmbeddingProvider
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * OpenAI as an embedding provider: configuration, the key it shares with the chat client,
 * client construction and model dimensions.
 *
 * Gathered from the core specs that exercised it - `Llm4sConfigEmbeddingsSpec`,
 * `EmbeddingsConfigSpec`, `Llm4sConfigTextModelSpec`, `EmbeddingProviderSpiSpec`,
 * `EmbeddingClientFactorySpec` and `ModelDimensionRegistrySpec` - when the provider moved
 * to `llm4s-openai` (#1132). Core keeps the provider-neutral cases, using Voyage or a
 * fixture descriptor.
 *
 * Nothing here registers OpenAI by hand: every case resolves it through
 * `ProviderRegistry.default`, so these also prove that this module's
 * `META-INF/services` entry is found and its `reference.conf` block is merged.
 */
class OpenAIEmbeddingsSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

  private def load(hocon: String) =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

  "Llm4sConfig.embeddings" should {

    "load OpenAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "openai",
        "llm4s.embeddings.openai.baseUrl" -> "https://example.com/v1",
        "llm4s.embeddings.openai.model"   -> "text-embedding-3-small",
        // API key is shared with core OpenAI config keys
        "llm4s.openai.apiKey" -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "openai"
        cfg.baseUrl shouldBe "https://example.com/v1"
        cfg.model shouldBe "text-embedding-3-small"
        cfg.apiKey shouldBe "sk-test"
      }
    }

    "load OpenAI embeddings via unified EMBEDDING_MODEL format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "openai/text-embedding-3-small",
        // No explicit baseUrl - should use default
        "llm4s.openai.apiKey" -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "openai"
        cfg.model shouldBe "text-embedding-3-small"
        cfg.baseUrl shouldBe "https://api.openai.com/v1" // Default base URL
        cfg.apiKey shouldBe "sk-test"
      }
    }

    "allow custom base URL with unified format" in {
      val props = Map(
        "llm4s.embeddings.model"          -> "openai/text-embedding-3-small",
        "llm4s.embeddings.openai.baseUrl" -> "https://custom.openai.com/v1",
        "llm4s.openai.apiKey"             -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "openai"
        cfg.model shouldBe "text-embedding-3-small"
        cfg.baseUrl shouldBe "https://custom.openai.com/v1" // Custom base URL
      }
    }

    "load OpenAI embeddings config via llm4s.* (from EmbeddingsConfigSpec)" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "openai",
        "llm4s.embeddings.openai.baseUrl" -> "https://example.com/v1",
        "llm4s.embeddings.openai.model"   -> "text-embedding-3-small",
        // API key is shared with core OpenAI config keys
        "llm4s.openai.apiKey" -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)
        provider shouldBe "openai"
        cfg.baseUrl shouldBe "https://example.com/v1"
        cfg.model shouldBe "text-embedding-3-small"
        cfg.apiKey shouldBe "sk-test"
      }
    }
  }

  "Llm4sConfig.textEmbeddingModel" should {

    "return OpenAI text model settings with dimensions from the registry" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "openai",
        "llm4s.embeddings.openai.baseUrl" -> "https://example.com/v1",
        "llm4s.embeddings.openai.model"   -> "text-embedding-3-small",
        // API key is shared with core OpenAI config keys
        "llm4s.openai.apiKey" -> "sk-test"
      )

      withProps(props) {
        val pure = Llm4sConfig.textEmbeddingModel().fold(err => fail(err.toString), identity)

        pure.provider shouldBe "openai"
        pure.modelName shouldBe "text-embedding-3-small"

        // And explicitly via the registry as an extra safety check
        val expectedDims =
          ModelDimensionRegistry.getDimension("openai", pure.modelName).fold(err => fail(err.formatted), identity)
        pure.dimensions shouldBe expectedDims
      }
    }
  }

  "a credential the provider keeps outside its own section" should {

    "be resolved from the path its descriptor declares" in {
      // OpenAI's shape: no apiKey under llm4s.embeddings.openai, read from the chat key.
      val (_, config) = load(
        """llm4s {
          |  openai { apiKey = "sk-shared" }
          |  embeddings { model = "openai/text-embedding-3-small" }
          |}""".stripMargin
      ).value

      config.apiKey shouldBe "sk-shared"
    }

    "lose to an explicit key in the provider's own section" in {
      val (_, config) = load(
        """llm4s {
          |  openai { apiKey = "sk-shared" }
          |  embeddings {
          |    model = "openai/text-embedding-3-small"
          |    openai { apiKey = "sk-embeddings-only" }
          |  }
          |}""".stripMargin
      ).value

      config.apiKey shouldBe "sk-embeddings-only"
    }

    "be reported against the path it is actually set at" in {
      val error = load("""llm4s { embeddings { model = "openai/text-embedding-3-small" } }""").left.value.message

      error should include("llm4s.openai.apiKey")
      // Not the embeddings section, where setting it would do nothing.
      (error should not).include("llm4s.embeddings.openai.apiKey")
    }
  }

  "EmbeddingClient.from(provider,cfg)" should {

    "build client for openai without throwing" in {
      val cfg = EmbeddingProviderConfig(
        baseUrl = "https://api.openai.com/v1",
        model = "text-embedding-3-small",
        apiKey = "sk-test"
      )
      val res = EmbeddingClient.from("openai", cfg)
      res.isRight shouldBe true
    }
  }

  "the OpenAI embedding provider" should {

    "declare the documented model dimensions" in {
      given ProviderRegistry = ProviderRegistry.default

      ModelDimensionRegistry.getDimension("openai", "text-embedding-3-small").value shouldBe 1536
      ModelDimensionRegistry.getDimension("openai", "text-embedding-3-large").value shouldBe 3072
      ModelDimensionRegistry.getDimension("OpenAI", "text-embedding-3-small").value shouldBe 1536
      OpenAIEmbeddingProvider.modelDimensions should not be empty
    }

    "name the model when asked for one it does not declare" in {
      given ProviderRegistry = ProviderRegistry.default

      ModelDimensionRegistry.getDimension("openai", "gpt-4o").left.value.formatted should include(
        "Unknown model 'gpt-4o' for provider 'openai'"
      )
    }
  }
}
