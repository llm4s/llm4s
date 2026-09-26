package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
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
