package org.llm4s.configpolicy

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAICompatibleConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.{ BeforeAndAfterEach, EitherValues }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

import java.util.Locale

/**
 * What a policy keyed by provider must do when its keys are wrong or its caps are missing: every case
 * here is one where a security control would otherwise fail OPEN (a typo that pins nothing, a provider
 * with no cap, a locale that mangles the key, a pattern that a lookalike host satisfies).
 */
class ConfigPolicyProviderKeysSpec extends AnyWordSpec with Matchers with EitherValues with BeforeAndAfterEach {

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get)

  private val savedLocale = Locale.getDefault

  override protected def afterEach(): Unit = {
    Locale.setDefault(savedLocale)
    super.afterEach()
  }

  private def rules(config: ProviderConfig, policy: ConfigPolicy, env: CatalogEnvironment): List[String] =
    ConfigPolicyEngine.check(config, policy, env).map(_.rule)

  private def openai(url: String): OpenAIConfig =
    OpenAIConfig.fromValues("gpt-4o", "k", None, url).value

  private def fromSection(section: String): ProviderConfig =
    Llm4sConfig
      .providerFrom(ConfigSource.string(s"llm4s { providers { provider = \"main\"\n main { $section } } }"))
      .value

  private val optedIn = ConfigPolicy.prodSafeDefaults
    .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
    .withAllowedModelPatterns("openai-compatible/.*")

  "the prod preset" should {

    "cap a provider that has no per-provider entry, so opting one in cannot leave it unbounded" in {
      val huge =
        OpenAICompatibleConfig.fromValues("m", "https://gateway.example/v1", contextWindow = Some(10000000)).value
      rules(huge, optedIn, CatalogEnvironment.Prod) should contain("maxContextWindow")

      val large =
        OpenAICompatibleConfig.fromValues("m", "https://gateway.example/v1", contextWindow = Some(1000000)).value
      rules(large, optedIn, CatalogEnvironment.Prod) should not contain "maxContextWindow"
    }

    "let a per-provider entry replace the fallback cap" in {
      val huge =
        OpenAICompatibleConfig.fromValues("m", "https://gateway.example/v1", contextWindow = Some(10000000)).value
      val policy = optedIn.withMaxContextWindow(CatalogEnvironment.Prod, "openai-compatible", 20000000)
      rules(huge, policy, CatalogEnvironment.Prod) should not contain "maxContextWindow"
    }

    "reject, for every provider it caps, a model whose native window is over that cap" in {
      // (section, the provider's cap): each model's registry window is asserted to exceed the cap first,
      // so the test cannot pass vacuously if the registry data moves.
      val overWindow = Seq(
        """provider = "openai", model = "o1", apiKey = "k""""                                         -> 128000,
        """provider = "azure", model = "o1", endpoint = "https://x.openai.azure.com", apiKey = "k"""" -> 128000,
        """provider = "anthropic", model = "claude-opus-4-6", apiKey = "k""""                         -> 200000,
        """provider = "gemini", model = "gemini-exp-1206", apiKey = "k""""                            -> 1048576,
        """provider = "deepseek", model = "deepseek-v3.2", apiKey = "k""""                            -> 131072
      )
      overWindow.foreach { case (section, cap) =>
        withClue(section) {
          val cfg = fromSection(section)
          cfg.contextWindow should be > cap
          ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) should contain(
            PolicyViolation("maxContextWindow", s"contextWindow ${cfg.contextWindow} exceeds $cap")
          )
        }
      }
    }
  }

  "a per-provider key" should {

    "fail closed when it names no provider, instead of silently pinning nothing" in {
      val evil = openai("https://evil.example/v1")
      val pinned = ConfigPolicy.prodSafeDefaults.withRequiredBaseUrlPattern(
        CatalogEnvironment.Prod,
        "opnai",
        "https://api\\.openai\\.com/v1"
      )
      val found = ConfigPolicyEngine.check(evil, pinned, CatalogEnvironment.Prod)
      found.map(_.rule) should contain("unknownProvider")
      found.map(_.message).mkString should include("opnai")

      val capped = ConfigPolicy.prodSafeDefaults.withMaxContextWindow(CatalogEnvironment.Prod, "opnai", 1000)
      rules(evil, capped, CatalogEnvironment.Prod) should contain("unknownProvider")
    }

    "not be reported when it names a registered provider or an allowed one" in {
      val registered = ConfigPolicy.prodSafeDefaults.withRequiredBaseUrlPattern(
        CatalogEnvironment.Prod,
        "ollama",
        "http://localhost:.*"
      )
      rules(
        openai("https://api.openai.com/v1"),
        registered,
        CatalogEnvironment.Prod
      ) should not contain "unknownProvider"

      val allowedOnly = ConfigPolicy.prodSafeDefaults
        .withAllowedProviders("openai", "acme-llm")
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "acme-llm", "https://acme\\.example/.*")
      rules(
        openai("https://api.openai.com/v1"),
        allowedOnly,
        CatalogEnvironment.Prod
      ) should not contain "unknownProvider"
    }

    "apply to the provider's canonical id when keyed by an alias" in {
      val pinned = ConfigPolicy.permissive.withRequiredBaseUrlPattern(
        CatalogEnvironment.Prod,
        "google",
        "https://generativelanguage\\.googleapis\\.com/.*"
      )
      val evil = fromSection(
        """provider = "gemini", model = "gemini-2.5-pro", apiKey = "k", baseUrl = "https://evil.example/v1""""
      )
      rules(evil, pinned, CatalogEnvironment.Prod) should contain("requiredBaseUrl")
      rules(evil, pinned, CatalogEnvironment.Prod) should not contain "unknownProvider"
    }

    "match whatever the default locale, which must not mangle `OpenAI` into `openaı`" in {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      val policy = ConfigPolicy.permissive
        .withAllowedProviders("OpenAI")
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "OpenAI", "https://api\\.openai\\.com/v1")
      rules(openai("https://api.openai.com/v1"), policy, CatalogEnvironment.Prod) shouldBe empty
      rules(openai("https://evil.example/v1"), policy, CatalogEnvironment.Prod) should contain("requiredBaseUrl")
    }

    "replace, not tighten, the environment-wide pin, so a loose provider pin weakens a strict global one" in {
      val policy = ConfigPolicy.permissive
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/v1")
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai", "https://.*")
      rules(openai("https://evil.example/v1"), policy, CatalogEnvironment.Prod) shouldBe empty
    }
  }

  "a base-URL pin" should {
    val attacks = Seq(
      "https://api.openai.com.evil.example/v1",
      "https://api.openai.com:evil@evil.example/",
      "https://api.openai.com./v1"
    )

    "reject every lookalike when it ends in `/.*`" in {
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/.*")
      rules(openai("https://api.openai.com/v1"), policy, CatalogEnvironment.Prod) shouldBe empty
      attacks.foreach(url =>
        withClue(url)(rules(openai(url), policy, CatalogEnvironment.Prod) should contain("requiredBaseUrl"))
      )
    }

    "be bypassed by every lookalike when it ends in a bare `.*`, which is why the docs say not to" in {
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com.*")
      attacks.foreach(url => withClue(url)(rules(openai(url), policy, CatalogEnvironment.Prod) shouldBe empty))
    }
  }

  "the prod preset's model patterns" should {
    "need an explicit suffix pattern to allow dated snapshots" in {
      val snapshot = OpenAIConfig.fromValues("gpt-4o-2024-08-06", "k", None, "https://api.openai.com/v1").value
      rules(snapshot, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) should contain("allowedModels")

      val widened = ConfigPolicy.prodSafeDefaults.withAllowedModelPatterns("openai/gpt-4o(-.*)?")
      rules(snapshot, widened, CatalogEnvironment.Prod) should not contain "allowedModels"
      rules(
        OpenAIConfig.fromValues("gpt-4o", "k", None, "https://api.openai.com/v1").value,
        widened,
        CatalogEnvironment.Prod
      ) should not contain "allowedModels"
    }
  }
}
