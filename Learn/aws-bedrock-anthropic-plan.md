# Plan: AWS Bedrock Support for Anthropic Models

## Context

The goal is to allow llm4s users to call Anthropic Claude models through AWS Bedrock instead of the direct Anthropic API. This enables enterprises that standardize on AWS IAM for access control to use Claude without managing separate Anthropic API keys. The Anthropic Java SDK (already v2.11.1 in the project) publishes a `anthropic-java-bedrock` module that provides `AnthropicBedrockOkHttpClient` — it produces the same `Message`, `MessageCreateParams`, and streaming types as the direct client, so the heavy message-conversion logic can be reused.

**User choices:**
- Credentials: AWS Default Credential Chain (env vars, `~/.aws/credentials`, IAM roles)
- Model prefix: `bedrock-anthropic/` (e.g., `LLM_MODEL=bedrock-anthropic/anthropic.claude-3-5-sonnet-20241022-v2:0`)
- Region: via `baseUrl` field in config

---

## Implementation Steps

### 1. Add Dependency

**`project/Dependencies.scala`** — add:
```scala
val anthropicBedrock = "com.anthropic" % "anthropic-java-bedrock" % Versions.anthropic
```

**`build.sbt`** — add `Deps.anthropicBedrock` to core's `libraryDependencies` (after `Deps.anthropic`).

Run `sbt evicted` to verify no version conflicts with existing AWS SDK 2.29.51.

---

### 2. Register Provider Kind

**`modules/core/src/main/scala/org/llm4s/types/ProviderModelTypes.scala`**

- Add `case BedrockAnthropic` to `ProviderKind` enum
- Add to `ProviderKind.all`
- Add `case "bedrock-anthropic" => Some(ProviderKind.BedrockAnthropic)` in `fromString`
- Add `case ProviderKind.BedrockAnthropic => "bedrock-anthropic"` in `name` extension

After this step, `-Werror` + exhaustive matching will flag every file that needs updating.

---

### 3. Add Config Case Class

**`modules/core/src/main/scala/org/llm4s/llmconnect/config/ProviderConfig.scala`** — add after `MistralConfig`:

```scala
case class BedrockAnthropicConfig(
  region: String,
  model: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val provider: ProviderKind = ProviderKind.BedrockAnthropic
  override def toString: String =
    s"BedrockAnthropicConfig(region=$region, model=$model, contextWindow=$contextWindow, reserveCompletion=$reserveCompletion)"
```

With companion providing `fromValues(modelName, region)(using ContextWindowResolver)`. No `apiKey` field — uses AWS default cred chain. Fallback context window: 200000 for Claude models.

**`modules/core/src/main/scala/org/llm4s/config/DefaultConfig.scala`** — add:
```scala
val DEFAULT_BEDROCK_ANTHROPIC_REGION = "us-east-1"
```

---

### 4. Config Validation and Loading

**`NamedProviderValidator.scala`** — add `BedrockAnthropic` validator with `requireApiKey = false`, `requireBaseUrl = true` (baseUrl holds the region).

**`ProviderCapabilities.scala`** — add `BedrockAnthropic` object (no model lister initially).

**`ProviderCapabilitiesRegistry.scala`** — add to registry map.

**`NamedProviderLoader.scala`** — add case:
```scala
case ProviderKind.BedrockAnthropic =>
  val region = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_BEDROCK_ANTHROPIC_REGION)
  Right(BedrockAnthropicConfig.fromValues(section.model.asString, region))
```

---

### 5. Extract Shared Trait

**New file: `modules/core/src/main/scala/org/llm4s/llmconnect/provider/AnthropicMessageSupport.scala`**

Extract from `AnthropicClient` into a trait:
- `addMessagesToParams(conversation, paramsBuilder)` — message format conversion
- `convertToolToAnthropicTool(toolFunction)` — schema sanitization
- `stripAdditionalProperties(json)` — recursive schema cleanup
- `convertFromAnthropicResponse(response, model)` — response mapping (accept model as param)
- `extractToolCalls(response)` — tool call extraction
- `clampBudgetTokens(budgetTokens, maxTokens)` — thinking budget clamping
- `applySamplingParameters(builder, options)` — temperature setting
- `serializeRequestBody/serializeResponseBody/serializeStreamEvent` — serialization helpers

Then refactor `AnthropicClient` to `extends BaseLifecycleLLMClient with AnthropicMessageSupport`.

---

### 6. New BedrockAnthropicClient

**New file: `modules/core/src/main/scala/org/llm4s/llmconnect/provider/BedrockAnthropicClient.scala`**

