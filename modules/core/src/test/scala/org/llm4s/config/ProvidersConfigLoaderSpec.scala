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
          |    provider = "deepseek-primary"
          |    deepseek-primary {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      baseUrl = "https://api.deepseek.com/v1"
          |      apiKey = "sk-deepseek-primary"
          |      organization = "org-demo"
          |    }
          |    deepseek-main {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      baseUrl = "https://api.deepseek.com"
          |      apiKey = "deepseek-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider.map(_.asName) shouldBe Some("deepseek-primary")
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("deepseek-primary", "deepseek-main")

          val primary = cfg.namedProviders(ProviderName("deepseek-primary"))
          primary.provider shouldBe ProviderId("deepseek")
          primary.model.asString shouldBe "deepseek-chat"
          primary.baseUrl.map(_.asUrl) shouldBe Some("https://api.deepseek.com/v1")
          primary.apiKey.map(_.asKey) shouldBe Some("sk-deepseek-primary")
          primary.organization shouldBe Some("org-demo")

          val deepseek = cfg.namedProviders(ProviderName("deepseek-main"))
          deepseek.provider shouldBe ProviderId("deepseek")
          deepseek.model.asString shouldBe "deepseek-chat"
          deepseek.baseUrl.map(_.asUrl) shouldBe Some("https://api.deepseek.com")
          deepseek.apiKey.map(_.asKey) shouldBe Some("deepseek-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "make the selected provider resolvable from the loaded providers config" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "deepseek-main"
          |    deepseek-primary {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "sk-deepseek-primary"
          |    }
          |    deepseek-main {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "deepseek-key"
          |      baseUrl = "https://api.deepseek.com"
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

          selectedProviderName.asName shouldBe "deepseek-main"
          selectedProvider.provider shouldBe ProviderId("deepseek")
          selectedProvider.model.asString shouldBe "deepseek-chat"
          selectedProvider.apiKey.map(_.asKey) shouldBe Some("deepseek-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "fail clearly when the configured selected provider does not exist" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "missing-provider"
          |    deepseek-primary {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "sk-deepseek-primary"
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
          |    deepseek-primary {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "sk-deepseek-primary"
          |    }
          |    deepseek-main {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "deepseek-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider shouldBe None
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("deepseek-primary", "deepseek-main")
        case Left(err) =>
          fail(s"Expected ProvidersConfig without selected provider, got error: ${err.message}")
    }

    "fail the whole providers config when one named provider is invalid" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "deepseek-primary"
          |    deepseek-primary {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |      apiKey = "sk-deepseek-primary"
          |    }
          |    broken-deepseek {
          |      provider = "deepseek"
          |      model = "deepseek-chat"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Left(err) =>
          err.message should include("Provider 'broken-deepseek' (provider = deepseek) is missing required fields")
          err.message should include("- apiKey: set it in llm4s.conf under providers.broken-deepseek.apiKey")
        case Right(cfg) =>
          fail(s"Expected invalid named provider to fail whole providers config, got config: $cfg")
    }
  }
