package org.llm4s.samples.model

import org.llm4s.model.{ ModelMetadata, ModelMode, ModelRegistry }

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

  println("=" * 80)
  println("LLM4S Model Metadata Example")
  println("=" * 80)
  println()

  // Initialize the registry (loads embedded metadata)
  println("Initializing ModelRegistry...")
  ModelRegistry.initialize() match {
    case Right(_) =>
      println("✓ ModelRegistry initialized successfully")
    case Left(error) =>
      println(s"✗ Failed to initialize: $error")
      sys.exit(1)
  }
  println()

  // Example 1: Lookup a specific model
  println("Example 1: Looking up GPT-4o metadata")
  println("-" * 80)
  ModelRegistry.lookup("gpt-4o") match {
    case Right(metadata) =>
      println(s"Model ID: ${metadata.modelId}")
      println(s"Provider: ${metadata.provider}")
      println(s"Mode: ${metadata.mode.name}")
      println(s"Context Window: ${metadata.contextWindow.getOrElse("unknown")} tokens")
      println(s"Max Output: ${metadata.maxOutputTokens.getOrElse("unknown")} tokens")
      println(s"Input Cost: ${metadata.inputCostPerToken.getOrElse("unknown")} per token")
      println(s"Output Cost: ${metadata.outputCostPerToken.getOrElse("unknown")} per token")
      println(s"Description: ${metadata.description}")
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 2: Check model capabilities
  println("Example 2: Checking Claude 3.7 Sonnet capabilities")
  println("-" * 80)
  ModelRegistry.lookup("claude-3-7-sonnet-latest") match {
    case Right(metadata) =>
      println(s"Model: ${metadata.modelId}")
      println(s"Supports function calling: ${metadata.supports("function_calling")}")
      println(s"Supports vision: ${metadata.supports("vision")}")
      println(s"Supports prompt caching: ${metadata.supports("caching")}")
      println(s"Supports reasoning: ${metadata.supports("reasoning")}")
      println(s"Supports PDFs: ${metadata.supports("pdf")}")
      println(s"Supports computer use: ${metadata.supports("computer_use")}")
      println(s"Is deprecated: ${metadata.isDeprecated}")
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 3: List models by provider
  println("Example 3: Listing OpenAI chat models")
  println("-" * 80)
  val openaiModels = for {
    allOpenAI  <- ModelRegistry.listByProvider("openai")
    chatModels <- Right(allOpenAI.filter(_.mode == ModelMode.Chat))
  } yield chatModels

  openaiModels match {
    case Right(models) =>
      println(s"Found ${models.size} OpenAI chat models:")
      models.take(10).foreach { model =>
        val ctx = model.contextWindow.map(c => f"${c / 1000}%dK").getOrElse("?")
        println(f"  - ${model.modelId}%-40s (${ctx} context)")
      }
      if (models.size > 10) println(s"  ... and ${models.size - 10} more")
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 4: Find models with specific capabilities
  println("Example 4: Finding models with vision support")
  println("-" * 80)
  ModelRegistry.findByCapability("vision") match {
    case Right(models) =>
      println(s"Found ${models.size} models with vision support:")
      models
        .filter(_.mode == ModelMode.Chat) // Only chat models
        .take(5)
        .foreach { model =>
          val provider = model.provider
          println(f"  - ${model.modelId}%-50s ($provider)")
        }
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 5: Estimate costs
  println("Example 5: Estimating completion costs")
  println("-" * 80)
  ModelRegistry.lookup("gpt-4o") match {
    case Right(metadata) =>
      val inputTokens  = 10000
      val outputTokens = 2000

      metadata.pricing.estimateCost(inputTokens, outputTokens) match {
        case Some(cost) =>
          println(s"Model: ${metadata.modelId}")
          println(s"Input tokens: $inputTokens")
          println(s"Output tokens: $outputTokens")
          println(f"Estimated cost: $$${cost}%.6f")
        case None =>
          println("Pricing information not available")
      }
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 6: Register a custom model
  println("Example 6: Registering a custom model")
  println("-" * 80)
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
  println(s"✓ Registered custom model: ${customModel.modelId}")

  ModelRegistry.lookup("my-custom-llm-v1") match {
    case Right(metadata) =>
      println(s"  Description: ${metadata.description}")
      println(s"  Context window: ${metadata.contextWindow.get} tokens")
    case Left(error) =>
      println(s"  Error retrieving: $error")
  }
  println()

  // Example 7: Get registry statistics
  println("Example 7: Registry statistics")
  println("-" * 80)
  val stats = ModelRegistry.statistics()
  println(s"Total models: ${stats("totalModels")}")
  println(s"Embedded models: ${stats("embeddedModels")}")
  println(s"Custom models: ${stats("customModels")}")
  println(s"Providers: ${stats("providers")}")
  println(s"Chat models: ${stats("chatModels")}")
  println(s"Embedding models: ${stats("embeddingModels")}")
  println(s"Image generation models: ${stats("imageGenerationModels")}")
  println(s"Deprecated models: ${stats("deprecatedModels")}")
  println()

  // Example 8: List all providers
  println("Example 8: Available providers")
  println("-" * 80)
  ModelRegistry.listProviders() match {
    case Right(providers) =>
      println(s"Found ${providers.size} providers:")
      providers.foreach(p => println(s"  - $p"))
    case Left(error) =>
      println(s"Error: $error")
  }
  println()

  // Example 9: Load custom metadata from JSON
  println("Example 9: Loading custom metadata from JSON")
  println("-" * 80)
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
      println("✓ Custom metadata loaded successfully")
      ModelRegistry.lookup("my-experimental-model") match {
        case Right(metadata) =>
          println(s"  Model: ${metadata.modelId}")
          println(s"  Provider: ${metadata.provider}")
          println(s"  Context: ${metadata.contextWindow.getOrElse("unknown")} tokens")
        case Left(error) =>
          println(s"  Error: $error")
      }
    case Left(error) =>
      println(s"✗ Failed to load: $error")
  }
  println()

  println("=" * 80)
  println("Example complete!")
  println("=" * 80)
}
