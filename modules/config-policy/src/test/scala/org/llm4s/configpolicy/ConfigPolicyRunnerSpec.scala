package org.llm4s.configpolicy

import org.llm4s.llmconnect.config._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConfigPolicyRunnerSpec extends AnyWordSpec with Matchers {

  "ConfigPolicyRunner.providerName" should {
    "return openai for OpenAIConfig" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o", "sk-x", None, "https://api.openai.com/v1")
      ConfigPolicyRunner.providerName(cfg) shouldBe "openai"
    }
    "return ollama for OllamaConfig" in {
      val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434")
      ConfigPolicyRunner.providerName(cfg) shouldBe "ollama"
    }
  }

  "ConfigPolicyRunner.check" should {
    "pass when config matches devSandbox policy (ollama allowed)" in {
      val cfg = OllamaConfig.fromValues("llama3", "http://localhost:11434")
      val violations = ConfigPolicyRunner.check(cfg, ConfigPolicy.devSandbox)
      violations shouldBe empty
    }
    "fail when provider not in allowed list" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o", "sk-x", None, "https://api.openai.com/v1")
      val policy = ConfigPolicy.permissive.withAllowedProviders("anthropic", "ollama")
      val violations = ConfigPolicyRunner.check(cfg, policy)
      violations should have size 1
      violations.head.rule shouldBe "allowedProviders"
    }
    "fail when contextWindow exceeds max" in {
      val cfg =
        OllamaConfig("llama3", "http://localhost:11434", contextWindow = 200000, reserveCompletion = 4096)
      val policy = ConfigPolicy.devSandbox
      val violations = ConfigPolicyRunner.check(cfg, policy)
      violations should not be empty
      violations.exists(_.rule == "maxContextWindow") shouldBe true
    }
    "pass when model pattern matches" in {
      val cfg = OpenAIConfig.fromValues("gpt-4o-mini", "sk-x", None, "https://api.openai.com/v1")
      val policy = ConfigPolicy.prodSafeDefaults
      val violations = ConfigPolicyRunner.check(cfg, policy)
      violations.filter(_.rule == "allowedModels") shouldBe empty
    }
  }

  "ConfigPolicy.preset" should {
    "return Some for known presets" in {
      ConfigPolicy.preset("dev") shouldBe Some(ConfigPolicy.devSandbox)
      ConfigPolicy.preset("prod") shouldBe Some(ConfigPolicy.prodSafeDefaults)
      ConfigPolicy.preset("permissive") shouldBe Some(ConfigPolicy.permissive)
    }
    "return None for unknown" in {
      ConfigPolicy.preset("unknown") shouldBe None
    }
  }
}
