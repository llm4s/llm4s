---
layout: page
title: Model Metadata
description: "The LLM4S model registry: context windows, prices and capabilities from bundled LiteLLM metadata, looked up through ModelRegistryService."
search_title: "LLM4S model registry: context windows and pricing"
parent: Reference
nav_order: 21
---

# Model Metadata
{: .no_toc }

What LLM4S knows about a model - its context window, output limit, prices and capabilities - and how to look it up
or supply your own.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

`ModelRegistryService` (in `llm4s-core`, package `org.llm4s.model`) is an immutable snapshot of model metadata. The
provider clients use it to find a model's context window and output limit, the request rules a model needs (which
reasoning levels it takes, which parameters it rejects) and its prices, which give `Completion.estimatedCost`.

`LLMConnect.getClient` takes the registry as a `using` parameter, so an application builds one at its edge and passes
it down:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService

for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

`Llm4sConfig.modelRegistryService()` reads the `llm4s.modelRegistry` block (see [Configuration](#configuration)).
`ModelRegistryService.default()` returns the bundled catalogue without reading any configuration, for code that
builds its provider config from values.

## Where the data comes from

The bundled catalogue is a vendored copy of
[LiteLLM's model metadata](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json),
`modules/core/src/main/resources/modeldata/litellm_model_metadata.json`, refreshed from upstream periodically. It
covers OpenAI, Anthropic, Gemini, Azure, Bedrock, Mistral, DeepSeek, xAI, Ollama and many hosted models, with context
windows, prices per token and capability flags.

### Embedded overrides

Upstream occasionally drops or mis-shapes entries llm4s relies on (a chat model regressing to an `embedding` row, or a
canonical alias being removed so a substring fallback resolves to a third-party-hosted variant). So that the vendored
snapshot stays a clean, refreshable copy, llm4s layers a small curated corrections file over it:
`modules/core/src/main/resources/modeldata/llm4s_model_overrides.json`. Its entries take precedence over the snapshot
and survive refreshes. The overlay is applied **only** to the bundled default resource; a file, URL or other resource
you configure is loaded as it is. A missing or unparseable overrides file is not fatal: the snapshot is used alone.

An override entry replaces the snapshot's entry of the same id whole, so a correction can drop a key the snapshot
should not have. An entry with `"llm4s_patch": true` instead sets only the keys it lists and keeps the rest of the
snapshot's entry, prices included, so a flag LiteLLM does not carry can be added without freezing the entry against
future refreshes. A patch for an id the snapshot does not have is skipped with a warning. This is how
`supports_budget_thinking: false` (`ModelCapabilities.supportsBudgetThinking`) marks the models that reject a manual
thinking budget ([RFC 0008](../rfcs/0008-generation-controls)):

```json
"claude-opus-4-7": {
    "llm4s_patch": true,
    "supports_budget_thinking": false
}
```

## Looking a model up

`lookup(modelId)` and `lookup(provider, modelName)` return a `Result[ModelMetadata]`. An id that is not an exact key
is matched without case, then without a `provider/` prefix; an unknown model is a `Left`.

```scala
import org.llm4s.model.ModelRegistryService

def describe(registry: ModelRegistryService): Unit =
  registry.lookup("gpt-4o") match {
    case Right(metadata) =>
      println(s"Context window: ${metadata.contextWindow.getOrElse("unknown")}")
      println(s"Output limit: ${metadata.reserveCompletion.getOrElse("unknown")}")
      println(s"Supports vision: ${metadata.supports("vision")}")
      println(metadata.description)
    case Left(error) =>
      println(s"Model not found: ${error.message}")
  }
```

`ModelMetadata` carries `modelId`, `provider`, `mode` (a `ModelMode`: `Chat`, `Embedding`, `Completion`,
`ImageGeneration`, `AudioTranscription`, `AudioSpeech`, `Moderation`, `Rerank`, `Search` or `Unknown`),
`maxInputTokens`, `maxOutputTokens`, `capabilities`, `pricing` and `deprecationDate`, and derives:

| Member | Meaning |
|---|---|
| `contextWindow: Option[Int]` | `maxInputTokens`, else `maxOutputTokens` |
| `reserveCompletion: Option[Int]` | `maxOutputTokens` |
| `supports(capability: String): Boolean` | A capability flag; see below |
| `isDeprecated: Boolean` | `deprecationDate` is today or earlier |
| `description: String` | One line: id, provider, context window and capabilities |

`supports` takes `function_calling` (or `tools`), `parallel_function_calling`, `vision` (or `images`),
`prompt_caching` (or `caching`), `reasoning`, `response_schema` (or `structured`), `system_messages`, `pdf_input`
(or `pdf`), `audio_input`, `audio_output`, `web_search`, `computer_use`, `assistant_prefill` (or `prefill`),
`tool_choice` and `native_streaming` (or `streaming`). A flag the metadata does not set counts as `false`, except
streaming, which counts as `true`; any other name is `false`.

### Estimating a cost

```scala
import org.llm4s.model.ModelRegistryService

def estimate(registry: ModelRegistryService): Option[Double] =
  registry.lookup("gpt-4o").toOption.flatMap(_.pricing.estimateCost(inputTokens = 10000, outputTokens = 2000))
```

`estimateCost` multiplies by `pricing.inputCostPerToken` and `pricing.outputCostPerToken`, and is `None` when either
is unknown. `ModelPricing` also carries the cache write and read prices, batch and priority prices, the reasoning-token
price and the audio and image prices the metadata gives.

### Listing and searching

```scala
import org.llm4s.model.{ ModelMode, ModelRegistryService }

def survey(registry: ModelRegistryService): Unit = {
  registry.findByCapability("vision").foreach(models => println(s"${models.size} vision models"))
  registry.listByProvider("openai").foreach(models => models.take(5).foreach(m => println(m.modelId)))
  registry.listByMode(ModelMode.Embedding).foreach(models => println(s"${models.size} embedding models"))
  registry.listProviders().foreach(providers => println(providers.mkString(", ")))
  registry.statistics().foreach(stats => println(s"${stats("totalModels")} models, ${stats("providers")} providers"))
}
```

`statistics()` reports `totalModels`, `providers`, `chatModels`, `embeddingModels`, `imageGenerationModels` and
`deprecatedModels`.

## Your own metadata

A registry is a snapshot: nothing is registered into it after it is built. To use metadata of your own, build the
registry from it.

From configuration, at the application edge, point `llm4s.modelRegistry` at a file, a URL or a classpath resource in
LiteLLM's format (see [Configuration](#configuration)); `Llm4sConfig.modelRegistryService()` then loads it once at
startup. The source replaces the bundled catalogue, so it must contain every model your providers use.

In code:

```scala
import org.llm4s.model.{ ModelCapabilities, ModelMetadata, ModelMode, ModelPricing, ModelRegistryService }
import org.llm4s.types.Result

// a closed set of models, for example in tests
val inHouse = ModelMetadata(
  modelId = "my-custom-llm",
  provider = "custom",
  mode = ModelMode.Chat,
  maxInputTokens = Some(32000),
  maxOutputTokens = Some(8000),
  inputCostPerToken = Some(1e-6),
  outputCostPerToken = Some(3e-6),
  capabilities = ModelCapabilities(supportsFunctionCalling = Some(true)),
  pricing = ModelPricing(inputCostPerToken = Some(1e-6), outputCostPerToken = Some(3e-6)),
  deprecationDate = None
)
val closed: ModelRegistryService = ModelRegistryService.fromModels(Seq(inHouse))

// a catalogue in LiteLLM's JSON format, from a string, a file or a URL
val fromJson: Result[ModelRegistryService] = ModelRegistryService.fromJsonString(
  """{ "my-model": { "litellm_provider": "custom", "mode": "chat", "max_input_tokens": 16000,
    |  "max_output_tokens": 4000, "supports_function_calling": true } }""".stripMargin
)
val fromFile: Result[ModelRegistryService] = ModelRegistryService.fromFile("/etc/llm4s/models.json")
```

`fromFile`, `fromUrl` and `fromJsonString` apply no overrides; entries that cannot be read are logged and skipped. For
duplicate ids in `fromModels` the last one wins.

To correct one model's context window without replacing the catalogue, set it on the provider section instead:
`contextWindow` and `reserveCompletion` are keys of the generic `openai-compatible` and Perplexity sections, among
others (see [Providers](../guide/providers#provider-specific-keys)).

The JSON format is LiteLLM's:

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

## Configuration

Exactly one source is used; set one key, or none for the bundled catalogue.

| Key / Variable | Description | Example |
|----------------|-------------|---------|
| `llm4s.modelRegistry.resourcePath` / `LLM4S_MODEL_REGISTRY_RESOURCE` | Classpath resource containing model metadata | `/modeldata/litellm_model_metadata.json` |
| `llm4s.modelRegistry.filePath` / `LLM4S_MODEL_REGISTRY_FILE` | Path to a model metadata file loaded at boot | `/path/to/models.json` |
| `llm4s.modelRegistry.url` / `LLM4S_MODEL_REGISTRY_URL` | URL of a model metadata file loaded at boot | `https://example.com/models.json` |

`Llm4sConfig.modelRegistryConfig()` returns the `ModelRegistryConfig` these keys describe, and
`ModelRegistryService.fromConfig(config)` builds a registry from one built in code.

## API summary

| `ModelRegistryService` | Description |
|---|---|
| `lookup(modelId: String): Result[ModelMetadata]` | A model by id |
| `lookup(provider: String, modelName: String): Result[ModelMetadata]` | A model by provider and name |
| `listByProvider(provider: String): Result[List[ModelMetadata]]` | Every model of a provider |
| `listByMode(mode: ModelMode): Result[List[ModelMetadata]]` | Every model of a mode |
| `findByCapability(capability: String): Result[List[ModelMetadata]]` | Every model with a capability |
| `listProviders(): Result[List[String]]` | The providers present |
| `statistics(): Result[Map[String, Any]]` | Counts, as above |

| `ModelRegistryService` companion | Description |
|---|---|
| `default(): Result[ModelRegistryService]` | The bundled catalogue with overrides, no configuration read |
| `fromConfig(config: ModelRegistryConfig)` | Exactly one of resource, file or URL |
| `fromResource(path)` / `fromFile(path)` / `fromUrl(url)` / `fromJsonString(json)` | One source, as a `Result` |
| `fromModels(models: Iterable[ModelMetadata]): ModelRegistryService` | A snapshot of the given values |

## Running the example

```bash
sbt "samples/runMain org.llm4s.samples.model.ModelMetadataExample"
```

It looks models up, checks capabilities, estimates costs and lists providers.

## See also

- [Providers](../guide/providers): where each provider takes its context window from
- [Cost controls](../guide/cost-controls): reasoning levels, prompt caching and service tiers, which use these flags
- Tests: `org.llm4s.model.ModelRegistryServiceSpec`, `org.llm4s.model.ModelRegistryLookupSpec`,
  `org.llm4s.model.ModelMetadataSpec`
