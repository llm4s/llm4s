package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The value semantics of [[MemoryManagerConfig]]: defaults, presets, `copy`, equality.
 * What each option does to a manager is in [[MemoryManagerConfigBehaviourSpec]].
 */
class MemoryManagerConfigSpec extends AnyFlatSpec with Matchers {

  "MemoryManagerConfig.default" should "have the documented defaults" in {
    val config = MemoryManagerConfig.default

    config.autoExtractEntities shouldBe false
    config.defaultImportance shouldBe 0.5
    config.contextTokenBudget shouldBe 2000
    config.contextTokenBudget shouldBe MemoryManager.DefaultContextTokenBudget
    config.consolidationConfig shouldBe ConsolidationConfig.default
  }

  it should "equal a configuration built with no arguments, through apply or the constructor" in {
    MemoryManagerConfig() shouldBe MemoryManagerConfig.default
    new MemoryManagerConfig() shouldBe MemoryManagerConfig.default
  }

  "MemoryManagerConfig presets" should "differ only in automatic entity extraction" in {
    MemoryManagerConfig.testing.autoExtractEntities shouldBe false
    MemoryManagerConfig.fullFeatured.autoExtractEntities shouldBe true
    MemoryManagerConfig.fullFeatured.copy(autoExtractEntities = false) shouldBe MemoryManagerConfig.default
  }

  "MemoryManagerConfig.apply" should "set every option" in {
    val consolidation = ConsolidationConfig(maxMemoriesPerGroup = 100, strictMode = true)
    val config = MemoryManagerConfig(
      autoExtractEntities = true,
      defaultImportance = 0.8,
      contextTokenBudget = 3000,
      consolidationConfig = consolidation
    )

    config.autoExtractEntities shouldBe true
    config.defaultImportance shouldBe 0.8
    config.contextTokenBudget shouldBe 3000
    config.consolidationConfig shouldBe consolidation
  }

  "MemoryManagerConfig.copy" should "change only the options it is given" in {
    val original = MemoryManagerConfig.default
    val modified = original.copy(contextTokenBudget = 500)

    modified.contextTokenBudget shouldBe 500
    modified.autoExtractEntities shouldBe original.autoExtractEntities
    modified.defaultImportance shouldBe original.defaultImportance
    modified.consolidationConfig shouldBe original.consolidationConfig
  }

  it should "replace the ConsolidationConfig" in {
    val custom   = ConsolidationConfig(maxMemoriesPerGroup = 100, strictMode = true)
    val modified = MemoryManagerConfig.default.copy(consolidationConfig = custom)
    modified.consolidationConfig shouldBe custom
  }

  "MemoryManagerConfig equals and hashCode" should "compare every option" in {
    val config1 = MemoryManagerConfig(autoExtractEntities = true, contextTokenBudget = 700)
    val config2 = MemoryManagerConfig(autoExtractEntities = true, contextTokenBudget = 700)

    config1 shouldBe config2
    config1.hashCode() shouldBe config2.hashCode()
    config1 should not be config1.copy(autoExtractEntities = false)
    config1 should not be config1.copy(defaultImportance = 0.9)
    config1 should not be config1.copy(contextTokenBudget = 701)
    config1 should not be config1.copy(consolidationConfig = ConsolidationConfig.strict)
  }

  "MemoryManagerConfig.toString" should "name every option" in {
    val text = MemoryManagerConfig.default.toString
    text should include("autoExtractEntities=false")
    text should include("defaultImportance=0.5")
    text should include("contextTokenBudget=2000")
    text should include("consolidationConfig=")
  }

  "ConsolidationConfig" should "provide default and strict presets" in {
    val default = ConsolidationConfig.default
    default.maxMemoriesPerGroup shouldBe 50
    default.strictMode shouldBe false

    val strict = ConsolidationConfig.strict
    strict.maxMemoriesPerGroup shouldBe 50
    strict.strictMode shouldBe true
  }
}
