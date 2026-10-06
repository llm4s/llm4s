package org.llm4s.configpolicy

import org.scalatest.EitherValues
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OllamaConfig, OpenAICompatibleConfig, OpenAIConfig }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigPolicyEngineSpec extends AnyWordSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get)

  "ConfigPolicyEngine.check" should {
    "pass for dev ollama config under dev policy" in {
      val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434").value
      val violations = ConfigPolicyEngine.check(
        cfg,
        ConfigPolicy.devSandbox,
        CatalogEnvironment.Dev
      )
      violations shouldBe empty
    }

    "allow the generic openai-compatible provider under the dev preset" in {
      val cfg = OpenAICompatibleConfig.fromValues("qwen", "http://localhost:8000/v1").value
      ConfigPolicyEngine.check(cfg, ConfigPolicy.devSandbox, CatalogEnvironment.Dev) shouldBe empty
    }

    "reject the generic openai-compatible provider under the prod preset unless explicitly allowed" in {
      val cfg = OpenAICompatibleConfig.fromValues("gpt-4o-mini", "https://gateway.example/v1").value
      ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) should contain(
        PolicyViolation("allowedProviders", "Provider 'openai-compatible' is not allowed")
      )

      val optedIn = ConfigPolicy.prodSafeDefaults
        .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
        .withAllowedModelPatterns("openai-compatible/gpt-4o-mini")
      ConfigPolicyEngine.check(cfg, optedIn, CatalogEnvironment.Prod) shouldBe empty
    }

    "fail when provider is not in allowlist" in {
      val cfg        = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://api.openai.com/v1").value
      val policy     = ConfigPolicy.permissive.withAllowedProviders("anthropic")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedProviders")
    }

    "pass when model matches an allowlisted pattern" in {
      val cfg        = OpenAIConfig.fromValues("gpt-4o-mini", "test-key", None, "https://api.openai.com/v1").value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o-mini")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations shouldBe empty
    }

    "fail when model does not match any pattern" in {
      val cfg        = OpenAIConfig.fromValues("gpt-3.5-turbo", "test-key", None, "https://api.openai.com/v1").value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o-mini")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedModels")
    }

    "fail when context window exceeds policy max for environment" in {
      val cfg        = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://api.openai.com/v1").value
      val policy     = ConfigPolicy.permissive.withMaxContextWindow(CatalogEnvironment.Prod, 1000)
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("maxContextWindow")
    }

    "fail when base URL does not match required pattern" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://example.com/v1").value
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com.*")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("requiredBaseUrl")
    }

    "pass when base URL matches required pattern" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://api.openai.com/v1").value
      val policy =
        ConfigPolicy.permissive.withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com.*")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations shouldBe empty
    }

    "surface invalid model regex in policy as a violation" in {
      val cfg        = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://api.openai.com/v1").value
      val policy     = ConfigPolicy.permissive.withAllowedModelPatterns("[invalid")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain("allowedModelPatterns")
    }

    "match model patterns against the whole value, so a pin is not a prefix" in {
      val mini   = OpenAIConfig.fromValues("gpt-4o-mini", "k", None, "https://api.openai.com/v1").value
      val policy = ConfigPolicy.permissive.withAllowedModelPatterns("openai/gpt-4o")
      ConfigPolicyEngine.check(mini, policy, CatalogEnvironment.Prod).map(_.rule) should contain("allowedModels")
      val exact = OpenAIConfig.fromValues("gpt-4o", "k", None, "https://api.openai.com/v1").value
      ConfigPolicyEngine.check(exact, policy, CatalogEnvironment.Prod) shouldBe empty
    }

    "not let a lookalike host satisfy an anchored base-URL pin" in {
      val policy = ConfigPolicy.permissive
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/v1")
      Seq(
        "https://api.openai.com/v1.evil.example",
        "https://api.openai.com.evil.example/v1",
        "https://evil.example/?u=https://api.openai.com/v1"
      ).foreach { url =>
        val cfg = OpenAIConfig.fromValues("gpt-4o", "k", None, url).value
        ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod).map(_.rule) should contain("requiredBaseUrl")
      }
    }

    "apply a per-provider base-URL pin in place of the environment-wide one" in {
      val policy = ConfigPolicy.permissive
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.openai\\.com/v1")
        .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "openai-compatible", "https://api\\.groq\\.com/openai/v1")
      val groq = OpenAICompatibleConfig.fromValues("m", "https://api.groq.com/openai/v1").value
      ConfigPolicyEngine.check(groq, policy, CatalogEnvironment.Prod) shouldBe empty
      val other = OpenAICompatibleConfig.fromValues("m", "https://api.openai.com/v1").value
      ConfigPolicyEngine.check(other, policy, CatalogEnvironment.Prod).map(_.rule) should contain("requiredBaseUrl")
      val openai = OpenAIConfig.fromValues("gpt-4o", "k", None, "https://api.openai.com/v1").value
      ConfigPolicyEngine.check(openai, policy, CatalogEnvironment.Prod) shouldBe empty
    }

    "let a per-provider context cap override the environment-wide one" in {
      val policy = ConfigPolicy.permissive
        .withMaxContextWindow(CatalogEnvironment.Prod, 1000)
        .withMaxContextWindow(CatalogEnvironment.Prod, "openai", 200000)
      val cfg = OpenAIConfig.fromValues("gpt-4o", "k", None, "https://api.openai.com/v1").value
      ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod) shouldBe empty
      val ollama = OllamaConfig.fromValues("llama3", "http://localhost:11434").value
      ConfigPolicyEngine.check(ollama, policy, CatalogEnvironment.Prod).map(_.rule) should contain("maxContextWindow")
    }

    "accept every model the prod preset allows at its native context window" in {
      val sections = Seq(
        """provider = "openai", model = "gpt-4o", apiKey = "k"""",
        """provider = "anthropic", model = "claude-3-5-sonnet-20241022", apiKey = "k"""",
        """provider = "gemini", model = "gemini-2.5-pro", apiKey = "k"""",
        """provider = "deepseek", model = "deepseek-chat", apiKey = "k""""
      )
      sections.foreach { section =>
        val cfg = org.llm4s.config.Llm4sConfig
          .providerFrom(
            pureconfig.ConfigSource.string(s"llm4s { providers { provider = \"main\"\n main { $section } } }")
          )
          .value
        withClue(section) {
          ConfigPolicyEngine.check(cfg, ConfigPolicy.prodSafeDefaults, CatalogEnvironment.Prod) shouldBe empty
        }
      }
    }

    "accept the documented Groq and xAI recipes under the dev preset" in {
      Seq("https://api.groq.com/openai/v1" -> 131072, "https://api.x.ai/v1" -> 500000).foreach { case (url, window) =>
        val cfg = OpenAICompatibleConfig.fromValues("m", url, contextWindow = Some(window)).value
        ConfigPolicyEngine.check(cfg, ConfigPolicy.devSandbox, CatalogEnvironment.Dev) shouldBe empty
      }
    }
  }
}