```scala
class BedrockAnthropicClient(
  config: BedrockAnthropicConfig,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient with AnthropicMessageSupport {

  // Initialize Bedrock client using AWS Default Credential Chain
  private val client = AnthropicBedrockOkHttpClient
    .builder()
    .region(config.region)
    .build()

  protected def clientDescription: String = s"Bedrock Anthropic client for model ${config.model}"
  protected def providerName: String      = "bedrock-anthropic"
  protected def modelName: String         = config.model

  // complete() and streamComplete() follow the same structure as AnthropicClient
  // but use the Bedrock client's message service
}
```

Key difference from `AnthropicClient`: client initialization uses:
```scala
AnthropicBedrockOkHttpClient.builder().region(config.region).build()
```

The `complete()` and `streamComplete()` methods follow the same structure — build params, call `client.messages().create()`/`createStreaming()`, parse response using shared trait methods.

Error mapping adds AWS-specific exceptions (`SdkException` -> `AuthenticationError`).

---

### 7. Wire Up Routing

**`LLMConnect.scala`** — add in both `buildClient` and `fromProvider`:
```scala
case cfg: BedrockAnthropicConfig => BedrockAnthropicClient(cfg, metrics, exchangeLogging)
case (ProviderKind.BedrockAnthropic, cfg: BedrockAnthropicConfig) => BedrockAnthropicClient(cfg, metrics, exchangeLogging)
```

---

### 8. Tests

**Unit tests** (no AWS creds needed):
- `BedrockAnthropicConfig.fromValues` resolves context window correctly
- `NamedProviderValidator` passes without apiKey, fails without baseUrl
- `ProviderKind.fromString("bedrock-anthropic")` round-trips
- `LLMConnect.fromProvider` rejects mismatched config types
- HOCON parsing with bedrock-anthropic provider section

**Integration smoke** (tagged, requires AWS creds):
- Simple `complete()` call
- `streamComplete()` call
- Verify missing credentials gives `AuthenticationError`

---

## Sample Usage

```hocon
# application.conf
llm4s.providers {
  provider = "my-bedrock"
  my-bedrock {
    provider = "bedrock-anthropic"
    model = "anthropic.claude-3-5-sonnet-20241022-v2:0"
    baseUrl = "us-east-1"
  }
}
```

```bash
# Environment — AWS creds via standard chain
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=...
export AWS_REGION=us-east-1
```

---

## Verification

1. `sbt compile` — exhaustive match ensures all routing is wired
2. `sbt +test` — unit tests pass without AWS credentials
3. Manual smoke: configure a bedrock-anthropic provider, run a sample with valid AWS creds
4. `sbt evicted` — no dependency conflicts

---

## Files Modified (summary)

| File | Change |
|------|--------|
| `project/Dependencies.scala` | Add `anthropicBedrock` dep |
| `build.sbt` | Add to core libraryDependencies |
| `ProviderModelTypes.scala` | Add `BedrockAnthropic` enum + mappings |
| `ProviderConfig.scala` | Add `BedrockAnthropicConfig` case class |
| `DefaultConfig.scala` | Add region constant |
| `NamedProviderValidator.scala` | Add `BedrockAnthropic` validator |
| `ProviderCapabilities.scala` | Add `BedrockAnthropic` object |
| `ProviderCapabilitiesRegistry.scala` | Register in map |
| `NamedProviderLoader.scala` | Add loading case |
| `AnthropicClient.scala` | Mix in shared trait (refactor) |
| `LLMConnect.scala` | Add routing (2 locations) |
| **New:** `AnthropicMessageSupport.scala` | Shared message logic trait |
| **New:** `BedrockAnthropicClient.scala` | Bedrock client implementation |

---

## Risks and Edge Cases

1. **AWS SDK version alignment**: The project uses AWS SDK 2.29.51 for S3/STS. The `anthropic-java-bedrock` module transitively depends on `software.amazon.awssdk:bedrockruntime`. Use `sbt evicted` to check; apply `dependencyOverrides` if needed.

2. **Credential chain failures**: The AWS Default Credential Provider Chain can throw various exceptions. Map `SdkException`, `StsException`, and `AwsServiceException` to `AuthenticationError` or `ConfigurationError`.

3. **Cross-region inference**: Bedrock supports cross-region inference profiles (e.g., `us.anthropic.claude-3-5-sonnet-20241022-v2:0`). Pass the model string verbatim — the SDK handles routing.

4. **Extended thinking**: Bedrock may not support extended thinking for all model versions. The existing error mapping to `ValidationError` handles graceful rejection.

5. **`baseUrl` semantic overloading**: Using `baseUrl` for "region" works (Azure already repurposes `endpoint`) but is semantically confusing. A future improvement could add a dedicated `region` field to `RawNamedProviderSection`.
