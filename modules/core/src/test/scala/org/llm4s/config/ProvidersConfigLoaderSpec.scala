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
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      baseUrl = "https://fixturechat.invalid/v2"
          |      apiKey = "sk-fixture-primary"
          |      organization = "org-demo"
          |    }
          |    fixturechat-main {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      baseUrl = "https://fixturechat.invalid/v1"
          |      apiKey = "fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider.map(_.asName) shouldBe Some("fixturechat-primary")
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("fixturechat-primary", "fixturechat-main")

          val primary = cfg.namedProviders(ProviderName("fixturechat-primary"))
          primary.provider shouldBe ProviderId("fixturechat")
          primary.model.asString shouldBe "fixture-model"
          primary.baseUrl.map(_.asUrl) shouldBe Some("https://fixturechat.invalid/v2")
          primary.apiKey.map(_.asKey) shouldBe Some("sk-fixture-primary")
          primary.organization shouldBe Some("org-demo")

          val main = cfg.namedProviders(ProviderName("fixturechat-main"))
          main.provider shouldBe ProviderId("fixturechat")
          main.model.asString shouldBe "fixture-model"
          main.baseUrl.map(_.asUrl) shouldBe Some("https://fixturechat.invalid/v1")
          main.apiKey.map(_.asKey) shouldBe Some("fixture-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "make the selected provider resolvable from the loaded providers config" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-main"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-fixture-primary"
          |    }
          |    fixturechat-main {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "fixture-key"
          |      baseUrl = "https://fixturechat.invalid/v1"
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

          selectedProviderName.asName shouldBe "fixturechat-main"
          selectedProvider.provider shouldBe ProviderId("fixturechat")
          selectedProvider.model.asString shouldBe "fixture-model"
          selectedProvider.apiKey.map(_.asKey) shouldBe Some("fixture-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "fail clearly when the configured selected provider does not exist" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "missing-provider"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-fixture-primary"
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
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-fixture-primary"
          |    }
          |    fixturechat-main {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(cfg) =>
          cfg.selectedProvider shouldBe None
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("fixturechat-primary", "fixturechat-main")
        case Left(err) =>
          fail(s"Expected ProvidersConfig without selected provider, got error: ${err.message}")
    }

    "fail the whole providers config when one named provider is invalid" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-fixture-primary"
          |    }
          |    broken-fixturechat {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = ProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Left(err) =>
          err.message should include(
            "Provider 'broken-fixturechat' (provider = fixturechat) is missing required fields"
          )
          err.message should include("- apiKey: set it in llm4s.conf under providers.broken-fixturechat.apiKey")
        case Right(cfg) =>
          fail(s"Expected invalid named provider to fail whole providers config, got config: $cfg")
    }
  }
