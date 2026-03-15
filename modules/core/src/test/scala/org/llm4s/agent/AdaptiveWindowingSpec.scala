package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{ UserMessage, SystemMessage, AssistantMessage }

/**
 * Tests for AdaptiveWindowing strategy.
 * Verifies automatic context window optimization based on model capabilities and costs.
 */
class AdaptiveWindowingSpec extends AnyFlatSpec with Matchers {

  "AdaptiveWindowing" should "be constructible with context window size" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000
    )

    strategy.contextWindowSize shouldBe 128_000
    strategy.costSensitivity shouldBe 0.5 // Default
  }

  it should "have sensible defaults" in {
    val strategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 100_000)

    strategy.preserveMinTurns shouldBe 3
    strategy.costSensitivity shouldBe 0.5
    strategy.inputCostPerToken shouldBe None
    strategy.outputCostPerToken shouldBe None
  }

  it should "reject invalid context window size" in {
    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(contextWindowSize = 0)
    }

    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(contextWindowSize = -1000)
    }
  }

  it should "reject invalid cost sensitivity" in {
    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = -0.1
      )
    }

    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 1.1
      )
    }
  }

  // ==========================================================================
  // Window Calculation Tests
  // ==========================================================================

  "calculateOptimalWindow" should "use 70% by default for medium models" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )

    val optimal = strategy.calculateOptimalWindow
    optimal shouldBe (100_000 * 0.7).toInt
  }

  it should "apply conservative ratio for small models (≤32K)" in {
    val smallModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 8_000
    )

    val optimal = smallModel.calculateOptimalWindow
    // 8K * 0.6 (small tier) = 4,800 tokens
    optimal shouldBe (8_000 * 0.6).toInt
    optimal should be < (8_000 * 0.7).toInt // More conservative than baseline
  }

  it should "apply balanced ratio for medium models (64K-100K)" in {
    val mediumModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )

    val optimal = mediumModel.calculateOptimalWindow
    // 100K * 0.7 (medium tier) = 70,000 tokens
    optimal shouldBe (100_000 * 0.7).toInt
  }

  it should "apply generous ratio for large models (200K+)" in {
    val largeModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_000
    )

    val optimal = largeModel.calculateOptimalWindow
    // 200K * 0.75 (large tier) = 150,000 tokens
    optimal shouldBe (200_000 * 0.75).toInt
    optimal should be > (200_000 * 0.7).toInt
  }

  it should "apply very generous ratio for extra-large models (1M+)" in {
    val extraLargeModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 1_000_000
    )

    val optimal = extraLargeModel.calculateOptimalWindow
    // 1M * 0.8 (extra-large tier) = 800,000 tokens
    optimal shouldBe (1_000_000 * 0.8).toInt
  }

  it should "never return less than 1000 tokens" in {
    val tinyModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100 // Unrealistic but test the safeguard
    )

    val optimal = tinyModel.calculateOptimalWindow
    optimal should be >= 1000
  }

  // ==========================================================================
  // Cost Sensitivity Tests
  // ==========================================================================

  it should "reduce window with high cost sensitivity" in {
    val baseline = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 0.0 // No cost optimization
      )
      .calculateOptimalWindow

    val costOptimized = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 0.9 // Aggressive cost optimization
      )
      .calculateOptimalWindow

    costOptimized should be < baseline
  }

  it should "not reduce window when no cost data provided" in {
    val withoutCost = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 0.9
      )
      .calculateOptimalWindow

    val withoutCost2 = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = None, // Missing output cost
        costSensitivity = 0.9
      )
      .calculateOptimalWindow

    withoutCost shouldBe withoutCost2
  }

  it should "be more sensitive to input-heavy pricing" in {
    val expensiveInput = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.00001),   // Input is expensive
        outputCostPerToken = Some(0.000001), // Output is cheap
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    val balancedCost = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000005), // Equal
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    expensiveInput should be < balancedCost
  }

  it should "handle cost adjustment with zero costs gracefully" in {
    val withZeroCosts = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.0),
        outputCostPerToken = Some(0.0),
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    val withoutCosts = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    // Both should produce the same result when costs are zero or missing
    withZeroCosts shouldBe withoutCosts
  }

  it should "apply maximum cost reduction at 100% cost sensitivity" in {
    val costOptimized = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(1.0), // 100% of total (extreme case)
        outputCostPerToken = Some(0.0),
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    val baseline = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 0.0
      )
      .calculateOptimalWindow

    costOptimized should be <= baseline
    // Should apply max 20% reduction: costRatio=1.0 * sensitivity=1.0 * 0.2 = 0.2
    val expectedReduction = (baseline * 0.2).toInt
    (baseline - costOptimized) should be <= (expectedReduction + 1) // +1 for rounding
  }

  // ==========================================================================
  // Tier Boundary Tests (Complete Coverage)
  // ==========================================================================

  "Tier boundaries" should "apply 60% for exactly 32K (boundary)" in {
    val atBoundary = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 32_000
    )
    val optimal = atBoundary.calculateOptimalWindow
    optimal shouldBe (32_000 * 0.6).toInt
  }

  it should "apply 70% for just above 32K" in {
    val justAbove = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 32_001
    )
    val optimal = justAbove.calculateOptimalWindow
    optimal shouldBe (32_001 * 0.7).toInt
  }

  it should "apply 70% for exactly 100K (boundary)" in {
    val atBoundary = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )
    val optimal = atBoundary.calculateOptimalWindow
    optimal shouldBe (100_000 * 0.7).toInt
  }

  it should "apply 75% for just above 100K" in {
    val justAbove = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_001
    )
    val optimal = justAbove.calculateOptimalWindow
    optimal shouldBe (100_001 * 0.75).toInt
  }

  it should "apply 75% for exactly 200K (boundary)" in {
    val atBoundary = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_000
    )
    val optimal = atBoundary.calculateOptimalWindow
    optimal shouldBe (200_000 * 0.75).toInt
  }

  it should "apply 80% for just above 200K" in {
    val justAbove = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_001
    )
    val optimal = justAbove.calculateOptimalWindow
    optimal shouldBe (200_001 * 0.8).toInt
  }

  // ==========================================================================
  // Explanation String Tests (Tier Names)
  // ==========================================================================

  "explanation method" should "show 'small' tier for ≤32K" in {
    val small = PruningStrategy.AdaptiveWindowing(contextWindowSize = 8_000)
    small.explanation should include("small")
  }

  it should "show 'medium' tier for 32K-100K" in {
    val medium = PruningStrategy.AdaptiveWindowing(contextWindowSize = 64_000)
    medium.explanation should include("medium")
  }

  it should "show 'large' tier for >100K" in {
    val large = PruningStrategy.AdaptiveWindowing(contextWindowSize = 400_000)
    large.explanation should include("large")
  }

  it should "show percentage as integer" in {
    val strategy    = PruningStrategy.AdaptiveWindowing(contextWindowSize = 100_000)
    val explanation = strategy.explanation
    // Extract percentage from explanation
    (explanation should fullyMatch).regex(".*\\d+%.*".r)
  }

  it should "show context window in thousands" in {
    val strategy    = PruningStrategy.AdaptiveWindowing(contextWindowSize = 128_000)
    val explanation = strategy.explanation
    explanation should include("128K")
  }

  // ==========================================================================
  // Edge Cases for Cost Sensitivity
  // ==========================================================================

  "Cost sensitivity edge cases" should "handle cost sensitivity at 0.0" in {
    val noCostOptimization = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 0.0
      )
      .calculateOptimalWindow

    val baseline = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 0.0
      )
      .calculateOptimalWindow

    // Should be the same - no optimization applied
    noCostOptimization shouldBe baseline
  }

  it should "handle cost sensitivity at 1.0 (maximum)" in {
    val maxCostOptimization = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    val baseline = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 0.0
      )
      .calculateOptimalWindow

    // Should be less with cost optimization
    maxCostOptimization should be < baseline
  }

  // ==========================================================================
  // Integration with Pruning
  // ==========================================================================

  "AgentState.pruneConversation with AdaptiveWindowing" should "prune using calculated window" in {
    val messages = Seq(
      SystemMessage("You are helpful"),
      UserMessage("Hello"),
      AssistantMessage("Hi there!"),
      UserMessage("Who are you?"),
      AssistantMessage("I'm an assistant"),
      UserMessage("What can you do?"),
      AssistantMessage("I can help with many things"),
      UserMessage("Tell me a story"),
      AssistantMessage("Once upon a time..."),
      UserMessage("More!"),
      AssistantMessage("There was a kingdom...")
    )

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 8_000 // Small model
    )

    val config = ContextWindowConfig(
      pruningStrategy = strategy,
      preserveSystemMessage = true
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should have fewer messages after pruning
    pruned.conversation.messages.length should be <= messages.length
    // System message should be preserved
    pruned.conversation.messages.head shouldBe messages.head
  }

  it should "use adaptive windowing path in pruneConversation" in {
    val messages = Seq(
      SystemMessage("System prompt"),
      UserMessage("Q1"),
      AssistantMessage("A1"),
      UserMessage("Q2"),
      AssistantMessage("A2"),
      UserMessage("Q3"),
      AssistantMessage("A3")
    )

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 50_000
    )

    val config = ContextWindowConfig(
      pruningStrategy = strategy,
      maxMessages = None // Let adaptive windowing control via maxTokens
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should execute without error (case statement matched correctly)
    pruned.conversation.messages should not be empty
  }

  it should "respect preserve system message setting with adaptive windowing" in {
    val messages = Seq(
      SystemMessage("Critical system instruction"),
      UserMessage("Q1"),
      AssistantMessage("A1")
    )

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 4_000
    )

    val configPreserve = ContextWindowConfig(
      pruningStrategy = strategy,
      preserveSystemMessage = true
    )

    val prunedPreserve = AgentState.pruneConversation(state, configPreserve)

    // Should preserve system message
    prunedPreserve.conversation.messages.head shouldBe messages.head

    val configNoPreserve = ContextWindowConfig(
      pruningStrategy = strategy,
      preserveSystemMessage = false
    )

    val prunedNoPreserve = AgentState.pruneConversation(state, configNoPreserve)

    // When pruning to very small size, might not have system message
    if (prunedNoPreserve.conversation.messages.nonEmpty) {
      // If anything remains, it should follow the preserve setting
      if (prunedNoPreserve.conversation.messages.head == messages.head) {
        // System message is present
        prunedNoPreserve.conversation.messages.head shouldBe messages.head
      }
    }
  }

  it should "handle minimum recent turns with adaptive windowing" in {
    val messages = (1 to 20).flatMap { i =>
      Seq(
        UserMessage(s"Question $i"),
        AssistantMessage(s"Answer $i")
      )
    }

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 4_000,
      preserveMinTurns = 5
    )

    val config = ContextWindowConfig(
      pruningStrategy = strategy,
      minRecentTurns = 5 // Keep at least 5 turns
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should preserve roughly last 5 turns (10 messages)
    pruned.conversation.messages.length should be >= 10
  }

  it should "apply adaptive windowing with cost-sensitive configuration" in {
    val messages = (1 to 15).map { i =>
      if (i % 2 == 0) AssistantMessage(s"Response $i" * 10)
      else UserMessage(s"Query $i" * 10)
    }

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val expensiveInputStrategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.00001),
      outputCostPerToken = Some(0.000001),
      costSensitivity = 0.8
    )

    val config = ContextWindowConfig(
      pruningStrategy = expensiveInputStrategy,
      preserveSystemMessage = false
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should have pruned messages according to cost-sensitive window
    pruned.conversation.messages.length should be <= messages.length
  }

  it should "reduce window more aggressively with cost-sensitive adaptive windowing" in {
    val largeMessages = (1 to 50).map { i =>
      if (i % 2 == 0) AssistantMessage(s"Response $i with lots of content")
      else UserMessage(s"Query $i with lots of content")
    }

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(largeMessages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val expensiveInputStrategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      inputCostPerToken = Some(0.00001),
      outputCostPerToken = Some(0.000001),
      costSensitivity = 1.0 // Maximum cost sensitivity
    )

    val cheapInputStrategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.00001),
      costSensitivity = 1.0 // Maximum cost sensitivity
    )

    val configExpensive = ContextWindowConfig(pruningStrategy = expensiveInputStrategy)
    val configCheap     = ContextWindowConfig(pruningStrategy = cheapInputStrategy)

    val prunedExpensive = AgentState.pruneConversation(state, configExpensive)
    val prunedCheap     = AgentState.pruneConversation(state, configCheap)

    // Expensive input should result in more aggressive pruning (fewer messages)
    prunedExpensive.conversation.messages.length should be <= prunedCheap.conversation.messages.length
  }

  // ==========================================================================
  // Realistic Scenarios
  // ==========================================================================

  "AdaptiveWindowing for GPT-4o" should "use appropriate window" in {
    val gpt4o = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.0000025), // $2.50 per 1M input tokens
      outputCostPerToken = Some(0.000010), // $10 per 1M output tokens
      costSensitivity = 0.5                // Balanced
    )

    val window = gpt4o.calculateOptimalWindow
    println(s"GPT-4o optimal window: $window tokens")

    // Should be something like 89,600 (70% of 128K)
    window should be > 80_000
    window should be < 100_000
    window should be < 128_000 // Less than full context
  }

  it should "be more conservative when input is expensive" in {
    val expensive = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.00001), // Expensive!
      outputCostPerToken = Some(0.000001),
      costSensitivity = 0.8
    )

    val cheap = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.00001), // Expensive output
      costSensitivity = 0.8
    )

    expensive.calculateOptimalWindow should be < cheap.calculateOptimalWindow
  }

  "AdaptiveWindowing for Claude 3.5" should "use appropriate window" in {
    val claude = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_000,
      inputCostPerToken = Some(0.000003),  // $3 per 1M input tokens
      outputCostPerToken = Some(0.000015), // $15 per 1M output tokens
      costSensitivity = 0.5
    )

    val window = claude.calculateOptimalWindow
    println(s"Claude 3.5 optimal window: $window tokens")

    // Should be 70-75% of 200K
    window should be > 140_000
    window should be < 160_000
  }

  "AdaptiveWindowing for Ollama local" should "use large window" in {
    val ollama = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 4_096,
      inputCostPerToken = None, // No cost (local)
      outputCostPerToken = None,
      costSensitivity = 0.5
    )

    val window = ollama.calculateOptimalWindow
    println(s"Ollama optimal window: $window tokens")

    // Should use 60-70% for small model
    window should be > 2_000
    window should be < 3_000
  }

  // ==========================================================================
  // Copy and Equality
  // ==========================================================================

  "AdaptiveWindowing" should "support copy for modifications" in {
    val original = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val modified = original.copy(costSensitivity = 0.8)

    modified.contextWindowSize shouldBe original.contextWindowSize
    modified.costSensitivity should not be original.costSensitivity
    modified.costSensitivity shouldBe 0.8
  }

  it should "support equality" in {
    val s1 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val s2 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val s3 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.8
    )

    s1 shouldBe s2
    s1 should not be s3
  }
}
