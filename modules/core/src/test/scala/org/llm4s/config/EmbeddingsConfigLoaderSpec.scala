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
 * with the provider (#1132). The provider-neutral cases used Voyage until it moved to
 * `llm4s-voyage` too; they now use core's test `FixtureEmbeddingProvider`, and their Voyage
 * versions are in that module's `VoyageEmbeddingsSpec`.
 */
class EmbeddingsConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  // --------------------------------------------------------------------------
  // Unified EMBEDDING_MODEL Format Tests (provider/model)
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader with unified model format" should {

    "successfully load embeddings via provider/model format" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "fixtureembedding/fixture-embed-large"
          |    fixtureembedding {
          |      apiKey = "vk-test-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "fixtureembedding"
      cfg.model shouldBe "fixture-embed-large"
      cfg.apiKey shouldBe "vk-test-key"
      cfg.baseUrl shouldBe "https://fixtureembedding.invalid/v1"
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

    "successfully load embeddings via legacy provider setting" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "fixtureembedding"
          |    fixtureembedding {
          |      apiKey = "vk-legacy"
          |      model = "fixture-embed-large"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "fixtureembedding"
      cfg.model shouldBe "fixture-embed-large"
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

    "fail with clear error when the API key is missing" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "fixtureembedding/fixture-embed-small"
          |    fixtureembedding {
          |      baseUrl = "https://fixtureembedding.invalid/v1"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing fixtureembedding embeddings apiKey")
      error.message should include("llm4s.embeddings.fixtureembedding.apiKey")
      error.message should include("FIXTURE_EMBEDDING_API_KEY")
      error.message should include("FIXTURE_EMBEDDING_API_KEY")
    }

    "fail with clear error when the model is missing in legacy mode" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "fixtureembedding"
          |    fixtureembedding {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing fixtureembedding embeddings model")
      error.message should include("FIXTURE_EMBEDDING_MODEL")
    }
  }

  // --------------------------------------------------------------------------
  // Default Values Tests
  // --------------------------------------------------------------------------

  "EmbeddingsConfigLoader default values" should {

    "use the provider's default baseUrl when not specified" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "fixtureembedding/fixture-embed-small"
          |    fixtureembedding {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.baseUrl shouldBe "https://fixtureembedding.invalid/v1"
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
          |    model = "fixtureembedding/fixture-embed-large"
          |    provider = "openai"
          |    fixtureembedding {
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
      provider shouldBe "fixtureembedding"
      cfg.model shouldBe "fixture-embed-large"
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
          |    model = "  fixtureembedding/fixture-embed-small  "
          |    fixtureembedding {
          |      apiKey = "vk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.model shouldBe "fixture-embed-small"
    }

    "trim whitespace from API keys" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    model = "fixtureembedding/fixture-embed-small"
          |    fixtureembedding {
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
