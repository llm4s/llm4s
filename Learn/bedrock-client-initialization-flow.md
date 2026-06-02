# Bedrock Anthropic Client Initialization Flow

Step-by-step walkthrough of how the `BedrockAnthropicClient` is retrieved from:

```scala
providerCfg     <- Llm4sConfig.provider("bedrock-anthropic-main")
registryService <- Llm4sConfig.modelRegistryService()
given org.llm4s.model.ModelRegistryService = registryService
client          <- LLMConnect.getClient(providerCfg)
```

---

## Step 1: `Llm4sConfig.provider("bedrock-anthropic-main")`

**File:** `config/Llm4sConfig.scala:81`

```scala
def provider(name: String): Result[ProviderConfig] =
  for
    service <- modelRegistryService()
    given ContextWindowResolver = ContextWindowResolver(service)
    config <- NamedProviderLoader.load(ConfigSource.default, name)
  yield config
```

This does three things:
1. Loads a `ModelRegistryService` (reads model metadata — context window sizes, pricing, etc.)
2. Creates a `ContextWindowResolver` from it (used later to look up the model's token limits)
3. Calls `NamedProviderLoader.load` to parse the HOCON config section named `"bedrock-anthropic-main"`

---

## Step 2: `NamedProviderLoader.load`

**File:** `config/NamedProviderLoader.scala:11`

```scala
def load(source: ConfigSource, providerName: String): Result[ProviderConfig] =
  for
    providers  <- ProvidersConfigLoader.load(source)     // parse HOCON into typed model
    normalized <- providers.namedProviders
      .get(ProviderName("bedrock-anthropic-main"))       // find the named section
      .toRight(ConfigurationError(...))
    config     <- buildConfigFromNamedConfig("bedrock-anthropic-main", normalized)
  yield config
```

1. **`ProvidersConfigLoader.load`** reads your `application.conf` and returns a typed `ProvidersConfig` with all named providers as a `Map[ProviderName, NamedProviderConfig]`
2. Looks up `"bedrock-anthropic-main"` in the map — gets back a `NamedProviderConfig` with `provider = BedrockAnthropic`, `model = "us.anthropic.claude-sonnet-4-20250514-v1:0"`, `baseUrl = "us-west-2"`, etc.
3. Dispatches to `buildConfigFromNamedConfig`

---

## Step 3: `buildConfigFromNamedConfig` — pattern match on provider kind

**File:** `config/NamedProviderLoader.scala:107`

```scala
case ProviderKind.BedrockAnthropic =>
  val region  = section.baseUrl.map(_.asUrl).getOrElse(DefaultConfig.DEFAULT_BEDROCK_ANTHROPIC_REGION)
  val profile = section.organization
  Right(BedrockAnthropicConfig.fromValues(section.model.asString, region, profile))
```

Since `provider = "bedrock-anthropic"` maps to `ProviderKind.BedrockAnthropic`, this branch:
- Extracts `region` from `baseUrl` (-> `"us-west-2"`)
- Extracts optional `profile` from `organization` (-> `None` since it's commented out)
- Calls `BedrockAnthropicConfig.fromValues` which uses the `ContextWindowResolver` to determine the model's context window (200k for Claude) and reserve (4096)

**Returns:** `BedrockAnthropicConfig(region="us-west-2", model="us.anthropic.claude-sonnet-4-20250514-v1:0", contextWindow=200000, reserveCompletion=4096, profile=None)`

---

## Step 4: `Llm4sConfig.modelRegistryService()`

Loads model metadata (pricing, token limits) from a bundled JSON resource. This returns a `ModelRegistryService` that providers use for cost estimation and context window lookups.

---

## Step 5: `given org.llm4s.model.ModelRegistryService = registryService`

This line makes the `ModelRegistryService` available as a Scala 3 **given** (implicit). It's needed because `LLMConnect.getClient` has a `(using ModelRegistryService)` parameter — the compiler will pass it automatically.

---

## Step 6: `LLMConnect.getClient(providerCfg)`

**File:** `llmconnect/LLMConnect.scala:114`

```scala
def getClient(config: ProviderConfig)(using ModelRegistryService): Result[LLMClient] =
  fromConfig(config)
```

Which calls `buildClient`, which pattern-matches on the config type:

```scala
case cfg: BedrockAnthropicConfig =>
  BedrockAnthropicClient(cfg, metrics, exchangeLogging)
```

---

## Step 7: `BedrockAnthropicClient` construction

**File:** `llmconnect/provider/BedrockAnthropicClient.scala:53-67`

```scala
private val credentialsProvider: AwsCredentialsProvider = config.profile match {
  case Some(profile) => ProfileCredentialsProvider.builder().profileName(profile).build()
  case None          => DefaultCredentialsProvider.create()
}

private val client = AnthropicOkHttpClient.builder()
  .backend(
    BedrockBackend.builder()
      .region(Region.of(config.region))           // us-west-2
      .awsCredentialsProvider(credentialsProvider) // picks up AWS_ACCESS_KEY_ID etc.
      .build()
  )
  .build()
```

Since `profile = None`, it creates a `DefaultCredentialsProvider` which resolves credentials from:
1. `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_SESSION_TOKEN` env vars
2. `~/.aws/credentials` default profile
3. IAM instance role

The final result is an `LLMClient` (specifically `BedrockAnthropicClient`) that speaks to `bedrock-runtime.us-west-2.amazonaws.com` using your STS credentials, wrapping calls in the Anthropic Messages API format.

---

## Visual Summary

```
"bedrock-anthropic-main"
        |
        v
+---------------------+
|  Llm4sConfig        |  Loads ModelRegistryService + ContextWindowResolver
|  .provider(name)    |
+----------+----------+
           |
           v
+---------------------+
| NamedProviderLoader |  Reads HOCON -> finds section -> pattern matches on ProviderKind
| .load(source, name) |
+----------+----------+
           |
           v
+-------------------------------------------------------------+
| BedrockAnthropicConfig(region, model, contextWindow, ...)   |
+----------+--------------------------------------------------+
           |
           v
+---------------------+
| LLMConnect          |  Pattern matches config type -> constructs client
| .getClient(config)  |
+----------+----------+
           |
           v
+--------------------------------------------------------------+
| BedrockAnthropicClient                                       |
|   +-- AnthropicOkHttpClient + BedrockBackend(region, creds)  |
|   +-- implements LLMClient.complete() / streamComplete()     |
+--------------------------------------------------------------+
```

---

## Key Files

| Step | File | Lines |
|------|------|-------|
| Config entry point | `modules/core/src/main/scala/org/llm4s/config/Llm4sConfig.scala` | 81-86 |
| Named provider loader | `modules/core/src/main/scala/org/llm4s/config/NamedProviderLoader.scala` | 11-110 |
| Bedrock config model | `modules/core/src/main/scala/org/llm4s/llmconnect/config/ProviderConfig.scala` | 695-735 |
| Client factory | `modules/core/src/main/scala/org/llm4s/llmconnect/LLMConnect.scala` | 61-62, 114 |
| Client implementation | `modules/core/src/main/scala/org/llm4s/llmconnect/provider/BedrockAnthropicClient.scala` | 53-67 |
| Sample config (HOCON) | `modules/samples/src/main/resources/application.conf` | bedrock-anthropic-main section |
