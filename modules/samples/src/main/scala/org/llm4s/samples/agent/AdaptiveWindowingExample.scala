package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Example demonstrating adaptive context window management.
 *
 * AdaptiveWindowing automatically calculates the optimal context window
 * based on model capabilities and pricing, making it ideal for:
 * - Multi-model deployments (switch models without changing config)
 * - Cost-conscious production systems
 * - Quality-sensitive applications
 * - Hybrid cost/quality optimization
 */
object AdaptiveWindowingExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Adaptive Context Window Example ===")

    // Example 1: Basic AdaptiveWindowing (cost-quality balanced)
    basicExample()

    // Example 2: Cost-optimized (minimize spending)
    costOptimizedExample()

    // Example 3: Quality-optimized (maximize context)
    qualityOptimizedExample()

    // Example 4: Production multi-model setup
    multiModelExample()
  }

  def basicExample(): Unit = {
    logger.info("\n--- Example 1: Basic Adaptive Windowing ---")

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool))
      agent = new Agent(client)

      // Create adaptive windowing strategy for GPT-4o
      strategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 128_000,         // GPT-4o context
        inputCostPerToken = Some(0.0000025), // $2.50 per 1M input tokens
        outputCostPerToken = Some(0.000010), // $10 per 1M output tokens
        preserveMinTurns = 3,
        costSensitivity = 0.5 // Balanced: value quality ~= value cost savings
      )

      _ = logger.info("Strategy: {}", strategy.explanation)
      _ = logger.info("Optimal window: {} tokens", strategy.calculateOptimalWindow)

      config = ContextWindowConfig(
        pruningStrategy = strategy,
        preserveSystemMessage = true
      )

      // Run multi-turn conversation with automatic pruning
      finalState <- agent.runMultiTurn(
        initialQuery = "What's the weather in Paris?",
        followUpQueries = Seq(
          "And in London?",
          "How about Tokyo?",
          "Which is warmest?",
          "Should I pack an umbrella?",
          "What about a coat?",
          "When's the best time to visit?",
          "What are the rainy months?",
          "Tell me more about December weather"
        ),
        tools = tools,
        contextWindowConfig = Some(config)
      )

      _ = logger.info("=== Results ===")
      _ = logger.info("Final messages: {}", finalState.conversation.messageCount)
      _ = logger.info("User messages: {}", finalState.conversation.filterByRole(MessageRole.User).length)
      _ = logger.info("Assistant messages: {}", finalState.conversation.filterByRole(MessageRole.Assistant).length)
    } yield finalState

    result.fold(
      error => logger.error("Error: {}", error.formatted),
      state => logger.info("Success! Completed with status: {}", state.status)
    )
  }

  def costOptimizedExample(): Unit = {
    logger.info("\n--- Example 2: Cost-Optimized Windowing ---")

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool))
      agent = new Agent(client)

      // Very aggressive cost optimization
      strategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 128_000,
        inputCostPerToken = Some(0.0000025),
        outputCostPerToken = Some(0.000010),
        costSensitivity = 0.9 // 0.9 = aggressive cost minimization
      )

      _ = logger.info("Cost-optimized strategy: {}", strategy.explanation)
      _ = logger.info("Window reduced to: {} tokens (compared to ~89.6K balanced)", strategy.calculateOptimalWindow)

      config = ContextWindowConfig(
        pruningStrategy = strategy,
        preserveSystemMessage = true,
        minRecentTurns = 2 // Minimum 2 turns (cost savings)
      )

      finalState <- agent.runMultiTurn(
        initialQuery = "Tell me about machine learning",
        followUpQueries = Seq(
          "What about deep learning?",
          "Difference between them?",
          "Best frameworks?",
          "Which is faster?",
          "How to get started?",
          "Recommended courses?",
          "Any books?"
        ),
        tools = tools,
        contextWindowConfig = Some(config)
      )

      _ = logger.info("Cost-optimized: {} messages", finalState.conversation.messageCount)
    } yield finalState

    result.fold(
      error => logger.error("Error: {}", error.formatted),
      _ => logger.info("Cost-optimized conversation completed")
    )
  }

  def qualityOptimizedExample(): Unit = {
    logger.info("\n--- Example 3: Quality-Optimized Windowing ---")

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool))
      agent = new Agent(client)

      // Maximize context for better quality
      strategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 128_000,
        inputCostPerToken = Some(0.0000025),
        outputCostPerToken = Some(0.000010),
        costSensitivity = 0.1 // 0.1 = minimal cost concern, maximize quality
      )

      _ = logger.info("Quality-optimized strategy: {}", strategy.explanation)
      _ = logger.info("Using larger window: {} tokens", strategy.calculateOptimalWindow)

      config = ContextWindowConfig(
        pruningStrategy = strategy,
        preserveSystemMessage = true,
        minRecentTurns = 5 // Keep more context
      )

      finalState <- agent.runMultiTurn(
        initialQuery = "Explain quantum computing",
        followUpQueries = Seq(
          "How does superposition work?",
          "What about entanglement?",
          "Practical applications?",
          "Current limitations?",
          "Timeline to practical use?",
          "Leading companies?",
          "Learning path?",
          "Math requirements?"
        ),
        tools = tools,
        contextWindowConfig = Some(config)
      )

      _ = logger.info("Quality-optimized: {} messages", finalState.conversation.messageCount)
    } yield finalState

    result.fold(
      error => logger.error("Error: {}", error.formatted),
      _ => logger.info("Quality-optimized conversation completed")
    )
  }

  def multiModelExample(): Unit = {
    logger.info("\n--- Example 4: Production Multi-Model Setup ---")

    // Simulate different model configurations
    val models = Map(
      "gpt-4o" -> (
        "openai/gpt-4o",
        128_000,
        Some(0.0000025),
        Some(0.000010)
      ),
      "claude-3.5-sonnet" -> (
        "anthropic/claude-3-5-sonnet-latest",
        200_000,
        Some(0.000003),
        Some(0.000015)
      ),
      "ollama-local" -> (
        "ollama/mistral",
        4_096,
        None: Option[Double], // Local, no cost
        None: Option[Double]
      )
    )

    logger.info("=== Model Window Comparisons ===")

    models.foreach { case (name, (_, contextSize, inputCost, outputCost)) =>
      val strategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = contextSize,
        inputCostPerToken = inputCost,
        outputCostPerToken = outputCost,
        costSensitivity = 0.5
      )

      val optimal    = strategy.calculateOptimalWindow
      val percentage = (optimal.toDouble / contextSize * 100).toInt

      logger.info("{}: {} tokens ({}% of {}K context)", name, optimal, percentage, contextSize / 1000)
    }

    logger.info("\n--- Cross-model switching without config change ---")
    logger.info("Same ContextWindowConfig works across all models!")
    logger.info("AdaptiveWindowing automatically adjusts per model")
  }

  /**
   * Demonstrate how different cost ratios affect window size
   */
  def demonstrateCostSensitivity(): Unit = {
    logger.info("\n--- Cost Sensitivity Impact ---")

    val sensitivities = Seq(0.0, 0.25, 0.5, 0.75, 1.0)

    sensitivities.foreach { sensitivity =>
      val strategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.00001), // Expensive input
        outputCostPerToken = Some(0.000001),
        costSensitivity = sensitivity
      )

      val window = strategy.calculateOptimalWindow
      val used   = (window.toDouble / 100_000 * 100).toInt

      val label = sensitivity match {
        case 0.0 => "Quality-focused"
        case 0.5 => "Balanced"
        case 1.0 => "Cost-optimized"
        case _   => "Custom"
      }

      logger.info("{} ({}): {} tokens ({}%)", label, sensitivity, window, used)
    }
  }
}
