package org.llm4s.agent

import org.llm4s.agent.PruningStrategy.AdaptiveWindowing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for PruningStrategy.AdaptiveWindowing: the size tiers, the cost adjustment, the 1000-token floor,
 * the explanation and the validation of its parameters.
 */
class AdaptiveWindowingSpec extends AnyFlatSpec with Matchers {

  // gpt-4o's prices: $5 and $15 per 1M tokens, so input is a quarter of the combined price
  private val inputPrice  = 5e-6
  private val outputPrice = 1.5e-5

  // No field at its default, so a setter that resets another field shows up
  private val priced = AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice), 0.3)

  private def windowFor(contextWindowSize: Int): Int =
    AdaptiveWindowing(contextWindowSize).calculateOptimalWindow

  // ==========================================================================
  // Size tiers
  // ==========================================================================

  "AdaptiveWindowing.calculateOptimalWindow" should "use 60% of a window up to 32K tokens" in {
    windowFor(8_000) shouldBe 4_800
    windowFor(32_000) shouldBe 19_200
  }

  it should "use 70% of a window from 32,001 to 100K tokens" in {
    windowFor(32_001) shouldBe 22_400
    windowFor(100_000) shouldBe 70_000
  }

  it should "use 75% of a window from 100,001 to 200K tokens" in {
    windowFor(100_001) shouldBe 75_000
    windowFor(128_000) shouldBe 96_000
    windowFor(200_000) shouldBe 150_000
  }

  it should "use 80% of a window above 200K tokens" in {
    windowFor(200_001) shouldBe 160_000
    windowFor(1_000_000) shouldBe 800_000
  }

  // ==========================================================================
  // Cost adjustment
  // ==========================================================================

  it should "shrink the window by input's share of the price, times the sensitivity, times 0.2" in {
    // 128000 * 0.75 * (1 - 0.25 * sensitivity * 0.2)
    AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice)).calculateOptimalWindow shouldBe 93_600
    AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice), 0.0).calculateOptimalWindow shouldBe 96_000
    AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice), 1.0).calculateOptimalWindow shouldBe 91_200
  }

  it should "shrink the window more when input is the larger share of the price" in {
    // Prices swapped: input is three quarters, 128000 * 0.75 * (1 - 0.75 * 0.5 * 0.2)
    AdaptiveWindowing(128_000, Some(outputPrice), Some(inputPrice)).calculateOptimalWindow shouldBe 88_800
  }

  it should "not adjust for cost unless both prices are given and positive" in {
    AdaptiveWindowing(128_000, Some(inputPrice), None, 1.0).calculateOptimalWindow shouldBe 96_000
    AdaptiveWindowing(128_000, None, Some(outputPrice), 1.0).calculateOptimalWindow shouldBe 96_000
    AdaptiveWindowing(128_000, Some(0.0), Some(outputPrice), 1.0).calculateOptimalWindow shouldBe 96_000
    AdaptiveWindowing(128_000, Some(inputPrice), Some(0.0), 1.0).calculateOptimalWindow shouldBe 96_000
    AdaptiveWindowing(128_000, Some(-inputPrice), Some(outputPrice), 1.0).calculateOptimalWindow shouldBe 96_000
  }

  // ==========================================================================
  // Floor
  // ==========================================================================

  it should "never return fewer than 1000 tokens" in {
    windowFor(1_500) shouldBe 1_000
  }

  it should "return the 1000-token floor even when the model's window is smaller" in {
    // Current behaviour, pinned: the budget is larger than the window itself
    windowFor(500) shouldBe 1_000
  }

  // ==========================================================================
  // Explanation
  // ==========================================================================

  "AdaptiveWindowing.explanation" should "give the budget, the share used and the model tier" in {
    AdaptiveWindowing(8_000).explanation shouldBe "AdaptiveWindowing: using 4800 tokens (60% of 8K small model)"
    AdaptiveWindowing(100_000).explanation shouldBe "AdaptiveWindowing: using 70000 tokens (70% of 100K medium model)"
    AdaptiveWindowing(128_000).explanation shouldBe "AdaptiveWindowing: using 96000 tokens (75% of 128K large model)"
  }

  it should "round the share down after the cost adjustment" in {
    AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice)).explanation shouldBe
      "AdaptiveWindowing: using 93600 tokens (73% of 128K large model)"
  }

  it should "call a window above 200K tokens large" in {
    AdaptiveWindowing(1_000_000).explanation shouldBe
      "AdaptiveWindowing: using 800000 tokens (80% of 1000K large model)"
  }

  it should "be what toString returns" in {
    priced.toString shouldBe priced.explanation
  }

  // ==========================================================================
  // Validation
  // ==========================================================================

  "AdaptiveWindowing" should "reject a context window size that is not positive" in {
    val zero = intercept[IllegalArgumentException](AdaptiveWindowing(0))
    zero.getMessage should include("Context window size must be positive")
    intercept[IllegalArgumentException](AdaptiveWindowing(-1)).getMessage should include(
      "Context window size must be positive"
    )
  }

  it should "reject a cost sensitivity outside 0.0 to 1.0" in {
    intercept[IllegalArgumentException](AdaptiveWindowing(128_000, costSensitivity = 1.5)).getMessage should include(
      "Cost sensitivity must be between 0.0 and 1.0"
    )
    intercept[IllegalArgumentException](AdaptiveWindowing(128_000, costSensitivity = -0.1)).getMessage should include(
      "Cost sensitivity must be between 0.0 and 1.0"
    )
  }

  it should "accept a cost sensitivity of exactly 0.0 and 1.0" in {
    AdaptiveWindowing(128_000, costSensitivity = 0.0).costSensitivity shouldBe 0.0
    AdaptiveWindowing(128_000, costSensitivity = 1.0).costSensitivity shouldBe 1.0
  }

  it should "default to no prices and a cost sensitivity of 0.5" in {
    AdaptiveWindowing(128_000) shouldBe AdaptiveWindowing(128_000, None, None, 0.5)
  }

  // ==========================================================================
  // Setters
  // ==========================================================================

  "AdaptiveWindowing setters" should "change only their own field" in {
    priced.withContextWindowSize(64_000) shouldBe AdaptiveWindowing(64_000, Some(inputPrice), Some(outputPrice), 0.3)
    priced.withInputCostPerToken(1e-6) shouldBe AdaptiveWindowing(128_000, Some(1e-6), Some(outputPrice), 0.3)
    priced.withOutputCostPerToken(2e-6) shouldBe AdaptiveWindowing(128_000, Some(inputPrice), Some(2e-6), 0.3)
    priced.withCostSensitivity(0.8) shouldBe AdaptiveWindowing(128_000, Some(inputPrice), Some(outputPrice), 0.8)
  }

  they should "clear a price when given None" in {
    priced.withInputCostPerToken(None) shouldBe AdaptiveWindowing(128_000, None, Some(outputPrice), 0.3)
    priced.withOutputCostPerToken(None) shouldBe AdaptiveWindowing(128_000, Some(inputPrice), None, 0.3)
    priced.withInputCostPerToken(Some(1e-6)) shouldBe AdaptiveWindowing(128_000, Some(1e-6), Some(outputPrice), 0.3)
    priced.withOutputCostPerToken(Some(2e-6)) shouldBe AdaptiveWindowing(128_000, Some(inputPrice), Some(2e-6), 0.3)
  }

  they should "validate the new value" in {
    intercept[IllegalArgumentException](priced.withContextWindowSize(0)).getMessage should include(
      "Context window size must be positive"
    )
    intercept[IllegalArgumentException](priced.withCostSensitivity(1.1)).getMessage should include(
      "Cost sensitivity must be between 0.0 and 1.0"
    )
  }
}
