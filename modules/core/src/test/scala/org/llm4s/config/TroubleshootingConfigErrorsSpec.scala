package org.llm4s.config

import org.llm4s.error.{ ConfigurationError, LLMError, TroubleshootingLinks }
import org.llm4s.error.TroubleshootingLinks.Entry
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testutil.{ FixtureChatProvider, FixtureEmbeddingProvider, TroubleshootingPage }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Each first-run configuration error links its entry on the First-Run Troubleshooting page and ends with the
 * doctor's command (#2285), and the page's symptom for that entry shows the message as the code words it. Uses
 * only `FixtureChatProvider` and HOCON strings: no network, no environment.
 */
class TroubleshootingConfigErrorsSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry =
    ProviderRegistry.of(FixtureChatProvider).withEmbeddingProvider(FixtureEmbeddingProvider)

  private def source(hocon: String): ConfigSource = ConfigSource.string(hocon)

  /** `error` is a ConfigurationError whose message contains `prefix`, then the entry's link and the doctor line. */
  private def assertLinked(error: LLMError, entry: Entry, prefix: String): Unit =
    error shouldBe a[ConfigurationError]
    error.message should include(prefix)
    error.message should endWith(s"\n${TroubleshootingLinks.forId(entry)}\n${TroubleshootingLinks.DoctorLine}")
    withClue(s"the symptom of #${entry.id} on ${TroubleshootingLinks.PageSource}: ") {
      TroubleshootingPage.section(entry) should include(prefix)
    }

  private val fixtureSection =
    """llm4s.providers {
      |  provider = "main"
      |  main {
      |    provider = "fixturechat"
      |    model    = "fixture-model"
      |  }
      |}
      |""".stripMargin

  "A missing API key" should {
    "link #missing-api-key, from the default provider and from a section loaded by name" in {
      val prefix = "is missing required fields:\n  - apiKey: set "
      assertLinked(Llm4sConfig.defaultProvider(source(fixtureSection)).left.value, Entry.MissingApiKey, prefix)
      assertLinked(Llm4sConfig.provider(source(fixtureSection), "main").left.value, Entry.MissingApiKey, prefix)
    }

    "link #missing-api-key for an embeddings block too" in {
      val hocon =
        """llm4s.embeddings {
          |  model = "fixtureembedding/fixture-embed-small"
          |  fixtureembedding { baseUrl = "https://fixtureembedding.invalid/v1" }
          |}
          |""".stripMargin
      val error = EmbeddingsConfigLoader.loadProvider(source(hocon)).left.value
      error.message should include("embeddings apiKey: set ")
      error.message should endWith(
        s"\n${TroubleshootingLinks.forId(Entry.MissingApiKey)}\n${TroubleshootingLinks.DoctorLine}"
      )
      TroubleshootingPage.section(Entry.MissingApiKey) should include("embeddings apiKey: set ")
    }
  }

  "No default provider" should {
    "link #no-default-provider" in {
      val hocon = """llm4s.providers.main { provider = "fixturechat", model = "m", apiKey = "k" }"""
      assertLinked(
        Llm4sConfig.defaultProvider(source(hocon)).left.value,
        Entry.NoDefaultProvider,
        "No default provider configured under llm4s.providers.provider"
      )
      assertLinked(
        Llm4sConfig.defaultProviderName(source(hocon)).left.value,
        Entry.NoDefaultProvider,
        "No default provider configured under llm4s.providers.provider"
      )
    }
  }

  "An unknown section" should {
    "link #provider-not-found, as the default and by name" in {
      val hocon =
        """llm4s.providers {
          |  provider = "typo"
          |  main { provider = "fixturechat", model = "m", apiKey = "k" }
          |}
          |""".stripMargin
      assertLinked(Llm4sConfig.defaultProvider(source(hocon)).left.value, Entry.ProviderNotFound, "' was not found")
      assertLinked(Llm4sConfig.provider(source(hocon), "other").left.value, Entry.ProviderNotFound, "' was not found")
      assertLinked(Llm4sConfig.providers(source(hocon)).left.value, Entry.ProviderNotFound, "Configured provider '")
    }
  }

  "A provider that is not registered" should {
    "link #provider-not-registered" in {
      val hocon =
        """llm4s.providers {
          |  provider = "main"
          |  main { provider = "openai", model = "gpt-4o-mini", apiKey = "k" }
          |}
          |""".stripMargin
      assertLinked(
        Llm4sConfig.defaultProvider(source(hocon)).left.value,
        Entry.ProviderNotRegistered,
        " is not registered. Registered providers: "
      )
    }
  }

  "An application.conf that cannot be read" should {
    "link #application-conf-not-loaded when it does not parse" in {
      assertLinked(
        Llm4sConfig.defaultProvider(source("llm4s { providers = [ ")).left.value,
        Entry.ApplicationConf,
        "Failed to load raw providers config via PureConfig: "
      )
    }

    "link #application-conf-not-loaded when the providers block has the wrong shape" in {
      assertLinked(
        Llm4sConfig.defaultProvider(source("llm4s.providers = 42")).left.value,
        Entry.ApplicationConf,
        "Failed to load raw providers config via PureConfig: "
      )
    }

    "link #application-conf-not-loaded when one section cannot be read" in {
      val hocon =
        """llm4s.providers {
          |  provider = "main"
          |  main = "not an object"
          |}
          |""".stripMargin
      assertLinked(
        Llm4sConfig.defaultProvider(source(hocon)).left.value,
        Entry.ApplicationConf,
        "Failed to read llm4s.providers."
      )
    }
  }

  "A section that loads" should {
    "carry no link" in {
      Llm4sConfig
        .provider(source(fixtureSection + "llm4s.providers.main.apiKey = \"k\"\n"), "main")
        .isRight shouldBe true
    }
  }
