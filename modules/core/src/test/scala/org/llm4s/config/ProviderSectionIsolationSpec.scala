package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.llm4s.http.{ HttpResponse, MockHttpClient }
import org.llm4s.testutil.FixtureChatConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * One bad `llm4s.providers.<name>` section must not break the others (#1132).
 *
 * Every section used to be validated on every load, so a config holding `openai-main` and
 * `anthropic-main` failed entirely when only `OPENAI_API_KEY` was set: the unset `${?VAR}`
 * left `anthropic-main` without an API key, and that error was returned for `openai-main`
 * too. Only the section being resolved is validated now.
 */
class ProviderSectionIsolationSpec extends AnyWordSpec with Matchers:

  // A variable no environment sets, so `${?...}` leaves the key out, as an unset API key does.
  private val UnsetVar = "LLM4S_TEST_SECTION_ISOLATION_UNSET_KEY"

  private def config(badSection: String, selected: String = "good-one"): ConfigSource =
    ConfigSource.string(
      s"""
         |llm4s {
         |  providers {
         |    provider = "$selected"
         |    good-one {
         |      provider = "fixturechat"
         |      model = "fixture-model"
         |      apiKey = "good-key"
         |    }
         |    bad-one {
         |$badSection
         |    }
         |  }
         |}
         |""".stripMargin
    )

  private val unsetApiKey =
    s"""      provider = "fixturechat"
       |      model = "fixture-model"
       |      apiKey = $${?$UnsetVar}""".stripMargin

  private val missingModule =
    """      provider = "no-such-provider"
      |      model = "some-model"
      |      apiKey = "some-key"""".stripMargin

  private val unreadable =
    """      provider = "fixturechat"
      |      model = "fixture-model"
      |      headers = "not-a-map"""".stripMargin

  private def expectGood(result: org.llm4s.types.Result[?]): Unit =
    result match
      case Right(cfg: FixtureChatConfig) => cfg.apiKey shouldBe "good-key"
      case other                         => fail(s"Expected the good section's FixtureChatConfig, got $other")

  "A broken sibling section" when {
    Seq(
      "its ${?VAR} API key is unset"                -> unsetApiKey,
      "its provider module is not on the classpath" -> missingModule,
      "one of its keys cannot be read as its type"  -> unreadable
    ).foreach { case (label, badSection) =>
      label should {
        "not stop provider(name) resolving the good section" in {
          expectGood(Llm4sConfig.provider(config(badSection), "good-one"))
        }

        "not stop defaultProvider() resolving the good section" in {
          expectGood(Llm4sConfig.defaultProvider(config(badSection)))
        }

        "not stop defaultProviderName() naming the good section" in {
          Llm4sConfig.defaultProviderName(config(badSection)) shouldBe Right(ProviderName("good-one"))
        }

        "not stop listModels(name) for the good section" in {
          val http = new MockHttpClient(HttpResponse(200, """{"data":[{"id":"fixture-model"}]}""", Map.empty))
          Llm4sConfig.listModels("good-one", config(badSection), http).map(_.map(_.name.asString)) shouldBe
            Right(List("fixture-model"))
        }

        "still fail provider(name) for the bad section, naming it" in {
          Llm4sConfig.provider(config(badSection), "bad-one") match
            case Left(err)  => err.message should include("bad-one")
            case Right(cfg) => fail(s"Expected the bad section to fail, got $cfg")
        }

        "still fail defaultProvider() when the bad section is the default" in {
          Llm4sConfig.defaultProvider(config(badSection, selected = "bad-one")) match
            case Left(err)  => err.message should include("bad-one")
            case Right(cfg) => fail(s"Expected the bad default section to fail, got $cfg")
        }

        "report the bad section, and only it, as an error from providerConfigs" in {
          val (errors, configs) =
            Llm4sConfig.providerConfigs(config(badSection)).fold(err => fail(err.toString), identity)
          errors.keySet shouldBe Set(ProviderName("bad-one"))
          errors(ProviderName("bad-one")).message should include("bad-one")
          configs.keySet shouldBe Set(ProviderName("good-one"))
        }

        "still fail the full providers() load, which validates every section" in {
          Llm4sConfig.providers(config(badSection)).isLeft shouldBe true
        }
      }
    }
  }

  "The specific error for the bad section" should {
    "name the unset apiKey and how to bind it" in {
      Llm4sConfig.provider(config(unsetApiKey), "bad-one") match
        case Left(err) =>
          err.message should include("Provider 'bad-one' (provider = fixturechat) is missing required fields")
          err.message should include(
            "- apiKey: set llm4s.credentials.fixturechat.apiKey, or set apiKey under llm4s.providers.bad-one"
          )
        case Right(cfg) => fail(s"Expected a missing apiKey error, got $cfg")
    }

    "name the unregistered provider and the config path that asked for it" in {
      Llm4sConfig.provider(config(missingModule), "bad-one") match
        case Left(err) =>
          err.message should include("no-such-provider")
          err.message should include("llm4s.providers.bad-one.provider")
        case Right(cfg) => fail(s"Expected an unresolved-provider error, got $cfg")
    }

    "name the key that could not be read" in {
      Llm4sConfig.provider(config(unreadable), "bad-one") match
        case Left(err) =>
          err.message should include("llm4s.providers.bad-one")
          err.message should include("headers")
        case Right(cfg) => fail(s"Expected a read error, got $cfg")
    }
  }

  "Problems outside any single section" should {
    "still fail every lookup when the default names a section that does not exist" in {
      val source = config(unsetApiKey, selected = "missing-one")
      Llm4sConfig.defaultProvider(source).left.map(_.message) shouldBe
        Left("Configured provider 'missing-one' was not found")
      Llm4sConfig.defaultProviderName(source).left.map(_.message) shouldBe
        Left("Configured provider 'missing-one' was not found")
    }

    "still fail when the providers block itself is not an object" in {
      val source = ConfigSource.string("""llm4s { providers = "openai" }""")
      Llm4sConfig.provider(source, "good-one").isLeft shouldBe true
      Llm4sConfig.defaultProvider(source).isLeft shouldBe true
    }
  }
