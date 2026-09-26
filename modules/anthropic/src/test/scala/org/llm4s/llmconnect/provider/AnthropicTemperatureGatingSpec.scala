package org.llm4s.llmconnect.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

/**
 * Tests for [[AnthropicClient.rejectsSamplingTemperature]].
 *
 * anthropic-java 2.42 deprecated `temperature`: models released after Claude Opus 4.6
 * reject any value other than 1.0 with a 400, so the client must omit the parameter for
 * those models (Claude Opus 4.7+ and any Opus 5+) while still sending it for models that
 * support it. See PR #1072 review feedback.
 */
class AnthropicTemperatureGatingSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  private val rejecting = Table(
    "model",
    "claude-opus-4-7",
    "claude-opus-4-8",
    "claude-opus-4-8-20260101",
    "anthropic/claude-opus-4-8",
    "claude-opus-4-10",       // 4.10 > 4.6
    "claude-opus-5",          // major bump
    "claude-opus-5-20260101", // major bump with date suffix
    "claude-opus-10-2"        // double-digit major
  )

  private val accepting = Table(
    "model",
    "claude-opus-4-6", // 4.6 still supports temperature ("after Opus 4.6")
    "claude-opus-4-1", // older minor
    "claude-opus-4-1-20250805",
    "claude-opus-4-20250514", // Opus 4.0 with date suffix, NOT version 4.20250514
    "claude-3-opus-20240229", // legacy naming: "opus-<date>", not a high version
    "claude-sonnet-4-6",      // non-Opus models are not name-gated here
    "claude-haiku-4-5",
    "claude-opus-latest" // no parseable version
  )

  "rejectsSamplingTemperature" should "be true for Opus models that dropped sampling support" in {
    forAll(rejecting)(model => AnthropicClient.rejectsSamplingTemperature(model) shouldBe true)
  }

  it should "be false for Opus 4.6 and earlier, date-suffixed base versions, and other models" in {
    forAll(accepting)(model => AnthropicClient.rejectsSamplingTemperature(model) shouldBe false)
  }
}
