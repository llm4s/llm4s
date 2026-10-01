---
layout: page
title: Writing a Provider
parent: User Guide
nav_order: 13
---

# Writing a Provider
{: .no_toc }

How to publish your own LLM4S provider module - a chat client, an embedding provider, or both -
against `llm4s-core`, without changing LLM4S itself.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Before you start

`llm4s-core` ships no provider. OpenAI, Anthropic, Ollama, Voyage and the rest are separate
modules that register themselves through the same public SPI you will use, so a provider you
publish as `my-llm4s-acme` works exactly like one built into this repository: users add the
dependency, write a config section, and `LLMConnect.getClient` finds it.

Check first whether you need a module at all:

- **The vendor speaks the OpenAI `/chat/completions` API.** The generic `openai-compatible`
  provider in `llm4s-openai-compatible` may already cover it with config alone - see
  [OpenAI-compatible endpoints](providers.md#openai-compatible-endpoints). If the vendor bends the
  format (tool-call ids, system role, reasoning fields), the fix is an
  `OpenAICompatibleDialect` plus a descriptor in that module, contributed upstream - Mistral and
  Cohere are the examples. Do not write another copy of the chat-completions client.
- **Anything else** gets a module of its own, which is what this page covers.

Three rules hold throughout:

1. **Return `Result[A]`, never throw.** `type Result[+A] = Either[LLMError, A]`. Wrap calls that
   can throw with `Try(...).toResult` (`import org.llm4s.types.TryOps`).
2. **Never read the environment.** No `sys.env`, `System.getenv` or `ConfigFactory.load()` in
   provider code. Configuration arrives typed, through the descriptor; environment variables are
   bound in your module's `reference.conf`.
3. **Never edit `llm4s-core` to add a provider.** Everything below is an extension point.

## Worked examples

Read one of these alongside this page:

| Module | Shape | Start here when |
|--------|-------|-----------------|
| [`modules/providers/voyage`](https://github.com/llm4s/llm4s/tree/main/modules/providers/voyage) | Embeddings only, HTTP, no SDK | You only need embeddings - the smallest template |
| [`modules/ollama`](https://github.com/llm4s/llm4s/tree/main/modules/ollama) | Chat + embeddings, HTTP, no SDK, model lister | You need a chat client |
| [`modules/openai-compatible`](https://github.com/llm4s/llm4s/tree/main/modules/openai-compatible) | One client, many dialects | The vendor is OpenAI-compatible |

## Module layout

```
my-llm4s-acme/
├── build.sbt                      # libraryDependencies += "org.llm4s" %% "llm4s-core" % llm4sVersion
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule
    │   │   └── reference.conf
    │   └── scala/com/acme/llm4s/
    │       ├── AcmeConfig.scala       # your ProviderConfig
    │       ├── AcmeProvider.scala     # your ProviderDescriptor
    │       ├── AcmeClient.scala       # your LLMClient
    │       └── Llm4sAcmeModule.scala  # your Llm4sProviderModule
    └── test/scala/com/acme/llm4s/
        └── Llm4sAcmeModuleSpec.scala
```

Use your own package. The in-repo modules keep `org.llm4s.*` for source compatibility with
earlier releases; a third-party module has no reason to, and anything `private[llm4s]` stays
out of reach either way (see [Stability](#stability)).

## Registration

### The module class

`org.llm4s.llmconnect.spi.Llm4sProviderModule` is the unit of discovery: one per artifact, listing
whatever it supplies. Both members default to `Nil`.

```scala
trait Llm4sProviderModule:
  def chatProviders: Seq[ProviderDescriptor] = Nil
  def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Nil
```

Implement it as a **`class` with a public no-arg constructor, not an `object`** -
`java.util.ServiceLoader` instantiates the class it is given, and an `object` has no such
constructor:

```scala
package com.acme.llm4s

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule, ProviderDescriptor }

final class Llm4sAcmeModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor]               = Seq(AcmeProvider)
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(AcmeEmbeddings)
```

### The services file

`src/main/resources/META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, one line:

```
com.acme.llm4s.Llm4sAcmeModule
```

`ProviderRegistry.discover()` (and `ProviderRegistry.default`, its cached result) now finds the
module whenever the jar is on the classpath. Tell users who build fat jars to *merge* services
files (`MergeStrategy.filterDistinctLines` in sbt-assembly, `ServicesResourceTransformer` in
maven-shade); where that is impossible they register explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sAcmeModule)
// or extend another registry: ProviderRegistry.default.withModule(new Llm4sAcmeModule)
```

If two modules supply the same id, the last registered wins and `registry.report.collisions`
records it.

### `reference.conf`: binding the vendor key

Credentials belong to a vendor, keyed by provider id. Your module's `reference.conf` binds the
vendor's conventional variable to `llm4s.credentials.<id>.apiKey`; any chat section or embeddings
block without an `apiKey` of its own falls back to it:

```hocon
# my-llm4s-acme defaults. HOCON merges this with every other reference.conf on the classpath.
llm4s {
  credentials {
    acme {
      apiKey = ${?ACME_API_KEY}
    }
  }

  # Embeddings are keyed by provider id, so their env bindings can live here too.
  embeddings {
    acme {
      baseUrl = ${?ACME_EMBEDDING_BASE_URL}
      model   = ${?ACME_EMBEDDING_MODEL}
    }
  }
}
```

Do not put defaults (base URL, default model) here - those are code, on the config spec. Chat
sections are keyed by the *user's* instance name, which a `reference.conf` cannot know.

## The chat descriptor

`org.llm4s.llmconnect.spi.ProviderDescriptor` is the whole chat extension point:

```scala
trait ProviderDescriptor:
  def id: ProviderId
  def aliases: Set[String] = Set.empty
  def configSpec: ProviderConfigSpec
  def features: ProviderFeatures = ProviderFeatures.default
  def modelLister: Option[ProviderModelLister] = None
  def buildConfig(providerName: String, section: ProvidersConfigModel.NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig]
  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient]
```

`buildConfig` receives a section that has **already been validated** against `configSpec`:
required fields are present, the API key has fallen back to `llm4s.credentials.<id>.apiKey`, and
`section.extras` holds exactly your declared extra keys, defaults applied. `buildClient` must
check the config's type rather than cast - `ProviderDescriptor.expectConfig` does that.

### `ProviderConfigSpec` and `ProviderConfigKey`

The spec says what a section needs. The fields a provider author uses:

| Field | Meaning |
|-------|---------|
| `requiresApiKey` | the section must resolve an `apiKey` (its own, or the shared credential) |
| `requiresBaseUrl` | the section must set `baseUrl`; only when there is no `defaultBaseUrl` (Ollama) |
| `defaultBaseUrl` | used when the section omits `baseUrl` - the single source for your default endpoint |
| `baseUrlExample`, `baseUrlEnv` | shown in the missing-`baseUrl` message |
| `apiKeyEnv` | the variables your `reference.conf` binds, named in the missing-key error |
| `extras` | your provider-specific keys, as `ProviderConfigKey`s |

`ProviderConfigSpec.apiKeyAndDefaultBaseUrl(defaultBaseUrl, apiKeyEnv)` builds the common shape.

Anything beyond the built-in fields (`provider`, `model`, `baseUrl`, `apiKey`, `organization`,
`endpoint`, `apiVersion`, `contextWindow`, `reserveCompletion`, `headers` -
`ProviderConfigSpec.BuiltinKeys`) is declared as an extra rather than smuggled through a built-in
field:

```scala
ProviderConfigKey.required("project", "the project that owns your Acme deployment")
ProviderConfigKey.optional("region", "the Acme region serving the model", default = Some("eu-west"))
```

`ProviderConfigKey` also takes `env` (a variable to suggest in the missing-key message) and
`deprecatedAliases` (old names, accepted with a warning). Undeclared keys in a section are
reported as unknown and not passed on. Values are strings; parse numbers or booleans in
`buildConfig` and return a `ConfigurationError` for a malformed one.

Read the values with `section.extra("region")` (an `Option[String]`) or
`ProviderDescriptor.requireExtra(providerName, section, "region")` (a `Result[String]`).

### The descriptor

```scala
package com.acme.llm4s

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

object AcmeProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("acme")
  override val aliases: Set[String]  = Set("acme-ai")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.acme.example/v1"),
    apiKeyEnv      = Seq("ACME_API_KEY"), // what reference.conf binds
    extras         = Seq(
      ProviderConfigKey.optional("region", "the Acme region serving the model", default = Some("eu-west"))
    )
  )

  // Say so if the client cannot stream or call tools; the default claims both.
  override val features: ProviderFeatures = ProviderFeatures(streaming = true, toolCalling = false)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      region  <- ProviderDescriptor.requireExtra(providerName, section, "region")
      config  <- AcmeConfig.fromValues(apiKey, section.model.asString, baseUrl, region)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor.expectConfig[AcmeConfig](id, config).map(new AcmeClient(_, options))
```

### Your `ProviderConfig`

`org.llm4s.llmconnect.config.ProviderConfig` is an open trait: `providerId`, `model`,
`contextWindow`, `reserveCompletion`, `endpointUrl` and `withModel`. Resolve the window from the
model registry with `ContextWindowResolver` (the `using` parameter `buildConfig` receives), and
keep the key out of `toString`:

```scala
import org.llm4s.error.ConfigurationError

final case class AcmeConfig(
  apiKey: String,
  model: String,
  baseUrl: String,
  region: String,
  contextWindow: Int,
  reserveCompletion: Int
) extends ProviderConfig:
  val providerId: ProviderId                    = ProviderId("acme")
  def endpointUrl: Option[String]               = Some(baseUrl)
  def withModel(model: String): AcmeConfig      = copy(model = model)
  override def toString: String                 = s"AcmeConfig(***, $model, $baseUrl, $region)"

object AcmeConfig:
  def fromValues(apiKey: String, model: String, baseUrl: String, region: String)(using
    resolver: ContextWindowResolver
  ): Result[AcmeConfig] =
    if apiKey.trim.isEmpty then Left(ConfigurationError("Acme apiKey must be non-empty", List("apiKey")))
    else
      val (window, reserve) = resolver.resolve(
        lookupProviders      = Seq("acme"),
        modelName            = model,
        defaultContextWindow = 128000,
        defaultReserve       = 4096,
        fallbackResolver     = _ => (128000, 4096) // when the registry does not know the model
      )
      Right(AcmeConfig(apiKey, model, baseUrl, region, window, reserve))
```

## What the user writes

With `my-llm4s-acme` on the classpath and `ACME_API_KEY` set, a section needs only `provider`
and `model`; extras sit beside the built-in fields:

```hocon
llm4s {
  providers {
    provider = "acme-main"

    acme-main {
      provider = "acme"
      model    = "acme-large"
      region   = "us-east"          # your ProviderConfigKey; omit it to get "eu-west"
    }

    acme-team {                     # a second account sets its own key, which wins
      provider = "acme"
      model    = "acme-small"
      apiKey   = ${?ACME_TEAM_API_KEY}
    }
  }
}
```

```scala
for
  config <- Llm4sConfig.defaultProvider()
  client <- LLMConnect.getClient(config)
yield client
```

## Shared plumbing for clients

These live in `llm4s-core` and are part of the provider-author SPI. Use them rather than
re-implementing their behaviour, so your provider reports metrics, errors and costs the way
every other provider does.

### `BaseLifecycleLLMClient` and `MetricsRecording`

`org.llm4s.llmconnect.BaseLifecycleLLMClient` (extends `LLMClient with MetricsRecording`) gives
an idempotent `close()`, a closed-state check, and metrics around each call. You supply:

```scala
protected def metrics: MetricsCollector       // from MetricsRecording; use options.metrics
protected def clientDescription: String        // "Acme client for model x", for the closed error
protected def providerName: String             // metrics label, e.g. "acme"
protected def modelName: String
protected def releaseResources(): Unit = ()    // called once, from close()
```

Wrap `complete` and `streamComplete` bodies in
`completeWithMetrics(operation: => Result[Completion]): Result[Completion]`, which fails a closed
client, times the call, and records tokens and `Completion.estimatedCost` on success. For
non-`Completion` calls, `MetricsRecording.withMetrics(provider, model, operation, extractUsage,
extractCost)` does the same.

### `ProviderExchangeRecorder` and `ProviderExchangeLogging`

`LlmClientOptions.exchangeLogging` is a `ProviderExchangeLogging` (`Disabled`, or
`Enabled(sink)`) - the user's opt-in to capture raw request/response bodies. Honour it by calling,
once per request:

```scala
ProviderExchangeRecorder.record(
  exchangeLogging = options.exchangeLogging,
  provider        = providerName,
  model           = Some(config.model),
  startedAt       = startedAt,           // java.time.Instant taken before the call
  requestBody     = requestText,
  responseBody    = Some(responseText),
  result          = result               // the Result you are about to return
)
```

It is a no-op when disabled and never fails your call if the sink throws. Do not put credentials
in the request body you record; send them in headers.

### `Llm4sHttpClient`

`org.llm4s.http.Llm4sHttpClient` is the JDK-backed HTTP client (`Llm4sHttpClient.create()`), with
`get`, `post`, `postBytes`, `postMultipart`, `put`, `delete`, `postRaw` and `postStream`
(timeouts in milliseconds). Take one as a constructor parameter so tests can inject a stub.
Non-2xx statuses are returned, not thrown, but I/O failures **do** throw, so wrap each call in
`Try(...).toResult` (only `getResult` does that for you).

### `HttpErrorMapper`

```scala
HttpErrorMapper.mapHttpError(statusCode: Int, body: String, provider: String): Result[Nothing]
```

Maps a non-2xx response to the standard error types - 401/403 `AuthenticationError`, 429
`RateLimitError`, 400 `ValidationError`, anything else `ServiceError` - pulling a message out of
common JSON error shapes, redacted and truncated. Retry and fallback logic keys off these types,
so use it rather than inventing your own.

### `CostEstimator`

```scala
CostEstimator.estimate(model: String, usage: TokenUsage)(using ModelRegistryService): Option[Double]
CostEstimator.estimateFromMetadata(metadata: Option[ModelMetadata], usage: TokenUsage): Option[Double]
```

Prices a call from the model registry, including cached and reasoning tokens. Put the result in
`Completion.estimatedCost`; `completeWithMetrics` records it.

### `RequestTransformer` and `TransformationResult`

`org.llm4s.model.RequestTransformer` applies what the model registry knows about a model -
temperature range, disallowed parameters, no tools, no system messages, no structured output,
no native streaming - before you encode a request:

```scala
RequestTransformer.default(service: ModelRegistryService): RequestTransformer
RequestTransformer.withOverrides(overrides: Map[String, ModelCapabilities], service): RequestTransformer
RequestTransformer.adjusted(service)(adjust: (String, ModelCapabilities) => ModelCapabilities): RequestTransformer
```

Use `adjusted` for vendor rules the registry does not record: you receive the model id and the
registry's capabilities and return the ones to apply. `llm4s-openai`'s `OpenAIModelRules` is the
example - the o-series take temperature 1 only and no system message, whatever the registry says:

```scala
RequestTransformer.adjusted(service) { (modelId, caps) =>
  if modelId.startsWith("acme-reasoner") then caps.copy(supportsSystemMessages = Some(false))
  else caps
}
```

`TransformationResult.transform(modelId, options, messages, dropUnsupported, transformer)` runs
the option and message transforms in one call and returns the transformed `options`, `messages`
and `requiresFakeStreaming`.

### `EmbeddingProvider`

```scala
trait EmbeddingProvider:
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse]
  def embedMultimodal(request: MultimediaEmbeddingRequest): Result[EmbeddingResponse] // defaults to a 501 error
```

What an `EmbeddingProviderDescriptor.build` returns. Report failures as `EmbeddingError(code,
message, provider)`.

### Streaming: `SSEParser`, `StreamingAccumulator`, `StreamingToolArgumentParser`

- `SSEParser.createStreamingParser()` returns an incremental parser: `addChunk(text)`, then drain
  `nextEvent()` / `hasEvents`, and `flush()` at end of stream. Each `SSEEvent` has `data`,
  `event`, `id` and `retry`. `SSEParser.parseEvent` / `parseStream` handle whole strings.
- `StreamingAccumulator.create()` folds `StreamedChunk`s into a final `Completion`:
  `addChunk(chunk)`, `updateTokens(prompt, completion)` (or `updateTokensWithThinking`) when usage
  arrives, then `toCompletion: Result[Completion]`. It keys tool calls by id, so **every
  tool-call chunk must carry its call's id** - if the wire format identifies continuations only
  by index, map index to id yourself; a chunk with an empty id is skipped. It is mutable: one per
  request.
- `StreamingToolArgumentParser.parse(raw: String): ujson.Value` turns an argument fragment into
  the `ToolCall.arguments` value the accumulator expects (`{}` for empty, the parsed JSON when
  complete, the raw string otherwise).

### `ProviderModelLister`

```scala
trait ProviderModelLister:
  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]]
```

Return one from `ProviderDescriptor.modelLister` if the vendor has a model-listing endpoint. If
it serves the OpenAI `/models` shape, `ProviderModelListers.openAICompatible(provider,
defaultBaseUrl, ...)` builds one for you.

### Redaction

llm4s's own credential redaction (`org.llm4s.util.Redaction`) is internal and not part of the
SPI. `HttpErrorMapper` already runs provider error bodies through it, so error text you build
with it is redacted; beyond that, keep credentials out of anything you log.

## A minimal client

```scala
final class AcmeClient(
  config: AcmeConfig,
  clientOptions: LlmClientOptions,
  http: Llm4sHttpClient = Llm4sHttpClient.create()
)(using registry: ModelRegistryService)
    extends BaseLifecycleLLMClient:

  protected val metrics: MetricsCollector  = clientOptions.metrics
  protected def clientDescription: String = s"Acme client for model ${config.model}"
  protected def providerName: String      = "acme"
  protected def modelName: String         = config.model

  private val transformer = RequestTransformer.default(registry)
  private val headers     = Map("Authorization" -> s"Bearer ${config.apiKey}", "Content-Type" -> "application/json")

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics {
      TransformationResult
        .transform(config.model, options, conversation.messages, dropUnsupported = true, transformer = transformer)
        .flatMap(t => send(AcmeWire.encode(config.model, t.messages, t.options)))
    }

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions,
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    completeWithMetrics(...) // postStream + SSEParser + StreamingAccumulator, as Ollama's client does

  def getContextWindow(): Int     = config.contextWindow
  def getReserveCompletion(): Int = config.reserveCompletion

  private def send(requestText: String): Result[Completion] =
    val startedAt = Instant.now()
    val response  = Try(http.post(s"${config.baseUrl}/chat", headers, requestText, timeout = 120000)).toResult
    val result = response.flatMap { r =>
      if r.statusCode / 100 == 2 then AcmeWire.decode(r.body).map(withCost)
      else HttpErrorMapper.mapHttpError(r.statusCode, r.body, providerName)
    }
    ProviderExchangeRecorder.record(
      clientOptions.exchangeLogging, providerName, Some(config.model), startedAt,
      requestText, response.toOption.map(_.body), result
    )
    result

  private def withCost(c: Completion): Completion =
    c.copy(estimatedCost = c.usage.flatMap(CostEstimator.estimate(config.model, _)))
```

`AcmeWire` stands for your own request encoding and response decoding.

## The embedding descriptor

`org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor` is the embedding half. Its config lives at
`llm4s.embeddings.<id>` and is selected with `EMBEDDING_MODEL=<id>/<model>`. Usually you declare
an `EmbeddingConfigSpec` and implement `build`; the default `buildConfig` resolves model, base URL
and key against the spec.

```scala
object AcmeEmbeddings extends EmbeddingProviderDescriptor:
  val id: ProviderId = ProviderId("acme")    // chat and embedding ids are separate namespaces

  override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.acme.example/v1"),
    defaultModel   = Some("acme-embed-1"),
    apiKeyEnv      = Seq("ACME_API_KEY"),
    modelEnv       = Some("ACME_EMBEDDING_MODEL")
  )

  // What ModelDimensionRegistry answers from; override dimensionsOf for model-name variants.
  override val modelDimensions: Map[String, Int] = Map("acme-embed-1" -> 1024)

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Right(AcmeEmbeddingProvider(config, Llm4sHttpClient.create()))
```

`EmbeddingConfigSpec.defaultApiKey` is for a provider that takes a key but needs no real one
(local Ollama). Override `buildConfig(section, modelOverride)` only when the config cannot be
expressed with the spec.

## Testing: `Llm4s<Name>ModuleSpec`

Every provider module in this repository proves its registration in one spec, and yours should
too - it replaces the exhaustivity check the compiler gave when providers were a closed `enum`.
It shows that the module is discovered, that it is the only module supplying its ids, that it can
be registered explicitly, and that each descriptor gets from config to client:

```scala
class Llm4sAcmeModuleSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.default().toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  private val section = NamedProviderConfig(
    provider = AcmeProvider.id, model = ModelName("acme-large"), baseUrl = None,
    apiKey = Some(ApiKey("test-key")), organization = None, endpoint = None, apiVersion = None,
    extras = Map("region" -> "eu-west") // built in code, so no validation fills the default
  )

  "the my-llm4s-acme services entry" should {
    "be discovered" in {
      val registry = ProviderRegistry.discover()
      registry.get(ProviderId("acme")) shouldBe Right(AcmeProvider)
      registry.findEmbedding(ProviderId("acme")) shouldBe Some(AcmeEmbeddings)
      registry.canonicalId("acme-ai") shouldBe ProviderId("acme")
    }

    "be the only module that supplies acme" in {
      ProviderRegistry.default.report.modules
        .filter(_.providerIds.contains("acme"))
        .map(_.moduleClass) shouldBe Seq(classOf[Llm4sAcmeModule].getName)
    }

    "be registrable explicitly" in {
      ProviderRegistry.ofModules(new Llm4sAcmeModule).get(ProviderId("acme")) shouldBe Right(AcmeProvider)
    }
  }

  "AcmeProvider" should {
    "build an AcmeConfig and an AcmeClient from a section" in {
      AcmeProvider
        .buildConfig("test", section)
        .flatMap(AcmeProvider.buildClient(_, LlmClientOptions.default))
        .map(_.getClass) shouldBe Right(classOf[AcmeClient])
    }

    "load from a named section, extras defaulted" in {
      // src/test/resources/application.conf:
      //   llm4s.providers.acme-test { provider = "acme", model = "acme-large", apiKey = "k" }
      Llm4sConfig.provider("acme-test").map(_.asInstanceOf[AcmeConfig].region) shouldBe Right("eu-west")
    }
  }
```

Also test the client itself against a local stub server (the in-repo modules use the JDK's
`com.sun.net.httpserver.HttpServer`), including that `streamComplete` really streams if
`features.streaming` is true, and an error status maps to the right `LLMError`.

## Stability

`llm4s-core` reaches a binary-compatibility baseline (MiMa) at 0.5.0 and freezes at 1.0. The
provider-author SPI described on this page is **public and frozen at 1.0**: binary-compatible
across all 1.x releases, so a provider compiled against 1.0 keeps working. That covers:

- the registration SPI in `org.llm4s.llmconnect.spi` - `Llm4sProviderModule`,
  `ProviderDescriptor`, `ProviderConfigSpec`, `ProviderConfigKey`, `ProviderFeatures`,
  `EmbeddingProviderDescriptor`, `EmbeddingConfigSpec`, `EmbeddingProviderSection`,
  `ProviderRegistry`;
- `ProviderConfig`, `NamedProviderConfig`, `ContextWindowResolver`, `LlmClientOptions`;
- `BaseLifecycleLLMClient`, `MetricsRecording`, `ProviderExchangeRecorder`,
  `ProviderExchangeLogging`, `HttpErrorMapper`, `CostEstimator`, `EmbeddingProvider`,
  `StreamingAccumulator`, `SSEParser`, `StreamingToolArgumentParser`, `Llm4sHttpClient`,
  `ProviderModelLister`, `RequestTransformer` and `TransformationResult`.

Anything `private[llm4s]` - `ProviderResultOps`, for example - is internal, may change in any
release, and cannot be reached from your package anyway. Do not work around that by declaring
your code in `org.llm4s`.

The OpenAI-format helpers - `ResponseFormatMapper`, `ToolCallDeserializer` and
`StandardToolCallDeserializer`, `OpenAICompatibleClient` and its dialects - ship in
`llm4s-openai-compatible`, not in core. Depending on that module for them puts it on your users'
classpath; for an OpenAI-compatible vendor, contribute a dialect there instead.

## Checklist

- [ ] `Llm4sProviderModule` as a `class` with a no-arg constructor, named in
      `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`
- [ ] `reference.conf` binds the vendor variable to `llm4s.credentials.<id>.apiKey`, and
      `apiKeyEnv` names the same variable
- [ ] defaults (base URL, model) on the spec, not in `reference.conf`
- [ ] provider-specific keys declared as `ProviderConfigKey` extras
- [ ] `ProviderFeatures` honest about streaming and tool calling
- [ ] client built on `BaseLifecycleLLMClient`, errors via `HttpErrorMapper`, cost via
      `CostEstimator`, exchanges via `ProviderExchangeRecorder`
- [ ] no exceptions escape, no environment reads
- [ ] `Llm4s<Name>ModuleSpec` covering discovery, sole ownership, explicit registration and the
      config-to-client round trip
