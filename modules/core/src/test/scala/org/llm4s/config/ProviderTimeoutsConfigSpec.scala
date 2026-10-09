package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProviderName }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.Result
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a named provider section (#712): read, checked, and carried on the
 * [[NamedProviderConfig]] every provider descriptor builds its config from.
 */
class ProviderTimeoutsConfigSpec extends AnyWordSpec with Matchers:

  private given ProviderRegistry = ProviderRegistry.of(FixtureChatProvider)

  private def load(body: String): Result[NamedProviderConfig] =
    val hocon =
      s"""llm4s.providers.main {
         |  provider = fixturechat
         |  model    = m
         |  apiKey   = k
         |$body
         |}
         |""".stripMargin
    RawProvidersConfigLoader
      .loadSections(ConfigSource.string(hocon))
      .flatMap(_.validated(ProviderName("main")))

  private def loaded(body: String): ProviderTimeouts =
    load(body) match
      case Right(config) => config.timeouts
      case Left(error)   => fail(s"expected the section to load, got: ${error.message}")

  private def refused(body: String): String =
    load(body) match
      case Left(error: ConfigurationError) => error.message
      case Left(other)                     => fail(s"expected a ConfigurationError, got $other")
      case Right(config)                   => fail(s"expected the section to be refused, got $config")

  "A section without a timeouts block" should {
    "leave every timeout unset, so each client keeps its own default" in {
      val timeouts = loaded("")
      timeouts shouldBe ProviderTimeouts.default
      timeouts.isDefault shouldBe true
      timeouts.request shouldBe None
      timeouts.stream shouldBe None
    }

    "treat an explicitly null block as absent" in {
      loaded("timeouts = null") shouldBe ProviderTimeouts.default
    }

    "treat an empty block as absent" in {
      loaded("timeouts {}") shouldBe ProviderTimeouts.default
    }
  }

  "A timeouts block" should {
    "read the request and stream timeouts as durations" in {
      val timeouts = loaded("timeouts { request = 3m, stream = 15m }")
      timeouts.request shouldBe Some(3.minutes)
      timeouts.stream shouldBe Some(15.minutes)
      timeouts.isDefault shouldBe false
    }

    "accept either timeout alone" in {
      loaded("timeouts { request = 45s }") shouldBe ProviderTimeouts(request = Some(45.seconds))
      loaded("timeouts { stream = 90s }") shouldBe ProviderTimeouts(stream = Some(90.seconds))
    }

    "accept every duration unit HOCON writes" in {
      loaded("timeouts { request = 1500ms }").request shouldBe Some(1500.millis)
      loaded("timeouts { request = 2 minutes }").request shouldBe Some(2.minutes)
      loaded("timeouts { request = 1h }").request shouldBe Some(1.hour)
    }

    "take the value from a ${?VAR} binding, and leave the default when it is unset" in {
      loaded("timeouts { request = ${?LLM4S_TEST_712_UNSET_VARIABLE} }") shouldBe ProviderTimeouts.default
    }

    "be carried on the validated section next to its other fields" in {
      val config = load("timeouts { request = 10s }").getOrElse(fail("expected the section to load"))
      config.model.asString shouldBe "m"
      config.apiKey.isDefined shouldBe true
      config.timeouts.request shouldBe Some(10.seconds)
    }
  }

  "A timeouts block with a bad value" should {
    "refuse zero, naming the key by its full path" in {
      val message = refused("timeouts { request = 0s }")
      message should include("llm4s.providers.main.timeouts.request")
      message should include("greater than zero")
    }

    "refuse a negative stream timeout, naming the key" in {
      val message = refused("timeouts { stream = -5s }")
      message should include("llm4s.providers.main.timeouts.stream")
      message should include("greater than zero")
    }

    "name the stream key, and not the request key, when only the stream is bad" in {
      val message = refused("timeouts { request = 10s, stream = 0s }")
      message should include("timeouts.stream")
      (message should not).include("timeouts.request")
    }

    "refuse an infinite timeout, which is not a finite duration" in {
      val message = refused("timeouts { request = Inf }")
      message should include("timeouts.request")
    }

    "refuse a value that is not a duration, naming the key" in {
      val message = refused("timeouts { request = soon }")
      message should include("timeouts.request")
    }

    "refuse a misspelled key instead of silently ignoring it" in {
      val message = refused("timeouts { reqest = 2m }")
      message should include("reqest")
      message should include("request, stream")
    }

    "refuse a block that is not an object" in {
      refused("timeouts = 30s") should include("timeouts")
    }
  }

  "A timeouts block" should {
    "not make `timeouts` an unknown provider key" in {
      // `timeouts` is a built-in key like `headers`: it must not be reported as an undeclared extra.
      load("timeouts { request = 1m }").isRight shouldBe true
    }

    "stay out of the extras" in {
      val config = load("timeouts { request = 1m }").getOrElse(fail("expected the section to load"))
      config.extras shouldBe Map.empty
    }
  }

  "ProviderTimeouts" should {
    "start with nothing set" in {
      ProviderTimeouts.default.request shouldBe None
      ProviderTimeouts.default.stream shouldBe None
      ProviderTimeouts().isDefault shouldBe true
    }

    "use the configured value, and the client's default otherwise" in {
      val set = ProviderTimeouts(Some(7.seconds), Some(9.seconds))
      set.requestOr(2.minutes) shouldBe 7.seconds
      set.streamOr(10.minutes) shouldBe 9.seconds
      ProviderTimeouts.default.requestOr(2.minutes) shouldBe 2.minutes
      ProviderTimeouts.default.streamOr(10.minutes) shouldBe 10.minutes
    }

    "set each value independently with the with* setters" in {
      val timeouts = ProviderTimeouts.default.withRequest(5.seconds).withStream(6.seconds)
      timeouts shouldBe ProviderTimeouts(Some(5.seconds), Some(6.seconds))
      timeouts.withRequest(None) shouldBe ProviderTimeouts(None, Some(6.seconds))
      timeouts.withStream(None) shouldBe ProviderTimeouts(Some(5.seconds), None)
    }

    "check a value only when asked to" in {
      ProviderTimeouts.validated(Some(1.second), Some(2.seconds)) shouldBe Right(
        ProviderTimeouts(Some(1.second), Some(2.seconds))
      )
      ProviderTimeouts.validated(None, None) shouldBe Right(ProviderTimeouts.default)
      ProviderTimeouts.validated(Some(0.seconds), None).left.map(_.message) match
        case Left(message) => message should include("timeouts.request")
        case Right(other)  => fail(s"expected a refusal, got $other")
      ProviderTimeouts.validated(None, Some(-1.second)).isLeft shouldBe true
    }

    "be what a NamedProviderConfig carries, with the old apply still available" in {
      val withTimeouts = NamedProviderConfig(
        provider = org.llm4s.types.ProviderModelTypes.ProviderId("fixturechat"),
        model = org.llm4s.types.ProviderModelTypes.ModelName("m"),
        baseUrl = None,
        apiKey = None,
        timeouts = ProviderTimeouts(Some(1.minute), None)
      )
      withTimeouts.timeouts.request shouldBe Some(1.minute)
      withTimeouts.withTimeouts(ProviderTimeouts.default).timeouts shouldBe ProviderTimeouts.default

      // The signature that predates `timeouts` still compiles and leaves it unset.
      val old = NamedProviderConfig(
        org.llm4s.types.ProviderModelTypes.ProviderId("fixturechat"),
        org.llm4s.types.ProviderModelTypes.ModelName("m"),
        None,
        None,
        Map.empty[String, String],
        Map.empty[String, String]
      )
      old.timeouts shouldBe ProviderTimeouts.default
    }
  }

  "The loader" should {
    "apply the section's timeouts to the config a descriptor builds, without the descriptor reading them" in {
      val hocon =
        """llm4s.providers.main {
          |  provider = fixturechat
          |  model    = m
          |  apiKey   = k
          |  timeouts { request = 3m, stream = 15m }
          |}
          |""".stripMargin
      org.llm4s.config.Llm4sConfig.provider(ConfigSource.string(hocon), "main") match
        case Right(config: FixtureChatConfig) =>
          // FixtureChatProvider.buildConfig never mentions timeouts: the loader applied them.
          config.timeouts shouldBe ProviderTimeouts(Some(3.minutes), Some(15.minutes))
        case other => fail(s"expected a FixtureChatConfig, got $other")
    }

    "leave a config that does not carry timeouts as the descriptor built it" in {
      val config = new org.llm4s.llmconnect.config.ProviderConfig:
        def providerId               = org.llm4s.types.ProviderModelTypes.ProviderId("x")
        def model                    = "m"
        def contextWindow            = 1
        def reserveCompletion        = 1
        def endpointUrl              = None
        def withModel(model: String) = this
      config.withTimeouts(ProviderTimeouts(Some(1.second), None)) shouldBe config
      config.timeouts shouldBe ProviderTimeouts.default
    }
  }
