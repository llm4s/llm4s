package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.llmconnect.config.AnthropicConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Named `anthropic` provider sections, from validation to a loaded config.
 *
 * Moved from core's `NamedProviderConfigValidatorSpec` with the provider (#1132); core's
 * config-loading specs that used Anthropic only as a convenient API-key provider now use
 * the test fixture `FixtureChatProvider` instead. These resolve `anthropic` through
 * `ProviderRegistry.default`, so they also prove this module's services entry is found.
 */
class AnthropicNamedProviderSpec extends AnyWordSpec with Matchers:

  private def validate(providerName: String, section: RawNamedProviderSection) =
    NamedProviderConfigValidator.validate(ProviderName(providerName), section)

  "NamedProviderConfigValidator" should {

    "validate and normalize an Anthropic named provider section" in {
      validate(
        "anthropic-main",
        RawNamedProviderSection(
          provider = Some("anthropic"),
          model = Some("claude-sonnet-4-20250514"),
          baseUrl = Some("https://api.anthropic.com"),
          apiKey = Some("sk-ant-test"),
        )
      ) match
        case Right(cfg) =>
          cfg.provider shouldBe ProviderId("anthropic")
          cfg.model.asString shouldBe "claude-sonnet-4-20250514"
          cfg.apiKey.map(_.asKey) shouldBe Some("sk-ant-test")
        case Left(err) =>
          fail(s"Expected Anthropic NamedProviderConfig, got error: ${err.message}")
    }

    "fail clearly when an Anthropic section omits the apiKey" in {
      validate(
        "broken-anthropic",
        RawNamedProviderSection(
          provider = Some("anthropic"),
          model = Some("claude-sonnet-4-5"),
          baseUrl = None,
          apiKey = None,
        )
      ) match
        case Left(err) =>
          err.message should include("Provider 'broken-anthropic' (provider = anthropic) is missing required fields")
          err.message should include(
            "- apiKey: set ANTHROPIC_API_KEY, or set apiKey under llm4s.providers.broken-anthropic"
          )
        case Right(cfg) =>
          fail(s"Expected a missing-apiKey failure, got config: $cfg")
    }

    "read webSearch from an anthropic section, off unless set (#1216)" in {
      def load(name: String, body: String) =
        Llm4sConfig.provider(ConfigSource.string(s"llm4s.providers.$name { $body }"), name)
      val anthropic = """provider = "anthropic", model = "claude-sonnet-4-5", apiKey = "k""""

      load("a-off", anthropic) should matchPattern { case Right(c: AnthropicConfig) if !c.webSearch => }
      load("a-on", s"$anthropic, webSearch = true") should matchPattern {
        case Right(c: AnthropicConfig) if c.webSearch =>
      }
      load("a-str", s"""$anthropic, webSearch = "TRUE" """) should matchPattern {
        case Right(c: AnthropicConfig) if c.webSearch =>
      }
    }

    "refuse a webSearch value that is not true or false" in {
      val hocon =
        """llm4s.providers.a-bad { provider = "anthropic", model = "m", apiKey = "k", webSearch = "yes" }"""
      Llm4sConfig.provider(ConfigSource.string(hocon), "a-bad").left.map(_.message) match
        case Left(message) => message should include("webSearch must be true or false, got 'yes'")
        case Right(cfg)    => fail(s"expected an error, got $cfg")
    }
  }

  "Llm4sConfig" should {

    "load an Anthropic named provider end to end, defaulting the base URL" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    anthropic-main {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |      apiKey = "anthropic-key"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "anthropic-main") match
        case Right(anthropic: AnthropicConfig) =>
          anthropic.model shouldBe "claude-sonnet-4-5"
          anthropic.apiKey shouldBe "anthropic-key"
          anthropic.baseUrl shouldBe AnthropicConfig.DEFAULT_BASE_URL
        case other =>
          fail(s"Expected AnthropicConfig, got $other")
    }

    "keep a configured base URL" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "anthropic-proxy"
          |    anthropic-proxy {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |      apiKey = "anthropic-key"
          |      baseUrl = "https://anthropic-proxy.example"
          |    }
          |  }
          |}
          |""".stripMargin

      Llm4sConfig.provider(ConfigSource.string(hocon), "anthropic-proxy") match
        case Right(anthropic: AnthropicConfig) => anthropic.baseUrl shouldBe "https://anthropic-proxy.example"
        case other                             => fail(s"Expected AnthropicConfig, got $other")
    }
  }
