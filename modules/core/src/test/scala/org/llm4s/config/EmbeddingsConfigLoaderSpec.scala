package org.llm4s.config

import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * Comprehensive unit tests for EmbeddingsConfigLoader validation and parsing.
 *
 * These tests use ConfigSource.string() to provide deterministic HOCON input
 * without relying on environment variables or external configuration files.
 *
 * The OpenAI embedding cases moved to `llm4s-openai`'s `OpenAIEmbeddingsConfigLoaderSpec`
 * with the provider (#1132); the provider-neutral cases use Voyage.
 */
class EmbeddingsConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  // --------------------------------------------------------------------------
  // Unified EMBEDDING_MODEL Format Tests (provider/model)
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader with unified model format" should {

    "successfully load Voyage embeddings via provider/model format" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "voyage/voyage-3-large"
          |    voyage {
          |      apiKey = "vk-test-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-3-large"
      cfg.apiKey shouldBe "vk-test-key"
      cfg.baseUrl shouldBe "https://api.voyageai.com/v1"
    }

    "fail with clear error for invalid model format (missing slash)" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "text-embedding-3-small"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Invalid embedding model format")
      error.message should include("provider/model")
    }

    "fail with clear error for unknown embedding provider" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "unknownprovider/some-model"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      // The registry's own message: it names the registered providers and the scan that
      // found them, so "never added the dependency" is distinguishable from "module failed".
      error.message should include("is not registered")
      error.message should include("Registered embedding providers")
      error.message should include("unknownprovider")
    }
  }

  // --------------------------------------------------------------------------
  // Legacy EMBEDDING_PROVIDER Format Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader with legacy provider format" should {

    "successfully load Voyage embeddings via legacy provider setting" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "voyage"
          |    voyage {
          |      apiKey = "vk-legacy"
          |      model = "voyage-2"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-2"
      cfg.apiKey shouldBe "vk-legacy"
    }

    "fail with clear error for unknown legacy provider" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "cohere"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      // The registry's own message: it names the registered providers and the scan that
      // found them, so "never added the dependency" is distinguishable from "module failed".
      error.message should include("is not registered")
      error.message should include("Registered embedding providers")
      error.message should include("cohere")
    }

    "fail with clear error when neither model nor provider is set" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing embedding config")
      error.message should include("EMBEDDING_MODEL")
      error.message should include("EMBEDDING_PROVIDER")
    }
  }

  // --------------------------------------------------------------------------
  // Missing Required Fields Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader validation" should {

    "fail with clear error when Voyage API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "voyage/voyage-3"
          |    voyage {
          |      baseUrl = "https://api.voyageai.com/v1"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing voyage embeddings apiKey")
      error.message should include("llm4s.embeddings.voyage.apiKey")
      error.message should include("VOYAGE_API_KEY")
      error.message should include("VOYAGE_API_KEY")
    }

    "fail with clear error when Voyage model is missing in legacy mode" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "voyage"
          |    voyage {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing voyage embeddings model")
      error.message should include("VOYAGE_EMBEDDING_MODEL")
    }
  }

  // --------------------------------------------------------------------------
  // Default Values Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader default values" should {

    "use default baseUrl for Voyage when not specified" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "voyage/voyage-3"
          |    voyage {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.baseUrl shouldBe "https://api.voyageai.com/v1"
    }
  }

  // --------------------------------------------------------------------------
  // Local Embedding Models Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader.loadLocalModels" should {

    "successfully load local models configuration" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    localModels {
          |      imageModel = "clip-ViT-B-32"
          |      audioModel = "whisper-large"
          |      videoModel = "video-clip-v1"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadLocalModels(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val models = result.value
      models.imageModel shouldBe "clip-ViT-B-32"
      models.audioModel shouldBe "whisper-large"
      models.videoModel shouldBe "video-clip-v1"
    }

    "fail with clear error when localModels section is missing required fields" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    localModels {
          |      imageModel = "clip"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadLocalModels(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Failed to load llm4s embeddings localModels via PureConfig")
    }

    "fail with clear error when localModels section is completely missing" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadLocalModels(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Failed to load llm4s embeddings localModels via PureConfig")
    }
  }

  // --------------------------------------------------------------------------
  // Unified vs Legacy Precedence Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader precedence" should {

    "prefer unified model format over legacy provider when both are set" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "voyage/voyage-3-large"
          |    provider = "openai"
          |    voyage {
          |      apiKey = "vk-unified"
          |    }
          |    openai {
          |      model = "text-embedding-ada-002"
          |    }
          |  }
          |  openai {
          |    apiKey = "sk-legacy"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      // Unified format should take precedence
      provider shouldBe "voyage"
      cfg.model shouldBe "voyage-3-large"
    }
  }

  // --------------------------------------------------------------------------
  // Malformed Configuration Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader with malformed config" should {

    "fail gracefully when llm4s root is missing" in {
      val hocon =
        """
          |someOtherConfig {
          |  value = "test"
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Failed to load llm4s embeddings config via PureConfig")
    }

    "fail gracefully when embeddings section has wrong structure" in {
      val hocon =
        """
          |llm4s {
          |  embeddings = "invalid-scalar-value"
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Failed to load llm4s embeddings config via PureConfig")
    }

    "preserve error context from PureConfig when parsing fails" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    openai = "should-be-an-object"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      // Parsing fails at PureConfig level because openai should be an object
      result.left.value.message should (include("PureConfig")
        .or(include("OBJECT"))
        .or(include("Missing embedding config")))
    }
  }

  // --------------------------------------------------------------------------
  // Whitespace Handling Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader whitespace handling" should {

    "trim whitespace from model specification" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "  voyage/voyage-3  "
          |    voyage {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.model shouldBe "voyage-3"
    }

    "trim whitespace from API keys" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "voyage/voyage-3"
          |    voyage {
          |      apiKey = "  vk-trimmed  "
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.apiKey shouldBe "vk-trimmed"
    }

    "treat empty model string as missing" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "   "
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      result.left.value.message should include("Missing embedding config")
    }
  }
}
