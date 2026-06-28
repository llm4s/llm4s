package org.llm4s.configpolicy

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class EnvConfigPolicySpec extends AnyFunSpec with Matchers {

  private val base = ConfigSnapshot(
    provider = Some("openai"),
    model = Some("gpt-4o-mini"),
    maxTokens = Some(2048),
    reasoningBudget = Some(5000),
    region = Some("eastus"),
    baseUrl = None
  )

  describe("PolicyBuilder.allowedProviders") {
    it("should pass when provider is in allowed list") {
      val p = PolicyBuilder.allowedProviders(Set("openai", "azure"), Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("allowed-providers")
    }

    it("should fail when provider is not in allowed list") {
      val p = PolicyBuilder.allowedProviders(Set("azure"), Set("prod"))
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }

    it("should skip when environment doesn't match") {
      val p = PolicyBuilder.allowedProviders(Set("azure"), Set("dev"))
      p.evaluate(base, "prod") shouldBe PolicySkip("allowed-providers")
    }
  }

  describe("PolicyBuilder.allowedModels") {
    it("should pass when model is in allowed list") {
      val p = PolicyBuilder.allowedModels(Set("gpt-4o-mini"), Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("allowed-models")
    }

    it("should fail when model is not in allowed list") {
      val p = PolicyBuilder.allowedModels(Set("claude-3-5-sonnet"), Set("prod"))
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }
  }

  describe("PolicyBuilder.maxTokensLimit") {
    it("should pass when tokens are within limit") {
      val p = PolicyBuilder.maxTokensLimit(4096, Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("max-tokens-limit")
    }

    it("should fail when tokens exceed limit") {
      val p = PolicyBuilder.maxTokensLimit(1000, Set("prod"))
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }

    it("should warn when maxTokens is not set") {
      val p = PolicyBuilder.maxTokensLimit(4096, Set("prod"))
      p.evaluate(base.copy(maxTokens = None), "prod").isInstanceOf[PolicyWarn] shouldBe true
    }
  }

  describe("PolicyBuilder.requiredRegion") {
    it("should pass when region is set (no specific regions)") {
      val p = PolicyBuilder.requiredRegion(envs = Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("required-region")
    }

    it("should fail when region is not set but required") {
      val p = PolicyBuilder.requiredRegion(envs = Set("prod"))
      p.evaluate(base.copy(region = None), "prod").isInstanceOf[PolicyFail] shouldBe true
    }

    it("should pass when region matches allowed list") {
      val p = PolicyBuilder.requiredRegion(Set("eastus", "westeurope"), Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("required-region")
    }

    it("should fail when region is not in allowed list") {
      val p = PolicyBuilder.requiredRegion(Set("westeurope"), Set("prod"))
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }
  }

  describe("PolicyBuilder.reasoningBudgetLimit") {
    it("should pass when budget is within limit") {
      val p = PolicyBuilder.reasoningBudgetLimit(8000, Set("prod"))
      p.evaluate(base, "prod") shouldBe PolicyPass("reasoning-budget-limit")
    }

    it("should fail when budget exceeds limit") {
      val p = PolicyBuilder.reasoningBudgetLimit(1000, Set("prod"))
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }

    it("should pass when budget is not set") {
      val p = PolicyBuilder.reasoningBudgetLimit(1000, Set("prod"))
      p.evaluate(base.copy(reasoningBudget = None), "prod").isInstanceOf[PolicyPass] shouldBe true
    }
  }

  describe("PolicyBuilder.customPolicy") {
    it("should allow custom evaluation logic") {
      val p = PolicyBuilder.customPolicy("base-url-required", Set("prod")) { (cfg, _) =>
        if (cfg.baseUrl.exists(_.nonEmpty)) Right("ok") else Left("baseUrl required")
      }

      p.evaluate(base.copy(baseUrl = Some("https://api.example.com")), "prod") shouldBe PolicyPass(
        "base-url-required",
        "ok"
      )
      p.evaluate(base, "prod").isInstanceOf[PolicyFail] shouldBe true
    }
  }
}
