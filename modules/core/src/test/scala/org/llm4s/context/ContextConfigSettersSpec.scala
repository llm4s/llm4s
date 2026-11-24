package org.llm4s.context

import org.llm4s.types.HeadroomPercent
import org.llm4s.types.HeadroomPercent.{ Conservative, Light }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Each `ContextConfig` setter changes its own field and nothing else.
 *
 * Each setter is applied to `ContextConfig.default` and to a config with no field at its default, so a setter
 * that also resets another field to its default value is caught too.
 */
class ContextConfigSettersSpec extends AnyFlatSpec with Matchers {

  /** `ContextConfig.default`'s values, so each expected value below names only the field it changes. */
  private def defaults(
    headroomPercent: HeadroomPercent = HeadroomPercent.Standard,
    maxSemanticBlocks: Int = 5,
    enableDeterministicCompression: Boolean = true,
    enableLLMCompression: Boolean = true,
    summaryTokenTarget: Int = 400
  ): ContextConfig =
    ContextConfig(
      headroomPercent = headroomPercent,
      maxSemanticBlocks = maxSemanticBlocks,
      enableDeterministicCompression = enableDeterministicCompression,
      enableLLMCompression = enableLLMCompression,
      summaryTokenTarget = summaryTokenTarget
    )

  /** A config with every field different from `ContextConfig.default`. */
  private def changed(
    headroomPercent: HeadroomPercent = Conservative,
    maxSemanticBlocks: Int = 12,
    enableDeterministicCompression: Boolean = false,
    enableLLMCompression: Boolean = false,
    summaryTokenTarget: Int = 250
  ): ContextConfig =
    ContextConfig(
      headroomPercent = headroomPercent,
      maxSemanticBlocks = maxSemanticBlocks,
      enableDeterministicCompression = enableDeterministicCompression,
      enableLLMCompression = enableLLMCompression,
      summaryTokenTarget = summaryTokenTarget
    )

  private val base = ContextConfig.default

  "ContextConfig.default" should "equal the config the expected values are built from" in {
    base shouldBe defaults()
  }

  "ContextConfig setters" should "set the headroom percent" in {
    base.withHeadroomPercent(Light) shouldBe defaults(headroomPercent = Light)
    base.withHeadroomPercent(Conservative) shouldBe defaults(headroomPercent = Conservative)
    changed().withHeadroomPercent(Light) shouldBe changed(headroomPercent = Light)
  }

  it should "set the maximum number of semantic blocks" in {
    base.withMaxSemanticBlocks(12) shouldBe defaults(maxSemanticBlocks = 12)
    changed().withMaxSemanticBlocks(5) shouldBe changed(maxSemanticBlocks = 5)
  }

  it should "set the deterministic compression toggle" in {
    base.withEnableDeterministicCompression(false) shouldBe defaults(enableDeterministicCompression = false)
    changed().withEnableDeterministicCompression(true) shouldBe changed(enableDeterministicCompression = true)
  }

  it should "set the LLM compression toggle" in {
    base.withEnableLLMCompression(false) shouldBe defaults(enableLLMCompression = false)
    changed().withEnableLLMCompression(true) shouldBe changed(enableLLMCompression = true)
  }

  it should "set the summary token target" in {
    base.withSummaryTokenTarget(250) shouldBe defaults(summaryTokenTarget = 250)
    changed().withSummaryTokenTarget(400) shouldBe changed(summaryTokenTarget = 400)
  }
}
