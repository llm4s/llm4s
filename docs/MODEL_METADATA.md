# Model Metadata System

## Overview

The LLM4S model metadata system provides a centralized, type-safe way to query information about LLM models, including:

- Context window sizes and token limits
- Pricing information (input/output costs)
- Capabilities (vision, function calling, caching, etc.)
- Provider information
- Deprecation status

This system addresses [Issue #233](https://github.com/llm4s/llm4s/issues/233) by creating proper model metadata for use throughout the code.

## Key Components

### ModelMetadata

The `ModelMetadata` case class represents comprehensive information about a model:

```scala
case class ModelMetadata(
  modelId: String,
  provider: String,
  mode: ModelMode,
  maxInputTokens: Option[Int],
  maxOutputTokens: Option[Int],
  inputCostPerToken: Option[Double],
  outputCostPerToken: Option[Double],
  capabilities: ModelCapabilities,
  pricing: ModelPricing,
  deprecationDate: Option[String]
)
```

Key methods:
- `contextWindow: Option[Int]` - Get the effective context window size
- `supports(capability: String): Boolean` - Check if a capability is supported
- `isDeprecated: Boolean` - Check if the model is deprecated
- `description: String` - Get a human-readable description

### ModelRegistry

The `ModelRegistry` object provides a singleton lookup service for model metadata:

```scala
// Initialize the registry (happens automatically on first use)
ModelRegistry.initialize()

// Lookup a model
val metadata = ModelRegistry.lookup("gpt-4o")

// Lookup by provider and model name
val metadata = ModelRegistry.lookup("openai", "gpt-4o")

// List models by provider
val openaiModels = ModelRegistry.listByProvider("openai")

// Find models by capability
val visionModels = ModelRegistry.findByCapability("vision")

// Get all providers
val providers = ModelRegistry.listProviders()
```

## Data Source

The system uses [LiteLLM's model metadata](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json) as its authoritative source. This JSON file contains:

- **24,000+ lines** of model configuration data
- Models from **multiple providers**: OpenAI, Anthropic, Azure, Bedrock, Ollama, and more
- Up-to-date pricing and context window information
- Capability flags for each model

The metadata file is:
- **Embedded** in the library at compile time (`modules/core/src/main/resources/modeldata/litellm_model_metadata.json`)
- **Updateable** at runtime from external sources
- **Extensible** with custom model definitions

## Usage Examples

### Basic Model Lookup

```scala
import org.llm4s.model.ModelRegistry

// Lookup GPT-4o
ModelRegistry.lookup("gpt-4o") match {
  case Right(metadata) =>
    println(s"Context window: ${metadata.contextWindow.getOrElse("unknown")}")
    println(s"Supports vision: ${metadata.supports("vision")}")
    println(s"Input cost: ${metadata.inputCostPerToken.getOrElse("unknown")}")
  case Left(error) =>
    println(s"Model not found: $error")
}
```

### Checking Capabilities

```scala
// Check if a model supports specific features
ModelRegistry.lookup("claude-sonnet-4-5-latest") match {
  case Right(metadata) =>
    if (metadata.supports("function_calling")) {
      println("Model supports function calling!")
    }
    if (metadata.supports("vision")) {
      println("Model supports vision!")
    }
    if (metadata.supports("caching")) {
      println("Model supports prompt caching!")
    }
  case Left(error) =>
    println(s"Error: $error")
}
```

Supported capability queries:
- `function_calling`, `tools`
- `vision`, `images`
- `prompt_caching`, `caching`
- `reasoning`
- `response_schema`, `structured`
- `system_messages`
- `pdf_input`, `pdf`
- `audio_input`, `audio_output`
- `web_search`
- `computer_use`
- `assistant_prefill`, `prefill`
- `tool_choice`

### Estimating Costs

```scala
ModelRegistry.lookup("gpt-4o") match {
  case Right(metadata) =>
    val cost = metadata.pricing.estimateCost(
      inputTokens = 10000,
      outputTokens = 2000
    )
    cost.foreach(c => println(f"Estimated cost: $$${c}%.6f"))
  case Left(error) =>
    println(s"Error: $error")
}
```

### Finding Models by Capability

```scala
// Find all models with vision support
ModelRegistry.findByCapability("vision") match {
  case Right(models) =>
    models.foreach { model =>
      println(s"${model.modelId} - ${model.provider}")
    }
  case Left(error) =>
    println(s"Error: $error")
}
```

### Listing Models by Provider

```scala
// Get all OpenAI models
ModelRegistry.listByProvider("openai") match {
  case Right(models) =>
    println(s"Found ${models.size} OpenAI models")
    models.foreach { model =>
      println(s"  ${model.modelId}")
    }
  case Left(error) =>
    println(s"Error: $error")
}
```

## Custom Models

### Registering Individual Models

You can register custom models not in the LiteLLM dataset:

```scala
import org.llm4s.model._

val customModel = ModelMetadata(
  modelId = "my-custom-llm",
  provider = "custom",
  mode = ModelMode.Chat,
  maxInputTokens = Some(32000),
  maxOutputTokens = Some(8000),
  inputCostPerToken = Some(1e-6),
  outputCostPerToken = Some(3e-6),
  capabilities = ModelCapabilities(
    supportsFunctionCalling = Some(true),
    supportsVision = Some(false)
  ),
  pricing = ModelPricing(
    inputCostPerToken = Some(1e-6),
    outputCostPerToken = Some(3e-6)
  ),
  deprecationDate = None
)

ModelRegistry.register(customModel)
```

### Loading Custom Metadata Files

You can override or extend the embedded metadata with a custom JSON file:

**Option 1: Environment Variable**

```bash
export LLM4S_MODEL_METADATA_FILE=/path/to/custom_models.json
```

The registry will automatically load this file on initialization.

**Option 2: Programmatic Loading**

```scala
// Load from file
ModelRegistry.loadCustomMetadata("/path/to/custom_models.json")

// Load from JSON string
val customJson = """{
  "my-model": {
    "litellm_provider": "custom",
    "mode": "chat",
    "max_input_tokens": 16000,
    "max_output_tokens": 4000,
    "supports_function_calling": true
  }
}"""

ModelRegistry.loadCustomMetadataFromString(customJson)
```

**Custom Metadata Format**

The JSON format follows LiteLLM's schema:

```json
{
  "model-id": {
    "litellm_provider": "provider-name",
    "mode": "chat",
    "max_input_tokens": 128000,
    "max_output_tokens": 16384,
    "input_cost_per_token": 2.5e-6,
    "output_cost_per_token": 1e-5,
    "supports_function_calling": true,
    "supports_vision": true,
    "supports_prompt_caching": true
  }
}
```

### Overriding Existing Models

Custom models take precedence over embedded metadata, so you can override existing model definitions:

```scala
// Override GPT-4o with custom context window
val override = ModelMetadata(
  modelId = "gpt-4o",
  provider = "openai",
  mode = ModelMode.Chat,
  maxInputTokens = Some(200000), // Custom larger window
  maxOutputTokens = Some(16384),
  // ... other fields
)

ModelRegistry.register(override)

// Now lookups will use the custom definition
ModelRegistry.lookup("gpt-4o") // Returns overridden metadata
```

## Dynamic Updates

You can update the registry with fresh metadata from LiteLLM's GitHub repository:

```scala
// Update from the default LiteLLM source
ModelRegistry.updateFromUrl()

// Or specify a custom URL
ModelRegistry.updateFromUrl("https://example.com/custom_metadata.json")
```

This is useful for:
- Getting the latest model information
- Updating pricing data
- Adding newly released models

## Integration with Provider Configs

The provider configuration classes (`OpenAIConfig`, `AnthropicConfig`, etc.) automatically use the `ModelRegistry` to lookup context windows and other metadata:

```scala
// When creating a provider config, it looks up metadata automatically
val config = OpenAIConfig("gpt-4o", configReader)
// Context window and reserve completion are pulled from ModelRegistry
```

The system uses a **fallback strategy**:
1. Try to lookup from `ModelRegistry`
2. If not found, use hardcoded fallback values
3. Log a debug message indicating which source was used

This ensures backwards compatibility while leveraging the new metadata system.

## Model Modes

Models can operate in different modes:

```scala
sealed trait ModelMode
object ModelMode {
  case object Chat               // Conversational models
  case object Embedding          // Text embedding models
  case object Completion         // Legacy completion models
  case object ImageGeneration    // Image generation (DALL-E, etc.)
  case object AudioTranscription // Speech-to-text
  case object AudioSpeech        // Text-to-speech
  case object Moderation         // Content moderation
  case object Rerank             // Document reranking
  case object Search             // Search models
  case object Unknown            // Fallback
}
```

## Statistics and Monitoring

Get statistics about the loaded metadata:

```scala
val stats = ModelRegistry.statistics()

println(s"Total models: ${stats("totalModels")}")
println(s"Embedded models: ${stats("embeddedModels")}")
println(s"Custom models: ${stats("customModels")}")
println(s"Providers: ${stats("providers")}")
println(s"Chat models: ${stats("chatModels")}")
println(s"Deprecated models: ${stats("deprecatedModels")}")
```

## Running the Example

To see the model metadata system in action:

```bash
sbt "samples/runMain org.llm4s.samples.model.ModelMetadataExample"
```

This example demonstrates:
- Model lookups
- Capability checking
- Cost estimation
- Provider listing
- Custom model registration
- Custom metadata loading

## API Reference

### ModelRegistry

| Method | Description |
|--------|-------------|
| `initialize(): Result[Unit]` | Initialize the registry (automatic on first use) |
| `lookup(modelId: String): Result[ModelMetadata]` | Lookup model by ID |
| `lookup(provider: String, modelName: String): Result[ModelMetadata]` | Lookup by provider and name |
| `listByProvider(provider: String): Result[List[ModelMetadata]]` | Get all models for a provider |
| `listByMode(mode: ModelMode): Result[List[ModelMetadata]]` | Get all models of a specific mode |
| `findByCapability(capability: String): Result[List[ModelMetadata]]` | Find models with a capability |
| `listProviders(): Result[List[String]]` | Get all available providers |
| `register(metadata: ModelMetadata): Unit` | Register a custom model |
| `registerAll(models: List[ModelMetadata]): Unit` | Register multiple models |
| `loadCustomMetadata(filePath: String): Result[Unit]` | Load custom metadata from file |
| `loadCustomMetadataFromString(json: String): Result[Unit]` | Load from JSON string |
| `updateFromUrl(url: String): Result[Unit]` | Update from external source |
| `statistics(): Map[String, Any]` | Get registry statistics |
| `reset(): Unit` | Clear all metadata (for testing) |

### ModelMetadata

| Method | Description |
|--------|-------------|
| `contextWindow: Option[Int]` | Get effective context window size |
| `reserveCompletion: Option[Int]` | Get output token capacity |
| `supports(capability: String): Boolean` | Check if capability is supported |
| `isDeprecated: Boolean` | Check if model is deprecated |
| `description: String` | Get human-readable description |

### ModelPricing

| Method | Description |
|--------|-------------|
| `estimateCost(inputTokens: Int, outputTokens: Int): Option[Double]` | Estimate completion cost |
| `estimateCostWithCaching(inputTokens: Int, cachedTokens: Int, outputTokens: Int): Option[Double]` | Estimate cost with caching |

## Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `LLM4S_MODEL_METADATA_FILE` | Path to custom metadata file | `/path/to/models.json` |

## Best Practices

1. **Use the Registry**: Always prefer `ModelRegistry.lookup()` over hardcoded context windows
2. **Check Capabilities**: Verify model capabilities before using features
3. **Handle Missing Data**: Model metadata fields are optional, always handle `None` cases
4. **Cache Lookups**: The registry is optimized for repeated lookups, but cache results in hot paths
5. **Custom Models**: Use custom metadata for:
   - Internal models not in LiteLLM
   - Overriding incorrect metadata
   - Testing with mock models
6. **Update Periodically**: Consider updating from LiteLLM periodically for latest pricing/models

## Benefits

- **Centralized**: Single source of truth for model information
- **Type-Safe**: Compile-time safety with Scala types
- **Up-to-Date**: Based on actively maintained LiteLLM data
- **Extensible**: Easy to add custom models
- **Runtime Queries**: Check capabilities at runtime before using features
- **Cost Estimation**: Calculate costs before making API calls
- **No Hardcoding**: Eliminates scattered context window constants

## Migration from Hardcoded Values

Before (hardcoded):
```scala
val contextWindow = if (model.contains("gpt-4o")) 128000 else 8192
```

After (using ModelRegistry):
```scala
val contextWindow = ModelRegistry
  .lookup(model)
  .toOption
  .flatMap(_.contextWindow)
  .getOrElse(8192) // fallback
```

## See Also

- [LiteLLM Documentation](https://docs.litellm.ai/)
- [LiteLLM Model Metadata](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json)
- [Issue #233](https://github.com/llm4s/llm4s/issues/233)
- Sample: `org.llm4s.samples.model.ModelMetadataExample`
- Tests: `org.llm4s.model.ModelMetadataSpec`, `org.llm4s.model.ModelRegistrySpec`
