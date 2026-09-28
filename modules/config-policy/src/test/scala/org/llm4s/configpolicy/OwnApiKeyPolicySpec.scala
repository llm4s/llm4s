package org.llm4s.configpolicy

import org.llm4s.config.{ ApiKeySource, Llm4sConfig }
import org.llm4s.config.ProvidersConfigModel.ProviderName
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * The `ownApiKey` rule: in prod, every chat section sets its own `apiKey`.
 *
 * A section without one uses its vendor's shared `llm4s.credentials.<id>.apiKey`. That is the
 * convenient default, and also how a section meant for a second account, missing its key,
 * silently bills the first.
 */
class OwnApiKeyPolicySpec extends AnyWordSpec with Matchers with EitherValues {

  private val inheriting = Map[ProviderName, ApiKeySource](
    ProviderName("openai-batch") -> ApiKeySource.Credentials("llm4s.credentials.openai.apiKey")
  )
  private val explicit = Map[ProviderName, ApiKeySource](
    ProviderName("openai-main") -> ApiKeySource.Section("llm4s.providers.openai-main.apiKey")
  )

  "ConfigPolicyEngine.checkApiKeySources" should {

    "flag a section that would inherit the shared key, under the prod preset" in {
      ConfigPolicyEngine.checkApiKeySources(
        inheriting ++ explicit,
        ConfigPolicy.prodSafeDefaults,
        CatalogEnvironment.Prod
      ) shouldBe List(
        PolicyViolation(
          "ownApiKey",
          "llm4s.providers.openai-batch sets no apiKey, so it would use the shared llm4s.credentials.openai.apiKey; " +
            "set llm4s.providers.openai-batch.apiKey to the key for the account it should bill"
        )
      )
    }

    "pass sections that set their own key" in {
      ConfigPolicyEngine.checkApiKeySources(explicit, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) shouldBe
        empty
    }

    "be enabled in the prod preset only" in {
      ConfigPolicy.prodSafeDefaults.ownApiKeyRequiredIn shouldBe Set(CatalogEnvironment.Prod)
      ConfigPolicy.devSandbox.ownApiKeyRequiredIn shouldBe empty
      ConfigPolicy.permissive.ownApiKeyRequiredIn shouldBe empty

      ConfigPolicyEngine.checkApiKeySources(inheriting, ConfigPolicy.devSandbox, CatalogEnvironment.Dev) shouldBe empty
      ConfigPolicyEngine.checkApiKeySources(inheriting, ConfigPolicy.permissive, CatalogEnvironment.Prod) shouldBe
        empty
      // The prod preset run against another environment does not apply the rule either.
      ConfigPolicyEngine.checkApiKeySources(
        inheriting,
        ConfigPolicy.prodSafeDefaults,
        CatalogEnvironment.Staging
      ) shouldBe
        empty
    }

    "be switchable on for any environment" in {
      val policy = ConfigPolicy.permissive.withOwnApiKeyRequired(CatalogEnvironment.Staging)
      ConfigPolicyEngine.checkApiKeySources(inheriting, policy, CatalogEnvironment.Staging).map(_.rule) shouldBe
        List("ownApiKey")
    }
  }

  "the rule, fed from a real config" should {

    "flag every inheriting section, not only the default, and skip keyless providers" in {
      val source = ConfigSource.string(
        """llm4s.providers {
          |  provider = "openai-main"
          |  openai-main  { provider = "openai", model = "gpt-4o-mini", apiKey = "sk-main" }
          |  openai-batch { provider = "openai", model = "gpt-4o-mini" }
          |  claude       { provider = "anthropic", model = "claude-sonnet-4-5" }
          |  local        { provider = "ollama", model = "llama3", baseUrl = "http://localhost:11434" }
          |}""".stripMargin
      )

      val violations = ConfigPolicyEngine.checkApiKeySources(
        Llm4sConfig.apiKeySourcesFrom(source).value,
        ConfigPolicy.prodSafeDefaults,
        CatalogEnvironment.Prod
      )

      violations.map(_.message.takeWhile(_ != ' ')) shouldBe List(
        "llm4s.providers.claude",
        "llm4s.providers.openai-batch"
      )
    }
  }
}
