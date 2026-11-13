package org.llm4s.portable

import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.llm4s.toolapi.ToolExecutionConfig
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/**
 * Entry points of core's files that lean on the JDK, reached so the Scala.js and Scala Native linkers check them.
 * This file holds the probes that link and pass on all three platforms. Those that do not live in the partially
 * shared test directories, so each platform links only what it can: `js-jvm` (not Native), `jvm-native` (not
 * Scala.js) and `jvm` (neither). The design record's inventory is built from these four files.
 */
class InventoryProbeSpec extends AnyFlatSpec with Matchers {

  "ToolExecutionConfig" should "build with a timeout" in {
    ToolExecutionConfig.default.withTimeout(5.seconds).timeout shouldBe Some(5.seconds)
  }

  "UsageSummary" should "add usage per model (BigDecimal cost)" in {
    val summary =
      UsageSummary().add("m", TokenUsage(promptTokens = 1, completionTokens = 2, totalTokens = 3), Some(0.5))
    summary.inputTokens shouldBe 1
    summary.totalCost shouldBe BigDecimal(0.5)
  }

  "ApiKey" should "register the key for redaction and hide it from toString (Base64, atomics)" in {
    ApiKey("sk-portable-spike-0123456789abcdef").toString shouldBe "***"
  }
}
