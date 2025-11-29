package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.ModelDimensionRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.textEmbeddingModel under controlled configuration.
 */
class Llm4sConfigTextModelSpec extends AnyWordSpec with Matchers {

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
        val expectedDims = ModelDimensionRegistry.getDimension("openai", pure.modelName)
        pure.dimensions shouldBe expectedDims
      }
    }
  }
}
