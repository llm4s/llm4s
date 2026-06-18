# AWS Bedrock Anthropic Integration - Implementation Summary

## Overview

Added AWS Bedrock support for Anthropic Claude models to llm4s. Users can now call Claude models through AWS Bedrock using IAM credentials instead of direct Anthropic API keys.

**Full build passes: 6656 tests green, all modules compile.**

---

## New Files Created

### `modules/core/src/main/scala/org/llm4s/llmconnect/provider/AnthropicMessageSupport.scala`

Shared trait extracted from `AnthropicClient` containing all message conversion, tool schema sanitization, response parsing, and serialization logic. Both `AnthropicClient` and `BedrockAnthropicClient` mix in this trait.

### `modules/core/src/main/scala/org/llm4s/llmconnect/provider/BedrockAnthropicClient.scala`

New provider client that uses `AnthropicOkHttpClient` with a `BedrockBackend` for AWS Default Credential Chain authentication. Identical message/streaming behavior to the direct Anthropic client.

---

## Modified Files (14 total)

| File | Change |
|------|--------|
| `project/Dependencies.scala` | Added `anthropicBedrock` dependency (`com.anthropic:anthropic-java-bedrock:2.11.1`) |
| `build.sbt` | Added dep to core + AWS SDK version overrides to prevent conflicts |
| `ProviderModelTypes.scala` | Added `BedrockAnthropic` to enum, `fromString`, `name` extension |
| `ProviderConfig.scala` | Added `BedrockAnthropicConfig` case class with `fromValues` factory |
| `DefaultConfig.scala` | Added `DEFAULT_BEDROCK_ANTHROPIC_REGION = "us-east-1"` |
| `NamedProviderValidator.scala` | Added `BedrockAnthropic` validator (`requireApiKey = false`, `requireBaseUrl = true`) |
| `ProviderCapabilities.scala` | Added `BedrockAnthropic` capabilities object |
| `ProviderCapabilitiesRegistry.scala` | Registered in the provider map |
| `NamedProviderLoader.scala` | Added config loading case for `ProviderKind.BedrockAnthropic` |
| `AnthropicClient.scala` | Refactored to mix in `AnthropicMessageSupport` trait, removed duplicated methods |
| `LLMConnect.scala` | Added routing in both `buildClient` and `fromProvider` |
| `ProviderKindSpec.scala` | Updated test assertions for 11 providers |
| `ProviderSetupRuntime.scala` | Added exhaustive match cases for `BedrockAnthropicConfig` |
| `PrometheusMetricsExample.scala` | Added exhaustive match case for `BedrockAnthropicConfig` |

---

## Architecture

```
AnthropicMessageSupport (trait)
  - addMessagesToParams()
  - convertToolToAnthropicTool()
  - stripAdditionalProperties()
  - convertFromAnthropicResponse()
  - extractToolCalls()
  - clampBudgetTokens()
  - applySamplingParameters()
  - serializeRequestBody/ResponseBody/StreamEvent()
        |
        +--- AnthropicClient (direct API via AnthropicOkHttpClient)
        |
        +--- BedrockAnthropicClient (AWS Bedrock via AnthropicOkHttpClient + BedrockBackend)
```

The key difference: `BedrockAnthropicClient` initializes its HTTP client with:
```scala
AnthropicOkHttpClient.builder()
  .backend(BedrockBackend.builder().region(Region.of(config.region)).build())
  .build()
```

This uses the AWS Default Credential Provider Chain automatically.

---

## Usage

### HOCON Configuration

```hocon
llm4s.providers {
  provider = "my-bedrock"
  my-bedrock {
    provider = "bedrock-anthropic"
    model = "anthropic.claude-3-5-sonnet-20241022-v2:0"
    baseUrl = "us-east-1"   # AWS region (stored in baseUrl field)
  }
}
```

### Environment Variables

```bash
# AWS credentials via standard chain (no Anthropic API key needed)
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-1
```

### Programmatic Usage

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{LLMConnect, LlmClientOptions}
import org.llm4s.llmconnect.config.BedrockAnthropicConfig
import org.llm4s.llmconnect.model.*

// Via named provider config (recommended)
for {
  registryService <- Llm4sConfig.modelRegistryService()
  given org.llm4s.model.ModelRegistryService = registryService
  providerCfg <- Llm4sConfig.defaultProvider()
  client      <- LLMConnect.getClient(providerCfg)
  response    <- client.complete(Conversation(Seq(UserMessage("Hello from Bedrock!"))))
} yield response

// Direct construction
given resolver: org.llm4s.llmconnect.config.ContextWindowResolver = ???
given registry: org.llm4s.model.ModelRegistryService = ???

val config = BedrockAnthropicConfig.fromValues(
  modelName = "anthropic.claude-3-5-sonnet-20241022-v2:0",
  region = "us-east-1"
)
val client = LLMConnect.getClient(config)
```

---

## Key Design Decisions

1. **No API key field** - `BedrockAnthropicConfig` deliberately omits `apiKey`; the AWS Default Credential Provider Chain handles authentication.

2. **`baseUrl` holds the region** - Following Azure's precedent of repurposing existing config fields, the `baseUrl` field in HOCON config stores the AWS region string. This avoids schema changes to `RawNamedProviderSection`.

3. **Shared trait over inheritance** - Extracted `AnthropicMessageSupport` as a trait rather than making `AnthropicClient` open for extension. Cleaner separation with no risk to existing code.

4. **AWS SDK version pinning** - Added `dependencyOverrides` in `build.sbt` to keep AWS SDK at 2.29.51 (the version already in use), preventing the `anthropic-java-bedrock` dependency from pulling in 2.33.1 which introduces a deprecation warning that breaks `-Werror`.

---

## Verification

1. `sbt compile` - exhaustive match ensures all routing is wired
2. `sbt buildAll` - all modules compile + 6656 tests pass
3. Manual smoke: configure a `bedrock-anthropic` provider, run a sample with valid AWS creds
4. Cross-region inference models (e.g., `us.anthropic.claude-*`) work by passing the model string verbatim to the SDK
