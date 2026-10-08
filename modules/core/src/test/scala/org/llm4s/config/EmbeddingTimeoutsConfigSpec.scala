package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, ProviderTimeouts }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, EmbeddingProviderSection, ProviderRegistry }
import org.llm4s.testutil.FixtureEmbeddingProvider
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of an embedding section (#712): the same block a chat section has, read from
 * `llm4s.embeddings.<id>`, checked, and carried on the [[EmbeddingProviderConfig]] a provider is built from.
 */
class EmbeddingTimeoutsConfigSpec extends AnyWordSpec with Matchers with EitherValues {

  private def load(section: String): Either[org.llm4s.error.LLMError, EmbeddingProviderConfig] =
    EmbeddingsConfigLoader
      .loadProvider(
        ConfigSource.string(
          s"""llm4s.embeddings {
             |  model = "fixtureembedding/fixture-embed-large"
             |  fixtureembedding {
             |    apiKey = "vk-test-key"
             |$section
             |  }
             |}
             |""".stripMargin
        )
      )
      .map(_._2)

  private def refused(section: String): String =
    load(section) match
      case Left(error: ConfigurationError) => error.message
      case Left(other)                     => fail(s"expected a ConfigurationError, got $other")
      case Right(config)                   => fail(s"expected the section to be refused, got $config")

  "An embedding section" should {

    "leave the timeouts unset without a timeouts block" in {
      load("").value.timeouts shouldBe ProviderTimeouts.default
    }

    "carry the request timeout onto the provider config" in {
      val config = load("timeouts { request = 3m }").value
      config.timeouts.request shouldBe Some(3.minutes)
      config.model shouldBe "fixture-embed-large"
    }

    "carry a stream timeout too, which an embedding call has no use for" in {
      load("timeouts { request = 1m, stream = 9m }").value.timeouts shouldBe
        ProviderTimeouts(Some(1.minute), Some(9.minutes))
    }

    "refuse a non-positive timeout, naming the key by its full path" in {
      val message = refused("timeouts { request = 0s }")
      message should include("llm4s.embeddings.fixtureembedding.timeouts.request")
      message should include("greater than zero")
    }

    "refuse a misspelled key instead of silently ignoring it" in {
      val message = refused("timeouts { reqest = 3m }")
      message should include("reqest")
    }

    "refuse a value that is not a duration, naming the key" in {
      refused("timeouts { request = soon }") should include("timeouts.request")
    }

    "keep reading its other keys with a timeouts block present" in {
      val config = load("""baseUrl = "https://example.invalid/v1"
                          |timeouts { request = 45s }""".stripMargin).value
      config.baseUrl shouldBe "https://example.invalid/v1"
      config.apiKey shouldBe "vk-test-key"
      config.timeouts.request shouldBe Some(45.seconds)
    }
  }

  "The loader" should {
    "apply the section's timeouts even when a descriptor overrides buildConfig and drops them" in {
      // A descriptor that builds its own config, as a provider with unusual config may, and so never
      // mentions timeouts: the loader must still apply the section's.
      object Overriding extends EmbeddingProviderDescriptor:
        val id                  = FixtureEmbeddingProvider.id
        override val configSpec = FixtureEmbeddingProvider.configSpec
        override def buildConfig(section: EmbeddingProviderSection, modelOverride: Option[String]) =
          Right(EmbeddingProviderConfig("http://x", modelOverride.getOrElse("m"), "k"))
        def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] = FixtureEmbeddingProvider.build(config)

      given ProviderRegistry = ProviderRegistry.ofEmbeddings(Overriding)
      val result = EmbeddingsConfigLoader.loadProvider(
        ConfigSource.string(
          """llm4s.embeddings {
            |  model = "fixtureembedding/m"
            |  fixtureembedding { apiKey = "k", timeouts { request = 3m } }
            |}
            |""".stripMargin
        )
      )
      result.value._2.timeouts.request shouldBe Some(3.minutes)
    }
  }

  "EmbeddingProviderConfig" should {
    "default to no timeouts, so a config built by hand behaves as before" in {
      EmbeddingProviderConfig("http://x", "m", "k").timeouts shouldBe ProviderTimeouts.default
    }
  }
}
