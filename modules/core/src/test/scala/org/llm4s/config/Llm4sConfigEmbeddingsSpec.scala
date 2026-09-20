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

    // --- Unified EMBEDDING_MODEL format tests ---

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

    "load Voyage embeddings via unified EMBEDDING_MODEL format" in {
      val props = Map(
        "llm4s.embeddings.model"         -> "voyage/voyage-3",
        "llm4s.embeddings.voyage.apiKey" -> "vk-test"
        // No explicit baseUrl - should use default
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "voyage"
        cfg.model shouldBe "voyage-3"
        cfg.baseUrl shouldBe "https://api.voyageai.com/v1" // Default base URL
        cfg.apiKey shouldBe "vk-test"
      }
    }

    "load Ollama embeddings via unified EMBEDDING_MODEL format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "ollama/mxbai-embed-large"
        // No explicit baseUrl - should use default
        // No API key needed for Ollama
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "ollama"
        cfg.model shouldBe "mxbai-embed-large"
        cfg.baseUrl shouldBe "http://localhost:11434" // Default base URL
        cfg.apiKey shouldBe "not-required"
      }
    }

    "prefer unified model format over legacy provider" in {
      val props = Map(
        "llm4s.embeddings.model"    -> "openai/text-embedding-3-large", // Takes precedence
        "llm4s.embeddings.provider" -> "voyage",                        // Should be ignored
        "llm4s.openai.apiKey"       -> "sk-test"
      )
      withProps(props) {
        val (provider, cfg): (String, EmbeddingProviderConfig) =
          Llm4sConfig.embeddings().fold(err => fail(err.toString), identity)

        provider shouldBe "openai" // Unified format wins
        cfg.model shouldBe "text-embedding-3-large"
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

    "reject invalid embedding model format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "invalid-format-no-slash"
      )
      withProps(props) {
        val result = Llm4sConfig.embeddings()
        result.isLeft shouldBe true
        result.left.getOrElse(fail()).message should include("Invalid embedding model format")
      }
    }

    "reject unknown embedding provider in unified format" in {
      val props = Map(
        "llm4s.embeddings.model" -> "unknown-provider/some-model"
      )
      withProps(props) {
        val result = Llm4sConfig.embeddings()
        result.isLeft shouldBe true
        result.left.getOrElse(fail()).message should include("is not registered")
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
