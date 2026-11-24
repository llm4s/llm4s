---
layout: page
title: Writing a Provider
description: "Publish your own LLM4S provider module, a chat client or embedding provider, against llm4s-core without changing LLM4S itself."
search_title: "Write a custom LLM provider module for LLM4S"
parent: Core
nav_order: 115
grand_parent: User Guide
cites_closed_issues: [1493, 1818]
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
  [OpenAI-compatible endpoints](providers#openai-compatible-endpoints). If the vendor bends the
  format (tool-call ids, system role, reasoning fields), the fix is an
  `OpenAICompatibleDialect` plus a descriptor in that module, contributed upstream - Mistral and
  Cohere are the examples. Do not write another copy of the chat-completions client.
- **Anything else** gets a module of its own, which is what this page covers.

Three rules hold throughout:

1. **Return `Result[A]`, never throw.** `type Result[+A] = Either[LLMError, A]`. Wrap calls that
   can throw with `Try(...).toResult` (`import org.llm4s.syntax.toResult`).
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
├── build.sbt                      # "org.llm4s" %% "llm4s-core", and "llm4s-provider-testkit" % Test
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

Use your own package. The in-repo modules, the template `modules/providers/voyage` included, are
declared in `org.llm4s.*` because they are llm4s: their class names are part of earlier releases.
That is not something to copy. Being inside `org.llm4s` lets them reach `private[llm4s]` helpers
your module cannot, and the template uses one: `org.llm4s.util.Redaction.safeBody`, for the body of
an error message. Everything else it calls is the public SPI this guide describes; build your error
from the response with `HttpErrorMapper.toError`, which redacts and truncates the body itself (see
[Stability](#stability)).

## Registration

### The module class

`org.llm4s.llmconnect.spi.Llm4sProviderModule` is the unit of discovery: one per artifact, listing
whatever it supplies. Both members default to `Nil`.

```scala
trait Llm4sProviderModule:
  def providers: Seq[ProviderDescriptor] = Nil
  def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Nil
```

One naming rule holds across the SPI: **an unprefixed name is the chat half, and `embedding*` the embedding
half**. So `ProviderDescriptor` and `EmbeddingProviderDescriptor`, `providers` and `embeddingProviders` here, and
on `ProviderRegistry` `descriptors` / `embeddingDescriptors`, `find` / `findEmbedding`, `resolve` /
`resolveEmbedding`, `ids` / `embeddingIds`, `of` / `ofEmbeddings` and `withProvider` / `withEmbeddingProvider`.

Implement it as a **`class` with a public no-arg constructor, not an `object`** -
`java.util.ServiceLoader` instantiates the class it is given, and an `object` has no such
constructor:

```scala
package com.acme.llm4s

import org.llm4s.llmconnect.spi.{ EmbeddingProviderDescriptor, Llm4sProviderModule, ProviderDescriptor }

final class Llm4sAcmeModule extends Llm4sProviderModule:
  override def providers: Seq[ProviderDescriptor]                   = Seq(AcmeProvider)
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
  def buildConfig(section: org.llm4s.config.NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig]
  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient]
```

The embedding descriptor has the same two methods with the same shape: `buildConfig(section, context)` and
`build(config, context)`. Each takes one context value in place of a list of parameters, so what a later
provider needs is a new field of the context and not a new signature for every descriptor:

| Context | Carries |
|---|---|
| `ProviderConfigContext` | `providerName` (the user's section name, for error messages), `contextWindows` (the `ContextWindowResolver` a config's `fromValues` takes) |
| `ProviderBuildContext` | `options` (the caller's `LlmClientOptions`; `metrics`, `exchangeLogging` and `httpClient` read it), `modelRegistry` |
| `EmbeddingConfigContext` | `modelOverride` (the `<model>` half of `EMBEDDING_MODEL=<id>/<model>`) |
| `EmbeddingBuildContext` | `options` (the caller's `EmbeddingClientOptions`; `httpClient` reads it) |

`buildConfig` receives a section that has **already been validated** against `configSpec`:
required fields are present, the API key has fallen back to `llm4s.credentials.<id>.apiKey`, and
`section.extras` holds exactly your declared extra keys, defaults applied. `build` must
check the config's type rather than cast - `ProviderDescriptor.expectConfig` does that.

To call a descriptor yourself, in a test say, make the context from what is in scope:
`descriptor.buildConfig(section, ProviderConfigContext.of("my-section"))` takes the `ContextWindowResolver` in
scope (with a `ModelRegistryService` in scope, the one over it), and
`descriptor.build(config, ProviderBuildContext.of(options))` the `ModelRegistryService`.

`aliases` are other names a section's `provider` may use: `ProviderRegistry.find` and `resolve` accept an
alias as they accept the id. An id always wins, so an alias equal to another provider's id is ignored, and of
two providers declaring one alias the later registered keeps it; both cases appear in
`ProviderRegistry.report.collisions` (kind `ProviderCollisionKind.ChatAlias` or `EmbeddingAlias`) and in the
discovery log. The report is the registry's to build and yours to read: its ids are `ProviderId`s.

### Workload identity

A provider that can authenticate with a workload identity token (a SPIFFE JWT-SVID, say) declares
the keys it reads from a section's `auth` block in `ProviderConfigSpec.authExtras`. Core parses
`identityTokenFile` / `identityToken` itself and hands the rest to `buildConfig` as
`section.auth`; read a key with `ProviderDescriptor.requireAuthExtra`. A section sets `apiKey` or
`auth`, never both, and a section with `auth` never consults the shared credential.
`TokenExchange.provider` gives an RFC 8693 exchange with caching and single-flight refresh, and
`FakeTokenExchangeServer` and `TestJwt` in the testkit test it without a network. An SDK that does
its own federation takes the identity token's file path instead (see `AnthropicProvider`).

The configuration side is `@Stable` ([#1818](https://github.com/llm4s/llm4s/issues/1818)): `AuthConfig`,
`IdentitySource`, `TokenExchangeConfig`, `AccessToken` and the `AccessTokenProvider` trait, which a Stable
`NamedProviderConfig`, `ProviderDescriptor.requireAuthExtra` and `OpenAICompatibleClient.Credential` carry. The
machinery behind them - `TokenExchange`, `CachingAccessTokenProvider` and `IdentityTokenProvider` - is still
`@Experimental`, so keep it out of your own Stable signatures. An `AccessTokenProvider` should return a failure as a
`Left`; `OpenAICompatibleClient` and `CachingAccessTokenProvider` turn one that throws into an `AuthenticationError`.

The three suppliers of a credential are siblings with one method, `current()`: `ApiKeyProvider.current()` gives an
`ApiKey`, and `AccessTokenProvider.current()` and `IdentityTokenProvider.current()` a `Secret`, so a token prints
as `***` and is read with `.reveal` only where it is sent. `AccessTokenProvider.invalidate(rejected: Secret)` has a
no-op default, so a provider that caches nothing is a lambda: `() => readToken().map(Secret.token)`. An
`IdentitySource` is the configured form (`File(path)` or `Literal(token: Secret)`), and
`IdentityTokenProvider.from(source)` the provider that reads it.

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
| `authExtras` | the keys you read from a section's `auth` block, as `ProviderConfigKey`s; empty (the default) means the provider rejects `auth` |

`ProviderConfigSpec.apiKeyAndDefaultBaseUrl(defaultBaseUrl, apiKeyEnv)` builds the common shape.

Anything beyond the built-in fields (`provider`, `model`, `baseUrl`, `apiKey`, `headers`, `timeouts` -
`ProviderConfigSpec.BuiltinKeys`) is declared as an extra rather than smuggled through a built-in
field:

```scala
ProviderConfigKey.required("project", "the project that owns your Acme deployment")
ProviderConfigKey.optional("region", "the Acme region serving the model", default = Some("eu-west"))
```

`ProviderConfigKey` also takes `env` (a variable to suggest in the missing-key message), as a named
argument or afterwards with `withEnv`. Undeclared keys in a section are
reported as unknown and not passed on. Values are strings; parse numbers or booleans in
`buildConfig` and return a `ConfigurationError` for a malformed one.

Read the values with `section.extra("region")` (an `Option[String]`) or
`ProviderDescriptor.requireExtra(context.providerName, section, "region")` (a `Result[String]`).

### The descriptor

```scala
package com.acme.llm4s

import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.*
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ProviderId }
import org.llm4s.types.Result

object AcmeProvider extends ProviderDescriptor:
  val id: ProviderId                 = ProviderId("acme")
  override val aliases: Set[String]  = Set("acme-ai")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some(BaseUrl("https://api.acme.example/v1")),
    apiKeyEnv      = Seq("ACME_API_KEY"), // what reference.conf binds
    extras         = Seq(
      ProviderConfigKey.optional("region", "the Acme region serving the model", default = Some("eu-west"))
    )
  )

  // Say so if the client cannot stream or call tools; the default claims both.
  override val features: ProviderFeatures = ProviderFeatures(streaming = true, toolCalling = false)

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows // AcmeConfig.fromValues takes it
    for
      apiKey  <- ProviderDescriptor.requireApiKey(context.providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(context.providerName, section, configSpec)
      region  <- ProviderDescriptor.requireExtra(context.providerName, section, "region")
      config  <- AcmeConfig.fromValues(apiKey, section.model, baseUrl, region)
    yield config

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry // AcmeClient's constructor takes it
    ProviderDescriptor.expectConfig[AcmeConfig](id, config).map(new AcmeClient(_, context.options))
```

### Your `ProviderConfig`

`org.llm4s.llmconnect.config.ProviderConfig` is an open trait, and every member is abstract: `providerId`,
`model`, `contextWindow`, `reserveCompletion`, `endpointUrl` and `withModel`, then the three settings the
loader applies to whatever your descriptor builds - `timeouts`/`withTimeouts`, `network`/`withNetwork` and
`apiKeyProvider`/`withApiKeyProvider`. None has a default, so a config cannot ignore a configured proxy by
omission. Resolve the window from the model registry with `ContextWindowResolver` (`context.contextWindows`
in `buildConfig`), and keep the key out of `toString`:

```scala
import org.llm4s.error.ConfigurationError
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.{ ContextLimits, ProviderTimeouts }
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }

final case class AcmeConfig(
  apiKey: String,
  model: ModelName,
  baseUrl: String,
  region: String,
  contextWindow: Int,
  reserveCompletion: Int,
  timeouts: ProviderTimeouts = ProviderTimeouts.default,
  network: HttpNetworkSettings = HttpNetworkSettings.default,
  apiKeyProvider: Option[ApiKeyProvider] = None
) extends ProviderConfig:
  val providerId: ProviderId                                     = ProviderId("acme")
  def endpointUrl: Option[BaseUrl]                               = Some(BaseUrl(baseUrl))
  def withModel(model: ModelName): AcmeConfig                    = copy(model = model)
  def withTimeouts(timeouts: ProviderTimeouts): AcmeConfig       = copy(timeouts = timeouts)
  def withNetwork(network: HttpNetworkSettings): AcmeConfig      = copy(network = network)
  def withApiKeyProvider(provider: ApiKeyProvider): Result[AcmeConfig] =
    Right(copy(apiKeyProvider = Some(provider)))
  override def toString: String = s"AcmeConfig(***, ${model.asString}, $baseUrl, $region)"

object AcmeConfig:
  def fromValues(apiKey: String, model: String, baseUrl: String, region: String)(using
    resolver: ContextWindowResolver
  ): Result[AcmeConfig] =
    if apiKey.trim.isEmpty then Left(ConfigurationError("Acme apiKey must be non-empty", List("apiKey")))
    else
      val limits = resolver.resolve(
        lookupProviders      = Seq("acme"),
        modelName            = model,
        defaultContextWindow = 128000,
        defaultReserve       = 4096,
        fallbackResolver     = _ => ContextLimits(128000, 4096) // when the registry does not know the model
      )
      Right(AcmeConfig(apiKey, ModelName(model), baseUrl, region, limits.contextWindow, limits.reserveCompletion))
```

## What the user writes

With `my-llm4s-acme` on the classpath and `ACME_API_KEY` set, a section needs only `provider`
and `model`; extras sit beside the built-in fields:

```hocon
llm4s {
  defaultProvider = "acme-main"
  providers {
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
protected def modelName: ModelName
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
  options.exchangeLogging,
  ProviderExchangeCall(
    provider     = ProviderId(providerName),
    startedAt    = startedAt,            // java.time.Instant taken before the call
    requestBody  = requestText,
    model        = Some(config.model),
    responseBody = Some(responseText)
  ),
  result                                 // the Result you are about to return
)
```

It is a no-op when disabled and never fails your call if the sink throws. Do not put credentials
in the request body you record; send them in headers.

### `Llm4sHttpClient`

`org.llm4s.http.Llm4sHttpClient` is the JDK-backed HTTP client (`Llm4sHttpClient.create()`), with
`get`, `post`, `postBytes`, `postMultipart`, `put`, `delete`, `postRaw` and `postStream`. Those are final
conveniences over the one method an implementation writes, `send(request: HttpRequest): Result[StreamingHttpResponse]`;
a request they do not cover (a PATCH, a HEAD, a GET of bytes) is an `HttpRequest` passed to `send`, `sendText` or
`sendRaw`. Take one as a constructor parameter so tests can inject a stub: a test double implements `send` alone,
answering with `Right(HttpResponse(200, body).toStreaming)`. When `LlmClientOptions.httpClient` is set, send your requests
through that client and do not close it - the caller owns it; otherwise build your own and close it in
`releaseResources`. A provider that does so opts in by overriding `features` with
`ProviderFeatures.default.withCustomHttpClient(true)` and runs `assertUsesSuppliedHttpClient` in its module spec;
`customHttpClient` defaults to `false`, which a client built on a vendor SDK keeps.

```scala
def post(
  url: String,
  headers: Map[String, String] = Map.empty,
  body: String = "",
  timeout: FiniteDuration = 10.seconds
): Result[HttpResponse]
```

Every method returns a `Result` and never throws for a transport failure, so there is no
`try`/`catch` to write:

| Failure | `Left` |
|---|---|
| request or connection timed out | `TimeoutError` (carries the timeout) |
| connection refused, unknown host, other I/O error | `NetworkError` |
| invalid URL, header or timeout; unreadable multipart file | `ValidationError` |
| thread interrupted (the interrupt flag is kept) | `CancelledError` |

A non-2xx status is **not** an error at this layer: it is a `Right` response for you to inspect
(`HttpResponse.ensureSuccess`, or `HttpErrorMapper` below). Timeouts are
`scala.concurrent.duration.FiniteDuration`; the default is 10 seconds, and 10 minutes for
`postStream`. A `timeout` bounds two waits, and is not a deadline for the whole call: the wait for the
response's status line and headers, and each wait for more of the body, so a stream that keeps delivering may
run for any length of time while one that stalls fails its read with a `SocketTimeoutException`. An
`HttpRequest` sets the two apart (`withResponseTimeout`, `withIdleTimeout`). `HttpResponse`, `HttpRawResponse` and `StreamingHttpResponse` all carry `headers`
(lower-case keys); `response.header("Retry-After")` looks one up case-insensitively. A
`StreamingHttpResponse`'s body is yours to close, on an error status too.

### Honouring the `timeouts` block

Every provider section, and every embedding section, may carry `timeouts { request = ..., stream = ... }`.
You do not read the block: the loader parses it into `NamedProviderConfig.timeouts` (checked to be positive
and finite) and applies it to the config your descriptor builds, by calling `ProviderConfig.withTimeouts`;
it applies the proxy and TLS settings of `llm4s.http` and the section's own `http` block the same way, with
`withNetwork`. `AcmeConfig` above carries both, so all that is left is to pass them on when you call the
HTTP client:

```scala
// in the client: your own default stays when the section sets none
httpClient.post(url, headers, body, timeout = config.timeouts.requestOr(2.minutes))
httpClient.postStream(url, headers, body, timeout = config.timeouts.streamOr(10.minutes))
```

Both setters are abstract, so a config says what it does with them. A provider with no request to time out
and no connection to route - a model run in-process, as `llm4s-jlama` does - returns `this` from each and
keeps `timeouts` and `network` at their defaults; the loader then refuses a section that sets `timeouts` or
an `http` block of its own (`llm4s.providers.<name>.timeouts: provider '<id>' takes no timeouts`) rather than
dropping it. The process-wide `llm4s.http` block is not an error for such a provider. An embedding provider
reads `EmbeddingProviderConfig.timeouts.requestOr(...)`, and only `request` has a meaning there.

Methods added to the trait after 1.0 will have default implementations, so a test double that
implements it keeps compiling.

### Honouring `apiKeyFile` (key rotation)

A section may name its key with `apiKeyFile` instead of `apiKey` (#2313). Validation reads the file once into
`NamedProviderConfig.apiKey`, so your descriptor builds its config as usual; the loader then calls
`ProviderConfig.withApiKeyProvider(ApiKeyProvider.file(path))` on it. To support rotation, carry the provider,
as `AcmeConfig` above does, and ask it for the key on every request, not once when the client is built:

```scala
// in the client, per request
val key: Result[String] = config.apiKeyProvider.fold[Result[String]](Right(config.apiKey))(_.current().map(_.asKey))
```

`withApiKeyProvider` returns a `Result`, because not every config can take one. A config whose client reads the
key once returns `ProviderConfig.noApiKeyProvider(providerId, "reads its key once")` and `None` from
`apiKeyProvider`: a section's `apiKeyFile` then gives its client the key the file held when the section was
loaded, and a rotated key only after a reload (the loader logs that). A provider that takes no API key returns
`ProviderConfig.noApiKeyProvider(providerId)`, and its section refuses `apiKeyFile`. Either way a caller who
sets a provider in code gets a `ConfigurationError` instead of a config that silently ignored it. Prove support
with `assertHonoursApiKeyProvider`.

An embedding provider gets the same from its block's `apiKeyFile` (or the vendor's shared one) through
`EmbeddingProviderConfig.apiKeyProvider`: read the key per request with `cfg.currentApiKey()`, which falls back
to `cfg.apiKey`, rather than `cfg.apiKey` itself; it is `None` for a provider that takes no key. Prove it with `assertEmbeddingHonoursApiKeyProvider`.

### `HttpErrorMapper`

```scala
HttpErrorMapper.toError(
  statusCode: Int,
  body: String,
  provider: String,
  headers: Map[String, Seq[String]]
): LLMError
```

Maps a non-2xx response to the standard error types - 401/403 `AuthenticationError`, 429
`RateLimitError`, 400 `ValidationError`, anything else `ServiceError` - pulling a message out of
common JSON error shapes, redacted and truncated. Retry and fallback logic keys off these types,
so use it rather than inventing your own. Pass the response's `headers`: a `Retry-After`
(delta-seconds or an HTTP-date) becomes the error's `retryAfter`, a `FiniteDuration` - on a 429's
`RateLimitError`, or a 503's (any other status's) `ServiceError` - so retries wait as long as the
provider asked rather than a guessed backoff. It returns the error itself: your client wraps it in a `Left`.

`HttpErrorMapper.mapStreamError(status, message, provider)` is the same mapping for a failure a provider
reports inside a stream, after the 200 status: give it the status the event states, or 502 when it states
none, and the provider's message, which every error type it returns keeps.

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
RequestTransformer.adjusted(service)(adjust: (String, ModelCapabilities) => ModelCapabilities): RequestTransformer
```

`RequestTransformer` is sealed: these factories are the only way to build one, so every transformer
reports the options it drops or adjusts. Use `adjusted` for vendor rules the registry does not record: you receive the model id and the
registry's capabilities and return the ones to apply. `llm4s-openai`'s `OpenAIModelRules` is the
example - the o-series take temperature 1 only and no system message, whatever the registry says:

```scala
RequestTransformer.adjusted(service) { (modelId, caps) =>
  if modelId.startsWith("acme-reasoner") then caps.withSupportsSystemMessages(false)
  else caps
}
```

`TransformationResult.transform(modelId, options, messages, transformer, dropUnsupported)` runs
the option and message transforms in one call and returns the transformed `options`, `messages`,
`requiresFakeStreaming` and `adjustments`.

**Reporting adjustments** ([RFC 0008](../rfcs/0008-generation-controls)). A caller learns which
requested options were not sent as asked from `Completion.adjustments`, a list of
`RequestAdjustment` (`Dropped`, `Adjusted`, `NotNeeded`, each naming a `RequestOption`). Your client:

- calls `transform` with `dropUnsupported = true` (the default), and puts `t.adjustments` on every
  `Completion` it returns, streamed or not: `completion.withAdjustments(t.adjustments)`;
- adds a `Dropped` for an option its own request mapping cannot send, rather than ignoring it. A set `promptCache`
  is `Dropped` when the request carries no cache setting, or `NotNeeded` when the provider caches a repeated prefix
  on its own ([provider-side prompt caching](cost-controls#provider-side-prompt-caching)). A set `serviceTier` is
  `Dropped` by a provider that has no tiers to choose ([service tiers](cost-controls#service-tiers)).

An `OpenAICompatibleDialect` does the second through `addReasoning`, which returns what it could not
send: the standard one reports a set `reasoning` or `thinking` as `Dropped`, and an override that sends them returns
`List.empty`, or an `Adjusted` for a level it sent as another. `OpenAICompatibleClient` puts the list on the
`Completion`. `org.llm4s.model.EffortMapping` (`private[llm4s]`) holds the shared pieces llm4s's own clients use: the
nearest level a model accepts, and the reports for a changed level and for a level `thinking = Off` overrode.

A `RequestTransformer` of your own reports nothing yet. The hook that turns the transformer's drops into
adjustments, `adjustOptions`, is `private[llm4s]`, so only llm4s's own transformers fill
`t.adjustments`; with yours the list is empty, and a `Left` from your `transformOptions` still fails the
request. Report what your request mapping drops as above.

An agent stores each call's adjustments in its durable `ModelCallCompleted` event, so a `reason`,
`requested` or `sent` string names an option, a model or a value, never request or response content.

The transform never logs. llm4s's own clients log each adjustment at WARN once per model and option.

### `EmbeddingProvider`

```scala
trait EmbeddingProvider:
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse]
```

What an `EmbeddingProviderDescriptor.build` returns. Report failures as `EmbeddingError(code,
message, provider)` - but not a cancellation: an interrupted `Llm4sHttpClient` call returns `CancelledError`, and
mapping it into an `EmbeddingError` (as `left.map(err => EmbeddingError(...))` does) hides it. Pass it through, as the
Voyage, Jina, Ollama and OpenAI providers do, and see [Cancellation](#cancellation).

`request.purpose` is an `InputPurpose`, `Document` (the default) or `Query`: whether the texts are to be
indexed or are a search query. If your vendor's API embeds the two differently, map it onto the vendor's own
parameter in `embed` (Voyage and Cohere send `input_type`, Jina sends `task`), and let a typed setting of the
provider, if it has one, win over it, because that is a deliberate choice and can say more than two values
can. If your models embed both alike, ignore it and do not send a field for it. Test the exact wire field
for both purposes against a local server, as `VoyageAIInputTypeSpec` does.

### Streaming: `SSEParser`, `StreamingAccumulator`, `ToolCallRules`

- `SSEParser.createStreamingParser()` returns an incremental parser: `addChunk(text)`, then drain
  `nextEvent()` / `hasEvents`, and `flush()` at end of stream. Feed it the body as it arrives, in
  chunks of any size: lines may end in LF, CRLF or CR, split anywhere. Each `SSEEvent` has `data`,
  `event`, `id` and `retry` (a `FiniteDuration`). `SSEParser.parseEvent` reads one event's lines.
- `StreamingAccumulator(model)` folds `StreamedChunk`s into a final `Completion` whose `model` is
  `model`: `addChunk(chunk)`, `updateUsage(usage)` with each `TokenUsage` the stream reports
  (thinking and cache counts included; a field a later report leaves at zero or `None` keeps its
  earlier value), then `toCompletion: Result[Completion]`. It keys tool calls by id, so **every
  tool-call chunk must carry its call's id** - if the wire format identifies continuations only
  by index, map index to id yourself; a chunk with an empty id is skipped. It is mutable: one per
  request.
- Pass each streamed argument fragment to the accumulator as-is, as `ujson.Str(raw)` (`ujson.Obj()`
  for an empty one): the accumulator joins the fragments and parses the whole. Do not parse a
  fragment on its own - a fragment that happens to be valid JSON (`":"`, `"Paris"`, `1`) would lose
  its quotes or be dropped. `ToolCallRules.parseArguments(raw: String): Result[ujson.Value]` is
  for a reassembled argument string: `{}` for empty or blank, the object when it is a complete JSON
  object, and otherwise a `Left`, the malformed-tool-call `ProcessingError` (cut or invalid JSON,
  JSON that is not an object, or JSON nested more than 512 levels deep, which is never parsed).
  `StreamingAccumulator.toCompletion` applies RFC 0009's tool-call rules to the joined arguments:
  a broken call is a `Left`, never `{}` or dropped; empty arguments stand only when the stream
  ended `ToolCalls` or `Stop`; and any call in a stream the output limit cut (`Length`) is a `Left`.
  Apply the same rules on your `complete` path: a call your client cannot read as a JSON object is
  a `Left`, and a reply that ended `Length` with a tool call runs none.

### `ProviderModelLister`

```scala
trait ProviderModelLister:
  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]]
```

Return one from `ProviderDescriptor.modelLister` if the vendor has a model-listing endpoint. If
it serves the OpenAI `/models` shape, `ProviderModelListers.openAICompatible(provider,
defaultBaseUrl, ...)` in `llm4s-openai-compatible` builds one for you (add that dependency);
its `sectionHeaders` parameter derives request headers from the section, e.g. from one of your
extras.

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
  protected def modelName: ModelName      = config.model

  private val transformer = RequestTransformer.default(registry)
  private val headers     = Map("Authorization" -> s"Bearer ${config.apiKey}", "Content-Type" -> "application/json")

  def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
    completeWithMetrics {
      TransformationResult
        .transform(config.model, options, conversation.messages, transformer)
        .flatMap(t =>
          send(AcmeWire.encode(config.model, t.messages, t.options)).map(_.withAdjustments(t.adjustments))
        )
    }

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions)(onChunk: StreamedChunk => Unit): Result[Completion] =
    completeWithMetrics(...) // postStream + SSEParser + StreamingAccumulator, as OpenAICompatibleClient does

  def getContextWindow(): Int     = config.contextWindow
  def getReserveCompletion(): Int = config.reserveCompletion

  private def send(requestText: String): Result[Completion] =
    val startedAt = Instant.now()
    val response  = http.post(s"${config.baseUrl}/chat", headers, requestText, timeout = 120.seconds)
    val result = response.flatMap { r =>
      if r.statusCode / 100 == 2 then AcmeWire.decode(r.body).map(withCost)
      else Left(HttpErrorMapper.toError(r.statusCode, r.body, providerName, r.headers))
    }
    ProviderExchangeRecorder.record(
      clientOptions.exchangeLogging,
      ProviderExchangeCall(
        provider = ProviderId(providerName),
        startedAt = startedAt,
        requestBody = requestText,
        model = Some(config.model),
        responseBody = response.toOption.map(_.body)
      ),
      result
    )
    result

  private def withCost(c: Completion): Completion =
    c.withEstimatedCost(c.usage.flatMap(CostEstimator.estimate(config.model, _)))
```

`AcmeWire` stands for your own request encoding and response decoding.

### Content parts

A `UserMessage` and a `ToolMessage` carry `parts: Seq[ContentPart]`: `Text`, `Image` and `Document`, each image
or document from a `MediaSource` (`Url`, `Inline` bytes, or a `ProviderFile` uploaded to a named provider). Your
encoder sends every part your API takes, in each role it takes it, and fails the request **before sending it** with
`UnsupportedContentPartError(providerName, part.kind, role.name)` for any other: never drop a part, and never turn
one into text. Send a `ProviderFile` only when its `provider` is your provider id. A message with exactly one text
part should encode as your API's plain text form, as before; `message.content` is the text view. The matrix of what
each llm4s provider sends is in [Multimodal content](multimodal).

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
    defaultBaseUrl = Some(BaseUrl("https://api.acme.example/v1")),
    defaultModel   = Some(ModelName("acme-embed-1")),
    apiKeyEnv      = Seq("ACME_API_KEY")
  )

  // What ModelDimensionRegistry answers from; override dimensionsOf for model-name variants.
  override val modelDimensions: Map[ModelName, Int] = Map(ModelName("acme-embed-1") -> 1024)

  def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
    Right(
      AcmeEmbeddingProvider(
        config,
        // the caller's own client, when EmbeddingClientOptions supplied one; else one for the config's proxy and TLS
        context.httpClient.getOrElse(Llm4sHttpClient.create(HttpClientOptions(config.network)))
      )
    )
```

`context.httpClient` is the HTTP client a caller passed with
`EmbeddingClient.from(config, EmbeddingClientOptions().withHttpClient(client))`: send your requests
through it and do not close it, as a chat client does with `LlmClientOptions.httpClient`. Prove it with
`assertEmbeddingUsesSuppliedHttpClient(AcmeEmbeddings, config)` in the module spec.

The section a descriptor is handed, `EmbeddingProviderSection`, is typed as a chat section is: `baseUrl` is an
`Option[BaseUrl]` and `model` an `Option[ModelName]`. A key of your own - a region, an API version - is declared
in `EmbeddingConfigSpec.extras` with the same `ProviderConfigKey` a chat provider uses:

```scala
override val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec(
  requiresApiKey = true,
  extras = Seq(ProviderConfigKey.optional("region", "the Acme region serving the model", default = Some("eu-west")))
)
```

The loader reads `llm4s.embeddings.acme.region`, applies the default, and fails the load naming a required key
that is missing. The value arrives in `section.extra("region")`, and the default `buildConfig` carries every
declared key over, so `build` reads `config.extra("region")`. A key you do not declare is not passed on, and a
name every section has (`EmbeddingConfigSpec.BuiltinKeys`: `apiKey`, `apiKeyFile`, `baseUrl`, `model`,
`timeouts`) cannot be declared.

`EmbeddingConfigSpec.defaultApiKey` is for a provider that takes a key but needs no real one
(local Ollama). Override `buildConfig(section, context)` only when the config cannot be
expressed with the spec; `context.modelOverride` is the `<model>` half of `EMBEDDING_MODEL=<id>/<model>`, which
takes precedence over the section's `model`.

## Testing: `Llm4s<Name>ModuleSpec` {#testing}

Every provider module in this repository proves its registration in one spec, and yours should
too - it replaces the exhaustivity check the compiler gave when providers were a closed `enum`.
It shows that the module is discovered, that it is the only module supplying its ids, that it can
be registered explicitly, that each descriptor gets from config to client, and that your
`reference.conf` binds the variable `apiKeyEnv` names.

Those checks ship as **`llm4s-provider-testkit`**, the same ones the in-repo provider modules
run. Add it in test scope; it brings ScalaTest with it:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-provider-testkit" % llm4sVersion % Test
```

The testkit **requires JDK 21**: `assertCancelsWhenInterrupted` and its siblings run the call on a
virtual thread (`Thread.ofVirtual`), and `LocalProviderTestServer` answers on
`Executors.newVirtualThreadPerTaskExecutor`, both JDK 21 APIs. The build that runs your
`Llm4s<Name>ModuleSpec` needs a JDK 21 toolchain, whatever your provider module itself targets
(the library's own minimum JDK is settled in
[#1493](https://github.com/llm4s/llm4s/issues/1493)).

It has these parts, all in `org.llm4s.testkit`:

| | What it gives you |
|---|---|
| `ProviderModuleChecks` | The checks, as assertions: `assertModule` (= `assertDiscovered` + `assertSoleSupplier` + `assertRegistrableWith`), `assertBuildsClient` / `buildClient`, `assertRefusesForeignConfig`, `assertModelCallContract` (how a reply ends, on `complete` and on a stream alike, and that the client really streams: see [the model call contract](#the-model-call-contract)), `assertCancelsWhenInterrupted`, `assertCancelsStreamWhenInterrupted`, `assertOneToolResultPerCall`, `assertContentParts` (each `ContentPartCase` your client declares reaches a local server with its payload; every other fails with `UnsupportedContentPartError` and sends nothing), `assertHonoursApiKeyProvider` (two requests with the key rotated between them carry the old key and then the new), `assertEmbeddingHonoursApiKeyProvider` (the same for `embed`), `assertBuildsEmbeddingProvider`, `assertCredentialBindings`, `assertEmbeddingCredentialBindings`, `assertUsesSuppliedHttpClient` (a client built with `LlmClientOptions.withHttpClient` sends `complete` and `streamComplete` through it, and does not close it). Mix the trait into a spec of any ScalaTest style, or call the companion object. A failure points at the line in your spec. |
| `ProviderTestConfig` | `loadSection`, `loadProvider` and `loadEmbeddings`: config loaded as an application loads it, from a HOCON string over every `reference.conf` on the classpath, with `${?VAR}` resolved against a `Map` you pass - never the real environment, so an exported `ACME_API_KEY` on your machine cannot make a test pass that fails in CI. |
| `CredentialsRoundTrip` | `chatSectionKey`, `chatBindings`, `embeddingsKey`, `embeddingBindings`: which key a section or embeddings block with no `apiKey` of its own ends up with, for cases the assertions do not cover - an alias, two variables in precedence order, a variable that must *not* be picked up. |
| `LocalProviderTestServer` | `withServer(path)(handler)(baseUrl => ...)`, `sendJsonResponse`, `sendSseResponse`, `holdOpen` / `streamThenHold` (requests that never finish, released when `withServer` ends), OpenAI-format bodies and an Anthropic-format `anthropicMessage`: the JDK's HTTP server on an ephemeral port, to point a client at. `ToolResultContract` (with `ToolMessageFormat`) reads a request body for the tool-result contract below. |

```scala
package com.acme.llm4s

import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class Llm4sAcmeModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val section = NamedProviderConfig(
    provider = AcmeProvider.id, model = ModelName("acme-large"), baseUrl = None,
    apiKey = Some(ApiKey("test-key")),
    extras = Map("region" -> "eu-west") // built in code, so no validation fills the default
  )

  "the my-llm4s-acme services entry" should {
    // Discovered by ProviderRegistry.discover(), every id and alias resolving to your
    // descriptors; no other module supplying "acme"; and ProviderRegistry.ofModules works.
    "register the module" in assertModule(new Llm4sAcmeModule)
  }

  "AcmeProvider" should {
    "build an AcmeClient from a section, and refuse another provider's config" in {
      assertBuildsClient(AcmeProvider, section) shouldBe an[AcmeClient]
      assertRefusesForeignConfig(AcmeProvider)
    }

    // AcmeContractFixtures.replies: one Applicability per ContractCase, below
    "report how each reply ended, on complete and on a stream alike, and really stream (RFC 0009)" in {
      assertModelCallContract(AcmeContractFixtures.replies, "/v1/chat") { baseUrl =>
        assertBuildsClient(AcmeProvider, section.withBaseUrl(BaseUrl(baseUrl)))
      }
    }

    "load from a named section as an application does, extras defaulted" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "acme-main",
          """llm4s.providers.acme-main { provider = "acme", model = "acme-large" }""",
          Map("ACME_API_KEY" -> "test-key")
        )
        .map(_.asInstanceOf[AcmeConfig].region) shouldBe Right("eu-west")
    }
  }

  "the my-llm4s-acme reference.conf" should {
    "bind ACME_API_KEY to llm4s.credentials.acme.apiKey, for chat and embeddings" in {
      assertCredentialBindings(AcmeProvider)
      assertEmbeddingCredentialBindings(AcmeEmbeddings, "acme-embed-1")
    }

    "give the acme-ai alias the same key" in {
      given ProviderRegistry = ProviderRegistry.default
      CredentialsRoundTrip.chatSectionKey("acme-ai", Map("ACME_API_KEY" -> "k")) shouldBe Right(Some("k"))
    }
  }
```

`assertCredentialBindings` loads a section with no `apiKey` once per variable in `apiKeyEnv`,
with only that variable set, so the shared credential is the only place a key can come from; when
it fails it names the `reference.conf` line that is missing. Test the client itself against
`LocalProviderTestServer` too, including that an error status maps to the right `LLMError`.

### The model call contract

Every chat client reports how a reply ended the same way, on `complete` and on `streamComplete`
([RFC 0009](../rfcs/0009-model-call-contract)):

- **A finish reason on every `Completion`.** Map your provider's own value to `FinishReason`
  (`Stop`, `Length`, `ToolCalls`, or `Other(raw)` for anything else), in your own module. Never
  report `Stop` for a reason the provider did not send: a `Completion` built without one carries
  `FinishReason.Unknown`, which is also what to report when the provider sent none.
- **A stream is `Right` only if the provider sent its end marker** (`finish_reason`, `[DONE]`,
  `message_stop`, `done: true`, a `finishReason`...). A stream that ends without one was cut off:
  a retryable `NetworkError`, or a `ServiceError` with status 502.
- **An error ends the call.** An error event after the 200 status, or an error-class reason, is a
  `Left` carrying the provider's message. A content filter is `Left(ContentFilteredError)`. Chunks
  already delivered stay delivered.
- **Tool-call arguments.** A call whose arguments are not a complete JSON object is the
  malformed-tool-call `Left(ProcessingError)`, never `{}` and never dropped. Absent or blank
  arguments mean `{}` only when the reply ended `ToolCalls` or `Stop`. A tool call in a reply that
  ended `Length` is a `Left`: the model was cut while it wrote its calls, so none is run.

`assertModelCallContract(cases, path)(baseUrl => client)` checks all of this. For each
`ContractCase` you either supply your provider's bytes (`Applicability.Applies(RecordedReply(complete, stream))`,
each a `LocalProviderTestServer` handler, captured from the real service where you can) or declare
it `Applicability.NotApplicable(reason)`. A case missing from the map fails the check, so a gap
shows in your spec instead of being missed, and a case added to the testkit later fails your spec
until you declare it.

| `ContractCase` | The reply | Required result |
|---|---|---|
| `CleanText` | the model ends its turn with text | `Right`, `FinishReason.Stop`, non-empty text, no tool call |
| `TextCutByLimit` | the output limit cuts the text | `Right`, `FinishReason.Length`, the partial text |
| `ToolCall` | the model calls a tool | `Right`, `FinishReason.ToolCalls`, each call's arguments a JSON object |
| `NoArgumentToolCall` | a call to a tool with no parameters, arguments absent, blank or `{}` | `Right`, `FinishReason.ToolCalls`, arguments `{}` |
| `ToolCallCutByLimit` | the output limit cuts the reply while it makes a tool call | `Left(ProcessingError)`, the malformed tool call |
| `MalformedArguments` | arguments that are not a JSON object, in a reply that otherwise ended cleanly | `Left(ProcessingError)`, the malformed tool call |
| `ContentFiltered` | the provider's content filter stops the reply | `Left(ContentFilteredError)` |
| `StreamEndsEarly` | stream only: some text, then the stream ends without the end marker | `Left(NetworkError)`, or `Left(ServiceError)` with status 502 |
| `ErrorEvent` | stream only: some text, then an error event stating `ContractCase.ProviderErrorMessage` | a `Left` whose message carries `ContractCase.ProviderErrorMessage` |

The check runs `complete` with the reply's `complete` handler and `streamComplete` with its
`stream` handler, each against a fresh local server. The two stream-only cases run
`streamComplete` alone; build them with `RecordedReply.streamOnly(stream)`. A stream that
succeeds must also have delivered what it returned: its chunks' text joined is the
`Completion`'s text, the last reason a chunk carried is its finish reason, and its chunks name
the same tool calls.

`CleanText` must apply. Every chat client can answer with text, and that case's stream is what
proves the client really streams rather than falling back to `complete()`. A client that runs
in-process, with no wire format, still declares `CleanText` `Applies` and builds the client from a
scripted engine whatever base URL it is given, as `llm4s-jlama` does.

Declare a case `NotApplicable` only when your provider cannot produce it, and say why in the
reason, which must not be blank: `NotApplicable("Acme has no content filter")`,
`NotApplicable("Acme does not call tools")`.

```scala
object AcmeContractFixtures:
  import org.llm4s.testkit.{ Applicability, ContractCase, RecordedReply }
  import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, sendSseResponse }

  private def applies(body: String, sse: String) =
    Applicability.Applies(RecordedReply(sendJsonResponse(_, 200, body), sendSseResponse(_, sse)))

  val replies: Map[ContractCase, Applicability] = Map(
    ContractCase.CleanText -> applies(
      """{"text":"Hello","stop":"end_turn"}""",
      "data: {\"delta\":\"Hel\"}\n\ndata: {\"delta\":\"lo\",\"stop\":\"end_turn\"}\n\n"
    ),
    // ... TextCutByLimit, ToolCall, NoArgumentToolCall, ToolCallCutByLimit, MalformedArguments
    ContractCase.ContentFiltered -> Applicability.NotApplicable("Acme has no content filter"),
    ContractCase.StreamEndsEarly ->
      Applicability.Applies(RecordedReply.streamOnly(sendSseResponse(_, "data: {\"delta\":\"Hel\"}\n\n"))),
    ContractCase.ErrorEvent -> Applicability.Applies(
      RecordedReply.streamOnly(
        sendSseResponse(_, s"data: {\"delta\":\"Hel\"}\n\ndata: {\"error\":\"${ContractCase.ProviderErrorMessage}\"}\n\n")
      )
    )
  )
```

The in-repo fixtures are worked examples of each wire format: `ChatCompletionsContractFixtures`
(`modules/openai-compatible`), `AnthropicContractFixtures`, `GeminiContractFixtures`,
`OllamaContractFixtures`, `BedrockContractFixtures`, `WatsonXContractFixtures` and
`PerplexityContractFixtures`, each beside its module's `Llm4s<Name>ModuleSpec`.

**Rule 3 on your `complete` path is one call.** `StreamingAccumulator.toCompletion` applies the
tool-call rules to a stream; `ToolCallRules.checked` applies them to a whole reply. After your client
parses a `complete` reply, pass the `Completion` through it: a reply that ended `Length` with a tool
call, and one whose call arguments are not a JSON object, come back as the malformed-tool-call
`ProcessingError`:

```scala
import org.llm4s.llmconnect.provider.ToolCallRules

AcmeWire.decode(body).flatMap(ToolCallRules.checked)
```

Read a call's argument text with `ToolCallRules.parseArguments(name, raw)`, which is `{}` for blank
text and a `Left` naming the call for anything that is not a complete JSON object. A stream that
delivers a whole call in one chunk gives the accumulator `ToolCallRules.wholeCall(chunk)`, the call's
arguments as their JSON text, and the caller's `onChunk` the chunk unchanged: the accumulator reads an
empty `ujson.Obj()` as a placeholder, so an explicit `{}` would otherwise read as no arguments.

Refuse the content parts your wire format cannot carry with `PartEncoding.check(provider, messages,
user, tool)` before anything is sent, and encode a message's parts with `PartEncoding.encodeAll(provider,
role, parts)(encode)`: both take the parts you send as a partial function, and a part it does not cover
is an `UnsupportedContentPartError`, never dropped.

The `ToolCallCutByLimit` and `MalformedArguments` cases fail on `complete` until you do.

### Cancellation

A provider must return `Left(CancelledError)`, with the interrupt flag kept, when its thread is
interrupted. It must not throw `InterruptedException` or clear the flag. A client that extends
`BaseLifecycleLLMClient` gets this from `completeWithMetrics`; `Llm4sHttpClient` already returns
`CancelledError` for an interrupted request or stream read. Your module spec should run
`assertCancelsWhenInterrupted` and `assertCancelsStreamWhenInterrupted` against
`LocalProviderTestServer.holdOpen` and `streamThenHold`; an embedding provider runs
`assertEmbeddingCancelsWhenInterrupted`, and any other client call that returns a `Result` (a reranker, a speech
or image client) `assertCallCancelsWhenInterrupted("name")(call)`. A client that does not extend `BaseLifecycleLLMClient` can use the public helpers `CancelledError.attempt`, `CancelledError.whenInterrupted`, `CancelledError.fromThrowable` and `CancelledError.isCancellation`. `fromThrowable` and `isCancellation` only classify: they never set the flag, so if your code catches an `InterruptedException` itself, restore the flag with `Thread.currentThread().interrupt()`. A bare `InterruptedIOException`, such as OkHttp's call timeout, is not a cancellation unless the thread is interrupted or an `InterruptedException` lies beneath it.

### `validate()`

`LLMClient.validate()` is how an application checks a client at startup, so it must never report success without
having checked something. Its default is `Left(ValidationUnsupportedError)`: nothing was checked.

- If the vendor has a cheap request that needs the same credential a completion does - a lookup of one model, a
  model listing - override `validate()` to send it, with a short timeout, and map a refusal as a completion's is
  mapped (`HttpErrorMapper`). Prefer a lookup of the configured model, which also tells the user that the model
  exists. Never send a completion: `validate()` costs no tokens. Check a listing endpoint needs the key: one that
  answers without it would pass a wrong key.
- If it has none, leave the default.

Your module spec runs `assertValidates(accept, reject)(clientAt)` for the first kind, against a
`LocalProviderTestServer`: a request must reach the server before `Right(())`, a 401 must be an
`AuthenticationError`, and nothing listening must be a `Left`. For the second it runs
`assertValidationUnsupported(client)`. A client on `OpenAICompatibleClient` has the first already: `GET /models`,
or the dialect's `validationUrl` (`None` for a vendor with no such request). A vendor that refuses a wrong key with
a status other than 401 or 403 says so in the dialect's `refusesCredential`, so the refusal is an
`AuthenticationError` from `validate()` and from a completion alike.

### Tool results

An agent's tool loop sends a conversation in which every tool call has exactly one result, straight
after the assistant message that made it, and relies on your client to send it that way: OpenAI's
format puts one `role: "tool"` message per call after the assistant message, Anthropic's one
`tool_result` block per call at the start of the next user turn. A chat provider whose API takes
tools runs `assertOneToolResultPerCall(format, response, path)(baseUrl => client)`: it sends each
of `ToolResultContract.cases` - a parallel batch with error, rejection and denial results, an
unknown tool, provider call ids reused across turns, a handoff, a refused handoff batch - to a
`LocalProviderTestServer` answering with `response`, and fails on any
`ToolResultContract.violations` in the body the server received, and when the body carries fewer
calls or native results than the conversation: a client that drops a call it cannot pair, or sends
a result as plain text, would hide a broken conversation rather than send it. `ToolMessageFormat`
is `OpenAIChat` or `AnthropicMessages`; a provider with another wire format checks its own
encoding against `ToolResultContract.cases` the same way.

```scala
"send exactly one tool result per call" in {
  assertOneToolResultPerCall(ToolMessageFormat.OpenAIChat, LocalProviderTestServer.openAICompletion("done")) {
    baseUrl => assertBuildsClient(AcmeProvider, section.withBaseUrl(BaseUrl(baseUrl)))
  }
}
```

## Stability

`llm4s-core` reaches a binary-compatibility baseline (MiMa) at a pre-1.0 release, once the designs are final, and freezes at 1.0. The
provider-author SPI described on this page is **public and frozen at 1.0**: binary-compatible
across all 1.x releases, so a provider compiled against 1.0 keeps working. That covers:

- the registration SPI in `org.llm4s.llmconnect.spi` - `Llm4sProviderModule`,
  `ProviderDescriptor`, `ProviderConfigSpec`, `ProviderConfigKey`, `ProviderFeatures`,
  `EmbeddingProviderDescriptor`, `EmbeddingConfigSpec`, `EmbeddingProviderSection`,
  `ProviderConfigContext`, `ProviderBuildContext`, `EmbeddingConfigContext`, `EmbeddingBuildContext`,
  `ProviderRegistry`;
- `ProviderConfig`, `NamedProviderConfig`, `ContextWindowResolver`, `LlmClientOptions`, `EmbeddingClientOptions`;
- `BaseLifecycleLLMClient`, `MetricsRecording`, `ProviderExchangeRecorder`,
  `ProviderExchangeLogging`, `HttpErrorMapper`, `CostEstimator`, `EmbeddingProvider`,
  `StreamingAccumulator`, `SSEParser`, `ToolCallRules` (`checked`, `parseArguments`, `wholeCall`),
  `PartEncoding` (`check`, `encodeAll`), `Llm4sHttpClient`,
  `ProviderModelLister`, `RequestTransformer` and `TransformationResult`.

`llm4s-provider-testkit` is **Beta**, not part of the frozen SPI: it is a test-scope dependency,
so a change to it can break your tests but never your users, and it may gain checks in a minor
release (with a migration note).

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
- [ ] `ProviderFeatures` honest about streaming, tool calling and `customHttpClient` (opt in only when `LlmClientOptions.httpClient` is honoured)
- [ ] client built on `BaseLifecycleLLMClient`, errors via `HttpErrorMapper`, cost via
      `CostEstimator`, exchanges via `ProviderExchangeRecorder`
- [ ] no exceptions escape, no environment reads
- [ ] `t.adjustments` on every `Completion`, streamed or not, plus a `Dropped` for each option the
      request mapping cannot send ([reporting adjustments](#requesttransformer-and-transformationresult))
- [ ] `Llm4s<Name>ModuleSpec`, on `llm4s-provider-testkit`, covering discovery, sole ownership,
      explicit registration, the config-to-client round trip and the credential binding
- [ ] a chat provider runs `assertValidates`, or `assertValidationUnsupported` when the vendor has no cheap
      authenticated request ([`validate()`](#validate))
- [ ] a chat provider that takes tools runs `assertOneToolResultPerCall`
- [ ] a chat provider runs `assertModelCallContract`, every `ContractCase` declared, `CleanText` applying,
      and its `complete` path refuses a tool call in a `Length` reply ([the model call contract](#the-model-call-contract))
