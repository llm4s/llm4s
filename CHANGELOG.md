# Changelog

All notable changes to llm4s are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

<!-- The net change since 0.4.1, by area. Add an entry to its area's list; a follow-up to unreleased work edits
     its feature's entry instead. Rules: CLAUDE.md, "Reading files and output efficiently". -->

### Highlights

0.5.0 splits llm4s into modules. `llm4s-core` is a provider-free spine; each provider, RAG, memory, MCP, speech, image,
observability, the agent runtime and the built-in tools are separate artifacts, and a provider is registered by adding
its dependency. The agent runtime is rebuilt on a typed graph kernel with durable checkpoints, run claims, human review
and a Deep Agents-style harness (permissions, todos, semantic memory, replay-safe effects). New integrations for Java,
Kotlin, Spring Boot, cats-effect, ZIO and Pekko; new providers (Bedrock, watsonx, Jina, Cohere embeddings, a generic
OpenAI-compatible provider); workload identity, configurable timeouts, testkits and a BOM. Start with [Upgrading from
0.4.1 to 0.5.0](docs/migrations/0-4-1-to-0-5-0.md).

### Breaking changes

- `llm4s-core` ships no provider: add `llm4s-openai`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`,
  `llm4s-openai-compatible` or another provider module;
  [migration](docs/reference/migration.md#slice-5-mistral-cohere-and-voyage-leave-core-core-ships-no-provider).
- RAG, knowledge graph, memory, MCP, media, image and speech are separate artifacts, package names unchanged; migration:
  [RAG](docs/reference/migration.md#slice-1-llm4s-rag-and-llm4s-knowledgegraph),
  [memory](docs/reference/migration.md#slice-2-llm4s-memory-and-llm4s-memory-postgres),
  [MCP](docs/reference/migration.md#slice-3-llm4s-mcp), [image](docs/reference/migration.md#slice-3-llm4s-image),
  [speech](docs/reference/migration.md#slice-3-llm4s-speech).
- The agent runtime and the built-in tools leave core (`llm4s-agent`, `llm4s-agent-tools`), and `UsageSummary` moves to
  `org.llm4s.llmconnect.model`; migration:
  [agent](docs/reference/migration.md#slice-7-llm4s-agent---the-agent-runtime-leaves-core),
  [tools](docs/reference/migration.md#slice-7-llm4s-agent-tools---the-built-in-tools-leave-core),
  [UsageSummary](docs/reference/migration.md#slice-7-usagesummary-and-modelusage-move-to-orgllm4sllmconnectmodel).
- Langfuse, the trace collector and `CostTracker` move to `llm4s-observability`, Prometheus to
  `llm4s-observability-prometheus`; `TracingMode` is open and `TracingSettings` is `(mode, extras)`; migration:
  [Langfuse](docs/reference/migration.md#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core),
  [Prometheus](docs/reference/migration.md#slice-6-llm4s-observability-prometheus---prometheus-leaves-core),
  [tracing](docs/reference/migration.md#slice-6-tracing-backends-are-discovered-and-agent-state-is-a-traceevent).
- llm4s no longer brings a logging backend: add one such as `logback-classic`;
  [migration](docs/reference/migration.md#llm4s-no-longer-brings-a-logging-backend).
- `ProviderKind` is the open `ProviderId`, `ProviderConfig` is unsealed, and `fromValues` returns `Result`; migration:
  [ProviderId](docs/reference/migration.md#slice-4-pr-1-providerkind-becomes-providerid-providerconfig-opens-up),
  [fromValues](docs/reference/migration.md#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).
- `Llm4sConfig`'s provider and embedding methods take an implicit `ProviderRegistry` (binary break; source-compatible);
  [migration](docs/reference/migration.md#slice-4-pr-3-providers-are-discovered-on-the-classpath).
- Vendor keys are `llm4s.credentials.<id>.apiKey`; Azure reads `AZURE_OPENAI_API_KEY`, and the Voyage and Cohere
  reranker bindings moved; [migration](docs/reference/migration.md#vendor-credentials-a-shared-api-key-per-provider).
- `NamedProviderConfig` carries no vendor fields (they are provider extras), and Vertex AI reads `project` and
  `location`; migration: [extras](docs/reference/migration.md#pre-baseline-api-cleanup-pass-4), [Vertex
  AI](docs/reference/migration.md#named-providers-provider-specific-keys-vertex-ai-project-and-location).
- `OpenAIConfig`, `AnthropicConfig` and `OllamaConfig` have private constructors and `with*` setters, and every provider
  config gains a trailing `timeouts` field;
  [migration](docs/reference/migration.md#provider-configs-build-with-apply-change-with-with).
- `AzureToolHelper` is `OpenAIToolHelper`, over openai-java types;
  [migration](docs/reference/migration.md#slice-5-llm4s-openai-moves-to-openai-java).
- The agent API is rewritten: `Agent.builder`, threads by `ThreadId`, `AgentResult`, guardrails as middleware, explicit
  handoff ids; `AgentState` and orchestration are removed;
  [migration](docs/reference/migration.md#stage-1-migration-agent-runtime).
- Pre-baseline API cleanup: dead and deprecated API removed, growth-prone data types lose `.copy`, durations are
  `FiniteDuration`, `Llm4sHttpClient` returns `Result`, `llmconnect.middleware` and `ReliableProviders` are removed;
  [passes 1](docs/reference/migration.md#pre-baseline-api-cleanup-pass-1) to
  [8](docs/reference/migration.md#pre-baseline-api-cleanup-pass-8).
- Image formats are `org.llm4s.media.MediaType`, and image generation errors are `LLMError`s with `Image`-prefixed
  cases; migration: [MediaType](docs/reference/migration.md#slice-3-llm4s-media),
  [errors](docs/reference/migration.md#image-generation-errors-are-llmerrors).
- RAG: one document extractor (`TikaDocumentExtractor`), `FileEmbedder.encodeFromPath`, `PgSearchIndexConfigLoader`, and
  embedding providers named by id; [upgrade guide](docs/migrations/0-4-1-to-0-5-0.md#rag-memory-mcp-speech-and-image),
  [migration](docs/reference/migration.md#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).
- MCP transports and clients return `Result`, and interrupted clients return `CancelledError`;
  [migration](docs/reference/migration.md#cancellation-for-non-chat-clients-and-mcp-tool-hints-1331).
- `AssistantMessage` is a growth-prone type and `Completion.thinking` comes from the message;
  [migration](docs/reference/migration.md#assistantmessage-and-completionthinking-1381).
- Embedding cache keys and `EmbeddingRequest` change shape; [upgrade
  guide](docs/migrations/0-4-1-to-0-5-0.md#7-errors-cancellation-and-types).
- `MemoryManagerConfig` loses `autoRecordMessages` and `consolidationEnabled`, and keyword search matches whole words;
  [upgrade guide](docs/migrations/0-4-1-to-0-5-0.md#rag-memory-mcp-speech-and-image).
- Behaviour: Ollama sends tools, config-policy patterns match the whole value;
  [Ollama](docs/migrations/0-4-1-to-0-5-0.md#ollama-tool-calls),
  [config-policy](docs/migrations/0-4-1-to-0-5-0.md#config-policy-patterns).
- Behaviour: `JSONValidator` checks property types, `Result.traverse` short-circuits; migration:
  [JSONValidator](docs/reference/migration.md#jsonvalidator-checks-property-types-922),
  [traverse](docs/reference/migration.md#resulttraverse-and-recoverwithbackoff-960).
- The workspace runner refuses more commands, and `WorkspaceConfigSupport.loadSandboxConfig` is removed; [upgrade
  guide](docs/migrations/0-4-1-to-0-5-0.md#workspace-runner-commands).
- A hand-built `VertexAIClient` sets `VertexAIConfig.wellKnownCredentialPath` to keep the gcloud fallback;
  [migration](docs/reference/migration.md#vertex-ai-application-default-credentials-1939).
- `AudioFormat` gains `Mp3`, and `RegexValidator`'s primary constructor changed (recompile); migration:
  [Mp3](docs/reference/migration.md#audioformatmp3-1307),
  [RegexValidator](docs/reference/migration.md#regexvalidators-constructor).
- Redaction output changes shape in places (quotes kept around a value, one placeholder per value, more shapes masked);
  [migration](docs/reference/migration.md#redaction-output-1932).

### Core

- **Added** `CancelledError`: an interrupted call returns `Left(CancelledError)` with the interrupt flag kept, never
  retried; chat, embedding, reranker, MCP, image and speech clients follow it
  [#1270](https://github.com/llm4s/llm4s/issues/1270) [#1331](https://github.com/llm4s/llm4s/issues/1331); [design
  §4.4](docs/design/typed-agent-runtime/4.04-stage-0-prototype-ox-cancellation-and-provider-interruption.md),
  [§4.12](docs/design/typed-agent-runtime/4.12-stage-1-slice-5-non-chat-cancellation-and-toolhints.md).
- **Added** `LLMClient.completeStructured[A]` and `extractJson` [#932](https://github.com/llm4s/llm4s/issues/932)
  [#977](https://github.com/llm4s/llm4s/pull/977); [guide](docs/guide/structured-output.md)
  [#1310](https://github.com/llm4s/llm4s/issues/1310).
- **Added** `Completion.citations` for the sources a model cited (OpenAI, Azure, Requesty, OpenRouter)
  [#1216](https://github.com/llm4s/llm4s/issues/1216).
- **Added** reasoning in the conversation: `AssistantMessage.thinking` (`ThinkingBlock`) is kept and replayed where the
  provider takes it, and signed thinking only to the provider and model that produced it
  [#1381](https://github.com/llm4s/llm4s/issues/1381) [#1389](https://github.com/llm4s/llm4s/issues/1389)
  [#1416](https://github.com/llm4s/llm4s/issues/1416) [#1615](https://github.com/llm4s/llm4s/issues/1615)
  [#1622](https://github.com/llm4s/llm4s/issues/1622) [#1384](https://github.com/llm4s/llm4s/pull/1384)
  [#1411](https://github.com/llm4s/llm4s/pull/1411); [guide](docs/guide/providers.md#thinking-in-conversation-history).
- **Added** `@Stable` and `@Experimental` (`org.llm4s.annotation`) state each public type's tier; workload-identity
  types are `@Stable` [#1281](https://github.com/llm4s/llm4s/issues/1281)
  [#1818](https://github.com/llm4s/llm4s/issues/1818); [API
  stability](docs/reference/api-stability.md#tiers-in-the-code).
- **Changed** `LLMError.isRecoverable` is total, and `RetryPolicy`, `LLMClientRetry`, `ErrorRecovery` and graph retries
  share one rule [#1380](https://github.com/llm4s/llm4s/issues/1380)
  [#1316](https://github.com/llm4s/llm4s/issues/1316).
- **Changed** every client sends through `Llm4sHttpClient`, and a 429's or 503's `Retry-After` sets the retry delay
  (`ServiceError.retryAfter`) [#1133](https://github.com/llm4s/llm4s/issues/1133).
- **Changed** `Result.traverse` short-circuits, and `ErrorRecovery.recoverWithBackoff` returns an error it does not
  retry unchanged [#960](https://github.com/llm4s/llm4s/issues/960); [guide](docs/guide/error-handling.md).
- **Changed** `DefaultErrorMapper` reads 401 and 429 only in an HTTP context and otherwise keeps the cause
  [#1668](https://github.com/llm4s/llm4s/issues/1668).
- **Changed** (breaking) embedding cache keys are unambiguous (`CacheKeyGenerator`, `CachedEmbeddingClient` keyed by
  `InputPurpose`), and `CachingLLMClient` refuses a NaN threshold [#1297](https://github.com/llm4s/llm4s/issues/1297);
  [guide](docs/guide/caching.md).
- **Removed** dead, deprecated and accidental public API before the baseline (passes 1 to 8)
  [#1133](https://github.com/llm4s/llm4s/issues/1133);
  [migration](docs/reference/migration.md#pre-baseline-api-cleanup-pass-1).
- **Fixed** (security) `Redaction` hides credentials in many more shapes: compound and plural keys, arrays and objects,
  escaped and Python-repr quoting, JSON-escaped query strings, URL userinfo, cookie and proxy headers; a provider error
  body is redacted before it is truncated, in linear time [#1518](https://github.com/llm4s/llm4s/issues/1518)
  [#1576](https://github.com/llm4s/llm4s/issues/1576) [#1646](https://github.com/llm4s/llm4s/issues/1646)
  [#1647](https://github.com/llm4s/llm4s/issues/1647) [#1654](https://github.com/llm4s/llm4s/issues/1654)
  [#1655](https://github.com/llm4s/llm4s/issues/1655) [#1657](https://github.com/llm4s/llm4s/issues/1657)
  [#1667](https://github.com/llm4s/llm4s/issues/1667) [#1672](https://github.com/llm4s/llm4s/issues/1672)
  [#1674](https://github.com/llm4s/llm4s/issues/1674) [#1675](https://github.com/llm4s/llm4s/issues/1675)
  [#1676](https://github.com/llm4s/llm4s/issues/1676) [#1677](https://github.com/llm4s/llm4s/issues/1677)
  [#1680](https://github.com/llm4s/llm4s/issues/1680) [#1684](https://github.com/llm4s/llm4s/issues/1684)
  [#1686](https://github.com/llm4s/llm4s/issues/1686) [#1687](https://github.com/llm4s/llm4s/issues/1687)
  [#1695](https://github.com/llm4s/llm4s/issues/1695) [#1697](https://github.com/llm4s/llm4s/issues/1697)
  [#1706](https://github.com/llm4s/llm4s/issues/1706) [#1707](https://github.com/llm4s/llm4s/issues/1707)
  [#1765](https://github.com/llm4s/llm4s/issues/1765) [#1800](https://github.com/llm4s/llm4s/issues/1800)
  [#1813](https://github.com/llm4s/llm4s/issues/1813) [#1816](https://github.com/llm4s/llm4s/issues/1816)
  [#1818](https://github.com/llm4s/llm4s/issues/1818) [#1841](https://github.com/llm4s/llm4s/issues/1841)
  [#1932](https://github.com/llm4s/llm4s/issues/1932); since #1932 one linear pass with no regex, failing closed past 16
  string encodings; [migration](docs/reference/migration.md#redaction-output-1932).
- **Fixed** JSON a model, tool or provider sent is refused beyond 512 levels of nesting instead of overflowing the stack
  [#1562](https://github.com/llm4s/llm4s/issues/1562) [#1630](https://github.com/llm4s/llm4s/issues/1630)
  [#1643](https://github.com/llm4s/llm4s/issues/1643) [#1644](https://github.com/llm4s/llm4s/issues/1644)
  [#1648](https://github.com/llm4s/llm4s/issues/1648) [#1651](https://github.com/llm4s/llm4s/issues/1651)
  [#1658](https://github.com/llm4s/llm4s/issues/1658).
- **Fixed** `RegexSafetyManager` returns an error instead of letting `StackOverflowError` escape
  [#1379](https://github.com/llm4s/llm4s/issues/1379).
- **Fixed** `ToolRegistry` keeps a timeout that fires before the tool starts, and async retries no longer park a pool
  thread [#1139](https://github.com/llm4s/llm4s/issues/1139) [#1194](https://github.com/llm4s/llm4s/pull/1194)
  [#1066](https://github.com/llm4s/llm4s/issues/1066) [#1197](https://github.com/llm4s/llm4s/issues/1197).
- **Fixed** `SafeParameterExtractor` integers reject fractions and overflow
  [#964](https://github.com/llm4s/llm4s/issues/964); `ProcessingError.context` names a message-less cause's class
  [#1556](https://github.com/llm4s/llm4s/issues/1556).
- **Fixed** `ReliableClient` applies `ReliabilityConfig.rateLimit` [#1133](https://github.com/llm4s/llm4s/issues/1133).
- **Fixed** `TokenizerMapping` reports Azure-prefixed Claude and legacy `gpt-3` models correctly
  [#1557](https://github.com/llm4s/llm4s/issues/1557) [#1558](https://github.com/llm4s/llm4s/issues/1558)
  [#1572](https://github.com/llm4s/llm4s/issues/1572).

### Configuration

- **Added** vendor credentials `llm4s.credentials.<id>.apiKey`, bound by each provider module's `reference.conf`, so a
  section needs only `provider` and `model`; the config-policy rule `ownApiKey`
  [#1132](https://github.com/llm4s/llm4s/issues/1132) [#1126](https://github.com/llm4s/llm4s/issues/1126);
  [migration](docs/reference/migration.md#vendor-credentials-a-shared-api-key-per-provider).
- **Added** provider-specific keys (`ProviderConfigSpec.extras`) with validation, defaults and deprecated aliases;
  unknown keys are reported [#1215](https://github.com/llm4s/llm4s/issues/1215)
  [#1131](https://github.com/llm4s/llm4s/issues/1131).
- **Added** `timeouts { request, stream }` in a provider or embedding section
  [#712](https://github.com/llm4s/llm4s/issues/712); [configuration](docs/getting-started/configuration.md#timeouts).
- **Added** workload identity (SPIFFE): an `auth` block for `openai`, `anthropic` and `openai-compatible` (RFC 8693
  exchange) instead of an API key [#1354](https://github.com/llm4s/llm4s/issues/1354)
  [#1357](https://github.com/llm4s/llm4s/issues/1357) [#1818](https://github.com/llm4s/llm4s/issues/1818).
- **Added** `DoctorCli`, a setup checker in `llm4s-config-policy` [#1481](https://github.com/llm4s/llm4s/issues/1481);
  [Doctor](docs/reference/doctor.md).
- **Changed** config-policy has per-provider pins and caps, and patterns match the whole value
  [#1220](https://github.com/llm4s/llm4s/issues/1220); [upgrade
  guide](docs/migrations/0-4-1-to-0-5-0.md#config-policy-patterns).
- **Fixed** one bad provider section no longer breaks every provider: only the section loaded is validated
  [#1132](https://github.com/llm4s/llm4s/issues/1132).
- **Fixed** config errors name the real `application.conf` path and key, and warnings are logged once
  [#1132](https://github.com/llm4s/llm4s/issues/1132) [#903](https://github.com/llm4s/llm4s/pull/903).

### Providers

- **Added** the provider SPI: `ProviderDescriptor` and `Llm4sProviderModule`, discovered from `META-INF/services`, with
  `ProviderRegistry` for explicit registration [#1131](https://github.com/llm4s/llm4s/issues/1131);
  [guide](docs/guide/writing-a-provider.md).
- **Added** `llm4s-openai-compatible`: one SDK-free client for DeepSeek, Z.ai, OpenRouter, Mistral and Cohere chat, and
  a generic `openai-compatible` provider configured by `baseUrl` and `model`, whose context window comes from the model
  registry [#1132](https://github.com/llm4s/llm4s/issues/1132) [#925](https://github.com/llm4s/llm4s/issues/925)
  [#1217](https://github.com/llm4s/llm4s/issues/1217);
  [migration](docs/reference/migration.md#slice-5-llm4s-openai-compatible).
- **Added** `llm4s-bedrock`: AWS Bedrock over Converse and ConverseStream
  [#1008](https://github.com/llm4s/llm4s/issues/1008) [#1029](https://github.com/llm4s/llm4s/issues/1029).
- **Added** `llm4s-watsonx` (Beta): IBM watsonx.ai on the chat endpoints, with tool calling; not yet run against the
  live service [#1019](https://github.com/llm4s/llm4s/issues/1019) [#1314](https://github.com/llm4s/llm4s/issues/1314).
- **Added** `llm4s-jev` (Beta): a typed client for TypeSafe's Jev decision model, not an `LLMClient`
  [#1265](https://github.com/llm4s/llm4s/issues/1265); [guide](docs/guide/jev.md).
- **Added** Ollama tool calling in the native client, and `responseFormat` as Ollama's `format`
  [#1219](https://github.com/llm4s/llm4s/issues/1219) [#932](https://github.com/llm4s/llm4s/issues/932).
- **Changed** each provider is a module: `llm4s-openai` (OpenAI, Azure, Requesty, now on OpenAI's `openai-java` SDK),
  `llm4s-anthropic`, `llm4s-gemini` (Gemini, Vertex AI) and `llm4s-ollama`
  [#1132](https://github.com/llm4s/llm4s/issues/1132);
  [migration](docs/reference/migration.md#slice-5-llm4s-openai-moves-to-openai-java).
- **Changed** (breaking) `OpenAIConfig`, `AnthropicConfig` and `OllamaConfig` are built with `apply` and changed with
  `with*` [#1388](https://github.com/llm4s/llm4s/issues/1388) [#712](https://github.com/llm4s/llm4s/issues/712);
  [migration](docs/reference/migration.md#provider-configs-build-with-apply-change-with-with).
- **Changed** OpenAI sends `reasoning_effort` to reasoning models, and OpenAI-compatible streams ask for token usage
  [#1132](https://github.com/llm4s/llm4s/issues/1132) [#1209](https://github.com/llm4s/llm4s/issues/1209);
  [guide](docs/guide/providers.md#reasoning-models).
- **Changed** Anthropic sends tool calls and results as `tool_use` and `tool_result` blocks
  [#1381](https://github.com/llm4s/llm4s/issues/1381).
- **Changed** Mistral and Cohere stream, call tools and take structured output
  [#925](https://github.com/llm4s/llm4s/issues/925).
- **Fixed** streamed tool calls kept only their first argument fragment (OpenAI, DeepSeek, Z.ai, OpenRouter) or came
  back out of order; fragments reach `onChunk` verbatim [#1132](https://github.com/llm4s/llm4s/issues/1132)
  [#1212](https://github.com/llm4s/llm4s/issues/1212).
- **Fixed** Anthropic extended thinking failed with the default temperature
  [#1212](https://github.com/llm4s/llm4s/issues/1212).
- **Fixed** Z.ai honours `CompletionOptions.reasoning` [#1681](https://github.com/llm4s/llm4s/issues/1681).
- **Fixed** DeepSeek, Z.ai and OpenRouter `complete` could wait forever; requests now time out
  [#912](https://github.com/llm4s/llm4s/issues/912).
- **Fixed** a Requesty config reported `providerId = openai`, and Azure and Requesty errors and metrics were labelled
  `openai` [#1132](https://github.com/llm4s/llm4s/issues/1132).
- **Fixed** `provider = "vertexai"` failed validation; Vertex AI token refresh is serialised
  [#1131](https://github.com/llm4s/llm4s/issues/1131) [#1191](https://github.com/llm4s/llm4s/pull/1191).
- **Fixed** Vertex AI finds gcloud's Application Default Credentials on Windows and under `CLOUDSDK_CONFIG`
  [#1939](https://github.com/llm4s/llm4s/issues/1939);
  [migration](docs/reference/migration.md#vertex-ai-application-default-credentials-1939).
- **Fixed** a deep or malformed Anthropic, Gemini or Ollama model listing is a `Left`, not an exception
  [#1660](https://github.com/llm4s/llm4s/issues/1660).

### Embeddings & RAG

- **Added** embedding providers join the SPI (`EmbeddingProviderDescriptor`) and bring their own config and model
  dimensions [#1131](https://github.com/llm4s/llm4s/issues/1131);
  [migration](docs/reference/migration.md#slice-4-pr-4-embedding-providers-join-the-spi).
- **Added** `llm4s-voyage`, `llm4s-jina` and `llm4s-cohere` embedding providers
  [#1132](https://github.com/llm4s/llm4s/issues/1132) [#1028](https://github.com/llm4s/llm4s/issues/1028)
  [#1060](https://github.com/llm4s/llm4s/issues/1060) [#1068](https://github.com/llm4s/llm4s/issues/1068).
- **Added** `EmbeddingRequest.purpose` (`InputPurpose.Query` or `Document`), sent by Voyage, Jina and Cohere; RAG and
  memory embed searches as queries [#1218](https://github.com/llm4s/llm4s/issues/1218).
- **Added** `RerankerConfigLoader` in `llm4s-rag`, the first reader of `llm4s.rerank`
  [#1132](https://github.com/llm4s/llm4s/issues/1132).
- **Changed** RAG, vector stores, chunking, reranking and evaluation are `llm4s-rag`, and the knowledge graph is
  `llm4s-knowledgegraph` [#1128](https://github.com/llm4s/llm4s/issues/1128);
  [migration](docs/reference/migration.md#slice-1-llm4s-rag-and-llm4s-knowledgegraph).
- **Changed** `RAGConfig` names its embedding provider by id, and `RAGASFactory` no longer guesses dimensions
  [#1131](https://github.com/llm4s/llm4s/issues/1131).
- **Changed** invalid `ChunkingConfig` and `WeightedScore` behaviour is documented and property-tested
  [#1318](https://github.com/llm4s/llm4s/issues/1318) [#1358](https://github.com/llm4s/llm4s/issues/1358);
  [guide](docs/guide/vector-store.md).
- **Removed** the duplicate extractors: `TikaDocumentExtractor` and `MediaExtractor` replace `DefaultDocumentExtractor`
  and `UniversalExtractor`; `EmbeddingClient.encodePath` is `FileEmbedder.encodeFromPath`.
- **Fixed** re-ingesting a document replaces its chunks, and deleting `doc-1` no longer deletes `doc-10`
  [#1318](https://github.com/llm4s/llm4s/issues/1318) [#1000](https://github.com/llm4s/llm4s/issues/1000)
  [#1036](https://github.com/llm4s/llm4s/pull/1036).
- **Fixed** a failed read or S3 listing no longer empties or deletes the index, and `RAG.build` works on a
  `PgSearchIndex` table [#1231](https://github.com/llm4s/llm4s/pull/1231)
  [#1236](https://github.com/llm4s/llm4s/pull/1236);
  [migration](docs/reference/migration.md#a-failed-read-no-longer-deletes-or-clears-indexed-documents).
- **Fixed** `SentenceChunker` dropped text at every sentence boundary (re-chunk what it indexed), and no chunker splits
  a surrogate pair or overflows on a large window [#1718](https://github.com/llm4s/llm4s/issues/1718)
  [#1725](https://github.com/llm4s/llm4s/issues/1725) [#1711](https://github.com/llm4s/llm4s/issues/1711)
  [#1424](https://github.com/llm4s/llm4s/pull/1424).
- **Fixed** OpenAI [#1413](https://github.com/llm4s/llm4s/pull/1413) and Voyage embeddings posted to
  `/v1/v1/embeddings`; `EMBEDDING_MODEL=ollama/nomic-embed-text` failed to load, and `voyage-3-large` had the wrong
  dimensions.
- **Fixed** `GraphJsonParser` returns a `Left` for a reply that is not an object and logs only a preview
  [#1527](https://github.com/llm4s/llm4s/issues/1527) [#1635](https://github.com/llm4s/llm4s/issues/1635).

### Agent runtime

- **Added** a typed graph kernel (`org.llm4s.agent.graph`): typed state and routes, joins, supersteps on virtual
  threads, checkpoints with commit-gated replay, `GraphRuntime` runs with budgets, interrupts and resume, per-node retry
  and cache, Mermaid export [#1266](https://github.com/llm4s/llm4s/issues/1266)
  [#1267](https://github.com/llm4s/llm4s/issues/1267) [#1268](https://github.com/llm4s/llm4s/issues/1268)
  [#1269](https://github.com/llm4s/llm4s/issues/1269) [#1270](https://github.com/llm4s/llm4s/issues/1270)
  [#1277](https://github.com/llm4s/llm4s/issues/1277) [#1327](https://github.com/llm4s/llm4s/issues/1327); [design
  §4.2](docs/design/typed-agent-runtime/4.02-stage-0-prototype-typed-state-routing-and-joins.md) to
  [§4.11](docs/design/typed-agent-runtime/4.11-stage-1-slice-1-kernel-completion.md).
- **Added** typed agent tools (`AgentTool`, `AgentToolSpec`, `ToolHints`) and `AgentMiddleware` (`ApprovalMiddleware`,
  `GuardrailMiddleware`, `ContextWindowMiddleware`) [#1278](https://github.com/llm4s/llm4s/issues/1278)
  [#1279](https://github.com/llm4s/llm4s/issues/1279); [design
  §4.7](docs/design/typed-agent-runtime/4.07-stage-0-prototype-agent-tool-contract.md),
  [§4.8](docs/design/typed-agent-runtime/4.08-stage-0-prototype-agent-middleware.md).
- **Added** agent event streams: `AgentEvents`, `Agent.stream`, `AgentRun.subscribe`, and run-end tracing
  (`TraceEvent.AgentRunEnded`) [#1329](https://github.com/llm4s/llm4s/issues/1329)
  [#1378](https://github.com/llm4s/llm4s/issues/1378) [#1387](https://github.com/llm4s/llm4s/issues/1387)
  [#1731](https://github.com/llm4s/llm4s/issues/1731); [design
  §4.14](docs/design/typed-agent-runtime/4.14-stage-1-slice-3-events-and-tracing.md),
  [guide](docs/guide/agents/streaming.md).
- **Added** durable runs: run claims with fencing tokens and tenant isolation, and `CheckpointerContract` in the new
  `llm4s-agent-testkit` [#1699](https://github.com/llm4s/llm4s/issues/1699)
  [#1700](https://github.com/llm4s/llm4s/issues/1700) [#1918](https://github.com/llm4s/llm4s/issues/1918)
  [#1926](https://github.com/llm4s/llm4s/issues/1926); [design
  §4.16](docs/design/typed-agent-runtime/4.16-stage-2-slice-1-the-durable-checkpointer-contract.md),
  [guide](docs/guide/agents/durable-checkpointers.md).
- **Added** `llm4s-agent-checkpoint-sqlite`: `SqliteCheckpointer`, so a run survives a restart and `recover` continues
  it [#1701](https://github.com/llm4s/llm4s/issues/1701) [#1951](https://github.com/llm4s/llm4s/issues/1951); [design
  §4.20](docs/design/typed-agent-runtime/4.20-stage-2-slice-2-the-sqlite-checkpointer.md).
- **Added** checkpoint history, `fork`, `updateState` and retention (`prune`)
  [#1702](https://github.com/llm4s/llm4s/issues/1702); [design
  §4.19](docs/design/typed-agent-runtime/4.19-stage-2-slice-3-checkpoint-history-fork-and-retention.md).
- **Added** tool idempotency keys, and one result per tool call in every model request
  [#1703](https://github.com/llm4s/llm4s/issues/1703) [#1740](https://github.com/llm4s/llm4s/issues/1740); [design
  §4.17](docs/design/typed-agent-runtime/4.17-stage-2-slice-4-tool-side-effect-safety.md).
- **Added** human review: breakpoints, typed middleware questions and `GuardrailReviewMiddleware`
  [#1704](https://github.com/llm4s/llm4s/issues/1704); [design
  §4.18](docs/design/typed-agent-runtime/4.18-stage-2-slice-5-human-review-interrupts.md),
  [guide](docs/guide/agents/human-review.md).
- **Added** live subscriptions across runtimes and processes sharing a checkpointer
  [#1705](https://github.com/llm4s/llm4s/issues/1705) [#1737](https://github.com/llm4s/llm4s/issues/1737); [design
  §4.21](docs/design/typed-agent-runtime/4.21-stage-2-slice-6-live-subscriptions-across-runtimes.md).
- **Changed** (breaking) `Agent` runs on the graph runtime: built once with `Agent.builder`, run by `ThreadId`,
  guardrails as middleware, a Block leaves the thread usable, interrupting a call cancels its turn
  [#1326](https://github.com/llm4s/llm4s/issues/1326) [#1328](https://github.com/llm4s/llm4s/issues/1328)
  [#1322](https://github.com/llm4s/llm4s/pull/1322) [#1330](https://github.com/llm4s/llm4s/issues/1330)
  [#1682](https://github.com/llm4s/llm4s/issues/1682) [#1688](https://github.com/llm4s/llm4s/issues/1688); [design
  §4.13](docs/design/typed-agent-runtime/4.13-stage-1-slice-2-the-agent-loop-on-the-graph-runtime.md),
  [migration](docs/reference/migration.md#stage-1-migration-agent-runtime).
- **Changed** handoffs have explicit ids (`Handoff.to(id, builder, reason)`)
  [#1278](https://github.com/llm4s/llm4s/issues/1278);
  [migration](docs/reference/migration.md#agent-tool-contract-and-handoff-ids).
- **Changed** `JSONValidator` enforces `properties.<field>.type` [#922](https://github.com/llm4s/llm4s/issues/922)
  [#923](https://github.com/llm4s/llm4s/issues/923).
- **Removed** orchestration (`PlanRunner`, `DAG`, `TypedAgent`, `CancellationToken`): use `GraphBuilder`
  [#1330](https://github.com/llm4s/llm4s/issues/1330); [design
  §4.15](docs/design/typed-agent-runtime/4.15-stage-1-slice-4-orchestration-removed.md),
  [migration](docs/reference/migration.md#orchestration-removed-1330).
- **Fixed** LLM-judge guardrails refuse a reply that is not one number from 0 to 1 and an out-of-range threshold
  [#1405](https://github.com/llm4s/llm4s/issues/1405) [#1520](https://github.com/llm4s/llm4s/issues/1520).
- **Fixed** `PIIMasker` masks international phones and 15-digit cards, its email pattern runs in linear time, and
  `PIIDetector` warns in `Warn` mode [#1517](https://github.com/llm4s/llm4s/issues/1517)
  [#1568](https://github.com/llm4s/llm4s/issues/1568) [#1713](https://github.com/llm4s/llm4s/issues/1713)
  [#1094](https://github.com/llm4s/llm4s/issues/1094).
- **Fixed** guardrails: `Warn` logs in five more, case folding ignores the JVM locale, `ToneValidator` reads multi-line
  text, `CompositeGuardrail.any` stops at the first pass [#1315](https://github.com/llm4s/llm4s/issues/1315).

### Agent harness (Phase 3)

- **Added** kernel hooks: middleware reads a `ModelContext` and writes declared state, adds prompt sections and counts
  its own model calls; an output Block restores the whole turn [#1902](https://github.com/llm4s/llm4s/issues/1902);
  [design §4.22](docs/design/typed-agent-runtime/4.22-stage-3-slice-c1-kernel-hooks.md),
  [migration](docs/reference/migration.md#kernel-hooks-for-the-harness-1902).
- **Added** tool permissions (`PermissionMiddleware`), per-tool timeouts and batch locks
  [#1903](https://github.com/llm4s/llm4s/issues/1903); [design
  §4.23](docs/design/typed-agent-runtime/4.23-stage-3-slice-c2-tool-permissions-timeouts-locks.md),
  [guide](docs/guide/agents/index.md#tool-permissions-timeouts-and-locks).
- **Added** replay of external effects: `ReplayGuard`, `EffectKey` and `ReplayContract`
  [#1921](https://github.com/llm4s/llm4s/issues/1921); [design
  §4.24](docs/design/typed-agent-runtime/4.24-stage-3-slice-c4-replay-of-external-effects.md).
- **Added** an agent's answer can have a response format: `AgentBuilder.withOutput(schema)` and
  `AgentResult.decodeOutput` [#1912](https://github.com/llm4s/llm4s/issues/1912); [design
  §4.26](docs/design/typed-agent-runtime/4.26-stage-3-slice-f8a-response-format.md).
- **Added** typed custom streams: `EventHandlers`, and `events(run, ...)` on `AgentIO`, `AgentZ` and `AgentPekko`
  [#1914](https://github.com/llm4s/llm4s/issues/1914); [design
  §4.28](docs/design/typed-agent-runtime/4.28-stage-3-slice-f9-typed-custom-streams.md).
- **Added** a checkpoint restores after its graph changes, and a completed thread into a new graph version
  [#1934](https://github.com/llm4s/llm4s/issues/1934); [design
  §4.29](docs/design/typed-agent-runtime/4.29-stage-3-slice-c5-graph-evolution.md).
- **Added** todos: `TodoMiddleware`, a `write_todos` tool and a plan prompt section
  [#1907](https://github.com/llm4s/llm4s/issues/1907); [design
  §4.30](docs/design/typed-agent-runtime/4.30-stage-3-slice-f3-todos.md), [guide](docs/guide/agents/index.md#todos).
- **Added** semantic memory: `llm4s-agent-memory` and `SemanticMemoryMiddleware`
  [#1911](https://github.com/llm4s/llm4s/issues/1911); [design
  §4.31](docs/design/typed-agent-runtime/4.31-stage-3-slice-f7-semantic-memory.md),
  [guide](docs/guide/agents/memory.md#semantic-memory-middleware).

### Memory

- **Added** `TransactionalMemoryStore` (`InMemoryStore`, SQLite, Postgres) and `MemoryStore.replace`
  [#1911](https://github.com/llm4s/llm4s/issues/1911).
- **Changed** agent memory is `llm4s-memory`, and its Postgres store `llm4s-memory-postgres`
  [#1129](https://github.com/llm4s/llm4s/issues/1129);
  [migration](docs/reference/migration.md#slice-2-llm4s-memory-and-llm4s-memory-postgres).
- **Changed** `contextTokenBudget` and `autoExtractEntities` take effect; `autoRecordMessages` and
  `consolidationEnabled` are removed [#1579](https://github.com/llm4s/llm4s/issues/1579).
- **Changed** keyword search matches whole words, not substrings [#1594](https://github.com/llm4s/llm4s/issues/1594).
- **Fixed** `MemoryStore.storeAll` is all or nothing, and the SQL stores answer every `MemoryFilter` as `InMemoryStore`
  does [#1320](https://github.com/llm4s/llm4s/issues/1320).
- **Fixed** `getRelevantContext` stays within `maxTokens` and writes no empty heading
  [#1580](https://github.com/llm4s/llm4s/issues/1580).
- **Fixed** `VectorMemoryStore` keeps metadata with quotes, closes a failed connection and waits for concurrent writers
  [#1002](https://github.com/llm4s/llm4s/issues/1002) [#1306](https://github.com/llm4s/llm4s/pull/1306).

### Tools & workspace

- **Changed** the built-in tools (`llm4s-agent-tools`) depend on core only; search tool config is `ToolsConfigLoader`
  [#1242](https://github.com/llm4s/llm4s/issues/1242); [guide](docs/guide/builtin-tools.md)
  [#1296](https://github.com/llm4s/llm4s/issues/1296).
- **Removed** `WorkspaceConfigSupport.loadSandboxConfig`, which enforced nothing: the runner reads
  `WORKSPACE_SANDBOX_PROFILE` [#1730](https://github.com/llm4s/llm4s/issues/1730).
- **Fixed** (security) built-in tools: file paths are judged by where they really are, shell commands get a scrubbed
  environment and checked option values, `readOnly` no longer allows `env`, and the HTTP tool forwards only safe headers
  across origins, blocks IPv6 private ranges and stops at its byte cap
  [#1408](https://github.com/llm4s/llm4s/issues/1408) [#1296](https://github.com/llm4s/llm4s/issues/1296)
  [#872](https://github.com/llm4s/llm4s/pull/872) [#897](https://github.com/llm4s/llm4s/pull/897)
  [#1723](https://github.com/llm4s/llm4s/issues/1723) [#1734](https://github.com/llm4s/llm4s/issues/1734)
  [#1766](https://github.com/llm4s/llm4s/issues/1766) [#1807](https://github.com/llm4s/llm4s/issues/1807).
- **Fixed** (security) the workspace runner's command policy checks every argument, runs WebSocket commands without a
  shell, ignores planted git config, starts programs by trusted path and refuses interpreters as extra commands
  [#1715](https://github.com/llm4s/llm4s/issues/1715) [#1716](https://github.com/llm4s/llm4s/issues/1716)
  [#1720](https://github.com/llm4s/llm4s/issues/1720) [#1721](https://github.com/llm4s/llm4s/issues/1721)
  [#1728](https://github.com/llm4s/llm4s/issues/1728) [#1730](https://github.com/llm4s/llm4s/issues/1730)
  [#1738](https://github.com/llm4s/llm4s/issues/1738) [#1756](https://github.com/llm4s/llm4s/issues/1756)
  [#1761](https://github.com/llm4s/llm4s/issues/1761) [#1763](https://github.com/llm4s/llm4s/issues/1763)
  [#1776](https://github.com/llm4s/llm4s/issues/1776) [#1790](https://github.com/llm4s/llm4s/issues/1790)
  [#1792](https://github.com/llm4s/llm4s/issues/1792) [#1805](https://github.com/llm4s/llm4s/issues/1805);
  [sandbox](docs/reference/workspace-sandbox.md).
- **Fixed** tool edge cases: the calculator rejects non-finite results and is locale-independent, `get_current_datetime`
  and `json_tool` check their input, file tools return an error for an unknown encoding
  [#1516](https://github.com/llm4s/llm4s/issues/1516) [#1511](https://github.com/llm4s/llm4s/issues/1511)
  [#1512](https://github.com/llm4s/llm4s/issues/1512) [#1513](https://github.com/llm4s/llm4s/issues/1513)
  [#1508](https://github.com/llm4s/llm4s/issues/1508) [#1509](https://github.com/llm4s/llm4s/issues/1509)
  [#1510](https://github.com/llm4s/llm4s/issues/1510) [#1710](https://github.com/llm4s/llm4s/issues/1710).
- **Fixed** the `workspace-runner` image builds again [#1311](https://github.com/llm4s/llm4s/pull/1311).

### MCP

- **Added** `ToolHints` read from MCP tool annotations, trusted only with `trustAnnotations = true`
  [#1331](https://github.com/llm4s/llm4s/issues/1331); [design
  §4.12](docs/design/typed-agent-runtime/4.12-stage-1-slice-5-non-chat-cancellation-and-toolhints.md).
- **Changed** `org.llm4s.mcp` is `llm4s-mcp` [#1130](https://github.com/llm4s/llm4s/issues/1130);
  [migration](docs/reference/migration.md#slice-3-llm4s-mcp).
- **Changed** `MCPTransport` and `MCPClient` return `Result`, and an interrupted call returns `CancelledError`
  [#1331](https://github.com/llm4s/llm4s/issues/1331).
- **Fixed** a tool result flagged `isError` is a failure, text results stay text, and a failed listing is a `Left`
  [#1006](https://github.com/llm4s/llm4s/issues/1006) [#1041](https://github.com/llm4s/llm4s/pull/1041)
  [#1319](https://github.com/llm4s/llm4s/issues/1319).
- **Fixed** Streamable HTTP notifications send the required `Accept` header, and `timeout` bounds an SSE reply
  [#1006](https://github.com/llm4s/llm4s/issues/1006) [#1785](https://github.com/llm4s/llm4s/issues/1785).
- **Fixed** the stdio transport drains the server's stderr while a request is in flight
  [#1952](https://github.com/llm4s/llm4s/issues/1952).
- **Fixed** a dead server on a port containing `404` no longer falls back to HTTP+SSE.

### Speech & image

- **Added** cloud speech: OpenAI, ElevenLabs and Azure text-to-speech, OpenAI and Azure speech-to-text, and opt-in MP3
  output [#1010](https://github.com/llm4s/llm4s/issues/1010) [#1307](https://github.com/llm4s/llm4s/issues/1307);
  [guide](docs/guide/speech.md#cloud-providers).
- **Added** `GeminiVisionClient` (Experimental) [#1005](https://github.com/llm4s/llm4s/issues/1005).
- **Added** `llm4s-media`: `MediaType` and `MediaCategory`, the shared multimodal vocabulary
  [#1130](https://github.com/llm4s/llm4s/issues/1130).
- **Changed** `llm4s-image` and `llm4s-speech` leave core, and Vosk and JNA with them
  [#1130](https://github.com/llm4s/llm4s/issues/1130); [image](docs/reference/migration.md#slice-3-llm4s-image),
  [speech](docs/reference/migration.md#slice-3-llm4s-speech).
- **Fixed** `AudioPreprocessing.resamplePcm16` could hang and returned the wrong length
  [#1308](https://github.com/llm4s/llm4s/issues/1308).

### Observability

- **Added** tracing backends are discovered (`TracingBackend` SPI), so `TRACING_MODE=<name>` selects a third-party
  backend [#1133](https://github.com/llm4s/llm4s/issues/1133).
- **Added** `llm4s-observability-micrometer`: `MicrometerMetrics` writes llm4s metrics into a `MeterRegistry`
  [#1466](https://github.com/llm4s/llm4s/issues/1466) [#1817](https://github.com/llm4s/llm4s/issues/1817);
  [guide](docs/guide/observability/micrometer.md).
- **Changed** Langfuse without keys does not start, and a Langfuse trace is one agent run, grouped by thread
  [#1133](https://github.com/llm4s/llm4s/issues/1133) [#1329](https://github.com/llm4s/llm4s/issues/1329).
- **Fixed** a partly accepted Langfuse batch (HTTP 207) was reported as exported
  [#1239](https://github.com/llm4s/llm4s/issues/1239).
- **Fixed** `MetricsCollector.compose` forwards the image-generation methods
  [#1609](https://github.com/llm4s/llm4s/issues/1609).

### Integrations (Java, Kotlin, Spring Boot, cats-effect, ZIO, Pekko)

- **Added** `llm4s-java-api` (Beta): clients, builders, completions, options, embeddings and agents from Java, with Java
  types only and `LlmResult` instead of exceptions [#934](https://github.com/llm4s/llm4s/issues/934)
  [#1487](https://github.com/llm4s/llm4s/issues/1487) [#1488](https://github.com/llm4s/llm4s/issues/1488)
  [#1489](https://github.com/llm4s/llm4s/issues/1489) [#1490](https://github.com/llm4s/llm4s/issues/1490)
  [#1377](https://github.com/llm4s/llm4s/issues/1377) [#1392](https://github.com/llm4s/llm4s/issues/1392)
  [#1393](https://github.com/llm4s/llm4s/issues/1393) [#1666](https://github.com/llm4s/llm4s/issues/1666)
  [#1817](https://github.com/llm4s/llm4s/issues/1817) [#1591](https://github.com/llm4s/llm4s/issues/1591);
  [guide](docs/guide/java.md).
- **Added** `llm4s-kotlin-api` (Experimental), published with each release: suspend functions and `Flow`s over the Java
  facade [#937](https://github.com/llm4s/llm4s/issues/937) [#1494](https://github.com/llm4s/llm4s/issues/1494)
  [#1693](https://github.com/llm4s/llm4s/issues/1693) [#1663](https://github.com/llm4s/llm4s/issues/1663)
  [#1671](https://github.com/llm4s/llm4s/issues/1671) [#1850](https://github.com/llm4s/llm4s/issues/1850).
- **Added** `llm4s-spring-boot-starter` (Beta): auto-configuration for any provider through `llm4s.providers.*`, a
  health indicator and Micrometer metrics [#936](https://github.com/llm4s/llm4s/issues/936)
  [#1467](https://github.com/llm4s/llm4s/issues/1467) [#1636](https://github.com/llm4s/llm4s/issues/1636)
  [#1640](https://github.com/llm4s/llm4s/issues/1640) [#1642](https://github.com/llm4s/llm4s/issues/1642)
  [#1820](https://github.com/llm4s/llm4s/issues/1820); [guide](docs/guide/spring-boot.md).
- **Added** `llm4s-effect` and `llm4s-zio` (Beta): cats-effect, fs2, ZIO and ZIO Streams wrappers over `LLMClient` and
  `Agent` [#935](https://github.com/llm4s/llm4s/issues/935); [cats-effect](docs/guide/cats-effect.md),
  [ZIO](docs/guide/zio.md).
- **Added** `llm4s-pekko` (Beta): Pekko Streams `Source`s over `LLMClient` and `Agent`
  [#1457](https://github.com/llm4s/llm4s/issues/1457) [#1815](https://github.com/llm4s/llm4s/issues/1815)
  [#1822](https://github.com/llm4s/llm4s/issues/1822); [guide](docs/guide/pekko.md).

### Testkits

- **Added** `llm4s-testkit`: `ScriptedLLMClient`, a scriptable fake `LLMClient` for your tests
  [#1475](https://github.com/llm4s/llm4s/issues/1475) [#1796](https://github.com/llm4s/llm4s/issues/1796)
  [#1797](https://github.com/llm4s/llm4s/issues/1797); [guide](docs/guide/testing-with-the-testkit.md).
- **Added** `llm4s-provider-testkit`: the checks a provider module proves itself with, for external authors too
  [#1133](https://github.com/llm4s/llm4s/issues/1133) [#1582](https://github.com/llm4s/llm4s/issues/1582)
  [#1493](https://github.com/llm4s/llm4s/issues/1493) [#1703](https://github.com/llm4s/llm4s/issues/1703).

### Documentation & samples

- **Added** [Upgrading from 0.4.1 to 0.5.0](docs/migrations/0-4-1-to-0-5-0.md) and the [compatibility
  policy](docs/reference/compatibility-policy.md) [#1281](https://github.com/llm4s/llm4s/issues/1281).
- **Added** guides: [error handling](docs/guide/error-handling.md) [#960](https://github.com/llm4s/llm4s/issues/960),
  [caching](docs/guide/caching.md) [#1297](https://github.com/llm4s/llm4s/issues/1297), [structured
  output](docs/guide/structured-output.md) [#1310](https://github.com/llm4s/llm4s/issues/1310), [derived
  schemas](docs/guide/derived-schemas.md) [#1472](https://github.com/llm4s/llm4s/issues/1472), [built-in
  tools](docs/guide/builtin-tools.md) [#1296](https://github.com/llm4s/llm4s/issues/1296), [RAG query
  transformers](docs/guide/rag-query-transformers.md) [#1545](https://github.com/llm4s/llm4s/issues/1545), [provider
  exchange logging](docs/guide/observability/provider-exchange-logging.md)
  [#1298](https://github.com/llm4s/llm4s/issues/1298) and metrics in [monitoring](docs/guide/observability/index.md)
  [#700](https://github.com/llm4s/llm4s/issues/700).
- **Added** the [cookbook](docs/examples/cookbook.md): thirteen recipes that run offline in CI
  [#1476](https://github.com/llm4s/llm4s/issues/1476) [#1330](https://github.com/llm4s/llm4s/issues/1330).
- **Added** samples and templates: `CostTrackingExample` [#516](https://github.com/llm4s/llm4s/issues/516)
  [#898](https://github.com/llm4s/llm4s/issues/898), `MultiProviderComparisonExample`
  [#957](https://github.com/llm4s/llm4s/issues/957) [#1081](https://github.com/llm4s/llm4s/issues/1081), a Scala CLI
  quick start [#1456](https://github.com/llm4s/llm4s/issues/1456), Gradle and a standalone Java sample
  [#938](https://github.com/llm4s/llm4s/issues/938) [#968](https://github.com/llm4s/llm4s/issues/968)
  [#981](https://github.com/llm4s/llm4s/issues/981), a staged-deployment template
  [#846](https://github.com/llm4s/llm4s/issues/846) [#857](https://github.com/llm4s/llm4s/issues/857).
- **Added** `llm4s-schema-derivation` (Experimental): `SchemaOf[A]` derives the JSON schema of `completeStructured` from
  a case class [#1472](https://github.com/llm4s/llm4s/issues/1472).
- **Added** a contributing guide for OpenAI-compatible providers [#1132](https://github.com/llm4s/llm4s/issues/1132);
  [CONTRIBUTING](CONTRIBUTING.md#adding-an-openai-compatible-provider-a-dialect).
- **Fixed** the docs taught `LLM_MODEL`, which nothing reads since 0.3.2
  [#903](https://github.com/llm4s/llm4s/pull/903); install snippets follow the latest release
  [#1281](https://github.com/llm4s/llm4s/issues/1281); Reference pages no longer 404
  [#444](https://github.com/llm4s/llm4s/issues/444); `nav_order` is unique
  [#1623](https://github.com/llm4s/llm4s/issues/1623).

### Build & tooling

- **Added** `llm4s-bom`, one version for every llm4s artifact [#1462](https://github.com/llm4s/llm4s/issues/1462).
- **Added** MiMa on the frozen modules, a MiMa dry run, `sbt stabilityTierCheck`, `frozenDependencyCheck` and
  `publishedArtifactsCheck` [#924](https://github.com/llm4s/llm4s/issues/924)
  [#1281](https://github.com/llm4s/llm4s/issues/1281) [#1126](https://github.com/llm4s/llm4s/issues/1126); [API
  stability](docs/reference/api-stability.md).
- **Added** POM metadata and descriptions (`sbt pomMetadataCheck`), a CycloneDX SBOM per module and a dependency scan,
  and `scripts/verify-release.sh` [#1455](https://github.com/llm4s/llm4s/issues/1455)
  [#1588](https://github.com/llm4s/llm4s/issues/1588) [#1464](https://github.com/llm4s/llm4s/issues/1464)
  [#1150](https://github.com/llm4s/llm4s/issues/1150); [SBOM](docs/reference/sbom.md).
- **Added** CI: a merge queue, a docs-deploy dry run, checks of the support matrix, stability tiers and internal docs
  links, retries of transient downloads [#1638](https://github.com/llm4s/llm4s/issues/1638)
  [#1152](https://github.com/llm4s/llm4s/issues/1152) [#967](https://github.com/llm4s/llm4s/issues/967)
  [#1482](https://github.com/llm4s/llm4s/issues/1482); [merge queue](docs/reference/merge-queue.md).
- **Added** integration suites against the MCP reference server and the cloud speech APIs
  [#1006](https://github.com/llm4s/llm4s/issues/1006) [#1041](https://github.com/llm4s/llm4s/pull/1041)
  [#1011](https://github.com/llm4s/llm4s/issues/1011).
- **Changed** the aggregate Scaladoc covers every published module, and the build has no third-party resolver
  [#1130](https://github.com/llm4s/llm4s/issues/1130).
- **Changed** `llm4s-workspace-client` drops seven unused dependencies (Tika, POI, PDFBox, jsoup, Postgres, HikariCP,
  commons-io).
- **Fixed** the docs deploy was rejected on a release tag [#1152](https://github.com/llm4s/llm4s/issues/1152)
  [#1147](https://github.com/llm4s/llm4s/pull/1147).

## [0.4.1] - 2026-08-29

### Fixed
- `Neo4jGraphStore.traverse` never worked against a real Neo4j. The variable-length pattern
  was built from a non-interpolated string, so `$maxDepth` reached Cypher as a parameter
  reference, which is illegal in a `MATCH` pattern ("Parameter maps cannot be used in MATCH
  patterns") and failed every traversal. It is now a breadth-first expansion of one level per
  query, which also avoids the path enumeration a variable-length pattern implies: `[*0..]`
  matches every relationship-unique path before `min(length(p))` reduces them to one row per
  node, which grows exponentially on a cyclic or dense graph - and unbounded is the default
  depth. Semantics follow `GraphTraversal.bfs`, which backs the in-memory store.
- `QdrantVectorStore` could not store a record whose ID was not already a UUID. Qdrant accepts
  only an unsigned integer or a UUID as a point ID and rejected everything else with
  `"test-1" is not a valid point ID`, so `upsert`, `get`, `delete` and `search` all failed.
  Non-UUID IDs are now mapped to a UUID derived from the ID, with the record's own ID carried
  in the payload and read back from there; UUID IDs are unchanged. Derived IDs are version 8
  UUIDs (RFC 9562's "custom" space) and an ID that is itself a version 8 UUID is derived from
  rather than passed through, so a caller cannot land two records on one point by supplying
  the UUID that another ID maps to. A mocked-HTTP unit test had been asserting the broken
  request shape, which is why nothing caught this.
- `QdrantVectorStore` reported absence as failure. Qdrant answers 404 both for a point that
  does not exist and for a collection that does not exist - and the collection is created
  lazily on first upsert and deleted outright by `clear()` - so `get` returned a `Left`
  instead of `Right(None)`, and `count`, `list`, `search` and `stats` failed on an empty
  store rather than reporting it empty. Reads now treat 404 as empty; writes still error.
- 11 of the 18 integration suites in `modules/it` were executed by nothing - not locally, not
  in CI, not on release - because the tier aliases named two `testOnly` patterns and every
  suite outside them matched nothing. Tier membership is now declared per suite by class
  annotation (`@Local`, `@Docker`, `@Workspace`, `@Ollama`, `@Cloud` in `org.llm4s.it.tags`)
  and `sbt it/itTierCheck` fails the build when a suite declares none or more than one.
  ([#1143](https://github.com/llm4s/llm4s/issues/1143))
- Suites no longer report a pass when their dependency is absent. `Tier.require` cancels
  locally and, under `LLM4S_IT_STRICT=true` (set by every tier's CI job), fails - so a
  service that did not start is visible instead of green. This also exposes that
  `GeminiSmokeSpec` reads `GEMINI_API_KEY` while the cloud job only passed `GOOGLE_API_KEY`;
  the workflow now passes both, plus `COHERE_API_KEY`.
- `ContainerisedWorkspaceTest` pinned a `workspace-runner:0.1.0-SNAPSHOT` image tag that the
  build stopped producing long ago. The tag now comes from the build itself.

### Added
- The old artifact coordinates retired in 0.4.0 now publish a Maven **relocation** stub, so
  a build that bumps `"org.llm4s" %% "core"` (or `workspaceclient`, `workspaceshared`,
  `trace-opentelemetry`, `knowledgegraph-neo4j`) to 0.4.1 resolves the renamed artifact
  instead of failing with an unresolved dependency. The stubs are POM-only and carry no code.

  Two limits worth stating. A build pinned to 0.3.4 sees no signal at all - no Maven mechanism
  reaches it. And coursier, sbt's resolver, follows a relocation *silently*: the build simply
  starts working again without printing anything, so update the coordinate by hand rather than
  waiting to be told. The stubs were written for 0.4.0
  ([#1146](https://github.com/llm4s/llm4s/pull/1146)) but landed after the tag, so 0.4.0 itself
  has no redirect ([#1150](https://github.com/llm4s/llm4s/issues/1150)).
- `sbt testIntegration` runs the containerised tier (pgvector, Qdrant, Neo4j) and a CI job
  runs it on **every PR** with those services as service containers - the suites covering
  pgvector, the Postgres keyword index, Qdrant, permission-aware RAG, Postgres-backed agent
  memory and Neo4j now have execution signal ahead of the modularisation carve
  ([#1126](https://github.com/llm4s/llm4s/issues/1126)), which moves most of that code.
- `sbt testWorkspace` runs the containerised workspace tier, in a CI job on pushes to `main`
  that first builds the `workspace-runner` image.

### Changed
- `modules/it` joined the root aggregate, so it is compiled, formatted and linted with every
  other module; it had been outside it, where its suites could stop compiling unnoticed.
  Default `sbt test` runs only its `@Local` tier, so the dependency-free tier stays fast.

## [0.4.0] - 2026-08-29

### Changed
- **Breaking (coordinates only):** every published artifact was renamed to carry an `llm4s-`
  prefix in consistent kebab-case. No API, package or source changes accompany the rename.

  | Old coordinate | New coordinate |
  |---|---|
  | `org.llm4s:core` | `org.llm4s:llm4s-core` |
  | `org.llm4s:workspaceshared` | `org.llm4s:llm4s-workspace-shared` |
  | `org.llm4s:workspaceclient` | `org.llm4s:llm4s-workspace-client` |
  | `org.llm4s:trace-opentelemetry` | `org.llm4s:llm4s-observability-otel` |
  | `org.llm4s:knowledgegraph-neo4j` | `org.llm4s:llm4s-knowledgegraph-neo4j` |

  The rename also escapes the accidental `2.1.593` version mis-published under `core_3` /
  `core_2.13`, which cannot be retracted from Maven Central and sorts as "latest" in some
  resolvers. `0.3.4` and earlier remain published under the old coordinates, so no existing
  build breaks until it upgrades. See
  [docs/reference/migration.md](docs/reference/migration.md#artifact-coordinate-rename-v040).
- Unpublished modules (`samples`, `workspaceRunner`, `workspaceSamples`, `it`, `benchmarks`)
  had their `name` values aligned to the same convention; they set `publish / skip := true`,
  so this has no downstream effect.

### Docs
- Updated every published coordinate in the docs, including the Maven Central badge and the
  Maven `<artifactId>` snippet, to the new names.

## [0.3.4] - 2026-06-19

### Changed
- Updated build dependencies; fixed Docker image `apt-key` removal

### Docs
- Added ScalaDoc for `LLMCompressor` and `ContextManager`
- Clarified that `LLMCompressor` cost is charged per digest, not per over-cap message
- Aligned production-readiness documentation

## [0.3.3] - 2026-06-14

### Added
- SSE transport (2024-11-05 spec) and bearer-token authentication for `MCPServer`
- Image generation with integrated cost tracking and metrics
- LLM-driven memory entity extraction
- Streaming chat-TUI sample built on termflow

### Changed
- Enabled Scalafix configuration-boundary enforcement across the codebase

### Security
- Redact API keys from provider error messages
- Added Dependabot and published a threat model
- Constant-time MCP auth comparison, public-bind guard, and bounded connection pool

### Fixed
- ReDoS vulnerability in regex handling (workspace and core)
- Incorrect WAV headers and metadata in `WavFileGenerator`
- Validate SQLite metadata keys and table names

## [0.3.2] - 2026-04-26

### Added
- `ModelRegistryService` trait and default implementation for provider/model abstraction
- Modular RAG example with guide and tests
- Provider dashboard sample demo
- Expanded STT domain model with richer metadata, validation, and error handling

### Changed
- Refactored `AssistantAgent` to expose `AgentContext` on the constructor
- **Breaking:** Removed `LLMProvider` sealed trait in favour of `ProviderKind` — update exhaustive pattern matches; see [0.x → 1.0 migration guide](docs/migrations/0x-to-1x.md#llmprovider-replaced-by-providerkind)
- Simplified project names in `build.sbt`

### Fixed
- Thread `ModelRegistryService` through `ModularRAGExample`
- Hardened `ShellTool` allowlist to block shell-metacharacter injection
- Broken markdown table in README
- Build and clean up of modular RAG example

### Docs
- Result-based error handling in all examples

## [0.3.1] - 2026-02-22

### Added
- Adaptive windowing pruning strategy and context window guide
- ScalaDoc Waves 1–5 across all public API packages (entry-point types, agent/message/toolapi, provider implementations, infrastructure, usage summary)

### Changed
- Split monolithic `Agent.scala` into focused modules for improved testability

### Fixed
- 12 Scaladoc errors that caused the `core/doc` build to fail

## [0.3.0] - 2026-02-22

### Added
- Queryable in-process `TraceStore` with `InMemoryTraceStore`
- Thread-safe `CircuitBreaker` with optimistic locking and improved testability via clock injection
- Production deployment guide (`docs/PRODUCTION_DEPLOYMENT.md`)
- PostgreSQL `MemoryStore` usage examples and troubleshooting docs

### Changed
- **Breaking:** Removed all previously-deprecated throwing APIs (`agent.initialize`, `DateTimeTool.tool`, `BuiltinTools.core`, etc.) — migrate to their `Safe` variants; see [Migrating from v0.2.9 to v0.3.0](docs/migrations/v0.2.9-to-v0.3.0.md)

### Fixed
- WAV header bugs, incorrect metadata and resource leaks in the speech module
- Empty-embedding error assertions in RAG tests
- Provider test coverage via `Llm4sHttpClient` injection

## [0.2.9] - 2026-01-11

### Added
- Unified document processing pipeline with S3 source support
- Multi-tonal translation example

### Changed
- Replaced `println` with structured logging in memory samples
- Removed duplicate `sbt`/`coursier` cache entries from CI test matrix

### Fixed
- `DisableSyntax` scalafix violations

## [0.2.8] - 2026-01-05

### Added
- Unified `EMBEDDING_MODEL` configuration format (`provider/model-name`)
- Shared `MCPConfig` extracted to eliminate duplication across MCP server/client samples
- PureConfig-based configuration for MCP server and client samples

### Fixed
- `PgSearchIndex` not persisting vectors when using `RAGConfig.withSearchIndex`

## [0.2.7] - 2026-01-02

### Added
- `WebCrawlerLoader` for RAG document ingestion from live URLs
- `WebCrawlerLoader` design specification

### Fixed
- Code review issues in `Agent` and `WorkspaceAgentInterfaceImpl`

## [0.2.6] - 2026-01-02

### Added
- Permission-based RAG for enterprise multi-tenant access control
- PostgreSQL integration tests in CI

### Fixed
- Cross-collection chunk ID collision (IDs are now namespaced by collection name)
- Permission validation and CI environment variables
- PostgreSQL permission test reliability

## [0.2.5] - 2025-12-30

### Fixed
- Restore working `publishTo` configuration with local staging

## [0.2.4] - 2025-12-29

### Fixed
- Publishing: remove custom `publishTo`, rely on `sbt-ci-release` defaults

## [0.2.3] - 2025-12-28

### Fixed
- Out-of-memory error during release caused by Scaladoc generation; disable test-module Scaladoc and increase heap

## [0.2.2] - 2025-12-28

### Fixed
- Scaladoc generation failures for Scala 2.13

## [0.2.1] - 2025-12-26

### Added
- Comprehensive tests for `types`, `rag`, `agent`, and `assistant` packages
- Code coverage reporting to CI via Codecov

### Changed
- Consolidated tracing implementations; removed duplicate code and legacy type aliases
- Renamed `EnhancedAgent` / `TypedAgent` to simpler canonical names

### Fixed
- Flaky tests in `StdioTransportConcurrencySpec` and `SessionStateSpec`
- Codecov token configuration for v4 action

## [0.2.0] - 2025-12-17

### Added
- RAG Phase 2: complete RAG pipeline with evaluation, benchmarking, guardrails, and cost tracking
- Comprehensive documentation overhaul
- Validation using the Cats `Validated` framework

### Fixed
- Race condition in `StdioTransportImpl` concurrent request handling
- Flaky truncation test in `ShellToolsSpec`
- Maven artifact coordinates in documentation

## [0.1.16] - 2025-10-20

### Added
- Debug logging to `Agent` and comprehensive game-tools integration tests

### Fixed
- Critical `NoSuchElementException` bug in `healthCheck()`

## [0.1.15] - 2025-10-20

### Fixed
- Zero-parameter tools now correctly accept `null` argument payloads

## [0.1.14] - 2025-10-12

### Changed
- **Breaking:** Removed `DBx` module — PostgreSQL/vector-store functionality has been replaced by the RAG module

### Fixed
- Tool calling bug causing HTTP 400 errors with the Anthropic API
- Parameter ordering in `OpenAIClient` builder methods
- Simplified cross-test commands and cleaned up unused SBT configurations

## [0.1.13] - 2025-09-28

### Added
- Multi-agent orchestration: type-safe `Agent[I, O]` contracts, DAG planning, topological execution with level-based parallelism
- Vector operations foundation and `VectorStore` layer
- Security documentation and exception-safety refactor (Try → Either throughout)

### Changed
- Moved all modules into `modules/` subdirectory; cleaned up `build.sbt`
- Removed legacy codegen module and deprecated workspace classes

### Fixed
- SQL injection prevention and connection pooling in database utilities

## [0.1.12] - 2025-09-13

### Added
- Scalafix rule to ban direct environment-variable access (`sys.env`, `System.getenv`)
- Improved speech-file generation with comprehensive error handling and test coverage

### Fixed
- Tool call failures and improved error messages

## [0.1.11] - 2025-08-30

### Added
- Ollama LLM provider support with example implementations and tests
- Assistant agent with session management (`AssistantAgent`, `SessionManager`, `ConsoleInterface`)

### Changed
- Replaced `EnvLoader` with `ConfigReader` for flexible configuration handling
- Refactored MCPClient for better maintainability
- Enhanced Agent framework and tracing

## [0.1.10] - 2025-08-18

### Added
- Native SDK streaming support for OpenAI and Anthropic

## [0.1.9] - 2025-08-01

### Fixed
- Scala 3 build compilation errors

## [0.1.8] - 2025-08-01

### Changed
- Refactored `Agent.run` to support resuming execution from an existing `AgentState`

## [0.1.0] – [0.1.7] - 2025-04-06 – 2025-08-01

Initial release. Versions 0.1.0 through 0.1.7 were primarily focused on establishing the release pipeline:

### Added
- Core LLM client abstraction with OpenAI and Anthropic providers
- `Result[A]` / `Either[LLMError, A]` error model
- Tool calling API with `ToolRegistry` and `ToolBuilder`
- Agent framework with basic run/step loop
- SBT multi-module build with cross-compilation for Scala 2.13 and 3.x

### Fixed (release infrastructure)
- Windows ScalaFmt build issue
- Publishing configuration and v-prefixed tag handling
- GitHub Actions release workflow

---

[Unreleased]: https://github.com/llm4s/llm4s/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/llm4s/llm4s/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/llm4s/llm4s/compare/v0.3.4...v0.4.0
[0.3.4]: https://github.com/llm4s/llm4s/compare/v0.3.3...v0.3.4
[0.3.3]: https://github.com/llm4s/llm4s/compare/v0.3.2...v0.3.3
[0.3.2]: https://github.com/llm4s/llm4s/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/llm4s/llm4s/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/llm4s/llm4s/compare/v0.2.9...v0.3.0
[0.2.9]: https://github.com/llm4s/llm4s/compare/v0.2.8...v0.2.9
[0.2.8]: https://github.com/llm4s/llm4s/compare/v0.2.7...v0.2.8
[0.2.7]: https://github.com/llm4s/llm4s/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/llm4s/llm4s/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/llm4s/llm4s/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/llm4s/llm4s/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/llm4s/llm4s/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/llm4s/llm4s/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/llm4s/llm4s/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/llm4s/llm4s/compare/v0.1.16...v0.2.0
[0.1.16]: https://github.com/llm4s/llm4s/compare/v0.1.15...v0.1.16
[0.1.15]: https://github.com/llm4s/llm4s/compare/v0.1.14...v0.1.15
[0.1.14]: https://github.com/llm4s/llm4s/compare/v0.1.13...v0.1.14
[0.1.13]: https://github.com/llm4s/llm4s/compare/v0.1.12...v0.1.13
[0.1.12]: https://github.com/llm4s/llm4s/compare/v0.1.11...v0.1.12
[0.1.11]: https://github.com/llm4s/llm4s/compare/v0.1.10...v0.1.11
[0.1.10]: https://github.com/llm4s/llm4s/compare/v0.1.9...v0.1.10
[0.1.9]: https://github.com/llm4s/llm4s/compare/v0.1.8...v0.1.9
[0.1.8]: https://github.com/llm4s/llm4s/compare/v0.1.7...v0.1.8
[0.1.7]: https://github.com/llm4s/llm4s/compare/v0.1.0...v0.1.7
[0.1.0]: https://github.com/llm4s/llm4s/releases/tag/v0.1.0
