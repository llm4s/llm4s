# Implementation Plan: AWS Bedrock Adapter for llm4s

## Key Design Decisions

- **Use the Converse API** (`/model/{modelId}/converse`) — unified format across all Bedrock model families (Claude, Llama, Titan, Mistral), no model-specific request adaptation needed
- **SigV4 signing via AWS SDK** — already a transitive dependency (`s3`/`sts` pull in `auth`), safer than manual implementation
- **Event-stream binary decoder** for streaming — Bedrock uses `application/vnd.amazon.eventstream` framing, not SSE
- **No heavy Bedrock SDK** — raw HTTP + SigV4 keeps the classpath lean

## Implementation Sequence

### Step 1: Add `ProviderKind.Bedrock` Enum Value

**File:** `modules/core/src/main/scala/org/llm4s/types/ProviderModelTypes.scala`

- Add `case Bedrock` to the `ProviderKind` enum
- Add `ProviderKind.Bedrock` to the `all` sequence
- Add `"bedrock" => Some(ProviderKind.Bedrock)` in `fromString`
- Add `case ProviderKind.Bedrock => "bedrock"` in the `name` extension

### Step 2: Extend Config Model with Bedrock Fields

**Files:**
- `modules/core/src/main/scala/org/llm4s/config/ProvidersConfigModel.scala`
- `modules/core/src/main/scala/org/llm4s/config/RawProvidersConfigLoader.scala`
- `modules/core/src/main/scala/org/llm4s/config/NamedProviderConfigNormalizer.scala`

Add optional fields to `RawNamedProviderSection` and `NamedProviderConfig`:
- `region: Option[String]`
- `accessKeyId: Option[String]`
- `secretAccessKey: Option[String]`
- `sessionToken: Option[String]`

Update `forProduct7` -> `forProduct11` in the PureConfig reader.

### Step 3: Add `BedrockConfig` Case Class

**File:** `modules/core/src/main/scala/org/llm4s/llmconnect/config/ProviderConfig.scala`

```scala
case class BedrockConfig(
  region: String,
  accessKeyId: String,
  secretAccessKey: String,
  sessionToken: Option[String],
  model: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  override val provider: ProviderKind = ProviderKind.Bedrock
```

Companion object with `fromValues` factory method. Uses `ContextWindowResolver` with
`lookupProviders = Seq("bedrock", "bedrock_converse", "anthropic")` (litellm metadata already contains 330 bedrock entries).

### Step 4: Create SigV4 Signing Utility

**New File:** `modules/core/src/main/scala/org/llm4s/llmconnect/provider/aws/AwsSigV4Signer.scala`

Uses `software.amazon.awssdk.auth.signer.Aws4Signer` from the AWS SDK (already transitive).

```scala
object AwsSigV4Signer:
  def sign(
    method: String,
    url: String,
    headers: Map[String, String],
    body: Array[Byte],
    region: String,
    accessKeyId: String,
    secretAccessKey: String,
    sessionToken: Option[String],
    service: String = "bedrock"
  ): Map[String, String] = ...
```

### Step 5: Create Event-Stream Binary Decoder

**New File:** `modules/core/src/main/scala/org/llm4s/llmconnect/provider/aws/EventStreamDecoder.scala`

Bedrock streaming uses AWS event-stream binary protocol (not SSE). Each message:
- 12-byte prelude (total-length, headers-length, prelude-CRC)
- Headers
- JSON payload
- Message-CRC

Consider using `software.amazon.awssdk.protocols.eventstream.EventStreamMessage` (available transitively).

### Step 6: Implement `BedrockClient`

**New File:** `modules/core/src/main/scala/org/llm4s/llmconnect/provider/BedrockClient.scala`

Follows the `GeminiClient` pattern (raw HTTP via `Llm4sHttpClient`):

```scala
class BedrockClient(
  config: BedrockConfig,
  protected val metrics: MetricsCollector = MetricsCollector.noop,
  exchangeLogging: ProviderExchangeLogging = ProviderExchangeLogging.Disabled,
  private[provider] val httpClient: Llm4sHttpClient = Llm4sHttpClient.create()
)(using val registryService: ModelRegistryService)
    extends BaseLifecycleLLMClient
```

Endpoints:
- Complete: `POST https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse`
- Stream: `POST https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse-stream`

Converse API request format:
```json
{
  "modelId": "anthropic.claude-3-sonnet-20240229-v1:0",
  "messages": [{"role": "user", "content": [{"text": "Hello"}]}],
  "system": [{"text": "You are a helpful assistant"}],
  "inferenceConfig": {"maxTokens": 2048, "temperature": 0.7, "topP": 1.0},
  "toolConfig": {"tools": [...]}
}
```

### Step 7: Wire into `LLMConnect` Factory

**File:** `modules/core/src/main/scala/org/llm4s/llmconnect/LLMConnect.scala`

```scala
case cfg: BedrockConfig =>
  BedrockClient(cfg, metrics, exchangeLogging)
```

### Step 8: Wire Config Loading and Validation

