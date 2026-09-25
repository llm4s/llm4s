package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingConfigSpec, EmbeddingProviderDescriptor, ProviderRegistry }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.textEmbeddingModel under controlled configuration.
 */
class Llm4sConfigTextModelSpec extends AnyWordSpec with Matchers with EitherValues {

  private def withProps(props: Map[String, String])(f: => Unit): Unit = {
    val originals = props.keys.map(k => k -> Option(System.getProperty(k))).toMap
    try {
      props.foreach { case (k, v) => System.setProperty(k, v) }
      // Ensure Typesafe Config sees updated system properties.
      ConfigFactory.invalidateCaches()
      f
    } finally
      originals.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
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

    "resolve EMBEDDING_MODEL=ollama/nomic-embed-text, as the README documents" in {
      // Regression: the central dimension table had no ollama entry, so the documented
      // configuration failed here with "Unknown model 'nomic-embed-text'".
      withProps(Map("llm4s.embeddings.model" -> "ollama/nomic-embed-text")) {
        val settings = Llm4sConfig.textEmbeddingModel().value

        settings.provider shouldBe "ollama"
        settings.modelName shouldBe "nomic-embed-text"
        settings.dimensions shouldBe 768
      }
    }

    "resolve the provider and its dimensions through the caller's registry" in {
      val props = Map("llm4s.embeddings.model" -> "fixturelocal/fixture-encoder")

      withProps(props) {
        val settings = Llm4sConfig
          .textEmbeddingModel()(using ProviderRegistry.default.withEmbeddingProvider(FixtureEmbeddings))
          .value

        settings.provider shouldBe "fixturelocal"
        settings.modelName shouldBe "fixture-encoder"
        settings.dimensions shouldBe 512
      }
    }

    "report the provider as unregistered under a registry that lacks it" in {
      // The negative half of the pair above: the same configuration, resolved by a
      // registry the fixture was never added to.
      val props = Map("llm4s.embeddings.model" -> "fixturelocal/fixture-encoder")

      withProps(props) {
        val error = Llm4sConfig.textEmbeddingModel()(using ProviderRegistry.default).left.value.formatted

        error should include("fixturelocal")
        error should include("llm4s.embeddings.model")
      }
    }
  }

  /** An embedding provider registered only by the caller, as an application's would be. */
  private object FixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId = ProviderId("fixturelocal")

    override val modelDimensions: Map[String, Int] = Map("fixture-encoder" -> 512)

    override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
      requiresApiKey = false,
      defaultBaseUrl = Some("http://localhost:9999")
    )

    def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }
}
