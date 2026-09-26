package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProvidersConfigValidatorSpec extends AnyWordSpec with Matchers:

  "ProvidersConfigLoader.validate" should {

    "validate and normalize a full providers config" in {
      val raw = RawProvidersConfig(
        selectedProvider = Some(ProviderName("fixturechat-primary")),
        namedProviders = Map(
          ProviderName("fixturechat-primary") -> RawNamedProviderSection(
            provider = Some(" fixturechat "),
            model = Some(" fixture-model "),
            baseUrl = Some(" https://fixturechat.invalid/v2 "),
            apiKey = Some(" sk-fixture-primary "),
            organization = Some(" org-demo "),
            endpoint = None,
            apiVersion = None,
          ),
          ProviderName("fixturechat-main") -> RawNamedProviderSection(
            provider = Some("fixturechat"),
            model = Some("fixture-model"),
            baseUrl = Some("https://fixturechat.invalid/v1"),
            apiKey = Some("fixture-key"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
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
          main.apiKey.map(_.asKey) shouldBe Some("fixture-key")
        case Left(err) =>
          fail(s"Expected ProvidersConfig, got error: ${err.message}")
    }

    "allow providers config without a selected provider" in {
      val raw = RawProvidersConfig(
        selectedProvider = None,
        namedProviders = Map(
          ProviderName("fixturechat-main") -> RawNamedProviderSection(
            provider = Some("fixturechat"),
            model = Some("fixture-model"),
            baseUrl = None,
            apiKey = Some("fixture-key"),
            organization = None,
            endpoint = None,
            apiVersion = None,
          )
        )
      )

      ProvidersConfigLoader.validate(raw) match
        case Right(cfg) =>
          cfg.selectedProvider shouldBe None
          cfg.namedProviders.keySet.map(_.asName) shouldBe Set("fixturechat-main")
          cfg.namedProviders(ProviderName("fixturechat-main")).provider shouldBe ProviderId("fixturechat")
        case Left(err) =>
          fail(s"Expected ProvidersConfig without selected provider, got error: ${err.message}")
    }

    "fail clearly when the selected provider is not defined" in {
      val raw = RawProvidersConfig(
        selectedProvider = Some(ProviderName("missing-provider")),
        namedProviders = Map(
          ProviderName("fixturechat-primary") -> RawNamedProviderSection(
            provider = Some("fixturechat"),
            model = Some("fixture-model"),
            baseUrl = None,
            apiKey = Some("sk-fixture-primary"),
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
            model = Some("fixture-model"),
            baseUrl = None,
            apiKey = Some("sk-fixture-primary"),
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
