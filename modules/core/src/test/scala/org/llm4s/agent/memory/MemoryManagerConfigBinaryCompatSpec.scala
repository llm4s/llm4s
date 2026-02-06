package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for binary compatibility of MemoryManagerConfig.
 *
 * This spec verifies that the old 5-parameter constructor is still available
 * through the companion object apply method, maintaining backward compatibility.
 */
class MemoryManagerConfigBinaryCompatSpec extends AnyFlatSpec with Matchers {

  "MemoryManagerConfig companion apply" should "support old 5-parameter signature" in {
    // This simulates how old compiled code (pre-consolidationConfig) would call the constructor
    val config = MemoryManagerConfig.apply(
      autoRecordMessages = true,
      autoExtractEntities = false,
      defaultImportance = 0.5,
      contextTokenBudget = 2000,
      consolidationEnabled = true
    )

    config.autoRecordMessages shouldBe true
    config.autoExtractEntities shouldBe false
    config.defaultImportance shouldBe 0.5
    config.contextTokenBudget shouldBe 2000
    config.consolidationEnabled shouldBe true
    // Should use default ConsolidationConfig
    config.consolidationConfig shouldBe ConsolidationConfig.default
  }

  it should "use default ConsolidationConfig when not specified" in {
    val config = MemoryManagerConfig(
      autoRecordMessages = false,
      autoExtractEntities = true,
      defaultImportance = 0.8,
      contextTokenBudget = 3000,
      consolidationEnabled = true
    )

    config.consolidationConfig.maxMemoriesPerGroup shouldBe 50
    config.consolidationConfig.strictMode shouldBe false
  }

  it should "support new 6-parameter signature with custom ConsolidationConfig" in {
    val customConfig = ConsolidationConfig(maxMemoriesPerGroup = 100, strictMode = true)
    val config = MemoryManagerConfig(
      autoRecordMessages = true,
      autoExtractEntities = false,
      defaultImportance = 0.5,
      contextTokenBudget = 2000,
      consolidationEnabled = true,
      consolidationConfig = customConfig
    )

    config.consolidationConfig.maxMemoriesPerGroup shouldBe 100
    config.consolidationConfig.strictMode shouldBe true
  }

  "ConsolidationConfig" should "provide default and strict presets" in {
    val default = ConsolidationConfig.default
    default.maxMemoriesPerGroup shouldBe 50
    default.strictMode shouldBe false

    val strict = ConsolidationConfig.strict
    strict.maxMemoriesPerGroup shouldBe 50
    strict.strictMode shouldBe true
  }

  "MemoryManagerConfig.default" should "have sensible defaults including ConsolidationConfig" in {
    val config = MemoryManagerConfig.default
    
    config.autoRecordMessages shouldBe true
    config.autoExtractEntities shouldBe false
    config.defaultImportance shouldBe 0.5
    config.contextTokenBudget shouldBe 2000
    config.consolidationEnabled shouldBe false
    config.consolidationConfig shouldBe ConsolidationConfig.default
  }
}
