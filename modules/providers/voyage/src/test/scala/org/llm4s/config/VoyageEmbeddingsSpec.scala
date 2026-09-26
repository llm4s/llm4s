package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.provider.VoyageAIEmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Voyage as an embedding provider: configuration, defaults, client construction and model
 * dimensions.
 *
 * Gathered from the core specs that used Voyage as their embedding provider -
 * `EmbeddingsConfigLoaderSpec`, `Llm4sConfigEmbeddingsSpec`, `EmbeddingsConfigSpec`,
 * `EmbeddingClientFactorySpec` and `ModelDimensionRegistrySpec` - when the provider moved to
 * `llm4s-voyage` (#1132). Those specs now use core's test `FixtureEmbeddingProvider` for the
 * provider-neutral behaviour; the cases here are their Voyage versions, kept so Voyage's own
 * facts (its default base URL, its env-var names, its alias and its dimensions) stay tested
 * where Voyage lives.
 *
 * Nothing here registers Voyage by hand: every case resolves it through
 * `ProviderRegistry.default`, so these also prove that this module's `META-INF/services`
 * entry is found and its `reference.conf` block is merged.
 */
class VoyageEmbeddingsSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      ConfigFactory.invalidateCaches()
      f
    } finally {
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
      ConfigFactory.invalidateCaches()
    }
  }

  private def load(hocon: String): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

  "EmbeddingsConfigLoader" should {

    "successfully load Voyage embeddings via provider/model format" in {
      val (provider, cfg) =
        load("""llm4s { embeddings { model = "voyage/voyage-3-large", voyage { apiKey = "vk-test-key" } } }""").value

      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-3-large"
      cfg.apiKey shouldBe "vk-test-key"
      cfg.baseUrl shouldBe "https://api.voyageai.com/v1"
    }

    "successfully load Voyage embeddings via legacy provider setting" in {
      val (provider, cfg) =
        load(
          """llm4s { embeddings { provider = "voyage", voyage { apiKey = "vk-legacy", model = "voyage-2" } } }"""
        ).value

      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-2"
      cfg.apiKey shouldBe "vk-legacy"
    }

    "fail with clear error when Voyage API key is missing" in {
      val error = load(
        """llm4s { embeddings { model = "voyage/voyage-3", voyage { baseUrl = "https://api.voyageai.com/v1" } } }"""
      ).left.value

      error.message should include("Missing voyage embeddings apiKey")
      error.message should include("llm4s.embeddings.voyage.apiKey")
      error.message should include(VoyageConfigKeys.VOYAGE_API_KEY)
    }

    "fail with clear error when Voyage model is missing in legacy mode" in {
      val error = load("""llm4s { embeddings { provider = "voyage", voyage { apiKey = "vk-test" } } }""").left.value

      error.message should include("Missing voyage embeddings model")
      error.message should include(VoyageConfigKeys.VOYAGE_EMBEDDING_MODEL)
    }

    "prefer unified model format over legacy provider when both are set" in {
      val (provider, cfg) = load(
        """llm4s { embeddings { model = "voyage/voyage-3-large", provider = "openai", voyage { apiKey = "vk-unified" } } }"""
      ).value

      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-3-large"
    }
  }

  "Llm4sConfig.embeddings" should {

    "load VoyageAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "voyage",
        "llm4s.embeddings.voyage.baseUrl" -> "https://api.voyage.ai",
        "llm4s.embeddings.voyage.model"   -> "voyage-3-large",
        "llm4s.embeddings.voyage.apiKey"  -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "voyage"
        cfg.baseUrl shouldBe "https://api.voyage.ai"
        cfg.model shouldBe "voyage-3-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }

    "load Voyage embeddings via unified EMBEDDING_MODEL format, defaulting the base URL" in {
      val props = Map(
        "llm4s.embeddings.model"         -> "voyage/voyage-3",
        "llm4s.embeddings.voyage.apiKey" -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg) = Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "voyage"
        cfg.model shouldBe "voyage-3"
        cfg.baseUrl shouldBe "https://api.voyageai.com/v1"
        cfg.apiKey shouldBe "vk-test"
      }
    }

    "report the dimensions of the configured Voyage model" in {
      withProps(Map("llm4s.embeddings.model" -> "voyage/voyage-3-lite", "llm4s.embeddings.voyage.apiKey" -> "vk")) {
        val settings = Llm4sConfig.textEmbeddingModel().value
        settings.provider shouldBe "voyage"
        settings.modelName shouldBe "voyage-3-lite"
        settings.dimensions shouldBe 512
      }
    }
  }

  "EmbeddingClient.from" should {

    "build a Voyage client without a network call" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "https://api.voyage.ai", model = "voyage-3", apiKey = "vk-test")
      EmbeddingClient.from("voyage", cfg).isRight shouldBe true
    }

    "fold the voyageai alias onto voyage" in {
      val cfg = EmbeddingProviderConfig(baseUrl = "https://api.voyage.ai", model = "voyage-3", apiKey = "vk-test")
      EmbeddingClient.from("voyageai", cfg).isRight shouldBe true
    }
  }

  "ModelDimensionRegistry" should {

    "know the documented Voyage models" in {
      ModelDimensionRegistry.getDimension("voyage", "voyage-3").value shouldBe 1024
    }

    "give voyage-3-large its default size, not 1536" in {
      ModelDimensionRegistry.getDimension("voyage", "voyage-3-large").value shouldBe 1024
    }

    "resolve the voyageai alias and ignore the provider's case" in {
      ModelDimensionRegistry.getDimension("voyageai", "voyage-3").value shouldBe 1024
      ModelDimensionRegistry.getDimension("Voyage", "voyage-3").value shouldBe 1024
    }

    "name the model and provider when Voyage does not declare the model" in {
      ModelDimensionRegistry.getDimension("voyage", "gpt-4o").left.value.formatted should include(
        "Unknown model 'gpt-4o' for provider 'voyage'"
      )
    }

    "declare dimensions for every Voyage model it lists" in {
      VoyageAIEmbeddingProvider.modelDimensions should not be empty
      VoyageAIEmbeddingProvider.modelDimensions.values.foreach(_ should be > 0)
      VoyageAIEmbeddingProvider.configSpec.defaultModel.foreach { model =>
        VoyageAIEmbeddingProvider.dimensionsOf(model) shouldBe defined
      }
    }
  }
}
