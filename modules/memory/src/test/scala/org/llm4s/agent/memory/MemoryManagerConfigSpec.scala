package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The value semantics of [[MemoryManagerConfig]]: defaults, presets, `with*` setters, equality,
 * and the growth-safe shape (no public constructor or `copy`, #1847).
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

  "MemoryManagerConfig.apply" should "carry the defaults when given no arguments" in {
    val config = MemoryManagerConfig()

    config shouldBe MemoryManagerConfig.default
    config.autoExtractEntities shouldBe false
    config.defaultImportance shouldBe 0.5
    config.contextTokenBudget shouldBe MemoryManager.DefaultContextTokenBudget
    config.consolidationConfig shouldBe ConsolidationConfig.default
  }

  it should "default every option it is not given" in {
    val config = MemoryManagerConfig(defaultImportance = 0.7)

    config.defaultImportance shouldBe 0.7
    config.autoExtractEntities shouldBe false
    config.contextTokenBudget shouldBe MemoryManager.DefaultContextTokenBudget
    config.consolidationConfig shouldBe ConsolidationConfig.default
  }

  it should "set every option" in {
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

  "MemoryManagerConfig presets" should "differ only in automatic entity extraction" in {
    MemoryManagerConfig.testing shouldBe MemoryManagerConfig.default
    MemoryManagerConfig.fullFeatured.autoExtractEntities shouldBe true
    MemoryManagerConfig.fullFeatured.withAutoExtractEntities(false) shouldBe MemoryManagerConfig.default
  }

  "MemoryManagerConfig.withAutoExtractEntities" should "change only autoExtractEntities" in {
    val original = MemoryManagerConfig.default
    val modified = original.withAutoExtractEntities(true)

    modified.autoExtractEntities shouldBe true
    modified shouldBe MemoryManagerConfig(autoExtractEntities = true)
    modified.withAutoExtractEntities(false) shouldBe original
  }

  "MemoryManagerConfig.withDefaultImportance" should "change only defaultImportance" in {
    val modified = MemoryManagerConfig.default.withDefaultImportance(0.9)

    modified.defaultImportance shouldBe 0.9
    modified shouldBe MemoryManagerConfig(defaultImportance = 0.9)
  }

  "MemoryManagerConfig.withContextTokenBudget" should "change only contextTokenBudget" in {
    val modified = MemoryManagerConfig.default.withContextTokenBudget(500)

    modified.contextTokenBudget shouldBe 500
    modified shouldBe MemoryManagerConfig(contextTokenBudget = 500)
  }

  "MemoryManagerConfig.withConsolidationConfig" should "change only consolidationConfig" in {
    val custom   = ConsolidationConfig(maxMemoriesPerGroup = 100, strictMode = true)
    val modified = MemoryManagerConfig.default.withConsolidationConfig(custom)

    modified.consolidationConfig shouldBe custom
    modified shouldBe MemoryManagerConfig(consolidationConfig = custom)
  }

  "MemoryManagerConfig setters" should "chain, each keeping what the others set" in {
    val consolidation = ConsolidationConfig.strict
    val chained = MemoryManagerConfig.default
      .withAutoExtractEntities(true)
      .withDefaultImportance(0.8)
      .withContextTokenBudget(3000)
      .withConsolidationConfig(consolidation)

    chained shouldBe MemoryManagerConfig(
      autoExtractEntities = true,
      defaultImportance = 0.8,
      contextTokenBudget = 3000,
      consolidationConfig = consolidation
    )
  }

  "MemoryManagerConfig equals and hashCode" should "compare every option" in {
    val config1 = MemoryManagerConfig(autoExtractEntities = true, contextTokenBudget = 700)
    val config2 = MemoryManagerConfig(autoExtractEntities = true, contextTokenBudget = 700)

    config1 shouldBe config2
    config1.hashCode() shouldBe config2.hashCode()
    config1 should not be config1.withAutoExtractEntities(false)
    config1 should not be config1.withDefaultImportance(0.9)
    config1 should not be config1.withContextTokenBudget(701)
    config1 should not be config1.withConsolidationConfig(ConsolidationConfig.strict)
  }

  "MemoryManagerConfig.toString" should "be the case-class form the migration guide shows" in {
    MemoryManagerConfig.default.toString shouldBe "MemoryManagerConfig(false,0.5,2000,ConsolidationConfig(50,false))"
  }

  "MemoryManagerConfig" should "have no public constructor or copy, so it can gain options" in {
    assertDoesNotCompile("new MemoryManagerConfig(false, 0.5, 2000, ConsolidationConfig.default)")
    assertDoesNotCompile("MemoryManagerConfig.default.copy(defaultImportance = 0.9)")
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