**Files:**
- `modules/core/src/main/scala/org/llm4s/config/NamedProviderLoader.scala`
- `modules/core/src/main/scala/org/llm4s/config/NamedProviderValidator.scala`
- `modules/core/src/main/scala/org/llm4s/config/ProviderCapabilities.scala`
- `modules/core/src/main/scala/org/llm4s/config/ProviderCapabilitiesRegistry.scala`

Add Bedrock case in `buildConfigFromNamedConfig`:
```scala
case ProviderKind.Bedrock =>
  for
    region    <- required("region", section.region, "llm4s.providers.<name>.region")
    accessKey <- required("accessKeyId", section.accessKeyId, "llm4s.providers.<name>.accessKeyId")
    secret    <- required("secretAccessKey", section.secretAccessKey, "llm4s.providers.<name>.secretAccessKey")
  yield BedrockConfig.fromValues(
    section.model.asString, region, accessKey, secret, section.sessionToken
  )
```

### Step 9: Add Defaults and Reference Config

**Files:**
- `modules/core/src/main/scala/org/llm4s/config/DefaultConfig.scala`
- `modules/core/src/main/resources/reference.conf`

```hocon
# bedrock-main {
#   provider = "bedrock"
#   model = "anthropic.claude-3-sonnet-20240229-v1:0"
#   region = ${?AWS_REGION}
#   accessKeyId = ${?AWS_ACCESS_KEY_ID}
#   secretAccessKey = ${?AWS_SECRET_ACCESS_KEY}
#   sessionToken = ${?AWS_SESSION_TOKEN}
# }
```

### Step 10: Add Build Dependencies

**File:** `project/Dependencies.scala`

```scala
val awsAuth        = "software.amazon.awssdk" % "auth"             % Versions.awsSdk
val awsEventStream = "software.amazon.awssdk" % "aws-event-stream" % Versions.awsSdk
```

These are likely already transitive deps of `s3`/`sts`, but making them explicit ensures stability.

### Step 11: Tests

#### Unit Tests

**New Files:**
- `modules/core/src/test/scala/org/llm4s/llmconnect/provider/BedrockClientSpec.scala`
  - Request body serialization (verify SigV4 headers present)
  - Response parsing (mock Converse API JSON)
  - Error mapping (401 -> AuthenticationError, 429 -> RateLimitError)
  - Tool calling request/response translation
  - Closed-state behavior, metrics, exchange logging

- `modules/core/src/test/scala/org/llm4s/llmconnect/provider/aws/AwsSigV4SignerSpec.scala`
  - Verify signature matches known AWS test vectors

- `modules/core/src/test/scala/org/llm4s/llmconnect/provider/aws/EventStreamDecoderSpec.scala`
  - Verify binary frame decoding with sample payloads

- `modules/core/src/test/scala/org/llm4s/config/BedrockConfigSpec.scala`
  - `BedrockConfig.fromValues` with various model names
  - HOCON config loading
  - Validation failures (missing region, empty credentials)

#### Smoke Tests (require AWS credentials)

**New File:** `modules/it/src/test/scala/org/llm4s/llmconnect/smoke/BedrockSmokeSpec.scala`
- Basic complete request
- Stream response
- Tool calling round-trip
- AuthenticationError for invalid credentials

## Config Usage (End Result)

HOCON:
```hocon
bedrock-main {
  provider = "bedrock"
  model = "anthropic.claude-3-sonnet-20240229-v1:0"
  region = ${?AWS_REGION}
  accessKeyId = ${?AWS_ACCESS_KEY_ID}
  secretAccessKey = ${?AWS_SECRET_ACCESS_KEY}
  sessionToken = ${?AWS_SESSION_TOKEN}
}
```

Env-based: `LLM_MODEL=bedrock/anthropic.claude-3-sonnet-20240229-v1:0`

## Reference Files

Key files to study before implementing:
- `modules/core/src/main/scala/org/llm4s/llmconnect/LLMClient.scala` (99 lines) — trait to implement
- `modules/core/src/main/scala/org/llm4s/llmconnect/LLMConnect.scala` (192 lines) — factory/selector
- `modules/core/src/main/scala/org/llm4s/llmconnect/BaseLifecycleLLMClient.scala` — base class pattern
- `modules/core/src/main/scala/org/llm4s/llmconnect/config/ProviderConfig.scala` (679 lines) — config model
- `modules/core/src/main/scala/org/llm4s/llmconnect/provider/AnthropicClient.scala` (649 lines) — best reference (Bedrock invokes Anthropic models)
- `modules/core/src/main/scala/org/llm4s/llmconnect/provider/GeminiClient.scala` (537 lines) — good reference for raw HTTP pattern
- `modules/core/src/main/scala/org/llm4s/agent/Agent.scala` (1249 lines) — how agents consume clients

## Estimated Effort

| Phase | Time |
|-------|------|
| Read & understand provider interface | 2-3h |
| Implement `BedrockClient` (non-streaming) | 2-3h |
| Add streaming support (event-stream decoder) | 1-2h |
| Config wiring + ProviderSelector update | 30min |
| Tests | 1-2h |
| **Total** | **~1-1.5 days** |
