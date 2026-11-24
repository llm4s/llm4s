package org.llm4s.configpolicy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path, Paths }

/**
 * The two config-policy CLIs end to end, as CI's Config Policy Check job used to run them with `runMain`:
 * `CheckPolicies` against the committed smoke config, and `EnvCheckPolicies` with each preset against the `LLM_*`
 * values that preset allows. The environment is passed explicitly, so the result does not depend on the variables of
 * whoever runs the tests.
 */
class ConfigPolicyCiSmokeSpec extends AnyWordSpec with Matchers {

  private val SmokeConfig = "config/examples/application-policy-smoke.conf"

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get(System.getProperty("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(dir => Files.exists(dir.resolve(SmokeConfig)))
      .getOrElse(fail(s"$SmokeConfig was not found above ${System.getProperty("user.dir")}"))

  "CheckPolicies" should {
    "pass the dev policy on the committed smoke config (keyless Ollama)" in {
      CheckPolicies.run(Array("--env=dev", "--config", repoRoot.resolve(SmokeConfig).toString)) shouldBe 0
    }

    "reject an unknown environment with exit code 2" in {
      CheckPolicies.run(Array("--env=nowhere")) shouldBe 2
    }
  }

  "EnvCheckPolicies" should {
    def check(env: String, preset: String, vars: (String, String)*): Int =
      EnvCheckPolicies.run(EnvCheckPolicies.Options(env = env, preset = preset), vars.toMap.get)

    "pass the prod-safe preset" in {
      check(
        "prod",
        "prod-safe",
        "LLM_PROVIDER"         -> "openai",
        "LLM_MODEL"            -> "gpt-5.6-luna",
        "LLM_MAX_TOKENS"       -> "2048",
        "LLM_REASONING_BUDGET" -> "5000",
        "LLM_REGION"           -> "eastus"
      ) shouldBe 0
    }

    "pass the staging-balanced preset" in {
      check(
        "staging",
        "staging-balanced",
        "LLM_PROVIDER"         -> "gemini",
        "LLM_MODEL"            -> "gemini-3.8-flash",
        "LLM_MAX_TOKENS"       -> "4096",
        "LLM_REASONING_BUDGET" -> "8000",
        "LLM_REGION"           -> "westeurope"
      ) shouldBe 0
    }

    "pass the dev-sandbox preset" in {
      check(
        "dev",
        "dev-sandbox",
        "LLM_PROVIDER"         -> "ollama",
        "LLM_MODEL"            -> "qwen2.5:0.5b",
        "LLM_MAX_TOKENS"       -> "12000",
        "LLM_REASONING_BUDGET" -> "30000",
        "LLM_REGION"           -> "eastus"
      ) shouldBe 0
    }
  }
}
