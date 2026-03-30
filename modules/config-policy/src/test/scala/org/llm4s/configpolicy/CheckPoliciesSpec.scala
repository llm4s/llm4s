package org.llm4s.configpolicy

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class CheckPoliciesSpec extends AnyFunSpec with Matchers {

  describe("CheckPolicies argument parser") {
    it("should parse --env argument correctly") {
      val parsed = CheckPolicies.parseArgs(Array("--env=prod", "--preset=prod-safe", "--verbose"))
      parsed.env shouldBe "prod"
      parsed.preset shouldBe "prod-safe"
      parsed.verbose shouldBe true
    }

    it("should have valid preset definitions") {
      DefaultPolicies.listPresets should contain("prod-safe")
      DefaultPolicies.listPresets should contain("dev-sandbox")
      DefaultPolicies.listPresets should contain("staging-balanced")
    }
  }

  describe("Config snapshot extraction") {
    it("should handle all environment variable cases") {
      val env = Map(
        "LLM_PROVIDER"         -> "openai",
        "LLM_MODEL"            -> "gpt-4o-mini",
        "LLM_MAX_TOKENS"       -> "2048",
        "LLM_REASONING_BUDGET" -> "1000",
        "LLM_REGION"           -> "eastus",
        "OPENAI_BASE_URL"      -> "https://api.openai.com"
      )

      val snapshot = CheckPolicies.snapshotFromEnv(env.get)
      snapshot.provider shouldBe Some("openai")
      snapshot.model shouldBe Some("gpt-4o-mini")
      snapshot.maxTokens shouldBe Some(2048)
      snapshot.reasoningBudget shouldBe Some(1000)
      snapshot.region shouldBe Some("eastus")
      snapshot.baseUrl shouldBe Some("https://api.openai.com")
    }

    it("should handle missing optional fields") {
      val snapshot = CheckPolicies.snapshotFromEnv(_ => None)
      snapshot.provider shouldBe None
      snapshot.model shouldBe None
      snapshot.maxTokens shouldBe None
      snapshot.reasoningBudget shouldBe None
      snapshot.region shouldBe None
    }
  }

  describe("Policy evaluation integration") {
    it("should work end-to-end for prod environment") {
      val env = Map(
        "LLM_PROVIDER"         -> "openai",
        "LLM_MODEL"            -> "gpt-4o-mini",
        "LLM_MAX_TOKENS"       -> "1024",
        "LLM_REASONING_BUDGET" -> "5000",
        "LLM_REGION"           -> "eastus"
      )

      val code = CheckPolicies.run(CheckPolicies.Options(env = "prod", preset = "prod-safe"), env.get)
      code shouldBe 0
    }

    it("should detect multiple violations in one check") {
      val env = Map(
        "LLM_PROVIDER"         -> "ollama",
        "LLM_MODEL"            -> "unknown-model",
        "LLM_MAX_TOKENS"       -> "50000",
        "LLM_REASONING_BUDGET" -> "999999"
      )

      val code = CheckPolicies.run(CheckPolicies.Options(env = "prod", preset = "prod-safe"), env.get)
      code shouldBe 1
    }
  }

  describe("Report formatting") {
    it("should produce valid output structure") {
      val result = PolicyEvaluationResult(List(PolicyPass("test-policy")))
      val report = ConfigPolicyRunner.formatReport(result)
      report should include("CONFIG POLICY CHECK")
      report should include("Summary:")
    }

    it("should show correct status codes in output") {
      val env = Map(
        "LLM_PROVIDER"         -> "openai",
        "LLM_MODEL"            -> "gpt-4o-mini",
        "LLM_MAX_TOKENS"       -> "1000",
        "LLM_REASONING_BUDGET" -> "1000",
        "LLM_REGION"           -> "eastus"
      )

      CheckPolicies.run(CheckPolicies.Options(env = "prod", preset = "prod-safe"), env.get) shouldBe 0
    }

    it("should show failure status when policies fail") {
      val env = Map(
        "LLM_PROVIDER"         -> "deepseek",
        "LLM_MODEL"            -> "foo",
        "LLM_MAX_TOKENS"       -> "100000",
        "LLM_REASONING_BUDGET" -> "200000",
        "LLM_REGION"           -> "moon"
      )

      CheckPolicies.run(CheckPolicies.Options(env = "prod", preset = "prod-safe"), env.get) shouldBe 1
    }
  }
}
