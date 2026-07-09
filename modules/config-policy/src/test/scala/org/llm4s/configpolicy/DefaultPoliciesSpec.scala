package org.llm4s.configpolicy

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class DefaultPoliciesSpec extends AnyFunSpec with Matchers {

  private val prodGood = ConfigSnapshot(
    provider = Some("openai"),
    model = Some("gpt-4o-mini"),
    maxTokens = Some(2048),
    reasoningBudget = Some(9000),
    region = Some("eastus"),
    baseUrl = None
  )

  describe("DefaultPolicies.productionSafeDefaults") {
    it("should enforce production-safe configuration") {
      val result = ConfigPolicyRunner.evaluate(prodGood, "prod", DefaultPolicies.productionSafeDefaults)
      result.passed shouldBe true
    }

    it("should reject unsafe providers in production") {
      val result = ConfigPolicyRunner.evaluate(
        prodGood.copy(provider = Some("ollama")),
        "prod",
        DefaultPolicies.productionSafeDefaults
      )
      result.passed shouldBe false
    }

    it("should require region in production") {
      val result =
        ConfigPolicyRunner.evaluate(prodGood.copy(region = None), "prod", DefaultPolicies.productionSafeDefaults)
      result.failures.map(_.policyName) should contain("required-region")
    }

    it("should enforce token limits in production") {
      val result = ConfigPolicyRunner.evaluate(
        prodGood.copy(maxTokens = Some(50000)),
        "prod",
        DefaultPolicies.productionSafeDefaults
      )
      result.failures.map(_.policyName) should contain("max-tokens-limit")
    }
  }

  describe("DefaultPolicies.devSandboxDefaults") {
    it("should allow any provider in dev") {
      val cfg    = prodGood.copy(provider = Some("ollama"), maxTokens = Some(10000))
      val result = ConfigPolicyRunner.evaluate(cfg, "dev", DefaultPolicies.devSandboxDefaults)
      result.passed shouldBe true
    }

    it("should allow higher token limits in dev") {
      val result =
        ConfigPolicyRunner.evaluate(prodGood.copy(maxTokens = Some(12000)), "dev", DefaultPolicies.devSandboxDefaults)
      result.passed shouldBe true
    }
  }

  describe("DefaultPolicies.stagingBalancedDefaults") {
    it("should enforce staging policies") {
      val cfg    = prodGood.copy(provider = Some("gemini"), model = Some("gemini-1.5-pro"), maxTokens = Some(4096))
      val result = ConfigPolicyRunner.evaluate(cfg, "staging", DefaultPolicies.stagingBalancedDefaults)
      result.passed shouldBe true
    }

    it("should reject unsafe models in staging") {
      val cfg    = prodGood.copy(provider = Some("openai"), model = Some("gpt-4.1"))
      val result = ConfigPolicyRunner.evaluate(cfg, "staging", DefaultPolicies.stagingBalancedDefaults)
      result.passed shouldBe false
    }
  }

  describe("DefaultPolicies.costControlledDefaults") {
    it("should enforce cost limits across environments") {
      val result = ConfigPolicyRunner.evaluate(prodGood, "prod", DefaultPolicies.costControlledDefaults)
      result.passed shouldBe true
    }

    it("should reject excessive token usage") {
      val result = ConfigPolicyRunner.evaluate(
        prodGood.copy(maxTokens = Some(99999)),
        "prod",
        DefaultPolicies.costControlledDefaults
      )
      result.passed shouldBe false
    }
  }

  describe("DefaultPolicies.complianceDefaults") {
    it("should enforce data residency") {
      val result = ConfigPolicyRunner.evaluate(
        prodGood.copy(region = Some("eastasia")),
        "prod",
        DefaultPolicies.complianceDefaults
      )
      result.passed shouldBe false
    }

    it("should allow safe providers only") {
      val result = ConfigPolicyRunner.evaluate(
        prodGood.copy(provider = Some("deepseek")),
        "prod",
        DefaultPolicies.complianceDefaults
      )
      result.passed shouldBe false
    }
  }

  describe("DefaultPolicies.getPreset") {
    it("should return productionSafeDefaults for 'prod-safe'") {
      DefaultPolicies.getPreset("prod-safe") shouldBe Some(DefaultPolicies.productionSafeDefaults)
    }

    it("should return devSandboxDefaults for 'dev-sandbox'") {
      DefaultPolicies.getPreset("dev-sandbox") shouldBe Some(DefaultPolicies.devSandboxDefaults)
    }

    it("should return stagingBalancedDefaults for 'staging-balanced'") {
      DefaultPolicies.getPreset("staging-balanced") shouldBe Some(DefaultPolicies.stagingBalancedDefaults)
    }

    it("should return None for unknown preset") {
      DefaultPolicies.getPreset("unknown") shouldBe None
    }

    it("should support 'all' preset combining all defaults") {
      DefaultPolicies.getPreset("all").isDefined shouldBe true
      DefaultPolicies.getPreset("all").get.nonEmpty shouldBe true
    }
  }

  describe("DefaultPolicies.listPresets") {
    it("should contain all available preset names") {
      (DefaultPolicies.listPresets should contain).allOf(
        "prod-safe",
        "dev-sandbox",
        "staging-balanced",
        "cost-controlled",
        "compliance",
        "all"
      )
    }

    it("should not be empty") {
      DefaultPolicies.listPresets.nonEmpty shouldBe true
    }
  }
}
