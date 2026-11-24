package org.llm4s.samples.model

import org.llm4s.config.Llm4sConfig
import org.llm4s.model.{ ModelMetadata, ModelMode, ModelRegistryService }
import org.slf4j.LoggerFactory

/**
 * Example demonstrating how to use ModelRegistryService and ModelMetadata.
 *
 * This sample shows:
 * - Looking up model metadata by model ID
 * - Querying model capabilities
 * - Listing models by provider or capability
 * - Estimating costs
 * - Creating custom immutable registry snapshots
 */
object ModelMetadataExample extends App:
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("LLM4S Model Metadata Example")
  logger.info("=" * 80)

  logger.info("Loading ModelRegistryService from application config...")
  Llm4sConfig.modelRegistryService() match
    case Left(error) =>
      logger.error("Failed to create ModelRegistryService: {}", error)
      sys.exit(1)
    case Right(service) =>
      logger.info("Example 1: Looking up GPT-4o metadata")
      logger.info("-" * 80)
      service.lookup("gpt-5.6-terra") match {
        case Right(metadata) =>
          logger.info("Model ID: {}", metadata.modelId)
          logger.info("Provider: {}", metadata.provider)
          logger.info("Mode: {}", metadata.mode.name)
          logger.info("Context Window: {} tokens", metadata.contextWindow.getOrElse("unknown"))
          logger.info("Max Output: {} tokens", metadata.maxOutputTokens.getOrElse("unknown"))
          logger.info("Input Cost: {} per token", metadata.pricing.inputCostPerToken.getOrElse("unknown"))
          logger.info("Output Cost: {} per token", metadata.pricing.outputCostPerToken.getOrElse("unknown"))
          logger.info("Description: {}", metadata.description)
        case Left(error) =>
          logger.error("Error: {}", error)
      }

      // Example 2: Check model capabilities
      logger.info("Example 2: Checking Claude 3.7 Sonnet capabilities")
      logger.info("-" * 80)
      service.lookup("claude-sonnet-5-5") match {
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
      val openaiModels = for
        allOpenAI  <- service.listByProvider("openai")
        chatModels <- Right(allOpenAI.filter(_.mode == ModelMode.Chat))
      yield chatModels

      openaiModels match
        case Right(models) =>
          logger.info("Found {} OpenAI chat models:", models.size)
          models.take(10).foreach { model =>
            val ctx = model.contextWindow.map(c => f"${c / 1000}%dK").getOrElse("?")
            logger.info(f"  - ${model.modelId}%-40s (${ctx} context)")
          }
          if (models.size > 10) logger.info("  ... and {} more", models.size - 10)
        case Left(error) =>
          logger.error("Error: {}", error)

      // Example 4: Find models with specific capabilities
      logger.info("Example 4: Finding models with vision support")
      logger.info("-" * 80)
      service.findByCapability("vision") match
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
          logger.error("Error finding models with vision capability: {}", error)

      // Example 5: Estimate costs
      logger.info("Example 5: Estimating completion costs")
      logger.info("-" * 80)
      service.lookup("gpt-5.6-terra") match {
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

      // Example 6: Create a registry snapshot with a custom model
      logger.info("Example 6: Creating a registry snapshot with a custom model")
      logger.info("-" * 80)
      val customModel = ModelMetadata(
        modelId = "my-custom-llm-v1",
        provider = "custom",
        mode = ModelMode.Chat,
        maxInputTokens = Some(32000),
        maxOutputTokens = Some(8000),
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

      val customService = ModelRegistryService.fromModels(Seq(customModel))
      logger.info("Created registry snapshot for custom model: {}", customModel.modelId)

      customService.lookup("my-custom-llm-v1") match {
        case Right(metadata) =>
          logger.info("  Description: {}", metadata.description)
          logger.info("  Context window: {} tokens", metadata.contextWindow.get)
        case Left(error) =>
          logger.error("  Error retrieving: {}", error)
      }

      // Example 7: Search model ids (discovery; lookup is exact)
      logger.info("Example 7: Searching model ids for 'sonnet'")
      logger.info("-" * 80)
      service.search("sonnet").take(5).foreach(m => logger.info("  - {}", m.modelId))

      // Example 8: List all providers
      logger.info("Example 8: Available providers")
      logger.info("-" * 80)

      val providers = service.listProviders()
      logger.info("Found {} providers:", providers.size)
      providers.foreach(p => logger.info("  - {}", p))

      // Example 9: Build a registry snapshot from JSON
      logger.info("Example 9: Building a registry snapshot from JSON")
      logger.info("-" * 80)
      val customJson =
        """{
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

      ModelRegistryService.fromJsonString(customJson) match {
        case Right(jsonService) =>
          logger.info("Custom metadata snapshot created successfully")
          jsonService.lookup("my-experimental-model") match {
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
