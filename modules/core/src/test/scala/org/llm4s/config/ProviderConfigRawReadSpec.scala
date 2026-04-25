package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProviderConfigRawReadSpec extends AnyWordSpec with Matchers:

  "RawProvidersConfigLoader" should {

    "read the selected provider name and named providers from the new providers.provider config shape" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      baseUrl = "https://api.openai.com/v1"
          |      apiKey = "sk-test"
          |      organization = "org-demo"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = RawProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(raw) =>
          raw.selectedProvider.map(_.asName) shouldBe Some("openai-main")
          raw.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main")

          val provider = raw.namedProviders(ProviderName("openai-main"))
          provider.provider shouldBe Some("openai")
          provider.model shouldBe Some("gpt-4o-mini")
          provider.baseUrl shouldBe Some("https://api.openai.com/v1")
          provider.apiKey shouldBe Some("sk-test")
          provider.organization shouldBe Some("org-demo")
          provider.endpoint shouldBe None
          provider.apiVersion shouldBe None
        case Left(err) =>
          fail(s"Expected RawProvidersConfig, got error: ${err.message}")
    }

    "read multiple named providers while keeping the selected provider as a ProviderName" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai-main"
          |    openai-main {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      baseUrl = "https://api.openai.com/v1"
          |      apiKey = "sk-test"
          |    }
          |    gemini-main {
          |      provider = "gemini"
          |      model = "gemini-2.5-flash"
          |      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
          |      apiKey = "google-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = RawProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(raw) =>
          raw.selectedProvider.map(_.asName) shouldBe Some("openai-main")
          raw.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main", "gemini-main")

          val openai = raw.namedProviders(ProviderName("openai-main"))
          val gemini = raw.namedProviders(ProviderName("gemini-main"))

          openai.provider shouldBe Some("openai")
          openai.model shouldBe Some("gpt-4o-mini")
          gemini.provider shouldBe Some("gemini")
          gemini.model shouldBe Some("gemini-2.5-flash")
        case Left(err) =>
          fail(s"Expected RawProvidersConfig, got error: ${err.message}")
    }

    "allow the selected provider name to match a simple configured provider key" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "openai"
          |    openai {
          |      provider = "openai"
          |      model = "gpt-4o-mini"
          |      baseUrl = "https://api.openai.com/v1"
          |      apiKey = "sk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = RawProvidersConfigLoader.load(ConfigSource.string(hocon))

      result match
        case Right(raw) =>
          raw.selectedProvider.map(_.asName) shouldBe Some("openai")
          raw.namedProviders.keySet.map(_.asName) shouldBe Set("openai")

          val provider = raw.namedProviders(ProviderName("openai"))
          provider.provider shouldBe Some("openai")
          provider.model shouldBe Some("gpt-4o-mini")
          provider.baseUrl shouldBe Some("https://api.openai.com/v1")
          provider.apiKey shouldBe Some("sk-test")
          provider.organization shouldBe None
          provider.endpoint shouldBe None
          provider.apiVersion shouldBe None
        case Left(err) =>
          fail(s"Expected RawProvidersConfig, got error: ${err.message}")
    }
  }
