package org.llm4s.config

import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.EitherValues

/**
 * OpenAI embeddings through `EmbeddingsConfigLoader`: the unified and legacy formats, the
 * default base URL, and the key that OpenAI embeddings share with the chat client.
 *
 * Moved from core's `EmbeddingsConfigLoaderSpec` with the provider (#1132). `openai`
 * resolves through `ProviderRegistry.default`, so these also prove this module's
 * services entry and `reference.conf` block are found.
 */
class OpenAIEmbeddingsConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  "EmbeddingsConfigLoader with OpenAI embeddings" should {

    "successfully load OpenAI embeddings via provider/model format" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  embeddings {
          |    model = "openai/text-embedding-3-small"
          |  }
          |  openai {
          |    apiKey = "sk-test-embedding"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "openai"
      cfg.model shouldBe "text-embedding-3-small"
      cfg.apiKey shouldBe "sk-test-embedding"
      cfg.baseUrl shouldBe "https://api.openai.com/v1"
    }

    "use custom baseUrl when provided for OpenAI embeddings" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  embeddings {
          |    model = "openai/text-embedding-3-large"
          |    openai {
          |      baseUrl = "https://custom-openai.proxy.com/v1"
          |    }
          |  }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (_, cfg) = result.value
      cfg.baseUrl shouldBe "https://custom-openai.proxy.com/v1"
    }

    "successfully load OpenAI embeddings via legacy provider setting" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  embeddings {
          |    provider = "openai"
          |    openai {
          |      model = "text-embedding-ada-002"
          |    }
          |  }
          |  openai {
          |    apiKey = "sk-legacy-test"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      val (provider, cfg) = result.value
      provider shouldBe "openai"
      cfg.model shouldBe "text-embedding-ada-002"
      cfg.apiKey shouldBe "sk-legacy-test"
    }

    "fail with clear error when OpenAI API key is missing for embeddings" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  embeddings {
          |    model = "openai/text-embedding-3-small"
          |  }
          |  openai {
          |    baseUrl = "https://api.openai.com/v1"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("apiKey")
      // OpenAI embeddings reuse the chat key, so the error must point there and not at
      // llm4s.embeddings.openai.apiKey, where the user would set it in vain.
      error.message should include("llm4s.openai.apiKey")
      error.message should include("OPENAI_API_KEY")
      error.message should include("OPENAI_API_KEY")
    }

    "fail with clear error when OpenAI embeddings model is missing in legacy mode" in {
      val hocon =
        """
          |llm4s {
          |  embeddings {
          |    provider = "openai"
          |    openai {
          |      baseUrl = "https://api.openai.com/v1"
          |    }
          |  }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isLeft shouldBe true
      val error = result.left.value
      error.message should include("Missing openai embeddings model")
      error.message should include("OPENAI_EMBEDDING_MODEL")
    }

    "use default baseUrl for OpenAI when not specified" in {
      val hocon =
        """
          |llm4s {
          |  llm { model = "openai/gpt-4o" }
          |  embeddings {
          |    model = "openai/text-embedding-3-small"
          |  }
          |  openai {
          |    apiKey = "sk-test"
          |  }
          |}
          |""".stripMargin

      val result = EmbeddingsConfigLoader.loadProvider(ConfigSource.string(hocon))

      result.isRight shouldBe true
      result.value._2.baseUrl shouldBe "https://api.openai.com/v1"
    }
  }
}
