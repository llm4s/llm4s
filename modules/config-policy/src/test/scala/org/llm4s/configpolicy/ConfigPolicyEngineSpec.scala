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
  }
}
