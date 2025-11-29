package org.llm4s.samples.reasoning

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating the reasoning modes feature.
 *
 * This example shows how to:
 * - Configure reasoning effort levels
 * - Use explicit budget tokens for Anthropic
 * - Access thinking content from completions
 * - Track thinking token usage
 *
 * Note: Actual extended thinking requires models that support it:
 * - OpenAI: o1-preview, o1-mini, o3-mini
 * - Anthropic: Claude models with extended thinking enabled
 *
 * This example demonstrates the API without requiring a specific model.
 *
 * @example
 * {{{
 * // For OpenAI reasoning models:
 * export LLM_MODEL=openai/o1-preview
 * export OPENAI_API_KEY=sk-...
 *
 * // For Anthropic with extended thinking:
 * export LLM_MODEL=anthropic/claude-3-5-sonnet-latest
 * export ANTHROPIC_API_KEY=sk-ant-...
 *
 * sbt "samples/runMain org.llm4s.samples.reasoning.ReasoningModesExample"
 * }}}
 */
object ReasoningModesExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Reasoning Modes Example ===\n")

    // Demonstrate the API without requiring a specific model
    demonstrateCompletionOptionsAPI()

    logger.info("\n--- Testing with LLM (if configured) ---")

    // Try to create a client and run a simple completion with reasoning
    val clientResult = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
    } yield client

    clientResult match {
      case Left(error) =>
        logger.warn("LLM client not configured: {}", error.formatted)
        logger.info("Set LLM_MODEL and appropriate API key to test with a real model")

      case Right(client) =>
        logger.info("LLM client created successfully")
        testWithReasoning(client)
    }

    logger.info("\n=== Example Complete ===")
  }

  private def demonstrateCompletionOptionsAPI(): Unit = {
    logger.info("--- CompletionOptions API Demonstration ---\n")

    // 1. Default options (no reasoning)
    val defaultOptions = CompletionOptions()
    logger.info("Default options:")
    logger.info("  reasoning: {}", defaultOptions.reasoning)
    logger.info("  hasReasoning: {}", defaultOptions.hasReasoning)
    logger.info("  effectiveBudgetTokens: {}", defaultOptions.effectiveBudgetTokens)

    // 2. With reasoning effort
    logger.info("\nWith ReasoningEffort.High:")
    val highReasoning = CompletionOptions().withReasoning(ReasoningEffort.High)
    logger.info("  reasoning: {}", highReasoning.reasoning.map(_.name))
    logger.info("  hasReasoning: {}", highReasoning.hasReasoning)
    logger.info("  effectiveBudgetTokens: {}", highReasoning.effectiveBudgetTokens)

    // 3. With different effort levels
    logger.info("\nDefault budget tokens for each level:")
    ReasoningEffort.values.foreach { effort =>
      val tokens = ReasoningEffort.defaultBudgetTokens(effort)
      logger.info("  {}: {} tokens", effort.name, tokens)
    }

    // 4. With explicit budget tokens (overrides reasoning effort)
    logger.info("\nWith explicit budgetTokens (16000):")
    val explicitBudget = CompletionOptions()
      .withReasoning(ReasoningEffort.High)
      .withBudgetTokens(16000)
    logger.info("  reasoning: {}", explicitBudget.reasoning.map(_.name))
    logger.info("  budgetTokens: {}", explicitBudget.budgetTokens)
    logger.info("  effectiveBudgetTokens: {} (overrides High's 32768)", explicitBudget.effectiveBudgetTokens)

    // 5. Parsing from string
    logger.info("\nParsing ReasoningEffort from strings:")
    Seq("low", "MEDIUM", "high", "invalid").foreach { s =>
      val result = ReasoningEffort.fromString(s)
      logger.info("  '{}' -> {}", s, result)
    }
  }

  private def testWithReasoning(client: org.llm4s.llmconnect.LLMClient): Unit = {
    // Create options with medium reasoning
    val options = CompletionOptions()
      .withReasoning(ReasoningEffort.Medium)
      .copy(maxTokens = Some(1024))

    logger.info("Testing completion with reasoning effort: {}", options.reasoning.map(_.name))
    logger.info("Effective budget tokens: {}", options.effectiveBudgetTokens)

    // Create a conversation with a reasoning-friendly prompt
    val conversation = Conversation(
      Seq(
        UserMessage("What is 15% of 847? Think through this step by step.")
      )
    )

    client.complete(conversation, options) match {
      case Left(error) =>
        logger.error("Completion failed: {}", error)

      case Right(completion) =>
        logger.info("\n--- Completion Result ---")
        logger.info("Model: {}", completion.model)
        logger.info("Content: {}", completion.content)

        // Check for thinking content
        if (completion.hasThinking) {
          logger.info("\n--- Thinking Content ---")
          logger.info("{}", completion.thinking.getOrElse(""))
        } else {
          logger.info("\nNo thinking content in response (model may not support extended thinking)")
        }

        // Show token usage
        completion.usage.foreach { usage =>
          logger.info("\n--- Token Usage ---")
          logger.info("Prompt tokens: {}", usage.promptTokens)
          logger.info("Completion tokens: {}", usage.completionTokens)
          usage.thinkingTokens.foreach(t => logger.info("Thinking tokens: {}", t))
          logger.info("Total tokens: {}", usage.totalTokens)
          if (usage.hasThinkingTokens) {
            logger.info("Total output tokens (incl. thinking): {}", usage.totalOutputTokens)
          }
        }

        // Show fullContent (includes thinking if present)
        if (completion.hasThinking) {
          logger.info("\n--- Full Content (with thinking) ---")
          logger.info("{}", completion.fullContent)
        }
    }
  }
}
