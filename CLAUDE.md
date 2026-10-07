# CLAUDE.md - AI Assistant Guide for LLM4S

## Project Overview

**LLM4S** (Large Language Models for Scala) is a framework for building LLM-powered applications in Scala with:
- Multi-provider support (OpenAI, Anthropic, Azure, Ollama, Google Gemini, IBM watsonx.ai)
- Type-safe design with `Result[A]` error handling
- Agent framework with tools, guardrails, handoffs, and memory
- Scala 3 only (3.7.1). Scala 2.13 support is deferred to post-1.0 — see [#1126](https://github.com/llm4s/llm4s/issues/1126)

**Tech Stack:** Scala 3.7.1, JDK 21, SBT, ScalaTest, Cats, uPickle, Docker

## Core Principles

1. **Use `Result[A]` instead of exceptions** - `type Result[+A] = Either[LLMError, A]`
2. **Use `Llm4sConfig` at the app edge** - Never use `sys.env`, `System.getenv`, or `ConfigSource.default` directly in core code
3. **Use type-safe newtypes** - `ModelName`, `ApiKey`, `ConversationId` etc.
4. **Scala 3 idioms are welcome** - `opaque type`, `using` clauses, `enum` and `extension` are all in use. Do not rewrite them to a Scala 2.13-compatible subset; see [#1127](https://github.com/llm4s/llm4s/issues/1127)

## Active: modularisation programme (#1126)

`modules/core` is being split into per-concern modules ahead of a 1.0 API freeze. **Before moving, renaming, or adding files under `modules/core`, read [#1126](https://github.com/llm4s/llm4s/issues/1126) and the relevant slice issue.**

Slice order — each is an issue with its own scope and gotchas:

| Slice | Issue | Carves |
|---|---|---|
| 0 ✅ | [#1127](https://github.com/llm4s/llm4s/issues/1127) | build + tracker prerequisites |
| 1 ✅ | [#1128](https://github.com/llm4s/llm4s/issues/1128) | `llm4s-rag`, `llm4s-knowledgegraph` |
| 2 ✅ | [#1129](https://github.com/llm4s/llm4s/issues/1129) | `llm4s-memory`, `llm4s-memory-postgres` |
| 3 ✅ | [#1130](https://github.com/llm4s/llm4s/issues/1130) | `llm4s-mcp`, `llm4s-media`, `llm4s-image`, `llm4s-speech` |
| 4 ✅ | [#1131](https://github.com/llm4s/llm4s/issues/1131) | provider registration SPI |
| 5 ✅ | [#1132](https://github.com/llm4s/llm4s/issues/1132) | provider modules - `llm4s-ollama`, `llm4s-gemini`, `llm4s-anthropic`, `llm4s-openai`, `llm4s-openai-compatible` (incl. Mistral, Cohere), `llm4s-voyage`; core holds no client |
| 6 ✅ | [#1133](https://github.com/llm4s/llm4s/issues/1133) | `TracingBackend` SPI; `llm4s-observability` (Langfuse, trace collector/model/store, `CostTracker`); `llm4s-observability-prometheus`; pre-baseline API cleanup (passes 1-8) |
| 7 ✅ | [#1242](https://github.com/llm4s/llm4s/issues/1242) | `llm4s-agent-tools` (built-in tools + their config); `llm4s-agent` (`agent`, `assistant`); spine re-audit (core 20.7k lines) |
| 8 ⏳ | [#1281](https://github.com/llm4s/llm4s/issues/1281) | release, not a carve: publish 0.5.0, MiMa baseline on the Frozen-at-1.0 modules, `@Stable` / `@Experimental`, compatibility policy, g8 template; then 1.0 |

**Invariants for every carve:**

1. **Keep package names.** Move files between sbt modules without renaming `org.llm4s.*`, so each carve stays source-compatible — users add a dependency, not new imports. The one sanctioned exception is `org.llm4s.extract` in slice 1.
2. **Tests move with their code.** Leaving them behind silently drops coverage in both modules.
3. **`reference.conf` keys move with their code.** HOCON merges across jars; keys left behind become defaults that apply to nothing.
4. **Coverage floor and codecov flag land in the same commit as the carve.** A missing flag makes the moved code untracked rather than failing.
5. **One migration note per slice**, in CHANGELOG and docs.
6. **Integration suites move with their code, and keep a tier.** A suite that needs a
   database, a container, a model server or an API key lives in `modules/it` and declares
   exactly one tier tag from `org.llm4s.it.tags`; `sbt it/itTierCheck` fails the build
   otherwise. Carving code out of `core` without carrying its integration suite - or moving
   the suite and leaving it untagged - removes the only signal the carve has (see
   [#1143](https://github.com/llm4s/llm4s/issues/1143)).
7. **Add the new module to the `docs` project in `build.sbt`.** The published Scaladoc is one
   aggregate API tree built from that project's source list, not from `core` alone. A module
   missing from it does not fail - its API pages are simply never generated, which reads as
   "this API does not exist". Slices 1 and 2 both hit this and it went unnoticed until slice 3;
   `pages.yml` now fails the deploy if a known package is absent, and the ScalaDoc CI job runs
   `docs/doc` on every PR.
8. **A provider is a `ProviderDescriptor`, not an edit to shared files** - since slice 4 PRs 2
   and 3 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Implement
   `org.llm4s.llmconnect.spi.ProviderDescriptor`, list it in an `Llm4sProviderModule`, and
   declare that module in `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` - a
   `class` with a public no-arg constructor, never an `object`. Adding a provider is then adding
   a dependency; `ProviderRegistry.of` / `.ofModules` / `.withProvider` remain for explicit
   registration. Nothing in `llm4s-core` needs editing: `ProviderCapabilities`,
   `ProviderCapabilitiesRegistry` and the twelve `NamedProviderValidators` objects are gone, and
   the dispatch `match` expressions in `LLMConnect` and `NamedProviderLoader` with them.
   **`llm4s-core` ships no provider and holds no provider list**: `BuiltinProviders`,
   `BuiltinProviderModule`, `BuiltinProvidersSpec`, core's main `META-INF/services` entry and
   `ProviderRegistry.builtin` were deleted when the last client left core in slice 5
   ([#1132](https://github.com/llm4s/llm4s/issues/1132)). Never add a provider, a provider
   list or a services entry back to core. Each provider module proves its own registration
   instead, in an `Llm4s<Name>ModuleSpec`: discovered through `ProviderRegistry.discover()`,
   the only module supplying its ids, registrable with `ProviderRegistry.ofModules`, and a
   config-to-client round trip per descriptor - that spec is what replaced the compiler's
   exhaustivity check over the old closed `enum`. `modules/ollama` (chat plus embeddings) and
   `modules/providers/voyage` (embeddings only) are the worked examples: their own
   `Llm4s<Name>Module`, services entry, `reference.conf` block and module spec, and nothing of
   them in core.

Current per-module coverage floors are recorded in [#1127](https://github.com/llm4s/llm4s/issues/1127); floors ratchet upward and are never lowered.

## Repository Structure

```
llm4s/
├── modules/
│   ├── core/                  # Core library (published)
│   ├── rag/                   # RAG, vector stores, chunking, reranking, extraction (published)
│   ├── knowledgegraph/        # Knowledge graph model, storage, query (published)
│   ├── memory/                # Agent memory: managers, in-memory + SQLite stores (published)
│   ├── memory-postgres/       # Agent memory: Postgres/pgvector store (published)
│   ├── mcp/                   # Model Context Protocol client, server, transports (published)
│   ├── media/                 # Shared media vocabulary: MediaType, MediaCategory (published)
│   ├── image/                 # Image generation and vision/processing clients (published)
│   ├── speech/                # Speech-to-text and text-to-speech (published)
│   ├── ollama/                # Ollama chat + embedding provider (published)
│   ├── gemini/                # Gemini API + Vertex AI chat providers (published)
│   ├── anthropic/             # Anthropic Claude chat provider + Anthropic SDK (published)
│   ├── openai/                # OpenAI, Azure, Requesty chat + OpenAI embeddings + openai-java SDK (published)
│   ├── openai-compatible/     # One SDK-free chat-completions client: DeepSeek, Z.ai, OpenRouter, Mistral, Cohere, generic (published)
│   ├── providers/             # Community provider modules, one `llm4s-<name>` each (published)
│   │   ├── voyage/            # Voyage AI embedding provider
│   │   ├── bedrock/           # AWS Bedrock chat provider + AWS SDK bedrockruntime (Converse, ConverseStream)
│   │   ├── jina/              # Jina AI embedding provider (typed JinaTask)
│   │   ├── cohere/            # Cohere embedding provider, native /v2/embed (typed CohereInputType)
│   │   └── watsonx/           # IBM watsonx.ai chat provider (Beta; never run against the live service)
│   ├── provider-testkit/      # Checks for a provider module's Llm4s<Name>ModuleSpec, for external authors too (published)
│   ├── observability/         # Langfuse tracing backend, trace collector/model/store, CostTracker (published)
│   ├── observability-prometheus/ # Prometheus MetricsCollector + /metrics endpoint + Prometheus client (published)
│   ├── agent/                 # Agent runtime: Agent, guardrails, handoffs, orchestration, streaming; assistant (published)
│   ├── agent-tools/           # Built-in tools: core utilities, filesystem, HTTP, shell, web search (published)
│   ├── llm4s-effect/          # cats-effect IO / fs2 wrappers over LLMClient and Agent (published)
│   ├── llm4s-zio/             # ZIO 2 / ZIO Streams wrappers over LLMClient and Agent (published)
│   ├── java-api/              # Java facade over the client and agent (published)
│   ├── spring-boot-starter/   # Spring Boot auto-configuration on java-api (published)
│   ├── kotlin-api/            # Kotlin coroutine API: a separate Gradle build, not published yet
│   ├── samples/               # Usage examples
│   ├── deploy-service/        # /health and /llm-check service + image for the staged-deployment template (unpublished)
│   ├── workspace/             # Containerized execution
│   ├── config-policy/         # Config policy checks + CLI
│   ├── knowledgegraph-neo4j/  # Neo4j graph store
│   ├── trace-opentelemetry/   # OpenTelemetry tracing backend, `llm4s-observability-otel` (published)
│   ├── benchmarks/            # JMH benchmarks
│   ├── gradle-demo/           # Gradle consumer of llm4s, kept buildable (not published)
│   └── it/                    # Integration tests
├── docs/                # Documentation
├── project/             # SBT config
└── build.sbt
```

Slices 0 to 3 have landed. `modules/rag`, `modules/knowledgegraph`, `modules/memory`,
`modules/memory-postgres`, `modules/mcp`, `modules/media`, `modules/image` and `modules/speech`
are carved, so `modules/core` no longer holds `rag`, `vectorstore` (bar `PostgresVectorHelpers`,
see below), `chunking`, `reranker`, `eval`, `knowledgegraph`, `agent/memory`, `mcp`,
`imagegeneration`, `imageprocessing` or `speech`, nor any Tika/POI/PDFBox/jsoup/AWS, HikariCP,
Postgres, SQLite, Java-WebSocket, Vosk or JNA dependency. Slices 4 to 7 then took the
provider clients, the observability integrations, the built-in tools and the agent runtime, so core
is now the ~19k-line spine (see below).
Those three JDBC dependencies also left `commonSettings`, which used to put them on every
module's classpath - declare them per-module if you add database code. The build now has **no
third-party resolvers at all**: the "Vosk Repository" at alphacephei.com was the last one, and
it went with the speech carve because Vosk publishes to Maven Central and it had never resolved
anything. Think hard before adding one back.

Slice 5 is done. `modules/ollama` carries the Ollama chat client, embedding provider,
`OllamaConfig`, model lister and its `llm4s.embeddings.ollama` block, so core's tests cannot
use Ollama as a convenient no-key provider any more - use a fixture descriptor, as
`EmbeddingProviderSpiSpec` and `ModelDimensionRegistrySpec` do. `modules/gemini` followed,
carrying both Google providers - the Gemini API and Vertex AI, which only calls Gemini models in
the same JSON format and needs no extra dependency. `modules/anthropic` came third and took the
Anthropic Java SDK with it. `modules/openai` came fourth with the three providers that share
`OpenAIClient` - OpenAI, Azure and Requesty - plus `OpenAIEmbeddingProvider`, `AzureConfig` and
the tool helper, and took the Azure OpenAI SDK: **core now depends on no vendor SDK
(`com.anthropic`, `com.azure`, `com.openai`)**, and must not again. `OpenAIClient` has since
moved from Microsoft's deprecated `com.azure:azure-ai-openai` to OpenAI's `com.openai:openai-java`,
which serves Azure too (`AzureApiKeyCredential`, a forced `AzureUrlPathMode`, `api-version`), so
`AzureToolHelper` became `OpenAIToolHelper`; tests build SDK objects from JSON through
`ObjectMappers.jsonMapper()` (`OpenAISdkFixtures`), and read responses leniently through
`_field().asKnown()`, because the SDK's plain getters throw on a missing field. `modules/openai-compatible`
came fifth and is a **consolidation, not a pure move**: DeepSeek, Z.ai and OpenRouter had three
~400-line copies of one SDK-free chat-completions client, and are now thin subclasses of
`OpenAICompatibleClient`, each with an `OpenAICompatibleDialect` (headers, content encoding,
assistant-content policy, reasoning request, content/thinking/reasoning-token decoding,
tool-call parser - every member defaults to the standard format). The module also holds
`OpenAIConfig` - `llm4s-openai` depends on it for that, never the reverse, which would put the
OpenAI SDK on every OpenAI-compatible user's classpath - and the generic `openai-compatible`
provider, the standard dialect configured entirely from a named section (`baseUrl`, `model`,
optional `apiKey`, `contextWindow`, `reserveCompletion`, `headers`; `headers` is a built-in field
of `NamedProviderConfig`, the other two are the provider's own declared extras). **A new OpenAI-compatible provider is a
dialect and a descriptor in that module, or just config** - check whether `openai-compatible`
covers it before writing one; never another copy of the client. Mistral and Cohere followed as
dialects: Mistral over its OpenAI-format `/v1/chat/completions` (nine-character tool-call ids,
no empty assistant turns, content-as-chunks with thinking), Cohere over its OpenAI-compatibility
API (`https://api.cohere.ai/compatibility/v1`; `developer` system role, `json_object`+`schema`
response format; a configured native root gets `/compatibility/v1` appended). That gave both
streaming, which they had never had (#925). The dialect hook therefore also has
`sendEmptyAssistantTurns`, `encodeToolCallId`, `systemRole` and `encodeResponseFormat`, and the
base client reports streamed token usage. Community providers that are **not** OpenAI-compatible
live under `modules/providers/<name>`, published as `llm4s-<name>`: Voyage
(`modules/providers/voyage`, `llm4s-voyage`, embeddings only) was the first. With it core held
no client, and `BuiltinProviders` went (invariant 8). `StreamingResponseHandler` (with
`forProvider`, `OpenAIStreamingHandler` and `AnthropicStreamingHandler`) and
`OpenRouterToolCallDeserializer` were deleted with the openai-compatible carve: no client used
them. A streamed tool call is split across deltas that
continuations identify only by `index`; clients must give each continuation its call's id
(`OpenAICompatibleClient.StreamToolCalls`), because `StreamingAccumulator` keys calls by id and
skips a chunk with none. Tests that need an
incidental API-key provider - and never a real one, which would leave core in a later carve -
use `org.llm4s.testutil.FixtureChatProvider` (id `fixturechat`, `FixtureChatConfig`, a canned
no-network client). It lives in core's test sources, is registered by core's **test**
`META-INF/services`, so `ProviderRegistry.default` resolves it in core and in every module
depending on `core % "test->test"`; core's test `application.conf` default is
`fixturechat-main`. Its embedding counterpart is `org.llm4s.testutil.FixtureEmbeddingProvider`
(id `fixtureembedding`, alias, API key, default base URL, env-var names, declared dimensions,
canned vectors), registered the same way; use it wherever a spec needs "some embedding
provider", as core's embedding config, registry and dimension specs do. A spec that builds its own registry passes
it to `ProviderRegistry.of`/`.withProvider`. A provider module's own `Llm4s<Name>ModuleSpec` uses
the published `llm4s-provider-testkit` (`org.llm4s.testkit.ProviderModuleChecks`: discovery, sole
supplier, explicit registration, the config-to-client round trip, `assertRefusesForeignConfig`,
`assertStreams`, `assertCredentialBindings`), so in-repo providers prove themselves exactly as an
external one must; `CredentialsRoundTrip` and `LocalProviderTestServer` live there too. When a stand-in test checked a real provider's own facts in
passing, those move to that provider's spec (`DeepSeekNamedProviderSpec`, now in
`llm4s-openai-compatible`). Strings that do not reach a client
(model-registry data, config-policy allow-lists, secret patterns) stay. `llm4s-rag`'s
`RAGConfig.default` embeds with `openai`, so `rag` has a **test-only** dependency on `openai`;
never make it a compile one - that would put the OpenAI SDK on every RAG user's classpath.

Slice 6 is done. #1233 added the tracing extension point: `Tracing.create` builds `Console`
and `NoOp` itself and dispatches every other mode to an `org.llm4s.trace.spi.TracingBackend`
declared in `META-INF/services/org.llm4s.trace.spi.TracingBackend` (a `class`, never an `object`),
selected by `TracingMode.Named(name)`. `modules/observability` (`llm4s-observability`, no
third-party dependency) then took Langfuse (`LangfuseTracing`, `LangfuseBatchSender`,
`LangfuseTracingBackend`, `LangfuseConfig`, `LangfuseConfigLoader`, `LangfuseConfigKeys`), the
trace collector (`TraceCollectorTracing`, `trace.model`, `trace.store`) and `CostTracker`, and
`OpenTelemetryConfig` went to `modules/trace-opentelemetry` (`llm4s-observability-otel`). **Core
keeps only the tracing contract** - `Tracing`, `TraceEvent`, `TracingComposer`, `TracingMode`
(`Console`, `NoOp`, `Named`), the SPI, `NoOpTracing`, `ConsoleTracing`, `TracingSettings(mode,
extras)` - and **registers no backend and reads no backend's keys**: `TracingConfigLoader` reads
`llm4s.tracing.mode` and hands the selected mode's `llm4s.tracing.<mode>` block to the backend as
`TracingSettings.extras`, which each backend parses itself (`LangfuseConfig.fromExtras`,
`OpenTelemetryConfig.fromExtras`), as provider descriptors read their section. Never add a
`TracingMode` case object, a backend config field on `TracingSettings`, or a backend's
`reference.conf` block back to core; a backend module ships its own block, services entry and
`<Name>TracingBackendSpec` (discovery, explicit registration, a config round trip).
`llm4s-rag` depends on `llm4s-observability` for `RAGASLangfuseObserver`.
`modules/observability-prometheus` (`llm4s-observability-prometheus`) took `PrometheusMetrics`,
`PrometheusEndpoint`, `MetricsConfigLoader` (public now; it replaces the removed
`Llm4sConfig.metrics()`) and the `llm4s.metrics` `reference.conf` block, with the Prometheus client
and HTTP server, so **core declares no observability dependency** and keeps only the
`MetricsCollector` contract. It is separate from `llm4s-observability` so that `rag` does not pass
Prometheus on; `image` has it as a **test-only** dependency, for `ImageGenerationCostTrackingSpec` -
never make it a compile one.

Slice 7 (#1242) carves what the spine audit found left in core. `modules/agent-tools`
(`llm4s-agent-tools`) took `toolapi/builtin`, `toolapi/tools/WeatherTool`, `ToolsConfigLoader`
(public; its no-argument `load*SearchTool()` replaced the removed `Llm4sConfig` methods), the
`*SearchToolConfig` types, `ToolsConfigKeys` and the `llm4s.tools` `reference.conf` block. It
depends on **core only, never on the agent runtime** - the tools serve plain `ToolRegistry` tool
calling too. Core keeps the tool API (`ToolFunction`, `ToolRegistry`, schemas, execution).
`UsageSummary`/`ModelUsage` moved to `org.llm4s.llmconnect.model` so `llm4s-observability` need not
depend on `llm4s-agent`. `modules/agent` (`llm4s-agent`) then took `org.llm4s.agent` (bar
`agent.memory`, already in `llm4s-memory`, which does not depend on it) and `org.llm4s.assistant`,
with fansi. **Nothing in core may import either package** - the tracing contract's
agent event is `TraceEvent.AgentRunEnded` (built from core types; it replaced `AgentStateUpdated`
in #1329), so core's trace specs build it directly.
`workspaceClient` depends on `llm4s-agent` for `codegen`; `observability` only in Test scope.

With slice 7, `llm4s-core` is the spine: 20.7k lines at the re-audit, 19.1k after the cleanup passes (`types`, `error`, `config`, `model`,
`toolapi`, `context`, `llmconnect`, the `trace`/`metrics` contracts, `util`, `http`, `reliability`,
`core/safety`, `security`, `resource`, `syntax`, `identity`), on cats, upickle, slf4j-api, Typesafe
Config, pureconfig and jtokkit only ([re-audit](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540)).
**Nothing is frozen until 0.5.0 is published and the MiMa baseline is set** (slice 8,
[#1281](https://github.com/llm4s/llm4s/issues/1281)). Until then "frozen module" below means a module in
1.0 Scope's *Frozen at 1.0* tier - the ones calling `mimaFrozen` in `build.sbt` - and is a target, not a
constraint: never argue for or against a design from "frozen", "the baseline" or "can be added later
without breaking". Fix a bad API outright, record the break in the CHANGELOG and migration guide, and
add no shim or `@deprecated` overload. The rules below shape what those modules will freeze, which is
why they apply now.
Before slice 8 publishes 0.5.0 and sets the
MiMa baseline, **pre-baseline cleanup passes** (slice 6, done) removed what should not be frozen. Pass 1 removed the unused `org.llm4s.types` vocabulary (it keeps `Result`, its syntax and
the newtypes the library takes), every `@deprecated` member of core and `Agent`, `ContextConfig`'s
legacy field, `ClientStatus`, `StreamingOptions`, `RuntimeId`/`ModelId`, and cats `Show`/`Validated`
on the API. Do not add them back: **a frozen module gains no speculative public types and no
`@deprecated` members before the baseline** - delete instead, with a migration note - and a
helper only llm4s modules use is `private[llm4s]`. **A new top-level public type in a frozen module
needs `@Stable` (or `@Experimental` for a Beta one)** from `org.llm4s.annotation`; `sbt
stabilityTierCheck` fails the build otherwise, and a companion object is covered by its class. Pass 2 removed `ToolRegistry`'s provider switch
(`getToolDefinitionsSafe`; tool definitions are `getOpenAITools()`, the format every client
takes), replaced `CancellationToken`'s exception-throwing members with `whenCancelled:
Future[Unit]`, and moved single-consumer utilities to their consumer, keeping packages:
`SqlIdentifier`, `ChunkingUtils` and `RateLimitedLogger` to `llm4s-rag`, `ManagedResource` to
`llm4s-speech`, `LiftToResult` to `llm4s-observability`. **A utility with one consuming module
lives in that module, not core.** Pass 3 deleted `llmconnect.middleware`, `ReliableProviders`,
`ReliabilitySyntax` and `ReliableClient`'s factories (`new ReliableClient(...)` is the one way in,
and it applies `rateLimit` itself through a private `TokenBucket`); moved OpenAI's o-series and
`max_completion_tokens` rules out of core's `RequestTransformer` into `llm4s-openai`'s
`OpenAIModelRules` - **vendor model rules live in the vendor's module**, layered on with
`RequestTransformer.adjusted`, as Anthropic's temperature rule already did; and settled the
provider plumbing as a **public provider-author SPI, Frozen at 1.0** (`docs/guide/writing-a-provider.md`),
with `ResponseFormatMapper` and `ToolCallDeserializer` moved to `llm4s-openai-compatible` and
`ProviderResultOps` made `private[llm4s]`. Pass 4 moved `NamedProviderConfig`'s vendor fields
into descriptor-declared extras with unchanged HOCON names - `endpoint` (required) and
`apiVersion` to Azure, `organization` to OpenAI, Requesty and OpenRouter, `contextWindow` and
`reserveCompletion` to the generic `openai-compatible` provider - so `BuiltinKeys` is `provider`,
`model`, `baseUrl`, `apiKey`, `headers` and `requiresEndpoint` is gone; and moved
`ProviderModelListers` to `llm4s-openai-compatible` (`sectionHeaders` derives headers such as
`OpenAI-Organization` from a section). **A field only some providers read is that provider's
extra, never a field of `NamedProviderConfig`.** Pass 5 settled the SPI's API quality. **The
growth-prone data types** (`CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`,
`ModelCapabilities`, `ModelMetadata`, `ProviderConfigSpec`, `EmbeddingConfigSpec`,
`ProviderFeatures`, `NamedProviderConfig`, `ReliabilityConfig`, `CircuitBreakerConfig`,
`RateLimitConfig`, `ContextConfig`) are `final case class X private (...)` with a public companion
`apply` carrying the defaults and `with*` setters (an `Option` field's setter takes the value or an
`Option`); `.copy` is private. **To add a field after the baseline**: add it to the constructor and,
with a default, to `apply`; keep the previous `apply` as an overload *without* defaults that
forwards to the new one; add `withX`. Never re-expose `copy`. A type with an upickle `macroRW`
(`ModelCapabilities`) keeps its constructor defaults too - the reader fills missing keys from them.
A new frozen data type that may grow follows the same pattern from the start. Passes 6 and 7 typed
the public API's times: **a duration, supplied or reported, is a `FiniteDuration`, a point in time
an `Instant`** - never a raw `Int`/`Long` with its unit in the name (`timeoutMs`, `durationMs`) or
the Scaladoc, and never a plain `Duration`, which admits `Duration.Inf`. Wire formats keep their
units and keys: convert at the boundary (`.toMillis` into a JSON field; `DurationJson.millisRW` or
`WireDurations` for a `macroRW`, with `@upickle.implicits.key` pinning a renamed field), and round
up with `DurationRounding` when a whole-unit API reads `0` as "no timeout" or "now". A
`FiniteDuration` interpolated as `s"${d}ms"` compiles and prints `150 millisecondsms` - use
`d.toMillis`. Pass 8 kept third-party types out of `llm4s-agent`'s API: the console UI
(`ConsoleInterface`, `ConsoleConfig`, `StyleConfig`, `MessageType`), whose fansi `Attrs` and cats
`Show` would have frozen both libraries into it, and `SessionState.localDateTimeRW`, a public
implicit codec any `import SessionState._` picked up, are `private[assistant]`, and
`AssistantAgent` lost its `consoleConfig` parameter; `SimilarityUtils` is `private[llm4s]`. **A
frozen module's public signatures expose no third-party type** (fansi, cats type classes) and no
implicit a wildcard import would pull in.

`org.llm4s.vectorstore.PostgresVectorHelpers` is the one file in that package still in core:
it is a pure pgvector text codec shared by `llm4s-rag` and `llm4s-memory-postgres`, which must
not depend on each other.

`modules/media` is not a carve - it is a new module, added mid-slice-3 because `image` and
`speech` could not be split apart cleanly without it. Core had grown three overlapping image
format enumerations (`imagegeneration.ImageFormat`, `imageprocessing.ImageFormat`,
`imageprocessing.MediaType`) and RAG matched on raw MIME prefixes; carving first would have
frozen those copies into separate artifacts. `org.llm4s.media` holds the consolidated
vocabulary and nothing else - **no I/O, no content sniffing, no third-party dependencies**.
Keep it that way: the moment it grows a dependency, every consumer inherits it. Tika-based
sniffing stays in `llm4s-rag` and resolves its result through `MediaType.fromMimeType`.
Core's dependency on it is temporary and leaves with the `llm4s-image` carve.

**Key paths in `modules/core/src/main/scala/org/llm4s/`:**
- `types/` - Result type, newtypes
- `config/` - Llm4sConfig + typed loaders
- `llmconnect/` - LLM client and providers
- `toolapi/` - Tool calling API (the built-in tools are in `modules/agent-tools`)
- `trace/` - Tracing contract: `Tracing`, `TraceEvent`, `TracingMode`, the `TracingBackend` SPI,
  Console/NoOp (backends live in `modules/observability` and `modules/trace-opentelemetry`)

The agent runtime (`org.llm4s.agent`, `org.llm4s.assistant`) is in `modules/agent/src/main/scala/org/llm4s/`.

## Common Commands

```bash
sbt buildAll           # Clean, compile, test
sbt test               # Run tests
sbt scalafmtAll        # Format code
sbt cov                # Run coverage
sbt frozenDependencyCheck # no frozen module resolves a parsing, speech, cloud-storage, database, backend or foreign-SDK dependency
sbt testIntegration    # modules/it @Docker tier (Postgres/pgvector, Qdrant, Neo4j)
sbt testWorkspace      # modules/it @Workspace tier (needs a built workspace-runner image)
sbt testOllama         # modules/it @Ollama tier
sbt testSmoke          # modules/it @Cloud tier (live API keys)
sbt it/itTierCheck     # every suite in modules/it must declare exactly one tier
sbt stabilityTierCheck # every top-level public type of a frozen module is @Stable or @Experimental
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

## Commits

**Every commit needs a `Signed-off-by` trailer** - commit with `git commit -s`. This is the
[Developer Certificate of Origin](https://developercertificate.org/): the trailer certifies the
committer has the right to submit the code under the project's MIT licence, so it must name a
real person and cannot be added on someone else's behalf. `.github/workflows/dco.yml` and the DCO
app both check it, and both fail the PR over a single commit that lacks it - including a commit
appended to a branch whose earlier commits have it.

Fixing an unsigned commit rewrites history, so it costs a force-push: `git commit --amend -s`
for the most recent one, `git rebase --signoff main` for a branch of them, then
`git push --force-with-lease`. Signing as you go is cheaper than either. See
[CONTRIBUTING.md](CONTRIBUTING.md#developer-certificate-of-origin-dco).

## Configuration and Environment Variables

**Nothing in the library reads `LLM_MODEL`** (removed with legacy single-provider loading in #903).
Chat providers are named sections in the application's `application.conf`;
`llm4s.providers.provider` names the default that `Llm4sConfig.defaultProvider()` loads.
**Credentials belong to a vendor, keyed by provider id; clients belong to a use.** A client's key
is its own `apiKey` (chat section `llm4s.providers.<name>`, embeddings block
`llm4s.embeddings.<id>`, reranker `llm4s.rerank.cohere`), else `llm4s.credentials.<id>.apiKey`,
which each provider module's own `reference.conf` binds to the vendor's conventional variable,
else an error naming both. So with `OPENAI_API_KEY` set a section needs only `provider` and
`model`:

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

- Precedence: `-D` system properties > `application.conf` > each module's `reference.conf`.
  Environment variables are read only through `${?VAR}`.
- The fallback is generic, by canonical provider id (aliases such as `google` use `gemini`'s),
  inside `org.llm4s.config` (`SharedCredentials`); the reranker's is `RerankerConfigLoader` in
  `llm4s-rag`. A provider module declares the variable(s) it binds as `apiKeyEnv` on its
  `ProviderConfigSpec` / `EmbeddingConfigSpec`, used only in the missing-key message; never
  hard-code a variable name in core. `credentials` holds `apiKey` only. The source path is
  logged at INFO, never the value. No block for `openai-compatible` (no vendor), `ollama` (no
  key) or `vertexai` (OAuth2). A section for a second account sets its own `apiKey`; the
  config-policy `prod` preset flags sections that do not (`ownApiKey`).
- **Only the section being loaded is validated** (`ProviderSections.validated`): a section with
  no key available, or whose provider module is absent, fails only a load of that section.
  `Llm4sConfig.providers()` is the exception - it returns every section, so validates them all;
  `providerConfigs()` reports each section's error separately.
- Samples: `modules/samples/src/main/resources/application.conf` defaults to `ollama-local`
  (model `llama3:latest` - `ollama pull llama3` first, or set `OLLAMA_MODEL`) and
  binds `LLM4S_PROVIDER`, `OLLAMA_MODEL` and `OLLAMA_BASE_URL` - the samples' bindings, not the
  library's. Add other sections in the git-ignored `application.local.conf` beside it.
- Things that read `LLM_MODEL` themselves: the chat-tui sample (`ChatTuiConfig`) and the
  config-policy env check (`EnvCheckPolicies`). The `modules/it` `@Cloud` smoke suites read their
  API keys directly.
- `DocumentedProviderConfigSpec` (`modules/openai`) loads the documented config; keep the docs and
  it in step.

Variables that *are* bound by some module's `reference.conf`:

```bash
# Tracing mode (llm4s-core): console (default), none, or a TracingBackend's mode from its module
TRACING_MODE=langfuse                # langfuse (llm4s-observability), opentelemetry | otel (llm4s-observability-otel)
# Langfuse (llm4s-observability) -> llm4s.tracing.langfuse.*; also LANGFUSE_URL, _ENV, _RELEASE, _VERSION
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
# OpenTelemetry (llm4s-observability-otel) -> llm4s.tracing.opentelemetry.*
OTEL_SERVICE_NAME=llm4s-agent        # OTLP headers: llm4s.tracing.opentelemetry.headers, not OTEL_EXPORTER_OTLP_HEADERS
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Vendor keys -> llm4s.credentials.<id>.apiKey (chat sections, embeddings and the reranker share them)
OPENAI_API_KEY=sk-...                # llm4s-openai (openai); also REQUESTY_API_KEY, AZURE_OPENAI_API_KEY
ANTHROPIC_API_KEY=sk-ant-...         # llm4s-anthropic
GOOGLE_API_KEY=...                   # llm4s-gemini (gemini); GEMINI_API_KEY when GOOGLE_API_KEY is unset
DEEPSEEK_API_KEY=...                 # llm4s-openai-compatible; also ZAI_API_KEY, OPENROUTER_API_KEY, MISTRAL_API_KEY
COHERE_API_KEY=...                   # llm4s-openai-compatible, llm4s-rag and llm4s-cohere (Cohere chat, reranker, embeddings)
VOYAGE_API_KEY=pa-...                # llm4s-voyage

# Embeddings (llm4s-core selects; each provider module binds its own block)
EMBEDDING_MODEL=openai/text-embedding-3-small  # provider/model
# OPENAI_EMBEDDING_BASE_URL / VOYAGE_EMBEDDING_BASE_URL / OLLAMA_EMBEDDING_BASE_URL override base URLs

# Speech (llm4s-speech): cloud TTS/STT, "provider/model" like LLM_MODEL
SPEECH_TTS_MODEL=openai/tts-1                  # or openai/tts-1-hd, elevenlabs/<voice-id>, azure/<voice-name>
SPEECH_STT_MODEL=openai/whisper-1              # or azure/en-US
# SPEECH_TTS_VOICE=alloy                       # optional voice override
# Credentials, only for the selected provider: OPENAI_API_KEY, ELEVENLABS_API_KEY,
# AZURE_SPEECH_KEY + AZURE_SPEECH_REGION (see modules/speech reference.conf, docs/guide/speech.md)
```

The full list is in `docs/getting-started/configuration.md#environment-variables-llm4s-reads`.

## Code Conventions

### Error Handling

```scala
// GOOD - Return Result
def loadProviderConfig(): Result[ProviderConfig] = Llm4sConfig.defaultProvider()

// BAD - Don't throw
def parseConfig(): Config = throw new RuntimeException()

// Convert Try to Result
import org.llm4s.types.TryOps
Try("123".toInt).toResult
```

### Configuration

```scala
// GOOD - the section llm4s.providers.provider names, from application.conf
val provider: Result[ProviderConfig] = Llm4sConfig.defaultProvider()
// or a specific named section:
val named: Result[ProviderConfig] = Llm4sConfig.provider("openai-main")

// BAD - llm4s-openai already binds OPENAI_API_KEY to llm4s.credentials.openai.apiKey
val apiKey = sys.env.get("OPENAI_API_KEY")
```

### Naming

- Types: `PascalCase` (`LLMClient`, `CompletionResponse`)
- Values/functions: `camelCase` (`apiKey`, `createClient`)
- Constants: `SCREAMING_SNAKE_CASE` (`DEFAULT_TIMEOUT`)

### Scalafix Rules

**Banned patterns** (enforced via `.scalafix.conf`):
- `ConfigFactory.load()`, `sys.env()`, `System.getenv()` - use `Llm4sConfig` in app/test code
- `try/catch/finally` outside safety packages - use `Result`
- Infix operators - use `list.map(f)` not `list map f`

## Agent Framework

### Basic Agent Usage

An `Agent` is built once, with its tools, guardrails, handoffs and middleware, and run by `ThreadId`.

```scala
for {
  providerConfig  <- Llm4sConfig.defaultProvider()   // llm4s.providers.<default> in application.conf
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
  agent  <- Agent.builder("assistant", client)       // the id is an AgentId
              .withTools(new ToolRegistry(Seq(myTool)))
              .withSystemPrompt("You are a helpful assistant")
              .build()
  result <- agent.run("Query here")                  // Result[AgentResult]
} yield result.answer                                // Some(answer) when status is Completed
```

`AgentResult` carries `threadId`, `runId`, `activeAgent`, `status`, `messages` and `usage`.
`AgentStatus` is `Completed(answer)`, `Blocked(guardrail, reason)`, `StepLimitReached` or
`Suspended(approvals, questions)`; a guardrail block is an outcome, not a `Left`. Provider, tool and
middleware failures are `Left(GraphError...)`. `agent.start(...)` returns an `AgentRun` with
`await()` and `cancel()`; `recover(threadId)` and `resume(threadId, answers)` continue a thread
that failed or suspended.

### Multi-Turn Conversations

```scala
for {
  result1 <- agent.run("First query")
  result2 <- agent.continueConversation(result1, "Follow-up")   // same thread: run(result1.threadId, ...)
} yield result2
```

To restore a saved conversation, `agent.run(ThreadId("saved-1"), query, RunConfig(), history = saved)`
imports the messages into a new thread (no system messages in `history`).

### Built-in Tools

In `llm4s-agent-tools` (`modules/agent-tools`), which depends on core only - not on the agent
runtime. Search tool config comes from `ToolsConfigLoader.load*SearchTool()`.

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

BuiltinTools.core          // DateTime, Calculator, UUID, JSON
BuiltinTools.safe()        // + web search, HTTP
BuiltinTools.withFiles()   // + read-only file access
BuiltinTools.development() // All tools (use with caution)
```

### Guardrails

Guardrails are middleware on the agent, not per-run arguments.

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._

val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new GuardrailMiddleware(
      input = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
      output = Seq(new JSONValidator())
    )
  )
  .build()
```

A block ends the run `AgentStatus.Blocked(guardrail, reason)`; an output block replaces the stored
answer with a refusal. `new ContextWindowMiddleware(config)` prunes what is sent to the model, never
what the thread stores.

Built-in guardrails:
- **Simple validators**: `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`
- **LLM-as-Judge**: `LLMSafetyGuardrail`, `LLMFactualityGuardrail`, `LLMQualityGuardrail`, `LLMToneGuardrail`
- **Composition**: `CompositeGuardrail.all()`, `CompositeGuardrail.any()`, `CompositeGuardrail.sequence()`

### Handoffs

Handoffs are routes inside one graph, by agent id; the handoff id must equal the target builder's id.

```scala
import org.llm4s.agent.Handoff

val physics = Agent.builder("physics", client).withSystemPrompt("You are a physicist")
val agent = Agent.builder("triage", client)
  .withHandoffs(Handoff.to("physics", physics, "Physics expertise required"))
  .build()
// A cycle back to the root: physics.withHandoffs(Handoff.toId("triage", "Not a physics question"))
```

A handoff must be the only tool call in its message. Use handoffs for simple 2-3 agent
delegation. Use DAGs for complex parallel workflows.

### Memory

```scala
import org.llm4s.agent.memory._

val manager = SimpleMemoryManager.empty
for {
  m1 <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
  context <- m1.getRelevantContext("Tell me about Scala")
} yield context
```

### Reasoning Modes

```scala
val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .withMaxTokens(4096)

client.complete(conversation, options)
```

### Streaming Events

`AgentBuilder.withStreaming()` streams the model's answer; `agent.stream(threadId, query)(listener)`
delivers every event of the turn as a `StreamEvent`, matched with `AgentEvents` (`TextDelta`,
`ToolCallStarted`, `ToolExecuted`, `ModelCallCompleted`, ...). Durable events carry no message
content; content is live-only. `AgentRun.subscribe` is run-scoped; `AgentIO.stream`/`AgentZ.stream`
wrap it as fs2/ZIO streams. Tracing ends each run with `TraceEvent.AgentRunEnded` (#1329).

## Testing

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySpec extends AnyFlatSpec with Matchers {
  "Component" should "return success" in {
    MyComponent.process("valid") shouldBe Right(expected)
  }
}
```

**Best practices:** Deterministic, fast (use mocks), isolated, target 80%+ coverage.

## Adding New Code

### New Sample
1. Create in `modules/samples/src/main/scala/org/llm4s/samples/<category>/`
2. Implement with `extends App`
3. Run with `sbt "samples/runMain org.llm4s.samples.<category>.YourExample"`

### New Provider
Follow invariant 8 above: a `ProviderDescriptor` (or `EmbeddingProviderDescriptor`) in a module
of its own, listed in an `Llm4sProviderModule` declared in `META-INF/services`, with an
`Llm4s<Name>ModuleSpec` proving discovery and the round trip. Nothing goes in core. Where:
- **OpenAI-compatible** (speaks `/chat/completions`): first check the generic
  `openai-compatible` provider covers it with config alone; if not, a dialect and descriptor in
  `modules/openai-compatible` (Mistral and Cohere are the examples).
- **Anything else**: `modules/providers/<name>`, artifact `llm4s-<name>`, depending only on
  core; `modules/providers/voyage` is the template, `modules/ollama` for chat plus embeddings.
Wire it into the root and `docs` aggregates and the docs source list, `samples`, `it`, and
`configPolicy` if it has chat providers; add a coverage floor, a codecov flag and a CI upload.

### New Tool
1. Define function returning `Result[T]`
2. Register with `ToolRegistry`
3. Add tests and sample

## Resources

- [README.md](README.md) - Getting started
- [docs/examples/index.md](docs/examples/index.md) - Agent examples
- [docs/design/agent-framework-roadmap.md](docs/design/agent-framework-roadmap.md) - Agent framework roadmap
- [docs/design/](docs/design/) - Design documents
- Discord: https://discord.gg/4uvTPn6qww
- Issues: https://github.com/llm4s/llm4s/issues
