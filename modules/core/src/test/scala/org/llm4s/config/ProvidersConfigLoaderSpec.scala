package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

class ProvidersConfigLoaderSpec extends AnyWordSpec with Matchers:

  "ProvidersConfigLoader" should {

    "load and validate the full providers config from llm4s.providers" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      baseUrl = "https://api.openai.com/v1"
          |      apiKey = "sk-openai"
          |      organization = "org-demo"
          |    }
          |    anthropic-main {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |      baseUrl = "https://api.anthropic.com"
          |      apiKey = "anthropic-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider.map(_.asName) shouldBe Some("openai-main")
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main", "anthropic-main")

          val openai = cfg.namedProviders(ProviderName("openai-main"))
          openai.provider shouldBe ProviderId("openai")
          openai.model.asString shouldBe "gpt-4o-mini"
          openai.baseUrl.map(_.asUrl) shouldBe Some("https://api.openai.com/v1")
          openai.apiKey.map(_.asKey) shouldBe Some("sk-openai")
          openai.organization shouldBe Some("org-demo")

          val anthropic = cfg.namedProviders(ProviderName("anthropic-main"))
          anthropic.provider shouldBe ProviderId("anthropic")
          anthropic.model.asString shouldBe "claude-sonnet-4-5"
          anthropic.baseUrl.map(_.asUrl) shouldBe Some("https://api.anthropic.com")
          anthropic.apiKey.map(_.asKey) shouldBe Some("anthropic-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "make the selected provider resolvable from the loaded providers config" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "anthropic-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "sk-openai"
          |    }
          |    anthropic-main {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |      apiKey = "anthropic-key"
          |      baseUrl = "https://api.anthropic.com"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          val selectedProviderName =
            cfg.selectedProvider.getOrElse(fail("Expected selected provider to be defined"))

          val selectedProvider =
            cfg.namedProviders
              .get(selectedProviderName)
              .getOrElse(
                fail(s"Expected selected provider '${selectedProviderName.asName}' to exist in namedProviders")
              )

          selectedProviderName.asName shouldBe "anthropic-main"
          selectedProvider.provider shouldBe ProviderId("anthropic")
          selectedProvider.model.asString shouldBe "claude-sonnet-4-5"
          selectedProvider.apiKey.map(_.asKey) shouldBe Some("anthropic-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "fail clearly when the configured selected provider does not exist" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "missing-provider"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "sk-openai"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Left(err) =>
          err.message should include("Configured provider 'missing-provider' was not found")
        case Right(cfg) =>
          fail(s"Expected missing selected provider error, got config: $cfg")
    }

    "allow providers config to load when no selected provider is configured" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "sk-openai"
          |    }
          |    anthropic-main {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |      apiKey = "anthropic-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider shouldBe None
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main", "anthropic-main")
        case Left(err) =>
          fail(s"Expected ProvidersConfig without selected provider, got error: ${err.message}")
    }

    "fail the whole providers config when one named provider is invalid" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      apiKey = "sk-openai"
          |    }
          |    broken-anthropic {
          |      provider = "anthropic"
          |      model = "claude-sonnet-4-5"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Left(err) =>
          err.message should include("Provider 'broken-anthropic' (provider = anthropic) is missing required fields")
          err.message should include("- apiKey: set it in llm4s.conf under providers.broken-anthropic.apiKey")
        case Right(cfg) =>
          fail(s"Expected invalid named provider to fail whole providers config, got config: $cfg")
    }
  }
