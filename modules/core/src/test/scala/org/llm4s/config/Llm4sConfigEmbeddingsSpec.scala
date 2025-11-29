package org.llm4s.config

// scalafix:off DisableSyntax.NoConfigFactory
import com.typesafe.config.ConfigFactory
// scalafix:on DisableSyntax.NoConfigFactory
import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Sanity checks for Llm4sConfig.embeddings under controlled configuration.
 */
class Llm4sConfigEmbeddingsSpec extends AnyWordSpec with Matchers {

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

    "load VoyageAI embeddings config via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.provider"       -> "voyage",
        "llm4s.embeddings.voyage.baseUrl" -> "https://api.voyage.ai",
        "llm4s.embeddings.voyage.model"   -> "voyage-3-large",
        "llm4s.embeddings.voyage.apiKey"  -> "vk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "voyage"
        cfg.baseUrl shouldBe "https://api.voyage.ai"
        cfg.model shouldBe "voyage-3-large"
        cfg.apiKey shouldBe "vk-test"
      }
    }
  }

  "Llm4sConfig.localEmbeddingModels" should {
    "load local model names via llm4s.*" in {
      val props = Map(
        "llm4s.embeddings.localModels.imageModel" -> "custom-image-model",
        "llm4s.embeddings.localModels.audioModel" -> "custom-audio-model",
        "llm4s.embeddings.localModels.videoModel" -> "custom-video-model",
      )
      withProps(props) {
        val localModels: LocalEmbeddingModels =
          Llm4sConfig.localEmbeddingModels().fold(err => fail(err.toString), identity)

        localModels.imageModel shouldBe "custom-image-model"
        localModels.audioModel shouldBe "custom-audio-model"
        localModels.videoModel shouldBe "custom-video-model"
      }
    }

    "have defaults declared in reference.conf" in {
      val referenceConf = ConfigFactory.parseResources("reference.conf")
      val source        = ConfigSource.fromConfig(referenceConf)
      val localModels: LocalEmbeddingModels =
        org.llm4s.config.EmbeddingsConfigLoader.loadLocalModels(source).fold(err => fail(err.toString), identity)

      localModels.imageModel shouldBe "openclip-vit-b32"
      localModels.audioModel shouldBe "wav2vec2-base"
      localModels.videoModel shouldBe "timesformer-base"
    }
  }
}
