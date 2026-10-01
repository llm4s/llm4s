package org.llm4s.testkit

import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.fixtures.{ AcmeConfig, AcmeEmbeddings, AcmeProvider }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Loading config from a HOCON string and an injected environment, as an application would. */
class ProviderTestConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  given ProviderRegistry = ProviderRegistry.default

  private val acmeMain = """llm4s.providers.acme-main { provider = "acme", model = "acme-large" }"""

  "loadSection" should {

    "take the key from the vendor's shared credential, which reference.conf binds to the injected variable" in {
      val section = ProviderTestConfig.loadSection("acme-main", acmeMain, Map("ACME_API_KEY" -> "sk-acme")).value

      section.provider shouldBe ProviderId("acme")
      section.model shouldBe ModelName("acme-large")
      section.apiKey.map(_.asKey) shouldBe Some("sk-acme")
    }

    "let the section's own key win" in {
      val hocon = """llm4s.providers.acme-main { provider = "acme", model = "m", apiKey = ${?ACME_TEAM_KEY} }"""
      ProviderTestConfig
        .loadSection("acme-main", hocon, Map("ACME_API_KEY" -> "shared", "ACME_TEAM_KEY" -> "team"))
        .value
        .apiKey
        .map(_.asKey) shouldBe Some("team")
    }

    "canonicalise an alias" in {
      val hocon = """llm4s.providers.a { provider = "acme-ai", model = "m", apiKey = "k" }"""
      ProviderTestConfig.loadSection("a", hocon).value.provider shouldBe ProviderId("acme")
    }

    "not see the real process environment" in {
      // PATH is set in any process running these tests; only the injected map may be seen.
      val hocon = """llm4s.providers.acme-main { provider = "acme", model = "m", apiKey = ${?PATH} }"""
      ProviderTestConfig.loadSection("acme-main", hocon).left.value.message should include("ACME_API_KEY")
    }

    "validate only the named section" in {
      val hocon = acmeMain + "\n" + """llm4s.providers.broken { provider = "no-such-provider", model = "m" }"""
      ProviderTestConfig.loadSection("acme-main", hocon, Map("ACME_API_KEY" -> "k")).isRight shouldBe true
      ProviderTestConfig.loadSection("broken", hocon).isLeft shouldBe true
    }

    "report a section that does not exist" in {
      ProviderTestConfig.loadSection("absent", acmeMain).left.value.message should include("absent")
    }
  }

  "loadProvider" should {

    "build the descriptor's config, defaults applied" in {
      ProviderTestConfig.loadProvider("acme-main", acmeMain, Map("ACME_API_KEY" -> "sk-acme")).value shouldBe
        AcmeConfig("sk-acme", "acme-large", AcmeProvider.DefaultBaseUrl)
    }

    "fail as an application would when no key is available" in {
      ProviderTestConfig.loadProvider("acme-main", acmeMain).isLeft shouldBe true
    }
  }

  "loadEmbeddings" should {

    "select the embedding provider and build its config" in {
      val (id, config) = ProviderTestConfig
        .loadEmbeddings("""llm4s.embeddings.model = "acme/acme-small"""", Map("ACME_API_KEY" -> "sk-acme"))
        .value

      id shouldBe AcmeEmbeddings.id.asString
      config.model shouldBe "acme-small"
      config.apiKey shouldBe "sk-acme"
      config.baseUrl shouldBe AcmeProvider.DefaultBaseUrl
    }

    "fail for an embedding provider no module supplies" in {
      ProviderTestConfig.loadEmbeddings("""llm4s.embeddings.model = "nope/m"""").isLeft shouldBe true
    }
  }
