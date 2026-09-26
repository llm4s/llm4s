package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ ProviderId, ProviderName }
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.llmconnect.spi.fixtures.FixtureProvider
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

class Llm4sConfigProviderSpec extends AnyWordSpec with Matchers:

  "Llm4sConfig named providers" should {
    "load a named provider directly by name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    fixturechat-main {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg =
        Llm4sConfig.provider(ConfigSource.string(hocon), "fixturechat-main").fold(err => fail(err.toString), identity)

      cfg match
        case fixture: FixtureChatConfig =>
          fixture.model shouldBe "fixture-model"
          fixture.apiKey shouldBe "fixture-key"
          fixture.baseUrl shouldBe FixtureChatProvider.DefaultBaseUrl
        case other =>
          fail(s"Expected FixtureChatConfig, got $other")
    }

    // The registry is what decides whether a `provider = "..."` entry can resolve, and it is
    // injectable so that an application can resolve providers its own modules supply (#1131).
    val fixtureHocon =
      """
        |llm4s {
        |  providers {
        |    fixture-main {
        |      provider = "fixturecloud"
        |      model = "fixture-1"
        |      apiKey = "fixture-key"
        |    }
        |  }
        |}
        |""".stripMargin

    "refuse a provider no registry on this classpath supplies" in {
      Llm4sConfig.provider(ConfigSource.string(fixtureHocon), "fixture-main") match
        case Left(error) =>
          error.message should include("Provider 'fixturecloud'")
          error.message should include("Registered providers:")
        case Right(cfg) => fail(s"Expected an unresolved-provider error, got $cfg")
    }

    "resolve a provider supplied by the caller's registry" in {
      given ProviderRegistry = ProviderRegistry.default.withProvider(FixtureProvider)

      // Reaching the fixture's own error means validation and loading both routed through it.
      Llm4sConfig.provider(ConfigSource.string(fixtureHocon), "fixture-main") match
        case Left(error) => error.message shouldBe "fixture provider builds no config"
        case Right(cfg)  => fail(s"Expected the fixture provider's own error, got $cfg")
    }

    "fail when a sibling named provider is invalid even if the requested provider is valid" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |    broken-fixturechat {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.provider(ConfigSource.string(hocon), "fixturechat-primary")

      result match
        case Left(err) =>
          err.message should include(
            "Provider 'broken-fixturechat' (provider = fixturechat) is missing required fields"
          )
          err.message should include("- apiKey: set it in llm4s.conf under providers.broken-fixturechat.apiKey")
        case Right(cfg) =>
          fail(s"Expected invalid sibling named provider to fail whole config, got config: $cfg")
    }

    "load the full validated providers config" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |    fixturechat-main {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.providers(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg.selectedProvider shouldBe Some(ProviderName("fixturechat-primary"))
      cfg.namedProviders.keySet.map(_.asName) shouldBe Set("fixturechat-primary", "fixturechat-main")
    }

    "load the configured default provider name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val providerName =
        Llm4sConfig.defaultProviderName(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)
      providerName shouldBe ProviderName("fixturechat-primary")
    }

    "load the configured default provider as ProviderConfig" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val cfg = Llm4sConfig.defaultProvider(ConfigSource.string(hocon)).fold(err => fail(err.toString), identity)

      cfg match
        case fixture: FixtureChatConfig =>
          fixture.model shouldBe "fixture-model"
          fixture.apiKey shouldBe "named-fixture-key"
          fixture.baseUrl shouldBe FixtureChatProvider.DefaultBaseUrl
        case other =>
          fail(s"Expected FixtureChatConfig, got $other")
    }

    "list models for a configured named provider by name" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val responseBody =
        """{
          |  "data": [
          |    { "id": "fixture-model", "created": 1710000000, "owned_by": "fixturechat" }
          |  ]
          |}""".stripMargin

      val httpClient = new MockHttpClient(HttpResponse(200, responseBody, Map.empty))

      val result = Llm4sConfig.listModels("fixturechat-primary", ConfigSource.string(hocon), httpClient)

      result match
        case Right(models) =>
          models.map(_.name.asString) shouldBe List("fixture-model")
          models.map(_.provider) shouldBe List(ProviderId("fixturechat"))
          httpClient.lastUrl shouldBe Some(s"${FixtureChatProvider.DefaultBaseUrl}/models")
        case Left(err) =>
          fail(s"Expected listed models, got error: ${err.message}")
    }

    "fail clearly when listing models for a missing named provider" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    provider = "fixturechat-primary"
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "sk-test"
          |    }
          |  }
          |}
          |""".stripMargin

      val result =
        Llm4sConfig.listModels(
          "opneai-main",
          ConfigSource.string(hocon),
          new MockHttpClient(HttpResponse(200, "{}", Map.empty))
        )

      result match
        case Left(err) =>
          err.message should include("Configured provider 'opneai-main' was not found")
        case Right(models) =>
          fail(s"Expected missing named provider failure, got models: $models")
    }

    "fail when requesting the default provider name and no default is configured" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.defaultProviderName(ConfigSource.string(hocon))
      result.isLeft shouldBe true
      result.left.getOrElse(fail("expected Left")).message should include(
        "No default provider configured under llm4s.providers.provider"
      )
    }

    "fail when requesting the default provider and no default is configured" in {
      val hocon =
        """
          |llm4s {
          |  providers {
          |    fixturechat-primary {
          |      provider = "fixturechat"
          |      model = "fixture-model"
          |      apiKey = "named-fixture-key"
          |    }
          |  }
          |}
          |""".stripMargin

      val result = Llm4sConfig.defaultProvider(ConfigSource.string(hocon))
      result.isLeft shouldBe true
      result.left.getOrElse(fail("expected Left")).message should include(
        "No default provider configured under llm4s.providers.provider"
      )
    }
  }
