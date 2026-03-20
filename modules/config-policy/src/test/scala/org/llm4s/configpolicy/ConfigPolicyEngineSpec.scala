package org.llm4s.configpolicy

import org.llm4s.llmconnect.config.{ OllamaConfig, OpenAIConfig }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigPolicyEngineSpec extends AnyWordSpec with Matchers {

  "ConfigPolicyEngine.check" should {
    "pass for dev ollama config under dev policy" in {
      val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434")
      val violations = ConfigPolicyEngine.check(
        cfg,
        ConfigPolicy.devSandbox,
        CatalogEnvironment.Dev
      )
      violations shouldBe empty
    }

    "fail when provider is not in allowlist" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o", "test-key", None, "https://api.openai.com/v1")
      val policy = ConfigPolicy.permissive.withAllowedProviders("anthropic")
      val violations = ConfigPolicyEngine.check(cfg, policy, CatalogEnvironment.Prod)
      violations.map(_.rule) should contain ("allowedProviders")
    }
  }
}

