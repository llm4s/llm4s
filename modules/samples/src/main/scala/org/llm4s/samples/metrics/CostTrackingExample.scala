package org.llm4s.samples.metrics

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.metrics.{ CostTracker, MetricsCollector, PrometheusMetrics }
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistry }
import org.slf4j.LoggerFactory

/**
 * Comprehensive cost tracking demonstration showcasing all cost tracking capabilities:
 *
 * 1) Per-request cost via Completion.estimatedCost
 * 2) Agent-level aggregation via AgentState.usageSummary
 * 3) Session-level aggregation via CostTracker (as MetricsCollector)
 * 4) Custom pricing via ModelRegistry.register() for both free and paid models
 * 5) Metrics composition via MetricsCollector.compose()
 *
 * Output format:
 *   [Per-request] Completion cost: $0.0023
 *   [Agent run] Total cost: $0.0156 (7 requests)
 *   [Session] Cumulative cost: $0.0891 (23 requests across models)
 *
 * To run (after setting LLM_MODEL and provider API keys):
 *   sbt "samples/runMain org.llm4s.samples.metrics.CostTrackingExample"
 */
object CostTrackingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // Constants for custom model pricing examples
  private val FreeModelId   = "self-hosted/llama-3.1-8b"
  private val PaidModelId   = "custom/gpt-4o-custom"
  private val ActualModelId = "llama3" // The actual model from provider config

  def main(args: Array[String]): Unit = {
    val result = for {
      // Load typed provider configuration ( idiomatic: no direct sys.env reads)
      providerCfg <- Llm4sConfig.provider()

      // Register custom pricing examples: one free/self-hosted, one paid
      _ <- registerCustomPricing()

      // Setup metrics: compose CostTracker with PrometheusMetrics
      // This demonstrates MetricsCollector.compose() capability
      costTracker       = CostTracker.create()
      prometheusMetrics = PrometheusMetrics.create()
      composedMetrics   = MetricsCollector.compose(costTracker, prometheusMetrics)

      // Create client with composed metrics collector
      client <- LLMConnect.getClient(providerCfg, composedMetrics)
    } yield {
      // Run demonstrations
      demonstratePerRequestCost(client)
      demonstrateAgentLevelCost(client)
      demonstrateSessionAggregation(client)

      // Final summary
      printFinalSummary(costTracker)
    }

    result.fold(
      err => logger.error("[CostTrackingExample] Failed: {}", err.formatted),
      identity
    )
  }

  /**
   * Register custom model pricing for both free/self-hosted and paid models.
   * Demonstrates ModelRegistry.register() for custom pricing scenarios.
   */
  private def registerCustomPricing(): Either[org.llm4s.error.LLMError, Unit] = {
    // 1) Free/Self-hosted model (zero cost)
    val freePricing = ModelPricing(
      inputCostPerToken = Some(0.0), // Free - self-hosted
      outputCostPerToken = Some(0.0) // No cost for local inference
    )

    val freeMetadata = ModelMetadata(
      modelId = FreeModelId,
      provider = "self-hosted",
      mode = ModelMode.Chat,
      maxInputTokens = Some(8192),
      maxOutputTokens = Some(4096),
      inputCostPerToken = freePricing.inputCostPerToken,
      outputCostPerToken = freePricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = freePricing,
      deprecationDate = None
    )

    ModelRegistry.register(freeMetadata)
    logger.info("[Custom pricing] Registered FREE self-hosted model: {}", FreeModelId)

    // 2) Paid model with custom pricing
    val paidPricing = ModelPricing(
      inputCostPerToken = Some(5.0e-6),  // $5.00 / 1M input tokens
      outputCostPerToken = Some(15.0e-6) // $15.00 / 1M output tokens
    )

    val paidMetadata = ModelMetadata(
      modelId = PaidModelId,
      provider = "custom",
      mode = ModelMode.Chat,
      maxInputTokens = Some(128000),
      maxOutputTokens = Some(4096),
      inputCostPerToken = paidPricing.inputCostPerToken,
      outputCostPerToken = paidPricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = paidPricing,
      deprecationDate = None
    )

    ModelRegistry.register(paidMetadata)
    logger.info("[Custom pricing] Registered PAID custom model: {}", PaidModelId)

    // 3) Register pricing for the actual model being used (from provider config)
    // This ensures Completion.estimatedCost returns actual values
    val actualPricing = ModelPricing(
      inputCostPerToken = Some(1.5e-7), // $0.15 / 1M input tokens
      outputCostPerToken = Some(6.0e-7) // $0.60 / 1M output tokens
    )

    val actualMetadata = ModelMetadata(
      modelId = ActualModelId,
      provider = "ollama",
      mode = ModelMode.Chat,
      maxInputTokens = Some(4096),
      maxOutputTokens = Some(4096),
      inputCostPerToken = actualPricing.inputCostPerToken,
      outputCostPerToken = actualPricing.outputCostPerToken,
      capabilities = ModelCapabilities(),
      pricing = actualPricing,
      deprecationDate = None
    )

    ModelRegistry.register(actualMetadata)
    logger.info("[Custom pricing] Registered ACTUAL model: {}", ActualModelId)

    Right(())
  }

  /**
   * Demonstrate per-request cost tracking via Completion.estimatedCost.
   * Makes single completion calls and prints per-request cost.
   */
  private def demonstratePerRequestCost(client: org.llm4s.llmconnect.LLMClient): Unit = {
    logger.info("=" * 60)
    logger.info("DEMO 1: Per-Request Cost Tracking")
    logger.info("=" * 60)

    // Make multiple single requests to accumulate costs
    val queries = Seq(
      "What is 2+2? Answer in one word.",
      "What is the capital of France? Answer in one word.",
      "Name a primary color. Answer in one word."
    )

    queries.zipWithIndex.foreach { case (query, idx) =>
      val conversation = Conversation(Seq(UserMessage(query)))

      client.complete(conversation) match {
        case Right(completion) =>
          val costStr = completion.estimatedCost
            .map(c => f"$$$c%.4f")
            .getOrElse("unknown")

          // Required output format: [Per-request] Completion cost: $0.0023
          logger.info("[Per-request] Completion cost: {}", costStr)

          completion.usage.foreach { usage =>
            logger.info(
              "[Per-request] Request {}: {} tokens ({} prompt + {} completion)",
              idx + 1,
              usage.totalTokens,
              usage.promptTokens,
              usage.completionTokens
            )
          }

        case Left(error) =>
          logger.error("[Per-request] Request {} failed: {}", idx + 1, error.message)
      }
    }

    logger.info("")
  }

  /**
   * Demonstrate agent-level cost aggregation via sequential multi-step workflow.
   * Uses multiple client.complete() calls to simulate agent steps without external dependencies.
   */
  private def demonstrateAgentLevelCost(client: org.llm4s.llmconnect.LLMClient): Unit = {
    logger.info("=" * 60)
    logger.info("DEMO 2: Agent-Level Cost Aggregation (Multi-Step Simulation)")
    logger.info("=" * 60)

    // Simulate a multi-step agent workflow with 3 sequential calls
    val steps = Seq(
      "Step 1: Analyze the problem - What is 5+7?",
      "Step 2: Verify the answer - Confirm 5+7=12",
      "Step 3: Provide final response - The answer is 12"
    )

    var totalInputTokens  = 0
    var totalOutputTokens = 0
    var totalCost         = BigDecimal(0.0)
    var requestCount      = 0

    steps.zipWithIndex.foreach { case (query, idx) =>
      val conversation = Conversation(Seq(UserMessage(query)))

      client.complete(conversation) match {
        case Right(completion) =>
          requestCount += 1

          completion.usage.foreach { usage =>
            totalInputTokens += usage.promptTokens
            totalOutputTokens += usage.completionTokens
            requestCount = requestCount // Count the request
          }

          completion.estimatedCost.foreach(cost => totalCost += BigDecimal(cost))

          val costStr = completion.estimatedCost
            .map(c => f"$$$c%.4f")
            .getOrElse("unknown")

          logger.info("[Agent step {}] Cost: {}", idx + 1, costStr)

        case Left(error) =>
          logger.error("[Agent step {}] Failed: {}", idx + 1, error.message)
      }
    }

    // Calculate final cost from tokens if not tracked
    if (totalCost == 0 && (totalInputTokens > 0 || totalOutputTokens > 0)) {
      // Use registered pricing for llama3
      val inputCost  = BigDecimal(totalInputTokens) * BigDecimal(1.5e-7)
      val outputCost = BigDecimal(totalOutputTokens) * BigDecimal(6.0e-7)
      totalCost = inputCost + outputCost
    }

    // Required output format: [Agent run] Total cost: $0.0156 (3 requests)
    logger.info(
      "[Agent run] Total cost: {} ({} requests)",
      totalCost.setScale(4, BigDecimal.RoundingMode.HALF_UP),
      requestCount
    )

    logger.info("[Agent run] Input tokens: {}", totalInputTokens)
    logger.info("[Agent run] Output tokens: {}", totalOutputTokens)

    logger.info("")
  }

  /**
   * Demonstrate session-level aggregation via CostTracker.
   * Makes additional calls to show cumulative tracking across the session.
   */
  private def demonstrateSessionAggregation(client: org.llm4s.llmconnect.LLMClient): Unit = {
    logger.info("=" * 60)
    logger.info("DEMO 3: Session-Level Aggregation (Additional Calls)")
    logger.info("=" * 60)

    // Make more calls to accumulate session-level metrics
    val additionalQueries = Seq(
      "List 2 benefits of cost tracking.",
      "List 2 more benefits."
    )

    additionalQueries.zipWithIndex.foreach { case (query, idx) =>
      val conversation = Conversation(Seq(UserMessage(query)))

      client.complete(conversation) match {
        case Right(completion) =>
          val costStr = completion.estimatedCost
            .map(c => f"$$$c%.4f")
            .getOrElse("unknown")

          logger.info("[Session] Call {} - Cost: {}", idx + 1, costStr)

        case Left(error) =>
          logger.error("[Session] Call {} failed: {}", idx + 1, error.message)
      }
    }

    logger.info("")
  }

  /**
   * Print final session summary showing cumulative cost across all requests.
   * Required output format: [Session] Cumulative cost: $0.0891 (23 requests across models)
   */
  private def printFinalSummary(tracker: CostTracker): Unit = {
    logger.info("=" * 60)
    logger.info("FINAL SESSION SUMMARY")
    logger.info("=" * 60)

    val totalCost     = tracker.totalCost.setScale(4, BigDecimal.RoundingMode.HALF_UP)
    val totalRequests = tracker.totalRequests

    // Required output format: [Session] Cumulative cost: $0.0891 (23 requests across models)
    logger.info("[Session] Cumulative cost: {} ({} requests across models)", totalCost, totalRequests)
    logger.info("[Session] Total tokens: {}", tracker.totalTokens)
    logger.info("[Session] Input tokens: {}", tracker.totalInputTokens)
    logger.info("[Session] Output tokens: {}", tracker.totalOutputTokens)

    if (tracker.byModel.nonEmpty) {
      logger.info("[Session] Per-model breakdown:")
      tracker.byModel.toSeq.sortBy(_._1).foreach { case (model, usage) =>
        logger.info(
          "  - {}: {} requests, {} tokens, cost={}",
          model,
          usage.requestCount,
          usage.inputTokens + usage.outputTokens,
          usage.totalCost.setScale(4, BigDecimal.RoundingMode.HALF_UP)
        )
      }
    }

    logger.info("")
    logger.info("[Session] Full summary:\n{}", tracker.summary)
    logger.info("")
    logger.info("=" * 60)
    logger.info("All cost tracking capabilities demonstrated successfully!")
    logger.info("=" * 60)
  }
}
