package org.llm4s.config

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.llm4s.types.ProviderModelTypes.ProviderName
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The default section is named by `llm4s.defaultProvider`, outside the map of sections (#2806). It used to be
 * `llm4s.providers.provider`, one key among the user's own section names: a section could not be called `provider`,
 * and `provider` meant "the default section" one level above where it means "which provider this section is".
 */
class DefaultProviderKeySpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry = ProviderRegistry.of(FixtureChatProvider)

  private def source(hocon: String) = ConfigSource.string(hocon)

  "llm4s.defaultProvider" should {
    "name the default section" in {
      val hocon =
        """llm4s.defaultProvider = "main"
          |llm4s.providers.main { provider = fixturechat, model = m, apiKey = k }
          |""".stripMargin
      Llm4sConfig.defaultProviderName(source(hocon)).value shouldBe ProviderName("main")
      Llm4sConfig.defaultProvider(source(hocon)).value shouldBe a[FixtureChatConfig]
    }

    "be trimmed, and blank is unset" in {
      val section = "llm4s.providers.main { provider = fixturechat, model = m, apiKey = k }\n"
      Llm4sConfig.defaultProviderName(source("llm4s.defaultProvider = \" main \"\n" + section)).value shouldBe
        ProviderName("main")
      Llm4sConfig.defaultProviderName(source("llm4s.defaultProvider = \"  \"\n" + section)).left.value.message should
        include("llm4s.defaultProvider")
    }

    "be refused when it is not a string" in {
      val hocon =
        """llm4s.defaultProvider { name = "main" }
          |llm4s.providers.main { provider = fixturechat, model = m, apiKey = k }
          |""".stripMargin
      Llm4sConfig.defaultProviderName(source(hocon)).left.value.message should include("llm4s.defaultProvider")
    }
  }

  "a section named provider" should {
    "load like any other, and can be the default" in {
      val hocon =
        """llm4s.defaultProvider = "provider"
          |llm4s.providers.provider { provider = fixturechat, model = m, apiKey = k }
          |llm4s.providers.other    { provider = fixturechat, model = n, apiKey = k }
          |""".stripMargin
      Llm4sConfig.provider(source(hocon), ProviderName("provider")).value.model.asString shouldBe "m"
      Llm4sConfig.defaultProvider(source(hocon)).value.model.asString shouldBe "m"
      Llm4sConfig.providerConfigs(source(hocon)).value.keySet shouldBe Set(
        ProviderName("provider"),
        ProviderName("other")
      )
    }
  }

  "the old llm4s.providers.provider" should {
    val hocon =
      """llm4s.providers {
        |  provider = "main"
        |  main { provider = fixturechat, model = m, apiKey = k }
        |}
        |""".stripMargin

    "no longer name the default, and the error says where the key went" in {
      val message = Llm4sConfig.defaultProviderName(source(hocon)).left.value.message
      message should include("llm4s.defaultProvider")
      message should include("llm4s.providers.provider")
    }

    "not stop the sections beside it loading" in {
      Llm4sConfig.provider(source(hocon), ProviderName("main")).value shouldBe a[FixtureChatConfig]
    }
  }
