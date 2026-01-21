package org.llm4s.samples.model

import org.llm4s.model.{ ModelMetadata, ModelMode, ModelRegistry }
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to use the ModelRegistry and ModelMetadata.
 *
 * This sample shows:
 * - Looking up model metadata by model ID
 * - Querying model capabilities
 * - Listing models by provider or capability
 * - Estimating costs
 * - Registering custom models
 */
object ModelMetadataExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("LLM4S Model Metadata Example")
  logger.info("=" * 80)

  // Initialize the registry (loads embedded metadata)
  logger.info("Initializing ModelRegistry...")
  ModelRegistry.initialize() match {
    case Right(_) =>
      logger.info("ModelRegistry initialized successfully")
    case Left(error) =>
      logger.error("Failed to initialize: {}", error)
      sys.exit(1)
  }

  // Example 1: Lookup a specific model
  logger.info("Example 1: Looking up GPT-4o metadata")
  logger.info("-" * 80)
  ModelRegistry.lookup("gpt-4o") match {
    case Right(metadata) =>
      logger.info("Model ID: {}", metadata.modelId)
      logger.info("Provider: {}", metadata.provider)
      logger.info("Mode: {}", metadata.mode.name)
      logger.info("Context Window: {} tokens", metadata.contextWindow.getOrElse("unknown"))
      logger.info("Max Output: {} tokens", metadata.maxOutputTokens.getOrElse("unknown"))
      logger.info("Input Cost: {} per token", metadata.inputCostPerToken.getOrElse("unknown"))
      logger.info("Output Cost: {} per token", metadata.outputCostPerToken.getOrElse("unknown"))
      logger.info("Description: {}", metadata.description)
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 2: Check model capabilities
  logger.info("Example 2: Checking Claude 3.7 Sonnet capabilities")
  logger.info("-" * 80)
  ModelRegistry.lookup("claude-3-7-sonnet-latest") match {
    case Right(metadata) =>
      logger.info("Model: {}", metadata.modelId)
      logger.info("Supports function calling: {}", metadata.supports("function_calling"))
      logger.info("Supports vision: {}", metadata.supports("vision"))
      logger.info("Supports prompt caching: {}", metadata.supports("caching"))
      logger.info("Supports reasoning: {}", metadata.supports("reasoning"))
      logger.info("Supports PDFs: {}", metadata.supports("pdf"))
      logger.info("Supports computer use: {}", metadata.supports("computer_use"))
      logger.info("Is deprecated: {}", metadata.isDeprecated)
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 3: List models by provider
  logger.info("Example 3: Listing OpenAI chat models")
  logger.info("-" * 80)
  val openaiModels = for {
    allOpenAI  <- ModelRegistry.listByProvider("openai")
    chatModels <- Right(allOpenAI.filter(_.mode == ModelMode.Chat))
  } yield chatModels

  openaiModels match {
    case Right(models) =>
      logger.info("Found {} OpenAI chat models:", models.size)
      models.take(10).foreach { model =>
        val ctx = model.contextWindow.map(c => f"${c / 1000}%dK").getOrElse("?")
        logger.info(f"  - ${model.modelId}%-40s (${ctx} context)")
      }
      if (models.size > 10) logger.info("  ... and {} more", models.size - 10)
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 4: Find models with specific capabilities
  logger.info("Example 4: Finding models with vision support")
  logger.info("-" * 80)
  ModelRegistry.findByCapability("vision") match {
    case Right(models) =>
      logger.info("Found {} models with vision support:", models.size)
      models
        .filter(_.mode == ModelMode.Chat) // Only chat models
        .take(5)
        .foreach { model =>
          val provider = model.provider
          logger.info(f"  - ${model.modelId}%-50s ($provider)")
        }
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 5: Estimate costs
  logger.info("Example 5: Estimating completion costs")
  logger.info("-" * 80)
  ModelRegistry.lookup("gpt-4o") match {
    case Right(metadata) =>
      val inputTokens  = 10000
      val outputTokens = 2000

      metadata.pricing.estimateCost(inputTokens, outputTokens) match {
        case Some(cost) =>
          logger.info("Model: {}", metadata.modelId)
          logger.info("Input tokens: {}", inputTokens)
          logger.info("Output tokens: {}", outputTokens)
          logger.info(f"Estimated cost: $$${cost}%.6f")
        case None =>
          logger.info("Pricing information not available")
      }
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 6: Register a custom model
  logger.info("Example 6: Registering a custom model")
  logger.info("-" * 80)
  val customModel = ModelMetadata(
    modelId = "my-custom-llm-v1",
    provider = "custom",
    mode = ModelMode.Chat,
    maxInputTokens = Some(32000),
    maxOutputTokens = Some(8000),
    inputCostPerToken = Some(1e-6),
    outputCostPerToken = Some(3e-6),
    capabilities = org.llm4s.model.ModelCapabilities(
      supportsFunctionCalling = Some(true),
      supportsVision = Some(false),
      supportsSystemMessages = Some(true)
    ),
    pricing = org.llm4s.model.ModelPricing(
      inputCostPerToken = Some(1e-6),
      outputCostPerToken = Some(3e-6)
    ),
    deprecationDate = None
  )

  ModelRegistry.register(customModel)
  logger.info("Registered custom model: {}", customModel.modelId)

  ModelRegistry.lookup("my-custom-llm-v1") match {
    case Right(metadata) =>
      logger.info("  Description: {}", metadata.description)
      logger.info("  Context window: {} tokens", metadata.contextWindow.get)
    case Left(error) =>
      logger.error("  Error retrieving: {}", error)
  }

  // Example 7: Get registry statistics
  logger.info("Example 7: Registry statistics")
  logger.info("-" * 80)
  val stats = ModelRegistry.statistics()
  logger.info("Total models: {}", stats("totalModels"))
  logger.info("Embedded models: {}", stats("embeddedModels"))
  logger.info("Custom models: {}", stats("customModels"))
  logger.info("Providers: {}", stats("providers"))
  logger.info("Chat models: {}", stats("chatModels"))
  logger.info("Embedding models: {}", stats("embeddingModels"))
  logger.info("Image generation models: {}", stats("imageGenerationModels"))
  logger.info("Deprecated models: {}", stats("deprecatedModels"))

  // Example 8: List all providers
  logger.info("Example 8: Available providers")
  logger.info("-" * 80)
  ModelRegistry.listProviders() match {
    case Right(providers) =>
      logger.info("Found {} providers:", providers.size)
      providers.foreach(p => logger.info("  - {}", p))
    case Left(error) =>
      logger.error("Error: {}", error)
  }

  // Example 9: Load custom metadata from JSON
  logger.info("Example 9: Loading custom metadata from JSON")
  logger.info("-" * 80)
  val customJson = """{
    "my-experimental-model": {
      "litellm_provider": "experimental",
      "mode": "chat",
      "max_input_tokens": 16000,
      "max_output_tokens": 4000,
      "input_cost_per_token": 5e-7,
      "output_cost_per_token": 1.5e-6,
      "supports_function_calling": true,
      "supports_vision": false
    }
  }"""

  ModelRegistry.loadCustomMetadataFromString(customJson) match {
    case Right(_) =>
      logger.info("Custom metadata loaded successfully")
      ModelRegistry.lookup("my-experimental-model") match {
        case Right(metadata) =>
          logger.info("  Model: {}", metadata.modelId)
          logger.info("  Provider: {}", metadata.provider)
          logger.info("  Context: {} tokens", metadata.contextWindow.getOrElse("unknown"))
        case Left(error) =>
          logger.error("  Error: {}", error)
      }
    case Left(error) =>
      logger.error("Failed to load: {}", error)
  }

  logger.info("=" * 80)
  logger.info("Example complete!")
  logger.info("=" * 80)
}
