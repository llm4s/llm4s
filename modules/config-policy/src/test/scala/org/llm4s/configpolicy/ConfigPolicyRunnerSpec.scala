package org.llm4s.configpolicy

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class ConfigPolicyRunnerSpec extends AnyFunSpec with Matchers {

  private val sample = ConfigSnapshot(
    provider = Some("openai"),
    model = Some("gpt-4o-mini"),
    maxTokens = Some(1024),
    reasoningBudget = Some(500),
    region = Some("eastus"),
    baseUrl = None
  )

  describe("ConfigPolicyRunner") {
    it("should evaluate all policies and collect results") {
      val policies = List(
        PolicyBuilder.allowedProviders(Set("openai")),
        PolicyBuilder.maxTokensLimit(2048)
      )

      val result = ConfigPolicyRunner.evaluate(sample, "prod", policies)
      result.results.size shouldBe 2
      result.passed shouldBe true
    }

    it("should detect policy failures") {
      val result = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(100)))
      result.failures.size shouldBe 1
      result.passed shouldBe false
    }

    it("should distinguish between failures and warnings") {
      val config = sample.copy(maxTokens = None)
      val result = ConfigPolicyRunner.evaluate(config, "prod", List(PolicyBuilder.maxTokensLimit(100)))
      result.failures.size shouldBe 0
      result.warnings.size shouldBe 1
    }

    it("should report passed correctly") {
      val failResult = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(100)))
      val passResult = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(4096)))

      failResult.passed shouldBe false
      passResult.passed shouldBe true
    }

    it("should format report properly") {
      val result = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(4096)))
      val report = ConfigPolicyRunner.formatReport(result)
      report should include("CONFIG POLICY CHECK: PASS")
      report should include("Summary:")
    }

    it("should format report with failures") {
      val result = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(100)))
      val report = ConfigPolicyRunner.formatReport(result)
      report should include("CONFIG POLICY CHECK: FAIL")
      report should include("[FAIL]")
    }

    it("should include details in verbose mode") {
      val result = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(4096)))
      val report = ConfigPolicyRunner.formatReport(result, verbose = true)
      report should include("[PASS]")
    }

    it("should skip policies for different environments") {
      val result = ConfigPolicyRunner.evaluate(sample, "prod", List(PolicyBuilder.maxTokensLimit(4096, Set("dev"))))
      result.skipped.size shouldBe 1
    }
  }
}
