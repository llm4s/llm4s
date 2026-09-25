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

    "resolve the provider through the caller's registry" in {
      // `local` is the one provider ModelDimensionRegistry knows and BuiltinProviders
      // does not, so the dimension lookup succeeds and the registry is the only thing
      // under test.
      val props = Map("llm4s.embeddings.model" -> "local/openclip-vit-b32")

      withProps(props) {
        val settings = Llm4sConfig
          .textEmbeddingModel()(using ProviderRegistry.default.withEmbeddingProvider(LocalFixtureEmbeddings))
          .value

        settings.provider shouldBe "local"
        settings.modelName shouldBe "openclip-vit-b32"
        settings.dimensions shouldBe 512
      }
    }

    "report the provider as unregistered under a registry that lacks it" in {
      // The negative half of the pair above: the same configuration, resolved by a
      // registry the fixture was never added to.
      val props = Map("llm4s.embeddings.model" -> "local/openclip-vit-b32")

      withProps(props) {
        val error = Llm4sConfig.textEmbeddingModel()(using ProviderRegistry.default).left.value.formatted

        error should include("local")
        error should include("llm4s.embeddings.model")
      }
    }
  }

  /** An embedding provider registered only by the caller, as an application's would be. */
  private object LocalFixtureEmbeddings extends EmbeddingProviderDescriptor {
    val id: ProviderId = ProviderId("local")

    override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
      requiresApiKey = false,
      defaultBaseUrl = Some("http://localhost:9999")
    )

    def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
      Left(org.llm4s.error.ConfigurationError("fixture builds no provider"))
  }
}
