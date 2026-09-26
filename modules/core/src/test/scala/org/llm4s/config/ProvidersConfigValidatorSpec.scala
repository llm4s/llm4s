package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProvidersConfigValidatorSpec extends AnyWordSpec with Matchers:

  "ProvidersConfigLoader.validate" should {

    "validate and normalize a full providers config" in {
      val raw = RawProvidersConfig(
        selectedProvider = Some(ProviderName("openai-main")),
        namedProviders = Map(
          ProviderName("openai-main") -> RawNamedProviderSection(
            provider = Some(" openai "),
            model = Some(" gpt-4o-mini "),
            baseUrl = Some(" https://api.openai.com/v1 "),
            apiKey = Some(" sk-openai "),
            organization = Some(" org-demo "),
            endpoint = None,
            apiVersion = None,
          ),
          ProviderName("deepseek-main") -> RawNamedProviderSection(
            provider = Some("deepseek"),
            model = Some("deepseek-chat"),
            baseUrl = Some("https://api.deepseek.com"),
            apiKey = Some("deepseek-key"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
        case Right(cfg) =>
          cfg.selectedProvider.map(_.asName) shouldBe Some("openai-main")
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("openai-main", "deepseek-main")

          val openai = cfg.namedProviders(ProviderName("openai-main"))
          openai.provider shouldBe ProviderId("openai")
          openai.model.asString shouldBe "gpt-4o-mini"
          openai.baseUrl.map(_.asUrl) shouldBe Some("https://api.openai.com/v1")
          openai.apiKey.map(_.asKey) shouldBe Some("sk-openai")
          openai.organization shouldBe Some("org-demo")

          val deepseek = cfg.namedProviders(ProviderName("deepseek-main"))
          deepseek.provider shouldBe ProviderId("deepseek")
          deepseek.model.asString shouldBe "deepseek-chat"
          deepseek.apiKey.map(_.asKey) shouldBe Some("deepseek-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "allow providers config without a selected provider" in {
      val raw = RawProvidersConfig(
        selectedProvider = None,
        namedProviders = Map(
          ProviderName("deepseek-main") -> RawNamedProviderSection(
            provider = Some("deepseek"),
            model = Some("deepseek-chat"),
            baseUrl = None,
            apiKey = Some("deepseek-key"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
        case Right(cfg) =>
          cfg.selectedProvider shouldBe None
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("deepseek-main")
          cfg.namedProviders(ProviderName("deepseek-main")).provider shouldBe ProviderId("deepseek")
        case Left(err) =>
          fail(s"Expected ProvidersConfig without selected provider, got error: ${err.message}")
    }

    "fail clearly when the selected provider is not defined" in {
      val raw = RawProvidersConfig(
        selectedProvider = Some(ProviderName("missing-provider")),
        namedProviders = Map(
          ProviderName("openai-main") -> RawNamedProviderSection(
            provider = Some("openai"),
            model = Some("gpt-4o-mini"),
            baseUrl = None,
            apiKey = Some("sk-openai"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
        case Left(err) =>
          err.message should include("Configured provider 'missing-provider' was not found")
        case Right(cfg) =>
          fail(s"Expected missing selected provider error, got config: $cfg")
    }

    "surface normalization errors from invalid named providers" in {
      val raw = RawProvidersConfig(
        selectedProvider = Some(ProviderName("broken")),
        namedProviders = Map(
          ProviderName("broken") -> RawNamedProviderSection(
            provider = None,
            model = Some("gpt-4o-mini"),
            baseUrl = None,
            apiKey = Some("sk-openai"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
        case Left(err) =>
          err.message should include("missing required field `provider`")
        case Right(cfg) =>
          fail(s"Expected invalid named provider error, got config: $cfg")
    }
  }
