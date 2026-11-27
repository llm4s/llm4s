---
layout: page
title: Migration Guide
description: "Per-change migration notes for LLM4S releases after 0.4.1, from Secret credentials to multimodal content parts and subagent changes."
parent: Reference
nav_order: 2
cites_issues: all
---

# Migration Guide

This page is the canonical record of every breaking change since 0.4.1. To find the notes that apply to the release
you are on, start from [Migrating from 0.x to 1.0](../migrations/0x-to-1x), which has an entry point per release.
Upgrading from `0.4.1`? Start with [Upgrading from 0.4.1 to 0.5.0](../migrations/0-4-1-to-0-5-0), one page grouped
by what you use. The notes below are the per-change detail it links to. From 0.5.0 to 0.5.1 the source breaks are
[Multimodal content parts](#multimodal-content-parts-1913) and
[Harness profile and middleware placement](#harness-profile-and-middleware-placement-1915). From 0.5.1 to 0.6.0, [`MemoryManagerConfig` is
growth-safe](#memorymanagerconfig-is-growth-safe-1847), and so are [`ToolRetryPolicy`](#toolretrypolicy-is-growth-safe-1708),
[`GeminiConfig`, `VertexAIConfig` and `AzureConfig`](#geminiconfig-vertexaiconfig-and-azureconfig-are-growth-safe-2263),
[core's tool, client, embedding, registry and tracing configs](#core-config-types-are-growth-safe-2346),
[core's HTTP response, embedding, pricing, usage and exchange types](#core-data-types-are-growth-safe-2356),
[the `TraceEvent` cases](#traceevent-cases-are-growth-safe-2377),
[the `llmconnect.model` types](#llmconnectmodel-types-are-growth-safe-2378),
[the provider SPI, caching, streaming and exchange-sink types](#provider-spi-caching-streaming-and-exchange-sink-types-are-growth-safe-2379),
[the context types](#context-types-are-growth-safe-2380),
[the tool schemas and tool-call types](#tool-schemas-and-tool-call-types-are-growth-safe-2381),
[two `ToolCallError` and `ToolParameterError` cases](#toolcallerror-and-toolparametererror-cases-2382),
[the `AssistantError` cases](#assistanterror-cases-are-growth-safe-2383),
[`RerankProviderConfig`](#rerankproviderconfig-is-growth-safe-2478)
and [the remaining config, model and util types](#config-model-and-util-types-are-growth-safe-2384);
[`OpenAIConfig.fromValues` and `AzureConfig.fromValues` take model, key, then base URL](#provider-fromvalues-take-model-key-then-base-url-2340);
[`OpenAIToolHelper` is internal](#openaitoolhelper-is-internal-2360);
[`ServiceError`'s extractor gains `retryAfter`](#serviceerror-extractor-and-isrecoverablestatus-2266); [console tracing prints no content by default](#console-tracing-prints-no-content-by-default-2311);
[`OpenAICompatibleDialect.addReasoning` returns what it did not send](#openaicompatibledialectaddreasoning-returns-what-it-did-not-send-2115);
[`completionTokens` no longer includes thinking tokens](#completiontokens-no-longer-includes-thinking-tokens-2564);
[a Gemini reply a content filter stopped is an error](#a-gemini-reply-a-content-filter-stopped-is-an-error-2563); and
[`llm4s-java-api` and the Spring Boot starter no longer bring the provider modules](#llm4s-java-api-and-the-spring-boot-starter-no-longer-bring-the-provider-modules-1496).
From 0.6.0 to 0.7.0, [`budgetTokens` and `ReasoningEffort.None` are replaced by `Thinking`](#budgettokens-and-reasoningeffortnone-are-replaced-by-thinking-2115),
[finish reasons are typed](#finish-reasons-are-typed-2613),
[every provider's content-filter stop is an error](#every-providers-content-filter-stop-is-an-error-2677),
[a stream that did not finish is an error](#a-stream-that-did-not-finish-is-an-error-2613),
[a broken or cut tool call is an error](#a-broken-or-cut-tool-call-is-an-error-2613),
[`TokenUsage` and `ModelPricing` gain a prompt-cache field](#tokenusage-and-modelpricing-gain-a-prompt-cache-field-2115),
[a cut-off answer ends `Truncated`, not `Completed`](#a-cut-off-answer-ends-truncated-not-completed-2613),
[a workspace container has no egress](#a-workspace-container-has-no-egress-870),
[a RAG with a `SearchIndex` ingests only with permissions](#a-rag-with-a-searchindex-ingests-only-with-permissions-2716),
[the forwarding `apply` overloads are removed](#the-forwarding-apply-overloads-are-removed-1281),
[`CompressionRule`'s `apply` field is `transform`](#compressionrules-apply-field-is-transform-1281),
[`DocumentRegistry` is source-scoped](#documentregistry-is-source-scoped-2714),
[RAG tracing covers every model call and each query's stages](#rag-tracing-covers-every-model-call-and-each-querys-stages-2734),
[`RAG.ingestChunks` takes `DocumentChunk`s](#ragingestchunks-takes-documentchunks-2732),
[`SQLiteVectorStore` escapes metadata and filters exactly](#sqlitevectorstore-escapes-metadata-and-filters-exactly-2759),
[the PostgreSQL checkpoint schema moves to version 6](#the-postgresql-checkpoint-schema-moves-to-version-6-2453),
[RAG loaders take a `UrlPolicy`, and `DirectoryLoader` stays in its root](#rag-loaders-take-a-urlpolicy-and-directoryloader-stays-in-its-root-2724),
[context pruning enforces both limits and `minRecentTurns`](#context-pruning-enforces-both-limits-and-minrecentturns-2728-2729),
[the middleware SPI is consistent and testable](#the-middleware-spi-is-consistent-and-testable-2795),
[`llm4s-agent` types are tiered and growth-safe](#llm4s-agent-types-are-tiered-and-growth-safe-2778),
[the legacy embedding provider selector is removed](#the-legacy-embedding-provider-selector-is-removed),
[Vertex AI no longer reads `endpoint` and `organization`](#vertex-ai-no-longer-reads-endpoint-and-organization),
[`ProviderRegistry` lookups accept aliases](#providerregistry-lookups-accept-aliases-2812),
[`StreamingAccumulator` takes the model and reports usage one way](#streamingaccumulator-takes-the-model-and-reports-usage-one-way-2813),
[context trimming fails when nothing fits, and externalizes only into a store](#context-trimming-fails-when-nothing-fits-and-externalizes-only-into-a-store-2817),
[Langfuse traces are private and name the run's user](#langfuse-traces-are-private-and-name-the-runs-user-2828),
[the Langfuse backend sends OpenTelemetry spans](#the-langfuse-backend-sends-opentelemetry-spans-2883),
[`Tracing` has one abstract method](#tracing-has-one-abstract-method-2799),
[`ReliableClient`'s metrics are generic metrics](#reliableclients-metrics-are-generic-metrics-2801),
[`SecretPatterns` is in `llm4s-agent`](#secretpatterns-is-in-llm4s-agent-2824),
[`RegexSafetyManager` returns `Result`](#regexsafetymanager-returns-result-2825),
[an open circuit is a `CircuitOpenError`](#an-open-circuit-is-a-circuitopenerror-2797),
[the caching types report failure, and tool loops bypass the cache](#the-caching-types-report-failure-and-tool-loops-bypass-the-cache-2803),
[model registry sources, exact lookup and a sealed `RequestTransformer`](#model-registry-sources-exact-lookup-and-a-sealed-requesttransformer-2823),
[a guardrail block's message names only the guardrail](#a-guardrail-blocks-message-names-only-the-guardrail-2793),
[`LLMClient.validate()` checks the provider](#llmclientvalidate-checks-the-provider-2830),
[the error hierarchy cleanup](#error-hierarchy-cleanup-2819),
[`AssistantError` and the assistant's newtypes live in `llm4s-agent`](#assistanterror-and-the-assistants-newtypes-live-in-llm4s-agent-2820),
[`Result` helpers and conversions move, and `ErrorMapper` is removed](#result-helpers-and-conversions-move-and-errormapper-is-removed-2821),
[`Llm4sHttpClient` has one method to implement, `send`](#llm4shttpclient-has-one-method-to-implement-send-2802),
[`NetworkSecurity` returns the addresses it vetted, and a refusal is not retryable](#networksecurity-returns-the-addresses-it-vetted-and-a-refusal-is-not-retryable-2826),
[`Llm4sConfig.providerConfigs()` returns each section's `Result`](#llm4sconfigproviderconfigs-returns-each-sections-result-providers-and-providersconfig-are-internal-2804),
[`ToolRegistry` is a trait built with `ToolRegistry.of`](#toolregistry-is-a-trait-built-with-toolregistryof-2816),
[`SchemaDefinition` gains a union and a raw JSON Schema case](#schemadefinition-gains-a-union-and-a-raw-json-schema-case-2814),
[a tool handler has one error type](#a-tool-handler-has-one-error-type-2815),
[a guardrail's reason is never stored](#a-guardrails-reason-is-never-stored-2853),
[a turn's query is admitted before the turn is stored](#a-turns-query-is-admitted-before-the-turn-is-stored-2854),
[an agent run's trace and metrics report one status](#an-agent-runs-trace-and-metrics-report-one-status-2829),
[`TraceEvent` carries an error as it is, and `AgentInitialized` is removed](#traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800),
[one retry mechanism: `LLMClientRetry` and `ErrorRecovery` are removed](#one-retry-mechanism-llmclientretry-and-errorrecovery-are-removed-2798),
[the provider SPI's error mapping, published helpers and report types](#provider-spi-error-mapping-published-helpers-report-types-2811),
[the provider SPI's descriptors, embedding sections and credential suppliers](#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811),
[the Experimental context pipeline and token counters built over a tokenizer](#the-context-pipeline-is-experimental-and-token-counters-are-built-over-a-tokenizer-2818),
[`Modality` as an enum, multimodal embedding in `llm4s-rag` and a final `EmbeddingClient`](#modality-is-an-enum-multimodal-embedding-lives-in-llm4s-rag-and-embeddingclient-is-final-2808),
[opaque newtypes in `org.llm4s.types`](#newtypes-in-orgllm4stypes-are-opaque-and-headroompercent-is-always-valid-2822),
[`ReliableClient`'s private constructor](#reliableclient-has-a-private-constructor-2797),
[final provider clients with one factory](#provider-clients-are-final-with-one-factory-2910),
[`CachingLLMClient` and `CachedEmbeddingClient`'s private constructors](#cachingllmclient-and-cachedembeddingclient-have-private-constructors-2910),
[tracing backend aliases and the removal of `Tracing.create`](#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799),
[image metrics as `ImageMetrics` in `llm4s-image`](#image-metrics-are-imagemetrics-in-llm4s-image-2801),
[the Hugging Face image client on Inference Providers](#the-hugging-face-image-client-calls-inference-providers-2832),
[the config-policy `prod` preset's model generations](#the-config-policy-prod-preset-allows-model-generations-2885),
[the OpenAI image client's default model](#the-openai-image-client-defaults-to-gpt-image-26-sunburst-2884),
[which of `Secret` and `Secret.token` stays registered](#secret-or-secrettoken-which-one-stays-registered-2824),
[`llm4s.providers.provider` is now `llm4s.defaultProvider`](#llm4sprovidersprovider-is-now-llm4sdefaultprovider-2806),
[`Llm4sConfig` takes `ProviderName`, and `embeddings()` returns the config alone](#llm4sconfig-takes-providername-and-embeddings-returns-the-config-alone-2804),
[`ProviderConfig` has no default members, and `EmbeddingProviderConfig` is typed](#providerconfig-has-no-default-members-and-embeddingproviderconfig-is-typed-2805),
[`withToolSet` and `validateIn`, and the Java facade's guardrails sit innermost](#withtoolset-and-validatein-and-the-java-facades-guardrails-sit-innermost-2835),
[message parts, citations and adjustments are `Seq`s](#message-parts-citations-and-adjustments-are-seqs-2809),
[`LLMClient` accessors are named for their values, and `ToolBuilder.build`](#llmclient-accessors-are-named-for-their-values-and-toolbuilderbuild-2908),
[`ThinkingBlock.Opaque` and `BaseLifecycleLLMClient` take a `ProviderId`](#thinkingblockopaque-and-baselifecyclellmclient-take-a-providerid-2908),
[errors, `Result` helpers and `AgentResult.messages` are `Seq`s](#errors-result-helpers-and-agentresultmessages-are-seqs-2908),
[one retry rule: `LLMError.isRetryable` replaces the recoverable markers](#one-retry-rule-llmerrorisretryable-replaces-the-recoverable-markers-2916),
[every `LLMError` reports `httpStatus`, `retryAfter` and `requestId`](#every-llmerror-reports-httpstatus-retryafter-and-requestid-2916),
[a context-length overflow and an unknown model are typed errors](#a-context-length-overflow-and-an-unknown-model-are-typed-errors-2916),
[`AgentBuilder` tools are additive](#agentbuilder-tools-are-additive-2916),
[`StreamedChunk` names its deltas](#streamedchunk-names-its-deltas-2916),
[`LlmException.isRetryable`, and three new `LlmErrorKind`s](#llmexceptionisretryable-and-three-new-llmerrorkinds-2916),
[`AgentResult.runUsage`, and tighter `PromptInjectionDetector` defaults](#agentresultrunusage-and-tighter-promptinjectiondetector-defaults-2922-2923),
[model ids are `ModelName`, provider ids `ProviderId`, and provider configs' base URLs `BaseUrl`](#model-ids-are-modelname-provider-ids-providerid-and-provider-configs-base-urls-baseurl-2822),
[provider ids are `ProviderId`](#provider-ids-are-providerid),
[`CompletionOptions`' sampling parameters are optional, and unset ones are not sent](#completionoptions-sampling-parameters-are-optional-and-unset-ones-are-not-sent-2807),
[`Completion` reads `content` and `toolCalls` from its message](#completion-reads-content-and-toolcalls-from-its-message-2807),
[a `Completion` without a finish reason is `FinishReason.Unknown`](#a-completion-without-a-finish-reason-is-finishreasonunknown-2807),
[`Completion.estimatedCost` is a `BigDecimal`; `UsageSummary` counts cache tokens](#completionestimatedcost-is-a-bigdecimal-usagesummary-counts-cache-tokens-2807),
[`AssistantMessage.text`, and a flat stored assistant message](#assistantmessagetext-and-a-flat-stored-assistant-message-2809),
[one `Conversation.create`; `Conversation.empty` is a value](#one-conversationcreate-conversationempty-is-a-value-2809),
[`ProviderExchange` and `ProviderExchangeRecorder`](#providerexchange-and-providerexchangerecorder-2809),
[`MessageRole` is a Scala 3 `enum`](#messagerole-is-a-scala-3-enum-2809),
[`LLMConnect.getClient` is the one way to get a client](#llmconnectgetclient-is-the-one-way-to-get-a-client-2810),
[`LLMClient.streamComplete` takes its callback in a second parameter list](#llmclientstreamcomplete-takes-its-callback-in-a-second-parameter-list-2810),
[a tool call's id is a `ToolCallId`, and its idempotency key an `IdempotencyKey`](#a-tool-calls-id-is-a-toolcallid-and-its-idempotency-key-an-idempotencykey-2815)
and [`CachedEmbeddingClient`'s key function takes the request](#cachedembeddingclients-key-function-takes-the-request-2908).
From 0.7.0 to 1.0.0, [an error's `provider` is a `ProviderId`](#an-errors-provider-is-a-providerid-2936), the one
source break, and three behaviour changes:
[a cut-off answer ends `Truncated`, Anthropic's `max_tokens` default, and reported penalties](#a-cut-off-answer-ends-truncated-anthropics-max_tokens-default-and-reported-penalties-2933).
After 1.0.0, [an `openai` section on api.openai.com uses the Responses API](#an-openai-section-on-apiopenaicom-uses-the-responses-api-2900),
and, in the Experimental `llm4s-schema-derivation`,
[an annotation that is not a constant string is a compile error](#an-annotation-that-is-not-a-constant-string-is-a-compile-error-2951);
in the Beta `llm4s-bedrock`, [web-identity, assume-role and SSO credentials load](#bedrock-loads-web-identity-assume-role-and-sso-credentials-3127);
[Anthropic sends every system message, and no default one](#anthropic-sends-every-system-message-and-no-default-one-3125);
and [Vertex AI prefers the configured credential file, and retries a refused token](#vertex-ai-prefers-the-configured-credential-file-and-retries-a-refused-token-3126).

## An `openai` section on api.openai.com uses the Responses API (#2900)

**Who is affected:** a client of provider `openai` whose base URL is OpenAI's default (`https://api.openai.com/v1`),
with no `api` set, and a model the model registry lists for `/v1/responses` or does not know: `gpt-4o` and
`gpt-4o-mini` (and the snapshots `gpt-4o-2024-08-06`, `gpt-4o-2024-11-20`, `gpt-4o-mini-2024-07-18`), `gpt-4.1`,
most of the gpt-5 and later models the registry lists (`gpt-5`, `gpt-5-mini`, `gpt-5.1`, `gpt-5.6-luna`,
`gpt-5.6-terra`, `gpt-6-luna`, `gpt-6.1-terra`, ...), `o3`, and a model newer than the bundled data. A fine-tune with
no `ft:` entry in the registry follows its base model; one with an `ft:` entry follows that entry, and today those
list no endpoints, so it stays on Chat Completions. The INFO line logged when a client is built says what it chose
(below). OpenAI served the named models on the Responses API on 2026-11-26; llm4s's own patch to the bundled data lists `/v1/responses` for the `gpt-4o` ones, which LiteLLM does
not. That covers a section in `application.conf`, an `OpenAIConfig` built in code, the Java builder
(`Llm4s.openAi()`), the Kotlin DSL (`openAi { }`), the Spring Boot starter (the flat `llm4s.provider=openai` or a
section `llm4s.providers.<name>.provider=openai`) and the Quarkus extension (a section only). Such a client now calls
OpenAI's Responses API (`/v1/responses`) instead of Chat Completions; OpenAI's pro, codex and deep-research models,
which it serves only there, go to it wherever the base URL points.

**Not affected:** a model the registry knows but does not list for `/v1/responses` (`gpt-4-turbo`, `gpt-4`,
`gpt-3.5-turbo`, `o1`, `o3-mini`, `o4-mini`, `gpt-4o-2024-05-13`, the dated audio-preview snapshots such as
`gpt-4o-audio-preview-2024-12-17`, the `gpt-audio` family, the search models, `gpt-5.5-cyber`,
`gpt-5.5-cyber-preview`, `gpt-6-rosalind`), a fine-tune the registry's `ft:`
entry covers (`ft:gpt-4o-mini-2024-07-18:...`: those entries list no endpoints), another base URL (a proxy, a
gateway, OpenAI's regional hosts), Requesty, OpenRouter and Azure OpenAI. These stay on Chat Completions and send
what 1.0.x sent. The `gpt-audio` and search models were refused on the Responses API on 2026-11-26; `o1`,
`o3-mini`, `o4-mini`, `gpt-4-turbo` and `gpt-3.5-turbo` could not be called then, so they follow the data,
unverified. The one exception to another base URL: a responses-only model (`mode: responses` in the registry: the
pro, codex and deep-research models such as `o3-pro`, `gpt-5-pro`, `gpt-5-codex`, `o3-deep-research`) uses the
Responses API behind a gateway or proxy too, with or without an `openai/` prefix, because Chat Completions cannot
serve it at OpenAI; a gateway that bridges Chat Completions for it needs `api = "chat-completions"` on the section.
A model id the registry does
not hold, such as the undated alias `gpt-4o-audio-preview` or a snapshot newer than the bundled data, is unknown to
it and uses the Responses API on api.openai.com; if that model is served on Chat Completions only, set
`api = "chat-completions"` on its section (or add it to a model registry of your own). [Which API a section uses](../guide/providers#which-api-a-section-uses) has
the rules, and how to move one of these models to the Responses API (`api = "responses"`, or a model registry of
your own that lists `/v1/responses` for it).

**What changes for an affected client:**

- The request is a Responses API request (`input` items, `max_output_tokens`, `reasoning.effort` when an effort is asked, `store: false`),
  and the exchange log records it and its reply; the log's format does not change.
- A reasoning model calling tools is sent the effort you asked for. On Chat Completions a request with tools to a
  GPT 5.6, 6 or 6.1 model is sent `reasoning_effort: none` (in 1.0.x only `gpt-6-luna` got that; the others failed,
  see [#2940](https://github.com/llm4s/llm4s/issues/2940)). On the Responses API the configured effort is sent, and
  with none configured the field is left out, so the model's own default effort applies. So these models now reason
  with tools: reasoning tokens are used, billed and
  reported (`TokenUsage.thinkingTokens`), and they count against `maxTokens` (sent as `max_output_tokens`, which
  includes reasoning), so a small limit can cut the answer short. Replies take longer, which matters for request
  timeouts. Choose with `withReasoning(ReasoningEffort.Low)` (or another level) or `withThinking(Thinking.Off)`.
- The stored opaque reasoning blocks count towards the context window's token estimate (`ConversationTokenCounter`,
  about a quarter of their length), so context pruning starts earlier on a long thread.
- `presencePenalty` and `frequencyPenalty` are not sent, and are reported as `RequestAdjustment.Dropped`. A
  `maxTokens` below 16 is sent as 16 and reported as `RequestAdjustment.Adjusted`.
- A reasoning model's reasoning is kept on the thread: each reasoning item is stored on the assistant message as an
  encrypted opaque thinking block and sent back on later requests to the same model. Stored threads and checkpoints
  grow by one such item per reasoning turn. `keepReasoning = false` on the section (`withKeepReasoning(false)` in
  code) turns it off; when OpenAI answers a request with an error refusing a stored item, the error says so and
  names that setting
  ([Reasoning kept across a thread](../guide/providers#reasoning-kept-across-a-thread)).
- `Completion.id` and each streamed chunk's `id` are the response id (`resp_...`), not `chatcmpl-...`.
- A reply that fails with an error code is reported by what the code means: `rate_limit_exceeded` as a
  `RateLimitError`, a code for a request that can never succeed (`invalid_prompt`, the image codes) as a
  `ValidationError`, a policy refusal (`bio_policy`, ...) as a `ContentFilteredError`, any other as a `ServiceError`
  502 ([the list](../guide/providers#what-differs-on-the-responses-api)).

**To see which API a client chose,** look for the INFO line logged when it is built:
`OpenAI client for model <model> uses <responses|chat-completions>: <reason>`, for example
`OpenAI client for model gpt-4.1 uses responses: the model registry lists gpt-4.1 for /v1/responses`.

**A model registry of your own** (`llm4s.modelRegistry.filePath`, `.url` or `.resourcePath`) replaces the bundled
catalogue, llm4s's patches included, so routing follows your entries: a model your registry lists for
`/v1/responses` (in `supported_endpoints`) or marks `mode: responses` uses the Responses API, one it lists without
it stays on Chat Completions, and a model your registry omits is unknown, so it uses the Responses API on
api.openai.com and Chat Completions on any other base URL
([Model Metadata](model-metadata#endpoints-and-the-openai-api)).

**To keep 1.0.x behaviour,** set `api` to `chat-completions`; the request is then what 1.0.x sent. The value is
exactly `chat-completions` or `responses`; any other fails the load.

| Entry point | The line |
|---|---|
| HOCON section (`application.conf`) | `api = "chat-completions"` in `llm4s.providers.<name> { ... }` |
| `OpenAIConfig` in code | `config.withApi(OpenAIApi.ChatCompletions)` |
| Java builder | `Llm4s.openAi().api("chat-completions")` |
| Kotlin DSL | `api = "chat-completions"` in the `openAi { }` block |
| Spring Boot, flat keys | `llm4s.api=chat-completions` |
| Spring Boot, named section | `llm4s.providers.<name>.api=chat-completions` |
| Quarkus, named section | `llm4s.providers.<name>.api=chat-completions` |

Once any `llm4s.providers.*` property is set, Spring ignores the flat keys, so a named-section application sets `api`
in the section.

**Positional patterns.** `OpenAIConfig` gained `api`, `reasoningSummary` and `keepReasoning` (11 fields to 14), and
`ModelMetadata` gained `supportedEndpoints` (8 to 9). Both are growth-safe classes, built with `apply` and `with*`
setters, so a field gained is not a break of the 1.x promise
([Compatibility policy](compatibility-policy#what-the-promise-covers)), but a pattern that binds their fields by
position no longer compiles: `case OpenAIConfig(_, model, _, _, _, _, _, _, _, _, _) =>` needs three more `_`.
Read the fields by name (`config.model`), or let Scalafix add them, from code still compiling against 1.0.x:

```bash
sbt "scalafixAll dependency:Llm4sMigrateFrom_1_0@org.llm4s::llm4s-scalafix:<the new version>"
```

## An annotation that is not a constant string is a compile error (#2951)

In `llm4s-schema-derivation` (Experimental), `@description`, `@toolName` and `@upickle.implicits.key` now accept any
constant string: a literal, a `final val` or `inline val` declared without a type, a `+` of such constants, or an
`s"..."` interpolation whose arguments are all such constants. Before, only a single literal was read and anything else
was silently ignored, so the field was described by its name. An argument that is still not a constant (a `def`, a
`val` that is not `final`, a `final val` with a declared type, an `f"..."` interpolation) now fails to compile where
the `SchemaOf`, `PartialOf` or tool is derived, with a message naming the field. Make the value a constant
(`final val Limit = 750`, without `: Int`), or write the text out.

## Vertex AI prefers the configured credential file, and retries a refused token (#3126)

`llm4s-gemini` is frozen, and no signature changed. How a `vertexai` client authenticates changes:

| Before (1.0.0) | After |
|---|---|
| `GOOGLE_ACCESS_TOKEN` in the environment was used even when the section set `apiKey` (a credential file), and cached for the life of the process | A configured file always wins. The variable is used only when the section sets no file, before `GOOGLE_APPLICATION_CREDENTIALS`, and read again every five minutes. A deployment that relied on the variable overriding the file now authenticates as the file's principal: remove `apiKey` from the section to keep using the token |
| A credential file missing `refresh_token`, `private_key` or another field threw `NoSuchElementException` out of `complete` and `validate()` | An `AuthenticationError` naming the field |
| A token Vertex AI refused with a 401 kept failing until it expired | The token is dropped and the call retried once with a fresh one |
| An `authorized_user` file's `quota_project_id` was ignored | It is sent as `x-goog-user-project` |
| `location = "global"` called `global-aiplatform.googleapis.com`, which does not exist | It calls `aiplatform.googleapis.com` |

## Anthropic sends every system message, and no default one (#3125)

`llm4s-anthropic` is frozen, and no signature changed. The request it builds changes in four ways:

| Before (1.0.0) | After |
|---|---|
| Of several `SystemMessage`s only the last was sent, so a `HistoryCompressor` digest replaced the agent's own prompt | Each is sent, in order, as its own system text block; the JSON instruction of a `responseFormat` is appended once, to the last, and a cached system prompt's breakpoint goes on the last |
| A conversation with no `SystemMessage` was sent `You are Claude, a helpful AI assistant.` | No system prompt is sent, as on Bedrock. Add `SystemMessage("You are Claude, a helpful AI assistant.")` to keep the old behaviour |
| A `reasoning` level's budget, or a `Budget(n)` under the default `max_tokens`, could take all but one token of `max_tokens`, leaving the answer empty | It is capped at half of `max_tokens`, reported `Adjusted` on `Completion.adjustments`. Set `maxTokens` and `Thinking.Budget(n)` together to give thinking more |
| Thinking with `maxTokens` of 1024 or less was sent and refused by Anthropic (400); a conversation of system messages alone threw `IllegalStateException` | Both return a `ValidationError` before anything is sent |

## Bedrock loads web-identity, assume-role and SSO credentials (#3127)

`llm4s-bedrock` is Beta. It now brings the AWS SDK's `sts`, `sso` and `ssooidc` modules. The SDK loads three
credential sources through them reflectively, and skipped each one silently while they were missing, unless another
dependency (such as `llm4s-rag`) brought `sts` in.

| Before (1.0.0) | After |
|---|---|
| On EKS with IRSA (`AWS_WEB_IDENTITY_TOKEN_FILE`, `AWS_ROLE_ARN`) the default chain skipped web identity and could use the node's instance role | The pod's service-account role is used. Grant it `bedrock:InvokeModel` (and `InvokeModelWithResponseStream`) if the node role had it instead |
| A `profile` with `role_arn` or SSO settings failed at the first call | It authenticates as that role or SSO user |
| `close()` left the profile or default credentials provider open | `close()` closes it |

## GraphRAG is refused in every mode on a `RAG` with a `SearchIndex` (#2964)

`llm4s-rag` is Beta, and no signature changed. On a `RAG` built with `.withSearchIndex(...)`, `queryWithGraphRAG`
and `queryWithGraphRAGMode` in Global and Local mode now return a `ConfigurationError`, as Hybrid mode and plain
`query` already did, before the router or the model is called. They answered from the whole graph and every
community summary, with no principal or collection, so any caller could read what the permission API would hide.
GraphRAG has no permission-filtered form yet: on a permission-aware `RAG`, answer with
`queryWithPermissionsAndAnswer`, or run a separate `GraphRAG` over a graph store that holds only what every caller
of it may read.

## GraphRAG no longer writes community nodes into your graph store (#2965)

`llm4s-rag` is Beta, and no signature changed. `GraphRAG` only reads its `GraphStore` now. It used to write a
`community:<id>` node labelled `Community` per community, with a `CONTAINS` edge to each member, during the first
query, after deleting every node whose id started with `community:` or whose label was `Community`, so a query
could delete your own `Community` nodes and their edges. Every other reader of the store (`GraphQAPipeline`,
`GraphQueryExecutor`, `GraphQueryTool`) saw the synthetic nodes and edges. Communities and their summaries are now
held by the `GraphRAG` instance, built once even when several first queries arrive together, and every node in the
store is treated as your data, whatever its label or id.

A store that an earlier version queried still holds those artefacts, and GraphRAG now reads them as ordinary nodes.
Delete them once: the nodes whose id starts with `community:`, whose label is `Community` and which carry the
`communityId`, `level`, `summary` and `edgeCount` properties GraphRAG wrote (`deleteNode` removes their edges).
Summaries are recomputed per `GraphRAG` instance; storing them outside the graph is tracked in
[#2981](https://github.com/llm4s/llm4s/issues/2981).

## `KnowledgeGraphGenerator.extract` takes a `DocumentSource` and namespaces node ids (#2966, #2963)

`llm4s-knowledgegraph` is Experimental. The model's node ids are arbitrary (usually `1`, `2`, ...), and
`KnowledgeGraphGenerator.extract` wrote them to the store unchanged, so a second document on the same store replaced
the first one's nodes while keeping its edges: "Alice WORKS_FOR Acme" from one document and "Bob" and "Paris" from the
next became "Bob WORKS_FOR Paris".

| Before (1.0.0) | After |
|---|---|
| `generator.extract(text)` | `generator.extract(text, DocumentSource("doc-1"))`; the stored and returned ids are `doc-1__1`, `doc-1__2`, ..., as `MultiDocumentGraphBuilder` already namespaces them |
| `extract(text, entityTypes, relationTypes)` | `extract(text, source, entityTypes, relationTypes)` |
| `new KnowledgeGraphGenerator(llmClient)`, which wrote into a private in-memory store | `new FreeFormExtractor(llmClient).extract(text)`, which returns the `Graph` and stores nothing |
| A failed node write still let every edge write run | No edge is written once a node write has failed |
| `MultiDocumentGraphBuilder.extractDocuments` accepted blank or repeated source ids, cross-wiring those documents' entities | It returns a `ValidationError` before any extraction when a source id is blank, repeated, or already a source of `existingGraph`; `extractDocument` refuses a blank id |

To re-extract a document into a graph built earlier, build from a `SourceTrackedGraph` without that document, or give
the new version its own source id.

## `Neo4jGraphStore` reserves `llm4s_` keys, round-trips properties, and `executeRead` returns `ujson` values (#2969)

`llm4s-knowledgegraph-neo4j` is Experimental.

| Before (1.0.0) | After |
|---|---|
| A property named `llm4s_id` or `llm4s_label` replaced the node's id or label | A property key starting with `llm4s_` is a `Left` from `upsertNode` and `upsertEdge` |
| Every number was stored as a float, so a property filter matched `"42.0"` but not `"42"` | Whole numbers are stored as integers: `"42"` matches, as in the in-memory and JSON stores, and `"42.0"` does not |
| `ujson.Null` was dropped, and arrays and objects were stored and read back as their `toString` | They read back as written: primitive arrays as Neo4j lists, the rest as JSON in `llm4s_json` |
| `executeRead` returned `List[Map[String, String]]` of driver `toString` output (`"\"Alice\""`, `node<5>`) | It returns `List[Map[String, ujson.Value]]`: `ujson.Str("Alice")`, and a node as an object with `id`, `label` and `properties` |

Read a string column with `row("name").str` instead of stripping quotes. Values written before this release keep
their stored form: numbers stored as floats still match only `"42.0"` until rewritten, and an array or object stored
as text reads back as a `ujson.Str`. Re-upsert those nodes to store them in the new form.

## `JsonGraphStore.save` validates and updates the store; writes are atomic (#2968)

`llm4s-knowledgegraph` is Experimental, and no signature changed.

| Before (1.0.0) | After |
|---|---|
| `save(graph)` wrote the file but not the store's cache, so the next write rewrote the file from the old graph and `graph` was lost | `save` replaces what the store serves, and later writes build on it |
| `save` wrote a graph with an edge to a missing node, which every later `load` refused | `save` returns that `Left` and writes nothing |
| Each write truncated the file in place, so a crash or a full disk could leave it empty or cut off | Each write goes to a temporary file beside it, moved over it; the directory must be writable |
| `load()` re-read the file but the store kept serving its cache | `load()` refreshes what the store serves |
| A read of a missing file created it | It reads as an empty graph, and the first write creates the file |

## Graph stores share one contract: Neo4j relationship types, self-loops, ordering and schema (#2970)

`llm4s-knowledgegraph` and `llm4s-knowledgegraph-neo4j` are Experimental. The in-memory, JSON and Neo4j stores now
pass one contract suite, and `GraphStore`'s Scaladoc states the behaviour.

| Before (1.0.0) | After |
|---|---|
| `Neo4jGraphStore` refused a relationship type outside `[A-Za-z0-9_]+` (`"WORKS FOR"`, `"works-for"`, non-ASCII), and `"3D_PRINTS"` failed as a Cypher syntax error | Any non-empty type without a backslash is written, backtick-quoted; `deleteEdge` takes any type and is a `Right` |
| A Neo4j property filter refused a key outside `[A-Za-z0-9_]+`, such as `first-name` | Any key a node is written with can be filtered on |
| `getNeighbors(_, Both)` on Neo4j dropped self-loops | Every store returns a self-loop, once, in every direction |
| `traverse` ordered a level by id on Neo4j and by insertion order elsewhere | Every store orders by depth, then by node id within a depth |
| `stats().averageDegree` on Neo4j counted relationships to nodes outside the store; `densestNodeId` broke ties arbitrarily | `averageDegree` is `2 * edgeCount / nodeCount`; ties go to the smallest id |
| A `GraphFilter` with `propertyKey` but no `propertyValue` (or the reverse) was ignored | It is a `Left` from `query` |
| A failure to create the Neo4j uniqueness constraint was a logged warning, and the factory still returned the store | The factories create the constraint and an index on `llm4s_label`, and return a `Left` (closing a driver they opened) when they cannot. The constructor no longer calls the server |

`Neo4jGraphStore` is `AutoCloseable`, and `Neo4jGraphStore.Config` gains `connectionTimeout: FiniteDuration`
(default 30 seconds), so a positional pattern on `Config` needs one more field. A user without schema rights now
gets a `Left`: have an administrator create `llm4s_id_unique` and `llm4s_label_index` (the statements are in
`Neo4jGraphStore`'s Scaladoc); the factories then find them and succeed. Code that relied on traversal order within a
level should sort by its own key.

## `EntityLinker` merges transitively and keeps provenance; schema extraction enforces the schema (#2967, #2962, #2971)

`llm4s-knowledgegraph` is Experimental.

| Before (1.0.0) | After |
|---|---|
| After a model-confirmed merge, the kept node listed only its own documents and the rewritten edge had no sources | Every merge moves the removed node's sources to the node it merged into; an edge's sources are the union of every edge rewritten onto it |
| Two edges rewritten onto one key kept only one edge's sources (#2962) | Their sources are unioned |
| With three names for one entity, chained merges could bring a removed node back and split its edges | Confirmed pairs are grouped transitively and each group merges once |
| Nodes with a blank `name` merged into one, a nameless node merged with a node named like its id, or with ids differing only in case, and a blank name was a disambiguation candidate for every node | A blank or missing name is no name: the node is keyed by its exact id, merges with nothing, and is never a candidate |
| Every self-loop was deleted | Only a self-loop the merge created is dropped |
| Edges differing only in properties stayed duplicated | One edge per (source, target, relationship), properties merged with the first edge's winning |
| The canonical id was the smallest, so an incremental build could rename an existing node | `linkTracked(tracked, preferredIds)` keeps a preferred id; `MultiDocumentGraphBuilder` passes the existing graph's ids |
| One malformed element in the model's `merges` discarded every merge | It is skipped with a warning; the others apply |
| Strict mode (`allowOutOfSchema = false`) kept an edge whose endpoints broke its `sourceTypes`/`targetTypes` | It is dropped, and reported as before |
| `PropertyDefinition.required` was only a prompt hint | A missing (or `null`) required property is a `missing_required_property` violation; strict mode drops the node and its edges |
| `person` and `Person` stayed two labels | Labels and relationships are respelled as the schema spells them |
| An edge to a dropped node vanished unreported | It is an `endpoint_missing` violation |
| Empty `entityTypes` in strict mode dropped every node | Empty `entityTypes` or `relationshipTypes` admits anything, as empty `sourceTypes` does |
| The builder only logged violations | `extractDocumentsWithValidation` returns a `GraphExtraction` with each document's `ValidationResult` |

A disambiguation candidate now needs a non-blank `name` on both nodes. `ExtractionSchema.simple` matches its
`properties` keys to type names ignoring case.

## Graph queries: robust translation, bounded execution, and QA context the question picks (#2973, #2974, #2975)

`llm4s-knowledgegraph` is Experimental.

| Before (1.0.0) | After |
|---|---|
| A model reply of the wrong shape (`{"query": ...}`, `"node_id": {...}`, `"max_depth": "two"`) threw out of `translate` and `ask`; prose around the JSON was a `Left` | Every field is checked, so a wrong shape is a `Left`; the JSON object is found inside prose or a fence, a numeric id or `"2"` depth is accepted, and a `null` native-query explanation is empty |
| The translator was given no node ids, so the model invented them | `translate(question, entities)` lists the resolved entities' ids; a composite step can name an earlier step's nodes as `$1`, `$2`, ... |
| An unknown id gave an empty result that read "Found 0 neighbor(s)" | `GraphQueryResult.missingNodeIds` lists it, and the summary says "not found" |
| A multi-hop `FindNeighbors` with a `relationshipType` traversed every relationship | Every depth follows only that type |
| `FindNeighbors` results left out the start node | The start node comes first |
| `FindPath` followed outgoing edges only, enumerated every simple path, and `from == to` was "1 path" | It returns at most `maxPaths` shortest paths, following edges either way by default (`direction`), and `from == to` is no path |
| A depth, hop count or composite length from the model or the tool was unbounded | Clamped to `maxQueryDepth` (default 5); composites run at most `maxCompositeSteps` (8) |
| `GraphEngine.traverseDFS` could miss a node within `maxDepth`; a negative distance returned the start node | It finds what `traverse` finds; a negative depth gives nothing; `traverse` and `traverseDFS` take a `direction` |
| Every node with the guessed label was an entity, and a mention matched any substring of any property | Entities match by name (exact 1.0, other property 0.8, whole words 0.7), the label narrows, and the best `maxEntities` (10) are used |
| Trimming could drop every edge of the hub the question named, and did not trim at all without ranking | Identified entities are kept first; nodes are trimmed either way; values are cut at `maxPropertyChars`, paths at `maxPaths`, and `maxContextTokens` bounds the whole context |
| Betweenness threw on an edge to a node outside the result and doubled undirected scores; degree counted parallel edges | Edges to unknown nodes are ignored; betweenness is in [0, 1] (chain `a-b-c` gives `b = 1.0`); degree counts distinct neighbours |
| An empty graph cost three model calls; a store failure building the entity prompt was dropped | An empty graph is answered without a call; the failure is the `Left` |

`GraphQuery.FindPath` gains a `direction` field (default `Both`, where paths were directed before) and
`GraphQueryResult` a `missingNodeIds` field, so positional patterns on them need one more field.

## GraphRAG Hybrid shows its evidence; seeds match whole words (#2972)

`llm4s-rag` is Beta. `GraphHybridSearchResult` gains `communities` (default empty); no other signature changed.

| Before (1.0.0) | After |
|---|---|
| The Hybrid prompt held only `id score=... vector=... graph=...` lines and community summaries | It holds the retrieved passages (up to 10, each cut to 1,200 characters), the top 10 hits rendered with label, name and properties (each value cut to 300 characters) and the relationships between them, then the summaries |
| Every Hybrid hit had the same `vectorScore`, the mean of all vector results, so `hybridVectorWeight` could not change the order | A hit's `vectorScore` is the best score of the vector results that name it in their metadata (`entityId`, `nodeId`, ...), share its provenance (`source`, `docId`, ...) or mention its name as whole words; 0 when none does |
| Seeds matched any substring of the query (`the`, `is`), and a node scored once per property containing a word | Seeds match whole words of two or more characters outside a stopword list, and each query word counts once per field: 3 in the name, 2 in the id, 1 in any other property |
| `route` found an entity in almost every question, so it almost never chose Global | It chooses Local only when a whole query word matches a node; a question naming none goes to Global, as do broad ones |
| Local search sent 30 nodes and 40 edges in hash order, sometimes without the seed; `visitedNodeIds` was sorted by id | Nodes go seeds first, then by traversal depth, before the cut; edges nearest first. `visitedNodeIds` is in that order |
| `GraphRAGAnswer.communityIds` was empty for Hybrid | It lists the communities Hybrid used, also in `GraphHybridSearchResult.communities` |
| Nodes in no community of `minCommunitySize` were lumped together and summarised by the model as one community | The lump's summary lists them and says they were not summarised; the model is not called for it |

`route` never selects Hybrid, and `RAG.queryWithGraphRAG` never runs vector retrieval: its Scaladoc said the router
decides Global, Local or Hybrid, and now says what it does. Call `queryWithGraphRAGMode(question, GraphRAGMode.Hybrid)`
or `GraphRAG.answer(question, vectorResults)` for Hybrid. A query that relied on a short or common word matching a
node (`"AI"` still matches; `"it"`, `"the"`, `"show"` do not) now seeds differently or routes to Global.

## Memory stores embed what they store, and `PostgresMemoryStore` filters like `matches` (#3030, #3031)

`llm4s-memory` and `llm4s-memory-postgres` are Beta. No signature changed; `PostgresMemoryStore` gains `embedAll`.

| Before (1.0.0) | After |
|---|---|
| `PostgresMemoryStore` and `InMemoryStore.withEmbeddingService` stored a memory's embedding only if the caller set it, so what a `MemoryManager` or `SemanticMemoryMiddleware` recorded was never found by similarity | Given an `EmbeddingService`, both embed every memory they write without an embedding (`store`, `storeAll`, `replace`, `applyAtomically`), and `update` embeds again when it changes the content. A failed embedding call is a `Left` and nothing is written |
| `PostgresMemoryStore.search` without an `EmbeddingService` returned the newest memories with score 0, whatever the query | A keyword search, scored as `InMemoryStore` scores it; no match is an empty result |
| `Not(MinImportance(x))`, `Not(ByEntity(e))` and `Not(ByMetadata(k, v))` left out memories without the importance or the key, in `recall`, `count` and `deleteMatching` | They are kept, as `MemoryFilter.matches` keeps them |
| `HasMetadata`, `MetadataContains`, `ContentContains` and `Custom` were refused with `Unsupported filter`, and `ByMetadata` with a key outside `[a-zA-Z_][a-zA-Z0-9_]*` with `Invalid metadata key` | Every filter and any key is accepted |
| `PostgresMemoryStore` and `VectorMemoryStore` wrote `MemoryType.Custom("Report")` as `Report` and read it back lower-cased, `Custom("report")`; `Custom("task")` came back as `Task` | A custom type is written as `custom:Report` and read back unchanged. Rows already written as `Report` read back as `Custom("Report")`, and `ByType` finds them under either spelling |

Memories stored in a `PostgresMemoryStore` before this change have no embedding unless you set one, so vector search
does not return them. Embed them once, with the store opened with its `EmbeddingService`:

```scala
store.embedAll() // Result[Int]: how many memories were embedded
```

A store without an `EmbeddingService` now reads every row a filter narrows to for `search`; give it one, or a narrow
filter, on a large table. See [Postgres Memory Store](postgres-memory-store#search).

## Metadata filters on the SQL stores agree with `SQLiteVectorStore` (#2996)

`llm4s-rag` is Beta, and no signature changed. `PgVectorStore`, `PgKeywordIndex`, `PgSearchIndex` and
`SQLiteKeywordIndex` now select the records that `SQLiteVectorStore` selects for a `MetadataFilter`.

| Before (1.0.0) | After |
|---|---|
| `Contains` on the Postgres stores was a `LIKE`, so `%` and `_` in the substring were wildcards | `Contains` is a literal substring test: `%` and `_` match only themselves |
| `SQLiteKeywordIndex`'s `Contains` was a `LIKE`: wildcards, and case-insensitive for ASCII letters | It is literal and case-sensitive, like every other SQL store |
| `Not(Equals(...))`, `Not(Contains(...))` and `Not(In(...))` left out records without the key, so `deleteByFilter(Not(...))` deleted fewer rows on Postgres | Those records are kept, and deleted |
| `HasKey` failed on the Postgres stores, an empty `In` was a syntax error, and `PgSearchIndex` read `Not(All)` as no condition | `HasKey` works, and an empty `In` and `Not(All)` match nothing |

`MetadataFilter` has no case-insensitive match. If you relied on `SQLiteKeywordIndex`'s case folding, store a
lower-cased copy of the value under its own key when you index, such as `"title_lc" -> title.toLowerCase`. Then filter
with `Contains("title_lc", query.toLowerCase)`. Neither form of `Contains` takes a pattern, so a wildcard search
needs its own key, such as a prefix stored separately, or filtering in your code after the search.

## Collection permissions fail closed, and a leaf holding documents keeps them (#2990, #2991)

`llm4s-rag` is Beta. `CollectionStore` gains `effectiveAccess` (with a default) and `EffectiveAccess`;
`getEffectivePermissions` and `Collection.canQuery` are deprecated.

| Before (1.0.0) | After |
|---|---|
| `canQuery` on a public child of a restricted parent was `true` for everyone, and so was it when the parent's and child's sets were disjoint; a search denied both | `canQuery` gives the answer `findAccessible` and a search give: a non-admin needs a principal at every restricted level |
| A child could only name principals listed by its parent: `company` (`employees`) with `company/executive` (`managers`) was a `ValidationError` | Any principals are accepted. A public child of a restricted parent is still refused |
| `getEffectivePermissions` returned the intersection of the levels, empty meaning public, so disjoint levels read as public | It returns an error where the intersection is empty. Use `effectiveAccess`: `Public`, `Restricted(levels)` or `Nobody` |
| `create` or `ensureExists` of a child under a leaf made it a non-leaf, and its documents dropped out of every search; a failed child insert still demoted the parent | A leaf holding documents is refused a child (`ValidationError`), and the demotion and insert are one transaction. Search covers non-leaf collections, so documents an earlier version hid are found again |

`Collection.canQuery` checks only that collection's own `queryableBy`: call `CollectionStore.canQuery`, or
`effectiveAccess(path).map(_.permits(auth))`. Code that read an empty `getEffectivePermissions` as public should
match on `effectiveAccess` instead:

```scala
collections.effectiveAccess(path).map {
  case EffectiveAccess.Public             => "public"
  case EffectiveAccess.Restricted(levels) => levels.map(l => s"${l.path.value}: ${l.anyOf.size}").mkString(", ")
  case EffectiveAccess.Nobody             => "admins only"
}
```

To give children to a leaf that holds documents, clear it (`clearCollection`) and ingest those documents into a
child. A collection an earlier version made a non-leaf keeps its documents, which are searchable again, but it still
takes no new ones.

## A permission re-ingest replaces the document, and `dropSchema` deletes what was in a collection (#2988, #2992)

`llm4s-rag` is Beta. `SearchIndex` gains `moveDocument` (with a default) and `RAG` gains `moveWithPermissions`.

| Before (1.0.0) | After |
|---|---|
| `PgSearchIndex.ingest` (and `ingestWithPermissions`) of a document id already in the collection upserted by chunk id: chunks past the new count kept the old text and the old `readableBy` | The document's old chunks in that collection are deleted in the insert's transaction: a re-ingest replaces it |
| Ingesting the id into another collection left the old copy, with no way to move it | Still a second copy. `moveWithPermissions` / `SearchIndex.moveDocument` delete it from every other collection, in the same transaction for `PgSearchIndex` |
| `dropSchema` dropped the permission columns and tables and kept every row, which a migration then made public | It deletes every row in a collection first, in one transaction, keeping rows in none (a plain `PgVectorStore`'s). It refuses, changing nothing, while another vectors table holds rows in the shared collections |
| `runFullMigration(conn, table)` moved every row in no collection into a public `default`, with `readable_by` reset to `'{}'` | `runFullMigration(conn, table, into)` moves them into `into`, which must exist and be a leaf; `readable_by` is kept. A row `ingest` wrote that lost its collection is skipped |
| `MigrationStats(tablesCreated, indexesCreated, defaultCollectionId, vectorsMigrated)` | `MigrationStats(tablesCreated, indexesCreated, collectionId, vectorsMigrated, vectorsSkipped)` |

To keep migrating into the public `default` collection, create it and name it:

```scala
for {
  _     <- PgSchemaManager.initializeSchema(conn)
  _     <- PgSchemaManager.createDefaultCollection(conn)
  stats <- PgSchemaManager.runFullMigration(conn, "vectors", CollectionPath.unsafe("default"))
} yield stats
```

`migrateExistingVectors(conn, table, collectionId)` now refuses a collection that does not exist or is not a leaf.
A database `dropSchema` was run on before this release may still hold rows `ingest` wrote, with ids
`coll-<collectionId>-<docId>-chunk-<n>` and no permissions: no permission query returns them, the migration skips
them (`vectorsSkipped`), and a plain `PgVectorStore` on the table still reads them. Check them and delete them, for
example with `DELETE FROM vectors WHERE collection_id IS NULL AND id ~ '^coll-[0-9]+-'`.

`dropSchema` and the migration resolve each table name through `search_path`, as the index's own SQL does, and work
on the schema-qualified tables. On a connection with auto-commit off they run in a savepoint and leave the commit to
the caller.

## Keyword search replaces on re-index, reads free text, and survives a failing leg (#2994, #2995)

`llm4s-rag` is Beta. `LegFailurePolicy` is new; `HybridSearcher`, `AsyncHybridSearcher` and `RAGConfig` gain
`legFailurePolicy` and `withLegFailurePolicy`. `RAGConfig` has a new last field, so a positional pattern on it needs
one more `_`.

| Before (1.0.0) | After |
|---|---|
| Re-indexing an id in `SQLiteKeywordIndex` (`index`, `indexBatch`, `update`, and so every re-ingest of a changed document) kept the old full-text row: its old words still found the document | The full-text row is replaced. An index holding stale rows from before is rebuilt from its documents when opened, once |
| A query was FTS5 syntax as soon as it held a `"`: `he said "hi` failed, and a blank query failed | A query is free text. A balanced `"..."` is a phrase and a trailing `*` a prefix; open quotes, `NOT`, `:`, `^` and parentheses are searched as text. A blank query finds nothing |
| Every word was required, so most questions had no keyword hits; `OR` was searched as the word "or" | Every word is optional and BM25 ranks chunks holding more of them first. A bare `AND` or `OR` is dropped |
| A filter key was a JSON path: `a.b` matched nothing and keys with a space, `:` or `/` were refused with an error | A key is matched as written, as in `SQLiteVectorStore`, with bound parameters |
| One failing leg of an RRF or weighted search failed the whole search, including `RAG.query` and a permission query | The search answers from the other leg and logs a WARN. `withLegFailurePolicy(LegFailurePolicy.Fail)` restores the error. A `SearchIndex` without keyword search still fails a fused permission query as a configuration error |

A query that relied on every word being required, or on FTS5 operators, now finds more chunks: put the words that
must appear together in a phrase. Nothing is needed for stale full-text rows; opening the index repairs them.

## `SemanticMemoryMiddleware` bounds only what it wrote, and tenants never share storage (#3032, #3034)

`llm4s-agent-memory` is Experimental, `llm4s-memory` and `llm4s-memory-postgres` Beta. `MemoryStore` gains
`storageIdentity` (with a default), `MemoryBounds` gains an `owner` (`withOwner`, `counts`), and
`PostgresMemoryStore.fromDataSource` and `PostgresMemoryStore.Config` gain a `tenant`. A positional pattern on
`MemoryBounds` or `PostgresMemoryStore.Config` gains a `_` with `Llm4sMigrateFrom_1_0`.

| Before (1.0.0) | After |
|---|---|
| Every write of `SemanticMemoryMiddleware` evicted beyond `bounds` across the whole store, so attaching it to a store holding knowledge or facts deleted them | It marks what it writes with `llm4s.writer` = its id (`SemanticMemoryMiddleware.WriterKey`), and `bounds` counts and evicts only those. A `MemoryBounds` without `withOwner` still counts the whole store |
| A resolver giving two tenants two instances over one SQLite file, or one Postgres table, was accepted, and the tenants read each other's memories | It is refused on the second tenant's first turn, naming both tenants: a store with the same `storageIdentity` as another tenant's (a SQLite file's real path; a Postgres table and tenant) is the same storage |
| `PostgresMemoryStore` had no tenant: every store over a table read, counted, evicted and `clear()`ed every row, and `clear()` truncated the table | A store belongs to its `tenant` (`None` by default) and touches only that tenant's rows; `clear()` deletes them. Opening an older table adds a `tenant` column (`''`, the rows of `tenant = None`) and makes `(tenant, id)` its primary key, and `(tenant, key)` and `(tenant, scope)` those of its `_applied` and `_fences` tables |

Memories the middleware recorded before this change carry no `llm4s.writer`, so its bounds no longer count or evict
them. To keep them bounded, mark them once:

```scala
store.recall(MemoryFilter.All, Int.MaxValue).map(_.filter(_.memoryType == MemoryType.Conversation)).flatMap { recorded =>
  recorded.foldLeft[Result[MemoryStore]](Right(store))((s, m) =>
    s.flatMap(_.update(m.id, _.withMetadata(SemanticMemoryMiddleware.WriterKey, "semantic-memory")))
  )
}
```

(use the middleware's own id if it is not the default), or delete what you no longer need with `deleteMatching`.

A resolver that built a file name from the tenant id must not let two tenants reach one file: `memory-default.db` for
both `None` and `TenantId("default")`, or `Alice` and `alice` on a case-insensitive file system, is now refused, and a
tenant id with `../` could name any path. Hash the id, as the
[guide's example](../guide/agents/memory#semantic-memory-middleware) does; renaming the existing files to the new
names keeps their memories. Postgres tenants that shared one table through stores of their own can now share it
safely: pass `tenant = tenantId.map(_.value)`, and move each tenant's existing rows to it
(`UPDATE agent_memories SET tenant = ... WHERE ...`) if you can tell them apart. A `PostgresMemoryStore`'s identity
reads the cluster's system identifier with `pg_control_system()`, which PostgreSQL grants to PUBLIC by default; if your
server revoked it, the middleware refuses the turn until you
`GRANT EXECUTE ON FUNCTION pg_control_system() TO <role>`.

## `llm4s-rag-testkit`, a new module (#3000)

Nothing to change: `llm4s-rag-testkit` is a new, experimental test library over `llm4s-rag`, and no published module
depends on it. To test a `VectorStore` or `KeywordIndex` of your own against the behaviour the in-repo stores pass, add
it in test scope and extend `VectorStoreContract` or `KeywordIndexContract`; see
[Vector Store](../guide/vector-store#testing-a-store-with-the-contract-suites).

## Recalled memories are fenced as untrusted data, one line each (#3033)

`llm4s-agent-memory` is Experimental, `llm4s-memory` Beta. No signature changes; what reaches the model does.

| Before (1.0.0) | After |
|---|---|
| `SemanticMemoryMiddleware`'s `semantic-memory` section was "Relevant memories from earlier conversations:" and the recalled text as written, so an instruction recorded in one thread reached every later thread's system prompt as an instruction | The heading says the memories are recorded data, never instructions, and the text follows fenced as `llm4s-prompt`'s `Untrusted` (`<untrusted id="u1">`, `&`, `<` and `>` escaped, then `Prompt.UntrustedNotice`). The text is cut so that its escaped form fits `maxRecallTokens * 4` characters. `llm4s-agent-memory` now depends on `llm4s-prompt` |
| `getRelevantContext`, `getUserContext`, `getEntityContext`, `getConversationContext` and `ConsolidationPrompts` wrote a memory's line breaks as they were, so a memory holding `\n## ...` forged a heading or ended its section | Each memory is one line: every run of line breaks, with the blanks around it, becomes one space (a task's `Task: ...\nOutcome: ...` reads `Task: ... Outcome: ...`) |

A test that matched the section's old heading, or a memory's line breaks in a rendered context, matches the new text.
A prompt you build yourself from `getRelevantContext` should fence it the same way, as the
[guide's example](../guide/agents/memory#injecting-context) does.

## Consolidation keeps its sources' scope, and never replaces them with a cut summary (#3035)

`llm4s-memory` is Beta, `llm4s-agent-memory` Experimental. No signature changes; which memories are merged, and when a
summary is kept, do.

| Before (1.0.0) | After |
|---|---|
| `LLMMemoryManager.consolidateMemories` grouped entity facts by `entity_id`, knowledge by `source`, tasks by `success` and user facts by `user_id` (those with none together), across conversations; the summary kept the first source's value of each key, so `deleteMatching(MemoryFilter.ByConversation(c))` left `c`'s content inside a summary of another conversation | A group holds memories of one scope: every metadata value except `role`, `chunk_index`, `entity_name` and `entity_type` is equal, so the summary carries each source's `conversation_id`, `user_id`, `entity_id`, `source`, `llm4s.writer` and your own keys, and an erasure by any of them removes it. A user fact with no `user_id` is never consolidated. Groups are smaller, so fewer may reach `minCount` |
| A summary cut by its token limit, or cut at 2,000 characters, replaced up to 50 sources | A summary whose completion stopped for `FinishReason.Length` (or anything but `Stop` or `Unknown`), is empty, or is longer than `LLMMemoryManager.MaxSummaryChars` (2000) fails its group: the sources are kept, and `strictMode` returns the error |
| `original_ids` listed the first 10 sources | `original_ids` lists every memory the summary stands for, through earlier summaries, and `consolidation_version` is `2` (`ConsolidationProvenance` names the keys) |
| `SemanticMemoryMiddleware` stamped every summary with its own `llm4s.writer`, and cut it to `maxRecordChars` | A summary carries its sources' writer, so a summary of another writer's memories is not counted by `bounds`; a group mixing writers, or a summary longer than `maxRecordChars`, is refused and announced by `MemoryEvents.Skipped` |

A summary written by 1.0.0 has no `consolidation_version`. It reads back as before and is never consolidated again, but
its scope is its first source's, so it may hold another conversation's or user's content. Before relying on an
erasure, review or delete them:

```scala
import org.llm4s.agent.memory.{ ConsolidationProvenance, MemoryStore }

def dropLegacySummaries(store: MemoryStore) = store.deleteMatching(ConsolidationProvenance.legacySummaries)
```

## A directory that cannot be listed fails `sync`, and robots.txt follows RFC 9309 (#2998, #2999)

`llm4s-rag` is Beta. `CrawlerConfig` gains a last field, `maxCrawlDelay` (default 60 seconds), and
`withMaxCrawlDelay`; code built against 1.0.0 that calls its constructor, `copy` or `unapply` recompiles.

| Before (1.0.0) | After |
|---|---|
| `DirectoryLoader` read a directory it could not list (permissions, an I/O error) as empty, and skipped an entry whose real path could not be read, so `RAG.sync` deleted every indexed document under it | Either is a `LoadResult.ListingFailure`, after the documents that were listed: `sync` and `ingest` return its error and `sync` deletes nothing. `estimatedCount` is `None`. A link to a missing file is still skipped |
| A blank or comment line inside a robots.txt group ended it, dropping the rules after it, and a second group for the same agent was ignored | Blank and comment lines are ignored; a `User-agent` line after a rule starts a new group; every group for the agent (or every `*` group) is merged |
| Rules were matched against the path alone, so `Disallow: /*?` never applied | Rules are matched against the path and query, percent-encodings compared alike |
| A robots.txt answering 5xx, or that could not be fetched, allowed everything | It allows nothing: each page is a `Failure` with no `documentId` (so `sync` deletes nothing), and `getRules` returns `RobotsTxt.disallowAll`. A 4xx still allows everything |
| A `Crawl-delay` of any length was slept | It is capped at `maxCrawlDelay` |

A `sync` over a tree holding a directory the process cannot read now fails until the directory is readable or the
loader's root leaves it out. A crawl of a site whose robots.txt is failing now loads nothing from it; turn
`respectRobotsTxt` off only for a site you are allowed to crawl regardless.

## Secret providers refuse cleartext `http` and a JSON-object secret without a field, and share a failed read (#3102)

Three behaviour changes to `llm4s-secrets-vault` and `llm4s-secrets-aws`, both experimental. No signature changed;
`VaultSettings` and `AwsSecretsManagerSettings` gain `withCleartext`.

| Before (1.0.0) | After |
|---|---|
| `VaultSettings("http://vault.internal:8200", ...)` and `withEndpoint("http://...")` were accepted for any host | A `ConfigurationError` unless the host is loopback (`localhost`, `127.0.0.0/8`, `::1`). Use `https`, or add `.withCleartext(true)` to send the token or credentials unencrypted on purpose |
| `https://user:password@vault...` was accepted and printed by `toString` | A `ConfigurationError`; the HTTP client never used the user info. Remove it |
| An AWS secret whose `SecretString` is a JSON object, read without `withField`, was sent whole as the API key | A `ConfigurationError` naming the object's keys. Add `.withField("apiKey")` with the key that holds the API key |
| A failed read was tried again by the very next `current()` | The failure is returned to every caller for 1 second (a retryable error) or 5 seconds (any other), then the next call reads again. A caller interrupted while waiting gets a `CancelledError` |

## The workspace runner needs a token, and its container is published on loopback only (#3046, #3053)

`llm4s-workspace-client` and the workspace-runner image are experimental. Use the client and the image from the same
release: an older client cannot open this image's WebSocket, and an older image, which this client still drives, has
none of the protections. With `ContainerisedWorkspace`, nothing changes in your code.

| Before (1.0.0) | After |
|---|---|
| `docker run ... -p 8080:8080` published the runner on every host interface | `ContainerisedWorkspace` publishes `-p 127.0.0.1:<hostPort>:8080`. A runner started by hand should do the same |
| The runner's `/ws` accepted any connection | The runner does not start without `LLM4S_RUNNER_TOKEN` (32 characters or more), and refuses a handshake without `Authorization: Bearer <token>` (401) or with an `Origin` header (403). `ContainerisedWorkspace` makes a random token per container |
| A container ran on after its client's JVM died | It starts with `--rm --init`, a shutdown hook removes it, and the runner exits after `LLM4S_RUNNER_IDLE_TIMEOUT_SECONDS` (default 300, `0` never) with no client connected. `ContainerisedWorkspace` gains `idleTimeout` (last parameter, default 5 minutes) |
| A dropped connection made every later call throw `RuntimeException("WebSocket is not connected ...")` | The client reconnects. A call fails with `WorkspaceAgentException`: `CONNECTION_CLOSED` for a change or command in flight, `WORKSPACE_UNAVAILABLE` once reconnection failed (the container is then removed), `WORKSPACE_NOT_RUNNING` before start or after stop |
| `stopContainer()` skipped `docker rm` when `docker stop` failed | It always runs `docker rm -f`. `ContainerisedWorkspace` is `AutoCloseable`, so `Using.resource(workspace)` needs no `Releasable` |

To run the image by hand, see [Workspace sandbox](workspace-sandbox#example-locked-down-sandbox).

## `MCPServer` refuses rebound and cross-origin requests, and speaks strict JSON-RPC (#3069, #3071)

`llm4s-mcp` is Beta. Clients that call an `MCPServer` on `localhost` with `Content-Type: application/json`, which
includes llm4s's own client, need no changes. Check the table if a browser, a proxy or a hand-written client reaches it.

| Before (1.0.0) | After |
|---|---|
| Any `Host` and `Origin` were served | A server bound to a loopback `host` refuses (403) a `Host` that is not `localhost`, `127.x.x.x` or `[::1]`. Any server refuses an `Origin` that is not loopback. Name the others in the new `MCPServerOptions.allowedHosts` and `allowedOrigins` |
| A POST of any `Content-Type` was read | A POST must declare `application/json` (415 otherwise) |
| A notification was answered 200 | 202 with no body, as is a JSON-RPC response a client posts |
| Malformed JSON, or an object that is not a JSON-RPC request, was answered 200 with a parse error and id `"unknown"` | 400 with `-32700` (parse error) or `-32600` (invalid request). The id is `null` when it cannot be read. A batch is `-32600` |
| Replies had no `jsonrpc`, a numeric id came back as a string, and `capabilities` was `{}` | `"jsonrpc":"2.0"` on every reply, the id as it was sent, and `capabilities` is `{"tools":{"listChanged":false}}` |

```scala
// A server reached as https://mcp.example.com from a browser app at https://app.example.com
MCPServerOptions(8080, "/mcp", "tools", "1.0", apiKey = Some(key), host = "0.0.0.0",
  allowedHosts = Set("mcp.example.com"), allowedOrigins = Set("https://app.example.com"))
```

## A stdio MCP server inherits only an allowlisted environment (#3070)

`llm4s-mcp` is Beta. A server started by `MCPServerConfig.stdio` used to receive every variable of the JVM,
`OPENAI_API_KEY` and cloud credentials included. Now it gets the variables on an allowlist (`PATH`, `HOME`, `USER`,
`LOGNAME`, `SHELL`, `TERM`, `LANG`, `LC_ALL`, `LC_CTYPE`, `TMPDIR`, `TZ`, and on Windows `APPDATA`, `LOCALAPPDATA`,
`HOMEDRIVE`, `HOMEPATH`, `USERNAME`, `USERPROFILE`, `SYSTEMDRIVE`, `SYSTEMROOT`, `WINDIR`, `COMSPEC`, `PATHEXT`,
`PROCESSOR_ARCHITECTURE`, `PROGRAMFILES`, `TEMP`, `TMP`) plus those you declare. A server that needs a key has to be
given it:

```scala
MCPServerConfig.stdio("github", Seq("github-mcp-server", "stdio"),
  env = Map("GITHUB_PERSONAL_ACCESS_TOKEN" -> token), workingDirectory = Some(Paths.get("/srv/mcp")))
// the old behaviour, for a server you trust with every secret: inheritEnvironment = true
```

`StdioTransport` gains `env`, `workingDirectory` and `inheritEnvironment`, all with defaults, so a pattern
`case StdioTransport(command, name)` becomes `case StdioTransport(command, name, _, _, _)` (or `case t: StdioTransport`).
`MCPToolRegistry()` and `MCPToolRegistry.mcpServerConfig`, which ran the unpinned `@playwright/mcp@latest`, are
deprecated: build the config yourself with a pinned version, as the `PlaywrightExample` sample does.

## A run queue sees only the submitter's tenant's entries on a thread (#3085)

The run queue (`RunQueue`, `RunSubmitter`, the in-memory, SQLite and PostgreSQL queues) is experimental. No signature
changed, and the stored schema is the same. Entries on one thread id from different tenants no longer affect each
other:

| Before (1.0.0) | After |
|---|---|
| A `Reject` submission was refused with `ThreadBusy` when any tenant had an open entry on the thread | Only an open entry (or a live claim) of the submitter's own tenant refuses it; another tenant's is never reported |
| `Ticket.position` and `QueueEntry.position` counted every tenant's entries | They count only the entry's own tenant's entries ahead of it, so `0` means "next of this tenant's" |
| An `Interrupt` entry went ahead of every unstarted entry of the thread, whoever's | It goes ahead of its own tenant's entries only. Tenants are served in the order of their oldest open entry, and an entry that has started still runs first |
| `RunSubmitter.submit` queued a submission to any thread id | A thread whose latest checkpoint is another tenant's is refused with `GraphError.TenantMismatch`, naming only the submitter's tenant. A thread with no checkpoint yet is queued, as before |

A custom `RunQueue` must follow the same rules. The new `RunQueueContract` cases in `llm4s-agent-testkit` check them.

## A retention sweep page always makes progress, and skips a thread it cannot prune (#3086)

`Checkpointer.sweep`, `SweepPage` and the SQLite and PostgreSQL stores are experimental. No signature changed, and the
stored schema is the same.

| Before (1.0.0) | After |
|---|---|
| A page was pruned in one transaction (one `BEGIN IMMEDIATE` on SQLite), so a large backlog made it time out and roll back on every pass, and on SQLite held up live runs' commits | Each thread is pruned in transactions of its own, each removing at most 1,000 rows: events first, raising the events' floor as it goes, then checkpoints. A replay from a checkpoint about to be pruned may be refused with `ReplayUnavailable` before that checkpoint is removed |
| One thread whose prune failed failed the whole page, and every later thread went unswept | The thread is logged at WARN and skipped, and the page still returns its `next`. Only a `CancelledError`, or a closed store, fails the call |
| A page ran until it was done | Once the store's bound has passed (`callTimeout`, SQLite's `busyTimeout`), the page stops after its first thread, and its `next` is the last thread it finished. A caller that pages until `next` is `None` needs no change |
| `AgentWorker` reclaimed closed queue entries only after the whole sweep succeeded | It reclaims them after every pass, and still reports the sweep's error |

A custom `Checkpointer` should do the same. The new `CheckpointerContract` cases in `llm4s-agent-testkit` check it. A
store that can make one thread's prune fail overrides `refuseDeletes`, and one whose sweep has a budget overrides
`shortenSweepBudget`, so those cases run.

## The run queue closes what no worker can run, and bounds what a thread holds (#3091, #3093, #3094, #3095, #3096)

The run queue (`RunQueue`, `RunSubmitter`, `AgentWorker`, the in-memory, SQLite and PostgreSQL queues and
`EncryptingRunQueue`) is experimental. The stored schema is the same.

| Before (1.0.0) | After |
|---|---|
| An entry whose stored form did not decode failed every `take` of its agents, and `entry`, `openEntries` and `cancel` with it; the in-memory queue threw | It is read back with `QueuedInput.Unreadable` in place of its input (a new case: a `match` on `QueuedInput` needs it), and a `take` that reaches it closes it `Failed(Unrunnable)` and takes the next. `EncryptingRunQueue` does the same for a ciphertext that does not open; an entry whose key is missing still fails the take |
| A worker that could not read an entry's thread let its lease lapse, recording nothing, for ever | Each failed read records an attempt, so the entry is dead-lettered (`AttemptsExhausted`) after `maxAttempts` |
| An entry of an agent no worker serves stayed open for ever | The worker's sweep closes a thread's head that no worker has taken within `unrunnableAfter` of its submission `Failed(Unrunnable)`, unless a live claim holds its thread. A backlog that keeps every worker busy for that long closes heads too: raise `unrunnableAfter` if yours can |
| Cancelling an unstarted `Interrupt` entry left the running entry asked to stop | Closing it withdraws the stop |
| A thread could hold any number of entries of any size | A submission past `llm4s.agent.queue.maxOpenPerThread` (1,000) open entries of its tenant on the thread, or with an input longer than `maxInputBytes` (4 MiB), is refused with `QueueError.ThreadQueueFull` or `QueueError.InputTooLarge` |
| A worker passing over more than 256 heads kept taking the ones it could not send in `skip` | `TakeRequest.after` (a new field) lets it go on past them; the PostgreSQL `take` locks only the row it tries, and its `awaitClosed` waits on `LISTEN`/`NOTIFY` |

A custom `RunQueue` implements two new methods, `allDepths()` and `closeUntaken(age, limit)`, and honours
`TakeRequest.after` and the submission's bounds; a store that can write an entry it cannot read overrides
`RunQueueContract.spoilInput`, so the new contract case runs. `Submission`, `RunQueueSettings` and `TakeRequest` gain
fields with defaults: `apply`, `of` and the setters keep compiling.

## The Whisper engine writes into a temporary directory, and refuses subtitle formats (#3138)

`llm4s-speech` is Beta. No signature changed; `WhisperSpeechToText` behaves differently:

| Before (1.0.0) | After |
|---|---|
| The CLI ran without `--output_dir`, so it wrote `<stem>.txt` into the working directory, overwriting a file of that name | Each call passes `--output_dir <fresh temporary directory>` and deletes it afterwards. A wrapper script given as `command` must accept `--output_dir` and `--verbose False` |
| When no transcript file was found, stdout (the verbose progress log) was returned as the transcript | A `ProcessingError` saying no transcript was written |
| `outputFormat = "srt"`, `"vtt"` or `"tsv"` returned cue numbers and times as the text | `STTError.InvalidInput`: use `txt` or `json` |
| `language = Some("en-US")` was passed verbatim and the CLI exited 2 | `--language en`; the transcription still reports `en-US` |
| A failed run said only "non-zero exit code" | The message has the exit code and the end of stderr |

## Image reads have size limits, and operation ranges are checked (#3139)

`llm4s-image` is Beta. `LocalImageProcessor` gains two constructor parameters with defaults, so `new LocalImageProcessor()`
still compiles; what it accepts changes:

| Before (1.0.0) | After |
|---|---|
| Any image was fully decoded, so a small file declaring 50000 x 50000 pixels exhausted the heap | `InvalidInputError` for a file over 50 MiB or more than 50 million pixels, checked from the header; `new LocalImageProcessor(maxPixels = ..., maxFileBytes = ...)` changes the limits |
| `analyzeImage` decoded the whole image for its metadata | It reads the header only |
| The vision clients uploaded a file of any size | A file over 20 MiB is refused before the request |
| `Brightness` and `Contrast` outside -100 to 100 were applied (`Contrast(259)` divided by zero) | `InvalidInputError` |
| `Blur` took any radius, in time growing with its square | A radius from 0 to 100, in time proportional to the image |
| A resize to a huge size, a side truncated to 0 or a writer failure threw | A `Left`; a fitted side is at least 1 pixel |

## Saving an image never overwrites by default, and `GeneratedImage.bytes` replaces `asBytes` (#3140)

`llm4s-image` is Beta. Both `saveToFile` methods gain `overwrite: Boolean = false`, so existing calls compile:

| Before (1.0.0) | After |
|---|---|
| `GeneratedImage.saveToFile` wrote a 0-byte file for a URL-only image and returned `Right` | `Left(ImageValidationError)` naming the URL; nothing is written. Download `url` yourself |
| An OpenAI response entry with neither `b64_json` nor `url` became an empty image | `Left(ImageValidationError)` from the client |
| Both `saveToFile`s truncated an existing file in place, the source image included | A `Left` (`ImageValidationError`, or `InvalidInputError` for `ProcessedImage`) unless `overwrite = true`; the write goes to a temporary file that is then moved into place |
| `asBytes` threw `IllegalArgumentException` on invalid base64 | Deprecated: use `bytes`, an `Either[ImageGenerationError, Array[Byte]]` |

## File tools refuse name aliases and firmlinked paths, and `VectorMemoryStore` names its file (#3236, #3237, #3238)

`HostFileBackend` and the file tools of `llm4s-agent` are experimental; `llm4s-agent-tools` and `llm4s-memory` are Beta.
No signature changed and no stored format changed; what is refused is new:

| Before (1.0.0) | After |
|---|---|
| `HostFileBackend` accepted a path whose existing components matched the on-disk names after case folding and Unicode NFC, so on APFS or NTFS `/agents.md` reached `/AGENTS.md` and side-stepped a permission rule written for `/AGENTS.md` (#3236) | An existing component must be spelled exactly as the volume spells it: another case or normalisation is refused ("names an entry by a name that is not its own"), as an 8.3 short name already was. Use the name a listing shows |
| `PathPolicy` compared blocked entries by spelling and real path only, so on macOS every default blocked path was readable through its `/System/Volumes/Data/...` firmlink spelling (#3237) | A path with an existing ancestor whose file key is a blocked entry's is blocked, so the firmlink spelling of a blocked directory is blocked too. Where the platform reports file keys (not Windows), each check reads the attributes of the path's existing ancestors |
| `VectorMemoryStore.storageIdentity` was `None`, so `SemanticMemoryMiddleware` gave two tenants two instances over one file (#3238) | It names the file's real path, as `SQLiteMemoryStore` does: a resolver that maps two tenants to one file is refused |

## Guardrails see and keep every rewrite, and fail closed (#3231, #3232, #3233, #3234, #3235)

The guardrails and the streaming path are experimental, and `Agent.run`'s history import keeps its `@Stable`
signature. No signature changed and the stored format is the same; what changed is what passes:

| Before (1.0.0) | After |
|---|---|
| `CompositeGuardrail.all` ran every guardrail on the original value and returned it, so a masking guardrail inside it masked nothing (#3232) | Each guardrail sees the value as the one before it rewrote it, and `all` returns the last rewrite. A failing guardrail leaves the value unchanged for the next, and every failure is still reported |
| `PromptInjectionDetector`'s `how_to_dangerous`, `creative_writing`, `ignore_instructions`, `forget_instructions`, `disregard`, `sql_injection` and `script_tags` patterns could take cubic or quadratic time on crafted input, at admission (#3233) | They match in linear time, and every pattern's match is step-bounded: one that exhausts its budget (ten million reads plus 100 per character) counts as a match, under the detector's `onFail`. `script_tags` matches an event-handler attribute only at the start of a word (`onclick=`, not `money=`) |
| `GroundingGuardrail`, `ContextRelevanceGuardrail`, `SourceAttributionGuardrail` and `TopicBoundaryGuardrail` read a score by stripping every non-digit and clamping, and fell back to a number anywhere in the reply, so `SCORE: 2/10` read as 210 and passed (#3234) | Each score is read as `LLMGuardrail` reads one: a plain number from 0 to 1. A scaled, unreadable or missing score is a `Left` (a missing `ATTRIBUTION_SCORE` or `RELEVANCE_SCORE` no longer defaults to 0.5 or 0.7), and so is a reply without its `SCORE` and `GROUNDED` lines. `GroundingGuardrail` with `onFail = Fix` blocks a context with no chunks, as it blocks a low score |

## An error's `provider` is a `ProviderId` (#2936)

The provider an error names was the last provider id of the Stable API still a raw `String`. It is a `ProviderId` on
the nine errors that carry one: `AuthenticationError`, `RateLimitError`, `ServiceError`, `CircuitOpenError`,
`EmbeddingError`, `ContentFilteredError`, `ContextLengthError`, `ModelNotFoundError` and
`UnsupportedContentPartError`. Their factories take it, their `provider` field returns it, and their extractors bind
it.

| Before (0.7.0) | After |
|---|---|
| `RateLimitError("openai")`, `RateLimitError.local("openai")` | `RateLimitError(ProviderId("openai"))`, `RateLimitError.local(ProviderId("openai"))` |
| `AuthenticationError("openai", details)` | `AuthenticationError(ProviderId("openai"), details)` |
| `ServiceError(503, "openai", details)` | `ServiceError(503, ProviderId("openai"), details)` |
| `CircuitOpenError.open("openai", remaining)`, `probing("openai")` | `CircuitOpenError.open(ProviderId("openai"), remaining)`, `probing(ProviderId("openai"))` |
| `EmbeddingError(code, message, "voyage")` | `EmbeddingError(code, message, ProviderId("voyage"))` |
| `ContentFilteredError.prompt("gemini", reason)`, `reply(...)` | `ContentFilteredError.prompt(ProviderId("gemini"), reason)` |
| `ContextLengthError("openai", details)`, `ModelNotFoundError(...)`, `UnsupportedContentPartError(...)` | the same, with `ProviderId("openai")` |
| `error.provider == "openai"`, `error.provider.toUpperCase` | `error.provider == ProviderId("openai")`, or `error.provider.asString` where a `String` is wanted |
| `case ServiceError(_, _, "openai", _, _)` | `case ServiceError(_, _, p, _, _) if p == ProviderId("openai")` |
| `HttpErrorMapper.toError(status, body, "acme", headers)`, `mapStreamError(status, message, "acme")` | `HttpErrorMapper.toError(status, body, provider, headers)`, `mapStreamError(status, message, provider)`: the client's own `ProviderId` |
| `PartEncoding.check("acme", messages, user, tool)`, `encodeAll("acme", role, parts)(encode)` | `PartEncoding.check(provider, messages, user, tool)`, `encodeAll(provider, role, parts)(encode)` |
| `response.ensureSuccess("acme")`, `result.mapServiceError("acme", message)` | `response.ensureSuccess(ProviderId("acme"))`, `result.mapServiceError(ProviderId("acme"), message)` |

`import org.llm4s.types.ProviderModelTypes.ProviderId`. A client built on `BaseLifecycleLLMClient` passes its own
`provider`, to an error's factory and to the helpers that build one (#2939): `HttpErrorMapper.toError` and
`mapStreamError`, `PartEncoding.check` and `encodeAll`, and `HttpResponse`'s `ensureSuccess` and `mapServiceError`
take a `ProviderId` where they took a `String` label, so `provider.asString` there is `provider` again. The Scalafix
rule `ErrorProviderIdTyped` ([Scalafix](scalafix#migration-rules)), which every umbrella runs, wraps the argument of
each factory and helper (an argument written `id.asString` loses the `.asString` instead) and adds `.asString` to a
read of `error.provider`; a literal in a pattern is left for the compiler to report.

What stays a `String`: an error's `message` and its `context("provider")` entry, pinned by `ErrorProviderTextSpec`,
which was written against the old code; the labels of `MetricsCollector` and `ReliableClient`'s `providerName`; and
the Java and Kotlin view, where an opaque type is its `String` (`error.provider()` returns a `String`,
and `RateLimitError.apply("openai")` still compiles). The errors are not serialised, so no stored JSON changes. The
break is in Scala source only: an opaque type erases to its `String`, the binary signatures are those of 0.7.0, and
MiMa needs no filter.

A `ProviderId` is canonical: trimmed and in lower case. Two things read differently for that:

- An error built with a name that was not canonical carries the canonical one, in `provider`, in its message and in
  `context`; so does one a helper builds, and the default detail `HttpErrorMapper` writes for a body it cannot read
  (`acme API error (HTTP 500)`).
  llm4s itself did so in two places, which now name the provider's id: a `ContentFilteredError` from the
  Anthropic, Bedrock, Gemini, Vertex AI, OpenAI and Azure OpenAI clients (`gemini blocked the prompt: ...`, where it
  was `Gemini`; `vertexai`, where it was `Vertex AI`; `azure`, where it was `Azure OpenAI`), and the `ServiceError`
  of the three vision clients in `llm4s-image` (`Service error from openai: ...`).
- `ReliableClient` still labels its metrics with the `providerName` it was given, as given. Its own
  `CircuitOpenError` and local `RateLimitError` carry `ProviderId(providerName)`, so a name with capitals or
  surrounding spaces is lower-cased and trimmed in those two errors and their messages.

## A cut-off answer ends `Truncated`, Anthropic's `max_tokens` default, and reported penalties (#2933)

Three behaviour changes after 0.7.0. No signature changed.

**A `withOutput` agent's cut-off answer is `Truncated`, not `OutputInvalid`.** An answer the output limit cut off
(`FinishReason.Length`) is no longer checked against the schema or retried: the turn ends
`Right(AgentStatus.Truncated(partialAnswer))`, with the partial text stored in the thread, where 0.7.0 spent the
corrective call on it and returned `Left(GraphError.OutputInvalid(...))` with the turn removed. Code that handled
truncation by matching `OutputInvalid` matches the status instead:

```scala
agent.run(query).map(_.status) match
  case Right(AgentStatus.Truncated(partial)) => // cut off: raise maxTokens, or lower the reasoning effort
  case Left(GraphError.OutputInvalid(errors)) => // a complete answer that did not match the schema, twice
  case other                                  => // ...
```

**A reply cut off before any text is `Truncated("")`.** Any agent, with or without `withOutput`, whose model spent
its whole output allowance on thinking ends the turn `Truncated` with an empty `partialAnswer`, and stores nothing
for the reply; 0.7.0 failed the run with `Left(ValidationError)` ("assistant message content cannot be empty"). The
Java facade's `partialAnswer()` is then present and empty.

**Anthropic's default `max_tokens` follows the model.** With no `CompletionOptions.maxTokens`, `llm4s-anthropic`
sent `max_tokens: 2048`. It now sends the model's maximum output tokens on a streamed call (128,000 for the current
models), the same capped at 16,000 on a non-streamed call, and 8,192 for a model the registry does not know
([Anthropic](../guide/providers#sampling-parameters-thinking-and-output-length)). Answers that were cut off at 2048
now complete, so a call can cost and take more than it did; a `reasoning` level's thinking budget, which is fitted
below `max_tokens`, is no longer cut to 2047. Set `withMaxTokens(2048)` to keep the old limit.

The OpenRouter client sends no `max_tokens` when `maxTokens` is unset, but fitted a Claude model's thinking budget
as if it had sent 2048: `ReasoningEffort.High` asked for 2,047 thinking tokens, not 32,768. The budget is now fitted
below the model's maximum output tokens when the registry states them, and otherwise below 32,000, the smallest
maximum output of a Claude model that thinks (`High`, `XHigh` and `Max` then send 31,999, reported `Adjusted`; set
`maxTokens` to send more). So a `reasoning` level or a `Thinking.Budget` above 2,047 thinks, and can cost, more. `withThinking` with a budget of 2,047 keeps the
old one; `withMaxTokens(2048)` does too, and also sends `max_tokens: 2048`.

**A penalty a provider does not take is reported.** `presencePenalty` and `frequencyPenalty` were left out without a
word by the Anthropic, Gemini, Vertex AI, Bedrock and Ollama clients. The first four now report each one set as
`RequestAdjustment.Dropped` on `Completion.adjustments` (and so on `ModelCallCompleted.adjustments` and in the
adjustment log); Ollama sends them, as `options.presence_penalty` and `options.frequency_penalty`. A provider module
of your own that drops them silently now fails `assertModelCallContract`: report them, or send them.

## `LLMClient` accessors are named for their values, and `ToolBuilder.build` (#2908)

A method that returns a value is named for the value. The last `getX` accessors of the Stable API lose the `get`, and
the two that take no argument lose their parentheses with it. `ToolBuilder` has one method that builds, so it is
`build`; it still returns a `Result`.

| Before | After |
|---|---|
| `client.getContextWindow()` | `client.contextWindow` |
| `client.getReserveCompletion()` | `client.reserveCompletion` |
| `client.getContextBudget(headroom)` | `client.contextBudget(headroom)` |
| `counter.getTokenBreakdown(conversation)` (`ConversationTokenCounter`) | `counter.tokenBreakdown(conversation)` |
| `ModelDimensionRegistry.getDimension(provider, model)` | `ModelDimensionRegistry.dimension(provider, model)` |
| `ToolBuilder(...).withHandler(...).buildSafe()` | `.build()` |

An `LLMClient` of your own renames its two definitions:

```scala
// before
override def getContextWindow(): Int     = 128000
override def getReserveCompletion(): Int = 4096

// after
override def contextWindow: Int     = 128000
override def reserveCompletion: Int = 4096
```

A client whose constructor has a parameter of the new name defines it twice: make the parameter the member
(`class MyClient(override val contextWindow: Int, ...) extends LLMClient`) and delete the method. From Java and
Kotlin the calls are `client.contextWindow()` and `client.reserveCompletion()`, and a Kotlin client overrides
`fun contextWindow(): Int`. The Java facade's own getters are unchanged.

The Scalafix rule `AccessorRenames` ([Scalafix](scalafix#migration-rules)) rewrites the calls, and the definitions
in a class that extends something; `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6` run it.

## `ThinkingBlock.Opaque` and `BaseLifecycleLLMClient` take a `ProviderId` (#2908)

The last provider ids that were raw strings in the Stable API are `ProviderId`s.

| Before | After |
|---|---|
| `ThinkingBlock.Opaque(provider: String, data)` | `ThinkingBlock.Opaque(provider: ProviderId, data)`: `ThinkingBlock.Opaque(ProviderId("openrouter"), data)` |
| `case ThinkingBlock.Opaque("openrouter", data)` | `case ThinkingBlock.Opaque(p, data) if p == ProviderId("openrouter")`, or match a `val` that holds the id |
| `BaseLifecycleLLMClient`: `protected def providerName: String` | `protected def provider: ProviderId` |
| `MetricsRecording.withMetrics(provider: String, model: String, ...)` | `withMetrics(provider: ProviderId, model: ModelName, ...)` |

Stored JSON is unchanged: an opaque block is still `{"type":"opaque","provider":"openrouter","data":"..."}`, pinned
by `ThinkingBlockWireFormatSpec`, which was written against the old code. A `ProviderId` is canonical (trimmed and
lower-cased), so a block stored with a provider spelt otherwise reads back with the canonical spelling; every llm4s
client already writes its id that way.

A provider client built on `BaseLifecycleLLMClient` replaces its `providerName`:

```scala
// before
protected def providerName: String = "acme"

// after
protected def provider: ProviderId = ProviderId("acme")
```

The client's `providerId`, its metrics label and the provider a `CancelledError` names all come from it; the label a
`MetricsCollector` receives is still a `String`, `provider.asString`. Where the client passes its name to an
error builder (`HttpErrorMapper.toError`) or an error's own factory (`ServiceError(status, provider, details)`),
pass `provider`: both take the `ProviderId` ([note](#an-errors-provider-is-a-providerid-2936)).

## Errors, `Result` helpers and `AgentResult.messages` are `Seq`s (#2908)

The collections that [message parts, citations and adjustments](#message-parts-citations-and-adjustments-are-seqs-2809)
left as `List` or `Vector` in Stable signatures are `Seq`s too.

| Before | After |
|---|---|
| `ValidationError.violations: List[String]`; `ValidationError(field, violations: List[String])`, `withViolations(List[...])` | `Seq[String]` |
| `ConfigurationError.missingKeys: List[String]`; `ConfigurationError(message, missingKeys: List[String])` | `Seq[String]` |
| `ToolParameterError.MissingParameter.availableParameters`, `ToolParameterError.MultipleErrors.errors`, `ToolCallError.InvalidArguments.parameterErrors`: `List` | `Seq` |
| `ToolCallErrorJson.flattenErrors(List[...]): List[...]` | `Seq[...]` in and out |
| `Result.sequence(List[Result[A]]): Result[List[A]]`, `Result.traverse(List[A])(f): Result[List[B]]` | `Seq` in, `Result[Seq[...]]` out |
| `Result.validateAll(List[A])(v): Either[List[LLMError], List[A]]` | `Either[Seq[LLMError], Seq[A]]` |
| `AgentResult.messages: Vector[Message]` | `Seq[Message]` |
| `OpenAICompatibleClient.parseCitations(...): List[Citation]` | `Seq[Citation]`: change the return type of an override |

Code that passes a `List` or `Vector` compiles unchanged. Code that reads one of these as a `List` or `Vector`
(`val all: Result[List[Int]] = Result.traverse(...)`, `case ConfigurationError(_, List(key))`, a `::` pattern on
`parameterErrors`) declares `Seq`, matches `case Seq(key)`, or calls `.toList` / `.toVector`. None of these types is
stored, so no wire format changes.

## `CachedEmbeddingClient`'s key function takes the request (#2908)

`CachedEmbeddingClient`'s `keyGenerator` was a `(String, ModelName, InputPurpose) => String`, a shape that could not
be given anything more of the request without breaking every key function. It is now
`(String, EmbeddingRequest) => String`: one input text and the request it came in.

```scala
// before
def tenantKey(tenant: String)(text: String, model: ModelName, purpose: InputPurpose): String =
  CacheKeyGenerator.sha256(tenant, model.asString, purpose.toString, text)

// after
def tenantKey(tenant: String)(text: String, request: EmbeddingRequest): String =
  CacheKeyGenerator.sha256(tenant, request.model.name.asString, request.purpose.toString, text)
```

The default is still `CacheKeyGenerator.embeddingKey`, which gains an overload of that shape,
`embeddingKey(text, request)`, and produces the same keys as before: an existing cache stays valid.
`embeddingKey(text, model, purpose)` is unchanged.

## One retry rule: `LLMError.isRetryable` replaces the recoverable markers (#2916)

"Can this be retried?" had three answers: the `RecoverableError` marker (`LLMError.isRecoverable`), which was `true`
for a `ServiceError` whatever its status, a 404 included; `ServiceError.isRecoverableStatus`; and
`RetryPolicy.isRetryable`, the only one the library's own retries used. There is now one: the member
`LLMError.isRetryable`, `true` only when sending the identical request again may succeed.

| Before | After |
|---|---|
| `LLMError.isRecoverable(error)` | `error.isRetryable` |
| `case e: RecoverableError => ...` | `case e if e.isRetryable => ...` |
| `LLMError.recoverableErrors(errors)`, `nonRecoverableErrors(errors)` | `errors.filter(_.isRetryable)`, `errors.filterNot(_.isRetryable)` |
| `serviceError.isRecoverableStatus` | `serviceError.isRetryable` |
| `class MyError(...) extends LLMError with NonRecoverableError` | `class MyError(...) extends LLMError` |
| `class MyError(...) extends LLMError with RecoverableError` | `class MyError(...) extends LLMError { override def isRetryable: Boolean = true }` |
| `RetryPolicy.custom(n, delayFn)` (retried `RetryPolicy`'s rule) | unchanged: it retries `error.isRetryable` |

`RecoverableError`, `NonRecoverableError`, `LLMError.isRecoverable`, `recoverableErrors`, `nonRecoverableErrors` and
`ServiceError.isRecoverableStatus` are removed. Scalafix's `ErrorIsRetryable` rewrites the calls; a marker on an error
type of your own is a hand edit, as the table shows.

What the library retries is unchanged: `RetryPolicy.isRetryable(error)` is `error.isRetryable` unless the policy is
`noRetry` or a `custom` one with its own predicate. What changes is the answer `isRecoverable` used to give for the
errors that were "recoverable" but never retried:

- a `ServiceError` with a status other than 408, 429 or 5xx (a 404, a 409, a 422) is not retryable;
- an `OptimisticLockFailure` and a `CircuitOpenError` are not retryable: each needs something to happen first (a
  re-read, a wait). Match on the type to handle them, as `AgentWorker` does to give a queue entry back;
- an `EmbeddingError`, which carried no marker, is retryable when its `code` is the status 408, 429 or 5xx.

## Every `LLMError` reports `httpStatus`, `retryAfter` and `requestId` (#2916)

The HTTP status of a provider's response, the delay it asked for and its request id were fields of some error types and
lost by others: a 400 became a `ValidationError` with no status. They are members of `LLMError` now, `None` when the
error is not a response or the response did not say:

```scala
result.left.foreach { error =>
  error.httpStatus // Option[Int]: 400, 401, 404, 429, 503 ...
  error.retryAfter // Option[FiniteDuration]: the server's Retry-After, never a default
  error.requestId  // Option[String]: x-request-id, request-id, x-amzn-requestid or apim-request-id
}
```

`HttpErrorMapper` fills them in for every status, and the OpenAI and Anthropic clients read them from their SDK's
exception. Three names moved to make room:

| Before | After |
|---|---|
| `serviceError.httpStatus` (an `Int`) | `serviceError.status`; `httpStatus` is `Some(status)` |
| `ServiceError(httpStatus = 503, provider = "p", details = "d")` | `ServiceError(status = 503, provider = "p", details = "d")` |
| `circuitOpenError.retryAfter` (a `FiniteDuration`) | `circuitOpenError.remaining`; `retryAfter` is `None`, since no server asked for the wait |
| `rateLimitError.retryDelay` | `rateLimitError.retryAfter.orElse(Some(RetryPolicy.DefaultRateLimitDelay))` |
| `serviceError.retryDelay` | `serviceError.retryAfter`; the policy's backoff is the wait without one |
| `RateLimitError.DefaultRetryDelay` | `RetryPolicy.DefaultRateLimitDelay` |
| `ServiceError.DefaultRetryDelay` | removed: nothing used it but `retryDelay` |

Scalafix's `ErrorIsRetryable` renames `serviceError.httpStatus` and `circuitOpenError.retryAfter`; the compiler
reports a `ServiceError.httpStatus` it missed, because an `Option[Int]` does not compare with an `Int`.

`retryAfter` is now only what the server said. The 30 seconds `RateLimitError.retryDelay` reported when the provider
gave no `Retry-After` is the retry policy's default (`RetryPolicy.DefaultRateLimitDelay`), which `ReliableClient`
still waits; a `CircuitOpenError`'s context key `retryAfter` is `remaining`.

An error mapped from a response is no longer equal to one you build without the response's facts:
`HttpErrorMapper.toError(400, body, ProviderId("p"), headers)` is `ValidationError("request", details).withHttpStatus(400)`, with
the request id when the headers carry one. Compare the fields a test cares about, or build the expected error with
`withHttpStatus` and `withRequestId`.

## A context-length overflow and an unknown model are typed errors (#2916)

The two provider refusals a caller most often reacts to had no type: a request too long for the model was a
`ValidationError` (or a `ServiceError`), an unknown model a `ServiceError` with status 404, and telling either from
any other 400 or 404 meant reading the provider's message.

```scala
client.complete(conversation, options) match {
  case Left(e: ContextLengthError) => // e.limit, e.requestedTokens: shorten the conversation and call again
  case Left(e: ModelNotFoundError) => // e.model: fix the section's model, or fall back to another provider
  case other                       => other
}
```

`ContextLengthError` carries `limit` and `requestedTokens` when the provider's message states them (OpenAI, Anthropic
and Gemini do). `ModelNotFoundError` carries `model` when the client knows which it asked for. Neither is retryable.
OpenAI, Azure OpenAI, Anthropic, Gemini, Vertex AI, Ollama, Bedrock (context length) and every OpenAI-compatible
service map to them, by the provider's error code where it has one (`context_length_exceeded`, `model_not_found`,
Anthropic's `not_found_error`, Mistral's `invalid_model`) and by its message otherwise.

If you matched these cases before, the match changes:

| Before | After |
|---|---|
| `case Left(e: ValidationError) if e.message.contains("context length")` | `case Left(e: ContextLengthError)` |
| `case Left(ServiceError(_, 404, _, _, _))` for an unknown model | `case Left(e: ModelNotFoundError)` |

A 404 that does not say the model is unknown - what a wrong `baseUrl` answers - is still a `ServiceError`. Ollama's 404
for a model that is not pulled is a `ModelNotFoundError`, with the same `ollama pull` hint in its message.
`NotFoundError` is unchanged: it is a missing configuration value or record, not a model.

## `AgentBuilder` tools are additive (#2916)

`AgentBuilder.withTools(registry)` and `withToolSet(toolSet)` each replaced whatever the other, or an earlier call, had
set, silently, while `withMiddleware`, `withHandoffs` and the guardrail setters append. Both now add to the agent's
tools:

```scala
Agent.builder("assistant", client)
  .withTools(builtins)     // a ToolRegistry
  .withToolSet(typedTools) // adds to them; it used to replace them
  .build()                 // Left(ValidationError) naming a tool both define: "tool 'x': duplicate tool name"
```

If you relied on a later call replacing an earlier one - a base builder whose tools a caller overrides - build the
registry or the set you want first, and call the setter once. Each tool of a `ToolSet` is still checked with its own
set's validator. The Java facade's `JAgentBuilder.tools(list)` is unchanged: it takes the whole list, as its guardrail
setters do.

## `StreamedChunk` names its deltas (#2916)

Every field of a `StreamedChunk` but its `id` is what that one chunk adds, yet only `thinkingDelta` said so: `content`
read as the text so far, and `toolCall` as a whole call.

| Before | After |
|---|---|
| `chunk.content` | `chunk.contentDelta` |
| `chunk.toolCall` | `chunk.toolCallDelta` |
| `chunk.withContent(text)`, `chunk.withToolCall(call)` | `chunk.withContentDelta(text)`, `chunk.withToolCallDelta(call)` |
| `StreamedChunk(id = "c1", content = Some("Hel"), toolCall = None)` | `StreamedChunk(id = "c1", contentDelta = Some("Hel"), toolCallDelta = None)` |

Scalafix's `StreamedChunkDeltaNames` rewrites all four. The types are unchanged, and so is `hasContent`. A
`toolCallDelta` is a fragment: the first of a call has its `id` and `name`, each later one adds a piece of the
`arguments` JSON, and every fragment carries its call's `id`. `StreamingAccumulator` joins them; `Completion.content`
and `Completion.toolCalls` are the whole answer, and keep their names.

## `LlmException.isRetryable`, and three new `LlmErrorKind`s (#2916)

The Java facade follows the one error model:

| Before | After |
|---|---|
| `e.isRecoverable()` | `e.isRetryable()`: core's `LLMError.isRetryable`, so `false` for a `SERVICE` error with a 400 or a 404 |
| `e.getKind() == LlmErrorKind.VALIDATION` for a content-filter block | `LlmErrorKind.CONTENT_FILTER` |
| a context-length overflow: `VALIDATION` or `SERVICE` | `LlmErrorKind.CONTEXT_LENGTH` |
| an unknown model: `SERVICE` with status 404 | `LlmErrorKind.MODEL_NOT_FOUND` |

A `switch` over `LlmErrorKind` with no `default`, and a Kotlin `when` with no `else`, need the three new cases.
`getStatusCode()` and `getRetryAfter()` now answer for every error that carries the fact (a `RATE_LIMIT` reports 429,
an `AUTHENTICATION` 401 or 403), and `getRequestId()` is new. A scripted failure of the fake client (`failure(kind, message)`) builds
an error of each new kind. In Kotlin, `error.isRecoverable` is `error.isRetryable`.

## Message parts, citations and adjustments are `Seq`s (#2809)

The model's public signatures use `Seq` for a collection, as `AssistantMessage.toolCalls` already did. Stored JSON
is unchanged: a message with one text part is still written as `content`, any other as `parts`, whatever `Seq`
holds them.

| Before | After |
|---|---|
| `UserMessage.parts`, `ToolMessage.parts`: `Vector[ContentPart]`; `apply(parts: Vector[...])`, `withParts(Vector[...])` | `Seq[ContentPart]`; a `List` or any other `Seq` is accepted |
| `Completion.citations: List[Citation]`, `withCitations(List[...])` | `Seq[Citation]` |
| `Completion.adjustments`, `TransformationResult.adjustments`: `List[RequestAdjustment]`; `RequestTransformer.transform` returns `(CompletionOptions, List[RequestAdjustment])` | `Seq[RequestAdjustment]` |
| `OpenAICompatibleDialect.addReasoning(...)`: `List[RequestAdjustment]` | `Seq[RequestAdjustment]`: change the return type of an override |
| `AgentEvents.ModelCallCompleted.adjustments: List[RequestAdjustment]` | `Seq[RequestAdjustment]` |

Code that reads one of these as a `List` or `Vector` (`val parts: Vector[ContentPart] = message.parts`, a
`case Vector(...)` or `::` pattern) calls `.toVector` / `.toList`, or uses `Seq` and `case Seq(...)`.

## `MessageRole` is a Scala 3 `enum` (#2809)

`MessageRole` was a sealed trait with four case objects and is now an `enum` with the same four cases, `System`,
`User`, `Assistant` and `Tool`. A value, a comparison and a `match` on the cases compile unchanged, `role.name` and
`toString` are still the lowercase string sent to the provider, and stored JSON is unchanged. What changes is code
that named a case as a type or reached it from Java:

| Before | After |
|---|---|
| `MessageRole.User.type` as a type | `MessageRole`: an `enum` case without fields has no type of its own to name |
| Java: `MessageRole.User$.MODULE$` | `MessageRole.User` (a static field); `MessageRole.valueOf("User")` and `MessageRole.values()` |
| A `case _: MessageRole.User.type =>` type pattern | `case MessageRole.User =>` |

## `withToolSet` and `validateIn`, and the Java facade's guardrails sit innermost (#2835)

MiMa skips an `@Experimental` method together with every overload of its name, so an Experimental overload of a
Stable method would leave the Stable one unchecked once the baseline is set. The two such overloads in `llm4s-agent`
are renamed:

| Before | After |
|---|---|
| `Agent.builder(id, client).withTools(toolSet)`, given a `ToolSet` | `.withToolSet(toolSet)`; `withTools(registry)` still takes a `ToolRegistry` |
| `override def validate(value: A, context: UsageAccounting)` in a guardrail | `override def validateIn(value: A, context: UsageAccounting)` |
| `guardrail.validate(value, context)` | `guardrail.validateIn(value, context)` |

`validate(value)` is unchanged. `RAGGuardrail.validateWithContext(output, ragContext)` is unchanged too: it validates
against a `RAGContext`, which is why the stage form is not called `validateWithContext`. `TypedAgent.withTools`,
`LoopAgent.withTools` and `ModelRequest.withTools` take only a `ToolSet` and keep their names.

The Java facade's `inputGuardrails` and `outputGuardrails` now sit where a Scala agent's `withInputGuardrails` and
`withOutputGuardrails` put them: innermost in the agent's middleware, after the facade's context window. Before, the
facade installed its own `GuardrailMiddleware` first. Input guardrails run at admission wherever they sit, so a blocked
query was already never stored; innermost, an output guardrail also checks the answer before any other middleware's
`afterAgent` sees it, the order the Scala builder chose so that a middleware such as semantic memory never records a
blocked answer. What a Java agent answers or blocks is unchanged, but its graph version changes (the guardrail
middleware's id is `agent-guardrails` now), so a Java agent's thread suspended or in flight before the upgrade is not
resumed by the upgraded agent: finish or abandon it first.

## `AgentResult.runUsage`, and tighter `PromptInjectionDetector` defaults (#2922, #2923)

**`AgentResult` gained `runUsage`**, the usage of the run that just finished, after `usage`, which is still the
thread's total. Nothing changes for code that reads the fields by name. A positional pattern takes a seventh binder,
which the `PositionalPatternsGainFields` Scalafix rule adds ([Scalafix](scalafix#migration-rules)):

| Before | After |
|---|---|
| `case AgentResult(t, r, a, s, m, usage)` | `case AgentResult(t, r, a, s, m, usage, _)` |

An application that subtracted the previous turn's `usage` from the new one to meter a turn can read `runUsage`
instead. `JAgentResult.runUsage()` is the same value for Java and Kotlin.

**`PromptInjectionDetector`'s default patterns match fewer texts.** Seven of them matched ordinary sentences of a
story, a game or a manual, and now need the text to say it is about the model:

| Pattern | No longer matches | Still matches |
|---|---|---|
| `dan_jailbreak` | "ask Dan to act as lookout", and a bare "act as DAN" | "DAN mode", "do anything now", "you are now DAN", "pretend you are DAN" |
| `roleplay_evil` | "pretend to be an evil cultist" | "roleplay as an evil AI", "act like an uncensored AI" |
| `no_restrictions` | "remove filters from the pump", "no restrictions" on its own | "remove all your filters", "respond without any restrictions", "you have no restrictions", "no safety filters" |
| `reveal_prompt`, `tell_me_prompt`, `what_is_prompt`, `repeat_instructions` | "show the instructions to the guard", "what are the rules", "repeat the instructions", "repeat all instructions" | "show me your system prompt", "what are your rules", "repeat the system prompt", "tell me your instructions", and for reveal and repeat "repeat the instructions above", "print the previous instructions", "repeat the instructions verbatim" |

To keep one of the old patterns, pass it with the defaults:
`PromptInjectionDetector(InjectionPattern.default :+ InjectionPattern("dan_name", yourRegex, InjectionCategory.RoleManipulation, 3))`.
To drop defaults that still do not suit your text, start from them rather than copying the list:
`PromptInjectionDetector.defaults.without("creative_writing", "extract_data")`, a `Result` that is `Left` for a name
that is not a pattern's ([Guardrails](../guide/agents/guardrails#prompt-injection-protection)).

## `llm4s.providers.provider` is now `llm4s.defaultProvider` (#2806)

The name of the default provider section used to be a key *inside* `llm4s.providers`, beside the sections it chose
between. So `provider` meant "the default section" at `llm4s.providers.provider` and "which provider this section
is" one level down at `llm4s.providers.<name>.provider`, and a section could not be called `provider`. It is now
`llm4s.defaultProvider`, beside the block; every key of `llm4s.providers` is a section.

**`application.conf`**: move the line out of the block and rename it.

```hocon
# before
llm4s {
  providers {
    provider = "openai-main"
    provider = ${?LLM4S_PROVIDER}        # if you bound it to a variable
    openai-main { provider = "openai", model = "gpt-4o-mini" }
  }
}

# after
llm4s {
  defaultProvider = "openai-main"
  defaultProvider = ${?LLM4S_PROVIDER}
  providers {
    openai-main { provider = "openai", model = "gpt-4o-mini" }
  }
}
```

| Where | Before | After |
|---|---|---|
| HOCON | `llm4s.providers.provider = "x"` | `llm4s.defaultProvider = "x"` |
| JVM system property | `-Dllm4s.providers.provider=x` | `-Dllm4s.defaultProvider=x` |
| Spring Boot `application.properties` / YAML | `llm4s.providers.provider=x` | `llm4s.default-provider=x` (`llm4s.defaultProvider` and `llm4s.default_provider` are read too) |
| Quarkus `application.properties` | `llm4s.providers.provider=x` | `llm4s.default-provider=x` (or `llm4s.defaultProvider`) |
| Environment | none: the library binds no variable to it | unchanged: bind your own, `llm4s.defaultProvider = ${?MY_VAR}` |

The library never bound an environment variable to this key, so no variable is renamed. The *samples* bind
`LLM4S_PROVIDER` in their own `application.conf`; that binding moved to `llm4s.defaultProvider` with the key.

**If you do not change it**, `llm4s.providers.provider = "x"` no longer selects anything:
`Llm4sConfig.defaultProvider()` and `defaultProviderName()` return
`No default provider configured under llm4s.defaultProvider. llm4s.providers.provider ('x') no longer names the default section: move it out of the providers block, as llm4s.defaultProvider = "x"`.
The string is also read as a section named `provider` that is not an object, so `providerConfigs()` holds a `Left`
under `provider`; the other sections load as before, and `Llm4sConfig.provider(ProviderName("x"))` still works.

No Scala method changed name: `Llm4sConfig.defaultProvider()` and `defaultProviderName()` read the new key.

**Also in #2806.** `org.llm4s.config.ConfigKeys` is removed: it held one constant, `ConfigKeys.EMBEDDING_MODEL`;
write `"EMBEDDING_MODEL"`. Each provider module's own `<Name>ConfigKeys` object is unchanged. The `llm4s.workspace`
block (`WORKSPACE_DIR`, `WORKSPACE_IMAGE`, `WORKSPACE_PORT`) is bound by `llm4s-workspace-client`'s `reference.conf`
instead of core's: nothing changes for an application with the workspace client on its classpath, its only reader;
one that read `llm4s.workspace.*` itself *without* that module binds the three variables in its own
`application.conf`.

## `Llm4sConfig` takes `ProviderName`, and `embeddings()` returns the config alone (#2804)

Follows [`Llm4sConfig.providerConfigs()` returns each section's `Result`](#llm4sconfigproviderconfigs-returns-each-sections-result-providers-and-providersconfig-are-internal-2804).
The `ProviderConfigNewtypes` Scalafix rule ([Scalafix](scalafix#migration-rules)) rewrites a literal or `String` argument of `provider` and `listModels`.

```scala
import org.llm4s.types.ProviderModelTypes.ProviderName

// before
Llm4sConfig.provider("openai-main")
Llm4sConfig.listModels("openai-main")
// after
Llm4sConfig.provider(ProviderName("openai-main"))
Llm4sConfig.listModels(ProviderName("openai-main"))
```

`defaultProviderName()` already returned a `ProviderName`, so `defaultProviderName().flatMap(Llm4sConfig.provider)`
needs no conversion. The same holds for `Llm4sSettings`.

**`embeddings()`** returned `Result[(String, EmbeddingProviderConfig)]`, the provider id beside its config. The id is
now `EmbeddingProviderConfig.providerId`, so it returns `Result[EmbeddingProviderConfig]`, and
`EmbeddingClient.from` takes the config with no provider name:

```scala
// before
for {
  pair <- Llm4sConfig.embeddings()
  (provider, config) = pair
  client <- EmbeddingClient.from(provider, config)
} yield client

// after
for {
  config <- Llm4sConfig.embeddings()          // config.providerId is the canonical provider id
  client <- EmbeddingClient.from(config)
} yield client
```

| Before | After |
|---|---|
| `Llm4sConfig.embeddings().map(_._2)` | `Llm4sConfig.embeddings()` |
| `Llm4sConfig.embeddings().map(_._1)` | `Llm4sConfig.embeddings().map(_.providerId.asString)` |
| `EmbeddingClient.from("voyage", config)` | `EmbeddingClient.from(config.withProviderId(ProviderId("voyage")))`, or build the config with that id |
| `EmbeddingClient.from(provider, config, options)` | `EmbeddingClient.from(config, options)` |
| `RAGASFactory.fromConfigs(providerCfg, (provider, embeddingCfg))`, `basicFromConfigs` (`llm4s-rag`) | `RAGASFactory.fromConfigs(providerCfg, embeddingCfg)` |
| `ProviderTestConfig.loadEmbeddings(...)`: `Result[(String, EmbeddingProviderConfig)]` (`llm4s-provider-testkit`) | `Result[EmbeddingProviderConfig]` |

**`textEmbeddingModel()`** returned `Llm4sConfig.TextEmbeddingModelSettings(provider, modelName, dimensions)`, a
copy of `EmbeddingModelConfig` with a provider string. That type is removed; the method returns
`Result[EmbeddingModelConfig]` (`name`, `dimensions`), which is what an `EmbeddingRequest` takes:

```scala
// before
for {
  settings <- Llm4sConfig.textEmbeddingModel()
  model = EmbeddingModelConfig(settings.modelName, settings.dimensions)
} yield model
// after
Llm4sConfig.textEmbeddingModel()
// the provider, if you read settings.provider: Llm4sConfig.embeddings().map(_.providerId)
```

**`ProvidersConfigModel` is removed.** It was an object used as a namespace: it held `NamedProviderConfig` and
re-exported every newtype of `org.llm4s.types.ProviderModelTypes`, so each had two import paths.

| Before | After |
|---|---|
| `import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig` | `import org.llm4s.config.NamedProviderConfig` |
| `import org.llm4s.config.ProvidersConfigModel.{ ProviderName, ProviderId, ModelName, BaseUrl, ApiKey }` | `import org.llm4s.types.ProviderModelTypes.{ ... }` |
| `import org.llm4s.config.ProvidersConfigModel.*` | `import org.llm4s.config.NamedProviderConfig` and `import org.llm4s.types.ProviderModelTypes.*` |
| `ProvidersConfigModel.NamedProviderConfig` in a signature | `NamedProviderConfig` |

A provider module's descriptor is the usual place this appears (`buildConfig(section: NamedProviderConfig, ...)`).

## `ProviderConfig` has no default members, and `EmbeddingProviderConfig` is typed (#2805)

The `ProviderConfigNewtypes` Scalafix rule ([Scalafix](scalafix#migration-rules)) wraps a provider config's named `model = "..."` argument and
`withModel("...")` in `ModelName(...)`; a model passed by position is left for the compiler to report.

**`ProviderConfig`, for callers:**

| Before | After |
|---|---|
| `config.model: String` | `config.model: ModelName`; `config.model.asString` for the string |
| `config.withModel("gpt-4o")` | `config.withModel(ModelName("gpt-4o"))` |
| `config.endpointUrl: Option[String]` | `Option[BaseUrl]`; `config.endpointUrl.map(_.asUrl)` |
| `config.withApiKeyProvider(keys): ProviderConfig` | `Result[ProviderConfig]`: `Llm4sConfig.provider(name).flatMap(_.withApiKeyProvider(keys))` |
| check `config.apiKeyProvider.isDefined` afterwards | not needed: a config that cannot take one returns a `ConfigurationError` |
| `OpenAIConfig(apiKey, "gpt-4o")`, and every other provider config's `apply` | `OpenAIConfig(apiKey, ModelName("gpt-4o"))`; `fromValues(...)` still takes the model as a `String` |
| `BedrockConfig(endpointUrl = Some("https://..."))` | `Some(BaseUrl("https://..."))`; `BedrockConfig.fromValues` still takes `Option[String]` |

On a concrete config the total setter remains: `openAIConfig.withApiKeyProvider(Some(keys)): OpenAIConfig` (and
`None` to go back to the key the config holds). Configs that authenticate without an API key (`OllamaConfig`,
`VertexAIConfig`, `BedrockConfig`, `JlamaConfig`) return
`Left(ConfigurationError("provider '<id>' takes no API key, so its config cannot take an ApiKeyProvider"))`;
they used to return themselves unchanged.

**`ProviderConfig`, for implementers** (a provider module, a test double): every member is now abstract. Add the six
that had defaults:

```scala
final case class AcmeConfig(
  apiKey: String,
  model: ModelName,                                   // was String
  baseUrl: String,
  contextWindow: Int,
  reserveCompletion: Int,
  timeouts: ProviderTimeouts = ProviderTimeouts.default,
  network: HttpNetworkSettings = HttpNetworkSettings.default,
  apiKeyProvider: Option[ApiKeyProvider] = None
) extends ProviderConfig:
  val providerId: ProviderId                                = ProviderId("acme")
  def endpointUrl: Option[BaseUrl]                          = Some(BaseUrl(baseUrl))   // was Option[String]
  def withModel(model: ModelName): AcmeConfig               = copy(model = model)      // was (String)
  def withTimeouts(timeouts: ProviderTimeouts): AcmeConfig  = copy(timeouts = timeouts)
  def withNetwork(network: HttpNetworkSettings): AcmeConfig = copy(network = network)
  def withApiKeyProvider(provider: ApiKeyProvider): Result[AcmeConfig] =
    Right(copy(apiKeyProvider = Some(provider)))
```

A config that cannot honour one says so rather than inheriting a no-op:

- no API key, or a client that reads the key once: `def apiKeyProvider = None` and
  `def withApiKeyProvider(p: ApiKeyProvider) = ProviderConfig.noApiKeyProvider(providerId)` (pass a second argument,
  e.g. `"reads its key once"`, for the reason);
- no request to time out and no connection to route (a model run in-process): `def timeouts = ProviderTimeouts.default`,
  `def withTimeouts(t: ProviderTimeouts) = this`, and the same for `network`.

**A section's setting its config does not take now fails the load** instead of being dropped:

- `llm4s.providers.<name>.timeouts: provider '<id>' takes no timeouts, so the block would be ignored - remove it`
- `llm4s.providers.<name>.http: provider '<id>' takes no proxy or TLS settings, so the block would be ignored - remove it`

Of the providers in this repository only `jlama` is affected: remove `timeouts { ... }` and `http { ... }` from a
`provider = "jlama"` section. The process-wide `llm4s.http` block (and so `HTTPS_PROXY`) is not an error for it.

**`ContextWindowResolver`** is final, `resolve` returns `ContextLimits(contextWindow, reserveCompletion)` instead of
`(Int, Int)`, `fallbackResolver` is `String => ContextLimits`, and the `logPrefix` parameter is gone:

```scala
// before
val (window, reserve) = resolver.resolve(Seq("acme"), model, 128000, 4096, _ => (128000, 4096), logPrefix = "Acme ")
// after
val limits = resolver.resolve(Seq("acme"), model, 128000, 4096, _ => ContextLimits(128000, 4096))
// limits.contextWindow, limits.reserveCompletion; or: val ContextLimits(window, reserve) = resolver.resolve(...)
```

**`EmbeddingProviderConfig`:**

```scala
// before
EmbeddingProviderConfig(baseUrl = "https://api.voyageai.com/v1", model = "voyage-3", apiKey = Secret(key))
EmbeddingProviderConfig("http://localhost:11434", "nomic-embed-text", Secret(""))      // a keyless provider

// after
EmbeddingProviderConfig(ProviderId("voyage"), BaseUrl("https://api.voyageai.com/v1"), ModelName("voyage-3"), Some(Secret(key)))
EmbeddingProviderConfig(ProviderId("ollama"), BaseUrl("http://localhost:11434"), ModelName("nomic-embed-text"))
```

| Before | After |
|---|---|
| `config.baseUrl: String`, `config.model: String` | `BaseUrl`, `ModelName`; `.asUrl`, `.asString` |
| `config.apiKey: Secret` (`Secret("")` when there is none) | `Option[Secret]` (`None` when there is none) |
| `config.withApiKey(secret)` | unchanged; `withApiKey(None)` clears it |
| `config.currentApiKey(): Result[Secret]` | `Result[Option[Secret]]`; a provider that requires a key turns `None` into its own error |
| `withBaseUrl(String)`, `withModel(String)` | `withBaseUrl(BaseUrl)`, `withModel(ModelName)` |
| `EmbeddingConfigSpec.resolveModel` / `resolveBaseUrl` / `resolveApiKey`: `Result[String]` / `Result[String]` / `Result[Secret]` | `Result[ModelName]` / `Result[BaseUrl]` / `Result[Option[Secret]]` |

An `EmbeddingProviderDescriptor` that overrides `buildConfig` passes its `id` as the config's `providerId`; the loader
sets it to the descriptor's canonical id in any case.

**Local encoders.** `LocalEmbeddingModels` keeps its package, `org.llm4s.llmconnect.config`, but ships in `llm4s-rag`
(with `ModelSelector`, its only user): add `llm4s-rag` if you used it from core alone.
`ModelDimensionRegistry.localDimension(model)` is `LocalEmbeddingModels.dimension(model)`;
`ModelDimensionRegistry.LocalProvider` is removed, and `ModelDimensionRegistry.getDimension("local", model)` no
longer answers from a built-in table: `local` is a provider id like any other, answered only by a registered
embedding provider of that id.

## Provider clients are final, with one factory (#2910)

Most applications never name a provider client: `LLMConnect.getClient` builds one from a named section and returns
an `LLMClient`, and nothing changes for them. This is for code that builds a client class directly.

Every provider client is now a final class whose constructor is not public, with **one factory** that takes the
config and an `LlmClientOptions` and returns a `Result`. The metrics collector, the exchange logging and the HTTP
client, which were positional arguments, are the options' fields, so a new option no longer changes a signature.
A config the client refuses is a `Left`, usually a `ConfigurationError`; the constructors threw
`IllegalArgumentException`.

It applies to `AnthropicClient`, `GeminiClient`, `VertexAIClient`, `OllamaClient`, `OpenAIClient`,
`OpenAICompatibleClient`, `BedrockClient`, `WatsonXClient` and `PerplexityClient`, and to the six vendor factories
below. With `AnthropicClient` as the example:

| Before | After |
|---|---|
| `new AnthropicClient(config)` | `AnthropicClient(config)`, a `Result[AnthropicClient]`; in a `for`, `client <- AnthropicClient(config)` |
| `new AnthropicClient(config, metrics)`, `AnthropicClient(config, metrics)` | `AnthropicClient(config, LlmClientOptions(metrics = metrics))` |
| `AnthropicClient(config, metrics, exchangeLogging)` | `AnthropicClient(config, LlmClientOptions(metrics, exchangeLogging))` |
| `GeminiClient(config, metrics, exchangeLogging, httpClient)` | `GeminiClient(config, LlmClientOptions(metrics, exchangeLogging).withHttpClient(httpClient))` |
| `OllamaClient(config, httpClient = http)` | `OllamaClient(config, LlmClientOptions().withHttpClient(http))` |
| `OpenAIClient(azureConfig)`, `OpenAIClient(azureConfig, metrics)` | `OpenAIClient.azure(azureConfig)`, `OpenAIClient.azure(azureConfig, LlmClientOptions(metrics = metrics))` |
| `new OpenAICompatibleClient(settings, dialect, metrics, exchangeLogging)` | `OpenAICompatibleClient(settings, dialect, LlmClientOptions(metrics, exchangeLogging))`, a `Result` |
| `try new OpenRouterClient(config) catch { case e: IllegalArgumentException => ... }` | `OpenRouterClient(config)` is a `Left(ConfigurationError)` |
| `val client: DeepSeekClient = ...`, `Result[ZaiClient]`, `case _: OpenRouterClient` | `OpenAICompatibleClient`: the vendor types are not public. To tell vendors apart, compare `client.providerId` |
| `new DeepSeekClient(config, metrics)` | `DeepSeekClient(config, LlmClientOptions(metrics = metrics))`, a `Result[OpenAICompatibleClient]` |
| `class MyClient(...) extends OpenAICompatibleClient(settings, dialect)` | an `OpenAICompatibleDialect` passed to `OpenAICompatibleClient(settings, dialect, options)`; wrap the client to change anything else |
| `class MyClient(config) extends AnthropicClient(config)`, overriding a `protected` member | no replacement: the classes are final. Wrap the client in your own `LLMClient` |

`LlmClientOptions` is in `org.llm4s.llmconnect`. As before, a client built on a vendor SDK (`AnthropicClient`,
`OpenAIClient`, `BedrockClient`) ignores `options.httpClient`, as does `PerplexityClient`, and a client you give one to
leaves it open when it is closed.

**The vendor client types on `OpenAICompatibleClient` are no longer public.** `DeepSeekClient`, `ZaiClient`,
`OpenRouterClient`, `MistralClient`, `CohereClient` and `XaiClient` were subclasses that added nothing to
`OpenAICompatibleClient` but a vendor's settings and dialect. The classes are now private to
`llm4s-openai-compatible`, and each name is only a factory: `DeepSeekClient(config, options)` and its five siblings
return a `Result[OpenAICompatibleClient]`, which is the type to declare. A client's vendor is its `providerId`
(`deepseek`, `zai`, `openrouter`, `mistral`, `cohere`, `xai`).

`OpenAIClient` has two factories because it serves two config types: `OpenAIClient(config: OpenAIConfig, options)` and
`OpenAIClient.azure(config: AzureConfig, options)`. `OpenAICompatibleClient` has two as well, one for an
`OpenAICompatibleConfig` and one for `Settings` and a dialect; the second takes its options explicitly.

The members that were `protected[provider]` (`createRequestBody`, `parseCompletion`, `parseStreamingChunks`,
`requestTimeout`, `streamTimeout` and the like) are `private[provider]`: they were never callable from outside
`org.llm4s.llmconnect.provider`, and are no longer points to override.

## `CachingLLMClient` and `CachedEmbeddingClient` have private constructors (#2910)

Both classes are final and their constructors private, so that a setting can be added without breaking a caller.
Build them with the companion's `apply`, which takes what the constructor took:

| Before | After |
|---|---|
| `new CachingLLMClient(base, embeddings, model, config, tracing)` | `CachingLLMClient(base, embeddings, model, config, tracing)` |
| `new CachingLLMClient(base, embeddings, model, config, tracing, clock)` | `CachingLLMClient(base, embeddings, model, config, tracing).withClock(clock)` |
| `new CachedEmbeddingClient(base, cache)` | `CachedEmbeddingClient(base, cache)` |
| `new CachedEmbeddingClient(base, cache, keyGenerator)` | `CachedEmbeddingClient(base, cache, keyGenerator)` |
| a subclass of either | no replacement: wrap the client instead |

`withClock` returns a new client with an empty cache, so call it where the client is built, not on one that has
already answered requests.

## `ReliableClient` has a private constructor (#2797)

`ReliableClient`'s constructor is private, so that a setting can be added to it without breaking a caller. Build one
with the companion's `apply` (`config` defaults to `ReliabilityConfig.default`) and add a collector with `withMetrics`.
The `clock` and `sleep` parameters were test seams and are no longer public.

| Before | After |
|---|---|
| `new ReliableClient(client, "openai", config)` | `ReliableClient(client, "openai", config)` |
| `new ReliableClient(client, "openai", ReliabilityConfig.default)` | `ReliableClient(client, "openai")`, or as before without `new` |
| `new ReliableClient(client, "openai", config, Some(metrics))` | `ReliableClient(client, "openai", config).withMetrics(metrics)` |
| `new ReliableClient(client, "openai", config, None)` | `ReliableClient(client, "openai", config)` |
| `new ReliableClient(client, "p", config, sleep = _ => ())`, in a test that must not wait | give the config a short delay: `config.withRetryPolicy(RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.millis))`; a scripted `RateLimitError` also needs a short `retryAfter` (`RateLimitError(provider, retryAfter = 1.millis)`), since without one the policy waits `RetryPolicy.DefaultRateLimitDelay` (30 s) |
| `new ReliableClient(client, "p", config, clock = fake)`, in a test of the deadline or of circuit recovery | no public replacement: use a short `deadline` or `recoveryTimeout` and wait for real |

`withMetrics` returns a new client with a circuit breaker and rate limit of its own, so call it where the client is
built, not on a client that has already served requests. `providerName` is still a `String`: it is the `provider`
label of the client's metrics, whatever name you choose, not a provider id.

## A tracing backend declares its aliases, and `Tracing.create` is removed (#2799)

**`Tracing.create` is removed.** It returned `NoOpTracing`, with the reason only in the log, when the configured
backend was missing or could not start. `Tracing.fromSettings` returns that reason, and is the only factory:

| Before | After |
|---|---|
| `Tracing.create(settings)` | `Tracing.fromSettings(settings)`, a `Result[Tracing]`; in a `for`, `tracer <- Tracing.fromSettings(settings)` |
| `Llm4sConfig.tracing().map(Tracing.create)` | `Llm4sConfig.tracing().flatMap(Tracing.fromSettings)` |
| the old fallback, kept on purpose | `Tracing.fromSettings(settings).getOrElse(new NoOpTracing())`, logging the error first if you want it logged |

**The `otel` alias belongs to its backend.** Core's mode parser named a backend (`"otel"` meant
`Named("opentelemetry")`). A `TracingBackend` now declares its own `aliases`, and `llm4s-observability-otel` declares
`otel`. Nothing changes for an application that sets `TRACING_MODE=otel` and loads its settings with
`Llm4sConfig.tracing()`: with `llm4s-observability-otel` on the classpath the settings still carry
`TracingMode.Named("opentelemetry")` and the `llm4s.tracing.opentelemetry` block, because `Llm4sConfig.tracing()`
replaces an alias by its backend's mode before it reads the block. What changes:

- `TracingMode.fromString("otel")` returns `Named("otel")`. Code that compared it with `Named("opentelemetry")` should
  compare the mode of the loaded `TracingSettings`, or use `"opentelemetry"`.
- Without `llm4s-observability-otel` on the classpath, `Llm4sConfig.tracing()` with `TRACING_MODE=otel` gives
  `Named("otel")` with empty extras, and `Tracing.fromSettings` fails naming the artifact to add, as before.
- **A shaded jar that registers the backend explicitly must use the full name.** With no services files,
  `Llm4sConfig.tracing()` cannot see the backend, so it cannot replace the alias, and
  `Tracing.fromSettings(settings, TracingBackends.of(new OpenTelemetryTracingBackend))` would start the backend on its
  defaults. Set `TRACING_MODE=opentelemetry` there.

A backend of your own with more than one name overrides `aliases`:

```scala
final class DatadogTracingBackend extends TracingBackend:
  val mode: TracingMode             = TracingMode.Named("datadog")
  override val aliases: Set[String] = Set("dd")
  def create(settings: TracingSettings): Result[Tracing] = ???
```

**`TracingBackends.find` returns `Result[Option[TracingBackend]]`.** `Right(None)` is "no backend answers to this
name"; a `Left` is a `ConfigurationError` naming two that do. Explicit registration still replaces by mode
(`withBackend`, a later argument of `TracingBackends.of`). Two *discovered* backends for one mode used to resolve to
the later one on the classpath; they are now that error, from `find`, `Tracing.fromSettings` and
`Llm4sConfig.tracing()`, as are two backends that share an alias or one whose alias is another's mode. Remove one
from the classpath, or pass the one you want in a `TracingBackends.of(...)`.

## Image metrics are `ImageMetrics` in `llm4s-image` (#2801)

`MetricsCollector.observeImageGeneration` and `recordImageGenerationCost` are removed: image generation lives in
`llm4s-image`, and its metrics are now declared there, as `ImageMetrics`, and recorded through the collector's
generic `increment` and `observe`, the way `ReliabilityMetrics` are.

**Nothing changes on a dashboard.** Every image series keeps its name, type, labels and buckets in both
`llm4s-observability-prometheus` and `llm4s-observability-micrometer` (`llm4s_image_generations_total`,
`llm4s_images_generated_total`, `llm4s_image_generation_duration_seconds`, `llm4s_image_generation_cost_usd_total`,
`llm4s_image_generation_errors_total`), and a failed image generation still counts in `llm4s_errors_total`. Three
things differ that are not a series' identity: the Micrometer duration meter's description gains " in seconds", the
Prometheus duration `_sum` is exact to the nanosecond where it was to the millisecond, and the Micrometer collector no
longer maps an unknown `operation` to `other` (the instrumented client only sends `generate` and `edit`).

**A collector of your own** that overrode the two methods no longer compiles. Image generation reaches it through
the generic methods; match on the metric:

```scala
import org.llm4s.imagegeneration.ImageMetrics

override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit = metric match
  case ImageMetrics.Generations     => () // labelValues: provider, model, operation, status
  case ImageMetrics.ImagesGenerated => () // provider, model; amount is the number of images
  case ImageMetrics.CostUsd         => () // provider, model; amount is the cost in USD
  case ImageMetrics.Errors          => () // provider, model, operation, error_type
  case _                            => ()

override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit =
  if metric == ImageMetrics.Duration then () // provider, model, operation
```

A collector that implements the three generic methods for every `Metric`, as the two shipped backends do, needs no
image code at all.

| Before | After |
|---|---|
| `MicrometerMetrics.Names.ImageGenerations` | `ImageMetrics.Generations.name` |
| `MicrometerMetrics.Names.ImagesGenerated` | `ImageMetrics.ImagesGenerated.name` |
| `MicrometerMetrics.Names.ImageGenerationDuration` | `ImageMetrics.Duration.name` |
| `MicrometerMetrics.Names.ImageGenerationCostUsd` | `ImageMetrics.CostUsd.name` |
| `MicrometerMetrics.Names.ImageGenerationErrors` | `ImageMetrics.Errors.name` |

`MetricsCollector.Errors` is new: the failed-operations counter (`llm4s_errors_total`) as a `Metric`, so that a module
of your own can add its failed operations to it with
`metrics.increment(MetricsCollector.Errors, Seq(provider, errorType))`. A backend of your own that keeps that count in
a meter of its own should record this metric into it.

## The Hugging Face image client calls Inference Providers (#2832)

Hugging Face retired the serverless Inference API at `api-inference.huggingface.co`, which `HuggingFaceClient` posted
to; it now posts to Hugging Face's own `hf-inference` provider on the
[Inference Providers router](https://huggingface.co/docs/inference-providers/tasks/text-to-image),
`https://router.huggingface.co/hf-inference/models/<model>`, in the format that page documents.

- **The default model** is `stabilityai/stable-diffusion-3-medium-diffusers` (was `stabilityai/stable-diffusion-xl-base-1.0`,
  which `hf-inference` does not serve): the text-to-image model the Hub lists for `hf-inference`. A model only
  third-party providers serve does not work with this client.
- **The token** needs the "Make calls to Inference Providers" permission.
- **`HuggingFaceConfig`** gains a last field, `baseUrl` (default `HuggingFaceConfig.DefaultBaseUrl`), for a proxy or a
  test server; a pattern match on its three fields no longer compiles.
- **`Parameters`** (the request body's `parameters`) names its steps field `num_inference_steps`, as the API does,
  where it was `inferenceSteps`, which the API never read; it gains `width` and `height`, from
  `ImageGenerationOptions.size` (none for `ImageSize.Auto`). An absent optional parameter is left out of the body.
- **`health()`** asks the Hub whether `hf-inference` serves the model
  (`GET https://huggingface.co/api/models/<model>?expand[]=inferenceProviderMapping`): `Healthy` when it does, live;
  `Degraded` otherwise.

| Before | After |
|---|---|
| `Parameters(guidance_scale, inferenceSteps, negative_prompt, seed)` | `Parameters(guidance_scale, num_inference_steps, negative_prompt, width, height, seed)` |
| `HuggingFaceConfig(apiKey, model, timeout)` | `HuggingFaceConfig(apiKey, model, timeout, baseUrl)` |

## The OpenAI image client defaults to `gpt-image-2.6-sunburst` (#2884)

OpenAI shut down `dall-e-2`, the client's old default, on 2026-05-12, and `editImage` always sent `dall-e-2`.

- **The default model** (`OpenAIConfig.DefaultModel`, also the default of `ImageGeneration.openAIClient` and
  `generateWithOpenAI`) is `gpt-image-2.6-sunburst`. An explicit `model` is kept.
- **`editImage` sends the configured model**, with `size = auto` when no size is given (a GPT Image model; a legacy
  model still keeps the source image's size).
- **For a GPT Image model** the client never sends `response_format`, and refuses `responseFormat = Some("url")` and
  `style` with an `ImageValidationError` before any request: those models always return base64 and take no style.
- **`OpenAIImageClient.deprecationWarningMessage` is removed**; `retirementWarning(model): Option[String]` covers every
  image model OpenAI has shut down or is shutting down, with OpenAI's replacements.

## The config-policy `prod` preset allows model generations (#2885)

`ConfigPolicy.prodSafeDefaults` allowed only `openai/gpt-4o(-mini)`, `anthropic/claude-3-5-sonnet.*`,
`gemini/gemini-2.x` and `deepseek/deepseek-chat`, all retired or retiring, and capped OpenAI at 128000 and Anthropic at
200000 tokens, below today's 1M windows, so a production config on a current model failed it.

- **Models**: each allowed provider is limited to its vendor's current generations, `ConfigPolicy.ModelFamilies`
  (GPT-5 and GPT-6; Claude 5 and later, Opus and Sonnet from 4.6, Haiku 4.5; Gemini 3; `deepseek-chat` and
  `deepseek-reasoner`; any Azure deployment). `bedrock` is allowed, for the same Claude families. A config on a
  retired generation (`gpt-4o-mini`, `claude-3-5-sonnet-*`, `gemini-2.5-pro`) now fails with `[allowedModels]`:
  move it to a current model, or allow the model in your own policy with
  `withAllowedModelPatterns((ConfigPolicy.prodModelPatterns :+ "openai/gpt-4o-mini")*)`.
- **Caps**: openai and azure 1050000, anthropic and bedrock 1000000, gemini 1048576, deepseek 163840: the largest
  window among each provider's current models in the bundled metadata.
- **`EnvCheckPolicies`' `staging-balanced` preset** allows the OpenAI, Anthropic and Gemini families above by pattern,
  in place of four pinned names (`PolicyBuilder.allowedModelPatterns` is **added**).

## `Secret` or `Secret.token`: which one stays registered (#2824)

Nothing changed here; the contract is now written down, on `Secret` and in
[Security best practices](../guide/patterns/security-best-practices#api-keys). Creating a `Secret` registers its value
process-wide for exact-match scrubbing. `Secret(value)` is a configured credential and is never evicted;
`Secret.token(value)` may be evicted once the registry holds more than 4096 values; a value under 8 characters is not
registered; nothing can be forgotten on request.

If your application creates a `Secret(...)` per tenant, per user or per request, change those to `Secret.token(...)`:
each distinct `Secret(...)` is held, and matched against every scrubbed text, for the life of the process. llm4s logs
one warning, naming this cause, when the registry passes 4096 values.

## `CompletionOptions`' sampling parameters are optional, and unset ones are not sent (#2807)

`temperature`, `topP`, `presencePenalty` and `frequencyPenalty` were `Double`s with defaults (`0.7`, `1.0`,
`0.0`, `0.0`), so llm4s could not tell "unset" from "set to the default" and always sent its own temperature
and top-p in place of the provider's. They are `Option[Double]`, `None` unless set.

```scala
// before
val options = CompletionOptions(temperature = 0.2, topP = 0.9)
val t: Double = options.temperature

// after
val options = CompletionOptions(temperature = Some(0.2), topP = Some(0.9))   // or .withTemperature(0.2).withTopP(0.9)
val t: Option[Double] = options.temperature
```

- **Behaviour.** A call that sets no temperature is no longer sent `temperature: 0.7` and `top_p: 1.0`: the
  provider's default applies, which for most models is a temperature of `1.0`. Set
  `withTemperature(0.7)` to keep the old requests. Jlama, whose engine takes no "unset", still uses `0.7`.
- **Reports.** A sampling option the caller left unset is neither sent nor reported. One the caller set that the
  model cannot take is a `RequestAdjustment` whatever its value; before, an explicit value equal to the default
  went unreported.
- **Java.** `JCompletionOptions.temperature()`, `topP()`, `presencePenalty()` and `frequencyPenalty()` return
  `OptionalDouble`.
- **Tracing.** The OpenTelemetry `gen_ai.request.temperature`, `top_p` and penalty attributes are present only
  for an option that was set.

`SamplingOptionsOptional` (in `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`) wraps the named arguments in `Some`. A read of one of the four
is left to you.

## `Completion` reads `content` and `toolCalls` from its message (#2807)

`Completion` stored `content` and `toolCalls` beside `message`, which has both, and three setters each updated
one side. They are now read from the message: `completion.content` is `completion.message.content` and
`completion.toolCalls` is `completion.message.toolCalls` (a `Seq`).

```scala
// before
Completion(id = id, created = now, content = text, model = model, message = AssistantMessage(text, calls), toolCalls = calls.toList)
completion.withContent("edited")
completion.asText

// after
Completion(id = id, created = now, model = model, message = AssistantMessage(text, calls))
completion.withMessage(completion.message.withContent("edited"))
completion.content
```

- `Completion.apply` is `(id, created, model, message, usage, estimatedCost, citations, adjustments,
  finishReason, servedTier)`. A positional construction or pattern loses its third and sixth places.
- **Check each construction site:** the text and the tool calls must be on the `message` you pass. A site that
  passed tool calls only as `toolCalls = ...` now has none.
- `withContent`, `withToolCalls` and `asText` are removed. `AssistantMessage.withContent` unseals signed thinking,
  as it always has.
- A completion assembled from a stream (`StreamingAccumulator.toCompletion`) now reports its tool calls on
  `toolCalls`; a client no longer copies them from the message.

`CompletionFromMessage` drops the two arguments, rewrites `asText`, and reports `withContent`, `withToolCalls`
and a pattern that binds either field.

## A `Completion` without a finish reason is `FinishReason.Unknown` (#2807)

`Completion.apply` defaulted `finishReason` to `Stop`, so a client that set none claimed a natural stop, while
"none read" was the sentinel `FinishReason.Other("")`. `FinishReason.Unknown` says it, and is the default.

```scala
completion.finishReason match {
  case FinishReason.Stop | FinishReason.ToolCalls => ...
  case FinishReason.Length                        => ...
  case FinishReason.Other(raw)                    => ...   // a provider value llm4s has no name for
  case FinishReason.Unknown                       => ...   // the client read none
}
```

- Replace `FinishReason.Other("")` with `FinishReason.Unknown`, in code and in patterns. `Unknown.name` is `""`,
  as `Other("").name` was, so code that filters on `name.nonEmpty` is unaffected.
- A test fake that builds a `Completion` without a reason and asserts `Stop` must set it:
  `finishReason = FinishReason.Stop`.
- JSON: `"Unknown"`. The other cases are written as before, and a stored `{"$type":"Other","raw":""}` reads as
  `Unknown`. An older llm4s reading an event written by this one reads `Unknown` as `Other("Unknown")`.

## `Completion.estimatedCost` is a `BigDecimal`; `UsageSummary` counts cache tokens (#2807)

A cost was an `Option[Double]` that `UsageSummary` converted to `BigDecimal` after the precision was already
lost. It is `Option[BigDecimal]` from `CostEstimator` on.

```scala
// before
completion.withEstimatedCost(0.0125)
val cost: Option[Double] = CostEstimator.estimate(model, usage)

// after
completion.withEstimatedCost(BigDecimal("0.0125"))
val cost: Option[BigDecimal] = CostEstimator.estimate(model, usage)
```

- `UsageSummary.add`, `ModelUsage.add`, the agent's `ModelContext.recordUsage` and `AdmissionContext.recordUsage`
  take `Option[BigDecimal]`. An `Option[Double]` you hold becomes `cost.map(BigDecimal.decimal)`.
- What stays a `Double`: `MetricsCollector.recordCost` and `MetricsRecording.withMetrics`' `extractCost`, the
  durable `ModelCallCompleted.estimatedCost`, `RAGQueryMetrics.costUsd` and the prices of `ModelPricing`.
- `UsageSummary` and `ModelUsage` gain `cachedTokens` and `cacheCreationTokens` (with `with*` setters), parts of
  `inputTokens`. A summary stored before them reads with both at zero. The Java facade exposes them on
  `JUsageSummary` and `JModelUsage`.

## `AssistantMessage.text`, and a flat stored assistant message (#2809)

```scala
// before
message.contentOpt
AssistantMessage(contentOpt = Some("Hello"), toolCalls = calls)
AssistantMessage(toolCalls = calls)
toolMessage.findToolCallName(history)            // "unknown-tool" when the call is not there

// after
message.text
AssistantMessage(text = Some("Hello"), toolCalls = calls)
AssistantMessage(None, calls)
toolMessage.findToolCallName(history)            // None when the call is not there
```

- `AssistantMessage()` no longer compiles: it built a message that fails `validate`. `content`, the text or `""`,
  and both `withContent` setters are unchanged.
- **Stored JSON.** An assistant message is written flat, as the other roles are:

  ```json
  {"type":"assistant","content":"Hello","toolCalls":[]}
  ```

  where it was `{"type":"assistant","data":{"contentOpt":"Hello","toolCalls":[]}}`. `content` is left out when
  the model produced only tool calls. The old shape is still read, so stored checkpoints, threads and
  conversations load unchanged; a rewrite of one stores the new shape. Code of your own that reads the JSON
  (a query over a store's rows, an export) must read both.
- **Thinking bindings.** A binding of sealed thinking is a fingerprint of the conversation before it. The
  fingerprint keeps its own encoding, so bindings made before this change still match and signed thinking is
  still replayed.
- `Message.validateConversation` and `Message.assistant` take a `Seq` (a `List` still passes).

`MessageAndConversationShapes` renames `contentOpt` and adds the missing `None`.

## One `Conversation.create`; `Conversation.empty` is a value (#2809)

```scala
// before
Conversation.create(UserMessage("hi"))
Conversation.create(SystemMessage("Be brief"), UserMessage("hi"))
Conversation.empty()

// after
Conversation.create(Seq(UserMessage("hi")))
Conversation.create(Seq(SystemMessage("Be brief"), UserMessage("hi")))
Conversation.empty
```

`Conversation.fromPrompts`, `userOnly` and `systemOnly` are unchanged, and `Conversation(messages)` still builds
one without validating. `MessageAndConversationShapes` rewrites both forms.

## `ProviderExchange` and `ProviderExchangeRecorder` (#2809)

For a sink of your own:

- `exchange.provider` is a `ProviderId`: `exchange.provider.asString` for the text.
- `exchange.duration` is still there, computed from `startedAt` and `completedAt`; `withDuration` is gone, and
  `ProviderExchange.apply` takes no `duration`. Its required values come first and `model`, `requestId`,
  `correlationId`, `responseBody` and `errorMessage` default to `None`.
- `ProviderExchangeLogging.enabled(sink)` is `ProviderExchangeLogging.Enabled(sink)`.

For a provider client:

```scala
// before
ProviderExchangeRecorder.record(
  exchangeLogging = options.exchangeLogging,
  provider = providerName,
  model = Some(config.model),
  startedAt = startedAt,
  requestBody = requestText,
  responseBody = Some(responseText),
  result = result
)

// after
ProviderExchangeRecorder.record(
  options.exchangeLogging,
  ProviderExchangeCall(
    provider = ProviderId(providerName),
    startedAt = startedAt,
    requestBody = requestText,
    model = Some(config.model),
    responseBody = Some(responseText)
  ),
  result
)
```

The `clock` and `idGenerator` parameters were test seams and are no longer public. The JSONL file is unchanged.

## `LLMConnect.getClient` is the one way to get a client (#2810)

```scala
// before
LLMConnect.fromConfig(config)
LLMConnect.getClient(config, metrics)
LLMConnect.getClient(ProviderId("openai"), config)
LLMConnect.fromProvider(ProviderId("openai"), config, options)
Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient)

// after
LLMConnect.getClient(config)
LLMConnect.getClient(config, LlmClientOptions(metrics = metrics))
LLMConnect.getClient(ProviderId("openai"), config, LlmClientOptions.default)
LLMConnect.getClient(ProviderId("openai"), config, options)
Llm4sConfig.defaultProvider().flatMap(LLMConnect.getClient(_))
```

`getClient(config, options = LlmClientOptions.default)` and `getClient(provider, config, options)` remain. Only
one overload may have a default, so the provider-explicit one takes its options. `ClientEntryPoints` rewrites
all but the method value.

## `LLMClient.streamComplete` takes its callback in a second parameter list (#2810)

`streamComplete(conversation, options = CompletionOptions(), onChunk)` had a defaulted parameter before a
required one, so the default was reachable only by naming `onChunk`.

```scala
// before
client.streamComplete(conversation, options, chunk => chunk.content.foreach(print))
client.streamComplete(conversation, onChunk = chunk => chunk.content.foreach(print))

// after
client.streamComplete(conversation, options)(chunk => chunk.content.foreach(print))
client.streamComplete(conversation)(chunk => chunk.content.foreach(print))
```

An `LLMClient` of your own, a wrapper or a test fake, changes its definition the same way:

```scala
override def streamComplete(conversation: Conversation, options: CompletionOptions)(
  onChunk: StreamedChunk => Unit
): Result[Completion] = ...
```

Java and Kotlin are unaffected: the method's JVM signature is the same three parameters. `ClientEntryPoints`
rewrites the calls and the overriding definitions. `MetricsRecording.withMetrics` lost its two defaults in the
same change: pass `extractUsage` and `extractCost`.

## Provider SPI: error mapping, published helpers, report types (#2811)

**`HttpErrorMapper.toError`.** `mapHttpError` returned a `Result[Nothing]` that was always a `Left`; `toError`
returns the error, and `headers` has no default.

| Before | After |
|---|---|
| `HttpErrorMapper.mapHttpError(status, body, provider, headers)` | `Left(HttpErrorMapper.toError(status, body, provider, headers))` |
| `HttpErrorMapper.mapHttpError(status, body, provider)` | `Left(HttpErrorMapper.toError(status, body, provider, Map.empty))` |

**Helpers you can now call.** `ToolCallRules.checked`, `parseArguments` and `wholeCall`, `PartEncoding.check` and
`encodeAll`, and `HttpErrorMapper.mapStreamError` are public. A provider outside `org.llm4s` no longer writes its
own tool-call check: pass the parsed `Completion` through `ToolCallRules.checked`.

| Before | After |
|---|---|
| `StreamingToolArgumentParser.parse(raw)` | `ToolCallRules.parseArguments(raw)` |
| `CostEstimator.estimateFromMetadata(metadata, usage)` | `CostEstimator.estimate(model, usage)` |

**`keylessWhen`.**

| Before | After |
|---|---|
| `keylessWhen = Some("authentication" -> "entra-id")` | `keylessWhen = Some(KeylessSetting("authentication", "entra-id"))` |
| `spec.keylessWhen.map(_._1)` | `spec.keylessWhen.map(_.key)` |

`withKeylessWhen(key, value)` still works and now takes more values.

**Registry report.** `ProviderRegistryReport`, `ProviderModuleReport`, `ProviderDiscoveryFailure` and
`ProviderIdCollision` are read-only: no public `apply`, no `with*` setters.

| Before | After |
|---|---|
| `collision.kind == "chat"` | `collision.kind == ProviderCollisionKind.Chat` |
| `collision.kind.endsWith("alias")` | `collision.kind.isAlias` |
| `module.providerIds.contains("openai")` | `module.providerIds.contains(ProviderId("openai"))` |
| `collision.id` as a `String` | `collision.id.asString` |

**Descriptors.** `EmbeddingProviderDescriptor.configSpec` is abstract: a descriptor that relied on the default adds
`val configSpec = EmbeddingConfigSpec()`. `AccessTokenProvider.invalidate` has a no-op default: an implementation
adds `override` and takes the rejected token as a `Secret` (see the next section), and a provider that caches
nothing can be a lambda.

`requireExtra` and `resolveBaseUrl` name the section in their hint (`llm4s.providers.my-section.region`), where
they printed `llm4s.providers.<name>.region`.

## Provider SPI: one shape for both descriptors, typed embedding sections, credential suppliers (#2811)

**Both descriptors have `buildConfig(section, context)` and `build(config, context)`.** What was a `using`
parameter, or a loose argument, is a field of the context.

| Before | After |
|---|---|
| `def buildConfig(providerName: String, section: NamedProviderConfig)(using ContextWindowResolver)` | `def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext)` |
| `providerName` | `context.providerName` |
| the `ContextWindowResolver` in scope | `given ContextWindowResolver = context.contextWindows` as the method's first line |
| `def buildClient(config: ProviderConfig, options: LlmClientOptions)(using ModelRegistryService)` | `def build(config: ProviderConfig, context: ProviderBuildContext)` |
| `options.metrics`, `options.exchangeLogging`, `options.httpClient` | `context.metrics`, `context.exchangeLogging`, `context.httpClient` (`context.options` is the whole `LlmClientOptions`) |
| the `ModelRegistryService` in scope | `given ModelRegistryService = context.modelRegistry` as the method's first line |
| `override def buildConfig(section: EmbeddingProviderSection, modelOverride: Option[String])` | `override def buildConfig(section: EmbeddingProviderSection, context: EmbeddingConfigContext)`, reading `context.modelOverride` |
| `def build(config: EmbeddingProviderConfig)` | `def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext)` |

A caller of a descriptor makes the context from what is in scope:

| Before | After |
|---|---|
| `descriptor.buildConfig("main", section)` | `descriptor.buildConfig(section, ProviderConfigContext.of("main"))` |
| `descriptor.buildConfig("main", section)(using resolver)` | `descriptor.buildConfig(section, ProviderConfigContext("main", resolver))` |
| `descriptor.buildClient(config, options)` | `descriptor.build(config, ProviderBuildContext.of(options))` |
| `descriptor.buildClient(config, LlmClientOptions.default)` | `descriptor.build(config, ProviderBuildContext.of())` |
| `embeddings.buildConfig(section, Some("model"))` | `embeddings.buildConfig(section, EmbeddingConfigContext(Some("model")))` |
| `embeddings.build(config)` | `embeddings.build(config, EmbeddingBuildContext())` |

`ProviderModuleChecks.buildClient` and `assertBuildsClient` in `llm4s-provider-testkit` keep their names and
arguments; `assertBuildsEmbeddingProvider` gains an optional `EmbeddingClientOptions`.

**An embedding provider can use the caller's HTTP client.**
`EmbeddingClient.from(provider, config, EmbeddingClientOptions().withHttpClient(client))` hands `client` to the
provider as `context.httpClient`. A provider of your own should send through it and not close it:

```scala
def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
  Right(MyEmbeddings(config, context.httpClient.getOrElse(Llm4sHttpClient.create(HttpClientOptions(config.network)))))
```

`assertEmbeddingUsesSuppliedHttpClient(descriptor, config)` in the module spec proves it.

**`Llm4sProviderModule.chatProviders` is `providers`.** An unprefixed name is the chat half everywhere in the SPI
(`ProviderDescriptor`, `ProviderRegistry.find`, `ids`, `of`) and `embedding*` the embedding half.

| Before | After |
|---|---|
| `override def chatProviders: Seq[ProviderDescriptor] = Seq(MyProvider)` | `override def providers: Seq[ProviderDescriptor] = Seq(MyProvider)` |
| `module.chatProviders` | `module.providers` |

**`EmbeddingProviderSection` is typed, and an embedding provider can declare keys of its own.**

| Before | After |
|---|---|
| `EmbeddingProviderSection(baseUrl = Some("https://x"), model = Some("m"))` | `EmbeddingProviderSection(baseUrl = Some(BaseUrl("https://x")), model = Some(ModelName("m")))` |
| `section.withBaseUrl("https://x").withModel("m")` | `section.withBaseUrl(BaseUrl("https://x")).withModel(ModelName("m"))` |
| `section.baseUrl` as a `String` | `section.baseUrl.map(_.asUrl)` |
| `section.model` as a `String` | `section.model.map(_.asString)` |

`EmbeddingConfigSpec(extras = Seq(ProviderConfigKey.required("region", "...")))` declares
`llm4s.embeddings.<id>.region`. The loader applies defaults and fails the load naming a required key that is
missing; the value is `section.extra("region")` in `buildConfig` and `config.extra("region")` in `build`. Keys you do
not declare are still ignored. Nothing to change for a provider that declares none.

**The three credential suppliers share one verb, and a token is a `Secret`.**

| Before | After |
|---|---|
| `tokens.token()` giving a `Result[String]` | `tokens.current()` giving a `Result[Secret]`; `.map(_.reveal)` where the string is sent |
| `def token(): Result[String] = Right(value)` | `def current(): Result[Secret] = Right(Secret.token(value))` |
| `override def invalidate(rejected: String): Unit` | `override def invalidate(rejected: Secret): Unit` |
| `AccessToken(value, expiresAt)` | `AccessToken(Secret.token(value), expiresAt)` |
| `accessToken.value` as a `String` | `accessToken.value.reveal` |
| `IdentityTokenSource` | `IdentityTokenProvider` |
| `source.fetch()` | `provider.current()` |
| `IdentityTokenSource.static("jwt")` | `IdentityTokenProvider.static(Secret("jwt"))` |
| `IdentitySource.Literal("jwt")` | `IdentitySource.Literal(Secret("jwt"))` |

`ApiKeyProvider.current()` is unchanged. `IdentitySource` keeps its name: it is the configured form, and
`IdentityTokenProvider.from(source)` reads it.

## The context pipeline is Experimental, and token counters are built over a tokenizer (#2818)

In `org.llm4s.context` (`llm4s-core`).

**Stability.** `ContextManager`, `ContextConfig`, `ContextStep`, `ManagedConversation`, `TokenWindow`,
`ConversationWindow`, `TokenUsageInfo`, `DeterministicCompressor`, `CompressionRule`, `ToolOutputCompressor`,
`ArtifactStore`, `LLMCompressor`, `HistoryCompressor`, `SemanticBlocks`, `SemanticBlock` and `SemanticBlockType` are
`@Experimental`: they work as before, but are outside the 1.x compatibility promise. `ConversationTokenCounter`,
`TokenBreakdown`, `MessageTokenInfo` and `org.llm4s.context.tokens` stay `@Stable`.

**Token counters.** A counter is built over a tokenizer, never over a model name, because which vocabulary a model
uses is its vendor's knowledge:

| Before | After |
|---|---|
| `ConversationTokenCounter.forModel("gpt-4o")` | `OpenAITokenizers.counterFor("gpt-4o")` (`org.llm4s.llmconnect.provider`, in `llm4s-openai`) |
| `ConversationTokenCounter.forModel("claude-...")`, or any model that is not OpenAI's | `ConversationTokenCounter.cl100k()`: the same `cl100k_base` approximation, now asked for by name |
| `ConversationTokenCounter.openAI()` | `ConversationTokenCounter.cl100k()` |
| `ConversationTokenCounter.openAI_o200k()` | `ConversationTokenCounter.o200k()` |
| `ConversationTokenCounter(tokenizerId)` | `ConversationTokenCounter.create(tokenizerId)` |
| `TokenizerMapping.getTokenizerId(model)` | `OpenAITokenizers.tokenizerFor(model)`, an `Option`: `None` for a model that is not OpenAI's |
| `TokenizerMapping.getAccuracyInfo(model)`, `isExactMapping(model)`, `TokenizerAccuracy` | removed: a count is exact when `OpenAITokenizers.tokenizerFor(model)` is defined, an approximation otherwise |
| `import org.llm4s.identity.TokenizerId` | `import org.llm4s.context.tokens.TokenizerId` (the scalafix rule `ResultSyntaxMoved` rewrites it) |

`OpenAITokenizers.counterFor` returns a `Left(TokenizerError)` for a model it does not recognise, where `forModel`
fell back to `cl100k_base` with a warning; write the fallback yourself where you want it:

```scala
val counter = OpenAITokenizers.counterFor(model).orElse(ConversationTokenCounter.cl100k())
```

`gpt-4.1`, `gpt-4.5`, `gpt-5`, `o3` and `o4-mini` now count with `o200k_base`; counts for those models change.

To count with a tokenizer of your own, implement `StringTokenizer` and pass it to `ConversationTokenCounter.of`.
Token counting calls the new `StringTokenizer.count(text)`, which defaults to `encode(text).length`; override it when
counting is cheaper than building the list.

**`ContextManager`.** The constructor is private and `withDefaults` is gone: `ContextManager.create(counter)` (its
`config` now defaults to `ContextConfig.default`) is the one factory. It returns a `Left(ValidationError)` for a
negative `maxSemanticBlocks` or a `summaryTokenTarget` that is not positive; it no longer checks the headroom, which
cannot be invalid any more (see the `org.llm4s.types` section below).

**Removed and retyped members.**

- `ContextConfig.enableSubjectiveEdits` and `withEnableSubjectiveEdits` are removed. `ContextManager` never read
  them. Delete the argument; to apply the subjective rules, call
  `DeterministicCompressor.compressToCap(messages, counter, cap, enableSubjectiveEdits = true)`.
- `ContextStep.name: String` is `ContextStep.stage: ContextStage` (`ToolDeterministicCompaction`,
  `HistoryCompression`, `LLMHistorySqueeze`, `FinalTokenTrim`); `withName` is `withStage`. `step.stage.toString` is
  the old name.
- `MessageTokenInfo.role` is a `MessageRole`; `role.name` is the old string.
- `TokenWindow.trimToBudget` and `fitsInBudget` take `headroom: HeadroomPercent` (default
  `HeadroomPercent.Standard`) where they took `headroomPercent: Double`.
- `StructuredInfo`, `HistoryDigest` and `PipelineStep` are private; they appeared in no public signature.
- `Token` and `TokenizerId` are final.

## `Modality` is an enum, multimodal embedding lives in `llm4s-rag`, and `EmbeddingClient` is final (#2808)

**`Modality`.** The four cases are nested: write `Modality.Text`, `Modality.Image`, `Modality.Audio` and
`Modality.Video`, or `import org.llm4s.llmconnect.model.Modality.*`. A wildcard import of
`org.llm4s.llmconnect.model` no longer puts a bare `Text` and `Image` beside `ContentPart.Text` and
`ContentPart.Image`.

**The multimodal embedding types** keep their package (`org.llm4s.llmconnect.model`) and move from `llm4s-core` to
`llm4s-rag`; add that dependency if you used them without it.

- `EmbeddingVector.values` is a `Seq[Double]` (it was an `Array[Float]`), the element type of
  `EmbeddingResponse.embeddings`. `dim` is the number of values: drop the `dim` argument of `EmbeddingVector(...)`,
  and `withDim` is gone.
- `RawMediaInput(bytes, mime)` copies the array; read it back as `data: IArray[Byte]`, and its size as `sizeBytes`
  (was `bytesApprox`). Two inputs with the same bytes and MIME type are equal.
- `MultimediaEmbeddingRequest.inputs` is a `Seq[RawMediaInput]`. `TextChunkInput`, `ImageInput`, `AudioInput`,
  `VideoInput` and the `MMInput` trait are removed: nothing in llm4s built or read them. Send media as
  `RawMediaInput`.

**Multimodal embedding is its own capability.** `EmbeddingProvider.embedMultimodal`, whose default answered every
provider's call with a `501`, is removed, with `EmbeddingClient.embedMultimodal` and
`CachedEmbeddingClient.embedMultimodal`. Implement `org.llm4s.llmconnect.provider.MultimodalEmbeddingProvider`
(`llm4s-rag`) instead, and hand it to `FileEmbedder`:

```scala
// before: an EmbeddingProvider that overrode embedMultimodal, wrapped in the EmbeddingClient
FileEmbedder.encodeFromPath(path, client, config)

// after
val multimodal: MultimodalEmbeddingProvider = request => myEndpoint.embed(request)
FileEmbedder.encodeFromPath(path, client, config, multimodal = Some(multimodal))
```

Without one, an image, audio or video file fails with an `EmbeddingError` (no code) that says to pass a provider or
set `experimentalStubs`; it used to fail with code `501`.

**`EmbeddingClient`** is a final class with a private constructor.

| Before | After |
|---|---|
| `new EmbeddingClient(provider)` | `EmbeddingClient(provider)` |
| `new EmbeddingClient(provider, Some(tracer), "query", Some(id))` | `EmbeddingClient(provider).withTracing(tracer).withOperation("query").withProviderId(id)` |
| `class Fake extends EmbeddingClient(...)`, `mock[EmbeddingClient]` | implement or mock `EmbeddingProvider`, and wrap it: `EmbeddingClient(fake)` |

`EmbeddingClient.from(provider, cfg)` returns the registry's `ConfigurationError` when no embedding provider has
that name. It used to rewrite it to `EmbeddingError(code = Some("400"), provider = "config")`; match on
`ConfigurationError` instead.

**`EmbeddingError`** is `org.llm4s.error.EmbeddingError` (it was in `org.llm4s.llmconnect.model`). Its fields are
unchanged. The scalafix rule `ResultSyntaxMoved` rewrites the import.

## Newtypes in `org.llm4s.types` are opaque, and `HeadroomPercent` is always valid (#2822)

- **`HeadroomPercent`** has no public constructor: use `HeadroomPercent.create(0.1)`, a `Result`, or a preset.
  `HeadroomPercent.None` is `HeadroomPercent.Zero` (it shadowed `scala.None`); `isValid` is gone, since every value
  is valid; read the ratio with `asRatio` (`value` is gone). It no longer prints as a percentage: format
  `asRatio * 100` where you showed one.
- **`TokenBudget`, `ContextWindowSize`, `ByteCount` and `ExternalizationThreshold`** were aliases of `Int` and
  `Long` and checked nothing; write `Int` (`TokenBudget`, `ContextWindowSize`) or `Long`. `LLMClient.getContextBudget`
  returns an `Int`, as it always did.
- **`SemanticBlockId`, `ArtifactKey` and `ContentSize`** are opaque types. Build them as before
  (`ArtifactKey("k")`, `ContentSize.fromString(text)`); read them with `id.asString`, `key.asString` and
  `size.asBytes` where you wrote `.value` and `.bytes`. They have no `copy`, and they do not match as case-class
  patterns.
- **`ExternalizedContent`** is private to `ToolOutputCompressor`; it appeared in no public signature.
- **`TraceId`** is `org.llm4s.trace.model.TraceId`, in `llm4s-observability`, which was its only user. The scalafix
  rule `ResultSyntaxMoved` rewrites the import.

## Model ids are `ModelName`, provider ids `ProviderId`, and provider configs' base URLs `BaseUrl` (#2822)

Every public signature that takes or returns a model id takes or returns an
`org.llm4s.types.ProviderModelTypes.ModelName`, where it was a `String`; a provider config's base URL is a `BaseUrl`.
Wrap a literal with `ModelName("gpt-4o")` / `BaseUrl("https://...")`, and read one back with `.asString` / `.asUrl`.
A `ModelName` no longer compares equal to a `String` at compile time: `completion.model == "gpt-4o"` does not
compile; write `completion.model == ModelName("gpt-4o")` or `completion.model.asString == "gpt-4o"`.

- **Completions and usage:** `Completion.model` and `withModel`; `UsageSummary.byModel` is a
  `Map[ModelName, ModelUsage]` and `add` takes a `ModelName`; `CostTracker.byModel` likewise; the agent's
  `ModelCallCompleted.model`, `ModelContext.recordUsage` and `AdmissionContext.recordUsage`.
- **The model registry:** `ModelMetadata.modelId` (and `withModelId`, `fromJson`), `ModelRegistryService.lookup`,
  `RequestTransformer` (every `modelId`, `adjusted`'s function, `withOverrides`' map), `TransformationResult.transform`,
  `CostEstimator.estimate`, `ContextWindowResolver.resolve` (`fallbackResolver: ModelName => ContextLimits`) and
  `strictContextWindow`.
- **Embeddings:** `EmbeddingModelConfig.name`, `EmbeddingConfigContext.modelOverride`, `EmbeddingConfigSpec.defaultModel`
  (and its `defaultBaseUrl`, a `BaseUrl`), `EmbeddingProviderDescriptor.modelDimensions` (`Map[ModelName, Int]`) and
  `dimensionsOf`, `ModelDimensionRegistry.getDimension`, `CacheKeyGenerator.embeddingKey` and the key function of
  `CachedEmbeddingClient` (`(String, ModelName, InputPurpose) => String`).
- **Tracing and exchange logs:** the `model` (and `responseModel`) of `TraceEvent.CompletionReceived`, `ErrorOccurred`,
  `TokenUsageRecorded`, `EmbeddingUsageRecorded`, `CostRecorded` and `ImageGenerationCompleted`, and the `Tracing`
  helpers that build them; `ProviderExchange.model` and `ProviderExchangeCall.model`.
- **Provider SPI:** `BaseLifecycleLLMClient.modelName`, `StreamingAccumulator(model)`,
  `OpenAICompatibleClient.Settings` (`model: ModelName`, `baseUrl: BaseUrl`), `OpenAICompatibleDialect.validationUrl`
  and `addReasoning`, `ProviderConfigSpec.defaultBaseUrl` and `apiKeyAndDefaultBaseUrl`,
  `ProviderDescriptor.resolveBaseUrl` (a `Result[BaseUrl]`), `NamedProviderConfig.baseUrlOrDefault`.
- **Provider configs:** every `fromValues` takes a `ModelName` and a `BaseUrl`; each config's `baseUrl` (Azure's
  `endpoint`), `withBaseUrl` and `DEFAULT_BASE_URL` are `BaseUrl`, as are `OpenAIProvider`, `OpenRouterProvider` and
  `RequestyProvider`'s `DEFAULT_BASE_URL`; `MistralConfig.apiBaseUrl` and `CohereConfig.compatibilityBaseUrl` return one.
- **Capabilities:** `ModelRef.model` and `requireCapabilities(provider, model)`.

```scala
// Before
val config = OpenAIConfig.fromValues("gpt-4o", apiKey, "https://api.openai.com/v1")
val done   = Completion(id = "1", created = Instant.EPOCH, model = "gpt-4o", message = AssistantMessage("hi"))
summary.byModel.get("gpt-4o")

// After
val config = OpenAIConfig.fromValues(ModelName("gpt-4o"), apiKey, BaseUrl("https://api.openai.com/v1"))
val done   = Completion(id = "1", created = Instant.EPOCH, model = ModelName("gpt-4o"), message = AssistantMessage("hi"))
summary.byModel.get(ModelName("gpt-4o"))
```

Nothing stored changes: a `ModelName` is written as the JSON string it wraps, so stored usage summaries, durable
agent events, model-metadata JSON, trace events and the provider exchange log read and write byte for byte as before.
The Java facade (`llm4s-java-api`) still presents model ids as `String`s.

The scalafix rule `ModelIdNewtypes` (part of `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`) wraps the argument at the common call sites:
`Completion`'s `model`, `ModelMetadata`'s `modelId`, `EmbeddingModelConfig`'s name, `lookup`, `estimate`,
`UsageSummary.add`, `StreamingAccumulator`, `withModel`, and a provider config's `fromValues` and `withBaseUrl`. A
`byModel` map key, a function typed `String => ...` and a model compared with a `String` are left for the compiler to
report.

### Provider ids are `ProviderId`

Where a value is a provider's id (the registry's vendor, such as `openai` or `bedrock_converse`), it is a
`ProviderId`, canonicalised to trimmed lowercase as `ProviderId("OpenAI") == ProviderId("openai")`:

- `ModelMetadata.provider` and `withProvider`;
- `ModelRegistryService.listProviders()` (a `List[ProviderId]`), `listByProvider(provider)` and
  `lookup(provider, modelName)`;
- `ContextWindowResolver.resolve`'s `lookupProviders` (a `Seq[ProviderId]`) and `strictContextWindow(provider, ...)`;
- `ModelDimensionRegistry.getDimension(provider, model)`;
- `ModelRef.provider` and `requireCapabilities(provider, model)` (`llm4s-capabilities`).

```scala
// Before
registry.lookup("openai", "gpt-4o")
ModelDimensionRegistry.getDimension("openai", "text-embedding-3-small")

// After
registry.lookup(ProviderId("openai"), ModelName("gpt-4o"))
ModelDimensionRegistry.getDimension(ProviderId("openai"), ModelName("text-embedding-3-small"))
```

A `ProviderId` is written as its string. A stored provider that was not lowercase reads back lowercased; LiteLLM's
model data, the model-metadata JSON llm4s writes and the agent's events only ever hold lowercase ids. `ModelIdNewtypes`
wraps `lookup`, `listByProvider`, `getDimension`, `withProvider` and `ModelMetadata`'s `provider`.

Left as `String` on purpose, being labels rather than ids: `MetricsCollector`'s `model` and `provider` (and
`MetricsRecording.withMetrics` and `ReliableClient`'s `providerName`, which pass them through); `OpenAICompatibleClient.Settings`'
`providerName`; and `ThinkingBlock.Opaque.provider`, part of a stored message. (The `provider` of the error types and of
`HttpErrorMapper` and `PartEncoding`, left a `String` then, is a `ProviderId` since
[#2936](#an-errors-provider-is-a-providerid-2936).) The model of the image, speech and RAG
configs (`RAGConfig.embeddingModel`, the image generation configs) also stays a `String`: no provider config or the
registry reads it.

## One retry mechanism: `LLMClientRetry` and `ErrorRecovery` are removed (#2798)

`ReliableClient` with a `RetryPolicy` is the library's one retry and circuit breaker.

| Before | After |
|---|---|
| `LLMClientRetry.completeWithRetry(client, conversation, options, maxAttempts = 3, baseDelay = 1.second)` | `ReliableClient(client, "openai", ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second))).complete(conversation, options)` |
| `LLMClientRetry.streamCompleteWithRetry(...)` | the same client's `streamComplete`; a stream that has delivered a chunk is still not retried |
| `ErrorRecovery.recoverWithBackoff(() => client.complete(c), 3, 1.second)` | the `ReliableClient` above |
| `new ErrorRecovery.CircuitBreaker[A](failureThreshold, recoveryTimeout)` | `ReliabilityConfig(circuitBreaker = CircuitBreakerConfig(failureThreshold, recoveryTimeout))` |
| `ErrorRecovery.CircuitState` / `Open` / `Closed` / `HalfOpen` | `org.llm4s.reliability.CircuitState` |

What changes in behaviour:

- When the attempts run out you get the last attempt's error unchanged, not an `ExecutionError` wrapping it.
- An open circuit refuses a call with a `CircuitOpenError`, which is not retried. `ErrorRecovery.CircuitBreaker`
  returned a retryable `ServiceError` 503.
- Waits come from the `RetryPolicy` (exponential by default). `LLMClientRetry`'s fixed 30 second cap is gone: set
  `maxDelay`.
- `ReliabilityConfig` has a deadline, five minutes by default; past it the result is a `TimeoutError`.

`ReliableClient` retries model calls. To retry an operation of your own, use a `RetryPolicy` directly:
`policy.isRetryable(error)` and `policy.delayFor(attemptNumber, error)`.

## An agent run's trace and metrics report one status (#2829)

`llm4s.agent.runs.ended` and `llm4s.agent.run.duration` took their `status` label from the runtime's terminal event, so a
guardrail block counted as `failed` and a turn that hit a limit as `completed`, while the run's trace reported `blocked`,
`step_limit_reached` and `token_limit_reached`. Both now report one status, a `TraceEvent.RunStatus`.

| `status` label before | After |
|---|---|
| `failed`, for a guardrail block | `blocked` |
| `completed`, for a turn that reached `withMaxSteps` | `step_limit_reached` |
| `completed`, for a turn that reached `RunBudgets.maxTokens` | `token_limit_reached` |
| `completed`, for an answer cut off at the output limit | `truncated` |

- **Check alerts and dashboards on `status="failed"` and `status="completed"`.** A failure-rate alert no longer fires on
  guardrail blocks; add `status="blocked"` if it should. A completion rate no longer counts limited or cut-off turns.
- No label names the guardrail: that is the trace's `blocked_by`. A graph that is not an agent turn still ends
  `completed`, `suspended`, `failed`, `cancelled` or `timed_out`.

`TraceEvent.AgentRunEnded` changes with it:

| Before | After |
|---|---|
| `status: String` (`"completed"`, ..., `"blocked:<guardrail>"`) | `status: TraceEvent.RunStatus`. `status.name` is the old string, except that a block is `"blocked"` and `blockedBy` is `Some(guardrail)` |
| `Set("failed", "cancelled", "timed_out").contains(e.status)` | `e.status.isError` |
| `TraceEvent.AgentRunEnded(thread, run, agent, "completed", messages, usage)` | `TraceEvent.AgentRunEnded(thread, run, agent, TraceEvent.RunStatus.Completed, messages, usage)` |
| `agent`: the agent active when the run ended | `agent`: the run's root agent. `activeAgent`: the agent active when it ended |

- The `invoke_agent <agent>` span (OpenTelemetry) and observation (Langfuse) of a run that handed off is named after the
  agent the run was started on, with the agent active at the end as `active_agent`. A query that groups runs by span name
  or `gen_ai.agent.name` now files such a run under its root agent.
- In `toJson`, Langfuse's metadata, OpenTelemetry's `status` attribute and `TraceCollector`, a blocked run's `status` is
  `blocked` and the guardrail is `blocked_by`. A filter on a `status` starting `blocked:` matches nothing from this
  version on.
- `AgentRunEnded` has fourteen fields: match `case e: TraceEvent.AgentRunEnded` and read its fields.
- An agent's `chat <model>` span has the model call's duration, where it was a point at the call's end: a query on
  span duration now sees model calls. The durable `AgentEvents.ModelCallCompleted` gains `duration: FiniteDuration`
  (stored in milliseconds; zero for usage recorded without a call and in an event stored before), so a positional
  pattern on it takes an eleventh binder, which the `PositionalPatternsGainFields` Scalafix rule adds: match
  `case AgentEvents.ModelCallCompleted(m)` and read its fields.

## `TraceEvent` carries an error as it is, and `AgentInitialized` is removed (#2800)

`ErrorOccurred` held a `Throwable`, but an `LLMError` is not one, so the library wrapped each in
`new RuntimeException(error.message)`: a backend reported `RuntimeException` as the type, with a stack trace of the
tracing code. The event now holds the error's `message`, its `errorType` and an optional `cause`.

| Before | After |
|---|---|
| `TraceEvent.ErrorOccurred(throwable, context)` | `TraceEvent.ErrorOccurred.of(throwable, context)` |
| `TraceEvent.ErrorOccurred(new RuntimeException(error.message), context, errorType = Some(...))` | `TraceEvent.ErrorOccurred.of(error, context)` for an `LLMError` |
| `tracing.traceError(new RuntimeException(error.formatted))` | `tracing.traceError(error, context)` |
| `e.error.getMessage`, `e.error.getClass.getSimpleName` | `e.message`, `e.errorType` |
| `e.error` | `e.cause`: `Some` for an error built from a `Throwable`, `None` for an `LLMError` |
| `e.errorType: Option[String]` | `e.errorType: String` |
| `e.withError(throwable)` | `e.withMessage(...)`, `withErrorType(...)`, `withCause(throwable)` |
| `TraceEvent.AgentInitialized(query, tools)` | Removed: nothing in the library emitted it. Trace your own with `TraceEvent.CustomEvent(name, data)` |
| `TraceEvent.ImageGenerationCompleted(model, provider, operation, ...)`, `provider: String` | `TraceEvent.ImageGenerationCompleted(model, operation, ..., provider = Some(ProviderId("openai")))`. `provider: Option[ProviderId]` is the last field |
| `case TraceEvent.ToolExecuted(name, _, _, _, _, _)` | `case e: TraceEvent.ToolExecuted => e.name` |

- **The recorded type of a library error changes.** The errors `TracingLLMClient`, `EmbeddingClient` and an agent run
  trace are `LLMError`s: `error_type` (Langfuse metadata, `TraceCollector`, the console) and OpenTelemetry's
  `error.type` on an `Error` span are now the error's own type, such as `RateLimitError` or `NodeFailed`, where they
  were `RuntimeException`. They carry no stack trace: Langfuse's `stack_trace` is empty and OpenTelemetry records no
  `exception` event for them. An error traced from a `Throwable` is recorded as before.
- An exception without a message has the message `""`, where it was `null`.
- `ErrorOccurred.of(throwable, context)` reads the exception's message when the event is built, so
  `traceError(throwable)` reads it even into a `NoOpTracing`.
- Langfuse no longer creates the `LLM4S Agent Run` trace `AgentInitialized` produced, and OpenTelemetry no longer the
  span of that name; an agent run's trace comes from `AgentRunEnded`.
- Events are growth-safe to build (`apply` and the `with*` setters) but a positional pattern names every field, so it
  stops compiling when one is added: match by type. The scalafix rule `PositionalPatternsGainFields` no longer pads a
  positional `ErrorOccurred` pattern, because its first field changed type.

## A guardrail's reason is never stored (#2853)

A guardrail's reason can quote the text it refused, so it is no longer written to the checkpoint store. It is on the
result of the run that was blocked, and nowhere else.

| Before | After |
|---|---|
| `case AgentStatus.Blocked(guardrail, reason)` with `reason: String` | `reason: Option[String]`: `Some` on the blocked run's result, `None` on a status rebuilt from the thread |
| `RunOutcome.Blocked(guardrail, reason)` | `RunOutcome.Blocked(guardrail)` |
| `TaskRefusal.Blocked(guardrail, reason: String)` | `reason: Option[String]`; `describe`, the parent model's tool error, names the guardrail only |
| the reason read back from a thread later | keep it yourself when the run returns it |

- **Checkpoint format 9.** A format-8 checkpoint loads, and the reason its `Blocked` outcome held is dropped as it is
  read. Rows written before keep the reason in the store until they are pruned or their thread is deleted. A build
  from before this change refuses a format-9 checkpoint. Neither SQL store's schema changes.
- **`EncryptingCheckpointer` seals an outcome's error text**: a `RunOutcome.Failed`'s message and a
  `RunOutcome.OutputInvalid`'s errors. Its case, a guardrail's name and a failure's kind stay readable. A store
  encrypted before this change holds those texts in plain, which a read now refuses (`EncryptionError.NotEncrypted`)
  unless `EncryptionSettings().withPlaintextReads(true)` is set while old rows remain. A failed run's message is still
  in its `RunFailed` event in plain.
- Java and Kotlin: `JAgentStatus.reason()` was already an `Optional`; it is empty on a status read back from a thread.

## A turn's query is admitted before the turn is stored (#2854)

A turn's first checkpoint holds its query, and every middleware hook ran after it was written. `AgentMiddleware.admit`
is a new stage that runs first: it may rewrite the query, and only the rewrite is stored, or refuse it with
`block(reason)`, and then nothing of the query is stored. Input guardrails run there.

| Before | After |
|---|---|
| a `beforeAgent` hook that redacts, to keep text out of the store | override `admit(query, context: AdmissionContext): Result[String]`; `beforeAgent` still rewrites what joins the conversation, after the first checkpoint |
| input guardrails ran after other middleware's `beforeAgent`, on what those returned | they run before every `beforeAgent`, on the query as submitted: a rewrite a guardrail depends on belongs in `admit` |
| `GuardrailMiddleware(input, output).beforeAgent(text, context)` called directly | `.admit(text, context)` with an `AdmissionContext` (`MiddlewareTestKit.admissionContext()`) |
| `override def validate(value: A, context: ModelContext)` in a guardrail | `override def validateIn(value: A, context: UsageAccounting)` ([renamed](#withtoolset-and-validatein-and-the-java-facades-guardrails-sit-innermost-2835)), which a `ModelContext` and an `AdmissionContext` both are; match on `ModelContext` for thread state |
| `GuardrailBlocked(guardrail, reason: String)` | `reason: Option[String]`; `block(reason)` builds it |
| `submitter.submit(thread, agent.id, QueuedInput.query(text))`, the query in the queue as submitted | `agent.admit(thread, text).map(QueuedInput.admitted)` first, to keep refused or unrewritten text out of the queue |

- `admit` cannot read or update thread state, emit an event, ask a question or call a tool; a middleware that asks
  from it is refused. `GuardrailReviewMiddleware`, which asks, still runs in `beforeAgent`.
- A refused query still starts a run, which holds the refusing guardrail's name and no query, so the
  `agent.guardrail_blocked` event, the `RunFailed` and a queue entry's close are as before.
- The reason is on the result of the call that admitted and ran the turn. A queued entry admitted by its submitter,
  and a turn held at a breakpoint before its first step and resumed, end `AgentStatus.Blocked(guardrail, None)`.
- Admission runs before the thread is claimed: a start the runtime then refuses (`ThreadBusy`, ...) has had its
  guardrails run.
- No checkpoint format or store schema change: an input stored before reads as not yet admitted, and its input step
  admits it.

## A tool handler has one error type (#2815)

A handler returned `Either[String, R]`, so every failure became a `ToolCallError.HandlerError` and the structured
errors (`InvalidArguments`, `ToolParameterError.MultipleErrors`, the `parameterErrors` of `ToolCallErrorJson`) could
only be reached through `executeEnhanced`, which ignored the tool's own handler. A handler now returns
`Either[ToolHandlerError, R]`, where `ToolHandlerError` is a sealed trait with a `message`: a `ToolParameterError`,
which is what a getter returns, or a `ToolHandlerError.Failed(message)`, the tool's own failure, built with
`ToolHandlerError.failed(message)`.

A getter's error returned as it is becomes `ToolCallError.InvalidArguments(toolName, parameterErrors)`; a `Failed`
is still a `HandlerError`. A `String` is no longer a handler's error, so a `Left("message")` becomes
`Left(ToolHandlerError.failed("message"))`:

```scala
.withHandler { args =>
  for {
    city    <- args.getString("city")                                                // Left(ToolParameterError)
    weather <- lookup(city).toRight(ToolHandlerError.failed(s"no such city: $city")) // Left(ToolHandlerError.Failed)
    report  <- render(weather).left.map(ToolHandlerError.failed)                     // a helper's Either[String, A]
  } yield report
}
```

| Before | After |
|---|---|
| `getString`, `getInt`, `getDouble`, `getBoolean`, `getArray`, `getObject`: `Either[String, A]` | the same names return `Either[ToolParameterError, A]` |
| `getStringEnhanced`, `getIntEnhanced`, ... | `getString`, `getInt`, ... |
| `args.getOptionalInt("n").left.map(_.getMessage)` | `args.getOptionalInt("n")` |
| `def handler(args: SafeParameterExtractor): Either[String, R]` | `Either[ToolHandlerError, R]` |
| `Left("message")`, `.toRight("message")`, `Either.cond(ok, value, "message")` in a handler | `Left(ToolHandlerError.failed("message"))`, `.toRight(ToolHandlerError.failed("message"))`, `Either.cond(ok, value, ToolHandlerError.failed("message"))` |
| a helper's `Either[String, A]` used in a handler: `result <- convert(value)` | `result <- convert(value).left.map(ToolHandlerError.failed)` |
| `args.getString("x")` where an `Either[String, A]` is needed | `args.getString("x").left.map(_.message)` |
| `tool.handler(args)` read as an `Either[String, R]` | `tool.handler(args).left.map(_.message)` |
| `tool.executeEnhanced(args, handler)` | `tool.execute(args)`: the tool's own handler reports structured errors |
| `SafeParameterExtractor.enhanced(json)` | `SafeParameterExtractor(json)` |
| `validateRequired("a" -> "string", "n" -> "integer")`: `Either[List[ToolParameterError], Unit]` | `validateRequired("a" -> ToolParameterType.String, "n" -> ToolParameterType.Integer)`: `Either[ToolParameterError, Unit]`, a `MultipleErrors` when several |
| `ToolCallErrorJson.toJson(error)("error")` | `("message")`, or `error.formatted` for the old text |
| `ToolBuilder[Map[String, Any], String](name, description, schema)` | `ToolBuilder[String](name, description, schema)` |
| `ToolFunction[A, R]`, `Seq[ToolFunction[_, _]]`, `ToolFunction[?, ?]` | `ToolFunction[R]`, `Seq[ToolFunction[_]]`, `ToolFunction[?]` |
| `tool.withSchema[U](schema)`: a `ToolFunction[U, R]` | `tool.withSchema(schema)`: the same `ToolFunction[R]` |

`ToolFunction` and `ToolBuilder` had two type parameters, and the first, the arguments type, was a phantom: no member
read it, every built-in tool set it to `Map[String, Any]`, and every consumer took `ToolFunction[_, _]`. It is gone:
`ToolFunction[R]` and `ToolBuilder[R]` keep the handler's result type, and take any `SchemaDefinition[_]`. The typed
tools of `llm4s-schema-derivation` (`Tool.of[A, R]`, `Tool.scoped`, `Tool.fromMethod`) keep their own type parameters
and return a `ToolFunction[R]`. The Scalafix rule `ToolFunctionOneTypeParameter` drops the first type argument
wherever both are written. A type parameter of your own that only named the arguments
(`def tool[T, R](...): ToolFunction[T, R]`) is left declared and unused: delete it by hand.

What changes without a compile error:

- **A missing or mistyped argument is `InvalidArguments`, not `HandlerError`**, for every tool whose handler returns
  the getter's error, the built-in tools included. `error.message` loses the `failed with error: ` prefix, and
  `ToolCallErrorJson.toJson` reports `errorType: "invalid_arguments"` with a `parameterErrors` array. Code that
  matched on `HandlerError` to find an argument problem matches `InvalidArguments`.
- **`validateRequired` takes types, not names.** A name it did not know (`"uuid"`) was checked for presence only;
  that no longer compiles.
- **A property whose own name has a dot** is read by that name: `getString("a.b")` returns the top-level property
  `a.b` when there is one, and otherwise `b` inside `a` as before.
- A handler whose every error is a getter's has the type `Either[ToolParameterError, R]`, which is an
  `Either[ToolHandlerError, R]`: nothing to change. `ToolHandlerError.failed` returns a `ToolHandlerError`, not a
  `Failed`, so a `for` that mixes it with getters needs no type written.

`ToolHandlerError` was briefly the union `String | ToolParameterError` on `main`, never in a release. A union in
a public signature cannot be read by the Scala 2.13 TASTy reader (`Unsupported Scala 3 union`), which broke every
2.13 caller that named `CompletionOptions`, and Java sees it as `Object`; hence the sealed trait. `Tool.of` in
`llm4s-schema-derivation` and the Java facade's `JTool` are unchanged: a `Tool.of` handler still returns
`Either[String, R]`.

`ToolCallError` is not an `LLMError`: a tool call's failure is sent back to the model as a result. Where a program
must stop on one, map it there: `result.left.map(e => ProcessingError("tool", e.formatted))`.

No Scalafix rule rewrites the getters: their names are unchanged and only the error type differs. The type
arguments are rewritten by `ToolFunctionOneTypeParameter`.

## A tool call's id is a `ToolCallId`, and its idempotency key an `IdempotencyKey` (#2815)

`ToolCall.id`, `ToolMessage.toolCallId` and `ToolCallInfo.toolCallId` were `String`s, and `ToolCallInfo.idempotencyKey`
a `String`, while the agent held its own `ToolCallId` and `IdempotencyKey`. There is one of each now, in core: opaque
types over `String`, built with `apply` and read with `asString`. `ToolCallId` is in `org.llm4s.llmconnect.model`, beside
`ToolCall`, and `IdempotencyKey` in `org.llm4s.toolapi`, beside `ToolCallInfo`. The agent's events (`ToolExecuted`,
`ToolCallStarted`, `ToolCallResult`) and `MessageUpdate.EditToolCall` carry them too.

```scala
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolCall, ToolCallId, ToolMessage }

val call   = ToolCall(ToolCallId("call_1"), "weather", ujson.Obj("city" -> "Paris"))
val turn   = AssistantMessage(None, Seq(call))
val result = ToolMessage("sunny", call.id)
```

| Before | After |
|---|---|
| `ToolCall("c1", name, args)`, `call.withId("c1")` | `ToolCall(ToolCallId("c1"), name, args)`, `call.withId(ToolCallId("c1"))` |
| `ToolMessage(text, "c1")`, `Message.tool(text, "c1")` | `ToolMessage(text, ToolCallId("c1"))`, `Message.tool(text, ToolCallId("c1"))` |
| `ToolCallInfo("c1", "key")` | `ToolCallInfo(ToolCallId("c1"), IdempotencyKey("key"))` |
| `call.id` or `info.idempotencyKey` where a `String` is needed (a header, a map key, `s"..."` is fine as it is) | `call.id.asString`, `info.idempotencyKey.asString` |
| `import org.llm4s.agent.graph.{ IdempotencyKey, ToolCallId }` | `import org.llm4s.llmconnect.model.ToolCallId` and `import org.llm4s.toolapi.IdempotencyKey` |
| `toolCallId.value`, `key.value` (the agent's types) | `toolCallId.asString`, `key.asString` |
| `ToolCallId(request.call.id)` | `request.call.id`, or `request.toolCallId` |
| `ToolExecuted(agent, "c1", ...)`, `.idempotencyKey: Option[String]` | `ToolExecuted(agent, ToolCallId("c1"), ...)`, `Option[IdempotencyKey]` |

Stored conversations, checkpoints and events are unchanged: both types are written as the plain JSON strings they
were. Java and Kotlin see a `String` (an opaque type erases to it), and the Java facade's `JToolCall.id()`,
`JMessage.toolCallId()` and `JToolArgs` keep returning `String`s.

A comparison with a `String` no longer compiles, `call.id == "c1"` and ScalaTest's `call.id shouldBe "c1"` alike:
compare with `ToolCallId("c1")`, or read `call.id.asString`.

The Scalafix rule `ToolCallIdTyped` (also in `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`) wraps an id or key set from a `String`, adds
`.asString` to every other read, and moves the agent's imports ([Scalafix](scalafix)).

## `SchemaDefinition` gains a union and a raw JSON Schema case (#2814)

`SchemaDefinition` is sealed, so its cases had to be complete before the baseline. Two are new:

- `AnyOfSchema` (`Schema.anyOf(description, first, rest*)`): a value that matches at least one alternative.
- `RawJsonSchema` (`Schema.raw(ujson.Obj(...))`): a JSON Schema written as JSON, sent as written whatever `strict` is.

**An exhaustive match on `SchemaDefinition` needs a case for each.**

| Before | After |
|---|---|
| `schema.withRange(Some(1), Some(5))` | `schema.withMinimum(1).withMaximum(5)` |
| `schema.withExclusiveRange(Some(0.0), None)` | `schema.withExclusiveMinimum(0.0)` |
| `schema.withLengthConstraints(Some(1), Some(80))` | `schema.withMinLength(1).withMaxLength(80)` |
| `schema.withSizeConstraints(max = Some(10))` | `schema.withMaxItems(10)` |
| `SchemaOf[SumType].definition` threw `UnsupportedOperationException` | it is an `AnyOfSchema` |

The removed setters replaced both bounds at once, so `withRange(max = Some(5))` cleared a minimum set before. To
clear a bound now, pass `None` to its own setter: `withMinimum(None)`.

Behaviour that changes without a compile error:

- **A strict schema makes optional properties nullable.** `toJsonSchema(strict = true)` (and so
  `toolDefinition(strict = true)`, which the OpenAI-compatible clients send) lists every property as required, as
  before, and now adds `null` to the type of each property that is not required. Before, `required = false` was
  dropped and the model had to invent a value. A handler reads such a property with `getOptional*`, which returns
  `None` for `null`; `getString` on it returns a `NullParameter` error.
- **The agent's default argument validator** accepts `null` for a property that is not required, and supports
  `anyOf`. A `ToolSet` still refuses a schema keyword the validator cannot check, which now can only come from a
  `RawJsonSchema` (`pattern`, `format`, `$ref`): pass a `ToolArgumentValidator` of your own for such a tool.
- **MCP tools** describe a nested object, an `anyOf` and a nullable property as they are. They were all described
  as strings, so a model that was sending a JSON string for an object argument now sends the object.
- **A typed tool** (`Tool.of`, `Tool.fromMethod`) accepts an argument type that holds a sum type; it returned a
  `Left` before. A sum type as the arguments themselves is still refused.
- A `NullableSchema` over a schema with neither `type` nor `anyOf` (only a `RawJsonSchema`) renders as an `anyOf` of
  that schema and `{"type": "null"}`, not as `"type": ["null"]`.

`NumberSchema.isInteger` and `NullableSchema` are unchanged.

## `ToolRegistry` is a trait built with `ToolRegistry.of` (#2816)

`ToolRegistry` was an open class that accepted two tools with the same name (the first won on execution, both were
sent to the provider) and carried `Future`-based execution that no llm4s module used. It is now a trait with three
members, `tools`, `tool(name)` and `execute(request)`.

| Before | After |
|---|---|
| `new ToolRegistry(tools)` | `ToolRegistry.of(tools)`: a `Result[ToolRegistry]`, a `ValidationError` naming any name used twice |
| `registry.getTool(name)` | `registry.tool(name)` |
| `registry.execute(request, config)` (took an unused `ExecutionContext`) | `ToolExecutor.execute(registry, request, config)`, no `ExecutionContext` |
| `registry.executeAsync(request, config)` | `ToolExecutor.executeAsync(registry, request, config)` |
| `registry.executeAll(requests, strategy, config)` | `ToolExecutor.executeAll(registry, requests, strategy, config)` |
| `tool.toOpenAITool()` / `toOpenAITool(strict)` | `tool.toolDefinition(strict = true)` / `toolDefinition(strict)`: no default, say which |
| `registry.getOpenAITools()` | `ToolFunction.toolDefinitions(registry.tools, strict = true)` |
| `ToolRetryPolicy(...)` and its `with*` setters, which threw | the same calls return a `Result[ToolRetryPolicy]` |
| `ToolExecutionStrategy.ParallelWithLimit(n)`, which threw | `ToolExecutionStrategy.parallelWithLimit(n)`: a `Result` |
| `error.getMessage` / `error.getFormattedMessage` on a `ToolCallError` or `ToolParameterError` | `error.message` / `error.formatted` |

```scala
for {
  tool     <- weatherTool
  registry <- ToolRegistry.of(Seq(tool))
  agent    <- Agent.builder("assistant", client).withTools(registry).build()
} yield agent
```

- `ToolExecutor`, `ToolExecutionConfig`, `ToolRetryPolicy` and `ToolExecutionStrategy` are `@Experimental`. The
  agent runtime never used them: it runs tools itself.
- `ToolExecutor` runs every attempt through the registry's own `execute`, so a registry that implements the trait
  (`MCPToolRegistry`) is honoured with a timeout, a retry and in a batch. Before, those paths skipped the override.
- `ToolRetryPolicy` has a `maxDelay` (30 seconds by default, `withMaxDelay`): `delayBeforeAttempt` stops growing
  there instead of overflowing. A policy that waited longer than that between attempts needs `maxDelay` set.
- A class that extended `ToolRegistry` implements the trait: `tools` and `execute(request)`.
- `MCPToolRegistry.execute` returns a local tool's own error (it used to go on to the MCP servers and report
  `UnknownFunction`), and `tools` lists a name once: a local tool shadows an MCP tool, an earlier server a later one.
- No Scalafix rule rewrites these: `ToolRegistry.of` and `ToolRetryPolicy(...)` change the type of the expression.
  `CopyToWithSetters_1_0` no longer rewrites `.copy` on a `ToolRetryPolicy`.

## `Llm4sConfig.providerConfigs()` returns each section's `Result`; `providers()` and `ProvidersConfig` are internal (#2804)

`Llm4sConfig` and `Llm4sSettings` (from `Llm4sConfig.from(config)`) are now one implementation: every `Llm4sConfig`
method calls the `Llm4sSettings` of the process-wide configuration. Both are `@Stable`, and so is
`Llm4sConfig.from`, the entry point at the application edge, although it takes a Typesafe `Config`.

**`providerConfigs()`** returned `Result[(Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig])]`. It
returns `Result[Map[ProviderName, Result[ProviderConfig]]]`: each section's config, or the error loading it, under
its name.

```scala
// before
Llm4sConfig.providerConfigs().map { case (errors, configs) =>
  errors.foreach((name, err) => println(s"${name.asName}: ${err.formatted}"))
  configs.get(ProviderName("openai-main"))
}

// after
Llm4sConfig.providerConfigs().map { sections =>
  sections.foreach {
    case (name, Left(err)) => println(s"${name.asName}: ${err.formatted}")
    case _                 => ()
  }
  sections.get(ProviderName("openai-main")).flatMap(_.toOption)
}
// the two maps, if you still want them:
//   sections.collect { case (name, Left(err)) => name -> err }
//   sections.collect { case (name, Right(config)) => name -> config }
```

**Removed from the public API** (none had a caller outside llm4s-core's own tests):

| Removed | Use |
|---|---|
| `Llm4sConfig.providers()`, `Llm4sSettings.providers()` | `providerConfigs()`, which reports each section separately |
| `ProvidersConfigModel.ProvidersConfig` | internal now; read sections with `provider(name)` or `providerConfigs()` |
| `Llm4sConfig.providerConfigs(map)` | internal now (it also ignored `llm4s.http`); put the section in configuration and call `provider(name)` |

**`modelRegistryConfig()`** used to answer "the bundled registry" whenever it could not read `llm4s.modelRegistry`.
A configuration that cannot be read, or a `resourcePath`, `filePath` or `url` that is not a string, is now a
`Left(ConfigurationError)` naming the key.

**`toString`**: `NamedProviderConfig.toString` shows its base URL without userinfo, query or fragment
(`https://***@host/v1`). Do not parse it.

## `NetworkSecurity` returns the addresses it vetted, and a refusal is not retryable (#2826)

`NetworkSecurity.validateUrl` and `validateHostname` return `Result[Seq[InetAddress]]`, the addresses the host
resolved to and that passed the check, in place of `Result[Unit]`. A caller that only asks "is it allowed" is
unchanged (`.isRight`, a `for` that discards the value); a caller that connects can now connect to one of those
addresses, which is what closes DNS rebinding. (`NetworkSecurity` is also in `org.llm4s.security` now, not
`org.llm4s.core.safety`: see [#2821](#result-helpers-and-conversions-move-and-errormapper-is-removed-2821).)

A refusal - a blocked host name or address, a protocol that is not allowed, a URL that cannot be parsed - is now a
`ValidationError` (field `url` or `hostname`), which is not recoverable, where it was a
`NetworkError(_, None, "ssrf-protection")`, which retry logic treats as retryable. A host name that does not resolve
is still a `NetworkError`; its `endpoint` is now the host name.

| Before | After |
|---|---|
| `case Left(e: NetworkError) if e.endpoint == "ssrf-protection"` | `case Left(e: ValidationError)` |
| `validateUrl(url, additionalBlockedHostnames = hosts)` | `validateUrl(url, hosts, Set("http", "https"))` |
| `validateUrl(url, allowedProtocols = protocols)` | `validateUrl(url, Set.empty, protocols)` |
| `NetworkSecurity.validateIP(ip)` | `NetworkSecurity.isBlockedIP(InetAddress.getByName(ip))`, or `validateHostname(ip)` |
| `NetworkSecurity.DefaultBlockedHostnames` | private; ask `NetworkSecurity.isBlockedHostname(host)` |

`validateUrl`, `validateHostname` and `isBlockedHostname` no longer have default arguments: each has a one-argument
form and a form taking every argument. `validateIP` is removed: it resolved host names, so it was never a check of
an IP address alone.

`llm4s-rag`'s `UrlPolicy` is unchanged: it still reports a refusal as a `NetworkError`.

## `Llm4sHttpClient` has one method to implement, `send` (#2802)

`Llm4sHttpClient` was eight abstract methods. It is now one, `send(request: HttpRequest): Result[StreamingHttpResponse]`;
`get`, `post`, `postBytes`, `postMultipart`, `put`, `delete`, `postRaw` and `postStream` keep their signatures but are
`final` conveniences over it, joined by `sendText` and `sendRaw` for a request they do not cover (a PATCH, a HEAD, a
GET of bytes). Code that only *calls* a client is unaffected.

**An implementation or test double** implements `send` alone:

```scala
// before: eight overrides
final class FakeHttp(reply: HttpResponse) extends Llm4sHttpClient {
  override def post(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) = Right(reply)
  // ... get, postBytes, postMultipart, put, delete, postRaw, postStream
}

// after
final class FakeHttp(reply: HttpResponse) extends Llm4sHttpClient {
  def send(request: HttpRequest): Result[StreamingHttpResponse] = Right(reply.toStreaming)
}
```

`request.method`, `request.url`, `request.headers`, `request.queryParams` and `request.body` (`HttpBody.Empty`,
`Text`, `Bytes` or `Multipart`) say what was asked. A real transport sends `request.fullUrl`, calls
`request.withMultipartEncoded` if it has no multipart support of its own, and wraps the body with
`StreamingHttpResponse.withIdleTimeout(request.idleTimeout)` if it has no read timeout of its own.

**A ScalaMock stub** of `get`, `post` and the rest no longer compiles, because they are final. Stub `send`, or
replace the stub with a small class as above.

**Timeouts.** A `timeout` now bounds two waits, neither a deadline for the whole call: the wait for the response's
status line and headers, and each wait for more of the body. Before, the JDK client stopped timing once the headers
arrived, so a response whose body stalled blocked its reader for good. A call that relied on that, reading a body
that pauses for longer than its `timeout`, now fails (a `TimeoutError` for a buffered call, a
`SocketTimeoutException` from a stream): raise the timeout, or set the two apart with
`HttpRequest(...).withResponseTimeout(...).withIdleTimeout(...)` and `send`.

**`MultipartPart`.** `TextField` and `FilePart` are built with `apply` and changed with `with*` setters; `copy` and
`new` are gone. `FilePart` holds a `content` (`MultipartPart.Content.File(path)` or `Content.Bytes(bytes)`) and a
`contentType` in place of `path`:

| Before | After |
|---|---|
| `part.path` | `part.content` (match on `MultipartPart.Content.File(path)`) |
| `case FilePart(name, path, filename)` | `case FilePart(name, MultipartPart.Content.File(path), filename, contentType)` |
| `part.copy(filename = f)` | `part.withFilename(f)` |
| *(bytes had to be written to a temporary file)* | `MultipartPart.FilePart.ofBytes(name, bytes, filename, contentType)` |

Field and file names are now escaped in the `Content-Disposition` header (`"`, CR and LF percent-encoded).

**`Llm4sHttpClient.create`** takes one `HttpClientOptions`:

| Before | After |
|---|---|
| `Llm4sHttpClient.create()` | unchanged |
| `Llm4sHttpClient.create(network)` | `Llm4sHttpClient.create(HttpClientOptions(network))` |
| `Llm4sHttpClient.create(connectTimeout)` | `Llm4sHttpClient.create(HttpClientOptions(connectTimeout = Some(connectTimeout)))` |
| `Llm4sHttpClient.create(network, connectTimeout)` | `Llm4sHttpClient.create(HttpClientOptions(network, Some(connectTimeout)))` |

## `Result` helpers and conversions move, and `ErrorMapper` is removed (#2821)

`Result`'s type and helper object are now one import, the conversions are extensions you import by name, and an
exception is mapped to an error by its type alone. The `ResultSyntaxMoved` Scalafix rule rewrites the imports in the
first table; `RemovedApis_1_0` reports the uses in the second.

| Before | After |
|---|---|
| `import org.llm4s.Result` (the helper object) | `import org.llm4s.types.Result`, which is the type and the object |
| `import org.llm4s.types.TryOps` (also `OptionOps`, `FutureOps`) | `import org.llm4s.syntax.toResult` |
| `import org.llm4s.error.ThrowableOps._` | `import org.llm4s.syntax.toLLMError` |
| `import org.llm4s.syntax.syntax._` | `import org.llm4s.syntax.*` |
| `import org.llm4s.core.safety.Safety` | `import org.llm4s.error.Safety` |
| `import org.llm4s.core.safety.NetworkSecurity` | `import org.llm4s.security.NetworkSecurity` |

`org.llm4s.syntax` is now an object, not a package: `import org.llm4s.syntax.*` brings in every extension, and
`import org.llm4s.types.*` brings in none, where it used to add `toResult` to every `Try`, `Option` and `Future`
in the file. `ResultOps.getOrElse` and `orElse` are gone without replacement: `Either`'s own members of those
names were always the ones called.

| Removed | Use instead |
|---|---|
| `ErrorMapper`, `DefaultErrorMapper`, and the `(implicit em: ErrorMapper)` parameter of `toResult`, `toLLMError`, `Safety.safely` and `Safety.fromTry` | `Safety.fromThrowable(t)` for the default mapping; for your own, `Try(...).toEither.left.map(yourMapping)` |
| `Result.combine(a, b)` | `for { x <- a; y <- b } yield (x, y)` |
| `Result.fromBoolean(cond, error)`, `fromBooleanWithValue(cond, value, error)` | `Either.cond(cond, (), error)`, `Either.cond(cond, value, error)` |
| `Result.fromFuture(f)`, `AsyncResult.fromFuture(f)`, `Safety.future.fromFuture(f)` | `f.toResult` (`import org.llm4s.syntax.toResult`) |
| `AsyncResult.success(v)`, `failure(e)`, `fromResult(r)` | `Future.successful(Right(v))`, `Future.successful(Left(e))`, `Future.successful(r)`; the `AsyncResult[A]` type alias stays |
| `Safety.fromOption(o, e)`, `Safety.mapError(r)(f)` | `o.toRight(e)`, `r.left.map(f)` |
| `Safety.future.safely(body)` | `Future(body).toResult` |

**Behaviour: no status sniffing by default.** `Try(...).toResult`, `toLLMError`, `Safety.safely`, `Safety.fromTry`
and `Result.safely` used to turn an exception whose message named HTTP status 401 into an `AuthenticationError`
and 429 into a `RateLimitError`, both for the provider `"unknown"`, including for code that made no HTTP call
(`429: unexpected token` from a parser). They now give an `UnknownError` for such an exception; a cancellation, a
socket timeout and a refused connection are classified as before. The clients built on a vendor SDK (OpenAI and
Azure OpenAI, Anthropic, Bedrock, Spring AI) still read the status from their SDK's exceptions, and now name
their provider on the error; the `RateLimitError` also keeps the exception's redacted message.

## `AssistantError` and the assistant's newtypes live in `llm4s-agent` (#2820)

`org.llm4s.error.AssistantError` and `org.llm4s.types.SessionId`, `FilePath` and `DirectoryPath` are used only by the
interactive assistant (`org.llm4s.assistant`), so they moved with it to `llm4s-agent`. Their packages are unchanged:
code that depends on `llm4s-agent` compiles as it is, and code that used them with `llm4s-core` alone adds
`llm4s-agent`. The three newtypes are no longer members of the `org.llm4s.types` package object, so their binary
names lose `package$` (`org.llm4s.types.SessionId`); recompile.

`AssistantError` is now an `LLMError`, a `NonRecoverableError`, so an `Either[AssistantError, A]` is a `Result[A]`
and an assistant failure can be returned where an `LLMError` is expected. Two things follow:

| Before | After |
|---|---|
| `error.formatted` is `SessionError: msg [component: session-manager, sessionId: s1]` | `LLMError`'s format: `SessionError: msg[component=session-manager,sessionId=s1]` |
| a match on `LLMError` could not see an `AssistantError` | `case e: AssistantError` matches; `LLMError.isRecoverable(e)` is `false` |

Each case's extractor is now written out, with the arity it had: patterns such as
`case AssistantError.FileError(message, path, operation, cause)` compile unchanged, and a field added later will be
read by name.

## Error hierarchy cleanup (#2819)

`org.llm4s.error` loses the types, constructors and members nothing in the library used. The `RemovedApis_1_0`
Scalafix rule reports each use of `APIError`, `SystemError` and the three `LLMError` constructors.

| Before | After |
|---|---|
| `APIError(provider, message, Some(status))` | `ServiceError(status, provider, message)`; read `httpStatus`, an `Int`, where you read `statusCode` |
| `APIError(provider, message)` | the error that fits: a `ServiceError` with the status, a `NetworkError`, or an error of your own |
| `SystemError(message, cause)` | an error of your own: `final case class MyError(message: String) extends LLMError with RecoverableError` |
| `LLMError.processingFailed(operation, message, cause)` | `ProcessingError(operation, message, cause)` |
| `LLMError.invalidImageInput(field, value, reason)` | `InvalidInputError(field, value, reason)` |
| `LLMError.apiCallFailed(provider, message, Some(status))` | `ServiceError(status, provider, message)` |
| `LLMError.show(error)` | `error.formatted` |
| `error.correlationId` | read your own MDC key; `formatted` no longer appends `[correlationId=...]` |
| `error.maxRetries` (`RecoverableError`) | your retry policy's own limit: nothing read it |
| `AuthenticationError.unauthorized(p)`, `invalidApiKey(p)` | `AuthenticationError(p, "Unauthorized access", "401")`, `AuthenticationError(p, "Invalid API key", "INVALID_KEY")` |
| `TokenizerError.notFound(id)` | `TokenizerError.notAvailable(id)` |
| `ExecutionError(...).withExitCode(1)` | `ExecutionError(message, operation, exitCode = Some(1))` |
| `OptimisticLockFailure#memoryId`, context key `memoryId` | `recordId` |
| `case SimpleError(message, _)` | `case SimpleError(message)` |
| `ProcessingError(operation, message = "...")` (named argument) | `details = "..."`; also on `audioResample`, `audioConversion`, `audioTrimming` and `audioValidation` |
| `ContextError.conversationTooLarge`, `emptyResult`, `semanticBlockingFailed`, `summarizationFailed`, `compressionFailed`, `contextPipelineFailed`, `toolCompressionFailed`, `externalizationFailed`, `tokenEstimationFailed` | removed: nothing raised them. Define an error of your own |

`llm4s-image`'s vision clients (`OpenAIVisionClient`, `AnthropicVisionClient`, `GeminiVisionClient`) returned an
`APIError`. A non-2xx answer, or a 200 with no usable text, is now a `ServiceError` carrying the status, so a 4xx is
no longer retried by `RetryPolicy`; a call that got no answer is the HTTP client's own `NetworkError` or
`TimeoutError`, where the OpenAI and Anthropic clients wrapped it; and a request that cannot be serialized is a
`ProcessingError`.

Every error now has an explicit `unapply` of fixed arity: `message`, then its fields in constructor order. No
pattern changes but `SimpleError`'s. A field added later is read by name, as `RateLimitError.origin` and
`ContentFilteredError.usage` already are.

## `LLMClient.validate()` checks the provider (#2830)

`validate()` returned `Right(())` for every provider client, whatever the key, the endpoint or the model. It now
sends the provider one cheap request, and a client with no such request says so.

| Before | After |
|---|---|
| `client.validate()` is `Right(())` for any provider client | `Right(())` only when the provider accepted a request: a model lookup (`openai`, `anthropic`, `gemini`, `ollama`, `cohere`, `vertexai`), a model listing (`openai-compatible`, `deepseek`, `mistral`, `xai`, `requesty`, `azure`, `perplexity`), a key lookup (`openrouter`), the IAM token exchange (`watsonx`) or loading the model (`jlama`) (#2855) |
| a wrong key or an unreachable endpoint passes | `Left(AuthenticationError)`, `Left(NetworkError)` and the like, as a completion would fail |
| `bedrock`, `zai`: `Right(())` | `Left(ValidationUnsupportedError)`: nothing was checked |
| your own `LLMClient` that does not override `validate()`: `Right(())` | `Left(ValidationUnsupportedError)`; override `validate()` to check, or to return `Right(())` in a test double whose code under test calls it |
| `client.validate().isRight` in a startup check or readiness probe | match `Left(_: ValidationUnsupportedError)` as "cannot check", or the probe fails for the providers in the third row |

`validate()` is now a network call with a timeout of ten seconds: do not call it on every readiness probe without
caching its result. A provider author's own `OpenAICompatibleDialect` validates with `GET /models` unless it
overrides `validationUrl(baseUrl, model)`, and one whose vendor refuses a key with a status other than 401 or 403
overrides `refusesCredential`; the [provider guide](../guide/writing-a-provider#validate) has the rules, and
`ProviderModuleChecks.assertValidates` / `assertValidationUnsupported` check them.

## A guardrail block's message names only the guardrail (#2793)

`GuardrailBlocked.message` was `Guardrail '<name>' blocked: <reason>` and is now `Guardrail '<name>' blocked`. A
reason can quote the text the guardrail refused, and the message is what the durable `TaskFailed` and `RunFailed`
events, a run queue entry's failure, logs and traces record.

| Before | After |
|---|---|
| `error.message` of a `GuardrailBlocked`, for the reason | `error.reason` (`case blocked: GuardrailBlocked => blocked.reason`) |
| the reason parsed out of a stored `RunFailed` or `TaskFailed` message | `AgentStatus.Blocked(guardrail, Some(reason))` from the run that was blocked; the event names the guardrail only |

The reason is no longer stored at all, and `AgentStatus.Blocked`, `RunOutcome.Blocked` and `TaskRefusal.Blocked`
changed with that: see [a guardrail's reason is never stored](#a-guardrails-reason-is-never-stored-2853). Events
already stored keep the message they were written with.

## Model registry sources, exact lookup and a sealed `RequestTransformer` (#2823)

In `org.llm4s.model` (`llm4s-core`). There is no shim.

**`ModelRegistryConfig`** holds one `source: ModelRegistrySource`, `Resource(path)`, `File(path)` or `Url(url)`;
`withResourcePath`, `withFilePath` and `withUrl` replace it, so `ModelRegistryConfig.default.withFilePath(p)` now
loads `p` (it used to fail, the default resource still being set). The `Option` setters and the `resourcePath`,
`filePath` and `url` fields are gone:

| Before | After |
|---|---|
| `ModelRegistryConfig(resourcePath = None, filePath = Some(p), url = None)` | `ModelRegistryConfig(ModelRegistrySource.File(p))` or `ModelRegistryConfig.default.withFilePath(p)` |
| `config.filePath` | `config.source match { case ModelRegistrySource.File(p) => ... }` |

`llm4s.modelRegistry` takes at most one of `resourcePath`, `filePath` and `url`; more than one is a
`ConfigurationError` from `Llm4sConfig.modelRegistryConfig()`.

**`lookup` is exact.** `lookup(modelId)` matches an exact id, ignoring case and a `provider/` prefix; it no longer
falls back to the one id containing `modelId`. For that, call `search(query): List[ModelMetadata]`, which returns
every id containing the query, and choose. `lookup(provider, modelName)` keeps its unprefixed attempt, now exact.

**`ModelRegistryService`**: `statistics()` is removed (count `listByMode`/`listByProvider` results if you need
numbers), and `listProviders()` returns `List[String]`, never having failed.

**`ModelMetadata`** loses `inputCostPerToken`, `outputCostPerToken` and their setters: they duplicated `pricing`,
which `CostEstimator` always read. Use `metadata.pricing.inputCostPerToken` and set prices with `withPricing`.
`deprecationDate` is an `Option[java.time.LocalDate]`; `fromJson` refuses an entry whose date is not
`YYYY-MM-DD` (the registry skips it with a warning) where `isDeprecated` read it as not deprecated, and
`isDeprecatedOn(date)` is new. `ModelMetadata.apply` defaults everything but `modelId` and `provider`:

```scala
// before
ModelMetadata("m", "p", ModelMode.Chat, None, None, None, None, ModelCapabilities(), ModelPricing(), Some("2026-01-01"))
// after
ModelMetadata("m", "p", deprecationDate = Some(LocalDate.parse("2026-01-01")))
```

`ModelCapabilities.fromJson` and `ModelPricing.fromJson`, which threw on malformed JSON, are internal: use
`ModelMetadata.fromJson(id, json)`, which returns a `Result`, and read `.capabilities` or `.pricing`.

**`RequestTransformer` is sealed**: build one with `RequestTransformer.default` or `RequestTransformer.adjusted`,
and express a rule of your own as `adjusted`'s capability function, whose adjustments are then reported (an
implementation outside llm4s reported none). `transformOptions` takes `dropUnsupported` explicitly. `disallowedParams`
is removed: read `capabilities.disallowedParams` from the registry. `withOverrides` is internal.

## The caching types report failure, and tool loops bypass the cache (#2803)

In `org.llm4s.llmconnect.caching`. There is no shim.

**`EmbeddingCache`** has no type parameter (it only ever held `Seq[Double]`), and a backend reports a failure
instead of throwing:

| Before | After |
|---|---|
| `EmbeddingCache[Seq[Double]]` | `EmbeddingCache` |
| `get(key): Option[Seq[Double]]` | `get(key): Result[Option[Seq[Double]]]` |
| `put(key, v): Unit` | `put(key, v): Result[Unit]` |
| `clear(): Unit = ()` (a default) | `clear(): Result[Unit]` (abstract) |
| `stats(): CacheStats` (abstract) | `stats(): Option[CacheStats] = None` |

`CachedEmbeddingClient` treats a failed `get` as a miss and a failed `put` as not stored, and logs both. Its
`cacheStats` is an `Option[CacheStats]` and `clearCache()` returns the backend's `Result[Unit]`.

**`InMemoryEmbeddingCache`** is built with `create`, which refuses a `maxSize` or `ttl` that is not positive (a
negative `maxSize` used to throw), and takes a `java.time.Clock` in place of `() => Long`:

```scala
// before
val cache = new InMemoryEmbeddingCache[Seq[Double]](maxSize = 1000, ttl = Some(1.hour), clock = () => nowMillis)
// after
val cache: Result[InMemoryEmbeddingCache] = InMemoryEmbeddingCache.create(maxSize = 1000, ttl = Some(1.hour), clock = clock)
```

**`CacheStats`** holds `size`, `hits` and `misses`; `totalRequests` and `hitRatePercent` are computed, so
`CacheStats(size, hits, misses, total, rate)` becomes `CacheStats(size, hits, misses)` and `withTotalRequests` and
`withHitRatePercent` are gone.

**`CacheEntry`** is internal to `CachingLLMClient`.

**`CacheConfig`** is a `final case class` with `withSimilarityThreshold`, `withTtl` and `withMaxSize`, each returning a
`Result` because it checks the new value. `CacheConfig.create` is unchanged.

**`CachedEmbeddingClient` is an `EmbeddingProvider`**, and so is `EmbeddingClient`. The wrapper's `baseClient` is an
`EmbeddingProvider`, so an `EmbeddingClient` still fits; where an `EmbeddingClient` is required, wrap the cache in
one: `new EmbeddingClient(cached)`. A response that called the base client keeps its `usage`, `metadata` and `dim`;
one served wholly from the cache has no `usage`. `CachingLLMClient`'s `embeddingClient` is an `EmbeddingProvider` too.

**`CachingLLMClient` no longer caches a conversation with a tool turn** (an assistant message with tool calls, or a
tool message): such a request goes straight to the base client and is not stored. Only user and system text is
embedded, so a tool loop's later steps used to be served the first step's tool-call completion.

## An open circuit is a `CircuitOpenError` (#2797)

While its circuit is open, or half-open with a probe in flight, `ReliableClient` returned
`ServiceError(503, "circuit-breaker", ...)`. A 503 is retryable, so an outer retry (`LLMClientRetry`, an agent
node's retry policy, a second `ReliableClient`) repeated the fast-fail. It now returns
`org.llm4s.error.CircuitOpenError`, which `RetryPolicy.isRetryable` does not retry:

```scala
client.complete(conversation) match
  case Left(e: CircuitOpenError) => fallBack(after = e.retryAfter) // was: Left(e: ServiceError) if e.provider == "circuit-breaker"
  case other                     => other
```

`provider` is the name the `ReliableClient` was given, `retryAfter` the time left before a probe is let through
(zero when `probing`), and `ErrorKind.fromLLMError` still reports it as `ServiceError`. Code that sent a retryable
error back to a queue, as the Kafka sample does, adds `CircuitOpenError` to that branch, or it dead-letters during
an outage.

Also in `org.llm4s.reliability`:

- **`RateLimitConfig.default` is `RateLimitConfig()`**, which is disabled, like every other `default`. It was the
  one enabled value: write `RateLimitConfig(enabled = true)` or `RateLimitConfig.default.withEnabled(true)`.
- **Removed:** `ReliabilityConfig.withoutDeadline` (`withDeadline(None)`) and the instance method
  `config.disabled` (`config.withEnabled(false)`). `ReliabilityConfig.disabled`, the preset, is unchanged.
- **A configuration a client cannot run with is an error, not a silent oddity.** `ReliabilityConfig.validate`
  (and `CircuitBreakerConfig.validate`, `RateLimitConfig.validate`) return a `ValidationError` for a
  `failureThreshold` or `successThreshold` below 1, a negative `recoveryTimeout`, a `burstCapacity` below 1 or a
  negative `requestsPerMinute` on an enabled rate limit. A `ReliableClient` built with one returns that error
  from every call and from `validate()`.
- **Only a probe's success closes a half-open circuit.** A call that began before the circuit opened and
  succeeds after it went half-open no longer counts towards `successThreshold`.

## `RegexSafetyManager` returns `Result` (#2825)

`RegexSafetyManager.safeCompile` and `safeFind` (`org.llm4s.security`) return `Result`, not `Either[String, _]`: a
refusal is a `ValidationError` for the field `pattern` or `input`, and the text that was the `Left` is in its
`message` after `Invalid pattern: ` or `Invalid input: `.

```scala
RegexSafetyManager.safeCompile(userPattern) match
  case Right(pattern) => RegexSafetyManager.safeFind(pattern, text) // Result[Boolean]
  case Left(error)    => refuse(error.message)                      // was: case Left(reason) => refuse(reason)
```

- **`safeFind` takes no `maxSteps`.** The budget is the documented 10000000 character accesses.
- **Removed, with no caller in llm4s:** `safeMatches` (anchor the pattern with `^...$` and use `safeFind`),
  `compileLiteralCaseInsensitive` (`compileLiteral(text, Pattern.CASE_INSENSITIVE)`), `replaceAllLiteral` and
  `replaceFirstLiteral` (`String.replace`, or `compileLiteral(text).matcher(input).replaceFirst(Matcher.quoteReplacement(r))`),
  and the `RegexComplexityException` class, which never escaped the object.
- **A quantified alternation compiles.** `safeCompile`, and so `RegexValidator("...")` and a `FileBackend`'s `grep`,
  refused every `(x|y)+` and `(x|y)*`, such as `(foo|bar)+` and `^(GET|POST)+$`. They now refuse only one whose
  alternatives overlap, such as `(a|ab)*`, as documented. A pattern your application relied on being refused may
  now run, within the step budget.

## `SecretPatterns` is in `llm4s-agent` (#2824)

`SecretPatterns`, with `SecretType` and `SecretMatch`, moved from `org.llm4s.util` in `llm4s-core` to
`org.llm4s.agent.guardrails.patterns` in `llm4s-agent`, beside `PIIPatterns` and its one consumer,
`SecretLeakGuardrail`. Change the import; there is no forwarder:

```scala
import org.llm4s.agent.guardrails.patterns.SecretPatterns            // was org.llm4s.util.SecretPatterns
import org.llm4s.agent.guardrails.patterns.SecretPatterns.SecretType
```

- **`SecretType` is open.** It was sealed, so `SecretLeakGuardrail(secretTypes)` could only choose among the built-in
  types. `new SecretType(name, pattern, placeholder)` adds a credential format of your own. A `match` over the
  built-in case objects now needs a `case _`.
- **Group 1 of a pattern is the secret.** `SecretType.JwtToken.pattern` now has a capturing group, and its whole match
  can start before the token. Code that applied `secretType.pattern` itself reads group 1 when the pattern has one,
  or calls `secretType.findAll(text)` or the new `secretType.redact(text)`.
- **`SecretType.PrivateKeyPem` matches the whole key**, from `-----BEGIN ... PRIVATE KEY-----` through
  `-----END ... PRIVATE KEY-----`, not the header line: `SecretMatch.value` is the block, and `redactAll` leaves no
  key body behind.
- **`SecretType.OpenAIKey` and `AnthropicKey` match keys holding `-` and `_`** (`sk-proj-...`, `sk-svcacct-...`,
  `sk-admin-...`, `sk-ant-...`), which they cut short or missed.
- **Removed:** `SecretPatterns.redactAllWithPlaceholder` (fold `secretType.findAll` over the text for a placeholder of
  your own), and `SecretMatch.withSecretType`, `withValue`, `withStartIndex` and `withEndIndex`: a match is a result,
  built with `SecretMatch(secretType, value, startIndex, endIndex)`.

## `ReliableClient`'s metrics are generic metrics (#2801)

`MetricsCollector` had three methods with no-op defaults that only `ReliableClient` called: `recordRetryAttempt`,
`recordCircuitBreakerTransition` and `recordError`. A backend that did not override them dropped them silently, and
`PrometheusMetrics` did not. They are removed. `ReliableClient` records three counters declared in
`org.llm4s.reliability.ReliabilityMetrics` through `MetricsCollector.increment`, which every backend that exports
generic metrics already implements.

| Before | After |
|---|---|
| `override def recordRetryAttempt(provider, attemptNumber)` | In `increment`: `case (ReliabilityMetrics.Retries, Seq(provider))` |
| `override def recordCircuitBreakerTransition(provider, newState)` | In `increment`: `case (ReliabilityMetrics.CircuitBreakerTransitions, Seq(provider, state))` |
| `override def recordError(errorKind, provider)` | In `increment`: `case (ReliabilityMetrics.Errors, Seq(provider, errorType))` |
| `collector.recordRetryAttempt("openai", 1)` | `collector.increment(ReliabilityMetrics.Retries, Seq("openai"))` |

```scala
override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
  (metric, labelValues) match {
    case (ReliabilityMetrics.CircuitBreakerTransitions, Seq(provider, state)) => alert(provider, state)
    case _                                                                    => ()
  }
```

- **Prometheus gains three series**: `llm4s_retries_total{provider}`,
  `llm4s_circuit_breaker_transitions_total{provider,state}` and `llm4s_reliability_errors_total{provider,error_type}`.
  Nothing else changes there.
- **Micrometer keeps its meters** (`llm4s.retries`, `llm4s.circuit.breaker.transitions`, `llm4s.reliability.errors`)
  and their tags. Their values now pass through the generic label guard: a provider is capped at
  `MetricsCollector.MaxLabelValues` (256) distinct values per meter, not the collector's 64, and `state` is one of
  `open`, `closed` and `half-open`, which `ReliableClient` passes from `CircuitState`. `MicrometerMetrics.Names.Retries`,
  `CircuitBreakerTransitions` and `ReliabilityErrors` are no longer constant expressions.
- The retry's attempt number is no longer passed: no backend recorded it. `error_type` arrives as its label
  (`rate_limit`, `timeout`, ...; `ReliabilityMetrics.errorTypeLabel`), not as an `ErrorKind`.

## `Tracing` has one abstract method (#2799)

`Tracing` declared five abstract methods, four of them wrappers every backend re-implemented. `TracingComposer.filter`
and `transform` forwarded those four to the wrapped tracer untouched, so a filter or a redacting transform did not
apply to `traceToolCall`, `traceError`, `traceCompletion` or `traceTokenUsage`. Now `traceEvent(event: TraceEvent)` is
the only abstract method and the others are `final`: each builds its event and passes it to `traceEvent`.

| Before | After |
|---|---|
| A `Tracing` overriding `traceToolCall`, `traceError`, `traceCompletion`, `traceTokenUsage` | Delete the four overrides. What they did belongs in `traceEvent`, matching `TraceEvent.ToolExecuted`, `ErrorOccurred`, `CompletionReceived` or `TokenUsageRecorded` |
| `tracing.traceToolCall(name, input, output)` (recorded zero duration and `success = true`) | `tracing.traceToolCall(name, input, output, duration, success)` |
| `tracing.shutdown()` | `tracing.close()`; `Tracing` is `AutoCloseable`. A backend overriding `shutdown()` overrides `close()` |
| `class X extends TracingComposer`, or `TracingComposer` as a type | The `TracingComposer` object: `TracingComposer.combine` / `filter` / `transform` |
| `TRACING_MODE=print` | `TRACING_MODE=console` |

- A filter or transform that let those four calls through unchanged now sees them. A predicate written for
  `traceEvent` calls only may start dropping tool calls, errors, completions and token usage: check it.
- `TraceCollectorTracing` stored the four calls as spans of their own shape (`tool:<name>`, `llm:<model>`,
  `tokens:<operation>`, `error`). They are now the spans their events become, as `traceEvent` always made them: named
  by event type (`tool_executed`, `completion_received`, `token_usage_recorded`, `error_occurred`), a completion's span
  holding `content` where the old one held `content_length`, and a tool call's `duration_ms` and `success`.
- A composed tracer's `close()` closes every tracer it wraps, whatever an earlier one throws.
- Spring Boot: the `llm4sTracing` bean's destroy method is `close`.

## Langfuse traces are private and name the run's user (#2828)

`LangfuseTracing` created every trace with `public: true` and `userId: "llm4s-user"`. Langfuse serves a public trace
to anyone holding its URL, without signing in, and an agent run's trace holds the turn's messages. Traces are now
created `public: false`, and nothing is hard-coded as the user.

- **Shared links stop working for new traces.** If you relied on opening a trace's URL without signing in, set
  `llm4s.tracing.langfuse.public = true` in `application.conf` (`LangfuseConfig.publicTraces`, or
  `publicTraces = true` on `new LangfuseTracing(...)`). Traces already sent stay as they are: make one private in
  Langfuse's UI, or delete it.
- **`userId` is the run's.** The agent's tracing sets the new `TraceEvent.AgentRunEnded.userId` from
  `RunConfig.principal`, and Langfuse receives it as the trace's `userId`; a run without a principal sends none. A
  dashboard filtered on the user `llm4s-user` matches nothing from this version on.
- `AgentRunEnded` gains the field `userId` (it has fourteen fields in all), so a positional pattern on it no longer
  compiles: match `case e: TraceEvent.AgentRunEnded` and read its fields. `LangfuseConfig`'s `toString` and equality include `publicTraces`.

## The Langfuse backend sends OpenTelemetry spans (#2883)

Langfuse removed its legacy batch ingestion API, `POST /api/public/ingestion`, from Langfuse Cloud on 2026-11-16, and a
self-hosted Langfuse v4 in `events_only` mode rejects it. The `langfuse` tracing mode now sends OTLP/HTTP JSON to
Langfuse's OpenTelemetry endpoint, `<url>/api/public/otel/v1/traces`, with `x-langfuse-ingestion-version: 4`; each
trace, span, generation and event it sent is a span whose `langfuse.*` attributes carry the same fields. The mode,
the `llm4s.tracing.langfuse.*` keys and the `LANGFUSE_*` variables are unchanged.

- **`LANGFUSE_URL` is the host.** `LangfuseConfig.DEFAULT_URL` is `https://cloud.langfuse.com`, was
  `https://cloud.langfuse.com/api/public/ingestion`. A URL that still ends in `/api/public/ingestion` keeps working:
  it is read as the host it is on. A self-hosted Langfuse must be v3.22 or later, which has the OTel endpoint.
- **An unplaced run's trace id.** A run traced without a placement (a hand-built `AgentRunEnded`) was the trace whose
  id is the run id; OTLP trace ids are 32 hex digits, so it is now the trace `TraceContext.rootFor(runId).traceId`.
  The run id stays in the trace's `run_id` metadata. Any other event without a placement is a trace of its own, as
  before. A run's trace now has a root span (`LLM4S Agent Run`) with its messages under it.
- **`LangfuseBatchSender`** has `sendTraces(request: ujson.Obj, config)`, an OTLP `ExportTraceServiceRequest`, and
  `sendScores(scores: Seq[ujson.Obj], config)`, posted to `/api/public/scores`; `sendBatch(events, config)` and
  `DefaultLangfuseBatchSender.normalizeIngestionUrl` are removed. A `LangfuseTracing` subclass that overrode
  `sendBatch(events: Seq[ujson.Obj])` overrides `sendBatch(request: ujson.Obj)`, the export.
- **`RAGASLangfuseObserver.logEvaluation(result, traceId)`** scores the trace with that id. To add its
  `Evaluation Details` span to the trace too, the id must be an OpenTelemetry trace id (32 hex digits), as every trace
  llm4s sends now has; any other id gets the scores alone, with a warning.
- Message spans no longer carry `tags` (Langfuse observations have none; the trace keeps `llm4s`, `agent`,
  `conversation`), and a model call's `usage` no longer has `unit: TOKENS`.

## Context trimming fails when nothing fits, and externalizes only into a store (#2817)

In `org.llm4s.context` (`llm4s-core`). No signature changes; behaviour you may rely on changed.

**`TokenWindow.trimToBudget`** pins every leading `SystemMessage` (the system prompt and all `[HISTORY_SUMMARY]`
digests, where it pinned only a digest in first position), and drops older history in whole turns: an assistant
message with tool calls goes with its tool results, so the window never starts at an orphaned `ToolMessage`. It now
returns `Left(ContextError)` when the pinned messages exceed the budget, or when not even the newest turn fits beside
them, where it returned `Right` with an empty or pinned-only conversation. `ContextManager.manageContext` returns that
`Left` from its last step; raise the budget, or trim the system prompt. To leave room for the turns,
`ContextManager` now caps the history digests at half the budget left after headroom and the leading system messages
(and at `summaryTokenTarget`, as before), and drops the digests it made when the newest turn fits only without them. It
no longer digests the leading system messages: a system prompt that exceeds the budget after headroom, or leaves no room
for the newest turn, is now that `Left`, where a digest replaced it. With an LLM client the squeeze runs in cases where
digesting the prompt used to avoid it; a squeeze that fails is a `Left`, as before.

**Externalization needs a store.** With `artifactStore = None` (the default of `DeterministicCompressor.compressToCap`
and of `ContextManager`), tool output over 8 KB used to become an `[EXTERNALIZED: key ...]` pointer into a store
created and discarded on each call. Now nothing is externalized: the output is compressed inline. Pass
`Some(store)` to keep externalizing and be able to `retrieve` the content.

**Binary content** is a `data:...;base64,` URI, a run of 256 or more base64 characters, or text that is mostly
control characters; mentioning the word "base64" no longer counts. It is never cut: over 2 KB it is externalized
whole when there is a store, and otherwise left as it is. The `[BINARY CONTENT - n bytes - externalized: id]`
placeholder, which stored nothing, is gone.

**User messages** are no longer rewritten by `CompressionRule.compressRepetitiveContent` or
`removeRedundantPhrases`, as `DeterministicCompressor`'s Scaladoc always promised.

## `StreamingAccumulator` takes the model and reports usage one way (#2813)

For provider authors using `org.llm4s.llmconnect.streaming` in `llm4s-core`.

| Before | After |
|---|---|
| `StreamingAccumulator.create()` | `StreamingAccumulator(model)`; `toCompletion`'s `Completion.model` is `model`, where it was `"unknown"` |
| `updateTokens(prompt, completion)` | `updateUsage(TokenUsage(prompt, completion, prompt + completion))` |
| `updateTokensWithThinking(prompt, completion, thinking)` | `updateUsage(TokenUsage(prompt, completion, prompt + completion + thinking, thinkingTokens = Some(thinking)))` |
| `addThinkingDelta(delta)` | `addChunk` a `StreamedChunk` whose `thinkingDelta` is `Some(delta)` |
| `SSEEvent.retry: Option[Int]`, `withRetry(Int)` | `Option[FiniteDuration]`, `withRetry(FiniteDuration)`: `retry.map(_.toMillis)` for the number |
| `SSEParser.parseStream(text)` | a `createStreamingParser()`: `addChunk(text)`, drain `nextEvent()`, then `flush()` |
| `StreamingParser#getEvents()` | drain `nextEvent()` while `hasEvents` |
| `new SSEParser.StreamingParser()` | `SSEParser.createStreamingParser()` (the class is `final`) |

`updateUsage` keeps the cache counts the removed methods dropped. The streaming parser now reads CRLF and CR line
endings, so a CRLF stream fed in raw chunks yields each event as its blank line arrives, where it yielded nothing until
`flush()` and then one merged event.

## `ProviderRegistry` lookups accept aliases (#2812)

`find`, `resolve`, `findEmbedding` and `resolveEmbedding` accept a provider's alias as well as its id, so
`registry.resolve(registry.canonicalId(raw))` is now `registry.resolve(ProviderId(raw))`. `canonicalId` stays, for
when you need the canonical id itself.

| Before | After |
|---|---|
| `registry.get(id)` | `registry.resolve(id)` |
| `registry.resolve(registry.canonicalId(raw), path)` | `registry.resolve(ProviderId(raw), path)` |

Behaviour changes:

- **An id beats an alias.** An alias equal to another provider's id used to win, so that provider could not be
  reached from config. Now the id wins. The alias is ignored and reported as a collision of kind `chat alias` or
  `embedding alias`.
- **Alias collisions are reported.** When two providers declare the same alias, the later one still keeps it, but
  `report.collisions` now records it, the discovery log warns about it, and `ProviderModuleChecks.assertSoleSupplier`
  fails a module whose id or alias collides.
- **A version-skewed descriptor no longer aborts discovery.** A descriptor whose `id` or `aliases` throws is left out
  and recorded in `report.failures`. The module's other descriptors are kept.
- **`discover()` scans core's own class loader as well as the context loader.** A module both loaders see counts once.
  `discover(loader)` scans only the loaders you pass.

## The legacy embedding provider selector is removed

Embeddings are selected by `EMBEDDING_MODEL` (`llm4s.embeddings.model`) alone, as `provider/model`. The older form,
`EMBEDDING_PROVIDER` (`llm4s.embeddings.provider`) naming only the provider, is no longer bound or read:

```bash
# before
EMBEDDING_PROVIDER=openai
OPENAI_EMBEDDING_MODEL=text-embedding-3-small

# after
EMBEDDING_MODEL=openai/text-embedding-3-small
```

In HOCON, replace `llm4s.embeddings.provider = "openai"` with
`llm4s.embeddings.model = "openai/text-embedding-3-small"`. A configuration that still sets
`llm4s.embeddings.provider` and no model fails to load with
`llm4s.embeddings.provider ('openai') is no longer read: set EMBEDDING_MODEL (llm4s.embeddings.model) to provider/model, e.g. openai/<model>, and remove llm4s.embeddings.provider`;
one that sets only the `EMBEDDING_PROVIDER` variable gets `Missing embedding config: set EMBEDDING_MODEL ...`.
`ConfigKeys.EMBEDDING_PROVIDER` is removed. The model now always comes from `EMBEDDING_MODEL`, so the per-provider
model settings only the provider-only form read are removed too ([#2835](https://github.com/llm4s/llm4s/issues/2835)):

| Before | After |
|---|---|
| `llm4s.embeddings.<id>.model = "m"` | `llm4s.embeddings.model = "<id>/m"` (`EMBEDDING_MODEL=<id>/m`). A block that still sets `model` fails to load: ``llm4s.embeddings.openai.model ('m') is no longer read: set EMBEDDING_MODEL (llm4s.embeddings.model) to openai/<model>, e.g. openai/m, and remove llm4s.embeddings.openai.model`` |
| `OPENAI_EMBEDDING_MODEL`, `OLLAMA_EMBEDDING_MODEL`, `VOYAGE_EMBEDDING_MODEL`, `JINA_EMBEDDING_MODEL`, `COHERE_EMBEDDING_MODEL` | not bound or read: put the model in `EMBEDDING_MODEL` |
| `OpenAIConfigKeys.OPENAI_EMBEDDING_MODEL` and the `*_EMBEDDING_MODEL` constants of `OllamaConfigKeys`, `VoyageConfigKeys`, `JinaConfigKeys` and `CohereEmbeddingConfigKeys` | removed |
| `EmbeddingConfigSpec(modelEnv = Some("ACME_EMBEDDING_MODEL"))`, `withModelEnv` | removed: drop the argument, and the `model = ${?ACME_EMBEDDING_MODEL}` line from the module's `reference.conf` |
| `EmbeddingProviderSection(model = ...)`, `section.model`, `withModel` | removed: a descriptor's model is `context.modelOverride`, else `configSpec.defaultModel` |
| `EmbeddingConfigSpec.resolveModel(id, section, modelOverride, spec)` | `resolveModel(id, modelOverride, spec)` |

`EmbeddingConfigSpec.defaultModel` stays: `llm4s-rag` uses it for a provider chosen without a model
(`RAGConfig().withEmbeddings("ollama")`).

## Vertex AI no longer reads `endpoint` and `organization`

Vertex AI's `endpoint` and `organization` keys, renamed to `project` and `location` in 0.5.0
([the note](#vertex-ai-endpoint-becomes-project-organization-becomes-location)), are no longer accepted as aliases.
Rename them:

```hocon
vertex-main {
  provider = "vertexai"
  model    = "gemini-2.0-flash"
  project  = "my-gcp-project"    # was endpoint
  location = "europe-west4"      # was organization; optional, default us-central1
}
```

`endpoint` is now an unknown key: logged with a warning that names it and lists the keys Vertex AI accepts, and
ignored. A section that sets `endpoint` and no `project` fails with
`- project: the GCP project ID that owns your Vertex AI resources (set it in application.conf under llm4s.providers.<name>.project)`.
One that sets `organization` and no `location` fails too, rather than run in `us-central1` unnoticed
([#2835](https://github.com/llm4s/llm4s/issues/2835)):
``llm4s.providers.<name> sets `organization`, which Vertex AI no longer reads: the region is `location`. Replace `organization = "europe-west4"` with `location = "europe-west4"`.``
Beside a `location`, a leftover `organization` is logged and ignored.

For provider authors, the alias mechanism goes with them: `ProviderConfigKey` loses `deprecatedAliases` and
`withDeprecatedAliases`, and `ProviderConfigSpec.BuiltinAliasKeys` is removed. Before 1.0 a key is renamed outright,
with a migration note ([compatibility policy](compatibility-policy)).

## The middleware SPI is consistent and testable (#2795)

`AgentMiddleware` and its types (`org.llm4s.agent.graph.middleware`, `@Experimental`) changed shape so that every hook
returns state and failure the same way. Only code that writes middleware is affected. There is no shim.

**Ordering constraints take a `MiddlewareRef`.** `runsBefore` and `runsAfter` name another middleware by id or by
type, and a reference can be optional:

```scala
// before
override def runsBefore: Set[MiddlewareId] = Set(MiddlewareId("guardrails"))
// after: by id, as before
override def runsBefore: Set[MiddlewareRef] = Set(MiddlewareRef(MiddlewareId("guardrails")))
// or by type, whatever id it was given, and only if there is one
override def runsBefore: Set[MiddlewareRef] = Set(MiddlewareRef.ofType[GuardrailMiddleware].ifPresent)
```

A required reference that names nothing in the stack is still refused. `SemanticMemoryMiddleware` no longer takes a
`runsBefore` argument: it runs outside the guardrail middleware of its stack by itself, wherever it was registered.
Delete the argument.

**The system prompt is `ModelRequest.systemPrompt`.** `ModelRequest.messages` is the history alone; the assembled
system prompt is no longer its first message. A `wrapModelCall` that read or kept the `SystemMessage` reads
`request.systemPrompt` and changes it with `request.withSystemPrompt(...)`; `request.sentMessages` is the list the
model receives. A wrapper that split the messages by role to protect the prompt no longer needs to.

**A hook adds to an update; nothing replaces one.**

| Was | Now |
|---|---|
| `BoundaryResult(text).withUpdate(u)` | `BoundaryResult(text, u)`, or `result.addUpdate(u)` |
| `success.withUpdate(success.update.combine(u))` | `success.addUpdate(u)` |
| matching `ToolOutcome.Success` to add a wrapper's update | `outcome.addUpdate(u)` (other outcomes are returned as they are) |
| `ModelResponse(completion).addUpdate(u)` | unchanged; `ModelResponse(completion, u)` also works |

**`ToolCallRequest` is read-only.** `withSpec`, `withCall` and `withResources` are removed: `wrapToolCall`'s `next`
takes no request, so a changed one never reached the tool. Build one in a test with `ToolCallRequest(spec, call)`.

**Reserved ids.** A middleware given to `withMiddleware` whose id is `agent-guardrails`, `delegation`,
`ancestor-permissions` or `response-format` (`MiddlewareId.reserved`) is refused by `build()`, whether or not the
agent installs its own under that id. Give yours another id; a constraint may still name one.

**Blocking a turn.** A boundary hook that ends a turn on purpose returns `block(reason)`: the turn ends
`AgentStatus.Blocked(id, reason)` instead of `agent.run` returning a `Left`. A `Left` still blocks the turn and is
returned as before. What each hook's `Left`, `ToolOutcome.Fatal` and throw do is in
[Writing Middleware](../guide/agents/middleware#errors); none of it changed.

**New.** `PromptSection.estimatedTokens` and `charsFor` are public. `MiddlewareTestKit` in `llm4s-agent-testkit`
builds a `ModelContext` and a `ToolContext` for calling a hook directly, and returns the order a stack resolves to
([Testing a middleware](../guide/agents/middleware#testing-a-middleware)).

## `llm4s-agent` types are tiered and growth-safe (#2778)

Every public top-level type of `llm4s-agent` now carries `@Stable` or `@Experimental`
([Tiers in the Code](api-stability#tiers-in-the-code)), and `sbt stabilityTierCheck` covers the module. Only a small
core is `@Stable` ([#2785](https://github.com/llm4s/llm4s/issues/2785)): `Agent`, `AgentBuilder`, `AgentResult`,
`AgentStatus`, `AgentRun`, `Handoff`, `AgentId`; in `agent.graph`, `ThreadId`, `RunId`, `InterruptId`, `NodeId`,
`TenantId`, `Principal`, `RunConfig`, `RunBudgets`, `RetentionPolicy`, `RunStatus`; `Guardrail`, `InputGuardrail`,
`OutputGuardrail`, and `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`. Everything else - the
graph runtime, checkpointers, events and streaming, middleware, tools and the tool loop, human review, delegation,
the harness, `agent.service`, encryption, metrics, the other guardrails and `org.llm4s.assistant` - is
`@Experimental`, and so are the members of the Stable types that name it (`withMiddleware`, `withRuntime`,
`withStreaming`, `stream`, `resume`, the `AgentStatus.Suspended` case and the others listed in
[Tiers in the Code](api-stability#tiers-in-the-code)). Nothing changes for code that uses them; they may change in a
1.x minor release. Case classes that may gain a field have a private constructor and `copy`, as core's do. There is
no shim.

**Guardrails attach through the builder.** `withInputGuardrails` and `withOutputGuardrails` are the Stable way;
`withMiddleware(new GuardrailMiddleware(...))` still works and is the Experimental form for choosing the position in
the stack ([Guardrails](../guide/agents/guardrails)). The builder's guardrails run innermost, closest to the model.

```scala
// before
Agent.builder("assistant", client)
  .withMiddleware(new GuardrailMiddleware(input = Seq(new LengthCheck(1, 10000)), output = Seq(new JSONValidator())))
// after
Agent.builder("assistant", client)
  .withInputGuardrails(LengthCheck(1, 10000))
  .withOutputGuardrails(JSONValidator())
```

**The four validators are built through their companions.** `LengthCheck`, `ProfanityFilter`, `JSONValidator` and
`RegexValidator` are final with private constructors:

| Was | Now |
|---|---|
| `new LengthCheck(min, max)` | `LengthCheck(min, max)` (or `LengthCheck.maxOnly`, `minOnly`) |
| `new ProfanityFilter()`, `new ProfanityFilter(customBadWords = ws)`, `..., caseSensitive = true)` | `ProfanityFilter()`, `ProfanityFilter.withCustomWords(ws)`, `ProfanityFilter.caseSensitive(ws)` |
| `new JSONValidator()`, `new JSONValidator(Some(schema))` | `JSONValidator()`, `JSONValidator.withSchema(schema)` |
| `new RegexValidator(regex)`, `new RegexValidator(regex, Some(message))` | `RegexValidator(regex)`, `RegexValidator(regex, message)` |

**`ThreadId.child` is internal.** It named `IdempotencyKey`, an Experimental type; a child thread's id is the one
`Agent.children` and the delegation events report.

**Growth-safe types.** `X(...)` still builds each one; `new X(...)` and `.copy(...)` no longer compile.

| Package | Types |
|---|---|
| `org.llm4s.agent` | `ContextWindowConfig`, `PruningStrategy.AdaptiveWindowing`, `Handoff` |
| `agent.events` | `CallUsage`, `ModelCallCompleted`, `ToolExecuted`, `HandedOff`, `GuardrailBlock`, `ModelCallStarted`, `TextDelta`, `ThinkingDelta`, `ToolCallStarted`, `ToolCallResult` |
| `agent.graph` | `Command`, `PendingInterrupt`, `Observer`, `EventRecord`, `EventDraft`, `PendingWrite`, `ThreadOwner`, `ChildThread`, `GraphShape`, `GraphSnapshot` and its `PendingTask`, `Activation` and `ParkedContinuation`, `GraphError.CheckpointWriteFailed`, `GraphError.ThreadBusy` |
| `agent.graph.toolloop` | `AgentInput`, `TurnOutput`, `TurnState`, `Transfer`, `LoopHandoff`, `ToolTask` |
| `agent.delegation` | `ChildRef` |
| `agent.guardrails.builtin` | `InjectionPattern` |

The rewrite is mechanical: each field has a `with` setter of its name, and an `Option` field's setter takes the value
or the `Option`. Read a field by name instead of matching the type positionally, so a later field does not break
the pattern. The Scalafix rule `AgentCopyToWithSetters` ([Scalafix rules](scalafix#migration-rules)) rewrites the
`copy` calls.

```scala
// before
config.copy(maxTokens = Some(8000), minRecentTurns = 2)
records.collect { case EventRecord(_, _, _, _, _, _, _, _, f: RunEvent.RunFailed) => f }
// after
config.withMaxTokens(8000).withMinRecentTurns(2)
records.map(_.event).collect { case f: RunEvent.RunFailed => f }
```

**Enum cases that hold a growth-safe class (#2781).** An enum case with fields is a case class too, so one that
may gain a field now holds a single growth-safe class and is read through it by name. `sbt stabilityTierCheck` fails
for an enum case with fields, in any frozen module, that is neither such a wrapper nor listed as closed.

| Case | Was | Now |
|---|---|---|
| `AgentStatus.Suspended` | `(approvals, questions, middlewareQuestions, breakpoints, delegated)` | `(suspension: Suspension)` |
| `RunEvent.RunStarted` | `(tenantId, principal)` | `(details: RunStartedDetails)` |
| `RunEvent.RunRecovered` | `(fromCheckpoint, tenantId, principal, takenOverFrom)` | `(details: RunRecoveredDetails)` |
| `RunEvent.RunResumed` | `(answered, tenantId, principal)` | `(details: RunResumedDetails)` |
| `RunEvent.RunFailed` | `(message, usage, steps)` | `(details: RunFailedDetails)` |
| `RunEvent.RunCancelled`, `RunTimedOut` | `(usage, steps)` | `(details: RunStoppedDetails)` |
| `RunEvent.StateUpdated` | `(fromCheckpoint, asNode, tenantId, principal)` | `(details: StateUpdatedDetails)` |
| `RunEvent.ThreadForked` | `(fromThread, fromCheckpoint, tenantId, principal)` | `(details: ThreadForkedDetails)` |
| `StreamEvent.Live` | `(threadId, runId, token, taskId, nodeId, name, version, payload)` | `(event: LiveEvent)` |
| `ToolOutcome.Success` | `(content, update)` | `(result: ToolSuccess)`, built with `ToolOutcome.success(content, update)` |

The field names are unchanged, one level down. A stored event log is unchanged too: a `RunEvent` is still written
flat, its fields beside its `$type`, and every log already written reads. The other cases of these enums, and the
cases with fields of the other `@Stable` enums (`RunResult`, `NodeResult`, `Route`, `ThinkingBlock`, ...), are closed
and keep their shape.

```scala
// before
status match
  case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.map((id, _) => result.approve(id))
events.collect { case RunEvent.RunFailed(message, _, _) => message }
events.collect { case StreamEvent.Live(_, _, _, _, _, name, _, payload) => name -> payload }
AgentTool(spec)((args, context) => ToolOutcome.Success(ToolContent.Json(ujson.Str("done"))))
next() match
  case ToolOutcome.Success(content, update) => ToolOutcome.Success(redact(content), update)
// after
status match
  case AgentStatus.Suspended(suspension) => suspension.approvals.map((id, _) => result.approve(id))
events.collect { case RunEvent.RunFailed(details) => details.message }
events.collect { case StreamEvent.Live(live) => live.name -> live.payload }
AgentTool(spec)((args, context) => ToolOutcome.success(ToolContent.Json(ujson.Str("done"))))
next() match
  case ToolOutcome.Success(result) => ToolOutcome.Success(result.withContent(redact(result.content)))
```

There is no Scalafix rewrite for these patterns: `PositionalPatternsGainFields` no longer pads
`AgentStatus.Suspended` or the `RunEvent` cases, so the compiler reports each pattern to rewrite. From Java and
Kotlin, `ToolOutcome.Success.apply(content, update)` becomes `ToolOutcome.success(content, update)`; `JAgentResult`
is unchanged.

**No longer public.** None was used outside `llm4s-agent`:

| Was | Now |
|---|---|
| `guardrails.GuardrailResult` and its cases | removed: nothing produced one. A guardrail returns `Result[A]` |
| `graph.middleware.MiddlewareStack`, `MiddlewareAsked` | internal: an agent orders its own middleware, and `AgentMiddleware.Asking.ask` raises the question |
| `ToolLoop.results`, `ToolLoop.queue`, `toolloop.ToolResult`, `LockSchedule` | internal state of a turn; read `Messages.key` for a tool's result |
| `guardrails.rag.GroundingResult`, `ContextRelevanceResult`, `SourceAttributionResult`, `TopicBoundaryResult`, `guardrails.builtin.InjectionMatch` | internal: each guardrail's parsed verdict |
| `org.llm4s.assistant.Command` | internal to `AssistantAgent` |

**Tiers that moved.** Core's `ToolHints` is now `@Stable` (the tool contract). `Agent.loop` is now an `@Experimental`
`def` rather than a constructor `val`, so `agent.loop.graph` reads as before. The RAG guardrails
(`org.llm4s.agent.guardrails.rag`) are `@Experimental`: an agent gives them the turn's `RAGContext` only through
`RAGGuardrail.withTurnContext`, whose shape may still change.

## Context pruning enforces both limits and `minRecentTurns` (#2728, #2729)

`ContextWindowConfig` (`llm4s-agent`, used by `ContextWindowMiddleware`) now does what its fields say.

- **`minRecentTurns` is honoured, and defaults to 1.** Nothing read it before: only the current turn was kept past a
  limit. Now the last `minRecentTurns` turns (each from its user message, tool calls and results included) are never
  pruned, under every strategy. The default changed from 3 to 1, so a config that does not set it behaves as before. A
  config that set it higher now sends those turns even when they exceed `maxTokens` or `maxMessages`: remove the
  setting to keep the old behaviour.
- **`PruningStrategy.AdaptiveWindowing.preserveMinTurns` is removed.** Set `ContextWindowConfig.minRecentTurns`
  instead. It counted messages (two a turn); `minRecentTurns` counts whole turns. Its default was 3, so set
  `minRecentTurns = 3` to keep an adaptive config's floor.
- **`maxTokens` and `maxMessages` together are both limits.** Before, `maxTokens` alone decided whether to prune and
  `maxMessages` alone how much to keep, so a history over `maxMessages` but under `maxTokens` was sent whole, and a
  pruned one could still exceed `maxTokens`. Now the history is pruned when either is exceeded and what is kept
  satisfies both. With `AdaptiveWindowing`, its window is a token limit beside them: the smaller of it and
  `maxTokens` applies, and `maxMessages` applies too (it was ignored).
- **`MiddleOut` fits `maxTokens`.** With no `maxMessages` it kept half the messages whatever their size; it now keeps
  as much of the start and the end as the limits allow, and no longer counts system messages outside `maxMessages`.
- **No earlier message is kept past a limit.** `OldestFirst` kept at least one message of the earlier history even
  when the protected turns had used the whole budget.
- **`RecentTurnsOnly(n)` always prunes to `n` turns.** It acted only once `maxTokens` or `maxMessages` was exceeded,
  so with neither set it did nothing, and with one set the history was sent whole until it crossed the limit. Now a
  history of more than `n` turns is pruned to the last `n` regardless. Those turns also obey the limits: when they
  exceed `maxTokens` or `maxMessages`, the oldest of them are dropped. If you relied on a generous limit to keep more
  than `n` turns, raise `n`.

## RAG loaders take a `UrlPolicy`, and `DirectoryLoader` stays in its root (#2724)

- **`DirectoryLoader` skips symbolic links that leave its root.** A link whose real path is outside the directory's
  real path is no longer followed, and only regular files are read (a FIFO or device is skipped). To follow such links
  as before, `DirectoryLoader(path).withConfineToRoot(false)`.
- **`UrlLoader`, `CrawlerConfig` and `DirectoryLoader` gain a field** (`urlPolicy`, `urlPolicy`, `confineToRoot`), so a
  positional pattern on one needs it. Construct them by name or with the `with*` setters.
- **The crawler's robots.txt fetch is checked.** It follows the same `UrlPolicy` as the pages, so a robots.txt on a
  refused host is never requested (the page fetch then fails as before), and its redirects are checked hop by hop.
- **Loopback and intranet hosts can be opted in.** Where you fetched internal pages yourself and passed them in with
  `TextLoader`, `withUrlPolicy(UrlPolicy.default.withAllowPrivateNetworks(true))` now lets the loaders do it.
- **No proxy.** The loaders connect to the address the policy approved over their own HTTP/1.1 socket, not through
  `HttpURLConnection`, so `http.proxyHost` and `https.proxyHost` no longer apply to them.

## The PostgreSQL checkpoint schema moves to version 6 (#2453)

`PostgresCheckpointer` gains a long-term store, `longTermStore(limits)`
([guide](../guide/agents/durable-checkpointers#long-term-memory-on-the-same-database)). Nothing in source changes
unless you use it.

- **The schema moves on, with no way back.** Opening a store brings its PostgreSQL schema from version 5 to 6: three
  tables are added (`checkpoint_store_items`, `checkpoint_store_applied`, `checkpoint_store_fences`) and none is
  changed. An older build refuses the new schema, so upgrade every process sharing a database together. The role
  that first opens it needs `CREATE` on the schema, once.
- **`deleteThread` and the sweep touch the new tables**, in their own transactions: a deleted thread's applied keys,
  and a reclaimed thread's fence. They never remove an item.
- **A custom `LongTermStore`** has a contract to pass: mix `LongTermStoreContract` (`llm4s-agent-testkit`) into a
  spec. `InMemoryLongTermStore`'s behaviour is unchanged.

## `SQLiteVectorStore` escapes metadata and filters exactly (#2759)

Metadata was written as JSON without escaping, so a key or value with a quote was cut short when read, and filters
matched with `LIKE` on that text. No API changes; stored files and filter results do:

- **Existing files need nothing.** Opening one changes no row. A row written from now on starts with a space and is
  JSON; any other row is read, and filtered, as the unescaped text 0.6.0 and earlier wrote, and is replaced when you
  upsert it.
- **Some old text is ambiguous**, where a key or value held quotes in the arrangement of a separator: `","` or `":"`, a
  key or value ending in `",`, a key ending in `":`. JSON stored as a value can be one. Such a row may read as other entries
  than were written: upsert it again. So may a row another program wrote as JSON with backslash escapes.
- **0.6.0 can open the file**, but does not unescape the new rows: a value is cut at its first quote and a backslash is
  doubled.
- **Filters compare exactly.** `Equals`, `Contains` and `In` are case-sensitive, `%` and `_` are ordinary characters,
  and `Contains` and `HasKey` look only at the named key. `deleteByFilter` deletes fewer rows where it used to delete
  look-alikes (`user_1` also deleted `userX1`). `In` works with more than one value, and matches nothing with none.

## `RAG.ingestChunks` takes `DocumentChunk`s (#2732)

`RAG.ingestChunks` dropped each chunk's metadata and returned only a count, so an application that keeps its own
view of what was indexed had to re-chunk the text and rebuild the ids. Now:

- **`ingestChunks(documentId, chunks: Seq[DocumentChunk], metadata)`** replaces the `Seq[String]` form (the two
  cannot both exist: they erase to the same JVM signature). Each chunk's `ChunkMetadata` is stored in its record's
  metadata under `chunk.`-prefixed keys (`ChunkMetadata.toRecordMetadata`), so search results carry it and
  `ChunkMetadata.fromRecordMetadata(result.metadata)` reads it back. Plain texts become chunks with
  `DocumentChunk.fromTexts(texts)`. The indices must run `0, 1, 2, ...` in order, or the call is a
  `ValidationError` and writes nothing.
- **It returns `Result[Seq[IndexedChunk]]`**, each chunk's stored `id`, `index` and `content`, not `Result[Int]`:
  `.map(_.size)` for the count.
- **`SearchIndex.ingest` returns the stored ids**, `Result[Seq[String]]` one per chunk in order, not a count. A
  `SearchIndex` of your own returns the ids its `query` reports for those chunks.
- Every ingest path (`ingestText`, files, loaders, `ingestWithPermissions`) now stores the chunker's `ChunkMetadata`
  in the record metadata too: a `chunk.`-prefixed key is new in a search result's `metadata`.

New, with nothing to migrate: `RAG.chunk(text)` (the pipeline's own chunker and config),
`ingestChunksWithPermissions(collectionPath, documentId, chunks, metadata, readableBy)` and
`ingestBytesWithPermissions(collectionPath, content, filename, documentId, metadata, readableBy)`.

## RAG tracing covers every model call and each query's stages (#2734)

- **`RAGAnswerResult` gains `metrics: Option[RAGQueryMetrics]`** (stage durations, candidates retrieved, chunks
  used, tokens, cost), and **`TraceEvent.RAGOperationCompleted` gains `stageDurations`**, so a positional pattern on
  either needs one more field. Match by name or read the fields.
- **A `RAG` built `withTracing` traces its own model calls.** If you wrapped the client you passed to `withLLM` in
  `TracingLLMClient` to see the answer calls, remove the wrapper or each call is traced twice.
- **A `QueryTransformer` of your own** can override `withTracing(tracing)` to trace its LLM calls; the default returns
  it unchanged.

## `DocumentRegistry` is source-scoped (#2714)

`RAG.sync` records what it indexed in a `DocumentRegistry`, which was always an in-memory one, and its deletion pass
removed every registered document the current loader did not list. Now:

- **The registry is pluggable.** `RAGConfig.withDocumentRegistry(registry)` names it; the default is still
  in memory. `PgDocumentRegistry` (`PgDocumentRegistry(jdbcUrl, user, password)` or
  `PgDocumentRegistry.fromDataSource(dataSource)`, sharing a pool with a `PgVectorStore`) and
  `SQLiteDocumentRegistry(path)` keep it across restarts, so the next sync re-embeds only what changed and deletes
  what the source dropped meanwhile. Pair it with stores that persist too, keyword index included (`withPgHybrid`,
  `withSQLite`): a sync skips documents the registry knows, so an empty store is never refilled for them. The
  pipeline does not close the registry; its owner does.
- **Sync is scoped by source.** `sync(loader, sourceId)` and `syncAsync(loader, sourceId)` delete only documents that
  source registered, so several loaders can share one `RAG`. `sync(loader)` syncs the default source
  (`DocumentRegistry.DefaultSourceId`, `"default"`), which also holds documents a plain ingest registered, and no
  longer deletes documents a named source owns. With one loader and no `sourceId`, nothing changes. `refresh` still
  clears every source.
- **A `DocumentRegistry` of your own** implements `register(docId, version, sourceId)` in place of
  `register(docId, version)` (now a concrete method that registers under the default source), and
  `documentIds(sourceId)`.

## The forwarding `apply` overloads are removed (#1281)

A growth-safe type that gained a field kept its previous `apply` as an overload without defaults, so code compiled
against the older signature still linked. Nothing promises binary compatibility before the MiMa baseline (0.7.0), and
every overload present then would be frozen for good, so each type now has one `apply`, with defaults. The overloads
removed:

| Type | Removed `apply` |
| --- | --- |
| `NamedProviderConfig` | the 8-argument (no `apiKeyFile`) and 6-argument (no `timeouts`) |
| `EmbeddingProviderConfig` | the 5-argument (no `apiKeyProvider`) and 4-argument (no `network`) |
| `LlmClientOptions` | `(metrics, exchangeLogging)` |
| `Completion` | the 10-argument (no `finishReason`) and 9-argument (no `adjustments`) |
| `ProviderConfigSpec` | the 8-argument (no `keylessWhen`) |
| `ProviderFeatures` | `(streaming, toolCalling)` |
| `TransformationResult` | `(options, messages, requiresFakeStreaming)` |
| `TraceEvent.CompletionReceived` | `(id, model, toolCalls, content, timestamp)` |
| `TraceEvent.ErrorOccurred` | the 9-argument (no `usage`) and `(error, context, timestamp, span)` |
| `TraceEvent.AgentRunEnded` | the 10-argument (no `provider`) |
| `TraceEvent.EmbeddingUsageRecorded` | `(usage, model, operation, inputCount, timestamp)` |
| `OpenAICompatibleClient.Settings` | the 8-argument (no `network`) |
| `AzureConfig` | the 7-argument (no `tokenProvider`) |
| `Checkpoint` | the 13-argument (no `trace`) and 10-argument (no `origin`, `outcome`, `deleting`) |
| `Commit` | the 7-argument (no `metadata`) and 6-argument (no `fence`) |
| `RunOrigin` | the 5-argument (no `trace`) |
| `RunBudgets` | `(maxSupersteps, timeout, maxConcurrency)` |
| `RunConfig` | the 9-, 8- and 7-argument (no `traceContext`, `childRetention`, `retention`) |
| `HarnessProfile` | the 12-argument (no `subagents`) |
| `ToolCallRequest` | `(spec, call)`: `resources` defaults to none |
| `TakeRequest` | `(holder, agents, ttl)`: `skip` defaults to none |

Scala source that called one of these by position still compiles: the remaining `apply` takes the same arguments
first and defaults the rest. Code compiled against 0.6.0 must be recompiled. Java and Kotlin do not see Scala's
defaults, so a call that passed the shorter list passes every argument now: the default of each one it left out is
the companion's `apply$default$N()`, so a 9-argument `Completion.apply(...)` gains
``Completion.`apply$default$10`(), Completion.`apply$default$11`(), Completion.`apply$default$12`()`` in Kotlin
(`Completion.apply$default$10()` through `apply$default$12()` in Java). Building the value with the shorter `apply`
a type keeps for Java (`OpenAIConfig.apply(apiKey, model)` and the other provider configs) and its `with*` setters
avoids the long list.

`OllamaConfig.apply(model, baseUrl, contextWindow, reserveCompletion)` gained defaults and a last `timeouts`
parameter, like the other provider configs, so a Java or Kotlin call passing those four arguments passes
`OllamaConfig.apply$default$5()` too; `OllamaConfig.apply(model, baseUrl)` and the setters are unchanged.

## `CompressionRule`'s `apply` field is `transform` (#1281)

`CompressionRule` (`org.llm4s.context`) held its function in a field named `apply`, which read like `apply` call
syntax and made its setter `withApply`. The field is `transform` now, and the setter `withTransform`
([#1281](https://github.com/llm4s/llm4s/issues/1281)):

```scala
// 0.6.0
val keep = CompressionRule("keep_all", apply = messages => messages)
val out  = keep.apply(messages)
val drop = keep.withApply(_ => Seq.empty)

// now
val keep = CompressionRule("keep_all", transform = messages => messages)
val out  = keep.transform(messages)
val drop = keep.withTransform(_ => Seq.empty)
```

A rule built positionally, `CompressionRule(name, f)`, is unchanged. The Scalafix rule `CompressionRuleTransform`
(in `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`) makes these edits.

## A RAG with a `SearchIndex` ingests only with permissions (#2716)

Mixing the plain RAG API with permission-aware RAG (`RAGConfig.withSearchIndex`) went wrong without an error. Each
case now either works or fails:

- **Plain ingest is refused.** `ingest` (a path or a loader), `ingestPath`, `ingestText`, `ingestChunks`,
  `ingestBytes`, `ingestBytesMultiple`, `sync`, `refresh` and their `Async` forms return a `ConfigurationError` on a
  `RAG` with a `SearchIndex`, before reading the loader or clearing anything, and so does `build` when the config
  also names document loaders (`withDocuments`). They used to write chunks with no collection (`collection_id NULL`
  in a `PgSearchIndex`'s table), which `queryWithPermissions` never returns. Ingest with
  `ingestWithPermissions(collectionPath, documentId, content, ...)`, `ingestChunksWithPermissions` or
  `ingestBytesWithPermissions`. Plain ingest is not routed to a default
  collection: `PgSchemaManager`'s default collection is public, so a document ingested without naming its readers
  would become readable by every principal. Rows a plain ingest wrote before can be moved to that collection,
  if that is what you want, with `PgSchemaManager.migrateExistingVectors`, or deleted with `deleteDocument`.
- **Plain search and `clear()` are refused.** `query`, `queryWithAnswer`, `queryWithAnswerStreaming` and GraphRAG's
  Hybrid mode (`queryWithGraphRAG` when it routes there) return a `ConfigurationError` on a `RAG` with a
  `SearchIndex`, before embedding the question or calling the model. With a `PgSearchIndex` they searched the index's
  own table with no collection or `readable_by` filter, so any caller could retrieve every principal's documents. Use
  `queryWithPermissions`, `queryWithPermissionsAndAnswer` or `queryWithPermissionsAndAnswerStreaming` with the
  caller's `UserAuthorization`; `UserAuthorization.Admin` reads everything, deliberately. `clear()` is refused too:
  it emptied that table, every collection's documents with it. It has no opt-in, because the permission API already
  covers each deliberate use: `searchIndex.clearCollection(path)` for one collection, `deleteDocument(docId)` for
  one document, and `searchIndex.dropSchema()` then `initializeSchema()` to reset the whole index.
- **`deleteDocument(docId)` deletes more.** With a `SearchIndex` it also deletes the document from every collection
  (`SearchIndex.deleteDocumentFromAllCollections`, new), whose chunks are named `coll-<collectionId>-<docId>-chunk-<n>`.
  It used to return `Right(())` and leave those chunks retrievable. A failed delete is now the `Left`. A
  `SearchIndex` of your own inherits a default that calls `deleteDocument` for each leaf collection; override it if
  your store can do it in one call. To delete from one collection only, `deleteFromCollection` is unchanged.
- **`queryWithPermissions` searches as `query` does.** It applies the query transformers, the fusion strategy and the
  reranker; it used to run vector search alone. A hybrid (`RRF`, the default, or `WeightedScore`) or keyword-only
  query calls the new `SearchIndex.keywordQuery`. `PgSearchIndex` implements it with full-text search over the
  vectors table's `content` (`english` configuration), and `initializeSchema()` adds the GIN index it uses: call it
  once on an existing table. A `SearchIndex` of your own that does not implement `keywordQuery` fails such a query
  with a `ConfigurationError`: implement it, or configure `.vectorOnly` for the previous, vector-only results.
- **`SearchIndex.queryHybrid` is removed.** It ignored its text and ran `query`. Call `query` for vector search, or
  `keywordQuery` for keyword search.

## A cut-off answer ends `Truncated`, not `Completed` (#2613)

An agent turn whose final answer the model's output limit cut off - the reply ended `FinishReason.Length`, at
`CompletionOptions.maxTokens` or the model's own cap - ends `AgentStatus.Truncated(partialAnswer)`. It used to end
`Completed(answer)` with the cut text as if it were the whole answer ([RFC 0009](../rfcs/0009-model-call-contract),
open question 6).

- **`AgentResult.answer` is `None`** for it, as for every status but `Completed`, and `decodeOutput` is a
  `ValidationError`. The partial text is `Truncated.partialAnswer`, and stays in the thread's messages, so the next
  turn (`continueConversation`, "go on") sees it; the turn is a completed one, like `StepLimitReached`.
- **A `match` on `AgentStatus` without a catch-all** needs a `Truncated` case. Scala 3 warns that the match is not
  exhaustive; the `AgentStatusGainsTruncated` Scalafix rule (in `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`) reports each one.
- **Delegation**: a child that ends `Truncated` is refused `TaskRefusal.Truncated`, as a child at its step limit is,
  and `SubagentEvents.TaskEnded`'s status is `truncated`.
- **Tracing**: `TraceEvent.AgentRunEnded.status` is `truncated`.
- **Stored threads**: the checkpoint's `RunOutcome` gains `Truncated`, so a settled turn rebuilds as `Truncated`.
  Threads stored before keep the status they had; a build before this one cannot read a `Truncated` outcome.
- **Java and Kotlin**: `AgentStatusKind` gains `TRUNCATED`, and `JAgentStatus.partialAnswer()` carries the text;
  `answer()` is empty for it. A `switch` or `when` over the kind without a `default` or `else` needs the case.

## Finish reasons are typed (#2613)

`Completion` gains `finishReason: FinishReason`, and `StreamedChunk.finishReason` changes from `Option[String]` to
`Option[FinishReason]` ([RFC 0009](../rfcs/0009-model-call-contract) PR 1). `FinishReason` is `Stop`, `ToolCalls`,
`Length` or `Other(raw)`; `name` gives the chat-completions string (`stop`, `tool_calls`, `length`) or the raw value.

```scala
// Before: a provider string, streams only
client.streamComplete(conversation, options, chunk => chunk.finishReason.foreach(r => if (r == "length") warn()))

// After: one type, on the Completion too
client.complete(conversation, options).map { c =>
  if (c.finishReason == FinishReason.Length) warn() // the content is cut short
}
```

- **Check every comparison against a string literal**: `chunk.finishReason.contains("stop")` still compiles, because
  `Option.contains` widens its argument, and is now always `false`. Write `contains(FinishReason.Stop)`. The
  `FinishReasonTyped` Scalafix rule ([Scalafix](scalafix#migration-rules)) rewrites these comparisons, a reason set
  from a literal, and any other read to `.map(_.name)`, which keeps its `Option[String]` type; the cases assume a
  client that maps its reason, and a Spring AI model gives `FinishReason.Other(raw)` for any value. It reports, for
  you to fix by hand, a comparison with 0.6.0's raw Bedrock or Gemini value (`end_turn`, `tool_use`, `MAX_TOKENS`,
  ...), which those clients now map to a case while a Spring AI model still gives `FinishReason.Other(raw)`; with a
  content filter's value (`content_filter`, `SAFETY`, ...), a `ContentFilteredError` now; and with Gemini's `STOP`,
  which is `Stop` or `ToolCalls`. Any `"stop"`, `"tool_calls"` or `"length"` in the same comparison is reported with
  it. A chunk set from a raw Bedrock or Gemini value, as in a test, gets the case that value maps to now.
- `StreamedChunk(..., finishReason = Some("stop"))` and `withFinishReason("stop")` take a `FinishReason` instead.
- A positional pattern on `Completion` gains a field, two with `servedTier` (#2746):
  `case Completion(id, _, content, _, _, _, _, _, _, _, _, _)`. The `PositionalPatternsGainFields` Scalafix rule adds
  them to 0.6.0's ten-field pattern, and every field since 0.5.1 to its nine-field one.
- `Completion(...)` with named arguments keeps compiling and gets `FinishReason.Stop`. A client or fake that builds
  one sets the reason it read; one it did not read is `FinishReason.Unknown` (#2807), which tracing leaves out.
- Every chat client maps it (RFC 0009 PR 2 added Anthropic's `stop_reason`, Gemini's and Vertex AI's `finishReason`
  and Bedrock's `stopReason`): a natural end or stop sequence is `Stop`, a tool call `ToolCalls` (Gemini's `STOP` after
  a function call too), a token or context-window limit `Length` (`max_tokens`, `MAX_TOKENS`,
  `model_context_window_exceeded`). Any other value is `Other(raw)`, such as Anthropic's `pause_turn` or Gemini's
  `CONTINUATION`. A Spring AI model's reason is always `Other(raw)`, since Spring AI has no common vocabulary.
- Behaviour that changes with the reason: tracing reports it for `complete`, where it reported none, and an Anthropic
  stream traces the `stop_reason` of `message_delta`, where it traced a made-up `stop`; OpenAI's fake
  streaming (o1) ends with the reply's own reason, not `stop`; Ollama reads `done_reason`, where it guessed `stop` or
  `tool_calls` from `done`; and Perplexity reports an `incomplete` reply that has a call as `Length`, not `ToolCalls`.
- An agent's `AgentEvents.ModelCallCompleted` gains `finishReason: Option[FinishReason]` (default `None`), after
  `adjustments`, filled from each call's `Completion` (PR 6). Construction is unchanged; a positional pattern needs a
  ninth binder and a tenth for `servedTier` (#2746), `case ModelCallCompleted(a, m, n, t, u, c, p, adj, _, _)`, which the `PositionalPatternsGainFields`
  Scalafix rule adds, or match on the type and read the fields. An event stored before reads with `None`; a reason this version cannot read, a case a later one adds, reads as `FinishReason.Other` with its stored value.

## Every provider's content-filter stop is an error (#2677)

The rule the Gemini clients follow since [#2563](#a-gemini-reply-a-content-filter-stopped-is-an-error-2563) now holds
for every chat client ([RFC 0009](../rfcs/0009-model-call-contract) PR 2). A reply the provider's content filter
stopped is a `Left(ContentFilteredError)`, from `complete` and from `streamComplete` alike, even after some text. That
covers the chat-completions `finish_reason` `content_filter` (OpenAI, Azure OpenAI, Requesty and the
OpenAI-compatible clients), Anthropic's `stop_reason` `refusal`, and Bedrock's `stopReason` `guardrail_intervened`
or `content_filtered`. Azure OpenAI's 400 for a prompt its filter blocked (`code: content_filter`) is one too, with
`promptBlocked = true`, where it was a `ValidationError`. Each of these used to come back as a finished `Completion`,
possibly empty or cut short, with the provider's reason as `Other`.

- A stream's text already passed to `onChunk` stays delivered; no chunk carries the filter's reason, and the error's
  message says how many characters the filter cut after.
- `ContentFilteredError.usage` carries the tokens the provider reported for the filtered call, which it bills, on
  both paths and for Gemini and Vertex AI too (#2676). A client's metrics record them, and `TracingLLMClient` puts
  them on the call's `TraceEvent.ErrorOccurred`, whose new last field is `usage: Option[TokenUsage]`; the
  OpenTelemetry backend reports it as the `chat <model>` span's `gen_ai.usage.*`, Langfuse in the event's metadata.
  `ContentFilteredError`'s own extractor still binds four fields.
- A positional pattern on `TraceEvent.ErrorOccurred` gains a field:
  `case TraceEvent.ErrorOccurred(error, context, _, _, _, _, _, _, _, _)`. The `PositionalPatternsGainFields` Scalafix
  rule adds it to 0.6.0's nine-field pattern.
- Match `ContentFilteredError` (`provider`, `reason`, `promptBlocked`) where you read a `content_filter`, `refusal`
  or guardrail reason off a `Completion`. `ReliableClient`'s circuit breaker counts it as neither a success nor a
  failure, and it is not retried.

## A stream that did not finish is an error (#2613)

`streamComplete` returns a `Completion` only when the provider said the reply ended
([RFC 0009](../rfcs/0009-model-call-contract) PR 4), as the OpenAI-compatible, Bedrock, watsonx and Perplexity
clients already did. A stream that stops without its provider's end marker - Anthropic's `message_stop`, a
`finish_reason` on OpenAI and Azure OpenAI, a candidate `finishReason` on Gemini and Vertex AI, `done: true` on
Ollama - is a `Left(NetworkError)`, which `ReliableClient` and `streamCompleteWithRetry` treat as retryable. These
clients used to return what had arrived as a finished `Completion`.

An error the provider reports after the 200 status ends the stream with that error, and nothing after it is read:

- Gemini and Vertex AI: a chunk with an `error` object, mapped by its `code` (`503` a `ServiceError`, `429` a
  `RateLimitError`; any other a `ServiceError` with status 502). These chunks used to be skipped.
- Ollama: a line `{"error": "..."}`, a `ServiceError` with status 502. It used to be skipped.
- Anthropic: an `error` event, mapped by its error type (`overloaded_error` a `ServiceError` with status 529,
  `rate_limit_error` a `RateLimitError`), where it was a `ServiceError` with status 200.
- OpenAI and Azure OpenAI: an event with an `error` object, mapped as the OpenAI-compatible client maps one (a
  `ServiceError` with status 502 unless the error states an HTTP failure status), where it was a `ServiceError`
  with status 200.

Text already passed to `onChunk` stays delivered; the `Left` says the reply is incomplete. Code that kept a cut
reply as an answer should treat the `Left` as a failed call, or retry it.

A Gemini or Vertex AI `data:` line that is not JSON fails the stream too, a `ServiceError` with status 502, where it
used to be skipped (RFC 0009 PR 5). Blank lines and SSE `:` comment lines, which is how a keep-alive is sent, are
still ignored.

`OpenAIClient` (`provider = "openai"` and `"azure"`) needs a `finish_reason` on the last choice: the OpenAI SDK
reads the stream and never shows the client `[DONE]`, so a server that ends its stream with `[DONE]` alone, and no
`finish_reason`, now fails with the `NetworkError`. Point such a server at the `openai-compatible` provider, which
takes `[DONE]` as the end marker.

**Provider authors:** `ProviderModuleChecks.assertStreams` is removed. `assertModelCallContract` checks how every
reply ends on both paths, and requires `ContractCase.CleanText` to apply, whose stream proves the client streams.
Replace an `assertStreams(client)` test with the full check, every `ContractCase` declared
([writing a provider](../guide/writing-a-provider#the-model-call-contract)).

## A broken or cut tool call is an error (#2613)

A tool call the client cannot run as the model meant it is a `Left`, the malformed-tool-call `ProcessingError`, on
`complete` and on a stream alike ([RFC 0009](../rfcs/0009-model-call-contract) PR 3). It used to become `{}`, be
dropped, or be passed on as a raw string, and an agent then ran the tool on arguments the model never wrote:

- **Arguments that are not a JSON object** - cut or invalid JSON, JSON of another type, or JSON nested more than 512
  levels deep, which is still never parsed (#1562). The OpenAI-compatible clients' `complete` made them `{}`;
  `OpenAIClient` dropped the call, so a reply whose only call was broken came back as an answer with no call; a
  stream's call kept them as a raw `ujson.Str`. Gemini and Vertex AI's `MALFORMED_FUNCTION_CALL` finish, a call
  Gemini dropped, is the same error, whether or not text came before it; it used to return the text.
- **A tool call in a reply cut by the output limit** (`FinishReason.Length`): every client, even when the call's
  arguments parse. A later call may be missing, or the arguments a valid prefix of what the model meant.
- **A call with no arguments** is `{}` only when the reply ended `ToolCalls` or `Stop`; otherwise it is a `Left`.

Code that ran the tool on the `{}`, or read the raw string, gets the `Left` instead; treat it as a failed call
(`ProcessingError` is not retried). In the agent runtime the run ends with the error rather than running a tool.

Source changes, all in the provider-author SPI:

- `StreamingToolArgumentParser.parse(raw)` returns `Result[ujson.Value]`: `Right({})` for blank, `Right` of the
  object, otherwise the `Left`. `StreamingAccumulator.currentToolCalls`, the live view, still shows unfinished
  arguments as the raw `Str`; `toCompletion` applies the rules.
- `OpenAICompatibleDialect.parseToolCalls` and `OpenAICompatibleDialect.lenientToolCalls` return
  `Result[Seq[ToolCall]]`. A dialect that overrides `parseToolCalls` returns a `Left` for a call whose arguments are
  not a JSON object; `lenientToolCalls` is still lenient about a missing `id` or `name`, not about arguments.

## `TokenUsage` and `ModelPricing` gain a prompt-cache field (#2115)

Provider-side prompt caching ([RFC 0008](../rfcs/0008-generation-controls) PR 6,
[cost controls](../guide/cost-controls#provider-side-prompt-caching)) is opt-in through
`CompletionOptions.withPromptCache`, and a call without it sends what it sent before. Two growth-safe types gain a
defaulted field, so only a positional pattern on them changes:

- `TokenUsage` gains `longCacheCreationTokens: Option[Int]`, the cache writes made with the 1-hour lifetime:
  `case TokenUsage(p, c, t, _, _, _)` becomes `case TokenUsage(p, c, t, _, _, _, _)`.
- `ModelPricing` gains `cacheCreationInputTokenCostAbove1hr: Option[Double]` as its last field.
- `CompletionOptions` gains `promptCache` and then `serviceTier` (#2726) as its last fields:
  `case CompletionOptions(t, _, _, _, _, _, _, _, _)` gains two `_`. `Completion` and `ModelCallCompleted` gain
  `servedTier: Option[ServiceTier]` (#2746), the tier that served the call, as theirs, and so does
  `TraceEvent.CompletionReceived` (#2750): `case TraceEvent.CompletionReceived(...)` with 0.6.0's fourteen binders
  gains a `_`.
- Java and Kotlin see no Scala defaults, so a call to `TokenUsage.apply`, `ModelPricing.apply` or
  `CompletionOptions.apply` from them passes the new arguments too (`Option.empty()`).

The `PositionalPatternsGainFields` Scalafix rule adds these binders ([Scalafix](scalafix#migration-rules)).

## Changes since 0.5.1 that no rule automates

The Scalafix rule `Llm4sMigrateFrom_0_5` ([Scalafix](scalafix#migration-rules); published from 1.0.0 as
`org.llm4s::llm4s-scalafix`, before that only as source in this repository) rewrites the mechanical source breaks
below: `.copy` on the newly growth-safe types, `String` API keys of the provider configs, the `fromValues` argument
order, `Completion.created`, positional patterns on types that gained fields, the `ServiceErrorOps` import and
`loadTextEmbeddingModel`, and since 0.6.0 `ReasoningEffort.None` and the string comparisons of
`StreamedChunk.finishReason`. It reports the removed APIs it cannot replace, and `budgetTokens`. It assumes 0.5.x code:
on 0.6.x, run `Llm4sMigrateFrom_0_6` instead ([Scalafix](scalafix#migration-rules)), which rewrites the renames
of 0.7.0 that its [rule table](scalafix#migration-rules) lists. Either runs with one command, from the 1.0.0 rules
and on code that still compiles against the release it is on
([Running a migration rule](scalafix#running-a-migration-rule)):

```bash
sbt "scalafixAll dependency:Llm4sMigrateFrom_0_6@org.llm4s::llm4s-scalafix:1.0.0"
```

These need a hand edit:

- Behaviour and configuration, not source: a supplied HTTP client is the caller's to close (#2341), the proxy settings
  (#2273), the file tools' default `blockedPaths` (#1408), console and OpenTelemetry tracing content, span names and
  kinds (#2311, #2275, #2531, #2137), `RetentionPolicy.maxAge` (#2130), and the dependencies `llm4s-java-api` and the
  Spring Boot starter no longer bring (#1496), `TokenUsage.completionTokens`, which no longer includes thinking
  tokens (#2564), a Gemini or Vertex AI reply a content filter stopped, now a `ContentFilteredError` (#2563), what
  `Thinking.Off` sends on OpenAI and Anthropic (#2115), the finish reasons the clients now map (#2613), and a broken
  or cut tool call, now a `Left` where it was `{}` or dropped (#2613).
- New cases and methods: exhaustive matches on `AgentStatus` (`TokenLimitReached`), `StreamEvent` (`Nested`) and
  `ReasoningEffort` (`Minimal`, `XHigh`, `Max`; reported), an
  `OpenAICompatibleDialect` that overrides `addReasoning` (now returns `List[RequestAdjustment]`, #2115), and
  a `Checkpointer` of your own (`sweep`, `threads`, `incompleteThreads`, `latestOfRuns`, `children`, the fenced
  `deleteThread`).
- Types that changed: `LLMClientPekko.streamComplete`'s `Future[Done]` (#1848), `LLM4STemplate.completeAsync`'s
  failure (#2276), `new X(...)` and a positional `copy` on a growth-safe type (reported), `MemoryManagerConfig`
  (was a class with no `copy`), and a key passed by position or another credential that is now a `Secret` (Langfuse,
  search-tool and store keys, `VectorStoreFactory.Config.withSecret`), `withBudgetTokens(n)`, whose replacement
  `Thinking.budget(n)` is a `Result` (reported), and a `StreamedChunk` finish reason set from a `String` that is not a
  literal (reported), and `StreamingToolArgumentParser.parse`, `OpenAICompatibleDialect.parseToolCalls` and
  `lenientToolCalls`, now a `Result` (#2613): the compiler names each, and only the caller can say what a broken
  call does.
- Removed or moved: `WorkspaceTools` (#1906), and `UsingOps` and `ModelSelector`, which need `llm4s-agent-tools` and
  `llm4s-rag` (#2358).
- Renames and new shapes of 0.7.0 that no rule rewrites; the compiler names each, and its note has the replacement:
  a tool handler's `Left("message")`, now `Left(ToolHandlerError.failed("message"))` (#2815); `new ToolRegistry(...)`,
  now `ToolRegistry.of` (#2816); `new` on a provider client, `ReliableClient`, `CachingLLMClient` or
  `CachedEmbeddingClient`, now the companion's factory (#2910, #2797); `ProviderDescriptor.buildClient`, now `build`,
  and the context arguments of `buildConfig` (#2811); `IdentityTokenSource`, now `IdentityTokenProvider`, with
  `token()` and `fetch()` now `current()` (#2811); `HeadroomPercent.None`, now `Zero`, and its `value`, now `asRatio`
  (#2822); `ContextManager.withDefaults`, now `create`, `ContextStep.name`, now `stage`, and
  `ConversationTokenCounter.openAI()` and `forModel`, now `cl100k()` and `OpenAITokenizers.counterFor` (#2818); the
  nested `Modality` cases (#2808); `ModelMetadata.inputCostPerToken` and `outputCostPerToken`, now `pricing` (#2823);
  `OptimisticLockFailure.memoryId`, now `recordId` (#2819); a middleware's `runsBefore`/`runsAfter`, now
  `Set[MiddlewareRef]`, and `withUpdate`, now `addUpdate` (#2795); `MessageRole.User.type` as a type (#2809);
  `MetricsRecording.withMetrics`' two arguments that had defaults (#2810); and `Tracing.create` (#2799).
- 0.7.0 changes outside Scala sources: the configuration key `llm4s.providers.provider`, now `llm4s.defaultProvider`
  (#2806); Java's `LlmException.isRecoverable()`, now `isRetryable()` (#2916); and `AgentBuilder.withTools` and
  `withToolSet`, which now append and refuse a duplicate tool name where a second call replaced the first (#2916).
- Since 0.7.0, with `ErrorProviderIdTyped` run: a literal provider in a pattern on an error
  (`case ServiceError(_, _, "openai", _, _)`), which the compiler reports, and the text of an error built with a
  provider name that was not lower case, which now reads in lower case
  ([note](#an-errors-provider-is-a-providerid-2936)). And behaviour, which no rule can rewrite: a `withOutput`
  agent's cut-off answer, now `AgentStatus.Truncated` where it was `Left(GraphError.OutputInvalid)`, Anthropic's
  default `max_tokens`, and the penalties a provider now sends or reports
  ([note](#a-cut-off-answer-ends-truncated-anthropics-max_tokens-default-and-reported-penalties-2933)).

## `completionTokens` no longer includes thinking tokens (#2564)

`TokenUsage.thinkingTokens` are never part of `TokenUsage.completionTokens`. OpenAI counts its `reasoning_tokens`
inside `completion_tokens`, and so do Perplexity's `output_tokens` and the `openai-compatible` providers that report
reasoning in OpenAI's format: DeepSeek, OpenRouter (its top-level `usage.reasoning_tokens` or
`completion_tokens_details`), and the generic provider (Groq and other servers), Mistral, Cohere and Z.ai whenever
they send `completion_tokens_details.reasoning_tokens`. Their clients now take the reasoning tokens out, so
`completionTokens` is the answer alone. Gemini, Vertex AI and xAI already counted them apart and are unchanged.
`totalTokens` is kept as the provider reported it, and `totalOutputTokens` is `completionTokens + thinkingTokens`.

The source does not change; numbers do. For an OpenAI reasoning model reporting prompt 81, completion 64 (46 of them
reasoning) and total 145, `completionTokens` was 64 and is now 18, with `thinkingTokens` 46. Code that read
`completionTokens` as all the output tokens reads `totalOutputTokens` instead. An `OpenAICompatibleDialect` of your
own reads `completion_tokens_details.reasoning_tokens` by default now; one for a provider that counts reasoning apart
from `completion_tokens`, as xAI does, overrides the new `reasoningInCompletion` to `false`.

Output token counts everywhere else are what the provider billed as output, thinking included, for every provider -
Gemini, Vertex AI and xAI too, where thinking used to be left out of them:

- `UsageSummary.outputTokens` and `ModelUsage.outputTokens` (so `AgentResult.usage`, `RunBudgets.maxTokens` and a
  child's token grant, which count input plus output), with `thinkingTokens` the part of it that was thinking. Do not
  add `thinkingTokens` to `outputTokens` again.
- The `output` token metric of `llm4s-observability-prometheus` and `llm4s-observability-micrometer`, and so
  `CostTracker`.
- OpenTelemetry's `gen_ai.usage.output_tokens`, Langfuse's generation `usage.output`, and the `output_tokens` beside
  `completion_tokens` and `thinking_tokens` on the trace collector's and Langfuse's token-usage records.
- Spring AI's `Usage.getCompletionTokens` from `Llm4sChatModel`, as Spring AI's own OpenAI binding counts it, and
  `RAGOperationCompleted.llmCompletionTokens`.

Dashboards and alerts on those counts see larger numbers for reasoning models. The raw `completion_tokens` on
trace events, spans and Langfuse records is `TokenUsage.completionTokens`, so for OpenAI, OpenAI-compatible and
Perplexity reasoning replies its value drops (64 becomes 18 above). A dashboard on it shifts: point it at
`output_tokens`, or at `completion_tokens` plus the new `thinking_tokens` beside it.

## A Gemini reply a content filter stopped is an error (#2563)

The Gemini and Vertex AI clients end a reply the same way from `complete` and from `streamComplete`:

- A content-filter `finishReason` (`SAFETY`, `RECITATION`, `LANGUAGE`, `BLOCKLIST`, `PROHIBITED_CONTENT`, `SPII`,
  `IMAGE_SAFETY`, `IMAGE_PROHIBITED_CONTENT`, `IMAGE_RECITATION`, `ESCALATION`) is a `Left(ContentFilteredError)`,
  even when text came before it: `complete` used to return that partial text as the answer, and a stream returned it
  too. A stream's text already passed to `onChunk` stays delivered; the error's message says how many characters
  the filter cut after. A prompt Gemini blocked (`promptFeedback.blockReason`) is one too, where `complete` failed
  with `UnknownError` and a stream returned an empty answer.
- Any other abnormal finish (`MALFORMED_FUNCTION_CALL`, `OTHER`, ...) is a `ValidationError` naming it only when the
  reply produced no text and no tool call; otherwise the reply is returned with what it produced.
- `STOP`, `MAX_TOKENS` or `CONTINUATION` with nothing produced is an empty answer.

Match `ContentFilteredError` (`provider`, `reason`, `promptBlocked`) where you handled a partial answer or an
`UnknownError`. `ReliableClient`'s circuit breaker counts it as neither a success nor a failure.

## Provider fromValues take model, key, then base URL (#2340)

Every provider config's `fromValues` now takes its common parameters in one order - model, then API key, then base
URL - with whatever is optional after what is required
([Configuration in code](../getting-started/configuration-in-code)). Two did not:

- `OpenAIConfig.fromValues(model, apiKey, organization, baseUrl)` is
  `OpenAIConfig.fromValues(model, apiKey, baseUrl, organization = None)`. Drop a `None` organization, or move it
  after the base URL:

  ```scala
  // Before
  OpenAIConfig.fromValues("gpt-4o", apiKey, None, OpenAIConfig.DEFAULT_BASE_URL)
  OpenAIConfig.fromValues("gpt-4o", apiKey, Some("org-1"), baseUrl, Some(ProviderId("requesty")))
  // After
  OpenAIConfig.fromValues("gpt-4o", apiKey, OpenAIConfig.DEFAULT_BASE_URL)
  OpenAIConfig.fromValues("gpt-4o", apiKey, baseUrl, Some("org-1"), Some(ProviderId("requesty")))
  ```

- `AzureConfig.fromValues(deployment, endpoint, apiKey, apiVersion)` is
  `AzureConfig.fromValues(deployment, apiKey, endpoint, apiVersion = AzureConfig.DEFAULT_API_VERSION)`, and
  `AzureConfig.fromTokenProvider(deployment, endpoint, tokenProvider, apiVersion)` is
  `AzureConfig.fromTokenProvider(deployment, tokenProvider, endpoint, apiVersion = AzureConfig.DEFAULT_API_VERSION)`.

The types differ (`Secret` against `String`, `Option[String]` against `String`), so a call in the old order no longer
compiles rather than misbehaving; calls with named arguments are unchanged.

Also new, and not breaking: a `given ModelRegistryService` now supplies the `ContextWindowResolver` that `fromValues`
takes, so `ModelRegistryService.default()` - the bundled catalogue, with no configuration file - is all a
values-only setup needs. A `ContextWindowResolver` given explicitly still wins.

## A supplied HTTP client is the caller's to close (#2341)

`LlmClientOptions.withHttpClient` hands the SDK-free provider clients (`ollama`, `gemini`, `vertexai`, `watsonx`,
`openai-compatible` and its dialects) an `Llm4sHttpClient` to send their requests through. A client supplied this
way, or to the `httpClient` constructor parameter of `OllamaClient`, `GeminiClient`, `VertexAIClient`,
`WatsonXClient` or an `OpenAICompatibleClient`, is no longer closed when the LLM client is closed: the caller owns
it. Close it yourself when your application stops. A client the provider builds itself is still closed with it.

`LlmClientOptions` gains `httpClient: Option[Llm4sHttpClient]` and `ProviderFeatures` gains `customHttpClient`,
`false` by default (and for `openai`, `azure`, `requesty`, `anthropic` and `bedrock`, whose SDKs keep their own
transport). A provider of your own that sends its requests through `options.httpClient` opts in with
`ProviderFeatures.default.withCustomHttpClient(true)`. Their `apply` overloads without the new field remain; a
pattern match on either type's extractor binds one more field.

## Credentials are Secret (#1932)

`ApiKey` is now an opaque type over `org.llm4s.security.Secret`, and every credential field in a config is a `Secret` (or `Option[Secret]`): provider and embedding configs, HTTP proxy and TLS-store passwords, the MCP server key, Langfuse keys, search-tool keys, image, speech, watsonx, jev and Bedrock keys, the Cohere reranker key, and store credentials (Postgres, Neo4j, Qdrant, Elasticsearch/OpenSearch, Redis, the S3 loader, the URL loader token). Every `Secret` is registered, and removed by exact match from the error messages, exchange logs and diagnostic text llm4s builds (trace content in a follow-up).

Before:

```scala
OpenAIConfig(apiKey = "sk-...", model = "gpt-4o", baseUrl = "https://api.openai.com/v1")
```

After:

```scala
import org.llm4s.security.Secret

OpenAIConfig(apiKey = Secret("sk-..."), model = "gpt-4o", baseUrl = "https://api.openai.com/v1")
```

- Read the value with `.reveal`. `toString` prints `***`.
- Convenience factories that took a `String` still do.
- `VectorStoreFactory.Config`: pass credentials with `withSecret("password" | "apiKey", Secret(...))`. `withOption("password" | "apiKey", ...)` is refused with a `ConfigurationError`.
- Values under 8 characters are not registered, so they are not masked by exact match.
- Java: the builders still take `String`. Constructing a config directly needs `Secret.apply(key)` (`import org.llm4s.security.Secret;`).

## Multimodal content parts (#1913)

In 0.5.1. Stage 3 slice F8b ([#1913](https://github.com/llm4s/llm4s/issues/1913)) gives user messages and tool results content parts: text, images and documents. Code that builds text messages and reads `content` needs no change. Source breaks, with no shims:

- **`UserMessage(parts: Vector[ContentPart])` and `ToolMessage(parts: Vector[ContentPart], toolCallId)`.** `UserMessage(text)` and `ToolMessage(text, toolCallId)` still build a one-text-part message, and `content` is the text view: the text parts, joined by newlines, `""` for a message that is all media. A pattern binds the parts now, so match on the type:

  ```scala
  // before
  case UserMessage(content)          => send(content)
  case ToolMessage(content, callId)  => sendResult(callId, content)
  // after
  case u: UserMessage => send(u.content)          // or map u.parts
  case t: ToolMessage => sendResult(t.toolCallId, t.content)
  ```

  The two are no longer case classes and have no extractor, so every positional pattern is a compile error: an extractor of the parts would have kept `s"$content"` compiling and printing the parts, and one of the text would have kept a client compiling that drops media. `msg.copy(content = ...)` becomes `msg.withParts(...)`.
- **`ToolOutcome.success(content: ToolContent, update)`**, with `ToolContent.Json(value)` for the JSON result a tool returned before and `ToolContent.Parts(parts)` for images and documents:

  ```scala
  ToolOutcome.Success(ujson.Str("done"))                    // before
  ToolOutcome.success(ToolContent.Json(ujson.Str("done")))  // after
  ToolOutcome.success(ToolContent.Parts(Vector(ContentPart.Text("The chart:"), chart)))
  ```

  `Parts` with no content, or with more than `ContentPart.MaxInlineBytes` of inline bytes in total, is the call's error result.
- **A custom `LLMClient`** that matched `UserMessage(content)` must decide what to do with media parts: send them, or return `Left(UnsupportedContentPartError(provider, part.kind, role))` before sending. `org.llm4s.llmconnect.provider.PartEncoding` is `private[llm4s]`; a provider outside llm4s uses `ContentPart.kind` and the error directly, and runs `ProviderModuleChecks.assertContentParts` in its module spec.
- **Stored data needs nothing.** The JSON of a one-text message is unchanged, and the old shape reads, so memory stores, saved conversations and thinking bindings are unaffected. An agent thread's `messages` and `tool-results` keys move to schema version 2 and migrate on restore; a thread written by this version cannot be restored by an earlier one.
- **A client refuses what it cannot send, before sending**: a part kind, source or format its wire format cannot carry is `UnsupportedContentPartError`. A provider's own limits (how many images, how large a request, which formats a model reads) are the provider's: its 400 or 413 comes back as a typed `ValidationError` or `ServiceError` with its message. The guide's [table](../guide/multimodal.html#what-each-provider-sends) lists what each takes.
- **An agent thread holds at most 20 MiB of inline media** (`Messages.MaxInlineBytes`): media added past it is replaced by a text part naming what was removed. Context budgets (`ConversationTokenCounter`, `ContextPruning`) count media by estimate.
- **Inline media is bounded** at 5 MiB per part by `ContentPart.image` / `ContentPart.document`, which return a `Result`. Every agent checkpoint copies the thread's messages, so larger media goes at a `MediaSource.Url` or in a `MediaSource.ProviderFile`. See [Multimodal content](../guide/multimodal.html).
## Subagents and isolation (Phase 4)

Not in a release yet. Stage 4 lets an agent delegate to child agents on threads of their own ([#2044](https://github.com/llm4s/llm4s/issues/2044)). Each slice adds its breaks here.

### Child threads (#2045)

A thread can now be a child thread: its checkpoints record a `RunOrigin` naming its parent ([design 4.41](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.41-stage-4-slice-k1-child-threads.md)).

- **Checkpoint format 8 and SQLite schema 6, with no way back.** Format 8 adds `Checkpoint.origin`, `Checkpoint.outcome` (how a settled run ended, written with every terminal checkpoint) and `Checkpoint.deleting` (a cascade delete that has begun; admission refuses such a thread with `GraphError.ThreadDeleting` until `deleteThread` finishes it); schema 6 adds `parent_thread_id` and `child_order` to `llm4s_checkpoint_threads`. Existing threads read and continue unchanged: format 7 reads as a top-level thread with no outcome, and the schema step backfills nothing. An older build refuses a format-8 checkpoint (`UnsupportedCheckpointFormat`) and a schema-6 file, so upgrade every process sharing a store together.
- **A `Checkpointer` implements `children`.** `children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]]` is a new abstract method: list the threads whose latest checkpoint's `origin.parent` is `parent`, in the order each first named it, paged after `after`, never a deleted one, and refuse an unknown `after` with a `ValidationError`. Index the parent as you commit a checkpoint (`checkpoint.origin.map(_.parent)`). A store that only forwards to another forwards this too. `CheckpointerContract` checks it.
- **`deleteThread` and `Agent.forget` cascade.** Deleting a thread first deletes every child thread below it; a child with a live run anywhere refuses the whole delete with `ThreadBusy`, deleting nothing.
- **`Checkpointer.deleteThread(threadId, token)` is `deleteThread(threadId, token, fence: Option[(ThreadId, FencingToken)])`** ([#2063](https://github.com/llm4s/llm4s/issues/2063)). With `Some((other, otherToken))` a store deletes only if `token` is the thread's current claim and `otherToken` is `other`'s, both checked in the same lock or transaction as the delete; otherwise it returns `GraphError.StaleClaim` - for the thread when its own claim is stale (checked first), else for `other` - and deletes nothing. `None` is the previous behaviour. The runtime fences a cascade's deletes by the root's claim and retention's by the parent's. A wrapper forwards it: `def deleteThread(threadId: ThreadId, token: FencingToken, fence: Option[(ThreadId, FencingToken)]) = underlying.deleteThread(threadId, token, fence)`. `CheckpointerContract` checks it.
- **A store checks `Commit.fence`** ([#2071](https://github.com/llm4s/llm4s/issues/2071)). `Commit` gains `fence: Option[(ThreadId, FencingToken)]` (`withFence`). With `Some((other, otherToken))` a store applies the commit only if its `token` is the thread's current claim and `otherToken` is `other`'s, both checked in the same lock or transaction as the write; otherwise it returns `GraphError.StaleClaim` - for the thread when its own claim is stale (checked first), else for `other` - and writes nothing. The runtime fences every admission and settlement a parent drives on a child (its first commit, `recoverChild`, `resumeChild`, `settleChild`, the abandon at `start`) by the parent's claim, so a stale parent task never registers or runs a child after a successor took the parent over. A wrapper that forwards the `Commit` whole needs no change. `CheckpointerContract` checks it.
- **Child threads refuse direct calls.** `start`, `recover`, `resume`, `fork`, `updateState` and `deleteThread` (and the `Agent` methods over them) on a child thread are refused with the new `GraphError.ChildThread`; delete its parent to delete it. An exhaustive match on `GraphError` needs the new case.
- **Settled children are pruned.** A thread keeps its newest 64 settled children (`RunConfig.withChildRetention`); older ones are deleted as the parent's run completes and when its next run starts, where a child an earlier run left `Running` or `Suspended` is first settled `Failed("abandoned", ...)`.
- **`Checkpoint.apply` and `RunConfig.apply`** gain parameters with defaults (`origin`, `outcome`; `childRetention`); calls that pass every previous argument resolve to the kept overloads unchanged.

### Token budgets and the run's deadline (#2047)

- **`AgentStatus.TokenLimitReached`** is a new case, so an exhaustive `match` on `AgentStatus` needs it. A turn ends
  with it when the run's token budget is used; like `StepLimitReached` it is a completed turn with no answer. In Java
  and Kotlin, `AgentStatusKind.TOKEN_LIMIT_REACHED` is a new constant, so a `switch` or `when` without a `default`
  needs it too. `TurnOutcome.TokenLimitReached` likewise, for code reading a `TurnOutput`.
- **`RunBudgets.maxTokens`** is a new field (default `None`, no budget). `RunBudgets(maxSupersteps, timeout,
  maxConcurrency)` still compiles; a pattern `case RunBudgets(s, t, c)` needs a fourth binder. Set a budget with
  `RunBudgets.default.withMaxTokens(n)`; a run can overshoot it by the one model call in flight
  ([design 4.43](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.43-stage-4-slice-k3-token-budgets.md)).

### Child-run supervision (#2046)

The runner that delegated calls use to run a child is internal ([design 4.42](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.42-stage-4-slice-k2-child-run-supervision.md)); two things change for everyone:

- **A tool call's own model usage is counted.** The usage a tool call's task records (a tool that calls a model through its context, or a delegated child's roll-up) now commits to the thread's `usage` with the call's result, so `AgentResult.usage` can be higher than before for agents with such tools.
- **The state key ids `delegation-prior-usage`, `delegation-input`, `delegation-counted` and `delegation-grant` are reserved.** Every agent graph registers them; a tool or middleware declaring a key with one of these ids is refused at `build()`.

### Child suspension through the parent (#2050)

A child that stops for an approval or a question now suspends its parent ([design 4.46](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.46-stage-4-slice-d3-child-suspension.md)):

- **`AgentStatus.Suspended` has a fifth field, `delegated`** (default empty): a subagent's interrupts, under composite ids `<parent>~<child>`. A positional pattern `case AgentStatus.Suspended(a, q, m, b)` needs a fifth binder, `case AgentStatus.Suspended(a, q, m, b, _)`; one matching by type (`case s: AgentStatus.Suspended`) is unchanged. Answer a delegated interrupt with the usual helpers by its composite id. In Java and Kotlin, `pending` lists each one as its kind in the child, under the composite id.
- **A suspended child is no longer a tool error.** Code that waited for the parent model to see `subagent '<id>' suspended` now gets the parent `Suspended` instead, and must answer or abandon it.
- **`ToolLoop.questions` no longer lists a `task` call waiting on its child**; `ToolLoop.delegatedQuestions` does.

### Explicit inheritance (#2049)

- **`RunEvent.RunFailed`, `RunCancelled` and `RunTimedOut` gain `steps: Long`** (default 0): how many of the run's
  failed attempts were an agent's model step, which a recovered child counts against its step grant ([design 4.45](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.45-stage-4-slice-d2-inheritance.md)).
  A positional pattern needs another binder: `case RunEvent.RunCancelled(_)` becomes `RunCancelled(_, _)`, and
  `RunFailed(message, usage)` becomes `RunFailed(message, usage, _)`. Stored events read unchanged (a missing `steps`
  is 0).

### Nested streams and tracing (#2051)

A parent's subscribers can follow its children, and a child's trace names its parent ([design 4.47](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.47-stage-4-slice-d4-nested-streams-and-tracing.md)):

- **`StreamEvent.Nested(child, event)`** is a new case, so an exhaustive `match` on `StreamEvent` needs it. Only a subscription made with `nested = true` receives one; add `case _: StreamEvent.Nested => ()` where you match exhaustively.
- **`TraceEvent.AgentRunEnded` gains `parentThreadId` and `parentRunId`** (`Option[String]`, default `None`), after `timestamp`. Construction is unchanged; a positional pattern `case AgentRunEnded(t, r, a, s, m, u, ts)` needs two more binders, or match on the type and read the fields.
- **OpenTelemetry span names.** The ended-run span `Agent Run` is now `invoke_agent <agent>`, and the tool span `Tool Execution: <tool>` is now `execute_tool <tool>`, each with `gen_ai.operation.name`; a dashboard or alert keyed on the old names needs the new ones. A child run's span carries `llm4s.parent.thread_id` and `llm4s.parent.run_id`.
- **`RunHandle.subscribe` takes `nested`** (`subscribe(capacity, nested = false)`), so a `RunHandle` of your own implements the two-argument form. A call site is unchanged.
- **A subagent traces to its parent's `Tracing`.** An agent built `withTracing` now also traces its subagents' runs, each ending with its own `AgentRunEnded`.

### Subagents in the harness profile (#2052)

`HarnessProfile` gains `subagents: SubagentConfig` ([design 4.48](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.48-stage-4-slice-p1-harness-subagents.md)). `HarnessProfile()` has `SubagentConfig.none`, so the minimal profile is unchanged; `HarnessProfile.deepAgents` and `HarnessConfigLoader.load()` now turn on the general-purpose subagent.

- **A `deepAgents` agent offers a `task` tool.** An application tool named `task` now clashes at `build()`: rename it, or turn the subagent off with `profile.withSubagents(SubagentConfig.none)` (or `llm4s.agent.harness.subagents.generalPurpose.enabled = false`).
- **Permission rules decide `task` calls.** A `deepAgents` profile with permission rules denies `task` unless a rule allows it, as for `write_todos`: add `PermissionRule.allow(Operation.Invoke, "task:general-purpose")` (or `"task:*"`).
- **`withDelegation` beside a harness is refused.** The profile's `SubagentConfig.delegation` sets the bounds: `profile.withSubagents(profile.subagents.withDelegation(config))`.
- **A harness agent's `delegation` middleware runs inside its permissions and outside its other middleware**, not innermost, so its prompt section comes before the harness's. Subagents added with `AgentBuilder.withSubagents` beside a harness follow the profile's own.

## Production adapters (Stage 5)

Not in a release yet. Stage 5 runs one agent service on several machines ([#2129](https://github.com/llm4s/llm4s/issues/2129)). Each slice adds its breaks here.

### The PostgreSQL checkpointer (#2130)

`llm4s-agent-checkpoint-postgres` adds `PostgresCheckpointer` ([design 4.50](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.50-stage-5-slice-s1-postgresql-checkpointer.md)). Adopting it needs nothing of existing code; these change for every store:

- **`RetentionPolicy.maxAge` is judged by commit time.** `prune` compares the store's clock with the time the store committed each checkpoint and event, by that same clock, no longer with the producer's `Checkpoint.createdAt` and event `timestamp`, so a writer with a skewed clock makes nothing too old or too young. With one clock for the runtime and the store, as before, nothing changes. A custom `Checkpointer` records a commit time with each checkpoint and event and judges `maxAge` by it; `CheckpointerContract` checks it, with a skewed producer.
- **SQLite schema 7, with no way back.** It adds the commit times to `llm4s_checkpoint_history` and `llm4s_checkpoint_events`. Rows written before it have none and are judged by their producer's timestamps, as before. A schema-6 build refuses a schema-7 file, so upgrade every process sharing a file together.
- **`GraphError.UnsupportedStoreSchema(store, found, supported)`** is a new case, returned when a store's storage was migrated by a newer build (the PostgreSQL store's refusal of a newer schema). An exhaustive match on `GraphError` needs it.
- **Upgrading a PostgreSQL deployment.** Instances of releases that change neither the store's schema nor the checkpoint format can be replaced one at a time. A release that changes either needs every instance stopped before the first new one starts: an old instance that meets the newer schema refuses to open with `UnsupportedStoreSchema` rather than writing anything.

### The thread registry (#2132)

Threads can be listed and searched ([design 4.52](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.52-stage-5-slice-s3-thread-registry.md)). Code that only runs agents changes nothing; these change:

- **Two new abstract `Checkpointer` methods.** `threads(query: ThreadQuery): Result[ThreadPage]` and `incompleteThreads(tenantId, after, limit): Result[ThreadPage]`. A custom store implements both (a forwarding wrapper forwards both), tenant-scoped, newest and oldest first by the time the store last committed a checkpoint or metadata to the thread, and passes `CheckpointerContract`'s new cases.
- **`Commit.metadata: Option[ThreadMetadata]`.** A commit carrying it replaces the thread's metadata, fenced like the rest of the commit; a store keeps it until another replaces it or the thread is deleted. `Commit.apply` keeps its earlier forms.
- **`RunConfig.metadata` is now used.** A top-level `start` with non-empty metadata sets it on the thread, and refuses metadata past `ThreadMetadata`'s limits (32 entries, keys of `A-Z a-z 0-9 _ . : / -` up to 64 characters, values up to 256) with a `ValidationError` before it claims anything. Change it later with `GraphRuntime.updateMetadata`.
- **Schemas move on, with no way back.** SQLite schema 8 and PostgreSQL schema 2 add the registry's columns and indexes, backfilled from each thread's latest checkpoint when the store opens. An older build refuses the new schema, so upgrade every process sharing a file or database together.

### One trace per run (#2137)

- **Positional patterns** on `TraceEvent.CustomEvent`, `TokenUsageRecorded`, `ErrorOccurred` and `AgentRunEnded` need
  one more binder, for the new last field `span: Option[TraceSpan]` (`case TraceEvent.CustomEvent(name, data, at, _)`).
  Constructor calls are unchanged: `span` defaults to `None`.
- **OpenTelemetry dashboards and queries.** An agent run is now one trace: `invoke_agent <agent>` is the parent of the
  run's `chat <model>` (new: one per model call, `SpanKind.CLIENT`), `execute_tool <tool>` (now `SpanKind.INTERNAL`)
  and its other events, and a delegated child's `invoke_agent` is under the `execute_tool task` that started it. A
  query that counted one trace per event, or keyed on the old parentless spans, needs rewriting; filter by
  `gen_ai.operation.name` instead.
- **Langfuse trace ids.** A traced agent run's Langfuse trace id is now its 32-hex trace id (the OpenTelemetry one),
  not the run id, which moves to the trace's `run_id` metadata. A child run has no trace of its own: find it inside
  its parent's trace, under the `execute_tool task` observation. A run traced without a placement (a hand-built
  `AgentRunEnded`) is unchanged.
- **Continuing a trace.** To put a run in an upstream request's trace, pass its `traceparent`:
  `RunConfig().withTraceContext(TraceContext.parse(header).toOption)`. A `resume` or `recover` with no context
  continues the trace its work started in.
- **Stored data needs nothing.** `RunOrigin.trace` and `Checkpoint.trace` are optional; a thread stored without them reads as `None`. A `resume` or `recover` given no `traceContext` now continues the trace its work started in (`Checkpoint.trace`) rather than starting one.

### Store-wide retention and tombstone reclamation (#2059)

A store can be swept, a bounded page at a time, and a deleted thread's tombstone is no longer kept for good ([design 4.53](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.53-stage-5-slice-s4-store-wide-retention-and-tombstone-reclamation.md)). Code that only runs agents changes nothing; these change:

- **A new abstract `Checkpointer` method.** `sweep(policy: SweepPolicy, after: Option[ThreadId], guard: SweepGuard): Result[SweepPage]` prunes every thread the store holds by `policy.retention`, as `prune` does, and reclaims tombstones older than `policy.tombstoneAge`, at most `policy.batchSize` threads per call. A custom store implements it (a forwarding wrapper forwards it) and passes `CheckpointerContract`'s new cases; a store whose cases share one database also overrides the contract's new `isolatedCheckpointer` hook with storage of its own.
- **A reclaimed id numbers from the store's reclamation mark.** Once a sweep has reclaimed a tombstone, a new thread of an id with no tombstone - any id, not only a reclaimed one - numbers its events from the highest first number of every tombstone reclaimed, not from `1`, so a reader of the reclaimed log still gets `GraphError.ThreadDeleted`. Code that assumed a new thread's first event is `1` reads from `afterSeq = 0` instead, as a reader of a deleted id already had to.
- **Paging children after a reclaimed child** refuses that cursor with a `ValidationError`, as for any id the store does not know as a child.
- **Schemas move on, with no way back.** SQLite schema 9 and PostgreSQL schema 3 record when each tombstone was deleted and hold the reclamation mark; the tombstones a store already holds are dated from the upgrade. An older build refuses the new schema, so upgrade every process sharing a file or database together.

### The run queue (#2134)

Runs can be submitted to a durable queue that workers on any machine take ([design 4.55](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.55-stage-5-slice-w1-run-submission-and-the-durable-run-queue.md)). Code that only runs agents changes nothing; these change:

- **A new abstract `Checkpointer` method.** `latestOfRuns(threadId, runIds): Result[Option[Checkpoint]]` answers the newest checkpoint any of a set of runs committed on a thread, by an index on the run. A custom store implements it (a forwarding wrapper forwards it) and passes `CheckpointerContract`'s new cases; a store that also offers a `RunQueue` keeps, through `prune`, the newest checkpoint of every run an open entry recorded, and passes `RunQueueContract`.
- **Schemas move on, with no way back.** SQLite schema 10 and PostgreSQL schema 4, the step after store-wide retention's, add `run_id` to every history row (backfilled from its JSON when the store opens, and indexed by thread, run and position) and the queue's two tables. An older build refuses the new schema, so upgrade every process sharing a file or database together.

### Cancel and the busy-thread policy (#2136)

A queued run can be cancelled from any machine, and a submission's `BusyThreadPolicy` is acted on in full ([design 4.57](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.57-stage-5-slice-w3-cancel-from-any-machine-and-the-busy-thread-policy.md)). A direct `start`, `resume` or `recover` on a busy thread is still refused with `ThreadBusy`, and `AgentRun.cancel` of a direct run still leaves its thread `Running` for `recover`. These change:

- **`Reject` also refuses a thread a direct run holds.** A `Reject` submission (the default) is refused with `GraphError.ThreadBusy` while a live claim holds the thread, not only while it has an open queue entry. Submit with `Enqueue` to wait behind the direct run instead.
- **A new abstract `RunQueue` method.** `cancel(tenantId, target, reason)` closes an unstarted entry at once and asks a started one to stop; a custom queue implements it, answers the request with every lease it grants or renews (`Lease.stop`) and on `QueueEntry.stopRequest`, and passes `RunQueueContract`'s new cases.
- **Schemas move on again.** SQLite schema 11 and PostgreSQL schema 5 add each queue entry's stop request. An older build refuses the new schema, so upgrade every process sharing a file or database together.

## A workspace container has no egress (#870)

A `ContainerisedWorkspace` whose sandbox allows no network (`networkAllowed = false`, the default, also when no
`sandboxConfig` is given) starts its container with `--cap-add NET_ADMIN -e LLM4S_NO_EGRESS=1`, and the image's entry
point blocks every connection out of it ([Container egress](workspace-sandbox#container-egress)). Behaviour, not
source:

- Nothing in the container reaches another host or container any more; on Linux its commands already could not.
  Set `networkAllowed = true` for a workspace that needs the network.
- The container needs an image from this release (with `iptables`) and a Docker host that lets a container with
  `NET_ADMIN` set its own firewall. Where it cannot, the container exits at start (status 78) and `startContainer`
  fails, rather than run without the isolation the sandbox asked for.
- A runner container started by hand keeps its egress unless it is given the same two flags; the runner says so at
  startup.

## Workspace command confinement (#1983)

Not in a release yet. On Linux, the workspace runner now starts every `execute` command under Landlock and, unless the network is allowed, a seccomp filter ([#1983](https://github.com/llm4s/llm4s/issues/1983), [design 4.39](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.39-workspace-command-confinement.md)). Behaviour breaks, with no opt-out; off Linux (macOS, Windows) nothing changes:

- **Commands lose the network by default.** `networkAllowed` (default `false`) now binds commands: `socket` fails for every family but `AF_UNIX`, so `sbt`, `curl` or a package manager that fetches fails. Set `networkAllowed = true` where a command needs it; in a container, pass the whole config:

  ```scala
  new ContainerisedWorkspace(dir, image, 8080,
    sandboxConfig = Some(WorkspaceSandboxConfig.Permissive.copy(networkAllowed = true)))
  ```
- **`readOnlyPaths` and `allowedPaths` bind commands too**, not only the runner's own file operations: a command cannot write under a read-only path (nor create one that does not exist yet), cannot create or delete entries directly in a directory above one, and cannot read outside `allowedPaths`.
- **A kernel without Landlock ABI v3** (Linux 6.2), or an image without the `llm4s-confine` helper, refuses every command `SANDBOX_UNAVAILABLE`; it never runs one unconfined. An amd64 image under emulation (Docker Desktop's Rosetta on Apple silicon) is such a case: build the image for the host architecture.
- **A runner started outside the image** grants commands `/tmp` only with `-XX:+DisableAttachMechanism` and `-XX:+PerfDisableSharedMem`, and then refuses git commands (`EXECUTION_FAILED`) while its `java.io.tmpdir` lies in `/tmp` or another path commands may write: set it to an owner-only directory elsewhere, as the image does (`/var/tmp/llm4s-runner`).

## Workspace backends (#1906)

Not in a release yet. Stage 3 slice F2 ([#1906](https://github.com/llm4s/llm4s/issues/1906)) puts the workspace behind the harness's `FileBackend` and `SandboxBackend` ([design 4.33](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.33-stage-3-slice-f2-workspace-backends.md)). Source and behaviour breaks, with no shims:

- **`org.llm4s.toolapi.WorkspaceTools` and `createDefaultWorkspaceTools` are deleted.** Their `ToolFunction`s called the workspace directly, with no locks, no replay guard and no bounds of their own. Build the harness tools over the workspace instead and give the agent a `ToolSet`; `codegen.CodeWorker` does this itself:

  ```scala
  // before
  WorkspaceTools.createDefaultWorkspaceTools(workspace).map(tools => builder.withTools(new ToolRegistry(tools)))
  // after
  for
    files   <- WorkspaceFileBackend("ws1", workspace)
    sandbox <- WorkspaceSandboxBackend("ws1", workspace)
    tools   <- ToolSet.of((FileTools(files) :+ ExecuteTool(sandbox))*)
  yield builder.withTools(tools)
  ```

  | Old tool | Replacement | Notes |
  |---|---|---|
  | `explore_files` | `ls`, `glob` | paths are absolute (`/src`), the workspace root is `/` |
  | `read_file` | `read_file` | numbered lines, paged by `offset` / `limit` |
  | `write_file` | `write_file` | creates parent directories; no append mode |
  | `modify_file` | `edit_file` | replaces exact text (`old_string`, `new_string`), not line ranges |
  | `search_files` | `grep` | a regex; `include` filters files |
  | `execute_command` | `execute` | `workdir` (default `/`), `timeout_seconds`; the result is text, `exit code: N` then `stdout:` and `stderr:` |

  An `execute` call is the resource `(Execute, <command>)`, so it needs a `PermissionMiddleware` rule if you use one.
- **`WorkspaceAgentInterface` has two new methods and two new parameters.** `fileSnapshot(path, includeContent, followLinks)` and `deleteFile(path, expectedHash)`; `writeFile(..., expectedHash)` and `executeCommand(..., maxOutputBytes)` (each stream's bound in UTF-8, JSON-escaped bytes). A class implementing the interface implements them; a caller that passes arguments by position is unaffected. The wire messages' new fields have defaults, so an older client and a newer runner (or the reverse) still talk, without the new calls.
- **`ContainerisedWorkspace` keeps the runner's error code.** A refused call used to throw `WorkspaceAgentException(..., "EXECUTION_FAILED")` with the code in the message; it now throws the runner's own code (`FILE_NOT_FOUND`, `PATH_NOT_ALLOWED`, ...), as `WorkspaceAgentInterfaceRemote` does. `EXECUTION_FAILED` now means the transport failed.
- **The runner enforces the sandbox's paths and limits** (#870): a file operation or a command's working directory outside `allowedPaths` is `PATH_NOT_ALLOWED`, a mutation under `readOnlyPaths` is `PATH_READ_ONLY`, and a write, append or line edit that would leave a file over `maxFileSize` is `SIZE_LIMIT_EXCEEDED`. Each path check is made on what the path names through symbolic links as well as on its text, so a file operation through a link that leads outside the workspace is now `PATH_ESCAPE_ATTEMPT`. A config that set these and relied on them being ignored now has its calls refused.
- **Command output is kept as it was written**, up to `maxOutputSize` bytes per stream (UTF-8, JSON-escaped, as it is carried). The direct `executeCommand` path used to read it line by line, ending every line with `\n` (a final line without one gained it, `\r\n` became `\n`) and keeping whole lines past the bound.

```scala
// a FileBackend and a SandboxBackend over a running ContainerisedWorkspace
for
  files   <- WorkspaceFileBackend("ws1", workspace)
  sandbox <- WorkspaceSandboxBackend("ws1", workspace)
yield (files, ExecuteTool(sandbox))
```

## Direct style with Ox: `llm4s-ox` (#2332)

Nothing changes for existing code: `llm4s-ox` is a new, Experimental module over `llm4s-core` and Ox. To stream a reply
as an Ox `Flow`, fan out, race providers or retry with Ox, add `"org.llm4s" %% "llm4s-ox"` and
`import org.llm4s.ox.*`; see [Direct style and Ox](../guide/direct-style).

## The doctor is `llm4s-doctor` (#2283)

The doctor moved from the unpublished `llm4s-config-policy` to the published `llm4s-doctor`, package `org.llm4s.doctor`
(`Doctor`, `DoctorCli`, `DoctorReport`, `DoctorCheck`, `CheckStatus`, `DoctorOptions`). Run it from your own project
([Doctor](doctor#running-it)): `sbt "Test/runMain org.llm4s.doctor.DoctorCli"` with `llm4s-doctor` as a test dependency.
In a checkout of this repository `sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli"` still works.

- `Doctor.run(options)` checks the application's own configuration; `Doctor.run(source, options, ports)` and
  `DoctorPorts` are internal.
- Without `--classpath`, the "Provider module" step reports the module as found "on this classpath": the doctor's
  classpath is now the one it is launched on, not one with every built-in provider module.

## `llm4s-secrets-vault`, a new module (#2479)

Nothing to change: `llm4s-secrets-vault` is a new, experimental module over `llm4s-core` alone, and no other module
depends on it. To read a provider's API key from HashiCorp Vault without Vault Agent or a CSI driver, add it and set
`VaultApiKeyProvider(VaultSettings(...))` with `withApiKeyProvider`; see
[Secrets and key rotation](../guide/production-deployment#secrets-and-key-rotation). `apiKeyFile` is unchanged.

## `llm4s-secrets-aws`, a new module (#2479)

Nothing to change: `llm4s-secrets-aws` is a new, experimental module over `llm4s-core` alone, and no other module
depends on it. To read a provider's API key from AWS Secrets Manager without the Secrets Store CSI driver or External
Secrets Operator, add it and set `AwsSecretsManagerApiKeyProvider(AwsSecretsManagerSettings(...))` with
`withApiKeyProvider`; see [Secrets and key rotation](../guide/production-deployment#secrets-and-key-rotation). `apiKeyFile`
is unchanged.

## `llm4s-repl`, a new module (#2342)

Nothing to change: `llm4s-repl` is a new, experimental module, and no other module depends on it. To try llm4s from the
Scala CLI REPL, a Metals worksheet or a script, add it (with a provider module) and `import org.llm4s.repl.*`; see
[Try llm4s in the REPL](../getting-started/repl). Keep it out of production code: it prints to the console and keeps
the conversation in memory.

## `llm4s-java-api` and the Spring Boot starter no longer bring the provider modules (#1496)

`llm4s-java-api` depended on `llm4s-openai`, `llm4s-anthropic`, `llm4s-ollama`, `llm4s-gemini` and
`llm4s-openai-compatible`, so every Java user received the OpenAI and Anthropic SDKs, OkHttp, Jackson and the Kotlin
standard library ([dependency footprint](dependency-conflicts#dependency-footprint)). They are now optional
dependencies: the facade's POM brings `llm4s-core` and `llm4s-agent`, and you declare the provider modules you call,
at the same version. `llm4s-spring-boot-starter` does the same for `llm4s-openai`, `llm4s-anthropic` and `llm4s-ollama`,
the providers its flat `llm4s.provider` keys build.

| You use | Add |
|---|---|
| `Llm4s.openAi()`, `Llm4s.azureOpenAi()`, a section with `provider = "openai"`, `"azure"` or `"requesty"`, or `openai/` embeddings | `org.llm4s:llm4s-openai_3` |
| `Llm4s.anthropic()`, or `provider = "anthropic"` | `org.llm4s:llm4s-anthropic_3` |
| `Llm4s.gemini()`, or `provider = "gemini"` or `"vertexai"` | `org.llm4s:llm4s-gemini_3` |
| `Llm4s.ollama()`, `provider = "ollama"`, or `ollama/` embeddings | `org.llm4s:llm4s-ollama_3` |
| `Llm4s.openAiCompatible()`, or DeepSeek, Mistral, Cohere chat, OpenRouter, Z.ai, xAI or `openai-compatible` | `org.llm4s:llm4s-openai-compatible_3` |

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai_3:$llm4sVersion")   // was brought by llm4s-java-api
}
```

Without the module, nothing fails at startup: a typed builder's `build()`, or the starter's `llm4s.provider`, returns a
`CONFIGURATION` failure that names the artifact to add, and a configured section whose provider is absent fails as it
always did for modules outside the facade. With `llm4s-bom` imported, the provider module needs no version. The
Kotlin API (`llm4s-kotlin-api`) depends on `llm4s-java-api`, so the same applies to it.

## `budgetTokens` and `ReasoningEffort.None` are replaced by `Thinking` (#2115)

`CompletionOptions` separates *whether* a model thinks from *how hard*
([RFC 0008](../rfcs/0008-generation-controls), [Cost controls](../guide/cost-controls)). `thinking: Option[Thinking]` is
new: `Thinking.Off`, `Thinking.Adaptive` or `Thinking.budget(n)`. `ReasoningEffort` gains `Minimal`, `XHigh` and `Max`.
Two things are removed, with no deprecation:

| Removed | Use instead |
|---|---|
| `CompletionOptions.budgetTokens`, `withBudgetTokens(Int)`, `withBudgetTokens(Option[Int])`, the `budgetTokens` argument of `CompletionOptions(...)` | `thinking`: `Thinking.budget(n).map(options.withThinking)`, a `Result` because a budget of 0 or less is a `ValidationError` |
| `ReasoningEffort.None` | `Thinking.Off` |
| Java: `JCompletionOptions.Builder.budgetTokens`, `JCompletionOptions.budgetTokens()`, `JReasoningEffort.NONE` | `thinking(JThinking.budget(n))`, `thinking()`, `JThinking.off()` |
| Kotlin: the `completionOptions { budgetTokens = n }` property | `thinking = JThinking.budget(n)` |

```scala
// Before
CompletionOptions().withBudgetTokens(8000)
CompletionOptions().withReasoning(ReasoningEffort.None)

// After
Thinking.budget(8000).map(CompletionOptions().withThinking)   // Result[CompletionOptions]
CompletionOptions().withThinking(Thinking.Off)
```

```java
// Before
JCompletionOptions.builder().reasoning(JReasoningEffort.NONE).budgetTokens(8000).build();
// After
JCompletionOptions.builder().thinking(JThinking.budget(8000)).build();   // throws IllegalArgumentException for <= 0
JCompletionOptions.builder().thinking(JThinking.off()).build();
```

What a migrated call sends:

- **`budgetTokens(n)` → `Thinking.budget(n)`** sends the same budget. A budget that does not fit Anthropic's
  `[1024, max_tokens)` is still fitted, and is now reported as `Adjusted` on `Completion.adjustments`.
- **`ReasoningEffort.None` → `Thinking.Off`** sends what `None` sent on Z.ai (`thinking.type: disabled` or
  `reasoning_effort: none`), xAI and watsonx (`low`, the least they allow) and Perplexity (`minimal`). On
  **OpenAI** it differs: `None` sent no `reasoning_effort`, so the model used its default effort, while `Thinking.Off`
  asks for the lowest effort the model accepts (`none` where the model can turn thinking off). To keep the model's
  default, set neither. On **Anthropic**, `None` sent no thinking, and `Thinking.Off` sends `thinking: disabled`.
- **`reasoning` alone** sends the same request as before, except to a model that rejects a manual budget (Claude
  Opus 4.7 and later), which failed with a 400 and is now sent adaptive thinking at that effort.

A stored or hand-written `"none"` no longer reads as a `ReasoningEffort`: the reader fails with
`"none" is no longer a ReasoningEffort; use Thinking.Off`, and `ReasoningEffort.fromString("none")` returns `None`.
An exhaustive `match` on `ReasoningEffort` (or a Java `switch`, a Kotlin `when`, on `JReasoningEffort`) needs cases
for the three new levels.

The `ThinkingReplacesBudgetTokens` Scalafix rule ([Scalafix](scalafix#migration-rules)) rewrites
`withReasoning(ReasoningEffort.None)` and `CompletionOptions(reasoning = Some(ReasoningEffort.None))` to
`Thinking.Off`. It reports, without rewriting, a budget (its replacement is a `Result`), any other use of
`ReasoningEffort.None`, and a `match` on `ReasoningEffort` with no catch-all. `Thinking.Off` takes precedence over
`reasoning`, where `None` was only a `reasoning` level that a later `withReasoning(High)` replaced: the rule reports a
chain that sets `withReasoning` again instead of rewriting it, but check options that are changed later through a
`val`. Java and Kotlin sources need a hand edit.

## Smaller breaks since 0.4.1

Changes too small for a section of their own. Each is listed under Breaking changes in the CHANGELOG.

### `OpenAICompatibleDialect.addReasoning` returns what it did not send (#2115)

`addReasoning(body, model, options)` returns `List[RequestAdjustment]` instead of `Unit`
([RFC 0008](../rfcs/0008-generation-controls)). The client puts the list on `Completion.adjustments`, so a caller learns
that a requested reasoning effort was not sent instead of losing it silently. The standard implementation sends
nothing and reports a set `CompletionOptions.reasoning` as `Dropped`. A dialect that overrides it returns
`List.empty` when it sent what was asked, and a `RequestAdjustment` for each part it did not send
([Writing a provider](../guide/writing-a-provider)). Recompile such a dialect: one compiled against an earlier llm4s
keeps the old method, which the client no longer calls, so its reasoning fields would silently not be sent.

### One proxy and TLS setting for every provider client (#2273)

Every provider client now honours `llm4s.http` ([Corporate networks](../getting-started/configuration#corporate-networks-a-proxy-and-a-custom-ca)):

- Core's `reference.conf` binds `llm4s.http.proxy.url` to `HTTPS_PROXY` and `nonProxyHosts` to `NO_PROXY`. An
  application whose environment sets `HTTPS_PROXY` now sends provider traffic through that proxy (loopback hosts stay
  direct). To keep the old direct route, set `llm4s.http.proxy.url = ""`, or list the hosts in `NO_PROXY`. A value
  that is not `http://host:port` fails the load, naming `llm4s.http.proxy.url`.
- `http` is a built-in key of a provider section, like `timeouts`: a provider declaring an extra named `http` must
  rename it.
- `BedrockConfig` and `WatsonXConfig` (public case classes) gain a last field, `network`, with a default: a pattern
  match on all their fields gains one `_`. `EmbeddingProviderConfig` and `OpenAICompatibleClient.Settings` gain
  `network` too; their previous `apply` signatures remain.
- `Llm4sHttpClient.create()` is unchanged and ignores `llm4s.http`; `Llm4sHttpClient.create(network)` takes the settings
  `Llm4sConfig.httpNetwork()` loads.

### The embeddings sample's settings left `Llm4sConfig` (#2265)

`Llm4sConfig` exposed settings only the embeddings sample (`EmbeddingExample` in `modules/samples`) read, and
`llm4s-core`'s `reference.conf` bound their variables. Under the single-consumer rule they moved to the samples with their
keys ([#2265](https://github.com/llm4s/llm4s/issues/2265)), and the duplicate entry points went:

| Removed from `llm4s-core` | Now |
|---|---|
| `Llm4sConfig.embeddingsChunking()`, `loadEmbeddingsChunking()`, `EmbeddingsChunkingSettings` | `EmbeddingSampleConfig.chunking()`, `EmbeddingChunkingSettings` (samples) |
| `Llm4sConfig.embeddingsInputs()`, `loadEmbeddingsInputs()`, `EmbeddingsInputSettings` | `EmbeddingSampleConfig.inputs()`, `EmbeddingInputSettings` (samples) |
| `Llm4sConfig.embeddingsUi()`, `loadEmbeddingsUiSettings()`, `EmbeddingsUiSettings` | `EmbeddingSampleConfig.ui()`, `EmbeddingUiSettings` (samples) |
| `Llm4sConfig.experimentalStubsEnabled` | `EmbeddingSampleConfig.experimentalStubs()` (samples) |
| `Llm4sConfig.localEmbeddingModels()` | `EmbeddingSampleConfig.localModels()` (samples); or build a `LocalEmbeddingModels` |
| `Llm4sConfig.loadTextEmbeddingModel()` | `Llm4sConfig.textEmbeddingModel()`, unchanged |
| `ConfigKeys.EMBEDDING_INPUT_PATH`, `EMBEDDING_QUERY`, `CHUNK_SIZE`, `CHUNK_OVERLAP`, `CHUNKING_ENABLED` | the string literals |
| `llm4s.embeddings.{inputPath,inputPaths,query,chunking,ui,experimentalStubs,localModels}` | `llm4s.samples.embeddings.*` in the samples' `application.conf` |

`EmbeddingSampleConfig` and its types are in `org.llm4s.samples.embeddingsupport`, which is not published: an application
that used these settings defines its own, or copies the sample's. `LocalEmbeddingModels` stays in `llm4s-core`, as
`FileEmbeddingConfig` (`llm4s-rag`) and `ModelSelector` take it, so build one directly:

```scala
import org.llm4s.llmconnect.config.LocalEmbeddingModels

val localModels = LocalEmbeddingModels(imageModel = "openclip-vit-b32", audioModel = "wav2vec2-base", videoModel = "timesformer-base")
```

The environment variables (`EMBEDDING_INPUT_PATH`, `EMBEDDING_INPUT_PATHS`, `EMBEDDING_QUERY`, `CHUNK_SIZE`,
`CHUNK_OVERLAP`, `CHUNKING_ENABLED`, `MAX_ROWS_PER_FILE`, `TOP_DIMS_PER_ROW`, `GLOBAL_TOPK`, `SHOW_GLOBAL_TOP`, `COLOR`,
`TABLE_WIDTH`, `ENABLE_EXPERIMENTAL_STUBS`, `LLM4S_EMBEDDINGS_LOCAL_{IMAGE,AUDIO,VIDEO}_MODEL`) still drive the sample,
which binds them itself; no llm4s library module reads them any more. Their defaults are unchanged. One difference: a
value that is set but cannot be read (`CHUNK_SIZE=abc`) is now a `ConfigurationError` instead of silently falling back to
the default.

### The Spring Boot starter's refused `completeAsync` fails with an `LlmException` (#2276)

`LLM4STemplate.completeAsync` now delegates to `JLlmClient.completeAsync`
([#2276](https://github.com/llm4s/llm4s/issues/2276)), whose futures fail only with an `LlmException`. When the
`llm4sTaskExecutor` refuses a call (its queue full, or shut down), the future's cause is an `LlmException` of kind
`OTHER` whose own cause is the `RejectedExecutionException`; it was the `RejectedExecutionException` itself. Code that
tests `e.getCause() instanceof RejectedExecutionException` tests `e.getCause().getCause()` instead.

### Postgres stores take a `javax.sql.DataSource` (#2300)

The Postgres stores accept a connection pool the application already has, of any kind
([#2300](https://github.com/llm4s/llm4s/issues/2300)), so the parameters that took HikariCP's concrete pool are widened
to `javax.sql.DataSource`:

| Before | Now |
|---|---|
| `PgVectorStore.create(dataSource: HikariDataSource, tableName, ownsDataSource)` | `PgVectorStore.create(dataSource: DataSource, tableName, ownsDataSource)` |
| `PgVectorStore(dataSource: HikariDataSource, tableName)` | `PgVectorStore(dataSource: DataSource, tableName)` |
| `PgKeywordIndex(dataSource: HikariDataSource, tableName[, language])` | `PgKeywordIndex(dataSource: DataSource, tableName[, language])` |
| - | `HybridSearcher.pgvectorShared(dataSource, vectorTableName, keywordTableName[, defaultStrategy])` |
| - | `PgSearchIndex.fromDataSource(dataSource, vectorTableName)` |
| - | `PostgresMemoryStore.fromDataSource(dataSource, tableName, embeddingService)` |

A `HikariDataSource` is a `DataSource`, so code that passes one compiles unchanged; code compiled against 0.5.x must be
recompiled. A store built over a `DataSource` never closes it unless `PgVectorStore.create` is given
`ownsDataSource = true`, and then only if the `DataSource` is `AutoCloseable`. `PgSearchIndex.close()` used to close
its pool unconditionally; it now closes only a pool it created from a `PgConfig`. A store over `PGSimpleDataSource` or
another non-pooling `DataSource` opens a connection per call. See
[Sharing your application's connection pool](../guide/vector-store.html#sharing-your-applications-connection-pool).

### `Completion.created` is an `Instant` (#2262)

A point in time in the public API is a `java.time.Instant` ([#2262](https://github.com/llm4s/llm4s/issues/2262)), and
`Completion.created` was the last raw timestamp: it was a `Long` of epoch seconds. `Completion(created = ...)`,
`withCreated` and `StreamingAccumulator.toCompletion(created)` now take an `Instant`, and `completion.created` returns
one. Providers that report a creation time (the OpenAI-format `created` field) still send epoch seconds on the wire; the
client converts it, and a reply without one gives `Instant.EPOCH`, as `0L` did before. Providers that report none stamp
the time the reply arrived, as before.

```scala
import java.time.Instant

val completion = Completion(id = "1", created = Instant.ofEpochSecond(1700000000L), content = "hi", model = "m",
  message = AssistantMessage("hi"))                     // was created = 1700000000L
val seconds: Long = completion.created.getEpochSecond   // where code needs the old number
```

Test fixtures that passed `0L` pass `Instant.EPOCH`.

### Unused speculative core types removed (#2345)

Three groups of public types in `llm4s-core` had no user in any llm4s module and are deleted before the 1.0
baseline ([#2345](https://github.com/llm4s/llm4s/issues/2345)), rather than frozen into it:

- `org.llm4s.types.typeclass.Encoder` and its implicit `EncoderOps`: encode to JSON with upickle directly
  (`upickle.default.writeJs(value)` given a `ReadWriter`), or keep a one-method trait of your own.
- `org.llm4s.types.typeclass.LLMCapable` (with `LLMCapableOps` and its `String` / `Conversation` instances): build a
  `Conversation` with `Conversation.create(UserMessage(text))` and read a reply with `completion.content`.
- `org.llm4s.llmconnect.model.CompletionChunk` and `ChunkDelta`: no client ever produced them. Streaming delivers
  `StreamedChunk` (`content`, `toolCall`, `finishReason`, `thinkingDelta`) to `streamComplete`'s `onChunk`.

### Tier hygiene before the baseline (#2358)

Public types of frozen modules that would otherwise have been frozen by mistake at the MiMa baseline
([#2358](https://github.com/llm4s/llm4s/issues/2358)) are now marked or placed to match their tier:

- `org.llm4s.trace.AnsiColors` is `private[llm4s]`: only `ConsoleTracing` and a sample used it. Define the escape codes
  you need yourself (`"\u001b[32m"` is green, `"\u001b[0m"` resets), or use a library such as fansi.
- `org.llm4s.core.safety.UsingOps` moved from `llm4s-core` to `llm4s-agent-tools`, its only consumer, and
  `org.llm4s.llmconnect.utils.ModelSelector` from `llm4s-core` to `llm4s-rag`. Packages are unchanged, so imports stay
  as they are; add the dependency if you used one without it. `scala.util.Using.resource` does what `UsingOps.using`
  does without either.
- The `org.llm4s.assistant` types (`AssistantAgent`, `Command`, `SessionManager`, `SessionState`, `SessionInfo`,
  `SessionSummary`) are `@Experimental`, matching their Beta tier in [1.0 Scope](v1-scope): MiMa does not freeze
  them, and they can change in a minor release with a migration note. Nothing about them changed in this release.

### The file tools' default `blockedPaths` covers each platform (#1408)

`FileConfig()`'s `blockedPaths` defaults to the new `FileConfig.DefaultBlockedPaths`, the system and credential
locations of the platform the JVM runs on ([#1408](https://github.com/llm4s/llm4s/issues/1408), finding F6). It was
`/etc`, `/var`, `/sys`, `/proc` and `/dev` everywhere, which on Windows blocked nothing. Now:

- Linux and other POSIX systems add `/boot`, `/root` and `/run`; macOS adds `/private/etc`, `/private/var` and
  `/Library/Keychains`; Windows has `C:\Windows`, `C:\ProgramData\Microsoft\Crypto` and `C:\ProgramData\ssh` instead
  of the POSIX entries.
- Every platform blocks the credential files under the user's home (`.ssh`, `.gnupg`, `.aws`, `.azure`,
  `.config/gcloud`, `.kube`, `.docker`, `.config/gh`, `.netrc`, `.git-credentials`, `.pgpass`, `.password-store`,
  `.npmrc`, `.pypirc`, `.m2/settings.xml`, `.m2/settings-security.xml`, `.ivy2/.credentials`, `.sbt/.credentials`),
  plus the GNOME keyring on Linux, `~/Library/Keychains` on macOS, and on Windows `_netrc`, `NTUSER.DAT` and the
  Credential Manager, DPAPI, gcloud, GnuPG, GitHub CLI and PostgreSQL stores under `AppData`.
- A blocked entry is matched without regard to case, so `/ETC/hosts` is blocked by `/etc`.

An agent that read one of these through the default config is now refused. To keep reading it, pass your own list,
best built from the default: `FileConfig(blockedPaths = FileConfig.DefaultBlockedPaths.filterNot(_.endsWith(".npmrc")))`.
The tool descriptions write the user's home directory as `~`. A config that sets `blockedPaths` itself is unchanged,
apart from the case-insensitive match.

### Console tracing prints no content by default (#2311)

The default tracing mode is still `console`, but `ConsoleTracing` now prints metadata only
([#2311](https://github.com/llm4s/llm4s/issues/2311)): event types, timestamps, models, ids, tool names, success
flags, durations, token counts and costs. The query of `AgentInitialized`, the content of `CompletionReceived`, the
input and output of `ToolExecuted`, the message and context of an error and an image generation's error message print
as `[withheld: N chars]`, and a `CustomEvent` shows its field names instead of its data. An application that built
tracing from `Llm4sConfig.tracing()` and never set `TRACING_MODE` no longer sends prompts and answers into whatever
collects its stdout.

No code has to change. To see content again while developing, opt in:

```hocon
llm4s.tracing.console.captureContent = true   # or LLM4S_TRACING_CONSOLE_CAPTURE_CONTENT=true
```

or construct the tracer with `new ConsoleTracing(captureContent = true)`; `new ConsoleTracing()` prints metadata
only. A `captureContent` value other than `true` or `false` is a `ConfigurationError` from `Tracing.fromSettings`
(`Tracing.create` logs it and falls back to `NoOpTracing`). A hand-built `TracingSettings(TracingMode.Console)` has
no extras, so it gets the safe default.

### OpenTelemetry spans follow the GenAI conventions; content is opt-in (#2275)

`llm4s-observability-otel` names and annotates model calls by the OpenTelemetry GenAI semantic conventions, version
`1.37.0` ([#2275](https://github.com/llm4s/llm4s/issues/2275); tables in
[Monitoring](../guide/observability/index.html#genai-semantic-conventions)). Dashboards, alerts and queries that select
the old names need updating:

| Before | Now |
|---|---|
| span `LLM Completion` | `chat <model>` (`CLIENT`) |
| span `Embedding Usage - <operation>` | `embeddings <model>` (`CLIENT`); the label stays in `operation` |
| span `Error` of a `TracingLLMClient` or `EmbeddingClient` call | `chat <model>` / `embeddings <model>` with `error.type` and status `ERROR` |
| attribute `completion_id` | `gen_ai.response.id` |
| attribute `tool_calls` | `llm4s.model_call.tool_calls` |
| attribute `content` (first 1000 characters of the answer) | `gen_ai.output.messages`, only with `captureContent` |
| attribute `input` of `LLM4S Agent Run`, `tool.input` / `tool.output` of `execute_tool` | only with `captureContent` |

Agent spans keep their names (`invoke_agent`, `chat`, `execute_tool`) and gain `gen_ai.provider.name`.

**No span carries message content by default.** To record it again, redacted, set:

```hocon
llm4s.tracing.opentelemetry.captureContent = true   # or OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT=true
```

or pass `OpenTelemetryConfig(captureContent = true)`. Plain and streamed completions are traced by wrapping the client,
`TracingLLMClient(client, tracing, providerConfig)`; an `EmbeddingClient` built by `EmbeddingClient.from` reports its
provider. `TraceEvent.CompletionReceived`, `ErrorOccurred`, `EmbeddingUsageRecorded` and `AgentRunEnded` gained fields
(provider, duration and the call's details), so a positional pattern on one of them no longer compiles: match on the
type and read the fields (`case e: TraceEvent.CompletionReceived => e.model`). Their `provider` is an
`Option[ProviderId]`, and `withProvider` takes a `ProviderId` (`.withProvider(ProviderId("openai"))`); `toJson` still
writes the plain id. `LLMClient` and `EmbeddingClient` gained `providerId: Option[ProviderId]` (default `None`), which a
client that wraps another should forward, as did the agent's `ModelStep`; `AgentEvents.ModelCallCompleted` carries it
as `provider: Option[ProviderId]`, stored as the plain id, so events stored before it still read.
`EmbeddingClient` takes `providerId` as its fourth parameter, before the `ModelRegistryService`: Java and Kotlin
callers of the constructor pass `Option.empty()` there.

### Only remote calls are `CLIENT` spans (#2531)

`llm4s-observability-otel` gives an event the `CLIENT` span kind only when it records a request to a remote service:
a completion, an embedding or image generation call, an agent's model call, or the failure of one of them (an
`ErrorOccurred` with an `operation`). Every other event, and any event added later, is `INTERNAL`, OpenTelemetry's
default ([#2531](https://github.com/llm4s/llm4s/issues/2531)). A dashboard or query that selects `CLIENT` spans no
longer sees:

| Span | Was | Now |
|---|---|---|
| `Error` (an `ErrorOccurred` with no operation) | `CLIENT` | `INTERNAL` |
| `ToolExecuted`'s `execute_tool <tool>` | `CLIENT` | `INTERNAL`, as an agent's `execute_tool` span already was |
| `CostRecorded`, `CacheHit`, `CacheMiss`, `RAGOperationCompleted` | `CLIENT` | `INTERNAL` |
| a `CustomEvent` with no `span` | `CLIENT` | `INTERNAL`, as a placed one already was |

No API changes.

### `ServiceError` extractor and `isRecoverableStatus` (#2266)

`ServiceError`'s extractor binds all five fields, `retryAfter` last
([#2266](https://github.com/llm4s/llm4s/issues/2266)); the hand-written four-field `unapply`, which left out
`retryAfter`, is gone. A positional pattern needs a fifth binder:

```scala
case ServiceError(message, status, provider, requestId)    => ... // before
case ServiceError(message, status, provider, requestId, _) => ... // after
case e: ServiceError                                       => ... // or match on the type
```

`isRecoverableStatus` is now a method of `ServiceError` rather than of the public `implicit class
ServiceError.ServiceErrorOps`, which an `import ServiceError.*` would have pulled in. `error.isRecoverableStatus`
compiles unchanged; delete any `import ServiceError.ServiceErrorOps`.

Not a source change, but visible: `HttpResponse#ensureSuccess` now puts the response's request id
(`x-request-id`, `request-id`, `x-amzn-requestid` or `apim-request-id`) and its `Retry-After` delay on the
`ServiceError`, so its message gains `(Request ID ...)` when the provider sent one, and `Result#mapServiceError`
keeps both fields. A retry now waits the delay the provider asked for rather than the default backoff.

### `LLMClientPekko.streamComplete` materializes `Future[Done]` (#1848)

`streamComplete` is now a `Source[StreamedChunk, Future[Done]]`, like every `AgentPekko` stream
([#1848](https://github.com/llm4s/llm4s/issues/1848)). The future completes once the provider call has returned, after a
cancellation too, so a caller can wait until nothing is left running. Code that only runs the stream is unchanged; code
that names the type changes it, and code that wanted the old value drops it:

```scala
val chunks: Source[StreamedChunk, NotUsed] = client.streamComplete(conversation).mapMaterializedValue(_ => NotUsed)
```

### The OpenAI-compatible provider configs are growth-safe (#2264)

`DeepSeekConfig`, `ZaiConfig`, `XaiConfig`, `MistralConfig` and `CohereConfig` (`llm4s-openai-compatible`) follow
the growth-prone pattern, as `OpenAIConfig` and `OpenAICompatibleConfig` beside them already did
([#2264](https://github.com/llm4s/llm4s/issues/2264)). The classes are `final`, `new` and `.copy` are private, and:

- the companion `apply` keeps its argument order - `apiKey`, `model`, `baseUrl`, `contextWindow`, `reserveCompletion`,
  `timeouts` - and every field after `model` now has a default: `DEFAULT_BASE_URL`, the new `DEFAULT_CONTEXT_WINDOW`
  and `DEFAULT_RESERVE_COMPLETION`, and `ProviderTimeouts.default`. Positional and named calls compile unchanged;
- `apply(apiKey, model)` is the entry point for Java and Kotlin, which do not see Scala default arguments;
- `withApiKey`, `withBaseUrl`, `withContextWindow` and `withReserveCompletion` join `withModel` and `withTimeouts`.

`fromValues`, which validates and resolves the context window from the model, is still the preferred way in.

```scala
import org.llm4s.llmconnect.config.DeepSeekConfig

val config = DeepSeekConfig("sk-...", "deepseek-chat").withContextWindow(64000) // was .copy(contextWindow = 64000)
```

### `MemoryManagerConfig` is growth-safe (#1847)

`MemoryManagerConfig` follows the growth-prone pattern ([#1847](https://github.com/llm4s/llm4s/issues/1847)): build
it with `MemoryManagerConfig(...)` (every option keeps its default) or a preset, and change it with
`withAutoExtractEntities`, `withDefaultImportance`, `withContextTokenBudget` and `withConsolidationConfig`. `new` and
`.copy` are private:

```scala
val config = MemoryManagerConfig.default.withContextTokenBudget(500) // was .copy(contextTokenBudget = 500)
```

`toString` is now the case-class form, `MemoryManagerConfig(false,0.5,2000,ConsolidationConfig(50,false))`.

### Core config types are growth-safe (#2346)

`ToolExecutionConfig`, `LlmClientOptions`, `EmbeddingProviderConfig`, `EmbeddingModelConfig`, `LocalEmbeddingModels`,
`ModelRegistryConfig` and `TracingSettings` (`llm4s-core`) follow the growth-prone pattern
([#2346](https://github.com/llm4s/llm4s/issues/2346)), so a new field - `ToolExecutionConfig` has an obvious next one -
no longer breaks Java and Kotlin callers or compiled code. Each is a `final case class` whose constructor and `copy`
are private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, and the same defaults, still
  compiles. `new X(...)` does not; drop the `new`. `ToolExecutionConfig.default` is new, beside
  `LlmClientOptions.default` and `ModelRegistryConfig.default`.
- **Replace `.copy` with a setter**, one per field:

  | Type | Setters |
  |---|---|
  | `ToolExecutionConfig` | `withTimeout`, `withRetryPolicy` (the value or an `Option`) |
  | `LlmClientOptions` | `withMetrics`, `withExchangeLogging` |
  | `EmbeddingProviderConfig` | `withBaseUrl`, `withModel`, `withApiKey`, `withTimeouts` |
  | `EmbeddingModelConfig` | `withName`, `withDimensions` |
  | `LocalEmbeddingModels` | `withImageModel`, `withAudioModel`, `withVideoModel` |
  | `ModelRegistryConfig` | `withResourcePath`, `withFilePath`, `withUrl` (the value or an `Option`) |
  | `TracingSettings` | `withMode`, `withExtras` |

  ```scala
  val cfg  = embeddingConfig.withModel("text-embedding-3-large")   // was .copy(model = ...)
  val exec = ToolExecutionConfig.default.withTimeout(5.seconds)    // was .copy(timeout = Some(5.seconds))
  val tr   = settings.withMode(TracingMode.NoOp)                    // was settings.copy(mode = TracingMode.NoOp)
  ```

- **Java and Kotlin**: call the companion's `apply` (`EmbeddingModelConfig.apply("m", 1536)`) instead of the
  constructor, and chain the setters.
- `ToolExecutionConfig`, `EmbeddingModelConfig` and `TracingSettings` are now `final`.

Pattern matching (`case EmbeddingModelConfig(name, dims)`) and the fields are unchanged.

### Core data types are growth-safe (#2356)

`HttpResponse`, `HttpRawResponse`, `StreamingHttpResponse`, `JsonHttpResponse` (`org.llm4s.http`), `EmbeddingResponse`,
`EmbeddingUsage`, `UsageSummary`, `ModelUsage` (`org.llm4s.llmconnect.model`), `ModelPricing` (`org.llm4s.model`),
`ProviderExchange` (`org.llm4s.llmconnect`) and `DiscoveredModel` (`org.llm4s.config`) follow the growth-prone pattern
([#2356](https://github.com/llm4s/llm4s/issues/2356)). Each is a `final case class` whose constructor and `copy` are
private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, and the same defaults, still
  compiles - `HttpResponse(200, body)` included. `new X(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field:

  | Type | Setters |
  |---|---|
  | `HttpResponse`, `HttpRawResponse`, `StreamingHttpResponse`, `JsonHttpResponse` | `withStatusCode`, `withBody`, `withHeaders` |
  | `EmbeddingResponse` | `withEmbeddings`, `withMetadata`, `withModality`, `withDim`, `withUsage` (the last three take the value or an `Option`) |
  | `EmbeddingUsage` | `withPromptTokens`, `withTotalTokens` |
  | `ModelPricing` | `with<Field>` for each price, e.g. `withInputCostPerToken` (the value or an `Option`) |
  | `UsageSummary` | `withRequestCount`, `withInputTokens`, `withOutputTokens`, `withThinkingTokens`, `withTotalCost`, `withByModel` |
  | `ModelUsage` | `withRequestCount`, `withInputTokens`, `withOutputTokens`, `withThinkingTokens`, `withTotalCost` |
  | `ProviderExchange` | `with<Field>` for each field, e.g. `withOutcome`; an `Option` field's setter takes the value or an `Option` |
  | `DiscoveredModel` | `withName`, `withProvider`, `withMetadata` |

  ```scala
  val throttled = response.withStatusCode(429)                    // was response.copy(statusCode = 429)
  val sized     = embeddings.withDim(1536)                        // was .copy(dim = Some(1536))
  val priced    = pricing.withInputCostPerToken(2.5e-6)           // was .copy(inputCostPerToken = Some(2.5e-6))
  ```

- **JSON is unchanged.** `ModelPricing`, `UsageSummary` and `ModelUsage` keep their `ReadWriter`s and their
  constructor defaults, so they write the same bytes and a document that omits a field still reads.
- **`UsageSummary.bigDecimalRw` is private.** It was a public implicit `ReadWriter[BigDecimal]` that
  `import UsageSummary._` (or `.*`) pulled into scope, changing how every `BigDecimal` in that file serialised.
  `UsageSummary.rw` still uses it, so `UsageSummary` JSON is the same; code that relied on the import for its own
  `BigDecimal` fields gets upickle's default codec, which also writes a string. Define your own given if you need
  `UsageSummary`'s lenient reading (a number, a string, anything else as zero).
- **Java and Kotlin**: call the companion's `apply` (`HttpResponse.apply(200, "ok", headers)`) instead of the
  constructor, and chain the setters.
- `HttpResponse`, `HttpRawResponse`, `StreamingHttpResponse`, `EmbeddingUsage`, `ModelPricing`, `UsageSummary` and
  `ModelUsage` are now `final`.

Pattern matching (`case HttpResponse(status, body, headers)`) and the fields are unchanged.

### `llmconnect.model` types are growth-safe (#2378)

`Conversation`, `SystemMessage`, `ToolCall`, `ResponseFormat.JsonSchema`, `EmbeddingVector`,
`MultimediaEmbeddingRequest`, `ImageInput`, `AudioInput`, `VideoInput` and `RawMediaInput`
(`org.llm4s.llmconnect.model`) follow the growth-prone pattern ([#2378](https://github.com/llm4s/llm4s/issues/2378)).
Each is a `final case class` whose constructor and `copy` are private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, and the same defaults, still
  compiles. `new X(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field:

  | Type | Setters |
  |---|---|
  | `Conversation` | `withMessages` (beside `addMessage` and `addMessages`) |
  | `SystemMessage` | `withContent` |
  | `ToolCall` | `withId`, `withName`, `withArguments` |
  | `ResponseFormat.JsonSchema` | `withSchema`, `withName`, `withStrict` |
  | `EmbeddingVector` | `withId`, `withModality`, `withModel`, `withDim`, `withValues`, `withMeta` |
  | `MultimediaEmbeddingRequest` | `withInputs`, `withModel`, `withModality`, `withMeta` |
  | `ImageInput` | `withWidth`, `withHeight`, `withChannels`, `withData` |
  | `AudioInput` | `withSamples`, `withSampleRate` |
  | `VideoInput` | `withFrameWidth`, `withFrameHeight`, `withChannels`, `withFps`, `withFrames` |
  | `RawMediaInput` | `withData`, `withMime` |

  ```scala
  val pruned = conversation.withMessages(kept)                   // was conversation.copy(messages = kept)
  val edited = call.withArguments(ujson.Obj("city" -> "Lyon"))   // was call.copy(arguments = ...)
  val loose  = format.withStrict(false)                          // was format.copy(strict = false)
  ```

- **JSON is unchanged.** `Conversation`, `SystemMessage` and `ToolCall` keep their `ReadWriter`s and write the same
  bytes.
- **Java and Kotlin**: call the companion's `apply` (`ToolCall.apply("id", "name", args)`) instead of the
  constructor, and chain the setters.
- `Conversation`, `ToolCall` and `ResponseFormat.JsonSchema` are now `final`.

Pattern matching (`case ToolCall(id, name, args)`) and the fields are unchanged.

### `TraceEvent` cases are growth-safe (#2377)

The thirteen `TraceEvent` cases - `AgentInitialized`, `CompletionReceived`, `ToolExecuted`, `ErrorOccurred`,
`TokenUsageRecorded`, `AgentRunEnded`, `CustomEvent`, `EmbeddingUsageRecorded`, `CostRecorded`,
`RAGOperationCompleted`, `ImageGenerationCompleted`, `CacheHit` and `CacheMiss` (`org.llm4s.trace`) - follow the
growth-prone pattern ([#2377](https://github.com/llm4s/llm4s/issues/2377)). Each is a `final case class` whose
constructor and `copy` are private:

- **Construction is unchanged**: `TraceEvent.X(...)` with the same arguments, positional or named, and the same
  defaults (`timestamp = Instant.now()`, `span = None`, and each optional metric `None`) still compiles. `new X(...)`
  does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field, named `with<Field>` - `withTimestamp` on every case,
  `withSpan` on the four that carry one, `withName` on `CustomEvent`, `withStatus`/`withMessages`/`withUsage` on
  `AgentRunEnded`, and so on. An `Option` field's setter takes the value or an `Option` (`withSpan(span)`,
  `withSpan(None)`).

  ```scala
  val renamed = event.withName("prefix_" + event.name)   // was event.copy(name = "prefix_" + event.name)
  val placed  = event.withSpan(span)                     // was event.copy(span = Some(span))
  ```

- **Java and Kotlin**: call the companion's `apply` instead of the constructor, and chain the setters.
- The thirteen cases are now `final`. Pattern matching (`case TraceEvent.CacheHit(similarity, threshold, ts)`), the
  fields and `toJson` are unchanged.

### `AssistantError` cases are growth-safe (#2383)

The seven `AssistantError` cases - `IOError`, `EOFError`, `DisplayError`, `SessionError`, `FileError`,
`SerializationError` and `CommandParseError` (`org.llm4s.error`) - follow the growth-prone pattern
([#2383](https://github.com/llm4s/llm4s/issues/2383)), as the `LLMError` cases already did. Each is a
`final case class` whose constructor and `copy` are private:

- **Construction is unchanged**: `AssistantError.X(...)` with the same arguments, positional or named, and the same
  defaults (`cause = None`, `llmCause = None`, `EOFError`'s `operation = "read"`) still compiles, as do the smart
  constructors (`AssistantError.fileReadFailed`, `sessionNotFound`, ...). `new X(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field: `withMessage` and `withCause` on every case that has the field,
  `withOperation`, `withSessionId`, `withLlmCause`, `withPath`, `withDataType`, `withDisplayType`, `withCommand` and
  `withParseOperation`. An `Option` field's setter takes the value or an `Option` (`withCause(ex)`, `withCause(None)`).

  ```scala
  val withLlm = err.withLlmCause(llmError)   // was err.copy(llmCause = Some(llmError))
  ```

- **Java and Kotlin**: call the companion's `apply` instead of the constructor, and chain the setters.
- Pattern matching (`case AssistantError.FileError(message, path, operation, cause)`), the fields, `context` and
  `formatted` are unchanged.

### `RerankProviderConfig` is growth-safe (#2478)

`RerankProviderConfig` (`org.llm4s.reranker`, `llm4s-rag`) gains `apiKeyProvider`, the key a reranker asks for per
request (a rotated `apiKeyFile`), and follows the growth-prone pattern
([#2478](https://github.com/llm4s/llm4s/issues/2478)): its constructor and `copy` are private.

- **Construction is unchanged**: `RerankProviderConfig(baseUrl, apiKey, model)`, positional or named, still compiles.
  `new RerankProviderConfig(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**: `withBaseUrl`, `withApiKey`, `withModel` and `withApiKeyProvider` (the provider or
  an `Option`).

  ```scala
  val other = config.withModel("rerank-v3.5")   // was config.copy(model = "rerank-v3.5")
  ```

- Pattern matching and the fields are unchanged; pattern matching now binds four fields.

### Provider SPI, caching, streaming and exchange-sink types are growth-safe (#2379)

`JsonlProviderExchangeSink` (`org.llm4s.llmconnect`), `CacheEntry`, `CacheStats` (`org.llm4s.llmconnect.caching`),
`OpenAICompatibleClient.Settings` (`llm4s-openai-compatible`), `SSEParser.SSEEvent` (`org.llm4s.llmconnect.streaming`)
and `EmbeddingProviderSection`, `ProviderConfigKey`, `ProviderModuleReport`, `ProviderDiscoveryFailure`,
`ProviderIdCollision` and `ProviderRegistryReport` (`org.llm4s.llmconnect.spi`) follow the growth-prone pattern
([#2379](https://github.com/llm4s/llm4s/issues/2379)). Each is a `final case class` whose constructor and `copy` are
private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, and the same defaults, still
  compiles. `new X(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field (an `Option` field's setter takes the value or an `Option`):

  | Type | Setters |
  |---|---|
  | `JsonlProviderExchangeSink` | `withPath` |
  | `CacheEntry` | `withEmbedding`, `withResponse`, `withTimestamp`, `withOptions` |
  | `CacheStats` | `withSize`, `withHits`, `withMisses`, `withTotalRequests`, `withHitRatePercent` |
  | `OpenAICompatibleClient.Settings` | `withProviderName`, `withDisplayName`, `withModel`, `withBaseUrl`, `withCredential`, `withContextWindow`, `withReserveCompletion`, `withTimeouts` |
  | `SSEEvent` | `withData`, `withEvent`, `withId`, `withRetry` |
  | `EmbeddingProviderSection` | `withApiKey`, `withBaseUrl`, `withModel`, `withTimeouts` |
  | `ProviderConfigKey` | `withName`, `withDescription`, `withRequired`, `withDefault`, `withEnv`, `withDeprecatedAliases` |
  | `ProviderModuleReport` | `withModuleClass`, `withProviderIds`, `withSource`, `withEmbeddingProviderIds` |
  | `ProviderDiscoveryFailure` | `withDetail`, `withFailure` |
  | `ProviderIdCollision` | `withKind`, `withId`, `withKeptModule`, `withDroppedModule` |
  | `ProviderRegistryReport` | `withDiscovered`, `withModules`, `withFailures`, `withCollisions` |

  ```scala
  // was ProviderConfigKey.optional(...).copy(env = Some("AWS_PROFILE"))
  val key = ProviderConfigKey.optional("profile", "the AWS profile").withEnv("AWS_PROFILE")
  // was section.copy(apiKey = resolvedKey)
  val keyed = section.withApiKey(resolvedKey)
  // was settings.copy(baseUrl = "https://eu.acme.example/v1")
  val moved = settings.withBaseUrl("https://eu.acme.example/v1")
  ```

- **Java and Kotlin**: call the companion's `apply` (`CacheStats.apply(size, hits, misses, total, rate)`) instead of
  the constructor, and chain the setters.
- `CacheEntry`, `CacheStats` and `SSEEvent` are now `final`.

Pattern matching (`case ProviderConfigKey(name, description, required, default, env, aliases)`) and the fields are
unchanged.

### Tool schemas and tool-call types are growth-safe (#2381)

`StringSchema`, `NumberSchema`, `IntegerSchema`, `BooleanSchema`, `ArraySchema`, `ObjectSchema`, `PropertyDefinition`,
`SafeParameterExtractor`, `ToolCallRequest` and `ToolFunction` (`org.llm4s.toolapi`) follow the growth-prone pattern
([#2381](https://github.com/llm4s/llm4s/issues/2381)). Each is a `final case class` whose constructor and `copy` are
private:

- **Construction is unchanged**: `Schema.string(...)`, `Schema.property(...)`, `ToolBuilder` and `X(...)` with the same
  arguments and defaults still compile. `new X(...)` does not; drop the `new` (`ToolFunction[R](...)`).
- **Replace `.copy` with a setter**:

  | Type | Setters |
  |---|---|
  | `StringSchema` | `withDescription`, `withEnum`, `withMinLength`, `withMaxLength` (the last three also take an `Option`), `withLengthConstraints` |
  | `NumberSchema`, `IntegerSchema` | `withDescription`, `withMinimum`, `withMaximum`, `withExclusiveMinimum`, `withExclusiveMaximum`, `withMultipleOf` (each also takes an `Option`), `withRange`, `withExclusiveRange`; `NumberSchema` also `withIsInteger` |
  | `BooleanSchema` | `withDescription` |
  | `ArraySchema` | `withDescription`, `withItemSchema`, `withMinItems`, `withMaxItems` (the value or an `Option`), `withSizeConstraints`, `withUniqueItems` |
  | `ObjectSchema` | `withDescription`, `withProperties`, `withProperty`, `withAdditionalProperties` |
  | `PropertyDefinition` | `withName`, `withSchema`, `withRequired` |
  | `SafeParameterExtractor` | `withParams`, `withCall` (the call or an `Option`) |
  | `ToolCallRequest` | `withFunctionName`, `withArguments` |
  | `ToolFunction` | `withName`, `withDescription`, `withSchema`, `withHandler` |

  ```scala
  val renamed = tool.withName("search_v2")                       // was tool.copy(name = "search_v2")
  val open    = schema.withAdditionalProperties(true)            // was schema.copy(additionalProperties = true)
  ```

- **Java and Kotlin**: `new SafeParameterExtractor(params)` becomes `SafeParameterExtractor.apply(params)`; the other
  types are built through their companion's `apply` or `Schema`.

Pattern matching (`case ToolCallRequest(name, args)`) and the fields are unchanged.

### Config, model and util types are growth-safe (#2384)

`Llm4sConfig.TextEmbeddingModelSettings`, `ProvidersConfigModel.ProvidersConfig` (`org.llm4s.config`),
`TransformationResult` (`org.llm4s.model`) and `SecretPatterns.SecretMatch` (now in `llm4s-agent`, without setters:
[#2824](#secretpatterns-is-in-llm4s-agent-2824)) follow the growth-prone
pattern ([#2384](https://github.com/llm4s/llm4s/issues/2384)). Each is a `final case class` whose constructor and `copy`
are private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, still compiles;
  `TransformationResult(options, messages)` keeps `requiresFakeStreaming = false` as its default. `new X(...)` does not;
  drop the `new`.
- **Replace `.copy` with a setter**, one per field, named `with<Field>`:

  | Type | Setters |
  |---|---|
  | `TextEmbeddingModelSettings` | `withProvider`, `withModelName`, `withDimensions` |
  | `ProvidersConfig` | `withSelectedProvider` (a `ProviderName` or an `Option`), `withNamedProviders` |
  | `TransformationResult` | `withOptions`, `withMessages`, `withRequiresFakeStreaming` |

  ```scala
  val fake = result.withRequiresFakeStreaming(true) // was result.copy(requiresFakeStreaming = true)
  ```

- **Java and Kotlin**: call the companion's `apply` (`TransformationResult.apply(options, messages, false)`) instead
  of the constructor, and chain the setters.

Pattern matching (`case SecretMatch(secretType, value, start, end)`) and the fields are unchanged.

### `OpenAIToolHelper` is internal (#2360)

`org.llm4s.toolapi.OpenAIToolHelper` (`llm4s-openai`) is now `private[llm4s]`
([#2360](https://github.com/llm4s/llm4s/issues/2360)). Its methods took and returned openai-java's
`ChatCompletionCreateParams.Builder` and `ChatCompletionTool`, which would have tied `llm4s-openai`'s 1.x
compatibility to that SDK's. `OpenAIClient` still uses it. If you built openai-java requests yourself, take the
tool definitions as JSON and hand them to the SDK:

```scala
val tools: ujson.Arr = new ToolRegistry(myTools).getOpenAITools()
```

### `ToolCallError` and `ToolParameterError` cases (#2382)

`ToolCallError.UnknownFunction` and `ToolParameterError.MissingParameter` (`org.llm4s.toolapi`) follow the growth-prone
pattern ([#2382](https://github.com/llm4s/llm4s/issues/2382)): each carries a self-correction hint that may gain company
(suggested names, say), so each is a `final case class` whose constructor and `copy` are private.

- **Construction is unchanged**: `ToolCallError.UnknownFunction("x")`, `UnknownFunction("x", Seq("a", "b"))` and
  `ToolParameterError.MissingParameter("p", "string")` still compile with the same defaults. `new X(...)` does not; drop
  the `new`.
- **Replace `.copy` with a setter**:

  | Type | Setters |
  |---|---|
  | `ToolCallError.UnknownFunction` | `withToolName`, `withAvailableTools` |
  | `ToolParameterError.MissingParameter` | `withParameterName`, `withExpectedType`, `withAvailableParameters` |

  ```scala
  val hinted = err.withAvailableTools(registry.tools.map(_.name)) // was err.copy(availableTools = ...)
  ```

Pattern matching (`case UnknownFunction(name, available)`) and the `ToolCallErrorJson` payloads are unchanged. The other
cases (`ExecutionError`, `HandlerError`, `InvalidArguments`, `Timeout`, `NullArguments`, `Cancelled`, `NullParameter`,
`TypeMismatch`, `InvalidNesting`, `MultipleErrors`) are closed records whose fields name exactly what went wrong; they
are unchanged.

### Context types are growth-safe (#2380)

`CompressionRule`, `ContextStep`, `PipelineStep`, `ManagedConversation`, `ConversationWindow`, `TokenUsageInfo`,
`StructuredInfo`, `HistoryDigest`, `TokenBreakdown`, `MessageTokenInfo` and `SemanticBlock` (`org.llm4s.context`)
follow the growth-prone pattern ([#2380](https://github.com/llm4s/llm4s/issues/2380)). Each is a `final case class`
whose constructor and `copy` are private:

- **Construction is unchanged**: `X(...)` with the same arguments, positional or named, still compiles;
  `SemanticBlock(...)` keeps `expectingAssistantResponse = false` as its default. `new X(...)` does not; drop the `new`.
- **Replace `.copy` with a setter**, one per field, named `with<Field>`:

  | Type | Setters |
  |---|---|
  | `CompressionRule` | `withName`, `withTransform` (`withApply` in 0.6.0; [note](#compressionrules-apply-field-is-transform-1281)) |
  | `ContextStep` | `withName`, `withConversation`, `withTokensBefore`, `withTokensAfter`, `withApplied` |
  | `PipelineStep` | `withName`, `withMessages`, `withTokensBefore`, `withTokensAfter`, `withApplied` |
  | `ManagedConversation` | `withConversation`, `withOriginalTokens`, `withFinalTokens`, `withSteps` |
  | `ConversationWindow` | `withConversation`, `withUsage`, `withWasTrimmed`, `withRemovedMessageCount` |
  | `TokenUsageInfo` | `withCurrentTokens`, `withBudgetLimit`, `withWithinBudget`, `withUtilizationPercentage` |
  | `StructuredInfo` | `withIdentifiers`, `withUrls`, `withConstraints`, `withStatusCodes`, `withErrors`, `withDecisions`, `withToolUsage`, `withOutcomes` |
  | `HistoryDigest` | `withBlockId`, `withBlockType`, `withContent`, `withOriginalTokens` |
  | `TokenBreakdown` | `withTotalTokens`, `withMessages`, `withOverhead` |
  | `MessageTokenInfo` | `withRole`, `withTokens`, `withPreview` |
  | `SemanticBlock` | `withId`, `withMessages`, `withBlockType`, `withExpectingAssistantResponse` |

  ```scala
  val over = usage.withWithinBudget(false) // was usage.copy(withinBudget = false)
  ```

- **Java and Kotlin**: call the companion's `apply` (`TokenUsageInfo.apply(850, 1000, true, 85)`) instead of the
  constructor, and chain the setters.

Pattern matching (`case ContextStep(name, conversation, before, after, applied)`) and the fields are unchanged.

### `GeminiConfig`, `VertexAIConfig` and `AzureConfig` are growth-safe (#2263)

`GeminiConfig`, `VertexAIConfig` (`llm4s-gemini`) and `AzureConfig` (`llm4s-openai`) follow the growth-prone pattern,
as `OpenAIConfig`, `AnthropicConfig` and `OllamaConfig` already did ([#2263](https://github.com/llm4s/llm4s/issues/2263)),
so a new field - as `googleSearch` was - no longer breaks Java and Kotlin callers or compiled code. The constructor
and `copy` are private:

- **Scala**: `GeminiConfig(...)`, `VertexAIConfig(...)` and `AzureConfig(...)` with the same arguments, positional or
  named (`timeouts`, `googleSearch` and `wellKnownCredentialPath` keep their defaults), still compile. Replace
  `config.copy(contextWindow = n)` with `config.withContextWindow(n)`; each field has a setter - `withApiKey`,
  `withBaseUrl`, `withReserveCompletion` and `withGoogleSearch` on Gemini; `withProjectId`, `withLocation`,
  `withCredentialFilePath` and `withWellKnownCredentialPath` (the value or an `Option`) on Vertex AI; `withEndpoint`,
  `withApiKey` and `withApiVersion` on Azure - besides `withModel` and `withTimeouts`.
- **Java and Kotlin**: call the companion's short `apply` and chain setters instead of the constructor -
  `GeminiConfig.apply(apiKey, "gemini-2.0-flash").withGoogleSearch(true)`, `VertexAIConfig.apply(projectId, model)`,
  `AzureConfig.apply(endpoint, apiKey, deployment).withApiVersion("2024-10-21")`. They take the default base URL,
  location or API version and a context window from the model name; `fromValues` still consults the bundled model
  catalogue.

Pattern matching (`case GeminiConfig(...)`) is unchanged.

### `ToolRetryPolicy` is growth-safe (#1708)

`ToolRetryPolicy` follows the growth-prone pattern, so that it could gain `jitter`
([#1708](https://github.com/llm4s/llm4s/issues/1708)): build it with `ToolRetryPolicy(...)`, whose arguments are
unchanged apart from the new `jitter` (default `0.0`, no jitter), and change it with `withMaxAttempts`,
`withBaseDelay`, `withBackoffFactor` and `withJitter`. `new` and `.copy` are private:

```scala
import org.llm4s.toolapi.ToolRetryPolicy
import scala.concurrent.duration._

val policy = ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis).withMaxAttempts(5) // was .copy(maxAttempts = 5)
```

### `AssistantMessage` and `Completion.thinking` (#1381)

`AssistantMessage` is a growth-prone type ([#1381](https://github.com/llm4s/llm4s/issues/1381)): build it with
`AssistantMessage(...)` and change it with `withContent`, `withToolCalls` and `withThinking`; `.copy` is private. A
positional pattern takes four fields: `case AssistantMessage(content, toolCalls, thinking, thinkingBinding)`.
`Completion` loses its `thinking` parameter and `withThinking`: `Completion.thinking` is `message.thinkingText`, so set
it with `completion.withMessage(completion.message.withThinking(...))`.

### `JSONValidator` checks property types (#922)

A schema's `properties.<field>.type` is now enforced on the top-level fields the output contains
([#922](https://github.com/llm4s/llm4s/issues/922)). Output that passed because the type was ignored may now be
rejected; correct the schema or the output. Nested `properties`, `items` and `enum` are still not checked.

### `Result.traverse` and `recoverWithBackoff` (#960)

`Result.traverse` stops calling its function at the first `Left`, so side effects no longer run for the elements after
a failure ([#960](https://github.com/llm4s/llm4s/issues/960)). `ErrorRecovery.recoverWithBackoff` returns an error it
does not retry unchanged instead of wrapping it in an `ExecutionError`, and retries a `ServiceError` only for a 5xx,
429 or 408 status.

### Vertex AI Application Default Credentials (#1939)

`VertexAIAuthProvider`'s `wellKnownCredentialPath` is an `Option[String]`, and `None` (the default) looks up no gcloud
credentials file ([#1939](https://github.com/llm4s/llm4s/issues/1939)). A client built from a named section finds the
file as Google's libraries do. A hand-built `VertexAIClient` keeps the fallback by setting the path:

```scala
VertexAIAdcConfigLoader.load().map(config.withWellKnownCredentialPath)
```

`GOOGLE_APPLICATION_CREDENTIALS` still wins.

### `AudioFormat.Mp3` (#1307)

`AudioFormat` has a new case, `Mp3` ([#1307](https://github.com/llm4s/llm4s/issues/1307)), so an exhaustive `match`
needs one more branch. PCM stays the default; the WAV writers and `AudioPreprocessing.standardizeForSTT` refuse MP3
with a `ValidationError`.

### `RegexValidator`'s constructor

The primary constructor of `RegexValidator` takes a compiled `Pattern` and a description. The `Regex`-based
constructors remain, so source compiles unchanged, but code compiled against 0.4.1 must be recompiled.

## Stage 3 migration: the Deep Agents harness

In 0.5.0, except the slices for #1904, #1905, #1908, #1909, #1910, #1913 and #1915, which are in 0.5.1. Stage 3
([#1901](https://github.com/llm4s/llm4s/issues/1901)) builds the harness as middleware
and tools on `AgentBuilder`. Each slice adds its part here as it lands:

- [#1902](https://github.com/llm4s/llm4s/issues/1902), kernel hooks for the harness - state-writing hooks, prompt
  sections, accounted model calls, and a Block that restores the whole turn: [below](#kernel-hooks-for-the-harness-1902).
- [#1934](https://github.com/llm4s/llm4s/issues/1934), restoring a checkpoint after the graph gains a key - which
  shape changes keep a thread restorable: [below](#restoring-after-the-graph-changes-1934).
- [#1903](https://github.com/llm4s/llm4s/issues/1903), tool permissions, timeouts and batch locks:
  [below](#tool-permissions-timeouts-and-batch-locks-1903).
- [#1907](https://github.com/llm4s/llm4s/issues/1907), todos: [below](#todos-1907).
- [#1908](https://github.com/llm4s/llm4s/issues/1908), context management - summarisation and large tool-output
  offload: [below](#context-management-1908).
- [#1911](https://github.com/llm4s/llm4s/issues/1911), semantic memory - the new `llm4s-agent-memory` module and
  `TransactionalMemoryStore`: [below](#semantic-memory-1911).
- [#1914](https://github.com/llm4s/llm4s/issues/1914), typed custom streams: [below](#typed-custom-streams-1914).
- [#1915](https://github.com/llm4s/llm4s/issues/1915), the harness profile and middleware placement checks:
  [below](#harness-profile-and-middleware-placement-1915).
- [#1921](https://github.com/llm4s/llm4s/issues/1921), replay of external effects - `ReplayGuard`, `Checkpointer.effect`
  and `Commit.effects`: [below](#replay-of-external-effects-1921).
- [#1926](https://github.com/llm4s/llm4s/issues/1926), the tenant on a run claim - `ClaimRequest` and `RunClaim`
  carry it: [below](#the-tenant-on-a-run-claim-1926).

Design: `docs/design/typed-agent-runtime/4.22-stage-3-slice-c1-kernel-hooks.md` (§4.22) onwards.

### Kernel hooks for the harness (#1902)

Code that only builds agents with the built-in middleware needs no change. A middleware of your own that overrides
`beforeAgent`, `afterAgent` or `wrapModelCall` does. Source breaks, with no shims:

- **The boundary hooks take a `ModelContext` and return a `BoundaryResult`.** `context.run` is the `RunContext` they
  took before; `context.state` the committed state the task read. Wrap the text, and add an update to keys the
  middleware declares in `writes` if it has one:

  ```scala
  // before
  override def beforeAgent(input: String, context: RunContext): Result[String] = Right(input.trim)
  // after
  override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
    Right(BoundaryResult(input.trim))
  ```

  `AgentMiddleware.Asking.answered` takes the `ModelContext` too.
- **`wrapModelCall` takes a `ModelContext` and its `next` returns a `ModelResponse`.** A wrapper that passed `next`
  through needs only the new signature. One that read the completion reads `response.completion`; one that changed it
  uses `response.withCompletion(c)`, which keeps the inner wrappers' updates; one that answered without calling
  `next` returns `ModelResponse(completion)`. A wrapper adds writes with `response.addUpdate(update)`.
- **`writes` covers every hook.** An update from any hook may touch only that middleware's own `writes`; any other
  key fails the run with a `ValidationError`.
- **An output Block restores every key the turn wrote.** Before, only the turn's messages, the active agent and the
  transfer were rolled back, and a key a tool or middleware wrote during a blocked turn kept its new value. Now
  every such key returns to its value before the turn; `usage` is kept. A file, sandbox or memory store outside the
  thread state is not restored.
- **Usage counts provider calls.** A retrying wrapper's discarded calls are now in `AgentResult.usage`, and a
  completion a wrapper returned without calling the model (a cache, a reviewed answer) no longer counts as a request.
  `ModelCallCompleted` is one event per provider call, `attempts` that call's attempt number (0 for a hook's own call
  through `ModelContext.accounted`). A hook that calls a model itself - or hands a client to a library that does -
  wraps the client with `context.accounted(client)`, so its calls are counted.
- **`RunEvent.RunFailed` gains `usage`; `RunCancelled` and `RunTimedOut` become case classes with `usage`**: the
  calls of the attempts that committed nothing - a task that failed, or one the cancel or deadline cut short. Match
  them as `RunFailed(message, _)`, `RunCancelled(_)` and `RunTimedOut(_)`; build them as `RunCancelled()`. Events
  stored before decode with `None`.
- **Guardrails take the hook's context.** `Guardrail` gains `validate(value, context: ModelContext)`, defaulting to
  `validate(value)`; `GuardrailMiddleware` calls it. A guardrail of your own that calls a model overrides it to call
  through `context.accounted(client)`; one that combines guardrails passes `context` on. A subclass of
  `LLMGuardrail` that called `evaluateWithLLM(content)` passes the client too: `evaluateWithLLM(content, llmClient)`.
- **An output Block keeps edits made by others.** A key `updateState` changed while the turn waited for review keeps
  the edit; only keys still holding the turn's writes are restored.
- **New hook `promptSections`** adds system-prompt content per request, never stored; together the sections are
  capped by `AgentBuilder.withMaxPromptSectionTokens` (default 8192, four characters a token, measured on the
  rendered prompt, separators included) and to 64 per request, over which the run fails. `LoopAgent` gains `maxPromptSectionTokens` and `TurnState` gains `priorValues`; a stored `TurnState` without
  it decodes.

### Restoring after the graph changes (#1934)

Each checkpoint now records its graph's shape (`GraphSnapshot.shape`, `CompiledGraph.shape`), and a restore accepts the
changes the restored run cannot observe (design §4.29). No code change is needed; what is refused changes:

- **Now restored:** a thread at rest (`Completed`, or `Failed` after a Block) into a graph of another `version`, with
  other edges or joins, or more nodes or keys - for an agent, a family with another prompt, or with a tool, middleware
  or handoff added. Any thread into a graph that only gained keys (each reads its initial value) or gained nodes no
  stored node has a static edge or join to.
- **Still refused:** a node removed; a key removed while the thread holds a value for it (finish with the old graph,
  `Remove` the entry, or `forget` the thread); and, for a thread in flight (suspended, or `Running` after a crash),
  another version, a stored node's changed out-edges, or a changed join over stored nodes. Resume or recover such a
  thread with the graph it was written by before changing it.
- **Snapshots written before this change** carry no shape. They restore into their own version when the graph only
  gained keys, and are refused for anything else until they run once more.
- `RestoreRejected` problems name the change; "snapshot's graph structure differs from this graph's" is gone.
- **Effect records (#1921) stay valid.** A thread restored in flight keeps its task ids, so a task `recover` runs again
  replays its recorded effects; a refused restore commits nothing and leaves them in place.
- A harness key that should stay removable `Remove`s its entry when it is empty again: removal is judged by the stored
  entry, which cannot be decoded once its key is gone.
### Tool permissions, timeouts and batch locks (#1903)

Design §4.23. Additive, bar one reserved key: an agent without `PermissionMiddleware`, `withTimeout` or `withLocks` behaves as before.

- **`tool-queue` is a key the loop owns.** A tool or middleware that declares a `StateKey` with id `tool-queue` is refused at build, as `tool-results` is. Rename it.
- **`ToolCallRequest` carries `resources`.** `ToolCallRequest(spec, call)` still compiles and gives the tool's `defaultResource`, `(Invoke, <name>)`; the loop builds every request with the call's own resources. A wrapper that builds one for a tool with `withResources` passes them: `ToolCallRequest(spec, call, resources)`.
- **`PermissionMiddleware` denies what no rule allows**, a tool without `withResources` included: add `PermissionRule.allow(Operation.Invoke, "<name>")` (or `"*"`) for each tool the agent may call. See [Tool Permissions, Timeouts and Locks](/guide/agents/#tool-permissions-timeouts-and-locks).
- **Adopting locks adds the `tool-queue` key.** The loop registers `tool-queue` only for an agent with a tool that declares `withLocks`. Since #1934 an added key keeps old checkpoints restorable, so threads checkpointed before the first tool adopted locks continue, at rest or suspended. Dropping every lock while a suspended thread still has calls queued in `tool-queue` is refused with `RestoreRejected` rather than stranding them: resume such a thread with its locks first. Once an agent has the key, changing lock functions is safe for threads in flight: a batch keeps the lock schedule it was made with. Agents without locks, timeouts and resources included, restore their existing threads unchanged.
- **Locked calls cost supersteps.** Each further round of a batch whose calls hold conflicting locks costs two supersteps; raise `RunBudgets.maxSupersteps` if a long batch of conflicting calls reaches it.

### Todos (#1907)

Design §4.30. Additive: `TodoMiddleware` (`org.llm4s.agent.harness`) gives an agent a `write_todos` tool and a plan prompt section.

- **Adding `TodoMiddleware` to an existing agent** registers the `todos` key, and its tool's lock registers `tool-queue`. Since #1934 a thread at rest continues, its plan starting empty. A thread in flight (suspended, or `Running` after a crash) is refused with `RestoreRejected`: resume or recover it with the old agent first. So is a thread last checkpointed before #1934, until it has run once under the agent it was written by. Removing `TodoMiddleware` refuses a thread whose plan holds items. Agents without it are unchanged.
- **`todos` is a key id the middleware owns.** A `StateKey` of your own with id `todos` in a family that also uses `TodoMiddleware` is refused at build, as two keys with one id are. Rename it.
- **With `PermissionMiddleware`, allow the tool**: `PermissionRule.allow(Operation.Invoke, "write_todos")`.

### Context management (#1908)

Design §4.34. Additive: `SummarizationMiddleware` and `ToolOutputOffload` (`org.llm4s.agent.harness`).

- **Adding either to an existing agent** registers its key (`summary`, `offloads`), which changes the agent's version as any middleware key does (§4.29): a thread at rest continues, starting with no summary or offloads; a thread in flight is refused until it is resumed or recovered with the old agent. Agents without them are unchanged.
- **`summary` and `offloads` are key ids the middleware own.** A `StateKey` of your own with either id in a family that uses the middleware is refused at build. Rename it.
- **Use `SummarizationMiddleware` or `ContextWindowMiddleware`, not both.** `ContextWindowMiddleware` is unchanged, the pruning-only option.

### Semantic memory (#1911)

Design §4.31. `SemanticMemoryMiddleware` is in a new module, `llm4s-agent-memory` (package `org.llm4s.agent.harness.memory`); add the dependency to use it. In `llm4s-memory`:

- **`InMemoryStore`, `SQLiteMemoryStore` and `PostgresMemoryStore` are `TransactionalMemoryStore`s.** Nothing changes for code that only stores and recalls. `InMemoryStore` carries its applied writes and fencing tokens in its value, and `clear()` keeps them.
- **`MemoryStore.replace(add, remove)` is new**, with a default that stores, then deletes; the SQL stores override it as one transaction. A store of your own that writes in place should override it too.
- **`LLMMemoryManager.consolidateMemories` replaces each group with one `store.replace(Seq(summary), groupIds)`** instead of a store followed by one delete per memory. A store of your own that overrides `store` and `delete` but not `replace` gets the same writes as before, in the same order.
- **`PostgresMemoryStore.Config.tableName` is at most 55 characters** (was 63), so the names derived from it (`<table>_applied`, `<table>_fences`, the `idx_<table>_...` indexes) stay distinct within PostgreSQL's 63-byte identifier limit. A longer name is refused when the config is built: give the table a shorter name.
- **SQLite memory files move to schema version 2.** Opening a file creates its two new tables, `memory_applied_writes` and `memory_fences`, and records version 2 beside version 1 in `schema_version`. A Postgres store creates `<table>_applied` and `<table>_fences` beside its table.
- **Adding `SemanticMemoryMiddleware` to an existing agent** registers the `semantic-memory-recalled` key and changes the agent's version (§4.29). A thread at rest restores, with nothing recalled until its next turn, and restores again if the middleware is removed (the key is cleared at the end of each turn). A thread in flight - suspended, or `Running` after a crash - is refused with `RestoreRejected`: resume or recover it with the old agent first. Agents without it are unchanged.

### Harness profile and middleware placement (#1915)

Design §4.37. `HarnessProfile` (`org.llm4s.agent.harness`), `AgentBuilder.withHarness` and `org.llm4s.config.HarnessConfigLoader` are additions; `llm4s-agent` now ships a `reference.conf` with the `llm4s.agent.harness` block. One behaviour changes:

- **`build()` refuses a middleware order that a middleware declares wrong** (`AgentMiddleware.placement`), with a `ValidationError` naming both middleware and the fix. A `SemanticMemoryMiddleware` registered after (inside) a `GuardrailMiddleware` or `GuardrailReviewMiddleware` - which recorded answers the guardrail then blocked - is refused: register it first, or name the guardrail's id in its `runsBefore`. So is one on a handoff target when the root agent has guardrail middleware, since the root's `afterAgent` runs after every agent's own: put both on the same agent. A `SummarizationMiddleware` registered after a `ToolOutputOffload` is refused too: register it first.

### Typed custom streams (#1914)

`EventHandlers` and the effect modules' `events(run, ...)` are additions. One behaviour changes:

- **An fs2, ZIO, Pekko or Java agent stream whose consumer stops draining now fails** once more than `Agent.StreamCapacity` (1024) durable events wait for it, with an error naming the last `seq` it took, as the runtime already disconnects a listener that far behind (`Disconnected(lastSeq, Lagging)`). Before, the stream's buffer kept every durable event without limit. A `stream*` that fails this way cancels its turn, as any failure of those streams does; resume from the thread's log with `GraphRuntime.subscribe(threadId, afterSeq = lastSeq)`. A consumer that keeps up, even slowly, is unaffected: a run's own events stay far below the bound.

### Replay of external effects (#1921)

Effects outside thread state replay through `ReplayGuard`: an intent and an outcome per `EffectKey`, kept in the thread's own store. Code that only runs agents or graphs compiles and works unchanged. Code that implements `Checkpointer`, or wraps one, changes:

- **`Checkpointer` gains `effect(threadId, key): Result[Option[EffectRecord]]`.** A wrapper forwards it:

  ```scala
  def effect(threadId: ThreadId, key: EffectKey) = underlying.effect(threadId, key)
  ```

- **`Commit` gains `effects: Vector[EffectRecord]`**, defaulted in `apply`, so existing calls compile. A store must do three things with it:
  - Upsert a commit's records by `key`.
  - On every commit that carries a checkpoint, first remove each record for which `commit.releasesEffect(record.taskId, record.lineage)` holds.
  - Remove a thread's records in `deleteThread`. Never remove them in `prune`.

  Persist records with `EffectRecord.toJson` and read them with `EffectRecord.fromJson`. `CheckpointerContract` gains the cases that check all of this.
- **`GraphError` gains `EffectReplayRefused(operation, reason)`.** A `match` on `GraphError` that must be exhaustive gains a case.
- **The SQLite schema moves to version 3** (`llm4s_checkpoint_effects`). A version-2 file is migrated when it is opened. A build from before this change refuses a version-3 file, as it refuses any newer schema.
- **A `Transactional` target fences itself by the claim token.** `AppliedKeys.forget(key, token)` and the effect given to `ReplayGuard.transactional` (`token => ...`) refuse a token older than the newest seen for `key.scope`. A `Verifiable` target's effect is a compare-and-set against the plan's `before`.
- **A store keys release by the record's task and lineage.** `Commit.releasesEffect(taskId, lineage)` replaces `releasesEffect(taskId, writer)`; persist `EffectRecord.lineage` with the record (SQLite: schema 4, `task_lineage`), and `PendingWrite.resultDigest` with the write. A store that persists the write's and the snapshot's JSON whole needs no change; the snapshot gains `PendingTask.resumeDigest` and `ParkedContinuation.questionDigest` / `resumeDigest`. `GraphSnapshot.liveTasks` is a set of `(id, lineage)` pairs. The checkpoint format moves to 7 (`PendingTask.lineage`, `originLineage`); format 6 never shipped (v0.4.1 has no graph checkpoints), so a format-6 checkpoint restores with no legacy keys: each task gets a fresh root lineage at admission, and any effect record it wrote is ignored.
- **A tool call's idempotency key now names the issuing model task and the call itself** (`IdempotencyKey.ofCall`), not the model call's checkpoint: a request asked again that answers with the same call gets the same key. `IdempotencyKey.derive` is removed: the loop no longer uses it, and a test builds a key with `IdempotencyKey("...")`.
- **`ToolQuestionRequest` gains `phase: Option[IdempotencyKey]`** (defaulted; a stored request without it decodes as `None`). An asking tool's `resume` gets effect keys of its own, distinct from `execute`'s.
- **New, opt-in API**: `RunContext.taskKey`, `RunContext.effectKey` and `ToolContext.effectKey`; `ReplayGuard` and its `Plan`; `ReplaySupport`, `StateCheck`, `AppliedKeys` and `EffectRecord`; and, in `llm4s-agent-testkit`, `ReplayContract`, which every external target of the harness passes. See [Tool side effects](../guide/agents/#tool-side-effects).

Design: `docs/design/typed-agent-runtime/4.24-stage-3-slice-c4-replay-of-external-effects.md` (§4.24).

### Harness backend SPIs (#1904)

Design §4.25. New, opt-in API in `org.llm4s.agent.harness`; nothing existing changes, except:

- **`llm4s-agent-testkit` depends on `llm4s-testkit`**, whose `ScriptedLLMClient` drives `FileBackendContract`'s agent runs. A build that excludes `llm4s-testkit` from its test classpath adds it back.
- **File tools over a `StateFileBackend` add the `files` key.** Every mutation holds the lock `files/state/<keyId>` (needed: it keeps two edits of one file in a batch from losing one, and the quota exact), so adding file tools over a `StateFileBackend` adds its `files` key and C2's `tool-queue`, and changes the agent's version. Under §4.29 (C5) a thread at rest continues, its files starting empty; a thread in flight (suspended, or `Running` after a crash) is refused with `RestoreRejected` until it is finished or forgotten. Taking the tools out again refuses only a thread whose files hold content: the last file's delete removes the key's entry, so a thread with no files restores.

### Event records carry their claim's token (#1918)

A run is told from an earlier run with the same `RunId` by its claim's fencing token. Code that only runs agents or graphs is unaffected.

- **`EventRecord` gains `token: FencingToken`, after `runId`.** A `Checkpointer` builds each record with `EventRecord.committed(threadId, seq, commit.token, draft)` (the token is a new parameter) and returns and replays it as stored. `CheckpointerContract` checks that every record carries the token of the commit that stored it.
- **Persisted logs stay readable.** A store that keeps the record's JSON, as both shipped stores do, needs no schema change: a record written before this change has no `token` key and reads as `FencingToken(0)`, which no store issues. An older build reading a newer record ignores the key.
- **Positional constructions and patterns gain a field.** `EventRecord(threadId, seq, runId, checkpointId, ...)` becomes `EventRecord(threadId, seq, runId, token, checkpointId, ...)`, and `case EventRecord(_, _, _, _, _, _, _, event)` gains a `_`.
- **`StreamEvent.Live` gains `token: FencingToken`, after `runId`**: the claim token of the run that sent the progress. `StreamEvent.Live(threadId, runId, taskId, nodeId, name, version, payload)` becomes `StreamEvent.Live(threadId, runId, token, taskId, nodeId, name, version, payload)`, and a positional pattern gains a `_`. Matching with `EventType` or `EventHandlers`, as `AgentEvents.TextDelta(d)` does, needs no change. A run's observer and its run-scoped streams no longer deliver progress of another claim under their `RunId`, and a run that has lost its claim sends none.

### The tenant on a run claim (#1926)

A claim records the tenant of the run that holds it, so that neither `ThreadBusy` nor `RunRecovered.takenOverFrom`
ever names another tenant's run. Code that only runs agents or graphs needs no change. Code that calls
`Checkpointer.claim` itself, builds a `RunClaim`, or implements `Checkpointer` changes:

- **`ClaimRequest(holder, ttl)` is `ClaimRequest(holder, tenantId, ttl)`**, `tenantId: Option[TenantId]`, also in
  `ClaimRequest.of`; `withTenantId` sets it. Pass the run's `RunConfig.tenantId`, or `None` for a run of no tenant.
  `claim`'s signature is unchanged, so a wrapper that forwards it needs no change.
- **`RunClaim(threadId, holder, token, expiresAt, takenOverFrom)` is `RunClaim(threadId, holder, tenantId, token,
  expiresAt, takenOverFrom)`**: the claim's own tenant, with `withTenantId`.
- **A store records the tenant with each claim, and names a claim's holder only to a claim of the same tenant**: a
  refusal is `ThreadBusy(..., BusyHolder.Run(holder))` only then, and `BusyHolder.Unnamed` otherwise, and a
  takeover's `takenOverFrom` is `None` when the replaced claim was another tenant's. A claim whose tenant the store
  cannot tell - one stored before it recorded tenants - is of no tenant. `CheckpointerContract` checks all of this.
- **`ThreadBusy` from a runtime now names a holder of the caller's own tenant on a thread with no checkpoint yet**:
  before, a holder in another runtime was `Unnamed` until the thread's first checkpoint. A holder of another tenant
  is `Unnamed`, also when the thread's latest checkpoint is the caller's.
- **`RunEvent.RunRecovered.takenOverFrom` is `None` for a takeover of another tenant's claim.**
- **The SQLite schema moves to version 5** (`claim_tenant`, `claim_tenant_token` on `llm4s_checkpoint_threads`). A
  version-4 file is migrated when it is opened; a claim it holds then is of no tenant, so its holder is named to
  nobody until it is released or lapses, at most `ttl` later. A build from before this change refuses a version-5
  file.

Design: `docs/design/typed-agent-runtime/4.16-stage-2-slice-1-the-durable-checkpointer-contract.md` (§4.16).

### Bounded store calls (#1919)

Code that only runs agents needs no change. A graph run may now end with one more error, and a store has one more
duty.

- **New `GraphError.CommitTimedOut(threadId, after)`**, from `RunHandle.await()` of a graph run with
  `Durability.Async` or `OnExit` whose closing commit stayed in the store for twice `ClaimPolicy.ttl`. An `Agent`
  runs `Sync`, so never sees it. An exhaustive `match` on `GraphError` gains a case. After it, read the thread
  before `recover`: the commit may or may not have landed.
- **A `Checkpointer` must return from every call within a bound it documents**, failing a call that reaches it with
  nothing changed. A store whose calls could wait on a lock, another connection or a network with no limit must add
  a timeout. To have `CheckpointerContract` check it, override `boundedStore` with
  `CheckpointerContract.BoundedStore(store, bound, busy)`.
- **`SqliteCheckpointer` calls fail sooner under contention inside one store**: a call waits for the store's own
  earlier calls at most `busyTimeout` (with its turn at the file), then fails with a `ProcessingError`, where it
  used to wait with no limit. Raise `SqliteCheckpointerConfig.busyTimeout` if many threads share one store and its
  calls are slow.

Design: `docs/design/typed-agent-runtime/4.16-stage-2-slice-1-the-durable-checkpointer-contract.md` (§4.16).

## Stage 2 migration: durable execution

In 0.5.0. Stage 2 ([#1699](https://github.com/llm4s/llm4s/issues/1699)) makes agent and graph threads durable and adds human review. One note covers the whole stage; each slice adds its part here as it lands:

- [#1700](https://github.com/llm4s/llm4s/issues/1700), the durable `Checkpointer` contract - run-claim leases, fencing tokens and a store contract suite: [below](#durable-checkpointer-contract-1700).
- [#1703](https://github.com/llm4s/llm4s/issues/1703), tool side-effect safety - idempotency keys and one result per tool call: [below](#tool-side-effect-safety-1703).
- [#1704](https://github.com/llm4s/llm4s/issues/1704), human-review interrupts - static breakpoints and typed middleware questions: [below](#human-review-interrupts-1704).
- [#1702](https://github.com/llm4s/llm4s/issues/1702), checkpoint history, fork, `updateState` and retention: [below](#checkpoint-history-fork-updatestate-and-retention-1702).
- [#1701](https://github.com/llm4s/llm4s/issues/1701), the SQLite checkpointer - a new module, nothing to change: [below](#sqlite-checkpointer-1701).
- [#1705](https://github.com/llm4s/llm4s/issues/1705), live subscriptions across runtimes - `Checkpointer.awaitEventsAfter` and `WatchPolicy`: [below](#live-subscriptions-across-runtimes-1705).

Design: `docs/design/typed-agent-runtime/4.16-stage-2-slice-1-the-durable-checkpointer-contract.md` (§4.16) onwards. Guide: [Durable Checkpointers](../guide/agents/durable-checkpointers.html).

### Durable `Checkpointer` contract (#1700)

A run now holds a lease on its thread - a `RunClaim` with an expiry and a `FencingToken` - which it renews while it runs and releases when it ends, and every commit carries the token. Code that only runs agents or graphs on `GraphRuntime` or `Agent` needs no change. Code that implements `Checkpointer`, builds a `Commit`, `Checkpoint` or `RunPosition`, or matches `ThreadBusy` does. Source breaks, with no shims:

- **`Checkpointer` gains `claim`, `renew` and `release`.** A store grants at most one live claim per thread, judged by its own clock, with a token greater than every token it issued for that thread before, and refuses every commit whose token is not the current claim's with `GraphError.StaleClaim`. A wrapper that delegates to another store forwards the three methods:

  ```scala
  def claim(threadId: ThreadId, request: ClaimRequest)                      = underlying.claim(threadId, request)
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration)   = underlying.renew(threadId, token, ttl)
  def release(threadId: ThreadId, token: FencingToken)                      = underlying.release(threadId, token)
  ```

  Test a store with `CheckpointerContract` from the new `llm4s-agent-testkit` (see the guide).
- **`Commit(checkpoint, pendingWrites, events)` is `Commit(token, checkpoint, pendingWrites, events)`**, with a private constructor: build it with `Commit(token, ...)` (all but the token default to empty) and change it with `withCheckpoint`, `withPendingWrites`, `withEvents` and `withToken`. A commit to `InMemoryCheckpointer` - or any store - now needs a claim first: `store.claim(threadId, ClaimRequest(runId, tenantId, ttl))` (the tenant since [#1926](#the-tenant-on-a-run-claim-1926)) returns the `RunClaim` whose `token` it carries.
- **`Checkpoint` and `RunPosition` have private constructors and gain `fencingToken: Option[FencingToken]`.** Build them with `Checkpoint(...)` and `RunPosition(...)` as before (the new field defaults to `None`) and change them with `with*` setters; `checkpoint.copy(parent = p)` is `checkpoint.withParent(p)`. A run's position carries its claim's token; `None` outside a runtime.
- **`Checkpoint.CurrentFormat` is 5.** Format 4 and earlier checkpoints still read (as written by no claim, `fencingToken = None`); a build before this change refuses format 5.
- **`GraphError.ThreadBusy(threadId, latestCheckpoint)` gains `holder: GraphError.BusyHolder`** (default `BusyHolder.Unnamed`): `BusyHolder.Run(runId)` names the holding run when the caller may see it; `Unnamed` is another run that cannot be named; `Lapsed` is a `fork`, `updateState` or `deleteThread` whose own claim lapsed before its write was known to land, with no other run known to hold the thread - a write it had already sent may still land, so read the thread before retrying. A pattern `case GraphError.ThreadBusy(t, c)` becomes `case GraphError.ThreadBusy(t, c, _)`, and one on a named holder `case GraphError.ThreadBusy(_, _, GraphError.BusyHolder.Run(runId))`. `ThreadBusy` is now also returned when another runtime or process holds a live claim on the thread, and by `updateState` and a `fork` onto a held target.
- **`GraphError.ThreadBusy` is a `RecoverableError`** (it was a `NonRecoverableError`): a retry may get past it once the thread is free, so generic retry code - `LLMError.isRecoverable`, `RetryPolicy`, `ErrorRecovery`, and a node given an explicit `RetryPolicy` (the default node policy retries nothing) - now retries it. Code that treated every `GraphError` but `DeadlineExceeded` as final should let `ThreadBusy` through to its retry. A call wrapped in `ErrorRecovery.recoverWithBackoff` that is still `ThreadBusy` after its last attempt now ends with an `ExecutionError` naming the attempts and the last error, not with the `ThreadBusy` itself; match on that, and beware that `ExecutionError` is itself recoverable, so nested retry layers multiply attempts.
- **`GraphError.StaleClaim(threadId, token: FencingToken, current: Option[FencingToken])` is new**: a commit, renewal or release made with a token that is not the thread's current claim. Build it, or compare with it, with `FencingToken(n)`, not a bare `Long`. It is the store's refusal. No runtime call returns it, wrapped or not: a run that loses its claim, or a `start`, `recover` or `resume` whose claim's commit is refused as stale, ends with `ClaimLost` (below), and a `fork`, `updateState` or `deleteThread` whose claim lapsed or was taken over mid-operation returns `ThreadBusy` - nothing was applied, so retry.
- **A run that lost its claim ends with `GraphError.ClaimLost(threadId, token, current)`, not `CheckpointWriteFailed(StaleClaim)`.** It is new: the run's lease passed with no renewal landing (the run judges it by its own monotonic clock), or a renewal or commit was refused as stale - the claim's own commit at admission included, so `start`, `recover` and `resume` return it too. The run stops at once, its tasks interrupted, and commits nothing more. A pattern `case GraphError.CheckpointWriteFailed(_, _: GraphError.StaleClaim, _)` becomes `case _: GraphError.ClaimLost`. Unlike `CheckpointWriteFailed`, it is not a store outage. `recover` may be attempted once the thread is free: it is refused with `ThreadBusy` while a successor holds it, a successor that finished leaves the thread's state showing it, and a lease that lapsed with no successor leaves the run for `recover` to continue.
- **`RunEvent.RunRecovered` gains `takenOverFrom: Option[String]`**, the run id whose expired claim the recovery took over, and **`RunClaim` gains `takenOverFrom: Option[RunId]`**, which a store sets when a claim replaces an expired, unreleased one. Both default to `None`, so a log written before still reads; a pattern `case RunEvent.RunRecovered(c, t, p)` becomes `case RunEvent.RunRecovered(c, t, p, _)`. A store reports the replaced holder from `claim` (`CheckpointerContract` checks it).
- **`InMemoryCheckpointer` takes an optional `Clock`** (default the system clock), by which its claims expire, and **`GraphRuntime` an optional `ClaimPolicy`** (`ttl` 30 seconds, `renewEvery` 10 seconds). Java and Kotlin keep `new InMemoryCheckpointer()`, `new GraphRuntime(store)` and `new GraphRuntime(store, clock)`. **`GraphRuntime.inMemory(clock, claims)`** gives its store the same clock - before, the store used the system clock whatever `clock` was - so a test that moves `clock` now moves claim expiry and `prune`'s ages too.
- **`GraphRuntime.deleteThread` claims the thread before deleting it**, so it is refused with `ThreadBusy` while a run holds the thread in any runtime, not only in its own.
- **`Checkpointer.deleteThread(threadId)` is `deleteThread(threadId, token: FencingToken)`**, fenced like a commit: a store deletes only while `token` is the thread's current claim, checked atomically with the delete, and otherwise returns `GraphError.StaleClaim` and deletes nothing. A thread with no claim - an unknown one included - is therefore refused too; claim it first (`GraphRuntime.deleteThread` does). A wrapper that delegates to another store forwards the token:

  ```scala
  def deleteThread(threadId: ThreadId, token: FencingToken) = underlying.deleteThread(threadId, token)
  ```

  A store implementation checks the token as its `commit` does, in the same lock or transaction as the delete. `GraphRuntime.deleteThread` passes its own claim's token, and reports a delete whose claim another runtime took over as `ThreadBusy` (or `TenantMismatch`, for another tenant's run), with nothing deleted.
- **A new thread under a deleted thread's id numbers its events on past the deleted thread's**, not from 1 again: one past the deleted thread's next number, which is never used. A store keeps a tombstone of that number (no data) for each deleted thread that numbered an event, refuses a read of the log after one of the deleted thread's numbers with the new **`GraphError.ThreadDeleted(threadId, afterSeq, firstSeq)`** (`Checkpointer.deleted(afterSeq, firstSeq)` is the rule), and reads from `afterSeq = 0` from the current thread's first event. Code that assumed a thread's first event is `1` should read from `0`.
- **`DisconnectReason` gains `ThreadDeleted`.** A subscription to a deleted thread - in the deleting runtime, or in another runtime sharing the store, at its next read of the store - ends with `Disconnected(lastSeq, ThreadDeleted)` and never delivers a new thread of the same id as a continuation of the old one; subscribe again from `0` to follow that one. (A subscription in another runtime that has delivered no event yet cannot be told from a new one, and follows the new thread.) A `match` on `DisconnectReason` needs the new case. Before, a subscription in another runtime went on delivering the new thread's events as if they continued the old log, so the old advice to cancel subscriptions before deleting a thread is no longer needed.
- **`GraphRuntime.subscribe` takes the caller's `RunConfig`, and refuses another tenant's thread.** `subscribe(threadId, afterSeq, capacity)(listener)` is `subscribe(threadId, afterSeq, capacity, config)(listener)`, and `TracingSubscriber.attach(runtime, threadId, tracing, afterSeq)` gains `config` too; both default to `RunConfig()`. A thread whose latest checkpoint is another tenant's - or, with no checkpoint yet, whose run admitting or executing in this runtime is - is refused with `GraphError.TenantMismatch` before the subscription joins, as `history` and `checkpoint` refuse it; a failed read of the thread is returned. Before, any caller could replay and follow any tenant's thread. Pass the `config` your runs use: a multi-tenant caller that subscribed with no `config` is now refused. `RunHandle.subscribe` and `AgentRun.subscribe` use their run's tenant and are unchanged.
- **`DisconnectReason` gains `TenantMismatch`.** A subscription screens each event against its tenant, so that a thread created under its id by another tenant after it subscribed - or deleted and created again by another tenant through another runtime - is never delivered: it ends with `Disconnected(lastSeq, TenantMismatch)` before that thread's first event. A `match` on `DisconnectReason` needs the new case.
- **A `ThreadBusy` whose re-read fails names nothing.** When a claim is refused, or a run's claim commit conflicts, the runtime re-reads the thread to check its tenant before naming its checkpoint. If that re-read failed, the error named the store's own, unchecked checkpoint id - another tenant's, possibly, with that tenant's run id in it. It is now `ThreadBusy(threadId, None, BusyHolder.Unnamed)`.
- **`CheckpointerContract` no longer requires a store to delete regardless of claims.** It checks a delete with the current token, a stale token and another holder's live token, a runtime delete whose claim was taken over, the numbering of a recreated thread, `ThreadDeleted` from `eventsAfter` and `awaitEventsAfter`, and a subscription in another runtime ending with `ThreadDeleted`.
- **The SQLite schema is version 2** (`first_seq` on `llm4s_checkpoint_threads`). Opening a version-1 file upgrades it in one transaction; a build that knows only version 1 refuses a version-2 file. Stop every process on the older build first: one that already has the file open is not refused, and its deletes are unfenced and drop tombstones.
- **There is no unfenced `deleteThread`.** To delete a thread outside a runtime, claim it and delete with the claim's token. A deleted id that numbered events keeps a small tombstone (its next event number) for good.

### Tool side-effect safety (#1703)

Design §4.17. Tools see an idempotency key, and the agent holds every model request to one result per tool call.

```scala
// before
val context = ToolContext(run, ToolCallId("call-1"), state)
ApprovalRequest(messageId, call, reason, ApprovalSource.Tool)
ToolQuestionRequest(messageId, call, question)

// after
val context = ToolContext(run, ToolCallId("call-1"), IdempotencyKey("key-1"), state)
ApprovalRequest(messageId, call, reason, ApprovalSource.Tool, IdempotencyKey("key-1"))
ToolQuestionRequest(messageId, call, question, IdempotencyKey("key-1"))
```

- **`ToolContext` takes the call's key**, after `toolCallId`: `ToolContext(run, toolCallId, idempotencyKey, state,
  approved)`, with `withIdempotencyKey`. You build one only to unit-test a tool; any key will do there
  (`IdempotencyKey("test")`).
- **`ToolTask`, `ApprovalRequest` and `ToolQuestionRequest` gain `idempotencyKey`** - last in `ApprovalRequest` and
  `ToolTask`, before the defaulted `approved` in `ToolQuestionRequest`. Code that only reads them (`AgentResult`'s
  `Suspended`, `ToolLoop.requests`/`questions`, the Java and Kotlin `PendingInterrupt`) is unchanged.
- **Pass the key to the systems your tools change.** The runtime runs a tool at least once: a retrying
  `wrapToolCall`, approval, an answered question and `recover` after a cancellation, `Fatal` or crash run a call
  again, with the same `context.idempotencyKey`. Send it as the external API's idempotency key (or store it in a
  unique column), and keep a tool's first run, before approval or an answer, free of side effects. See
  [Tool Side Effects](/guide/agents/#tool-side-effects).
- **A model request with a dangling tool call fails instead of being sent.** A `wrapModelCall` middleware that
  removes a `ToolMessage` but keeps its call (or the reverse) now fails the model call with a `ValidationError`
  naming the call; prune whole turns, as `ContextWindowMiddleware` does. The check is positional: each call's result
  must be in the run of tool messages straight after its assistant message.
- **A model message with repeated or blank tool-call ids is refused**, before it is stored, as a blank answer is:
  the run fails with `NodeFailed(ValidationError)`, and `recover` asks the model again. Before, a repeated id left
  the thread unable to advance.
- **Imported `history` must pair positionally too.** History that `Message.validateConversation` accepted only
  because an earlier turn's result shared a reused id is refused with a `ValidationError`.
- **No synthetic result for a call cut short.** Unchanged, and now stated: a cancelled, `Fatal` or crashed call
  records no result, and the thread waits for `recover` (`start` returns `IncompleteRun`), which runs only the
  unfinished calls with their keys.
- **Provider authors:** a chat provider that takes tools can run
  `ProviderModuleChecks.assertOneToolResultPerCall(format, response, path)(baseUrl => client)` from
  `llm4s-provider-testkit`; see [Writing a provider](/guide/writing-a-provider#tool-results).

### Human-review interrupts (#1704)

A turn can now wait for a person in two new ways: at a static breakpoint, before or after a node runs, and on a
typed question from middleware. Both are interrupts like approvals and tool questions, answered through the same
`resume`, any subset at a time. See [Human Review](../guide/agents/human-review.html).

- **`AgentStatus.Suspended` has four lists.** `Suspended(approvals, questions)` is
  `Suspended(approvals, questions, middlewareQuestions, breakpoints)`. The new fields default to empty, so
  constructing one still compiles, but a pattern must name all four: `case AgentStatus.Suspended(approvals, _)`
  becomes `case AgentStatus.Suspended(approvals, _, _, _)`, or match the type, `case _: AgentStatus.Suspended`.
- **`PendingInterrupt` (graph kernel) has `breakpoint: Option[BreakpointPhase]`**, `None` for a task that suspended
  itself. For a held task `resumeNode` is the held node and `question` its input (before) or `null` (after).
- **`RunConfig` has `interruptBefore` and `interruptAfter`** (`Set[NodeId]`, empty by default), with
  `withInterruptBefore` / `withInterruptAfter`. Admission refuses a node the graph does not have with a
  `ValidationError`, before the thread is read. `AgentBuilder.withInterruptBefore(AgentNode*)` and
  `withInterruptAfter(AgentNode*)` add breakpoints to every run of an agent; they are not part of its version.
- **`CompiledGraph.resume` refuses a breakpoint answer that is not JSON `null`** (`Breakpoint.proceed`,
  `AgentResult.proceed(id)`, Java `Answer.proceed(id)`) with `InvalidResume`.
- **Middleware can ask.** `AgentMiddleware.Asking[Q, Ans]` declares a question; a hook asks with `ask(q)` (from
  `beforeAgent`, `afterAgent`, `wrapModelCall`) or `askAbout(q)` (from `wrapToolCall`). A middleware that is not
  `Asking` and returns `ToolOutcome.Ask` from `wrapToolCall` now fails the run with `ToolFailed` ("asked a question it
  does not declare"); before, the question was attributed to the tool. A wrapper that retries on `Left` should pass a
  `Left(MiddlewareAsked)` through, as it passes a cancellation.
- **`ApprovalRequest` and `ToolQuestionRequest` gain `answered: Vector[GivenAnswer]`** (default empty), the
  middleware answers a call carries through an approval or a tool question. Both now have a private constructor, as
  the new `MiddlewareQuestionRequest`, `BreakpointRequest`, `GivenAnswer` and `GuardrailReview` do: `apply` still
  builds them and a pattern still matches them, but `copy` is private - `request.copy(approved = true)` becomes
  `request.withApproved(true)`.
- **Java and Kotlin: `PendingInterrupt.toolName()` and `argumentsJson()` return `Optional<String>`.** They were
  `String`s; a breakpoint on a model call, or a middleware question about an answer, has no tool call. They are present
  for every `APPROVAL` and `QUESTION`, so `p.toolName()` becomes `p.toolName().get()` (or `orElse`) there.
  `InterruptKind` gains `MIDDLEWARE_QUESTION` and `BREAKPOINT`: a Kotlin `when` over it that is used as an expression
  must cover them, and a Java `switch` expression too. `PendingInterrupt` gains `middleware()`, `node()` and `phase()`
  (the Java enum `BreakpointPhase`); `Answer` gains `proceed(id)`.
- **`Checkpoint.CurrentFormat` is 6**, with an identity migration from 5: a build that predates breakpoints refuses a
  format-6 checkpoint rather than run a held task's continuation as an ordinary task.

### Checkpoint history, fork, updateState and retention (#1702)

A store now keeps every checkpoint of a thread, and `GraphRuntime` gains `history`, `checkpoint`, `fork`,
`updateState` and `prune`. Code that only runs agents or graphs needs no change. Code that implements
`Checkpointer`, matches `GraphError` or `RunEvent` exhaustively, or relies on checkpoint ids does. Source breaks,
with no shims:

- **`Checkpointer` gains `history`, `checkpoint` and `prune`**, and its contract grows: keep every checkpoint a
  commit supersedes (pending writes still for the latest only), refuse a checkpoint id already in the history with
  `InvalidCommit`, and honour `Commit.retractTurn` by removing every checkpoint after the newest `Completed` or
  `Failed` one before adding the commit's. `CheckpointerContract` checks all of it. A delegating store forwards them:

  ```scala
  def history(threadId: ThreadId, before: Option[String], limit: Int) = underlying.history(threadId, before, limit)
  def checkpoint(threadId: ThreadId, checkpointId: String)            = underlying.checkpoint(threadId, checkpointId)
  def prune(threadId: ThreadId, policy: RetentionPolicy)              = underlying.prune(threadId, policy)
  ```
- **`Commit` gains `retractTurn: Boolean`**, defaulted to `false` in `Commit(...)`, with `withRetractTurn`. The
  runtime sets it on the `Failed` checkpoint that closes a run a node blocked.
- **`GraphError` gains `CheckpointNotFound(threadId, checkpointId)` and `ThreadExists(threadId)`; `RunEvent` gains
  `StateUpdated` and `ThreadForked`.** An exhaustive `match` needs the new cases.
- **Checkpoint ids count along the thread.** They are still `<runId>/<n>`, but `n` is one more than the parent's,
  so the first run on a thread writes `<runId>/1`, `<runId>/2`, ... as before, and a later run continues the count
  rather than starting again at 1. Code that built an id such as `s"$runId/1"` for a later run reads it from
  `history` (or `latest`) instead.
- **A Block retracts its turn from the history.** The thread's latest checkpoint after a Block is its `Failed`
  checkpoint, as before; the earlier checkpoints of the blocked turn are gone from `history`, `checkpoint` and
  `fork`.

`Checkpoint.CurrentFormat` stays 6: nothing changes in a checkpoint's JSON.

### SQLite checkpointer (#1701)

Nothing changes for existing code: the durable store is a new module, `llm4s-agent-checkpoint-sqlite`, and `llm4s-agent` is unchanged by it. To keep threads across restarts, add the dependency and replace the in-memory store:

```scala
// before: threads live as long as the process
val runtime = GraphRuntime.inMemory()

// after: threads live in a file, and `recover` continues a stopped run in the next process
val runtime = SqliteCheckpointer.open(Path.of("runs.db")).map(store => GraphRuntime(store))
```

`SqliteCheckpointer` is in `org.llm4s.agent.graph.sqlite`. It brings the SQLite JDBC driver, at the version `llm4s-memory` already uses. Close it with `close()` when the process is done with it. See [Durable Checkpointers](../guide/agents/durable-checkpointers.html#the-sqlite-store).

### Live subscriptions across runtimes (#1705)

A subscription now delivers the durable events of commits made through any runtime or process sharing its store, not only through its own runtime. Code that runs agents or graphs, or that implements `Checkpointer`, compiles and works unchanged. What changes:

- **`Checkpointer` gains `awaitEventsAfter(threadId, afterSeq, limit, timeout)`**, with a default that polls `eventsAfter`, so existing stores need no change. A store that can be told of commits may override it to return as soon as one lands; `InMemoryCheckpointer` does. A wrapper that delegates to another store should forward it, so that the wrapped store's notification is used:

  ```scala
  override def awaitEventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int, timeout: FiniteDuration) =
    underlying.awaitEventsAfter(threadId, afterSeq, limit, timeout)
  ```

- **`GraphRuntime` takes an optional `WatchPolicy`** after `ClaimPolicy` (default: enabled, `pollInterval` 250 ms). Java and Kotlin get `new GraphRuntime(store, clock, claims)`. `GraphRuntime(store, watch = WatchPolicy.disabled)` gives the old behaviour, where other runtimes' commits are seen only by subscribing again.
- **A live subscription holds a second virtual thread**, `llm4s-watch-<threadId>`, which polls a store that has no change notification (SQLite) once per `pollInterval`. `cancel()` stops it, as before for the dispatcher.
- **A subscription can now end with `Disconnected(lastSeq, ReplayFailed(error))` after its replay**, when a read of the store fails, for instance because the store was closed under it. Before, only a failed replay ended this way. Resubscribe with `afterSeq = lastSeq`.
- **Other runtimes' durable events arrive; their live events do not.** A listener that assumed every durable event came from its own runtime's runs should check `record.runId`.
- **`CheckpointerContract` gains change-notification cases and a `sibling(store, clock)` hook.** A durable store's spec overrides `sibling` to open another store over the same storage, so that the cases run between connections.

## Stage 1 migration: agent runtime

In 0.5.0 ([#1328](https://github.com/llm4s/llm4s/issues/1328), with [#1329](https://github.com/llm4s/llm4s/issues/1329)'s events and tracing, which restore the agent event stream #1328 removed). `Agent` now runs on `GraphRuntime`: the graph is the only agent loop, `AgentState` and the legacy loop are deleted, and nothing runs the old loop beside the new one. Tools, guardrails, handoffs and context pruning belong to the agent, set when you build it, and a conversation is carried by its `ThreadId` instead of by a value you pass back in. Design: `docs/design/typed-agent-runtime/4.13-stage-1-slice-2-the-agent-loop-on-the-graph-runtime.md` (§4.13). This note covers the whole of Stage 1 ([#1326](https://github.com/llm4s/llm4s/issues/1326)), five slices:

- [#1327](https://github.com/llm4s/llm4s/issues/1327), kernel completion: [below](#kernel-completion-1327).
- [#1328](https://github.com/llm4s/llm4s/issues/1328), the agent loop on `GraphRuntime`: this section.
- [#1329](https://github.com/llm4s/llm4s/issues/1329), events and tracing: the bullets this section marks #1329.
- [#1330](https://github.com/llm4s/llm4s/issues/1330), orchestration removed: [below](#orchestration-removed-1330).
- [#1331](https://github.com/llm4s/llm4s/issues/1331), cancellation for non-chat clients: [below](#cancellation-for-non-chat-clients-and-mcp-tool-hints-1331).

```scala
// before
val agent = new Agent(client)
val state = agent.run("query", tools, inputGuardrails = in, outputGuardrails = out, maxSteps = Some(10))

// after
val result = for {
  agent  <- Agent.builder("assistant", client)
              .withTools(tools)
              .withMiddleware(new GuardrailMiddleware(in, out))
              .withMaxSteps(10)
              .build()
  result <- agent.run("query")
} yield result
```

- **`new Agent(client).run(q, tools, ...)` is `Agent.builder(id, client)...build()` then `run(q)`.** `tools`, `systemMessage`, `completionOptions`, `maxSteps` and `handoffs` move to `withTools` (a `ToolRegistry`, `MCPToolRegistry` included, or a `ToolSet`), `withSystemPrompt`, `withCompletionOptions`, `withMaxSteps` and `withHandoffs`. `build()` returns `Result[Agent]` and refuses clashing tool names, an invalid or duplicate handoff id, and `maxSteps < 1`. `run` has the overloads `run(query, config)`, `run(threadId, query)`, `run(threadId, query, config)` and `run(threadId, query, config, history)`; `config` is a `RunConfig` (budgets, deadline).
- **Per-run guardrails are middleware.** `inputGuardrails`/`outputGuardrails` arguments become `.withMiddleware(new GuardrailMiddleware(input, output))` on the builder. A block is no longer an error: the run returns `Right` with `AgentStatus.Blocked(guardrail, reason)` and the thread stays usable (its checkpoint is `Failed`, which `recover` has nothing to continue and the next `run` accepts). An input block stores nothing of that turn's query (a new thread still keeps its imported `history`); an output block removes the whole turn - query, tool calls and results, answer, and any handoff made in it - so `result.messages` is the history from before the turn; `usage` keeps the turn's model calls. Another middleware's `beforeAgent`/`afterAgent` `Left` ends the run the same way but is returned as that `Left`, and a blank query - given, or produced by a `beforeAgent` - is a `ValidationError` with nothing stored. A guardrail that transforms (`PIIMasker`) now applies its transformation. Guardrails - and any run-boundary middleware (`beforeAgent`, `afterAgent`) - on the root agent guard the whole handoff family: they apply to every turn's query and final answer, whichever agent is active, outside the active agent's own. Give them to the root only; model and tool wrappers stay per agent.
- **`continueConversation(state, ...)` is `continueConversation(result, ...)`.** It reads only `result.threadId`; `run(threadId, query)` is the same call by thread. A turn on a `Suspended` result is refused, not layered on parked work.
- **Threads are kept until forgotten.** An `AgentState` was garbage once dropped; a thread lives in the agent's runtime, and one-shot `run` threads stay in the runtime until `forget`. Call `agent.forget(threadId)` (or `GraphRuntime.deleteThread(threadId, config)`) for a conversation you will not continue. `Checkpointer` implementations gain `deleteThread(threadId, token)` (fenced by the claim's token since the [#1700](#durable-checkpointer-contract-1700) follow-up).
- **`runMultiTurn` with `contextWindowConfig` is `runMultiTurn(first, followUps, config)` on an agent built with `new ContextWindowMiddleware(config)`.** Pruning trims what is sent to the model and never what the thread stores; the current turn (latest user message onward) is never pruned - the strategy, `Custom` included, runs only on the history before it, with the budget the turn leaves - the request always starts with a user message (so it may exceed the budget), and the system prompt is outside the budget. `runMultiTurn` stops at the first status that is not `Completed`.
- **`AgentState` fields move to `AgentResult`.**

  | `AgentState` | Now |
  |---|---|
  | `conversation` | `result.messages` (the thread's full history, never a system prompt) |
  | `status` | `result.status` (below) |
  | `logs` | removed; use `withTracing` and the `graph.*` events |
  | `usageSummary` | `result.usage` (accumulated over the thread) |
  | `tools`, `systemMessage`, `completionOptions`, `availableHandoffs`, `initialQuery` | the builder; `activeAgent` is on the result |

- **`AgentStatus` cases are replaced.** `Complete` is `Completed(answer)`; `Failed(error)` is `Left(GraphError...)` (provider errors as `GraphError.NodeFailed(cause)`; the thread stays recoverable with `recover`); `InProgress` and `WaitingForTools` are not exposed; `HandoffRequested` is gone, because a handoff runs inside the same run. New: `Blocked(guardrail, reason)`, `StepLimitReached` and `Suspended(approvals, questions)`, continued with `resume(threadId, answers)` and `result.approve`/`reject`/`edit`/`reply`.
- **`AgentContext` is removed.** `tracing` becomes `Agent.builder(...).withTracing(tracing)`, which emits the runtime's `graph.*` custom events for each run; `debug` and `traceLogPath` are gone. `TraceEvent.AgentStateUpdated` is no longer emitted by the agent; #1329 then replaced it with `TraceEvent.AgentRunEnded`.
- **`Handoff(agent)` is `Handoff(id, builder)`, and `transferSystemMessage` is removed.** The target is an `AgentBuilder`, not a built agent, and its id must equal the handoff id: `Handoff.to("physics", physicsBuilder, "reason")`. A target that must hand back to an agent already in the graph is named by id, `Handoff.toId("triage", "reason")`, so cycles compile. Each agent sends its own system prompt, which is never stored. A self-handoff is refused at build, and a handoff mixed with other tool calls in one message is refused at run time with an error result for every call. `preserveContext = false` (a parameter of `Handoff.to`, `Handoff.of` and `Handoff.toId`) sends the target the last user message before the transfer, then the transfer onwards.
- **`runStep`, `initializeSafe`, `runWithStrategy` and `ToolExecutionStrategy` (on the agent) are removed.** `agent.start(threadId, query)` returns an `AgentRun` (`threadId`, `runId`, `status`, `await()`, `cancel()`) at once; `run` is `start` then `await`; `startRecover` and `startResume` are the same for `recover` and `resume`. The parallel tool calls of one message are bounded by `RunBudgets.maxConcurrency`. `ToolExecutionStrategy` stays in core as a `ToolRegistry` feature; only the agent's use of it is gone. Step-by-step observation is `Agent.stream` or `AgentRun.subscribe` (#1329, below).
- **`runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `AgentEvent` and `AgentStreamingExecutor` are removed** (#1329 replaces them with `Agent.stream`, below). `TraceEvent.AgentStateUpdated` is replaced by `TraceEvent.AgentRunEnded`, and `AgentState#toTraceEvent` is gone.

  | Before | After |
  |---|---|
  | `agent.runWithEvents(query)(onEvent)` | `agent.stream(threadId, query)(listener).flatMap(_.await())` |
  | `runCollectingEvents` | collect in the listener, or replay with `GraphRuntime.subscribe(threadId, afterSeq = 0)` |
  | `AgentEvent.TextDelta(delta)` | `AgentEvents.TextDelta(d)` (`d.text`, `d.attempt`) with `withStreaming()` |
  | `ToolCallStarted`/`ToolCallCompleted`/`ToolCallFailed` | `AgentEvents.ToolCallStarted`, `ToolCallResult` (live), `ToolExecuted` (durable, with outcome) |
  | `HandoffStarted`/`HandoffCompleted` | `AgentEvents.HandedOff` |
  | `InputGuardrail*`/`OutputGuardrail*` | `AgentEvents.GuardrailBlocked` (on a block); the outcome on `AgentStatus.Blocked` |
  | `AgentStarted`/`AgentCompleted`/`AgentFailed`, `StepStarted`/`StepCompleted` | kernel events: `RunStarted`, `RunCompleted`, `RunFailed`; `ModelCallStarted`/`ModelCallCompleted` |
  | `TraceEvent.AgentStateUpdated` | `TraceEvent.AgentRunEnded` |
  | `context.progress(payload)` | `context.progress(name, version, payload)`, or an `EventType` |
  | `ModelStep.next(messages, tools)` | `next(messages, tools, call)` |

  Also: `AgentBuilder.withStreaming()`, `AgentRun.subscribe`, `AgentIO.stream*` and `AgentZ.stream*` are new. Langfuse traces now use the run id as the trace id and the thread id as the session id. `await()` returns once the listener has returned from the run's last event, so whatever it collected is complete. `AgentRunEnded.usage` is the run's own usage, not the thread's: a dashboard that summed the old cumulative figure per run double counted. A slow `AgentIO.stream`/`AgentZ.stream` consumer loses live events and gets a `StreamEvent.LiveGap` with their count; it no longer cancels the run. See the [streaming guide](../guide/agents/streaming.html).
- **Session files: `AgentState.saveToFile`/`loadFromFile` become saved messages plus `history`.** Save `result.messages` (a `Vector[Message]` has a `ReadWriter`) from a completed run, and later `agent.run(newThreadId, query, RunConfig(), history = saved)` on a thread that does not exist. `history` is refused on an existing thread, with a system message in it (prompts belong to the agents), or if it ends mid tool call. `ConversationPersistenceExample` shows it. Old session JSON files are not read.
- **`AgentIO` and `AgentZ` wrap the new `Agent`.** `LLMClientIO.agent(id)(configure)` and `LLMClientZ.agent(id)(configure)` take a function over the `AgentBuilder` (tools now belong to the agent); `run`, `continueConversation`, `recover` and `resume` take a `RunConfig` and return the `AgentResult`. Cancelling the fiber cancels the run. A thrown exception arrives as `GraphError.NodeFailed` carrying the original.
- **`CodeWorker`.** `executeTask` loses `traceLogPath` and returns `Result[AgentResult]`; `WorkspaceSettings.traceLogPath` and `WORKSPACE_TRACE_LOG` are removed. `AssistantAgent` builds its agent once and keeps a thread id; `SessionState` holds the thread id and the last result, and its `consoleConfig` parameter is gone.
- **Graph loop API.** `ToolLoop.build(id, version, root, agents: Vector[LoopAgent])` builds a family of agents (the earlier `ToolLoop.build(id, version, model, tools, middleware)` is gone; `LoopAgent(id, model, tools)` with `withSystemPrompt`, `withMaxSteps`, `withMiddleware` and `withHandoffs` replaces its arguments), and `ModelStep.next` returns a `Completion`, so a `wrapModelCall` middleware's `next` returns `Result[Completion]`.
- **Errors.** Provider, tool and middleware failures are `Left(GraphError...)`; the error content a tool failure gives the model is `{"error": ...}`. A guardrail block, the step limit and a suspension are `Right`.
- **Samples.** `AsyncToolAgentExample` is deleted; `StreamingAgentExample`, `StreamingWithToolsExample` and `EventCollectionExample` are rewritten on `Agent.stream` (#1329); the other agent samples use the builder.

### Kernel completion (#1327)

`GraphBuilder.implement`, `node` and `resumeNode` take `retry: RetryPolicy` and `cache: Option[CachePolicy]`. Both default to off, so a graph that sets neither runs as before. `CompiledGraph.toMermaid` is new. These are source breaks, with no shims:

- **`ToolContext`, `GraphError.ToolFailed`, `ModelRequest` and `ToolCallRequest` have a private constructor and no public `copy`.** Build them with `X(...)` and change them with `withY(...)`.
- **Tool call ids and names are typed.** `ToolContext.toolCallId`, `GraphError.ToolFailed.tool` and `.toolCallId` are the opaque `ToolCallId` and `ToolName` types: read the string with `.value`, and make an id with `ToolCallId(call.id)`. `ToolCallRequest` gains `toolCallId` and `toolName`.
- **`recover` re-runs a failed task under the node's full retry policy.** It used to give the task one more try.

### Cancellation for non-chat clients and MCP tool hints (#1331)

Embedding, reranker, MCP, image clients and the Whisper and Tacotron2 speech engines now return `Left(CancelledError)` when interrupted, as the cloud speech clients already did, with the thread's interrupt flag still set, and never retry it. Before, they reported an error of their own, and some reported a cancelled call as a success. Match `CancelledError` where you matched those errors: an embedding provider's `embed` can now return `Left(CancelledError)` where it returned only an `EmbeddingError`, so a match that narrows its `Left` to `EmbeddingError` needs a case for it. The CHANGELOG entry for #1331 lists every client's change. Source breaks, with no shims:

- **Image generation errors** are `LLMError`s, with renamed cases: see [Image generation errors are `LLMError`s](#image-generation-errors-are-llmerrors).
- **`MCPTransportImpl.sendRequest`, `sendNotification`, `MCPClient.initialize` and `getTools` return `Result`** instead of `Either[String, _]`: read the old string as `error.message`.
- **`ToolHints` moves to `llm4s-core`**: import `org.llm4s.toolapi.ToolHints`, not `org.llm4s.agent.graph.tool.ToolHints`.

`llm4s-mcp` also reads `ToolHints` from a server's tool annotations (`MCPClient.getToolHints`, `MCPToolRegistry.toolHints(name)`), but only for a server configured with `trustAnnotations = true` on its `MCPServerConfig` (default `false`). Any other server reports no hints, so `ToolHints.default` (approval required) applies.

### Orchestration removed (#1330)

`org.llm4s.agent.orchestration` is deleted: `PlanRunner`, `Plan`, `Node`, `Edge`, `TypedAgent`, `Policies`, `OrchestrationError` and `CancellationToken`, with `org.llm4s.types.PlanId` and `org.llm4s.types.AgentId` from `llm4s-core` (the agent's id is `org.llm4s.agent.AgentId`). `PlanRunner` passed `Map[String, Any]` between nodes and cast each node to `TypedAgent[Any, Any]`. A typed graph does the same job with checked handles, checkpoints and recovery. The [multi-agent graph recipe](../examples/cookbook/multi-agent-graph) is a worked replacement.

| Removed | Use instead |
|---|---|
| `TypedAgent[I, O]`, `TypedAgent.fromFunction` and the other factories | a `GraphNode[I]` given to `GraphBuilder.node`; call an `Agent` inside the node for an LLM step |
| `Node`, `Edge`, `Plan`, `Plan.builder` | `GraphBuilder.node` / `edge` / `staticJoin` / `dynamicJoin`, then `compile(entry)(output)` |
| `PlanRunner.execute(plan, inputs, token)` | `GraphRuntime.start(threadId, graph, input).flatMap(_.await())` |
| `PlanRunner(maxConcurrentNodes)` | `RunConfig` with `RunBudgets(maxConcurrency = n)` |
| `Policies.withRetry` | `retry = RetryPolicy(...)` on `GraphBuilder.node` / `implement` |
| `Policies.withTimeout` | `RunBudgets.withTimeout` (the whole run); a node bounds its own calls |
| `Policies.withFallback` | ordinary `Result` code in the node (`primary.orElse(fallback)`) |
| `OrchestrationError` | `GraphError` |
| `CancellationToken` | `RunHandle.cancel()` / `AgentRun.cancel()`, or interrupting the calling thread |
| `org.llm4s.types.PlanId` | `RunId` |
| `org.llm4s.types.AgentId` | `org.llm4s.agent.AgentId` |

```scala
// before
val plan   = Plan.builder.addNode(research).addNode(summary).addEdge(Edge("e", research, summary)).build
val result = PlanRunner().execute(plan, Map("research" -> question), token)   // Future[Result[Map[String, Any]]]

// after
val b        = GraphBuilder("research", "v1")
val findings = StateKey.replace[String]("findings", "")
val digest   = StateKey.replace[String]("digest", "")
val summary = b.node[Unit]("summary", writes = Set(digest)) { (_, state, _) =>
  NodeResult.fromResult(for {
    f    <- state.get(findings)
    turn <- summariser.run(s"Summarise: $f")
    text <- turn.answer.toRight(ValidationError("summary", "no answer"))
  } yield Command.empty.update(digest, text))
}
val research = b.node[String]("research", writes = Set(findings)) { (q, _, _) =>
  NodeResult.fromResult(
    researcher.run(q)
      .flatMap(_.answer.toRight(ValidationError("research", "no answer")))
      .map(f => Command.empty.update(findings, f).goto(summary))
  )
}
val handle = b.compile(research)(_.get(digest)).flatMap(GraphRuntime.inMemory().start(ThreadId("t-1"), _, question))
handle.foreach(_.cancel())   // instead of token.cancel()
```

`Agent.run`, `continueConversation`, `runMultiTurn`, `recover` and `resume` now cancel their turn when the calling thread is interrupted, and return once it has ended (waiting up to 5 seconds for the turn to end), so `recover` can follow at once; a caller already interrupted starts no turn. Cancelling a graph run therefore also cancels the agent turns its nodes are waiting on. Before, the turn kept running after `run` returned `Left(CancelledError)`. A caller that wants the turn to outlive an interrupt uses `start`, `startRecover` or `startResume`, and awaits the `AgentRun` itself. With tracing, the cancelled turn's trace is complete when the call returns. A turn that had already begun committing its outcome when the interrupt came cannot be cancelled: the call returns that outcome (`Right`, `Completed` or `Suspended`), with the interrupt flag still set - test the flag, not only the result, if an interrupt must stop your own code. `run(query)`, whose random thread id a `Left` does not carry, forgets the thread of a turn that failed or was cancelled once it has ended; name the thread (`run(threadId, query)`) to recover such a turn. The Java facade's `JAgent.run`, `continueConversation`, `resume` and `recover` go through these calls, so an interrupted Java caller cancels its turn too; they used to stop only the wait.

## Agent middleware

In 0.5.0 ([#1279](https://github.com/llm4s/llm4s/issues/1279)). The graph tool loop
takes a stack of `AgentMiddleware` in place of a `ToolCallPolicy`: one ordered extension point with
`beforeAgent`, `afterAgent`, `wrapModelCall` and `wrapToolCall` hooks, for approval, guardrails,
logging, retry and rate limits. The graph runtime is Experimental, so these are source breaks with
no shims. Design: `docs/design/typed-agent-runtime/4.08-stage-0-prototype-agent-middleware.md` (§4.8). (The `ToolLoop.build` signature shown below was generalised by #1328: see [Stage 1 migration](#stage-1-migration-agent-runtime).)

- **`ToolCallPolicy` and `PolicyDecision` are removed, and `ToolLoop.build` loses `policy`.** It
  takes `middleware: Seq[AgentMiddleware] = Nil` instead. A policy becomes an `AgentMiddleware`
  overriding `wrapToolCall`: `Allow` is `next()`, `Deny(reason)` is
  `ToolOutcome.Error(s"Denied: $reason")`, and `RequireApproval(reason)` is
  `if context.approved then next() else ToolOutcome.NeedsApproval(reason)` - or use
  `ApprovalMiddleware`:

  ```scala
  // before
  val policy: ToolCallPolicy = call =>
    call.name match
      case "drop_table" => PolicyDecision.Deny("never allowed")
      case "deploy"     => PolicyDecision.RequireApproval("deploys are irreversible")
      case _            => PolicyDecision.Allow
  ToolLoop.build("assistant", "v1", model, tools, policy)

  // after
  val guard = new AgentMiddleware:
    val id = MiddlewareId("guard")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      request.call.name match
        case "drop_table"                  => ToolOutcome.Error("Denied: never allowed")
        case "deploy" if !context.approved => ToolOutcome.NeedsApproval("deploys are irreversible")
        case _                             => next()
  ToolLoop.build("assistant", "v1", model, tools, Seq(guard))

  // or, for approval alone
  ToolLoop.build("assistant", "v1", model, tools, Seq(ApprovalMiddleware.unlessReadOnly))
  ```

- **`ApprovalSource.Policy` becomes `ApprovalSource.Middleware(id)`**, naming the middleware that
  asked; a tool's own request is still `ApprovalSource.Tool`.
- **`Approve` now re-runs the chain.** It used to skip the policy and run the tool; it now runs the
  whole middleware chain again, from the outermost wrapper, with `ToolContext.approved = true`, as
  `Edit` does with the new arguments. A deny rule that depends only on the call refuses the same
  calls as before; a wrapper that asks for approval must pass when `context.approved` is set, or
  the call becomes an error result (`asked for approval again`).
- **Guardrails in the graph loop apply their transformations.** `GuardrailMiddleware(input, output)`
  runs input guardrails in `beforeAgent` and output guardrails in `afterAgent`, each on the value the
  previous one returned, so a guardrail that rewrites its input (`PIIMasker`, say) changes what the
  model sees and what the run answers. The legacy `Agent` validates every guardrail against the
  original value and keeps it, so it never applied a transformation; it is unchanged. A `Block`
  fails the run with the error `CompositeGuardrail.all` reports.

## Agent tool contract and handoff ids

In 0.5.0 ([#1278](https://github.com/llm4s/llm4s/issues/1278)). The graph tool loop
runs `AgentTool`s, whose arguments are validated against their schema (rendered non-strict, so
optional fields may be omitted) before any middleware or the tool runs, and legacy handoffs take an explicit id. The graph runtime is
Experimental, so these are source breaks with no shims. Design:
`docs/design/typed-agent-runtime/4.07-stage-0-prototype-agent-tool-contract.md` (§4.7).

(Since #1328, `Handoff` targets are builders and `ToolLoop.build` takes a family of `LoopAgent`s: see
[Stage 1 migration](#stage-1-migration-agent-runtime).)

- **`LoopTool` is `AgentTool[A]`.** A tool now has an `AgentToolSpec[A]` - name, description and
  a core `SchemaDefinition[A]`, with a `ReadWriter[A]` for the arguments - and receives them
  decoded, with a `ToolContext` (run, call id, thread state, `approved`):

  ```scala
  // before
  val search = LoopTool("search")((call, approved) => ToolOutcome.Completed(run(call.arguments)))

  // after
  final case class SearchArgs(query: String) derives ReadWriter
  val schema = Schema.`object`[SearchArgs]("Search arguments").withRequiredField("query", Schema.string("Query"))
  val spec   = AgentToolSpec[SearchArgs]("search", "Search the index", schema)
  val search = AgentTool(spec)((args, context) => ToolOutcome.success(ToolContent.Json(ujson.Str(run(args.query)))))
  ```

  `LoopTool.fromToolFunction(f)` becomes `AgentTool.fromToolFunction(f)`; its arguments are now
  validated against the function's schema too.
- **`toolloop.ToolOutcome` is `tool.ToolOutcome`.** `Completed(content: String)` becomes
  `Success(content: ujson.Value, update)` (a `ujson.Str` is recorded as the string itself);
  `Failed(message)` becomes `Error(message)`; `NeedsApproval` is unchanged. `Ask` and `Fatal` are
  new: `Fatal(error)` fails the run with `GraphError.ToolFailed`, reported inside
  `GraphError.NodeFailed`, and the run is recoverable. A thrown exception is still an error result.
- **Approval now arrives in the context.** `LoopTool`'s `approved` parameter is
  `context.approved`.
- **State updates are declared.** A tool's `Success` update may touch only the keys in its
  `writes`; anything else fails the run. `ToolLoop.build` refuses a tool that declares
  `ToolLoop.results` or `Messages.key`.
- **`ToolLoop.build` takes a `ToolSet`.** Build it with `ToolSet.of(tools*)`, which returns
  `Left(ValidationError)` for an invalid name (`[a-zA-Z0-9_-]{1,64}`), a duplicate name, an
  argument schema that is not an object, or a schema keyword the validator cannot check. `AgentToolSpec.apply` throws
  `IllegalArgumentException` for an invalid name.
- **`ModelStep.next` takes the tool set.** `next(messages)` becomes `next(messages, tools)`, and
  `ModelStep.fromClient(client, options)` replaces `options.tools` with `tools.toolFunctions`, so
  pass the tools to `ToolLoop.build`, not in `CompletionOptions`.
- **Invalid arguments never reach the tool.** Arguments that break the schema, fail to decode or
  fail `withValidation` become the error result `Invalid arguments for '<tool>': ...`. Edited
  approval arguments are checked again, and a middleware's tool wrapper can still deny them.
- **Handoffs take an id.** `Handoff(agent, ...)` becomes `Handoff(id, agent, ...)`, and
  `Handoff.to(agent)` / `Handoff.to(agent, reason)` become `Handoff.to(id, agent)` /
  `Handoff.to(id, agent, reason)`, which throw `IllegalArgumentException` for an invalid id;
  `Handoff.of(id, agent, reason)` returns a `Result`. An id matches `[a-zA-Z0-9_-]{1,52}` and is
  unique within one run's handoffs; an agent run given an invalid or duplicate id fails with
  `ValidationError` before any model call. The handoff tool is `handoff_to_<id>` rather than
  `handoff_to_agent_<hash>`, so a conversation stored with the old name does not match a handoff.

  ```scala
  // before
  agent.run(query, tools, handoffs = Seq(Handoff.to(physicsAgent, "Physics expertise required")))

  // after
  agent.run(query, tools, handoffs = Seq(Handoff.to("physics", physicsAgent, "Physics expertise required")))
  ```

## Run API for graph runs

In 0.5.0 ([#1277](https://github.com/llm4s/llm4s/issues/1277)). `GraphRuntime.start`,
`recover` and `resume` return a `RunHandle` once the thread is claimed, and the run executes on a
thread the runtime owns. The graph runtime is Experimental, so these are source breaks with no
shims. Design: `docs/design/typed-agent-runtime/4.06-stage-0-prototype-run-api-and-event-dispatch.md` (§4.6).

- **`NodeContext` is `RunContext`.** `context.taskId`, `context.nodeId` and `context.superstep`
  become `context.position.taskId`, `.nodeId` and `.superstep`; `emit` and `progress` are
  unchanged, and `isCancelled` is new. Clients and tools stay captured by node closures; resolve
  per-tenant or per-thread resources from `context.config.tenantId` or `context.position`.
- **Limits are per run.** `compile(entry, maxSupersteps)` becomes `compile(entry)`, and
  `ToolLoop.build` drops `maxSupersteps`. Pass
  `RunConfig(budgets = RunBudgets(maxSupersteps = ..., timeout = ..., maxConcurrency = ...))` to
  each run; `RunBudgets.of` validates untrusted values as a `Result`, while `apply` and the
  `with*` setters throw `IllegalArgumentException` for a non-positive value.
- **`CompiledGraph.run(input)` is removed.** Use
  `GraphRuntime.inMemory().start(threadId, graph, input).flatMap(_.await())`.
- **`step(execution)` is `step(threadId, execution, config)`.** It runs at most
  `config.budgets.maxConcurrency` tasks at a time and checks no superstep limit.
- **`start`/`recover`/`resume(..., runId, durability)` become `(..., config, durability)`** and return
  `Result[RunHandle[O]]`; the run id is `config.runId`. Call `handle.await()` for the
  `RunResult`. Cancel with `handle.cancel()`: interrupting the caller no longer cancels the run,
  and interrupting a thread blocked in `await` returns `Left(CancelledError)` while the run
  continues. A cancel that arrives once the run has begun committing its outcome - completed,
  suspended or failed - is ignored, and the run ends with that outcome.
- **`recover` and `resume` take the thread first, as `start` does.** `recover(graph, threadId, ...)`
  becomes `recover(threadId, graph, ...)`, and `resume(graph, threadId, answers, ...)` becomes
  `resume(threadId, graph, answers, ...)`. Both arguments have different types, so the compiler
  flags every call to swap.
- **Subscribers have their own thread.** `subscribe` gains `capacity` (default 1024, at least 2).
  Listeners run on the subscription's dispatcher thread, not a task or committing thread. A
  listener that falls behind by more than `capacity` durable events is disconnected; a listener
  that throws is disconnected rather than ignored. `StreamEvent` gains `LiveGap(dropped)` and
  `Disconnected(lastSeq, reason)`; resubscribe with `afterSeq = lastSeq` to continue without a gap
  (`TracingSubscriber.attach` included: a lagging tracer is not re-attached for you). A
  subscription belongs to the thread, not one run, and keeps a parked dispatcher thread until
  `cancel()`; cancel subscriptions you no longer need.
- **Tenants are checked.** A run whose `RunConfig.tenantId` differs from the one on the thread's
  latest checkpoint is refused with `GraphError.TenantMismatch(threadId, requested)`, which names
  only the caller's tenant, never the owner's; `None` and `Some` differ. The check
  comes first: a wrong-tenant call gets `TenantMismatch` rather than `IncompleteRun`,
  `PendingInterrupts`, `NothingToRecover`, `NotSuspended` or `ThreadBusy`.
  Checkpoints move to format 3, and earlier checkpoints read as having no tenant.
- **`GraphError` is classified per case.** It extends `LLMError` rather than
  `NonRecoverableError`; every case is still a `NonRecoverableError` except the new
  `DeadlineExceeded`, which is a `RecoverableError` - `recover` it with a new budget. Code that
  relied on `GraphError <: NonRecoverableError` should match the case.
- **New cases break exhaustive matches.** `GraphError.DeadlineExceeded`, `TenantMismatch` and
  `RunCrashed`, `RunEvent.RunTimedOut`, and `StreamEvent.LiveGap` and `Disconnected` are new.
  `RunStarted` is now a case class, and it, `RunRecovered` and `RunResumed` gain `tenantId` and
  `principal`; events in the old encoding still read.

## Provider configs: build with `apply`, change with `with*`

In 0.5.0 ([#1388](https://github.com/llm4s/llm4s/issues/1388)). `OpenAIConfig`,
`AnthropicConfig` and `OllamaConfig` follow the growth-prone pattern (pass 5 below), so a new field
no longer breaks Java and Kotlin callers. The constructor and `copy` are private:

- **Scala**: `OpenAIConfig(...)` with the full field list, positional or named, still compiles.
  Replace `config.copy(baseUrl = url)` with `config.withBaseUrl(url)`; each field has a setter
  (`withApiKey`, `withModel`, `withOrganization`, `withContextWindow`, ...), and `OpenAIConfig`'s
  `Option` fields take the value or an `Option`.
- **Java and Kotlin**: call the companion's short `apply` and chain setters instead of the
  constructor - `OpenAIConfig.apply(apiKey, "gpt-4o").withOrganization("org-1")`,
  `AnthropicConfig.apply(apiKey, model)`, `OllamaConfig.apply(model, baseUrl)`. It takes the default
  base URL and a context window from the model name; `fromValues` still consults the bundled model
  catalogue.

## Image generation errors are `LLMError`s

In 0.5.0 ([#1331](https://github.com/llm4s/llm4s/issues/1331)). `llm4s-image`'s
generation clients return `Either[LLMError, _]`, so an interrupted call can return
`CancelledError`, and `ImageGenerationError` extends `LLMError`. Source breaks, with no shims:

- **Five cases are renamed** so they no longer share a name with an `org.llm4s.error` type. Both are
  `LLMError`s, so a `match` that imported the wrong one would compile and never fire:

  | Before (`org.llm4s.imagegeneration`) | After |
  |---|---|
  | `AuthenticationError` | `ImageAuthenticationError` |
  | `RateLimitError` | `ImageRateLimitError` |
  | `ServiceError` | `ImageServiceError` |
  | `ValidationError` | `ImageValidationError` |
  | `UnknownError` | `ImageUnknownError` |

- **`ImageServiceError(message, statusCode)`**: the second field was `code: Int`, which clashed with
  `LLMError.code: Option[String]`. It is a sealed type now (`TransientImageServiceError` for `0`, `408`,
  `429` and `5xx`, `RejectedImageServiceError` otherwise); build and match it through
  `ImageServiceError(message, status)` as before.
- **A match on a client's result** needs a case for other `LLMError`s, `CancelledError` among them.

## Cancellation by interrupt

In 0.5.0 ([#1270](https://github.com/llm4s/llm4s/issues/1270)). An interrupted call
now returns `Left(CancelledError)` with the thread's interrupt flag still set. Code that matched
`ExecutionError`, `TimeoutError` or `SimpleError` to detect an interrupted call should match
`CancelledError` instead, and `ToolCallError.Cancelled` for a tool call. `CancelledError` is
non-recoverable and is never retried. Chat clients no longer throw `InterruptedException` or report
`UnknownError` for an interrupt.

- **New cases break exhaustive matches.** `ErrorKind.Cancelled` (metric label `cancelled`),
  `RunEvent.RunCancelled` and `ToolCallError.Cancelled` are new; a `match` over `ErrorKind`,
  `RunEvent` or `ToolCallError` needs a case for each.
- **Mid-stream, `NetworkError` becomes `CancelledError`.** A stream read cut off by an interrupt
  used to be a recoverable `NetworkError`; code that retried it should stop on `CancelledError`.
- **`DefaultErrorMapper` and `Try(...).toResult` classify cancellations.** An exception mapped while
  the thread is interrupted, or caused by `InterruptedException` or `ClosedByInterruptException`,
  becomes `CancelledError` rather than `UnknownError` or `NetworkError`. A bare
  `InterruptedIOException` - OkHttp's call timeout is one - stays a timeout. Mapping never sets the
  interrupt flag; code that catches an `InterruptedException` itself must restore it.
- **`ReliableClient`** returns `CancelledError`, not the local `RateLimitError`, when interrupted
  while waiting for a local rate-limit token.
- **Graph supersteps run concurrently.** The default executor ran a superstep's tasks one after
  another; it now runs them concurrently on virtual threads, at most 16 at a time. Node code must be
  thread-safe, a task does not inherit the caller's `ThreadLocal` or MDC context, and in `Sync`
  durability, durable events and live progress are delivered on the task threads, not the caller's.
  Since [#1277](https://github.com/llm4s/llm4s/issues/1277) they are delivered on each
  subscription's dispatcher thread instead (see above).

## Pre-baseline API cleanup, pass 8

In 0.5.0; continues pass 7 below. It closes the last gaps in the frozen modules'
public API found by checking the [re-audit](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540)
against `main`.

- **`llm4s-agent`'s console UI is internal.** `ConsoleInterface`, `ConsoleConfig` (and its
  `StyleConfig`) and `MessageType` are `private[assistant]`: `ConsoleConfig`'s colours were fansi
  `Attrs` and `MessageType` carried a public `cats.Show`, which would have frozen both libraries
  into `llm4s-agent`'s API. `AssistantAgent` loses its `consoleConfig` parameter and the
  four-argument compatibility constructor; construct it with named arguments:

  ```scala
  new AssistantAgent(client, tools, sessionDir = "./sessions", agentContext = AgentContext.Default)
  ```

- `SessionState.localDateTimeRW`, a public implicit upickle codec for `java.time.LocalDateTime`
  that any `import SessionState._` picked up, is `private[assistant]`.
- `org.llm4s.llmconnect.utils.SimilarityUtils` is `private[llm4s]`: only core's caching client
  and `llm4s-rag` use it. Applications needing cosine similarity can compute it directly.

## Pre-baseline API cleanup, pass 7

In 0.5.0; continues pass 6 below, which typed the times a caller supplies. This pass
types the times the library **reports**: an elapsed time is a `FiniteDuration`, a point in time an
`Instant`, and the unit leaves the name. Every JSON, trace and wire format keeps its keys and
millisecond values (`duration_ms`, `durationMs`, `executionTimeMs`, ...).

```scala
// before
case AgentCompleted(state, steps, durationMs, _) => println(s"Done in ${durationMs}ms")
// after
case AgentCompleted(state, steps, duration, _) => println(s"Done in ${duration.toMillis}ms")
```

Check string interpolation in particular: `s"${duration}ms"` still compiles, but prints
`150 millisecondsms`.

| Module | Before | After |
|---|---|---|
| `llm4s-core` | `TraceEvent.ToolExecuted.duration: Long`, `RAGOperationCompleted.durationMs`, `ImageGenerationCompleted.durationMs`, `Tracing.traceRAGOperation(durationMs)` | `duration: FiniteDuration` |
| | `ProviderExchange.durationMs: Long` | `duration: FiniteDuration` |
| | `RateLimitError.requestsRemaining`, `RateLimitError.resetTime` | removed: no constructor could set them, so they were always `None` |
| `llm4s-agent` | `AgentEvent.ToolCallCompleted.durationMs`, `AgentCompleted.durationMs`, `AgentEvent.toolCompleted`/`agentCompleted`'s `durationMs` | `duration: FiniteDuration` |
| `llm4s-agent-tools` | `ShellResult.executionTimeMs`, `HTTPResult.responseTimeMs` | `executionTime`, `responseTime` (the tool's JSON keeps the `...Ms` keys) |
| `llm4s-rag` | `TimingInfo.durationMs`, `durationSeconds`, `avgPerItemMs` | `duration`, `avgPerItem: Option[FiniteDuration]` |
| | `ExperimentResult.totalTimeMs`/`totalTimeSeconds` | `totalTime: FiniteDuration` |
| | `BenchmarkResults.startTime`/`endTime: Long` (epoch ms), `totalDurationMs`/`totalDurationSeconds` | `Instant`s, `totalDuration: FiniteDuration` |
| `llm4s-image` | `ServiceStatus.averageGenerationTime: Option[Long]` (ms) | `Option[FiniteDuration]` |
| `llm4s-speech` | `Transcription.processingTimeMs: Option[Long]` | `processingTime: Option[FiniteDuration]` |
| workspace | `ExecuteCommandResponse.durationMs`, `CommandCompletedMessage.durationMs` | `duration: FiniteDuration` (the protocol keeps `durationMs`) |

## Pre-baseline API cleanup, pass 6

In 0.5.0; continues pass 5 below. A time the caller supplies is typed: a duration is
a `scala.concurrent.duration.FiniteDuration` and a point in time a `java.time.Instant`, never a
raw `Int`/`Long` whose unit lives in its name or its Scaladoc. Names lose their unit suffix
(`timeoutMs` becomes `timeout`). Defaults are unchanged, and so are the wire formats: HOCON keys,
JSON sent to Exa and the workspace runner, and HTTP headers.

```scala
import scala.concurrent.duration.*

// before
Left(RateLimitError("openai", 1000L))              // milliseconds, documented as seconds
CrawlerConfig(delayMs = 500, timeoutMs = 30000)
// after
Left(RateLimitError("openai", 1.second))
CrawlerConfig(delay = 500.millis, timeout = 30.seconds)
```

### `llm4s-core` errors, retry and reliability

- `RecoverableError.retryDelay`, `RateLimitError.retryAfter`/`retryDelay` and
  `ServiceError.retryDelay` are `Option[FiniteDuration]`; `RateLimitError(provider, retryAfter)`
  takes a `FiniteDuration` and the `RateLimitError(message, retryAfter, provider)` extractor yields
  one. `RateLimitError.resetTime` is an `Option[Instant]`. The fallback delay is named
  `RateLimitError.DefaultRetryDelay` (30 seconds, as before).
- `TimeoutError.timeoutDuration`, `ReliabilityConfig.deadline`/`withDeadline`,
  `CircuitBreakerConfig.recoveryTimeout`/`withRecoveryTimeout`, every `RetryPolicy` delay
  (`exponentialBackoff`, `linearBackoff`, `fixedDelay`, `custom`'s function, `delayFor`'s result)
  and `ErrorRecovery`'s `recoveryTimeout` were `Duration` and are `FiniteDuration`: an infinite
  delay or deadline could not be slept or added to a clock. Code passing `5.seconds` is unchanged.
- `ReliableClient` and `ErrorRecovery.CircuitBreaker` take `clock: () => Instant` (was
  milliseconds since the epoch); `ReliableClient`'s `sleep`, `ErrorRecovery`'s and
  `LLMClientRetry`'s `sleepFn` take a `FiniteDuration` (was a `Long` of milliseconds).
  `ErrorRecovery.recoverWithBackoff`'s `baseDelay` is a `FiniteDuration`.

### `llm4s-agent`

`OrchestrationError.AgentTimeoutError` carries `timeout: FiniteDuration` (was `timeoutMs: Long`).

### Beta modules

| Module | Before | After |
|---|---|---|
| `llm4s-agent-tools` | `ExaSearchToolConfig`/`BraveSearchToolConfig`/`DuckDuckGoSearchToolConfig` `timeoutMs: Int`, `ShellConfig.timeoutMs: Long`, `HttpConfig.timeoutMs: Int` | `timeout` |
| | `ExaSearchToolConfig.livecrawlTimeout: Option[Int]` (ms) | `Option[FiniteDuration]`, still sent to Exa in ms |
| `llm4s-rag` | `CrawlerConfig.delayMs`/`timeoutMs`, `withDelay(ms)`/`withTimeout(ms)` (also on `WebCrawlerLoader`), `UrlLoader.timeoutMs`/`withTimeout(ms)` | `delay`/`timeout`, `withDelay(FiniteDuration)`/`withTimeout(FiniteDuration)` |
| | `RobotsTxtParser.isAllowed`/`getRules` `timeoutMs`, `RobotsTxt.crawlDelay: Option[Int]` (s) | `timeout`, `Option[FiniteDuration]` (fractional `Crawl-delay` values are now kept) |
| | `EvaluatorOptions.timeoutMs`, `ChunkingUtils` `windowSeconds`/`clipSeconds`, `RateLimitedLogger` `throttleSeconds` | `timeout`, `window`/`clip`, `throttle` |
| | `HikariDefaults.CONNECTION_TIMEOUT_MS`/`IDLE_TIMEOUT_MS`/`MAX_LIFETIME_MS` | `ConnectionTimeout`/`IdleTimeout`/`MaxLifetime` |
| `llm4s-mcp` | `MCPServerConfig`/transport `timeout: Duration`, `MCPToolRegistry` `cacheTTL: Duration`, `MCPServer.stop(delay: Int)` | `FiniteDuration` |
| | `StdioTransportImpl` `startupTimeoutMs: Int` | `startupTimeout` |
| `llm4s-image` | `ImageGenerationConfig.timeout: Int` (ms), the `imagegeneration.provider.HttpClient` methods' `timeout: Int` | `FiniteDuration` |
| | OpenAI/Anthropic vision configs' `connectTimeoutSeconds`/`requestTimeoutSeconds` | `connectTimeout`/`requestTimeout` |
| workspace | `executeCommand` `timeout: Option[Int]` (s), `WorkspaceSandboxConfig.defaultCommandTimeoutSeconds` | `Option[FiniteDuration]`, `defaultCommandTimeout`; the JSON still carries whole seconds under the old keys |

**Test doubles** of `imagegeneration.provider.HttpClient` type the timeout as `FiniteDuration`; a
ScalaMock `onCall` lambda typed `_: Int` compiles but fails at runtime.

## Pre-baseline API cleanup, pass 5

In 0.5.0; continues pass 4 below. It settles the provider-author SPI's API quality
before the binary-compatibility baseline.

### Growth-prone data types: construct with named arguments, change with `with*`

A case class cannot gain a field without breaking binary compatibility - its constructor,
`apply` and `copy` all change - so the types the library will plausibly extend now have a private
constructor, a public companion `apply` carrying the defaults, and `with*` setters:
`CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`, `ModelCapabilities`,
`ModelMetadata`, `ProviderConfigSpec`, `EmbeddingConfigSpec`, `ProviderFeatures`,
`NamedProviderConfig`, `ReliabilityConfig`, `CircuitBreakerConfig`, `RateLimitConfig`,
`ContextConfig`.

Construction is unchanged. `.copy(...)` is no longer available outside the type:

```scala
// before
val opts = CompletionOptions(temperature = 0.2).copy(maxTokens = Some(500))

// after
val opts = CompletionOptions(temperature = 0.2).withMaxTokens(500)
```

Every field has a `withX`. An `Option` field's setter takes either the value or an `Option`
(`withMaxTokens(500)`, `withMaxTokens(None)`). Match these types by name (`c.usage`), not by
position: a positional pattern breaks when a field is added.

### `Llm4sHttpClient` returns `Result` and takes `FiniteDuration`

Every request method (`get`, `post`, `postBytes`, `postMultipart`, `put`, `delete`, `postRaw`,
`postStream`) returns `Result[...]` and never throws for a transport failure: a timeout is a
`TimeoutError`, a connection or I/O failure a `NetworkError`, an invalid URL, header or timeout a
`ValidationError`, an interruption an `ExecutionError` (with the interrupt flag restored). A
non-2xx status is still a `Right`. `getResult` is removed - `get` is now a `Result` itself.

```scala
// before
val response = Try(http.post(url, headers, body, timeout = 120000)).toResult
// after
import scala.concurrent.duration.*
val response: Result[HttpResponse] = http.post(url, headers, body, timeout = 120.seconds)
```

`HttpRawResponse` and `StreamingHttpResponse` carry `headers`, and every response type has a
case-insensitive `header(name)`. Pass the headers to `HttpErrorMapper.mapHttpError(status, body,
provider, headers)` so a 429's `Retry-After` (seconds or an HTTP date) becomes the
`RateLimitError`'s delay; every built-in provider does.

**Test doubles that implement the trait** return `Result[...]` and take `FiniteDuration`; return a
`Left` to simulate a failure instead of throwing. With ScalaMock, `.returns(resp)` becomes
`.returns(Right(resp))` and `.throws(e)` becomes `.returns(Left(error))`; type the timeout in
`onCall`/`where` lambdas as `FiniteDuration` - a lambda typed `_: Int` still compiles but fails at
runtime.

### `StreamingAccumulator`

| Before | After |
|---|---|
| `new StreamingAccumulator()` | `StreamingAccumulator.create()` (the class is `final`) |
| `getCurrentContent`, `getCurrentThinking`, `getCurrentToolCalls` | `currentContent`, `currentThinking`, `currentToolCalls` |
| `snapshot()`, `AccumulatorSnapshot` | `toCompletion(created)` |
| `StreamingAccumulator.withInitialState(...)` | `create()`, then `addChunk` / `updateTokens` |

### `RequestTransformer` and `TransformationResult`

- `TransformationResult.warnings` is removed: nothing ever filled it.
- `TransformationResult.transform(modelId, options, messages, transformer, dropUnsupported = true)`
  - the required `transformer` now comes before the defaulted flag.
- `RequestTransformer#getDisallowedParams` is now `disallowedParams`.

### `llm4s-provider-testkit`

New, Beta. A provider module's spec mixes in `org.llm4s.testkit.ProviderModuleChecks`; see
[Writing a provider](../guide/writing-a-provider#testing). In this repository,
`CredentialsRoundTrip` and `LocalProviderTestServer` moved from core's test sources to
`org.llm4s.testkit`.

### Internal now

`Llm4sConfig.providerFrom(source)` and `apiKeySourcesFrom(source)` took a pureconfig
`ConfigSource`, which is not part of llm4s's API; they are `private[llm4s]`. Load configuration
with `Llm4sConfig.provider(name)` / `defaultProvider()` / `apiKeySources()`.

## Pre-baseline API cleanup, pass 4

In 0.5.0; continues pass 3 below. Vendor fields leave `NamedProviderConfig`.

**Config files: no change.** `organization`, `endpoint`, `apiVersion`, `contextWindow` and
`reserveCompletion` keep their names in `llm4s.providers.<name>` sections. What changed is who owns
them: each is now a provider-specific key that only its provider declares.

| Key | Declared by |
|---|---|
| `organization` | `openai`, `requesty`, `openrouter` |
| `endpoint` (required), `apiVersion` (default `V2025_01_01_PREVIEW`) | `azure` |
| `contextWindow`, `reserveCompletion` | `openai-compatible` |

In a section for any other provider these keys used to be read and silently ignored; they are now
reported once as unknown keys, with a warning, and dropped - delete them. Vertex AI's deprecated
`endpoint`/`organization` aliases for `project`/`location` work exactly as before. A non-numeric
`contextWindow`/`reserveCompletion` is now reported when the section is resolved, naming the key
(`llm4s.providers.<name>.contextWindow must be a positive whole number, got '...'`), rather than as a
type error while reading the block.

**Scala callers:**

1. `NamedProviderConfig` no longer has `organization`, `endpoint`, `apiVersion`, `contextWindow` or
   `reserveCompletion`. Read the validated value from the section's extras:
   `config.organization` → `config.extra("organization")` (or `OpenAIConfig.OrganizationKey`),
   `config.endpoint` → `config.extra(AzureProvider.EndpointKey)`,
   `config.apiVersion` → `config.extra(AzureProvider.ApiVersionKey)`,
   `config.contextWindow` → `config.extra(OpenAICompatibleProvider.ContextWindowKey).map(_.toInt)`
   (likewise `ReserveCompletionKey`). Values are strings.
2. Code constructing `NamedProviderConfig(...)` drops those arguments; pass them in
   `extras = Map("endpoint" -> ..., ...)` if the descriptor needs them. Positional calls
   `NamedProviderConfig(id, model, baseUrl, apiKey, org, endpoint, apiVersion)` become
   `NamedProviderConfig(id, model, baseUrl, apiKey)`.
3. `ProviderConfigSpec(requiresEndpoint = true, endpointDescription = "...")` →
   `ProviderConfigSpec(extras = Seq(ProviderConfigKey.required("endpoint", "...")))`.
   `ProviderConfigSpec.BuiltinKeys` is now `provider, model, baseUrl, apiKey, headers`, and
   `BuiltinAliasKeys` is `baseUrl, apiKey` - a `deprecatedAliases` entry naming one of the moved keys
   still works, resolved from the section's extras.
4. `ProviderModelListers.openAICompatible` is now in `llm4s-openai-compatible` (package
   `org.llm4s.config` unchanged): a provider module calling it adds that dependency. It no longer
   sends `OpenAI-Organization` from the section; pass
   `sectionHeaders = ProviderModelListers.openAIOrganizationHeader` (and declare
   `OpenAIConfig.OrganizationConfigKey`) to keep it, or any `NamedProviderConfig => Map[String, String]`
   to derive other headers. `ProviderModelLister` and `DiscoveredModel` stay in `llm4s-core`.

## Pre-baseline API cleanup, pass 3

In 0.5.0; continues pass 2 below.

### `llmconnect.middleware`, `ReliableProviders` and `ReliabilitySyntax` are removed

The middleware package had no users outside its own tests and duplicated `caching` and the
metrics every provider client already records. `LLMClient` is a trait, so a decorator is a class
that implements it and delegates. `ReliableProviders.wrap` and `.withReliability(...)` were
shorthand for one constructor:

```scala
// before
val reliable = ReliableProviders.wrap(client, "openai", config)
val other    = client.withReliability("openai")

// after
val reliable = ReliableClient(client, "openai", config)
val other    = ReliableClient(client, "openai", ReliabilityConfig.default)
```

(The constructor has since become private: this is the companion's `apply`, see
[`ReliableClient` has a private constructor](#reliableclient-has-a-private-constructor-2797).)

`ReliableClient`'s companion factories (`ReliableClient(client)`, `ReliableClient(client, config)`,
`ReliableClient(client, config, metrics)`, `ReliableClient.withProviderName`) go too: three of them
guessed the provider name from the client's class name. Use `ReliableClient(client, name, config)`. For a client built
from config, the name is `providerConfig.providerId.asString`.

Behaviour fix: **`ReliableClient` now applies `ReliabilityConfig.rateLimit` itself**, before every
attempt, retries included. Before, only `ReliableProviders.wrap` honoured it, so a
`ReliableClient` built directly with rate limiting enabled was not rate limited.

### OpenAI's model rules leave `RequestTransformer`

`RequestTransformer.default` now applies only the registry's capabilities. The o-series
constraints (no system message, no native streaming, temperature 1, no sampling penalties) and
the `max_completion_tokens` rule for o-series and gpt-5 moved into `llm4s-openai`, which is the
only client that needs them; the Anthropic and Gemini clients no longer apply them to models
named like OpenAI's.

| Removed | Use instead |
|---|---|
| `RequestTransformer#requiresMaxCompletionTokens`, `TransformationResult.requiresMaxCompletionTokens` | none in core; it is an OpenAI wire parameter |
| `DefaultRequestTransformer` (now package-private) | `RequestTransformer.default(service)` or `withOverrides(...)` |
| (new) | `RequestTransformer.adjusted(service)((modelId, capabilities) => ...)`, for a provider module's own rules on top of the registry |

### Provider-author SPI

The plumbing provider modules build on is a public, frozen SPI; see
[Writing a provider](../guide/writing-a-provider). Two OpenAI-format helpers moved to
`llm4s-openai-compatible`, with unchanged packages: `org.llm4s.llmconnect.model.ResponseFormatMapper`
and `org.llm4s.llmconnect.serialization.{ToolCallDeserializer, StandardToolCallDeserializer}`.
`ProviderResultOps` (`tapRight` / `tapLeft`) is now `private[llm4s]`.

## Pre-baseline API cleanup, pass 2

In 0.5.0; continues pass 1 below.

| Removed | Use instead |
|---|---|
| `ToolRegistry#getToolDefinitionsSafe(provider)` | `getOpenAITools()`. It returned the same JSON for every provider it accepted and failed for the rest; every client takes this format |
| `CancellationToken#cancellationFuture`, `cachedCancellationFuture` | `whenCancelled`, a shared `Future[Unit]` that *succeeds* on cancel: `token.whenCancelled.map(_ => Left(myError))` |
| `CancellationToken#throwIfCancelled()`, `orchestration.CancellationException` | `if (token.isCancelled) Left(...)` |
| `ManagedResource.fileInputStream`, `dataOutputStream`, `byteArrayInputStream` | `ManagedResource.fromTry(() => Try(new ...), s => Try(s.close()))`, or `scala.util.Using` |
| `ManagedResource` `map` / `flatMap` (`ManagedResourceOps`) | none: they never released the underlying resource. Nest `use` calls instead |

Behaviour fix: a `PlanRunner` node cancelled while running now fails the plan with
`OrchestrationError.PlanExecutionError("Node <id> cancelled", ...)`. It used to surface as a
`NodeExecutionError` wrapping `CancellationException`, because the cancellation future only ever
failed and the mapping to the cancelled error never ran.

These utilities moved to the one module that uses them. Package names are unchanged, so code that
already depends on that module needs nothing; code that used them through `llm4s-core` alone adds
the module:

| Utility | Now in |
|---|---|
| `org.llm4s.util.SqlIdentifier`, `org.llm4s.llmconnect.utils.ChunkingUtils` | `llm4s-rag` |
| `org.llm4s.resource.ManagedResource` | `llm4s-speech` |
| `org.llm4s.util.LiftToResult` | `llm4s-observability` |

## Pre-baseline API cleanup, pass 1

In 0.5.0; from the
[spine re-audit](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540). A later
pre-1.0 release sets the binary-compatibility baseline, so public API that nothing used, or that was
already deprecated, is removed now rather than frozen.

| Removed | Use instead |
|---|---|
| `Result.fromTry(t)` | `t.toResult` (`import org.llm4s.types.TryOps`) or `Safety.fromTry(t)` |
| `error.isRecoverable` | `LLMError.isRecoverable(error)`, or match on `RecoverableError` / `NonRecoverableError` |
| `LLMError.fromThrowable(t)` | `t.toLLMError` (`org.llm4s.error.ThrowableOps.RichThrowable`) |
| `ToolBuilder#build()` | `buildSafe()`, which returns `Result[ToolFunction]` |
| `ToolRegistry#getToolDefinitions(p)` | `getToolDefinitionsSafe(p)` |
| `LLMCompressor.compress(...)`, `LLMCompressedConversation` | `ContextManager.withDefaults(tokenCounter, Some(llmClient))` then `manageContext(conversation, budget)`; no direct equivalent (see below) |
| `Agent` overloads taking `debug`, `tracing` or `traceLogPath` (`run`, `runStep`, `continueConversation`, `runMultiTurn`, `runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `runWithStrategy`, `continueConversationWithStrategy`) | the same method with `context = AgentContext(debug = ..., tracing = ..., traceLogPath = ...)` |
| `ContextConfig(..., enableRollingSummary = ..., ...)`, `ContextConfig.legacy(...)` | drop the argument (nothing read it); `ContextConfig(...)` or `ContextConfig.default.copy(...)` |
| `Safety.sequenceV(xs)` | `xs.traverse(_.toValidatedNec)` with `import cats.syntax.all.*`, which keeps every error; or, given items and a validator, `Result.validateAll(items)(validate)`. Not `Result.sequence`, which stops at the first error |
| `LLMError.llmErrorShow`, `error.show`, `error.display` | `error.formatted` |
| `org.llm4s.types` aliases and wrappers the library never used (`CompletionId`, `ToolName`, `ToolCallId`, `Url`, `MessageId`, `WorkspaceId`, `Json`, `Timeout`, `TokenCount`, the MCP/image/audio/video/plugin/workflow/metrics types, ...) | the underlying type, e.g. `String`, `ujson.Value`, `Long`, `Int` |
| `ConnectionStatus`, `ProviderCapabilities`, `ClientHealth`, `StreamingOptions`, `RuntimeId`, `ModelId` | none; nothing used them |

`LLMCompressor.compress` summarised a whole conversation with one LLM call, optionally with a
custom prompt. Nothing replaces it one-for-one: `LLMCompressor.squeezeDigest` only shrinks messages
already marked `[HISTORY_SUMMARY]` and returns any other conversation unchanged, so do not swap
one call for the other. `ContextManager.manageContext` is the supported path - it builds the
digests, compresses them and trims to the budget (`ContextConfig.enableLLMCompression` gates the
LLM step); the custom prompt has no counterpart.

`RateLimitedLogger`, `ProvidersConfigModel.RawNamedProviderSection` / `RawProvidersConfig`,
`agent.orchestration.MDCContext` and `assistant.ShowInstances` are now package-private.

`org.llm4s.types` keeps `Result`, `AsyncResult`, `TryOps` / `OptionOps` / `FutureOps`, and the
newtypes the library's APIs take: `SessionId`, `TraceId`, `FilePath`, `DirectoryPath`, `AgentId`,
`PlanId` (both removed later with orchestration, #1330), `SemanticBlockId`, `ArtifactKey`, `ExternalizedContent`, `ContentSize`,
`HeadroomPercent` and the `TokenBudget`, `ContextWindowSize`, `ByteCount` and
`ExternalizationThreshold` aliases.

## Slice 7: `llm4s-agent` - the agent runtime leaves core

The second slice 7 carve ([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D4)
moves the agent runtime into a new module, **`llm4s-agent`**. Package names are unchanged, so no
import changes; code that uses the agent adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % "<version>"
```

| Moved to `llm4s-agent` | Package |
|---|---|
| `Agent`, `AgentState`, `AgentStatus`, `AgentContext`, `Handoff`, `ContextWindowConfig`, `AgentTraceFormatter` | `org.llm4s.agent` |
| Guardrails - the traits, `CompositeGuardrail`, the built-in validators, the LLM-as-judge and RAG guardrails, PII patterns | `org.llm4s.agent.guardrails.*` |
| DAG orchestration | `org.llm4s.agent.orchestration` |
| Streaming events (`AgentEvent` and its cases) | `org.llm4s.agent.streaming` |
| The console assistant: `AssistantAgent`, `SessionManager`, `ConsoleInterface` (Beta) | `org.llm4s.assistant` |

**Not moved:** agent memory (`org.llm4s.agent.memory`) is already `llm4s-memory`, which does not
depend on `llm4s-agent`. `UsageSummary` and `ModelUsage` moved to `org.llm4s.llmconnect.model` in
`llm4s-core` just before this (see below). The ready-made tools are `llm4s-agent-tools`.

**What stays in `llm4s-core`** is what the agent is built on: `LLMClient` and `LLMConnect`, the
tool API (`ToolFunction`, `ToolRegistry`), and the tracing contract. An agent run is traced through
`TraceEvent.AgentStateUpdated`, built by `AgentState#toTraceEvent`, so tracing backends need
nothing from the agent module.

Other modules: `llm4s-workspace-client` now depends on `llm4s-agent`, for its `codegen` package.
`fansi` leaves `llm4s-core` with the assistant, its only user.

## Slice 7: `llm4s-agent-tools` - the built-in tools leave core

The first slice 7 carve ([#1242](https://github.com/llm4s/llm4s/issues/1242), decisions D2 and D3)
moves the ready-made tools into a new module, **`llm4s-agent-tools`**. Package names are unchanged,
so no import changes; code that uses any of them adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-tools" % "<version>"
```

| Moved to `llm4s-agent-tools` | Package |
|---|---|
| `BuiltinTools` and every tool under it: `core` (DateTime, Calculator, UUID, JSON), `filesystem`, `http`, `shell`, `search` (Brave, DuckDuckGo, Exa) | `org.llm4s.toolapi.builtin.*` |
| `WeatherTool` | `org.llm4s.toolapi.tools` |
| `ToolsConfigLoader` (was `private[config]`, now public), `BraveSearchToolConfig`, `DuckDuckGoSearchToolConfig`, `ExaSearchToolConfig` | `org.llm4s.config` |
| `ToolsConfigKeys` (new) | `org.llm4s.config` |

**What stays in `llm4s-core`** is the tool API: `ToolFunction`, `ToolRegistry`, `ToolBuilder`,
`Schema`, the execution strategies and `SafeParameterExtractor`. Your own tools need nothing new.
`llm4s-agent-tools` depends on `llm4s-core` only - not on the agent runtime - so the tools work
with plain tool calling through `ToolRegistry` as well as with `Agent`.

### Configuration is unchanged

The `llm4s.tools.brave`, `.duckduckgo` and `.exa` keys, their defaults, and the
`BRAVE_SEARCH_API_KEY`, `BRAVE_SEARCH_COUNT`, `BRAVE_SEARCH_API_URL`, `BRAVE_SAFE_SEARCH`,
`DUCK_DUCK_GO_SEARCH_API_URL` and `EXA_*` variables are the same. The block moved from core's
`reference.conf` to the module's, beside the code that reads it.

### Source breaks

1. **The tools need `llm4s-agent-tools`** (table above).
2. **`Llm4sConfig.loadBraveSearchTool()`, `loadDuckDuckGoSearchTool()` and `loadExaSearchTool()`
   are removed.** They returned config types that left core. The same methods are on
   `ToolsConfigLoader`:

   ```scala
   import org.llm4s.config.ToolsConfigLoader

   // before
   val braveConfig = Llm4sConfig.loadBraveSearchTool()

   // after
   val braveConfig = ToolsConfigLoader.loadBraveSearchTool()
   ```

   Each also takes a `ConfigSource`, to read from one of your own.
3. **`ConfigKeys.BRAVE_SEARCH_API_KEY` is `ToolsConfigKeys.BRAVE_SEARCH_API_KEY`**, with
   `ToolsConfigKeys.EXA_API_KEY` beside it.

## Slice 7: `UsageSummary` and `ModelUsage` move to `org.llm4s.llmconnect.model`

In 0.5.0; the preparation step of slice 7
([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D1).

Slice 7 carves the agent runtime out of `llm4s-core` into `llm4s-agent`. `UsageSummary` and
`ModelUsage` were in `org.llm4s.agent`, but they depend only on `TokenUsage`, and
`llm4s-observability`'s `CostTracker` builds them too. Leaving them in the agent package would make
`llm4s-observability` depend on the agent runtime; moving the file to core while keeping its
package would split `org.llm4s.agent` across two jars. So they move to `org.llm4s.llmconnect.model`,
beside `TokenUsage`, and stay in `llm4s-core`.

This is the one change to an import in the slice; every other move keeps its package.

```scala
// before
import org.llm4s.agent.{ ModelUsage, UsageSummary }

// after
import org.llm4s.llmconnect.model.{ ModelUsage, UsageSummary }
```

Code that only reads `AgentState.usageSummary` or `CostTracker.snapshot` needs no change. The JSON
form is unchanged, so an `AgentState` saved before the move still loads.

## llm4s no longer brings a logging backend

In 0.5.0; found by the slice 6 spine audit
([#1133](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5878475544)).

Every llm4s module used to declare `logback-classic` and `log4j-to-slf4j` as compile
dependencies, so they reached your classpath with llm4s. Libraries should leave that choice to the
application, and the published modules now declare only `slf4j-api`.

- **If you already configure logging** (your own logback, log4j2 via `log4j-slf4j2-impl`, or
  another SLF4J 2 backend), nothing changes - except that llm4s no longer adds a second backend
  or a bridge that clashes with `log4j-core`.
- **If you relied on the logback llm4s brought**, declare it yourself; without a backend, SLF4J
  logs nothing and prints one "no SLF4J providers were found" warning:

  ```scala
  libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.34"
  ```

- **If you used Monocle, commons-io or fansi through llm4s**, declare them yourself: Monocle and
  commons-io are no longer dependencies of any llm4s module, and fansi comes only with
  `llm4s-core`.

## A failed read no longer deletes or clears indexed documents

Follow-up to [#1236](https://github.com/llm4s/llm4s/pull/1236); in 0.5.0.

`LoadResult.Failure` gains a fourth field, `documentId: Option[String] = None`: the id the
failed document is (or would be) indexed under. `source` stays the path, key or URL. `RAG.sync`
uses it to tell a document it could not read from one that has gone from the source.

- **A pattern match on `LoadResult.Failure` needs a fourth argument**:
  `case LoadResult.Failure(source, error, recoverable)` becomes
  `case LoadResult.Failure(source, error, recoverable, documentId)` (or `_`). Constructor calls,
  `LoadResult.failure(source, error)`, `.source`, `.error` and `.recoverable` are unchanged;
  `LoadResult.failure(source, error, documentId = id)` is new.
- **`sync` keeps a document whose read fails.** A `Failure` with a `documentId` keeps that
  document's indexed version: it is not deleted, and not counted in `SyncStats`. Previously
  sync deleted it, because it had not been "seen". `SourceBackedLoader` (S3 and every other
  `DocumentSource`) and `UrlLoader` name the document on every per-document failure,
  `FileLoader` when extraction fails. Read failures are logged at WARN.
- **A `Failure` with no `documentId` makes `sync` skip its deletion pass** for that run, as it
  cannot tell which unlisted document failed. Adds and updates still apply. `WebCrawlerLoader`
  leaves it out on purpose (the pages below a failed page went uncrawled), as does `FileLoader`
  for a missing path. **A custom `DocumentLoader` should set `documentId`** on per-document
  failures when it knows the id, or its failures will now hold back deletions.
- **`refresh` reads the whole loader before it clears anything.** A `ListingFailure` - at any
  point, including a later S3 page - or, under `failFast`, any `Failure` returns the error and
  leaves the index and registry untouched. Previously the index was cleared first and left
  empty or half-rebuilt. The cost is memory: `refresh` and `refreshAsync` now hold every loaded
  document's extracted text in memory before clearing; use `sync`, which streams, for a
  source too large for that. Without `failFast`, a document that fails to read is still left
  out of the rebuilt index.
- `DocumentLoaders.successesOnly` still drops per-document failures, so a sync through it
  still deletes a document whose read failed.

## Slice 6: `llm4s-observability-prometheus` - Prometheus leaves core

The second slice 6 carve ([#1133](https://github.com/llm4s/llm4s/issues/1133), decisions D3 and
D4) moves the Prometheus metrics backend into a new module, **`llm4s-observability-prometheus`**,
which carries the Prometheus client and HTTP server. With it gone, `llm4s-core` declares no
observability dependency. Package names are unchanged, so no import changes:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-prometheus" % "<version>"
```

| Moved to `llm4s-observability-prometheus` | Package |
|---|---|
| `PrometheusMetrics`, `PrometheusEndpoint` | `org.llm4s.metrics` |
| `MetricsConfigLoader` (was `private[config]`, now public) | `org.llm4s.config` |

**What stays in `llm4s-core`** is the metrics contract: `MetricsCollector` (with `noop` and
`compose`), `Outcome` and `ErrorKind`. Every provider client, `ReliableClient` and the metrics and
rate-limiting middleware take a `MetricsCollector`, so none of them needs the new module; only the
code that builds a `PrometheusMetrics` does. It is kept apart from `llm4s-observability` so that
Prometheus does not reach every `llm4s-rag` user through that module.

### Configuration is unchanged

The keys and their defaults are the same - `llm4s.metrics.enabled` (`false`),
`llm4s.metrics.prometheus.enabled` (`true`) and `llm4s.metrics.prometheus.port` (`9090`). They used
to be hard-coded in `MetricsConfigLoader`, because core's `reference.conf` had no `llm4s.metrics`
block; that block now ships in the module's `reference.conf`, beside the code that reads it.

### Source break: `Llm4sConfig.metrics()` is removed

It returned a `PrometheusEndpoint`, so it could not stay in a core without Prometheus (D3).
`MetricsConfigLoader` replaces it, with the same result type:

```scala
import org.llm4s.config.MetricsConfigLoader

// before
val (metrics, endpoint) = Llm4sConfig.metrics().toOption.get

// after
val (metrics, endpoint) = MetricsConfigLoader.default().toOption.get
```

`MetricsConfigLoader.load(source)` reads from a `ConfigSource` of your own. Its `source` parameter
no longer defaults to `ConfigSource.default`; call `default()` for that.

## A failed listing fails the sync

[#1231](https://github.com/llm4s/llm4s/pull/1231); in 0.5.0.

A document loader that cannot enumerate its documents - an S3 listing with no credentials, a
missing bucket or access denied, a `DirectoryLoader` path that does not exist - now yields the
new `LoadResult.ListingFailure(source, error)` instead of a `LoadResult.Failure`. `RAG.sync`,
`ingest` and `refresh`, and their async forms, return its error as the `Left`. Previously
`sync` returned `Right` with 0 documents and then deleted every document it had indexed from
that source, because it had seen none of them.

- **Code that matches on `LoadResult` needs a `ListingFailure` case**; an exhaustive match
  without one warns, and fails the build under `-Werror`.
- **`RAG.ingest` no longer counts a listing error as one failed document** in `LoadStats`: it
  returns it as the `Left`, whether or not `failFast` is set. Code that read
  `stats.errors` for `"list-error"` should handle the `Left` instead.
- **`SourceBackedLoader` names the source**, not `"list-error"`: the `ListingFailure`'s
  `source` is the `DocumentSource`'s `description`, e.g. `S3(s3://bucket/prefix)`.
- A `sync` that fails part-way through a listing (a later S3 page) keeps the documents it
  synced before the failure and deletes nothing; re-running it once the source is reachable
  completes the sync. `refresh` cleared the index before reading the loader, so a listing
  failure left it empty - but said so; it now leaves the index untouched (see
  [above](#a-failed-read-no-longer-deletes-or-clears-indexed-documents)).

A `DocumentSource` reports a listing error by yielding a `Left` from `listDocuments()`, as
before; `SourceBackedLoader` does the rest. A custom `DocumentLoader` whose enumeration fails
should return `LoadResult.listingFailure(source, error)`.

## Vendor credentials: a shared API key per provider

[#1132](https://github.com/llm4s/llm4s/issues/1132), part of the modularisation programme
([#1126](https://github.com/llm4s/llm4s/issues/1126)); in 0.5.0. `0.4.1` and earlier
behave as before.

**Credentials belong to a vendor, keyed by provider id; clients belong to a use.** Each provider
module now binds its vendor's conventional API-key variable to a shared key,
`llm4s.credentials.<provider>.apiKey`, in its own `reference.conf`. A client - a chat section, an
embeddings block, the Cohere reranker - that sets no `apiKey` of its own uses it. So with
`OPENAI_API_KEY` set, a chat section needs only `provider` and `model`, and OpenAI embeddings need
no extra line.

This is additive: configs that set `apiKey` keep working unchanged, because a client's own key
always wins. Configs that failed for want of an `apiKey` line now load.

### Resolution order

For a client of provider *P*:

1. its own `apiKey`: `llm4s.providers.<name>.apiKey`, `llm4s.embeddings.<P>.apiKey`, or
   `llm4s.rerank.cohere.apiKey`;
2. otherwise `llm4s.credentials.<P>.apiKey`, where *P* is the canonical id - a
   `provider = "google"` section uses `llm4s.credentials.gemini`;
3. otherwise a `ConfigurationError` naming both places:
   `apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf`.

The credentials block holds `apiKey` only: `baseUrl`, `endpoint`, `model` and `apiVersion` are
never defaulted from it. Where each key came from is logged at INFO - for example
`llm4s.providers.openai-main: API key from llm4s.credentials.openai.apiKey` - and the value never
is.

```hocon
# before
openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"
  apiKey   = ${?OPENAI_API_KEY}
}
llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}

# after - with OPENAI_API_KEY exported
openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"
}
```

The old lines still work and may stay. A section for a **second account** keeps its own key:
`apiKey = ${?OPENAI_BATCH_API_KEY}`. If that variable is unset, the section now falls back to the
shared key instead of failing - see [Explicit keys in production](#explicit-keys-in-production).

### Variables bound

| Provider id | Variable | Module |
|---|---|---|
| `openai` | `OPENAI_API_KEY` | `llm4s-openai` |
| `azure` | `AZURE_OPENAI_API_KEY` | `llm4s-openai` |
| `requesty` | `REQUESTY_API_KEY` | `llm4s-openai` |
| `anthropic` | `ANTHROPIC_API_KEY` | `llm4s-anthropic` |
| `gemini` | `GOOGLE_API_KEY`, else `GEMINI_API_KEY` (Google's SDK precedence) | `llm4s-gemini` |
| `deepseek`, `zai`, `openrouter`, `mistral` | `DEEPSEEK_API_KEY`, `ZAI_API_KEY`, `OPENROUTER_API_KEY`, `MISTRAL_API_KEY` | `llm4s-openai-compatible` |
| `cohere` | `COHERE_API_KEY` | `llm4s-openai-compatible` and `llm4s-rag` |
| `voyage` | `VOYAGE_API_KEY` | `llm4s-voyage` |

None for `openai-compatible` (it has no vendor), `ollama` (no key) or `vertexai` (OAuth2:
Application Default Credentials, or a service-account file named by `apiKey`).

### Moved bindings

- **Voyage**: `llm4s.embeddings.voyage.apiKey = ${?VOYAGE_API_KEY}` became
  `llm4s.credentials.voyage.apiKey = ${?VOYAGE_API_KEY}`. `VOYAGE_API_KEY` works as before, and an
  explicit `llm4s.embeddings.voyage.apiKey` still wins.
- **Cohere reranker**: `llm4s.rerank.cohere.apiKey = ${?COHERE_API_KEY}` became
  `llm4s.credentials.cohere.apiKey = ${?COHERE_API_KEY}`, shared with the Cohere chat provider, so
  one `COHERE_API_KEY` serves both. An explicit `llm4s.rerank.cohere.apiKey` still wins.
- **Azure**: if you exported `AZURE_API_KEY` for a section without its own `apiKey`, rename it
  to `AZURE_OPENAI_API_KEY` - the openai SDK's name, and the one now bound - or keep an
  `apiKey = ${?AZURE_API_KEY}` line in the section.

### New: `RerankerConfigLoader`

`llm4s-rag`'s `reference.conf` has bound `llm4s.rerank` since #337, but no code read it.
`org.llm4s.config.RerankerConfigLoader.load(source)` / `.default()` now does, returning
`Result[Option[RerankProviderConfig]]` for `RAG.build(..., resolveRerankerConfig = ...)` or
`RerankerFactory.fromConfig`.

### Explicit keys in production

`llm4s-config-policy`'s `prod` preset (`ConfigPolicy.prodSafeDefaults`) gains the rule
`ownApiKey` (`ConfigPolicy.withOwnApiKeyRequired`, checked by
`ConfigPolicyEngine.checkApiKeySources` and run by `CheckPolicies`): every chat section whose
provider requires a key must set its own `apiKey`, so a section meant for a second account cannot
silently bill the default one. A `prod` check of a config that relies on the shared key now fails;
add `apiKey = ${?VAR}` to each section. `Llm4sConfig.apiKeySources()` / `apiKeySourcesFrom(source)`
report each section's `ApiKeySource` (`Section(path)` or `Credentials(path)`) for checks of your own.

### Source breaks

These are deliberate removals ahead of the MiMa baseline:

1. **`EmbeddingConfigSpec.apiKeyPath` is removed**, with the loader code that resolved it. The
   shared `llm4s.credentials.<id>.apiKey` replaces it for every provider: bind your vendor's
   variable there in your module's `reference.conf` instead of declaring a path.
2. **`EmbeddingConfigSpec.apiKeyEnv` is a `Seq[String]`**, not an `Option[String]`:
   `apiKeyEnv = Some("X")` becomes `apiKeyEnv = Seq("X")`. It lists the variables your module
   binds to `llm4s.credentials.<id>.apiKey`, highest precedence first, and is used only to word
   the missing-key error.
3. **`ProviderConfigSpec` gained `apiKeyEnv: Seq[String]`** (last, with a default), and
   `ProviderConfigSpec.apiKeyAndDefaultBaseUrl` an optional second parameter for it. Positional
   construction still compiles; a pattern match on `ProviderConfigSpec` needs one more binder.
4. **`OpenAIConfigKeys.AZURE_API_KEY` is now `AZURE_OPENAI_API_KEY`**, with the value
   `"AZURE_OPENAI_API_KEY"`.
5. **The missing-`apiKey` messages changed.** Chat: `apiKey: set <VAR>, or set apiKey under
   llm4s.providers.<name> in application.conf`. Embeddings: `Missing <id> embeddings apiKey: set
   <VAR>, or set apiKey under llm4s.embeddings.<id> in application.conf`. Code matching the old
   text needs updating.

For provider authors: bind `llm4s.credentials.<id>.apiKey = ${?<VENDOR>_API_KEY}` in your module's
`reference.conf`, using the name the vendor's own SDK or docs use, and declare the same variable
as `apiKeyEnv` on your `ProviderConfigSpec` or `EmbeddingConfigSpec`. Your module's round-trip spec
should prove the two agree.

## Slice 6: `llm4s-observability` - Langfuse, the trace collector and `CostTracker` leave core

The slice 6 carve ([#1133](https://github.com/llm4s/llm4s/issues/1133),
[#1126](https://github.com/llm4s/llm4s/issues/1126)) moves the tracing integrations that need
nothing beyond core into a new module, **`llm4s-observability`**. Package names are unchanged, so
no import changes; code that uses any of the types below adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability" % "<version>"
```

| Moved to `llm4s-observability` | Package |
|---|---|
| `LangfuseTracing`, `LangfuseBatchSender`, `DefaultLangfuseBatchSender`, `LangfuseHttpApiCaller`, `LangfuseTracingBackend` | `org.llm4s.trace` |
| `TraceCollectorTracing` and the rest of `TraceCollector.scala` | `org.llm4s.trace` |
| `Trace`, `Span`, `SpanId`, `SpanKind`, `SpanStatus`, `SpanEvent`, `SpanValue`, `TraceModelJson` | `org.llm4s.trace.model` |
| `TraceStore`, `InMemoryTraceStore`, `TraceQuery` | `org.llm4s.trace.store` |
| `CostTracker` | `org.llm4s.metrics` |
| `LangfuseConfig` (was in core) | `org.llm4s.llmconnect.config` |
| `LangfuseConfigKeys`, `LangfuseConfigLoader` (new) | `org.llm4s.config` |

`OpenTelemetryConfig` moves, in the same package, to `llm4s-observability-otel`, beside the
backend that reads it.

**What stays in `llm4s-core`** is the tracing contract (decisions D1 to D5 in #1133): `Tracing`,
`TraceEvent`, `TracingComposer`, `TracingMode`, the `TracingBackend` SPI, `NoOpTracing`,
`ConsoleTracing` (the default mode, so a core-only application still traces), `TracingSettings`,
and `MetricsCollector`. `llmconnect`, the agent runtime and every provider module use only these,
so none of them needs the new module. `llm4s-rag` depends on it, for `RAGASLangfuseObserver`; it
carries no third-party dependency, so this adds nothing else to a RAG user's classpath.

### Configuration is unchanged

The keys and variables are the same: `TRACING_MODE=langfuse`, `LANGFUSE_PUBLIC_KEY`,
`LANGFUSE_SECRET_KEY`, `LANGFUSE_URL`, `LANGFUSE_ENV`, `LANGFUSE_RELEASE`, `LANGFUSE_VERSION`
under `llm4s.tracing.langfuse.*`, and `TRACING_MODE=opentelemetry` (or `otel`),
`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT` and `llm4s.tracing.opentelemetry.headers`.
Only the module that binds them changed: the `llm4s.tracing.langfuse` block moved from core's
`reference.conf` to `llm4s-observability`'s, and `llm4s.tracing.opentelemetry` to
`llm4s-observability-otel`'s, each with the code that reads it. Core now reads only
`llm4s.tracing.mode`, and hands the selected mode's block to its backend as
`TracingSettings.extras`.

So an application that sets `TRACING_MODE=langfuse` adds `llm4s-observability` and changes no
config. Without it, `Tracing.create` logs an error naming the artifact and traces nothing -
exactly what `TRACING_MODE=opentelemetry` without `llm4s-observability-otel` already did:

```text
Tracing mode 'langfuse' is configured but no TracingBackend for it is on the classpath.
Add the 'org.llm4s' %% 'llm4s-observability' dependency. Available modes: console, noop.
```

### Behaviour change: Langfuse without keys does not start

`TRACING_MODE=langfuse` without `LANGFUSE_PUBLIC_KEY` or `LANGFUSE_SECRET_KEY` used to build a
`LangfuseTracing` that logged a warning and dropped every batch. The backend now refuses to start:
`Tracing.fromSettings` returns

```text
Langfuse tracing is selected but llm4s.tracing.langfuse.publicKey (LANGFUSE_PUBLIC_KEY) and
llm4s.tracing.langfuse.secretKey (LANGFUSE_SECRET_KEY) are not set.
```

and `Tracing.create` logs that once and returns `NoOpTracing`. Either way nothing reaches
Langfuse, as before; the difference is one clear error instead of a warning per batch.
`LangfuseTracing.from(config)` built directly is unchanged.

### Behaviour change: a selected block that is not an object is an error

`TracingSettings.extras` is the selected mode's block, `llm4s.tracing.<mode>`. When that key was
present but was not an object - `llm4s.tracing { mode = opentelemetry, opentelemetry =
"http://collector:4317" }` - it was treated as absent, so the backend started on its defaults
(here, a collector on localhost) and the operator's setting was silently ignored. Read failures
were swallowed the same way. `Llm4sConfig.tracing()` now returns a `ConfigurationError` naming
the path instead:

```text
llm4s.tracing.opentelemetry must be an object, but is a string. It holds the settings for
tracing mode 'opentelemetry': write it as llm4s.tracing.opentelemetry { ... }.
```

A block that is absent (or `null`) is still an empty `extras`, and the backend applies its
defaults. Only the selected mode's block is checked: a malformed block for a mode that is not
selected is still ignored.

### Reading Langfuse settings yourself

`TracingSettings` no longer has a `langfuse` field, and `extras` holds only the *selected* mode's
block. To read `llm4s.tracing.langfuse` whatever `TRACING_MODE` is - to combine Langfuse with
Console, or for `RAGASLangfuseObserver` - use the new loader:

```scala
import org.llm4s.config.LangfuseConfigLoader

// before
Llm4sConfig.tracing().map(settings => LangfuseTracing.from(settings.langfuse))
RAGASLangfuseObserver.fromTracingSettings(settings)

// after
LangfuseConfigLoader.default().map(LangfuseTracing.from)
LangfuseConfigLoader.default().map(RAGASLangfuseObserver.from)
```

### Source breaks

These are taken before the MiMa baseline is set, and none of them changes a package name.

1. **Langfuse, the collector, its model and store, and `CostTracker` need `llm4s-observability`**
   (table above).
2. **`TracingMode.Langfuse` and `TracingMode.OpenTelemetry` are removed.** Core keeps a case only
   for what it builds itself, `Console` and `NoOp`; the others are `TracingMode.Named("langfuse")`
   and `TracingMode.Named("opentelemetry")`, which `TracingMode.fromString` returns for
   `"langfuse"` and for `"opentelemetry"` / `"otel"`. `LangfuseConfig.Mode` and
   `OpenTelemetryConfig.Mode` name them. A `match` on the old case objects matches on
   `TracingMode.Named("langfuse")` instead.
3. **`TracingSettings` is `TracingSettings(mode, extras)`.** The `langfuse: LangfuseConfig` and
   `openTelemetry: OpenTelemetryConfig` fields are removed. A backend reads its block from
   `extras` - `LangfuseConfig.fromExtras(settings.extras)`,
   `OpenTelemetryConfig.fromExtras(settings.extras)` - and code that built settings by hand passes
   `extras = Map("publicKey" -> ..., "secretKey" -> ...)` for Langfuse, or
   `Map("serviceName" -> ..., "endpoint" -> ..., "headers.Authorization" -> ...)` for OpenTelemetry.
4. **`LangfuseConfig` moves to `llm4s-observability`**, and **`OpenTelemetryConfig` to
   `llm4s-observability-otel`**, in the same package.
5. **`DefaultConfig` is removed.** Its last four constants moved to `LangfuseConfig`:
   `DEFAULT_LANGFUSE_URL`, `_ENV`, `_RELEASE` and `_VERSION` are `LangfuseConfig.DEFAULT_URL`,
   `DEFAULT_ENV`, `DEFAULT_RELEASE` and `DEFAULT_VERSION`.
6. **`ConfigKeys.LANGFUSE_*` are `LangfuseConfigKeys.LANGFUSE_*`** in `llm4s-observability`.
7. **`RAGASLangfuseObserver.fromTracingSettings(TracingSettings)` is removed**: it read the removed
   `TracingSettings.langfuse`. Use `RAGASLangfuseObserver.from(config)` with
   `LangfuseConfigLoader.default()`, as above.
8. **`TraceEvent.createTraceEvent` and `org.llm4s.llmconnect.model.TraceHelper` are removed.**
   Both built Langfuse ingestion JSON - a `"trace-create"` batch envelope, and
   `event-create` / `generation-create` / `span-create` envelopes per conversation message - and
   nothing in llm4s called either; `LangfuseTracing` builds its own batches. They were
   Langfuse's wire format sitting in the core contract, so they are deleted rather than moved. Code
   that called them builds the JSON itself, or traces through `LangfuseTracing` (`llm4s-observability`),
   which sends the same event types.

## Slice 6: tracing backends are discovered, and agent state is a `TraceEvent`

The first slice 6 change ([#1133](https://github.com/llm4s/llm4s/issues/1133)) lands the extension
point for tracing before Langfuse and Prometheus are carved out of `llm4s-core`, as slice 4 did for
providers. Nothing moves module yet, and nothing in a release has it; `0.4.1` and earlier behave as
before.

### `Tracing.traceAgentState(AgentState)` is removed

`Tracing` no longer mentions `AgentState`, so the tracing contract does not depend on the agent
runtime. Agent state is traced as an ordinary event, `TraceEvent.AgentStateUpdated`, which
`AgentState#toTraceEvent` builds:

```scala
// before
tracing.traceAgentState(state)

// after
tracing.traceEvent(state.toTraceEvent)
```

`AgentStateUpdated` gained a `messages: Seq[Message]` field (default empty) before `timestamp`,
carrying the conversation, so that Langfuse still records a trace with one span per message. It is
not part of `toJson`. The agent emits the event exactly where it called `traceAgentState`.

A custom `Tracing` implementation drops its `traceAgentState` override; anything it did there
belongs in `traceEvent`'s `AgentStateUpdated` case.

### `Tracing.create` finds backends on the classpath

`Tracing.create` has since been removed: `Tracing.fromSettings` is the only factory, see
[A tracing backend declares its aliases, and `Tracing.create` is removed](#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799).
What follows describes it as it was in this slice.

`Tracing.create(settings)` kept its signature. It built `NoOp` and `Console` itself and handed
every other mode to an `org.llm4s.trace.spi.TracingBackend` registered in
`META-INF/services/org.llm4s.trace.spi.TracingBackend`:

- **OpenTelemetry**: `llm4s-observability-otel` registers `OpenTelemetryTracingBackend`. The
  `Class.forName` reflection core used to find it is gone. Adding the dependency is all it takes,
  as before.
- **Langfuse**: registered by core's own services entry until it is carved into
  `llm4s-observability` - which has since happened; see
  [the carve's note](#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core).
- **Anything else**: implement `TracingBackend` (a `class` with a public no-arg constructor, not an
  `object`) with `mode = TracingMode.Named("yourmode")`, declare it in the services file, and
  `TRACING_MODE=yourmode` selects it.

`Tracing.create` still never fails: a missing or broken backend logs an error and gives
`NoOpTracing`. The new **`Tracing.fromSettings(settings): Result[Tracing]`** returns that as a
`ConfigurationError` (or the backend's own error) instead, and
`Tracing.fromSettings(settings, TracingBackends.of(...))` registers a backend explicitly, for a
shaded jar whose services files did not survive.

### `TracingMode` is open

`TracingMode` gained a `Named(name)` case and a `name` member. `TracingMode.fromString` returns
`Named` for a value it does not recognise, lower-cased, where it used to return `NoOp` with a
warning; a blank value is still `NoOp`. The end result of an unknown `TRACING_MODE` is unchanged -
`Tracing.create` gives `NoOpTracing` - but the log line is now an error that lists the modes that
are available.

### Behaviour changes

1. **An `AgentStateUpdated` with no messages is exported to Langfuse** as a summary trace. The old
   `traceAgentState` sent nothing for an empty conversation.
2. **Langfuse reports a failed export of the conversation trace** as a `Left`; `traceAgentState`
   always returned `Right(())`. The agent swallows tracing errors either way.
3. **The agent state span takes the event's name.** Agent runs used to reach a tracer through
   `traceAgentState`, whose span name differed from the one `traceEvent` gave the same event; they
   now go through `traceEvent`, so the attributes are unchanged but the name is not. Update any
   dashboard or query keyed on the old name:

   | Backend | Old name (agent runs) | New name |
   |---|---|---|
   | OpenTelemetry | `Agent State Snapshot` | `Agent State Updated` |
   | `TraceCollectorTracing` | `agent-state-update` | `agent_state_updated` |
4. **An OpenTelemetry SDK that fails to start** is reported by `Tracing.fromSettings` and gives
   `NoOpTracing` from `Tracing.create`, rather than a tracer whose every call failed.

### Source breaks

1. **`Tracing.traceAgentState` is removed** - use `traceEvent(state.toTraceEvent)`.
2. **`TraceEvent.AgentStateUpdated` has a fifth field**, `messages`, before `timestamp`. A
   positional `AgentStateUpdated(status, messages, logs, timestamp)` must name the timestamp
   (`timestamp = ...`), and a pattern `AgentStateUpdated(a, b, c, ts)` needs one more binder.
3. **`TracingMode` has a new case**, `Named`, so an exhaustive `match` over it needs one more
   branch, and **a new abstract member, `name`**, for anything extending the sealed trait (nothing
   outside core can).
4. **`TracingMode.fromString` returns `Named(...)`, not `NoOp`, for an unrecognised value.**

## From `LLM_MODEL` to named provider sections

> **API keys: superseded** by [Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider).
> Provider modules now bind each vendor's API-key variable (`OPENAI_API_KEY`, ...), so the
> `apiKey = ${?OPENAI_API_KEY}` lines below, and the embeddings binding, are optional. What this
> note says about `LLM_MODEL` and base-URL variables still holds.

Since [#903](https://github.com/llm4s/llm4s/pull/903) (in 0.3.2) removed legacy single-provider
loading, **nothing in llm4s reads `LLM_MODEL`**, nor a provider's API-key or base-URL variable
(`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `AZURE_API_BASE`, `OLLAMA_BASE_URL`, ...).
No `reference.conf` binds them. Setting them has no effect, and an application that relied on them
fails at startup with a configuration error. The documentation kept teaching them until
[#1132](https://github.com/llm4s/llm4s/issues/1132)'s follow-up.

Configure providers as named sections in your own `application.conf`, binding secrets from the
environment with `${?VAR}` - you choose the variable names:

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
      apiKey   = ${?OPENAI_API_KEY}
    }
  }
}
```

| Before | After |
|---|---|
| `LLM_MODEL=openai/gpt-4o-mini` | a section with `provider = "openai"`, `model = "gpt-4o-mini"`, selected by `llm4s.providers.provider` |
| `OPENAI_API_KEY=...` read automatically | `apiKey = ${?OPENAI_API_KEY}` in that section, and the same variable exported |
| `OPENAI_BASE_URL`, `OLLAMA_BASE_URL`, ... | `baseUrl = "..."` (or `baseUrl = ${?OLLAMA_BASE_URL}`) in the section; an Ollama section defaults to `http://localhost:11434` |
| switching model by changing `LLM_MODEL` | a second section, selected by changing `provider`, by `-Dllm4s.providers.provider=<name>`, by your own `provider = ${?LLM4S_PROVIDER}` binding, or loaded directly with `Llm4sConfig.provider("<name>")` |
| `Llm4sConfig.provider()` | `Llm4sConfig.defaultProvider()` |

Two things to know:

- **Only the section you load is validated** (since
  [#1132](https://github.com/llm4s/llm4s/issues/1132)'s follow-up; in 0.5.0). A
  section whose required `apiKey` is unset, or whose provider module is not on the classpath,
  fails `provider("<that section>")` - and `defaultProvider()` when it is the default - but not
  a load of any other section. Up to 0.4.1 every section was validated on every load, so each
  environment had to fill in every section or ship a config without it. `Llm4sConfig.providers()`,
  which returns every section, still validates them all.
- **OpenAI embeddings do not see `OPENAI_API_KEY` either.** With
  `EMBEDDING_MODEL=openai/<model>` (which *is* bound, by llm4s-core's `reference.conf`), add
  `llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}`. Since #1132's follow-up the key no longer
  falls back to `llm4s.openai.apiKey`, the pre-#903 chat key that nothing else read: if you set
  that, set `llm4s.embeddings.openai.apiKey` instead.

Variables that `reference.conf` files do bind - `TRACING_MODE`, `LANGFUSE_*`, `OTEL_SERVICE_NAME`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, `EMBEDDING_MODEL`, `VOYAGE_API_KEY` and others - still work; see
[the variables llm4s reads](../getting-started/configuration#environment-variables-llm4s-reads).
Two tools read `LLM_MODEL` themselves: the chat-tui sample (`ChatTuiConfig`) and the config-policy
env check (`EnvCheckPolicies`).

## Named providers: provider-specific keys; Vertex AI `project` and `location`

A named provider section can now carry keys of the provider's own, declared by the provider
rather than squeezed into the shared fields ([#1215](https://github.com/llm4s/llm4s/issues/1215)).
In 0.5.0.

### Vertex AI: `endpoint` becomes `project`, `organization` becomes `location`

Vertex AI used to read its GCP project from `endpoint` and its region from `organization`. It
now has keys named for what they are:

```hocon
# before
vertex-main {
  provider     = "vertexai"
  model        = "gemini-2.0-flash"
  endpoint     = "my-gcp-project"
  organization = "europe-west4"
}

# after
vertex-main {
  provider = "vertexai"
  model    = "gemini-2.0-flash"
  project  = "my-gcp-project"
  location = "europe-west4"      # optional, default us-central1
}
```

The old spellings were kept as deprecated aliases until 0.7.0, which removed them
([note](#vertex-ai-no-longer-reads-endpoint-and-organization)). Setting both
`project` and a *different* `endpoint` (or `location` and a different `organization`) is an
error rather than a guess. A missing project is now reported as
`- project: the GCP project ID that owns your Vertex AI resources (set it in application.conf under llm4s.providers.<name>.project)`.

### Unknown keys are reported

A key in a section that is neither a built-in field nor one the provider declares used to be
ignored silently. It is still ignored - existing configs keep loading - but now with a warning
that names it and lists the keys the provider accepts, which is how a typo like `regoin` shows
up. An extra key whose value is an object or list is an error, since none takes one.

### The missing-`baseUrl` hint no longer suggests a variable nothing reads

A section missing a required `baseUrl` used to say `set <PROVIDER>_BASE_URL`, for example
`set OLLAMA_BASE_URL`, though nothing read such a variable for a named section. It now says
where to set the key - `baseUrl: set it in application.conf under llm4s.providers.<name>.baseUrl (e.g. ...)`.
Where the provider has a conventional variable (`ProviderConfigSpec.baseUrlEnv`), the message
shows the binding that makes it read, since a named section reads none by itself:
`openai-compatible` adds `to read it from OPENAI_COMPATIBLE_BASE_URL, add baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL} to the section`.
Code matching on the old message text needs updating.

### Source breaks

- `RawNamedProviderSection` and `NamedProviderConfig` gained a trailing `extras` parameter with
  a default, so construction by name or position still compiles; pattern matches that list
  every field (`case NamedProviderConfig(a, b, ...)`) need one more.
- `ProviderConfigSpec` gained `baseUrlEnv` and `extras`, both defaulted.
- `VertexAIProvider.configSpec` no longer sets `requiresEndpoint`. Code that builds a
  `NamedProviderConfig` by hand and passes it straight to `VertexAIProvider.buildConfig`,
  skipping validation, must set `extras = Map("project" -> ..., "location" -> ...)`: the
  deprecated aliases are resolved by validation, not by `buildConfig`.

### For provider authors

Declare provider-specific keys in `ProviderConfigSpec.extras` and read them from
`NamedProviderConfig.extras` - see [CONTRIBUTING](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#provider-specific-config-keys).

## Slice 5 follow-ups: `llm4s-openai-compatible`

Fixes to the shared OpenAI-compatible client after its carve
([#1132](https://github.com/llm4s/llm4s/issues/1132)). Nothing here needs a code change unless
you depend on the old behaviour.

### `complete` times out after two minutes

`OpenAICompatibleClient.complete` - DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and the generic
`openai-compatible` provider - sent its request with no timeout, so an endpoint that never
answered hung the caller. It now fails after two minutes (`OpenAICompatibleClient.RequestTimeout`),
the value the old Mistral and Cohere clients used; streaming keeps five minutes. Neither is
configurable yet ([#712](https://github.com/llm4s/llm4s/issues/712)): a slow local model with a
long prompt should stream.

### Streaming requests send `stream_options`

A streaming request from the generic provider and DeepSeek now carries
`"stream_options": {"include_usage": true}`, so servers that follow OpenAI - vLLM, Ollama's
`/v1`, Perplexity's Router - report token usage on streams. Z.ai, OpenRouter, Mistral and Cohere
do not send it. An endpoint that rejects unknown fields fails the stream with a 400 or 422; build
its config with `OpenAICompatibleConfig.fromValues(..., streamUsage = false)`. A dialect you wrote
yourself inherits `streamUsageOption = true`; override it to `false` if the provider rejects the
field.

### Requesty configs report `requesty`

A config loaded from a `provider = "requesty"` section reported `providerId` = `openai`, because
`OpenAIConfig` inferred its id from the base URL. `OpenAIConfig` now carries the id its descriptor
sets, in a new trailing field `explicitProviderId: Option[ProviderId] = None`
(`OpenAIConfig.fromValues` has a matching defaulted `providerId` parameter), so it reports
`requesty`; `provider = "openrouter"` sets `openrouter` the same way, so an OpenRouter section
with a proxy `baseUrl` still reaches OpenRouter. With `None` - a config built by hand, or
`provider = "openai"` - the id is inferred from the base URL as before.

What you may notice:

- **`llm4s-config-policy`** sees `requesty`: an `allowedProviders` list must name `requesty`
  rather than rely on `openai`, and model patterns match `requesty/<model>`.
- `LLMConnect.getClient(config)` on a Requesty config now dispatches to the `requesty`
  descriptor, which needs `llm4s-openai` - as the `openai` one did.
- `OpenAIConfig.toString` shows `providerId`; a pattern match destructuring all six fields must
  add a seventh.

### `OPENAI_COMPATIBLE_BASE_URL` and `OPENAI_COMPATIBLE_API_KEY`

These are now the conventional variables for a generic endpoint, named by
`OpenAICompatibleConfigKeys`. `Llm4sConfig` does not read them - the generic provider has no vendor
and so no shared credentials key, and nothing reads `LLM_MODEL` - so bind them in a section (`baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}`). The
chat-tui sample accepts `LLM_MODEL=openai-compatible/<model>` with them, and the config-policy
env check reads `OPENAI_COMPATIBLE_BASE_URL` as that provider's endpoint instead of
`OPENAI_BASE_URL`.

## Slice 5: Mistral, Cohere and Voyage leave core; core ships no provider

The last provider clients leave `llm4s-core` ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
This is in 0.5.0; `0.4.1` and earlier ship Mistral, Cohere and Voyage inside
`llm4s-core`.

| Provider | Now in | Add |
|---|---|---|
| Mistral (`provider = "mistral"`) | `llm4s-openai-compatible`, as a dialect | `"org.llm4s" %% "llm4s-openai-compatible"` |
| Cohere (`provider = "cohere"`) | `llm4s-openai-compatible`, as a dialect | `"org.llm4s" %% "llm4s-openai-compatible"` |
| Voyage AI embeddings (`EMBEDDING_MODEL=voyage/...`) | `llm4s-voyage` (`modules/providers/voyage`) | `"org.llm4s" %% "llm4s-voyage"` |

Package names are unchanged, and so are the names and constructors of `MistralClient`,
`MistralProvider`, `MistralConfig`, `CohereClient`, `CohereProvider`, `CohereConfig` and
`VoyageAIEmbeddingProvider`, so imports compile as before once the dependency is added. No
environment variable or config key changed its name; `llm4s.embeddings.voyage` moved to
`llm4s-voyage`'s `reference.conf`, so it exists exactly when the module is on the classpath.

### Mistral and Cohere stream now

Both are [OpenAI-compatible](#slice-5-llm4s-openai-compatible), so they are small dialects on
the shared `OpenAICompatibleClient` rather than clients of their own. Both gain **streaming**
(`streamComplete` returned "not supported" - [#925](https://github.com/llm4s/llm4s/issues/925)),
streamed tool calls and token usage, **tool calling** and structured output; their descriptors no
longer declare `streaming = false`.

- **Mistral** posts to `<baseUrl>/v1/chat/completions` as before (`baseUrl` is the API root,
  `https://api.mistral.ai`); a `baseUrl` already ending in `/v1` is no longer doubled. Tool-call ids
  are sent in the nine-character form Mistral insists on; a reasoning model's thinking is returned
  as `Completion.thinking`.
- **Cohere** now calls Cohere's
  [OpenAI-compatibility API](https://docs.cohere.com/docs/compatibility-api) instead of the native
  `/v2/chat`. `CohereConfig.DEFAULT_BASE_URL` is now `https://api.cohere.ai/compatibility/v1` (it
  was `https://api.cohere.com`). **A configured `baseUrl` keeps working:** one that does not end in
  `/compatibility/v1` is taken to be a Cohere API root, as it was, and gets `/compatibility/v1`
  appended (after a trailing `/v1` or `/v2` is dropped); `CohereConfig.fromValues` stores and logs
  the mapped URL, so `endpointUrl` and policy checks see where requests really go. A proxy that
  forwarded only `/v2/chat` must now forward `/compatibility/v1/chat/completions`. System messages
  go under the `developer` role and a JSON schema as `{"type": "json_object", "schema": ...}`, as
  Cohere documents.

Behaviour changes for both, now that they share the client DeepSeek, Z.ai and OpenRouter use:

1. **Reply text is not trimmed** (the old clients trimmed it).
2. **A reply with no text is an empty completion**, not a `ValidationError` - with tool calling,
   a reply may carry only tool calls.
3. **A missing `id` or `created` is left `""` / `0`** rather than a random UUID / the current time
   (Mistral), and Cohere's `created` now comes from the reply.
4. **A `ToolMessage` is sent** rather than refused (Mistral) or silently dropped (Cohere).
5. `CompletionOptions.reasoning` is still not sent to either: Mistral's `reasoning_effort` and
   Cohere's accept only some models or values.

The shared client itself changed for every provider on it: **streamed completions now report
token usage** and a cost estimate (they always came back with `usage = None`), and an empty
conversation fails with a `ValidationError` before any request is sent.

### Core ships no provider

With those three gone, `llm4s-core` holds no provider client, and the list that existed only
because it did is removed:

- **`BuiltinProviders` and `BuiltinProviderModule`** (`org.llm4s.llmconnect.provider`) are
  deleted, with core's `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` entry.
- **`ProviderRegistry.builtin` is deleted.** It would now be empty. Where discovery cannot run - a
  fat jar whose services files were overwritten - name the provider modules you ship:

  ```scala
  given ProviderRegistry =
    ProviderRegistry.ofModules(new Llm4sOpenAIModule, new Llm4sOpenAICompatibleModule, new Llm4sVoyageModule)
  ```

`ProviderRegistry.default` (discovery) is unchanged, and so is every call site that relies on it.
Core's `llmconnect/provider` package now holds only provider-neutral helpers: `CostEstimator`,
`EmbeddingProvider`, `HttpErrorMapper`, `MetricsRecording`, `ProviderExchangeRecorder` and
`ProviderResultOps`.

### Source breaks

1. **`BuiltinProviders`, `BuiltinProviderModule` and `ProviderRegistry.builtin` are removed** -
   see above.
2. **`ProviderModelListers.Mistral` is now `MistralModelLister`** (`org.llm4s.config`, in
   `llm4s-openai-compatible`); `MistralProvider.modelLister` returns it.
3. **`ConfigKeys.MISTRAL_API_KEY` and `MISTRAL_BASE_URL` are now on `OpenAICompatibleConfigKeys`**,
   and **`ConfigKeys.VOYAGE_API_KEY`, `VOYAGE_EMBEDDING_BASE_URL` and `VOYAGE_EMBEDDING_MODEL` on
   `VoyageConfigKeys`** (`llm4s-voyage`), both in `org.llm4s.config`. The strings are unchanged.
4. **`CohereConfig.DEFAULT_BASE_URL` changed value** - see above.
5. **`OpenAICompatibleDialect` gained four members** - `sendEmptyAssistantTurns`,
   `encodeToolCallId`, `systemRole` and `encodeResponseFormat` - each defaulting to the standard
   format, so an existing dialect compiles and behaves as before.

## Slice 5: `llm4s-openai-compatible`

The fifth provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying DeepSeek (`provider = "deepseek"`), Z.ai (`"zai"`), OpenRouter (`"openrouter"`) and a
new generic provider, `"openai-compatible"`, for any other endpoint that speaks the OpenAI
`/chat/completions` API. It is in the build but not yet in a release; `0.4.1` and earlier still
ship DeepSeek, Z.ai and OpenRouter inside `llm4s-core`, and have no generic provider.

Unlike the earlier carves this is a **consolidation, not a pure move**. DeepSeek, Z.ai and
OpenRouter each had their own ~400-line copy of the same SDK-free client; they are now thin
subclasses of one `OpenAICompatibleClient`, each with a small `OpenAICompatibleDialect` for what
genuinely differs (headers, content encoding, reasoning parameters, thinking extraction,
tool-call parsing). The module depends on nothing but `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % version
```

### What moved

| Code | Now in |
|---|---|
| `DeepSeekClient`, `DeepSeekProvider`, `ZaiClient`, `ZaiProvider`, `OpenRouterClient`, `OpenRouterProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-openai-compatible` |
| `DeepSeekConfig`, `ZaiConfig`, `OpenAIConfig` (`org.llm4s.llmconnect.config`) | `llm4s-openai-compatible` |
| `ProviderModelListers.DeepSeek` / `.OpenRouter` → `DeepSeekModelLister` / `OpenRouterModelLister` (`org.llm4s.config`) | `llm4s-openai-compatible` |
| `DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL` → `DeepSeekConfig.DEFAULT_BASE_URL` | `llm4s-openai-compatible` |
| `DefaultConfig.DEFAULT_OPENROUTER_BASE_URL` → `OpenRouterProvider.DEFAULT_BASE_URL` | `llm4s-openai-compatible` |
| `ConfigKeys.DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `OPENROUTER_BASE_URL` → `OpenAICompatibleConfigKeys` (`org.llm4s.config`) | `llm4s-openai-compatible` |
| the commented `deepseek-main`, `zai-main` and `openrouter-main` examples in `reference.conf` | `llm4s-openai-compatible`'s `reference.conf` |

New: `OpenAICompatibleClient`, `OpenAICompatibleDialect`, `OpenAICompatibleConfig`,
`OpenAICompatibleProvider`, `OpenAICompatibleModelLister`, `Llm4sOpenAICompatibleModule`.

Package names are unchanged, and so are the public shapes of the three clients - their
constructors and companion `apply` overloads - their descriptors and their configs, so
`new DeepSeekClient(config)` or `OpenRouterClient(config, metrics)` compile as before once the
dependency is added.

**`llm4s-openai` users:** `OpenAIConfig` moved here, and `llm4s-openai` now depends on
`llm4s-openai-compatible` to get it. That module brings no SDK, and nothing changes in your build.

### The generic `openai-compatible` provider

```hocon
llm4s.providers {
  local-vllm {
    provider = "openai-compatible"
    baseUrl = "http://localhost:8000/v1"   # required
    model = "Qwen/Qwen2.5-7B-Instruct"     # required
    # apiKey = ...                        # optional; no Authorization header without one
    # contextWindow = 32768               # optional; default 8192
    # reserveCompletion = 4096            # optional; default 2048
    # headers { X-Team = "search" }       # optional
  }
}
```

To support it, a named provider section may now carry `contextWindow`, `reserveCompletion` and a
`headers` object, which `NamedProviderConfig` exposes. Providers other than `openai-compatible`
ignore them. See [OpenAI-compatible endpoints](../guide/providers#openai-compatible-endpoints).

### Registration is the dependency

`llm4s-openai-compatible` declares `Llm4sOpenAICompatibleModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it. Without the dependency, `provider = "deepseek"`, `"zai"` and
`"openrouter"` fail with the registry's error, which names the providers that are registered.
`ProviderRegistry.builtin` no longer includes them; where discovery cannot run:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOpenAICompatibleModule) // `builtin` was removed later in slice 5
```

### Behaviour changes

The three copies had drifted apart; the shared client does each thing one way:

1. **DeepSeek returns thinking.** `deepseek-reasoner`'s `reasoning_content` is now
   `Completion.thinking` and is streamed as thinking deltas; the old client dropped it. Its
   `completion_tokens_details.reasoning_tokens` is `TokenUsage.thinkingTokens`.
2. **The stream body is closed on every failure.** Z.ai and OpenRouter left it open on an error
   status.
3. **Every call records exactly one provider exchange**, including a request that cannot be sent
   (DeepSeek and Z.ai recorded none for a non-streaming one; OpenRouter recorded failures twice).
4. **OpenRouter sends assistant content as a string.** The old client passed an `Option` through
   ujson's implicit conversion, so `"hi"` went out as `["hi"]` and no content as `[]`; it is now
   `"hi"`, `""` or `null`.
5. **Z.ai reads usage given as an array**, which its client meant to support but never matched.
6. **Streamed tool calls keep all their arguments.** A tool call streamed across several deltas
   lost every fragment after the first in all three clients: continuations carry only an `index`,
   the missing id was defaulted to `""`, and `StreamingAccumulator` skips a chunk with no id. The
   shared client now maps each index to its call's id for the life of the stream, and a streamed
   `Completion` reports its tool calls in `toolCalls`, as a non-streaming one does.
7. **A reply's `message.text` (`contentOpt` until #2809) is `None` when the reply has no text** for all three (it was
   `Some("")` for DeepSeek and Z.ai); `Completion.content` is `""` either way.
8. **Replies are read leniently where the copies threw**: a missing `id`, `created` or `model`
   defaults, a streamed event with no `choices` is skipped, and a malformed non-streaming reply is
   a `Left` for all three (Z.ai could throw). OpenRouter keeps its strict tool-call parsing;
   DeepSeek and Z.ai keep their lenient one.

### Source breaks

1. **`ProviderModelListers.DeepSeek` and `.OpenRouter` are now `DeepSeekModelLister` and
   `OpenRouterModelLister`**, in the same package. The descriptors' `modelLister` returns them.
2. **`DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL` and `DEFAULT_OPENROUTER_BASE_URL` are now
   `DeepSeekConfig.DEFAULT_BASE_URL` and `OpenRouterProvider.DEFAULT_BASE_URL`.** The values are
   unchanged.
3. **`ConfigKeys.DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL` and `OPENROUTER_BASE_URL` are now on
   `OpenAICompatibleConfigKeys`**, in the same package. The strings are unchanged.
4. **`ProviderRegistry.builtin` no longer includes `deepseek`, `zai` or `openrouter`** - see above.
5. **`NamedProviderConfig` and `RawNamedProviderSection` gained three trailing fields** with
   defaults (`contextWindow`, `reserveCompletion`, `headers`), so construction by name or by
   position is unaffected; a pattern match that destructures all seven fields must add three.
   `NamedProviderConfig.toString` now redacts the API key and header values.
6. **`ProviderModelListers.openAICompatible` gained two defaulted parameters**, `extraHeaders`
   and `apiKeyRequired`; existing calls compile unchanged. It no longer special-cases OpenRouter,
   whose lister passes its headers explicitly.

7. **`OpenRouterToolCallDeserializer` is removed** from `org.llm4s.llmconnect.serialization`. No
   client used it after the consolidation. The "double-nested" array it parsed was an artefact
   of the old `OpenRouterClient`, not OpenRouter's format; use `StandardToolCallDeserializer`
   (which stays), as `OpenRouterClient` now does.
8. **`StreamingResponseHandler` is removed**, with `BaseStreamingResponseHandler`,
   `OpenAIStreamingHandler`, `AnthropicStreamingHandler` and `StreamingResponseHandler.forProvider`
   (`org.llm4s.llmconnect.streaming`). No client streamed through them - each parses its own
   stream and accumulates with `StreamingAccumulator`, which stays - and `forProvider` was called
   only from tests. To assemble streamed chunks yourself, feed them to a `StreamingAccumulator`.

### What did *not* change

Every configuration key and environment variable for DeepSeek, Z.ai and OpenRouter: their
`provider` ids, `apiKey`, `baseUrl` and `organization`, and their default base URLs.

## Slice 5: `llm4s-openai` moves to `openai-java`

`llm4s-openai`'s `OpenAIClient` - behind `provider = "openai"`, `"azure"` and `"requesty"` - now
runs on OpenAI's official Java SDK, `com.openai:openai-java`, instead of Microsoft's
`com.azure:azure-ai-openai`. Microsoft has
[deprecated that SDK](https://learn.microsoft.com/en-us/java/api/overview/azure/ai-openai-readme?view=azure-java-preview)
(its last release, 1.0.0-beta.16, was on 2025-03-26) and points to `openai-java`, which also
covers Azure OpenAI ([#1132](https://github.com/llm4s/llm4s/issues/1132)).

**Most users change nothing.** `OpenAIClient`'s constructors and `apply` overloads,
`OpenAIProvider`, `AzureProvider`, `RequestyProvider`, `OpenAIConfig` and `AzureConfig`, every
configuration key and every environment variable are unchanged. Your dependency tree changes:
`llm4s-openai` now brings OkHttp, Jackson (2.x) and the Kotlin standard library rather than the
Azure core libraries. `openai-java` checks its Jackson version when a client is built and fails
on a Jackson it cannot use (a different major version, anything before 2.13.4, or 2.18.1); if
your application pins Jackson, keep it on a compatible 2.x.

### How Azure is configured

As before: `endpoint` is the resource endpoint (`https://<resource>.openai.azure.com`), `model`
is the deployment name, `apiKey` is sent as the `api-key` header, and `apiVersion` as the
`api-version` query parameter, so a request goes to
`<endpoint>/openai/deployments/<deployment>/chat/completions?api-version=<version>`. The client
tells the SDK this is Azure rather than letting it guess from the host name, so an endpoint on
your own domain (API Management, a private endpoint) keeps working. Two things are new:

- **`apiVersion` takes either form.** The wire form (`2024-10-21`, `2025-01-01-preview`), which
  the docs have always shown, now works; before, only the Azure SDK's constant names
  (`V2024_10_21`, `V2025_01_01_PREVIEW` - the form of `AzureConfig.DEFAULT_API_VERSION`) did.
  Both still work.
- **An endpoint ending in `/openai/v1`** uses Azure's unified v1 API: the deployment goes in the
  request body and `api-version` is sent only if you set one other than the default.

### Source break: `AzureToolHelper` is now `OpenAIToolHelper`

`AzureToolHelper` took and returned Azure SDK types, so it could not survive the SDK. It is
replaced, in the same package (`org.llm4s.toolapi`) and module, by `OpenAIToolHelper` over
`openai-java`'s types (`com.openai.models.chat.completions`):

| Before (`AzureToolHelper`) | Now (`OpenAIToolHelper`) |
|---|---|
| `addToolsToOptions(registry, options: ChatCompletionsOptions): ChatCompletionsOptions` | `addToolsToParams(registry, builder: ChatCompletionCreateParams.Builder): ChatCompletionCreateParams.Builder` |
| `convertToolRegistryToAzureTools(registry): java.util.List[ChatCompletionsToolDefinition]` | `convertToolRegistryToOpenAITools(registry): java.util.List[ChatCompletionTool]` |

```scala
import org.llm4s.toolapi.{ OpenAIToolHelper, ToolRegistry }
import com.openai.models.chat.completions.ChatCompletionCreateParams

val params = OpenAIToolHelper
  .addToolsToParams(new ToolRegistry(tools), ChatCompletionCreateParams.builder().model("gpt-4o"))
```

If you called the Azure SDK yourself alongside llm4s, add `com.azure:azure-ai-openai` to your
own build: `llm4s-openai` no longer brings it.

After 0.5.1 `OpenAIToolHelper` is internal too: see [`OpenAIToolHelper` is internal](#openaitoolhelper-is-internal-2360).

### Behaviour changes

1. **Streamed tool calls keep their arguments.** A streamed tool call arrives split across
   deltas, and only the first carries the call's `id`; the Azure SDK path keyed calls by `id`, so
   every later fragment - usually all of the arguments - was lost. Continuations are now matched
   by `index`, fragments are concatenated verbatim, and a streamed `Completion` reports its tool
   calls in `toolCalls` as a non-streaming one does.
2. **`OpenAIConfig.organization` is sent** as the `OpenAI-Organization` header. The Azure SDK
   ignored it.
3. **HTTP errors map by status code**: 401 and 403 to `AuthenticationError`, 429 to
   `RateLimitError`, 400 to `ValidationError`, anything else to `ServiceError`, each naming the
   provider. Before, they were classified by searching the exception message, and most became
   `UnknownError`.
4. **Streamed token usage** is read from whichever chunk carries it, including a usage-only final
   chunk, not only from the chunk with the finish reason.
5. **`close()` releases the SDK's HTTP client** (connections and threads); before it released
   nothing.
6. **Azure and Requesty are labelled as themselves.** Their errors (`AuthenticationError.provider`
   and the error context), metrics and provider-exchange log now say `azure` and `requesty`;
   every one said `openai` before. Requesty takes its label from its descriptor. A Requesty
   config loaded from a `provider = "requesty"` section also reports `providerId` = `requesty`
   since a later fix (see [above](#requesty-configs-report-requesty)); only an `OpenAIConfig`
   you build by hand with Requesty's base URL still infers `openai` from it - pass
   `providerId = Some(ProviderId("requesty"))` to `OpenAIConfig.fromValues` for that.
7. **Several streamed tool calls come back in the order the stream named them**, in
   `Completion.toolCalls` and on the message. `llm4s-core`'s `StreamingAccumulator` kept them in
   an unordered map, so they could come back in hash order; this applies to every client that
   streams through it.

## Slice 5: `llm4s-openai`

The fourth provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying the three providers that share `OpenAIClient` - OpenAI (`provider = "openai"`), Azure
OpenAI (`"azure"`) and Requesty (`"requesty"`) - and the OpenAI embedding provider
(`EMBEDDING_MODEL=openai/<model>`). It is in the build but not yet in a release; `0.4.1` and
earlier still ship them inside `llm4s-core`.

With it goes the Azure OpenAI SDK (`com.azure:azure-ai-openai`), which `OpenAIClient` is built
on: `llm4s-core` no longer depends on it, and with the Anthropic SDK already gone, core now
depends on no vendor SDK at all.

OpenRouter, DeepSeek and Z.ai are **not** in this module. They speak the OpenAI wire format but
each has its own client with no SDK, so bundling them here would make their users download the
Azure SDK for nothing. They went on to `llm4s-openai-compatible` - see
[above](#slice-5-llm4s-openai-compatible).

### What moved

| Code | Now in |
|---|---|
| `OpenAIClient`, `OpenAIProvider`, `AzureProvider`, `RequestyProvider`, `OpenAIEmbeddingProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-openai` |
| `AzureConfig` (`org.llm4s.llmconnect.config`) | `llm4s-openai` |
| `AzureToolHelper` (`org.llm4s.toolapi`) | `llm4s-openai` |
| `ProviderModelListers.OpenAI` / `.Requesty` → `OpenAIModelLister` / `RequestyModelLister` (`org.llm4s.config`) | `llm4s-openai` |
| `DefaultConfig.DEFAULT_OPENAI_BASE_URL` → `OpenAIProvider.DEFAULT_BASE_URL` | `llm4s-openai` |
| `DefaultConfig.DEFAULT_REQUESTY_BASE_URL` → `RequestyProvider.DEFAULT_BASE_URL` | `llm4s-openai` |
| `DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW` → `AzureConfig.DEFAULT_API_VERSION` | `llm4s-openai` |
| `ConfigKeys.OPENAI_*`, `REQUESTY_BASE_URL`, `AZURE_*`, `OPENAI_EMBEDDING_*` → `OpenAIConfigKeys` (`org.llm4s.config`) | `llm4s-openai` |
| the commented `openai-main`, `requesty-main` and `azure-main` examples, and the `llm4s.embeddings.openai` block, in `reference.conf` | `llm4s-openai`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.provider.OpenAIClient` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai" % version
```

### What stayed in core

- **`OpenAIConfig`**, because OpenRouter builds one too: `OpenRouterProvider` and
  `OpenRouterClient` take an `OpenAIConfig`, and its `providerId` answers `openrouter` for an
  OpenRouter base URL. It moved when OpenRouter did, to `llm4s-openai-compatible`, which
  `llm4s-openai` now depends on.
- **`OpenAIStreamingHandler`** (since removed with `StreamingResponseHandler`; see
  [`llm4s-openai-compatible`](#slice-5-llm4s-openai-compatible)), the SSE parser behind
  `StreamingResponseHandler.forProvider("openai" | "azure" | "openrouter")`, which OpenRouter's
  path shares. `OpenAIClient` streams through the Azure SDK.
- **`ConfigKeys.OPENROUTER_BASE_URL`**, still naming `OPENAI_BASE_URL` (since moved to
  `OpenAICompatibleConfigKeys`).
- Strings that do not reach a client: `ToolRegistry.getOpenAITools` and
  `getToolDefinitionsSafe("openai")`, the `openai/...` model-registry data, the `sk-` secret
  pattern, config-policy allow-lists.

### Registration is the dependency

`llm4s-openai` declares `Llm4sOpenAIModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "openai"`, `"azure"` and `"requesty"`, and
`EMBEDDING_MODEL=openai/<model>`, resolve as before. Without the dependency they fail with the
registry's error, which says the provider is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes them. If you used `builtin` to avoid classpath
discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOpenAIModule) // `builtin` was removed later in slice 5
```

**`llm4s-rag` users:** `RAGConfig.default` embeds with `openai/text-embedding-3-small`. A
pipeline built from the default therefore needs `llm4s-openai` too; otherwise name the provider
you ship with `.withEmbeddings("voyage", ...)` (or `"ollama"` with `llm4s-ollama`).
`llm4s-rag` does not depend on `llm4s-openai` itself, so it does not bring the Azure SDK.

### Source breaks

1. **`ToolRegistry.addToAzureOptions(options)` is removed.** Its signature exposed the Azure SDK
   type `ChatCompletionsOptions` from core's `ToolRegistry`, so core could not drop the SDK while
   it existed. Call `AzureToolHelper.addToolsToOptions(registry, options)` instead - same
   package (`org.llm4s.toolapi`), now in `llm4s-openai`, with the same result.
2. **`ProviderModelListers.OpenAI` and `.Requesty` are now `OpenAIModelLister` and
   `RequestyModelLister`**, in the same package (`org.llm4s.config`). The descriptors'
   `modelLister` returns them, so code that reached a lister through the descriptor is
   unaffected.
3. **Three defaults moved off `DefaultConfig`**: `DEFAULT_OPENAI_BASE_URL` is now
   `OpenAIProvider.DEFAULT_BASE_URL`, `DEFAULT_REQUESTY_BASE_URL` is now
   `RequestyProvider.DEFAULT_BASE_URL`, and `DEFAULT_AZURE_V2025_01_01_PREVIEW` is now
   `AzureConfig.DEFAULT_API_VERSION`. The values are unchanged. The base URLs live on the
   descriptors rather than on `OpenAIConfig` because `OpenAIConfig` stays in core.
4. **`ConfigKeys.OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_ORG`, `REQUESTY_BASE_URL`,
   `AZURE_API_BASE`, `AZURE_API_KEY`, `AZURE_API_VERSION`, `OPENAI_EMBEDDING_BASE_URL` and
   `OPENAI_EMBEDDING_MODEL` are now on `OpenAIConfigKeys`**, in the same package, as
   `ConfigKeys.ANTHROPIC_*` became `AnthropicConfigKeys`. The strings are unchanged.
5. **`ProviderRegistry.builtin` no longer includes `openai`, `azure` or `requesty`, nor the
   `openai` embedding provider** - see above.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "openai"`, `"azure"` or `"requesty"` and their `apiKey`, `baseUrl`, `organization`,
`endpoint` and `apiVersion`; `llm4s.embeddings.openai.*`, `OPENAI_EMBEDDING_BASE_URL` and
`OPENAI_EMBEDDING_MODEL`; and `llm4s.openai.apiKey` as the key OpenAI embeddings share with chat.
(A later change dropped that `llm4s.openai.apiKey` fallback: the key is
`llm4s.embeddings.openai.apiKey` alone - see
[From `LLM_MODEL` to named provider sections](#from-llm_model-to-named-provider-sections).)

## Slice 5: `llm4s-anthropic`

The third provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying the Anthropic Claude chat provider (`provider = "anthropic"`). It is in the build but
not yet in a release; `0.4.1` and earlier still ship it inside `llm4s-core`.

With it goes the Anthropic Java SDK (`com.anthropic:anthropic-java`): `llm4s-core` no longer
depends on it, so an application that does not use Anthropic no longer carries it. It was also
declared, unused, by `llm4s-workspace-client`, and has been removed from there too.

### What moved

| Code | Now in |
|---|---|
| `AnthropicClient`, `AnthropicProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-anthropic` |
| `AnthropicConfig` (`org.llm4s.llmconnect.config`) | `llm4s-anthropic` |
| `ProviderModelListers.Anthropic` → `AnthropicModelLister` (`org.llm4s.config`) | `llm4s-anthropic` |
| `DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` → `AnthropicConfig.DEFAULT_BASE_URL` | `llm4s-anthropic` |
| `ConfigKeys.ANTHROPIC_API_KEY`, `ConfigKeys.ANTHROPIC_BASE_URL` → `AnthropicConfigKeys` (`org.llm4s.config`) | `llm4s-anthropic` |
| the commented `anthropic-main` example in `reference.conf` | `llm4s-anthropic`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.config.AnthropicConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-anthropic" % version
```

### Registration is the dependency

`llm4s-anthropic` declares `Llm4sAnthropicModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "anthropic"` resolves as before. Without the
dependency it fails with the registry's error, which says the provider is not registered and
names the providers that are.

`ProviderRegistry.builtin` no longer includes Anthropic. If you used `builtin` to avoid
classpath discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sAnthropicModule) // `builtin` was removed later in slice 5
```

### Source breaks

Three names could not keep their fully-qualified path, because they were members of objects
that stay in core:

1. **`ProviderModelListers.Anthropic` is now `AnthropicModelLister`**, in the same package
   (`org.llm4s.config`). `AnthropicProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` is now `AnthropicConfig.DEFAULT_BASE_URL`**, the
   same place `GeminiConfig`, `DeepSeekConfig` and `MistralConfig` keep theirs. The value is
   unchanged.
3. **`ConfigKeys.ANTHROPIC_API_KEY` and `ConfigKeys.ANTHROPIC_BASE_URL` are now
   `AnthropicConfigKeys.ANTHROPIC_API_KEY` and `AnthropicConfigKeys.ANTHROPIC_BASE_URL`**, in the
   same package, as `ConfigKeys.OLLAMA_*` became `OllamaConfigKeys`. The strings are unchanged.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "anthropic"`, `apiKey` and the optional `baseUrl`.

What names Anthropic without depending on its client stays in core and answers the same with or
without `llm4s-anthropic`: `ToolRegistry.getToolDefinitionsSafe("anthropic")`, the
`anthropic/...` entries in the embedded model registry data, the `sk-ant-` secret pattern,
config-policy allow-lists, and `AnthropicStreamingHandler` - the SDK-free SSE parser behind
`StreamingResponseHandler.forProvider("anthropic")`, which `AnthropicClient` does not use.

## Slice 5: `llm4s-gemini`

The second provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying both of Google's chat providers: the Gemini API (`provider = "gemini"`, alias
`"google"`) and Vertex AI (`provider = "vertexai"`, alias `"vertex"`). It is in the build but
not yet in a release; `0.4.1` and earlier still ship both inside `llm4s-core`.

### Why Vertex AI is in the same module

`VertexAIClient` only calls Google's `publishers/google` models - Gemini - using the same JSON
request and response format as `GeminiClient`. The two differ in endpoint (Vertex is scoped to
a GCP project and region on `aiplatform.googleapis.com`) and in authentication (Vertex uses
OAuth2, implemented by `VertexAIAuthProvider` without a Google SDK), not in dependencies. So
bundling them costs a Gemini-API user nothing, while splitting Vertex AI out later would be a
breaking move for its users; bundling now is the direction that stays safe.

### What moved

| Code | Now in |
|---|---|
| `GeminiClient`, `GeminiProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-gemini` |
| `VertexAIClient`, `VertexAIProvider`, `VertexAIAuthProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-gemini` |
| `GeminiConfig`, `VertexAIConfig` (`org.llm4s.llmconnect.config`) | `llm4s-gemini` |
| `ProviderModelListers.Gemini` → `GeminiModelLister` (`org.llm4s.config`) | `llm4s-gemini` |
| `DefaultConfig.DEFAULT_GEMINI_BASE_URL` → `GeminiConfig.DEFAULT_BASE_URL` | `llm4s-gemini` |
| `DefaultConfig.DEFAULT_VERTEXAI_LOCATION` → `VertexAIConfig.DEFAULT_LOCATION` (already existed) | `llm4s-gemini` |
| the commented `gemini-main` example in `reference.conf` | `llm4s-gemini`'s `reference.conf`, with a `vertexai-main` example beside it |

Package names are unchanged, so `import org.llm4s.llmconnect.config.GeminiConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-gemini" % version
```

### Registration is the dependency

`llm4s-gemini` declares `Llm4sGeminiModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "gemini"`, `"google"`, `"vertexai"` and
`"vertex"` resolve as before. Without the dependency they fail with the registry's error, which
says the provider is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes Gemini or Vertex AI. If you used `builtin` to
avoid classpath discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sGeminiModule) // `builtin` was removed later in slice 5
```

### Source breaks

Three names could not keep their fully-qualified path, because they were members of objects
that stay in core:

1. **`ProviderModelListers.Gemini` is now `GeminiModelLister`**, in the same package
   (`org.llm4s.config`). `GeminiProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`DefaultConfig.DEFAULT_GEMINI_BASE_URL` is now `GeminiConfig.DEFAULT_BASE_URL`**, the
   same place `DeepSeekConfig`, `CohereConfig` and `MistralConfig` keep theirs. The value is
   unchanged.
3. **`DefaultConfig.DEFAULT_VERTEXAI_LOCATION` is removed**; use
   `VertexAIConfig.DEFAULT_LOCATION`, which already held the same `"us-central1"`.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "gemini"` or `"vertexai"`, the Vertex AI reading of `endpoint` (GCP project id),
`organization` (region) and `apiKey` (credential file path), and `GOOGLE_APPLICATION_CREDENTIALS`
for Vertex AI authentication. (`endpoint` and `organization` have since become Vertex AI's
`project` and `location`; see
[the note above](#vertex-ai-endpoint-becomes-project-organization-becomes-location).)

Strings that name Gemini without depending on its client stay in core and answer the same with
or without `llm4s-gemini`: `ToolRegistry.getToolDefinitionsSafe("gemini")`, the `gemini/...`
entries in the embedded model registry data, and config-policy allow-lists.

## Slice 4 (close-out): the last closed provider list, and `fromValues` stops throwing

The last items deferred from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)).
Both are source breaks, taken now because the API is not yet frozen; neither has a
deprecated shim, following the precedent of `ProviderKind` in PR 1. It is in the build but not
yet in a release, so nothing here affects `0.4.1` or earlier.

### `org.llm4s.rag.EmbeddingProvider` is gone

`llm4s-rag` kept its own closed list of embedding providers - `EmbeddingProvider.OpenAI`,
`Voyage` and `Ollama` - duplicating what the `ProviderRegistry` has known since PR 4. It was
wrong in both directions: an embedding provider from its own module could not be named through
it, and it named `ollama` whether or not `llm4s-ollama` was on the classpath. It also shared its
simple name with `org.llm4s.llmconnect.provider.EmbeddingProvider`, the embedding client trait.

`RAGConfig` now names the provider by id, the same id as in `EMBEDDING_MODEL=<id>/<model>`, and
`RAG.build` resolves it through the registry:

```scala
// Before
import org.llm4s.rag.{ EmbeddingProvider, RAG }

RAG.builder()
  .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")

EmbeddingProvider.fromString(name).toRight(...)   // to turn a configured name into one

// After
import org.llm4s.rag.RAG

RAG.builder()
  .withEmbeddings("openai", "text-embedding-3-large")

RAG.builder().withEmbeddings(name)                 // any registered id or alias; no conversion
```

`build` on a `RAGConfig` is an extension from `RAG.RAGConfigOps`, so the call site needs
`import org.llm4s.rag.RAG.RAGConfigOps` and a `ModelRegistryService` given (for example from
`Llm4sConfig.modelRegistryService()`); it takes only a `using` clause, so it is `.build`, not `.build()`.

`RAGConfig.embeddingProvider` is a `ProviderId`, so `config.embeddingProvider.name` becomes
`config.embeddingProvider.asString`. `RAG.build` and `RAGConfig#build` take an implicit
`ProviderRegistry`, resolved to `ProviderRegistry.default` when none is in scope, so existing
call sites compile unchanged and an application with its own registry reaches the providers in
it. `EmbeddingProvider.values` has no replacement in `llm4s-rag`: the providers are
`summon[ProviderRegistry].embeddingIds`.

### Behaviour changes

- An id that is not registered fails `RAG.build` with the registry's own "Embedding provider
  '...' is not registered" error, naming the ids that are - where `fromString` returned `None`
  and every caller wrote its own message. The resolver is asked for the provider's canonical id,
  so an alias such as `voyageai` arrives as `voyage`.
- **The model default moved from `llm4s-rag` into the provider.** `RAG` used to pick a model
  per provider from its own table, and ignore the model in the `EmbeddingProviderConfig` the
  resolver returned. `RAGConfig()` still defaults to `openai` / `text-embedding-3-small`, but
  `withEmbeddings(provider)` without a model now clears any model set earlier and uses, in
  order: the resolved config's model, then the provider's `configSpec.defaultModel`. With
  `Llm4sConfig.embeddings()` as the resolver, that is the model you configured. A provider with
  neither fails the build naming the provider, rather than guessing.
- Dimensions come from the provider's `dimensionsOf(model)` when not set explicitly, rather than
  from a second table in `llm4s-rag`. A model its provider does not declare keeps the old
  fallback of 1536.

### `fromValues` returns `Result`

Every `ProviderConfig` subtype's `fromValues` factory - `OpenAIConfig`, `AzureConfig`,
`AnthropicConfig`, `ZaiConfig`, `GeminiConfig`, `DeepSeekConfig`, `CohereConfig`,
`MistralConfig`, `VertexAIConfig` and `OllamaConfig` - validated its arguments with
`require(...)`, so a blank API key threw `IllegalArgumentException` out of a library whose rule
is that errors are values. They now return `Result[XConfig]`, and a blank credential or endpoint
is a `ConfigurationError` carrying the same message as before (`"OpenAI apiKey must be
non-empty"`) with the field in `missingKeys`.

```scala
// Before
val config: OpenAIConfig = OpenAIConfig.fromValues("gpt-4o", apiKey, baseUrl)
val client = LLMConnect.getClient(config)

// After
val client: Result[LLMClient] =
  OpenAIConfig.fromValues("gpt-4o", apiKey, baseUrl).flatMap(LLMConnect.getClient(_))
```

A `ProviderDescriptor.buildConfig` that returned `fromValues` from a `for`'s `yield`, or
through `.map`, binds it as a generator or uses `.flatMap` instead:

```scala
// Before
for
  apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
  baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
yield AcmeConfig.fromValues(section.model.asString, apiKey, baseUrl)

// After
for
  apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
  baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
  config  <- AcmeConfig.fromValues(section.model.asString, apiKey, baseUrl)
yield config
```

Code that caught the exception - `Try(OpenAIConfig.fromValues(...)).toEither`, or a test's
`an[IllegalArgumentException] should be thrownBy` - matches on the `Left` instead. Nothing
reachable from configuration changes: `Llm4sConfig` and the provider descriptors already
rejected a missing key before calling `fromValues`, and now propagate its `Left` rather than
letting a blank one throw.

## Slice 5: `llm4s-ollama`

The first provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
Ollama goes first because it has the smallest client, no vendor SDK, and a live `@Ollama`
integration tier - so the carve is checked against a real server rather than mocks. It is in
the build but not yet in a release; `0.4.1` and earlier still ship Ollama inside `llm4s-core`.

### What moved

| Code | Now in |
|---|---|
| `OllamaClient`, `OllamaProvider`, `OllamaEmbeddingProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-ollama` |
| `OllamaConfig` (`org.llm4s.llmconnect.config`) | `llm4s-ollama` |
| `ProviderModelListers.Ollama` → `OllamaModelLister` (`org.llm4s.config`) | `llm4s-ollama` |
| `ConfigKeys.OLLAMA_*` → `OllamaConfigKeys.OLLAMA_*` (`org.llm4s.config`) | `llm4s-ollama` |
| the `llm4s.embeddings.ollama` `reference.conf` block | `llm4s-ollama`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.config.OllamaConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ollama" % version
```

### Registration is the dependency

`llm4s-ollama` declares `Llm4sOllamaModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "ollama"` and
`EMBEDDING_MODEL=ollama/<model>` resolve as before. Without the dependency both fail with the
registry's error, which says `ollama` is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes Ollama, because core no longer ships it. If you
used `builtin` to avoid classpath discovery (a shaded fat jar, typically), add the module
explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOllamaModule) // `builtin` was removed later in slice 5
```

### Source breaks

Two names could not keep their fully-qualified path, because they were members of objects that
stay in core:

1. **`ProviderModelListers.Ollama` is now `OllamaModelLister`**, in the same package
   (`org.llm4s.config`). `OllamaProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`ConfigKeys.OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_BASE_URL` and `OLLAMA_EMBEDDING_MODEL`
   are now on `OllamaConfigKeys`**, also in `org.llm4s.config`. The variable names themselves
   are unchanged.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "ollama"`, `llm4s.embeddings.ollama.*`, `OLLAMA_EMBEDDING_BASE_URL` and
`OLLAMA_EMBEDDING_MODEL`. The `reference.conf` block moved rather than changed; HOCON merges
reference files across jars, so the keys exist exactly when the provider does.

`TokenizerMapping` still recognises the `ollama/` model-name prefix. It is a naming
convention on model strings rather than a reference to the provider, and it gives the same
answer whether or not `llm4s-ollama` is on the classpath.

## Slice 4 follow-up: embedding dimensions move into the provider

One of the two items deferred from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)),
and the precursor to carving `llm4s-ollama` ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
`ModelDimensionRegistry` was the last central provider list on the embedding side: a map in
`llm4s-core` covering `openai`, `voyage` and `local`. It had no `ollama` entry, so the documented

```bash
EMBEDDING_MODEL=ollama/nomic-embed-text
```

failed at `Llm4sConfig.textEmbeddingModel()` with `Unknown model 'nomic-embed-text' for
provider 'ollama'`, and `RAGASFactory.fromConfigs` papered over the same gap with
`.getOrElse(1536)` for a model that is 768-dimensional.

### Declaring dimensions

An embedding provider now declares the dimensions of the models it knows, alongside its
`configSpec`:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  override val modelDimensions = Map(
    "jina-embeddings-v3" -> 1024
  )
```

A provider whose model names have variants overrides `dimensionsOf(model)` instead - Ollama
folds a `:latest` tag onto the untagged name, and no other tag, because other tags of one model
can differ in size. A model a provider does not declare still embeds; only a caller that needs
its dimensionality up front is told it is unknown.

### Source-compatible signature changes

`ModelDimensionRegistry.getDimension`, `RAGASFactory.fromConfigs` and
`RAGASFactory.basicFromConfigs` take an implicit `ProviderRegistry`, resolved to
`ProviderRegistry.default` when none is in scope. Existing call sites compile unchanged; a
caller with its own registry now reaches the providers in it.

`ModelDimensionRegistry.localDimension(model)` answers the local non-text encoders
(`openclip-vit-b32`, `wav2vec2-base`, `timesformer-base`), which have no descriptor because
nothing can be configured with them. `getDimension("local", ...)` still works.

### Behaviour changes

- `RAGASFactory.fromConfigs` and `basicFromConfigs` return the lookup's `Left` for an embedding
  model its provider does not declare, instead of assuming 1536 dimensions. Build the
  `EmbeddingModelConfig` yourself and call `RAGASFactory.create` or `basic` for such a model.
- `getDimension` for an unregistered provider returns the registry's "not registered" error,
  naming the embedding providers that are, rather than "Unknown model".
- `voyage-3-large` resolves to 1024, its default output size, not 1536.

## Slice 4 (PR 5): embedding config moves into the provider

The fifth slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)), and the
follow-up PR 4 named. PR 4 made an embedding provider *resolvable* from its own module;
its configuration was still core's business:

```scala
final private case class EmbeddingsOllamaSection(apiKey: …, baseUrl: …, model: …)
implicit private val embeddingsOllamaSectionReader = …
private val DefaultOllamaEmbeddingBaseUrl = "http://localhost:11434"
private def buildOllamaEmbeddings(…) = …
// plus two `match` arms
```

— a typed case class, a PureConfig reader, a default, a builder and two dispatch arms per
provider. A third-party embedding provider could be registered and then had nothing to be
configured *with*.

**Nothing changes for users.** `EMBEDDING_MODEL`, `EMBEDDING_PROVIDER`, `OPENAI_API_KEY`,
`VOYAGE_API_KEY`, `OLLAMA_EMBEDDING_BASE_URL` and every `llm4s.embeddings.<id>` key behave
exactly as before.

### The section shape is core's; what it means is the provider's

`llm4s-core` now parses one uniform shape - `apiKey`, `baseUrl`, `model` - for whichever
provider was selected, and hands it to the descriptor:

```scala
def buildConfig(section: EmbeddingProviderSection, modelOverride: Option[String]): Result[EmbeddingProviderConfig]
```

Everything it needs arrives in `section`, already typed. A descriptor reads no configuration
itself: raw config access stays in `org.llm4s.config`, which is the boundary AGENTS.md sets.

Most providers never implement it. Declaring an `EmbeddingConfigSpec` is enough, and the
default implementation resolves the three fields against it:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  override val configSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.jina.ai/v1"),
    apiKeyEnv      = Some("JINA_API_KEY")   // named in the error when it is missing
  )
```

### Defaults are code, environment bindings are HOCON

They used to be both. `reference.conf` said `baseUrl = "http://localhost:11434"` and
`EmbeddingsConfigLoader` said `DefaultOllamaEmbeddingBaseUrl`, with nothing keeping them in
step. The default now lives only in the descriptor's `EmbeddingConfigSpec`, and each
provider's `reference.conf` block is reduced to the environment variables it binds:

```hocon
ollama {
  baseUrl = ${?OLLAMA_EMBEDDING_BASE_URL}
  model   = ${?OLLAMA_EMBEDDING_MODEL}
}
```

That block is keyed by **provider id**, so it travels with the provider when the provider moves
to its own module - HOCON merges these across jars. `ProviderId` canonicalises (trim, lowercase)
but does not restrict, so an id containing a dot is legal and must be quoted as a single HOCON
key; `EmbeddingConfigSpec.sectionPath` / `fieldPath` build the path that way, and the paths
named in errors are the paths to write:

```hocon
llm4s.embeddings."acme.embeddings" { apiKey = ${?ACME_API_KEY} }
```

(Chat config cannot do this: it is keyed by the user's *instance* name, which is why
`ProviderConfigSpec.defaultBaseUrl` is code and says so.)

### A key that lives somewhere else

> **Superseded:** `apiKeyPath` was removed in favour of the shared
> `llm4s.credentials.<id>.apiKey` - see
> [Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider). `apiKeyEnv` is now a
> `Seq[String]`.

A provider whose key is kept outside its own `llm4s.embeddings.<id>` section declares where to
look instead of the loader special-casing it, and that declaration is also what makes the error
name the place the key is really set:

```scala
override val configSpec = EmbeddingConfigSpec(
  requiresApiKey = true,
  apiKeyPath     = Some("llm4s.acme.apiKey"),
  apiKeyEnv      = Some("ACME_API_KEY"),
  …
)
```

> Missing acme embeddings apiKey (llm4s.acme.apiKey / ACME_API_KEY)

OpenAI was the case this was written for, reading `llm4s.openai.apiKey`; it no longer uses it
(its key is its own `llm4s.embeddings.openai.apiKey`), and no provider in this repository does.

`apiKeyPath` is a *declaration*, not a read: `EmbeddingsConfigLoader` resolves it and hands
the value back in the section before calling `buildConfig`. The provider owns the knowledge of
*where* its key lives; `org.llm4s.config` keeps sole ownership of *reading* it.

### The embedding config entry points take the registry

```scala
def embeddings()(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)]
def loadTextEmbeddingModel()(using ProviderRegistry): Result[TextEmbeddingModelSettings]
def textEmbeddingModel()(using ProviderRegistry): Result[TextEmbeddingModelSettings]
```

Binary-incompatible, source-compatible, as in PRs 3 and 4. Without it an application's own
registry could not reach the loader, and a provider it registered explicitly would resolve for
`EmbeddingClient.from` but not for its configuration.

All three resolve through the same registry, so a provider configurable by one is configurable
by all of them: `textEmbeddingModel` now goes through `embeddings` rather than calling the
loader a second time.

### Error messages

Unknown providers now produce the registry's message, which names what *is* registered and the
scan that found it. Missing-field errors name the config path and the environment variable the
descriptor declared. The provider is named by its canonical id (`openai`, not `OpenAI`) - the
spelling that appears in config.

## Slice 4 (PR 4): embedding providers join the SPI

The fourth slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)), and the last
unchecked item on that issue's list. PRs 2 and 3 made a *chat* provider self-describing and
discoverable. `EmbeddingClient.from` was still the shape the slice exists to delete:

```scala
provider.toLowerCase match
  case "openai" => Right(new EmbeddingClient(OpenAIEmbeddingProvider.fromConfig(cfg)))
  case "voyage" => ...
  case "ollama" => ...
  case other    => Left(EmbeddingError(...))
```

so an embedding provider was an edit to `llm4s-core` no matter where its code lived. It is now
resolved through the same `ProviderRegistry`.

### Declaring an embedding provider

`EmbeddingProviderDescriptor` is the embedding counterpart of `ProviderDescriptor`, and
`Llm4sProviderModule` gained a second list:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Right(JinaEmbeddingProvider.fromConfig(config))

final class JinaProviderModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(JinaEmbeddings)
```

Registration is otherwise identical to PR 3 - the same `META-INF/services` file, the same
`ServiceLoader` scan, the same escape hatches. A module declares whichever halves it has; both
default to empty.

> **If your module delegates**, forward *both* lists. Every `Llm4sProviderModule` member
> defaults to `Nil`, so a module that forwards only `chatProviders` contributes no embedding
> providers and fails silently rather than at compile time.

### Why a separate trait, not a method on `ProviderDescriptor`

The two provider sets overlap without either containing the other: OpenAI and Ollama supply a
chat client *and* an embedding provider, Voyage supplies only embeddings, Anthropic only chat.
Folding embeddings into `ProviderDescriptor` would force an embedding-only provider to implement
`buildConfig` and `buildClient` only to fail them.

The ids therefore live in **two namespaces**, and the same id can appear in both — `ollama` names
a chat client and an embedding provider that share nothing but a base URL. `ids` and
`embeddingIds` list them separately, and a provider that supplies no embeddings fails as such:

> Embedding provider 'anthropic' (from llm4s.embeddings.model) is not registered. Registered
> embedding providers: ollama, openai, voyage. If you expected 'anthropic', add the dependency
> that supplies it, or register it explicitly with ProviderRegistry.ofEmbeddings(...).

Each half names the registration call that accepts its own descriptor type - `of` for chat,
`ofEmbeddings` for embeddings - because following the other one is a compile error.

### `EmbeddingClient.from` takes the registry

```scala
def from(provider: String, cfg: EmbeddingProviderConfig)(using
  ModelRegistryService,
  ProviderRegistry
): Result[EmbeddingClient]
```

Binary-incompatible, source-compatible: existing call sites resolve `ProviderRegistry.default`
through the companion's given, exactly as the `Llm4sConfig` methods did in PR 3. An application
that registers its own passes it:

```scala
given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(JinaEmbeddings)
EmbeddingClient.from("jina", cfg)
```

`ProviderRegistry` gained `findEmbedding`, `resolveEmbedding`, `embeddingIds`,
`canonicalEmbeddingId`, `withEmbeddingProvider` and `ofEmbeddings`; `ProviderModuleReport` gained
`embeddingProviderIds`. The unknown-provider failure is still an `EmbeddingError` with code
`400`, now carrying the registry's diagnostics as its message.

### What did *not* change

`EmbeddingProvider` itself, `EmbeddingProviderConfig`, and each provider's `fromConfig` are
untouched - `OpenAIEmbeddingProvider.fromConfig(cfg)` still works and is still the direct route.
The three built-in objects simply *are* their own descriptors now.

Embedding **configuration** is not part of this change: `llm4s.embeddings` still has typed
`openai` / `voyage` / `ollama` sections in `EmbeddingsConfigLoader`, so a third-party embedding
provider is reachable through `EmbeddingClient.from` but still needs its config built by the
application. Moving config binding into the descriptor, as PR 2 did for chat, is the follow-up.

## Slice 4 (PR 3): providers are discovered on the classpath

The third slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 2 made a
provider a `ProviderDescriptor` that registers itself; this removes the last manual step. A
provider module on the classpath is now found without any registration code at the call site -
adding a provider is adding a dependency.

### Declaring a provider module

Ship a `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` naming an implementation:

```
com.example.llm4s.BedrockProviderModule
```

```scala
// Must be a `class` with a public no-arg constructor, not an `object`:
// ServiceLoader instantiates the named class, and a Scala `object` exposes its
// instance as a MODULE$ field instead. (This is also what GraalVM native-image
// needs, via its ServiceLoaderFeature.)
final class BedrockProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(BedrockProvider)
```

That is the whole registration. `ProviderRegistry.default` - what every `Llm4sConfig` and
`LLMConnect` call uses when the caller supplies no registry - is now
`ProviderRegistry.discover()`, computed once on first use.

`llm4s-core` declared its own providers the same way, through
`org.llm4s.llmconnect.provider.BuiltinProviderModule`, with no special case: they were
discovered exactly as a third-party module is. (Since slice 5 core ships no provider, and that
module is gone.)

### One broken jar cannot take out the others

`java.util.ServiceLoader`'s iterator throws `ServiceConfigurationError` for an entry it cannot
load, and the `for`-comprehension you would naturally write over it propagates the first such
error and abandons every remaining provider. `discover` drives the iterator by hand and guards
each step, so an unusable entry becomes a recorded failure and the scan continues:

```scala
val registry = ProviderRegistry.discover()
registry.report.failures.foreach(f => println(f.detail))
println(registry.report.describe)
// Discovery scanned 2 modules; 1 failed: loading a provider module failed: ...
//   - org.llm4s.llmconnect.provider.BuiltinProviderModule [file:/.../llm4s-core.jar]: openai, openrouter, ...
//   ! loading a provider module failed: ... Provider com.example.Missing not found
```

Failures are logged at WARN as they happen, and the scan summary is appended to the
"provider is not registered" error, because the two failure modes that are otherwise invisible
are a dependency that was never added and a fat jar whose services files were dropped:

> Provider 'bedrock' (from llm4s.providers.my-bedrock.provider) is not registered. Registered
> providers: anthropic, azure, ... If you expected 'bedrock', add the dependency that supplies
> it, or register it explicitly with ProviderRegistry.of(...). Discovery scanned 1 module; 0 failed.

### Fat jars

Shading tools default to *overwriting* same-named resources, which silently discards every
services file but one. Configure them to concatenate:

```scala
// sbt-assembly
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
  case other                                => (assembly / assemblyMergeStrategy).value(other)
}
```

```xml
<!-- maven-shade -->
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

If you cannot, register explicitly - this is what the escape hatch is for:

```scala
val registry = ProviderRegistry.ofModules(new Llm4sOpenAIModule).withProvider(BedrockProvider)
LLMConnect.getClient(config)(using registry)
```

`ProviderRegistry.ofModules` builds a registry from the modules you name, with no classpath scan
at all. (This section first suggested `ProviderRegistry.builtin`, the providers compiled into
`llm4s-core`; it was removed when the last of them left core.)

### `Llm4sConfig` takes the registry

Every `Llm4sConfig` method that reads `llm4s.providers` now takes an implicit
`ProviderRegistry`: `provider`, `providerConfigs` (both), `providers`, `defaultProviderName`,
`defaultProvider`, `listModels` (both), and `providerFrom`. Existing call sites are unchanged -
the companion supplies `ProviderRegistry.default` - and a caller who wants a different set of
providers passes one:

```scala
given ProviderRegistry = ProviderRegistry.default.withProvider(MyProvider)
val config = Llm4sConfig.provider("my-provider")   // now resolvable
```

This is a binary-incompatible change to those signatures, and source-compatible.

### What did *not* change

`ProviderDescriptor`, `ProviderConfigSpec`, `ProviderFeatures` and `Llm4sProviderModule` are as
PR 2 shipped them. `ProviderRegistry.of`, `ofModules`, `withProvider` and `withModule` behave as
before; registries built that way report `discovered = false` and carry no scan summary.

## Slice 4 (PR 2): the provider registration SPI

The second slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 1 removed the
two structures that made an out-of-module provider impossible - a closed `enum` and a `sealed`
trait. This one builds the extension point on top: a provider is now a value that describes
itself, and everything that used to enumerate providers looks them up instead.

Every provider still ships inside `llm4s-core`; what changed is that none of them is *wired in*
by hand any more. Splitting them into their own artifacts is slice 5
([#1132](https://github.com/llm4s/llm4s/issues/1132)).

### Adding a provider

Before, adding one chat provider meant editing roughly eight shared files - the closed `enum`,
the `sealed` config file, two `match` expressions in `LLMConnect`, the loader's dispatch, a
validator object, a capabilities object and the capabilities registry. That surface is why 13
open provider PRs all conflict with each other.

Now it is one file:

```scala
object BedrockProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("bedrock")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec(requiresApiKey = true, requiresEndpoint = true)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey   <- ProviderDescriptor.requireApiKey(providerName, section)
      endpoint <- ProviderDescriptor.requireField(
                    providerName, "endpoint", section.endpoint, "llm4s.providers.<name>.endpoint")
    yield BedrockConfig.fromValues(section.model.asString, apiKey, endpoint)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[BedrockConfig](id, config)
      .flatMap(BedrockClient(_, options.metrics, options.exchangeLogging))
```

plus a `ProviderConfig` implementation, a client, and one registration:

```scala
val registry = ProviderRegistry.default.withProvider(BedrockProvider)
LLMConnect.getClient(config)(using registry)
```

Classpath discovery - registering by *adding a dependency*, with no code at all - is PR 3.
`Llm4sProviderModule` is the service type it will discover; it is already here so that a module
can group its providers today.

### What is new

All in `org.llm4s.llmconnect.spi`:

| Type | Purpose |
|---|---|
| `ProviderDescriptor` | one provider: id, aliases, config shape, features, model lister, and the two builders |
| `ProviderConfigSpec` | which section fields the provider requires, its default base URL, and the text shown when a field is missing |
| `ProviderFeatures` | what the client actually implements, declared statically (Cohere and Mistral declared `streaming = false` until slice 5 - [#925](https://github.com/llm4s/llm4s/issues/925)) |
| `ProviderRegistry` | an immutable set of descriptors; `of`, `withProvider`, `withModule`, and lookup that returns `Result` |
| `Llm4sProviderModule` | the unit of registration - one module, several providers |

`ProviderRegistry` is resolved through a `using` clause with a default given in its companion, so
existing call sites are unchanged and a caller who wants a different set passes one:

```scala
LLMConnect.getClient(config)                    // ProviderRegistry.default
LLMConnect.getClient(config)(using myRegistry)  // only the providers you registered
```

### What was deleted

All of these were `private[llm4s]`, so this costs users nothing:

| Deleted | Replaced by |
|---|---|
| `config.ProviderCapabilities` (trait + 12 objects) | `ProviderDescriptor` |
| `config.ProviderCapabilitiesRegistry` | `ProviderRegistry` |
| `config.NamedProviderValidator` (trait) and `NamedProviderValidators` (12 objects) | `ProviderConfigSpec` + one generic `NamedProviderSectionValidator` |
| the twelve-branch `match` in `NamedProviderLoader` | `descriptor.buildConfig` |
| the two `match` expressions in `LLMConnect` | `descriptor.buildClient` |
| the hard-coded `"google"`/`"vertex"` alias fold in `NamedProviderConfigNormalizer` | `ProviderDescriptor.aliases` |

Error messages for missing fields are unchanged; they are now generated from the spec rather than
written out per provider.

### Source breaks

1. **`ReliableProviders`: seven per-provider factories collapse to `wrap`.**

   ```scala
   // Before - covered 7 of 12 providers, and no provider from another module
   ReliableProviders.openai(config, ReliabilityConfig.aggressive)
   ReliableProviders.anthropic(config)

   // After - covers every registered provider
   ReliableProviders.wrap(config, ReliabilityConfig.aggressive)
   ReliableProviders.wrap(config)
   ```

   The missing five were DeepSeek, Cohere, Mistral, Requesty and Vertex AI. `wrap(client,
   providerName, ...)`, for a client you already have, is unchanged.

2. **`OpenAIConfig.providerId` is derived from `baseUrl`.** It answers `openrouter` for a URL
   containing `openrouter.ai` and `openai` otherwise - which is exactly the routing `LLMConnect`
   already did with a hard-coded check, now stated by the config itself. If you build an
   `OpenAIConfig` for OpenRouter and inspect `providerId`, the answer changed from `openai` to
   `openrouter`; client routing is unchanged.

3. **`ProviderModelLister` is now public**, along with `ProviderModelListers` and its new
   `openAICompatible(provider, defaultBaseUrl, modelsPath)` factory - a provider module needs to
   supply a model lister, and most providers serve the OpenAI `/models` shape. The five
   near-identical per-provider lister objects became calls to that factory.

4. **`ProviderResultOps` and `ProviderExchangeRecorder` are now public** (they were
   `private[provider]`). A provider client outside `llm4s-core` needs both.

### What did *not* change

`Llm4sConfig`'s signatures, `NamedProviderLoader`'s results, `DiscoveredModel`, every
`ProviderConfig` subtype's fields, and the `llm4s.providers.*` config format. A configuration
that worked before works now, including `provider = "google"` and `provider = "vertex"`.

`ProviderConfig.fromValues` still uses `require(...)`, which throws rather than returning a
`Left`. Converting it is a behaviour change (throw → `Left`) that deserves its own note, and
embeddings (`EmbeddingClient.from`, `EmbeddingsConfigLoader`'s fixed-arity reader,
`ModelDimensionRegistry`, and the duplicate `org.llm4s.rag.EmbeddingProvider` name ADT) are still
on the old dispatch. Both are tracked under #1131.

> **Since resolved.** The embedding entry points moved onto the registry in PR 4, PR 5 and the
> dimensions follow-up above; the `org.llm4s.rag.EmbeddingProvider` ADT was removed, and
> `fromValues` converted to `Result`, in the
> [slice 4 close-out](#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).

## Slice 4 (PR 1): `ProviderKind` becomes `ProviderId`, `ProviderConfig` opens up

The first of the slice 4 changes ([#1131](https://github.com/llm4s/llm4s/issues/1131)). No SPI
yet - this only removes the two things that make a provider impossible to supply from outside
`llm4s-core`: a closed `enum` and a `sealed` trait. It is in the build but not yet in a release,
so nothing here affects `0.4.1` or earlier.

**This is a clean break: there is no deprecated `ProviderKind` shim.** A shim would have kept a
closed list of twelve providers inside the very module whose purpose is to remove it, and it
would only have half-worked - `case ProviderKind.OpenAI =>` and `def f(k: ProviderKind)` would
still compile, while `.values`, `.ordinal`, `.fromOrdinal` and exhaustivity would not. A clear
compile error beats a partly-working deprecated type.

### `ProviderKind` → `ProviderId`

```scala
// Before
enum ProviderKind:
  case OpenAI; case Anthropic; /* ... ten more */

// After
opaque type ProviderId = String
object ProviderId:
  def apply(raw: String): ProviderId = raw.trim.toLowerCase(Locale.ROOT)  // canonicalises
  extension (id: ProviderId) def asString: String = id
```

`ProviderId` is an **open vocabulary**, not an enumeration. Any string names a provider; whether
that provider can be resolved is answered at resolution time, by whatever is on the classpath -
which is the whole point. It stays `opaque` over `String`, so `Option[ProviderId]` and
`Map[ProviderName, ProviderId]` still do not box.

| Before | After |
|---|---|
| `ProviderKind.OpenAI` | `ProviderId("openai")` |
| `ProviderKind.fromString(s)` / `fromName(s)`, returning `Option` | `ProviderId(s)`, total |
| `kind.name` | `id.asString` |
| `kind.toString` → `"OpenAI"` | `id.asString` → `"openai"` |
| `ProviderKind.all` | no replacement - ask the thing that resolves providers, not the type |
| `ProviderKind.values` / `.ordinal` / `.fromOrdinal` / `.productPrefix` | no replacement |
| exhaustive `match` on `ProviderKind` | match on `id.asString`, with a default branch |

`asString` lives in `object ProviderId` rather than beside `ModelName.asString` and friends,
because every newtype in `ProviderModelTypes` erases to `String` and a second `asString` at that
level would be a double definition after erasure. Companion-scoped extensions resolve through the
opaque type's implicit scope, so `id.asString` still needs no extra import.

**`toString` changed value.** `ProviderKind.OpenAI.toString` was `"OpenAI"`; a `ProviderId` is
its canonical lowercase spelling, so it prints `"openai"`. If you interpolated a provider into
log or error text, expect the case to change. Uppercase derivations still work:
`providerId.asString.toUpperCase` is `"OPENAI"`, as `providerKind.toString.toUpperCase` was.

### `ProviderConfig` is no longer `sealed`, and describes itself

In Scala 3 `sealed` restricts extension to the **same file**, so all ten provider configs were
stuck in one 756-line file - not merely in the same jar. `ProviderConfig` is now a plain `trait`
with three new members:

```scala
trait ProviderConfig:
  def providerId: ProviderId              // replaces `val provider: ProviderKind`
  def endpointUrl: Option[String]         // the endpoint this config will contact
  def withModel(model: String): ProviderConfig
  // model, contextWindow, reserveCompletion unchanged
```

```scala
// Before
config.provider == ProviderKind.OpenAI
// After
config.providerId == ProviderId("openai")
```

The three additions exist so that code describing a config does not have to know the set of
subtypes. Four exhaustive matches were **deleted rather than moved** by using them:
`ConfigPolicyEngine.providerName`, `ConfigPolicyEngine.baseUrlOrEndpoint`,
`PrometheusMetricsExample`'s provider-name match, and `ProviderSetupRuntime.overrideModel`. If
you have a `match` on `ProviderConfig`, that is the migration: reach for `providerId`,
`endpointUrl` or `withModel` first, and only keep the match if you genuinely need
provider-specific fields.

Losing `sealed` also means an exhaustive `match` on `ProviderConfig` now compiles with a
warning - and **fails for anyone building with `-Werror`**, as this repo does. Add a default
branch, or annotate the scrutinee `(config: @unchecked)` if you have deliberately accepted the
risk.

One behaviour change falls out of this: `ConfigPolicyEngine.baseUrlOrEndpoint` used to return
`None` for `VertexAIConfig`, because the old match had no case for it. It now returns
`Some(computedBaseUrl)`. A `requiredBaseUrlPattern` policy that silently reported "no
endpoint/baseUrl found" for Vertex AI will now actually check the URL.

### Unknown provider ids are no longer rejected while parsing

`NamedProviderConfigNormalizer` used to fail on an unrecognised `provider` string with
`"Configured provider 'x' has unknown provider 'moonbeam'"`. It now produces a `ProviderId`
unconditionally; only *resolution* fails, with an error naming what is registered:

```
No provider capabilities registered for provider 'moonbeam'.
Registered providers: anthropic, azure, cohere, deepseek, gemini, mistral, ollama,
openai, openrouter, requesty, vertexai, zai
```

This is what lets a provider live in a module `llm4s-core` has never heard of. The accepted
aliases are unchanged - `provider = "google"` still resolves to `gemini`, and
`provider = "vertex"` to `vertexai` - though that table moves into each provider's descriptor
when the SPI lands.

### Validation error text is now provider-agnostic

```
// Before
Azure OpenAI provider 'my-azure' is missing required fields:
// After
Provider 'my-azure' (provider = azure) is missing required fields:
```

The per-field guidance underneath is unchanged, including the `${?AZURE_API_KEY}` substitution
hint. (Since superseded: the `apiKey` line now names the variable the provider module binds -
`AZURE_OPENAI_API_KEY` for Azure - see
[Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider).) Only the leading sentence differs, because it used to be generated from a hard-coded
display name per provider.

### Bug fix: `provider = "vertexai"` now works at all

`ProviderKind.VertexAI` existed, `NamedProviderLoader` built a `VertexAIConfig` from it, and
`LLMConnect` built a `VertexAIClient` from that - but Vertex AI was missing from
`ProviderCapabilitiesRegistry` and had no validator object. Since validation routes through that
registry, **every `provider = "vertexai"` config failed validation outright**, so none of the
supporting code was reachable from configuration. Both are now present, and the config path is
covered by tests.

### What did *not* change

`ProviderConfig.fromValues`'s `require(...)` calls still throw rather than returning `Result`;
converting them is a throw-to-`Left` behaviour change and is deferred to PR 2.
`ReliableProviders`' seven per-provider factories are also unchanged here - they collapse to a
single registry-routed `wrap` in PR 2. `NamedProviderLoader`, `NamedProviderValidator`,
`ProviderCapabilities`, `ProviderCapabilitiesRegistry` and `ProviderModelLister` are all
`private[llm4s]` or `private[config]`, so their reshaping costs users nothing.

## Slice 3: `llm4s-speech`

The last artifact of slice 3 ([#1130](https://github.com/llm4s/llm4s/issues/1130)) and the last
package out of `llm4s-core` in this slice. It is in the build but not yet in a release, so
nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.speech` (and `speech/io`, `processing`, `stt`, `tts`, `util`) | `llm4s-speech` |

**Package names did not change, and there are no source breaks.** 16 main and 21 test files
moved whole; the only code outside the package that referenced it was a sample.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use speech-to-text or text-to-speech
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-speech" % version
)
```

### What `llm4s-core` sheds

**Vosk (25 MB) and JNA.** `com.alphacephei:vosk` is imported by exactly one file,
`speech/stt/VoskSpeechToText.scala`, and until now sat on the classpath of every `llm4s-core`
user, whether or not they had any use for offline speech recognition. It is the single largest
dependency the carve programme has moved.

`Deps.jna` moves with it, but it is worth being precise about why, because it is not a second
dependency: Vosk's own POM already depends on `net.java.dev.jna:jna:5.7.0`. The explicit
declaration exists to win that version conflict and pull 5.19.1 instead. Dropping it would not
remove JNA - it would silently downgrade it to a release that predates Apple Silicon support.

`llm4s-workspace-client` also declared both, and used neither; those declarations are removed
here too, so Vosk genuinely leaves the build for everyone who is not doing speech.

### The Vosk resolver is deleted, not moved

`build.sbt` carried the project's only third-party resolver:

```scala
resolvers += "Vosk Repository" at "https://alphacephei.com/maven/"
```

It resolved nothing. Vosk 0.3.45 publishes to Maven Central, which is where every build has
actually been getting it - the local Coursier cache holds `vosk-0.3.45.jar` under
`repo1.maven.org` and not a single artifact under `alphacephei.com`. What it *did* do was add a
third-party host to the lookup path for every artifact in the build, including llm4s's own
inter-module jars, which produced a steady trickle of failed requests to alphacephei.com on
every resolve.

So it is removed rather than carried into `llm4s-speech`, and **the build now has no
third-party resolvers at all**.

### Slice 3 is complete

With this, `llm4s-core` no longer contains `rag`, `knowledgegraph`, `agent/memory`, `mcp`,
`imagegeneration`, `imageprocessing` or `speech`. What remains is the agent runtime,
`llmconnect`, `toolapi`, `config`, `trace` and the provider clients, which
[slices 4 to 6](https://github.com/llm4s/llm4s/issues/1126) address.

## Slice 3: `llm4s-image`

Part of slice 3 of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); the slice is
[#1130](https://github.com/llm4s/llm4s/issues/1130). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.imagegeneration` | `llm4s-image` |
| `org.llm4s.imageprocessing` | `llm4s-image` |

**Package names did not change, and this carve adds no source breaks of its own.** The image
API's one source break - image formats becoming `org.llm4s.media.MediaType` - landed earlier,
in [`llm4s-media`](#slice-3-llm4s-media), precisely so that this step is a pure file move.

The two packages move together because they are two halves of one subsystem: generate an
image, then analyse or convert it. Both are built on the same media vocabulary, and splitting
them would leave two artifacts nobody uses apart.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you generate images, or analyse them with a vision model
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % version,
  "org.llm4s" %% "llm4s-image" % version
)
```

`llm4s-image` brings `llm4s-media` with it, so `MediaType` is on your classpath either way.

### What `llm4s-core` sheds

No third-party dependency: the image clients are built on `Llm4sHttpClient`, `ujson`/`upickle`
and `javax.imageio` from the JDK, all of which core keeps for other reasons. What core sheds is
**19 source files and about 3,600 lines** of a subsystem most users never touch, along with its
edge to `llm4s-media` - that edge existed only because the image packages were still inside
core, and it leaves with them.

Core's measured statement coverage rises from 74.05% to 74.89% as a result, since the image
code was below core's average.

### One test moved with the code

`org.llm4s.async.AsyncErrorHandlingSpec` lived in core's test tree under a package name that
suggests it is about asynchrony in general. Every one of its assertions exercises an image
client - it checks that `ImageProcessingClient.analyzeImageAsync` and the image generation
clients' `Future { blocking { ... } }.recover { ... }` pattern surface thrown exceptions as
`Left` rather than as a failed `Future`. It moves to `llm4s-image` with the code it tests,
keeping its package name.

Had it been left behind it would simply have stopped compiling - but the more useful point is
that leaving it would have removed the only coverage of that behaviour from the module that
owns it.

## Slice 3: `llm4s-media`

A new module rather than a carve, landed as part of slice 3
([#1130](https://github.com/llm4s/llm4s/issues/1130)) and ahead of `llm4s-image` and
`llm4s-speech`, so those two carves can be pure file moves. It is in the build but not yet in
a release, so nothing here affects `0.4.1` or earlier.

### Why

A media type - a MIME string, a canonical file extension, and whether the thing is an image,
audio, video or text - is the one piece of vocabulary every multimodal subsystem needs to
name. Because there was nowhere shared to put it, each grew its own. `llm4s-core` shipped
three overlapping enumerations of the same handful of image formats:

| Type | Cases | Members |
|---|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | PNG, JPEG, WEBP | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.ImageFormat` | PNG, JPEG, WEBP, GIF | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.MediaType` | Jpeg, Png, Gif, WebP, Bmp, Tiff | `value` |

The first two are structurally identical and differ only in package, so a format produced by
image generation could not be handed to image processing without a hand-written conversion.
The third models the same six formats a third way, in the same package as the second. Meanwhile
`MediaExtractor` in `llm4s-rag` discriminated on raw MIME prefixes (`mimeType.startsWith
("image/")`), with no type to name the answer at all.

Carving `image` and `speech` out of core without fixing this would have frozen three copies
into three artifacts, where consolidating them later costs a cross-module source break rather
than an in-module one.

### What `llm4s-media` is

Vocabulary only - no I/O, no content sniffing, no third-party dependencies at all:

- `org.llm4s.media.MediaType` - `mimeType`, `extension`, `category`, plus `fromExtension`,
  `fromPath` and `fromMimeType` lookups. Sealed; refines into `ImageMediaType` and
  `AudioMediaType` so an image API can require an image without re-enumerating the cases.
- `org.llm4s.media.MediaCategory` - `Image`, `Audio`, `Video`, `Text`, `Application`, with
  `fromMimeType`.

Deciding what a file actually *is* from its bytes needs Tika and stays in `llm4s-rag`; that
code produces a MIME string and resolves it here. That separation is what lets every consumer
depend on `llm4s-media` without inheriting anything.

### Source breaks

**This is a source break, taken deliberately ahead of the 1.0 API freeze.** All three types
above are replaced by `org.llm4s.media.MediaType`.

| Before | After |
|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.MediaType` | `org.llm4s.media.MediaType` |
| `ImageFormat.PNG` | `MediaType.Png` |
| `ImageFormat.JPEG` | `MediaType.Jpeg` |
| `ImageFormat.WEBP` | `MediaType.WebP` |
| `ImageFormat.GIF` | `MediaType.Gif` |
| `MediaType.Jpeg.value` | `MediaType.Jpeg.mimeType` |

```scala
// Before
import org.llm4s.imagegeneration.ImageFormat
val opts = ImageGenerationOptions(format = ImageFormat.PNG)

// After
import org.llm4s.media.MediaType
val opts = ImageGenerationOptions(format = MediaType.Png)
```

Two lookups changed shape as well. `org.llm4s.imageprocessing.MediaType.fromExtension` and
`.fromPath` were total, silently returning JPEG for anything they did not recognise - so a
`.txt` file reported as an image and the caller could not tell. The replacements return
`Option`, and callers that genuinely want the old fallback ask for it:

```scala
// Before
val mt = MediaType.fromPath(path)                                   // JPEG if unrecognised

// After
val mt = MediaType.imageFromPath(path).getOrElse(MediaType.Jpeg)    // fallback is now visible
```

`AnthropicVisionClient.detectMediaType` keeps the old behaviour and its old signature shape -
it still answers JPEG for an unrecognised extension, because that is what the Anthropic API
assumes for an unlabelled image - but now returns an `ImageMediaType`.

### Adding the dependency

Nothing to add today: `llm4s-media` arrives as a transitive dependency of `llm4s-core` (via
the image packages, which are still in core) and of `llm4s-rag`. Declare it directly only if
you name `MediaType` or `MediaCategory` in your own signatures.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-media" % version
```

## Slice 3: `llm4s-mcp`

Third of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 3 is
[#1130](https://github.com/llm4s/llm4s/issues/1130), which carves three independent
subsystems - `mcp`, `image` and `speech` - one artifact at a time. This note covers `mcp`;
the other two follow. It is in the build but not yet in a release, so nothing here affects
`0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.mcp` | `llm4s-mcp` |

**Package names did not change, and there are no source breaks.** Nothing outside
`org.llm4s.mcp` referenced it, so the whole package moved with no facade left behind.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use the Model Context Protocol client, server or tool registry
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-mcp"  % version
)
```

### What `llm4s-core` sheds

**Java-WebSocket.** Worth being precise about why, because it is not what
[#1130](https://github.com/llm4s/llm4s/issues/1130) predicted: MCP does not use WebSockets at
all. Its transports are stdio, HTTP and SSE, built on `Llm4sHttpClient` and
`com.sun.net.httpserver`. `Deps.websocket` was declared on `llm4s-core` and imported by
nothing in it - the only WebSocket code in the repo is `ContainerisedWorkspace` in
`llm4s-workspace-client`, which declares the dependency itself. So core sheds it by dropping a
declaration that was never used, and the dependency does not follow `mcp` anywhere.

If you depend on `llm4s-core` and were picking up `org.java-websocket` transitively, declare
it yourself.

### Configuration keys

None. `org.llm4s.mcp` reads no `reference.conf` keys and no `Llm4sConfig` method names a type
that moved.

---

## Slice 2: `llm4s-memory` and `llm4s-memory-postgres`

Second of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 2 is
[#1129](https://github.com/llm4s/llm4s/issues/1129). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.agent.memory`, except `PostgresMemoryStore` | `llm4s-memory` |
| `org.llm4s.agent.memory.PostgresMemoryStore` | `llm4s-memory-postgres` |

**Package names did not change, and there are no source breaks in this slice.** Nothing
outside `org.llm4s.agent.memory` referenced it, so the whole package moved with no facade left
behind. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use agent memory
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-memory" % version
)

// ...and only if you store memories in Postgres/pgvector
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % version
```

### Why two artifacts

`PostgresMemoryStore` was the only file in the package that needed a connection pool and a
server-side driver. Shipping it alongside `InMemoryStore` would mean every user of agent
memory inherits HikariCP and the Postgres JDBC driver whether or not they ever open a
connection. `llm4s-memory` carries sqlite-jdbc — for the file-backed `SQLiteMemoryStore` and
`VectorMemoryStore` — and nothing else; `llm4s-memory-postgres` depends on `llm4s-memory` and
adds the two heavy dependencies.

### What `llm4s-core` sheds

**HikariCP and the Postgres JDBC driver** leave the core classpath. sqlite-jdbc leaves too:
all three used to be declared in the build's shared settings, which put them on *every*
module's classpath, so core could not shed them by itself. They are now declared only by the
modules that open a connection. If you depend on `llm4s-core` and use any of the three
directly, declare them yourself rather than relying on the transitive edge.

### `org.llm4s.vectorstore.PostgresVectorHelpers`

Unchanged for callers — same package, same object, same methods — but worth knowing where it
ships. It is the pgvector text codec (`[0.1,0.2,0.3]` ⇄ `Array[Float]`), it names no JDBC
type, and it now has consumers in two modules that do not and should not depend on each
other: `PgVectorStore` in `llm4s-rag` and `PostgresMemoryStore` in `llm4s-memory-postgres`.
Rather than have either reach for the other, the single copy lives in `llm4s-core`, which both
already depend on. Slice 1 had briefly moved it into `llm4s-rag` and left a private duplicate
in core for `PostgresMemoryStore`; that duplicate is now gone.

So `org.llm4s.vectorstore` is split across two jars: this one object in `llm4s-core`, the rest
in `llm4s-rag`. It resolves the same way on any ordinary classpath.

### Configuration keys

None. `agent/memory` reads no `reference.conf` keys and no `Llm4sConfig` method returns a type
that moved, so there is nothing to migrate.

---

## Slice 1: `llm4s-rag` and `llm4s-knowledgegraph`

First of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 1 is
[#1128](https://github.com/llm4s/llm4s/issues/1128). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.rag`, `org.llm4s.vectorstore`, `org.llm4s.chunking`, `org.llm4s.reranker`, `org.llm4s.eval`, `org.llm4s.extract`, `org.llm4s.knowledgegraph.graphrag` | `llm4s-rag` |
| `org.llm4s.knowledgegraph` (everything except `graphrag`) | `llm4s-knowledgegraph` |

**Package names did not change**, with the one deliberate exception described under [Source
breaks](#source-breaks) below. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use RAG, vector stores, chunking, reranking or the knowledge graph
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-rag"  % version   // depends on llm4s-knowledgegraph transitively
)
```

`llm4s-rag` depends on `llm4s-knowledgegraph`, so depending on the graph alone is only
worth doing if you want the graph without RAG.

`llm4s-knowledgegraph-neo4j` — a separate, already-published artifact — now depends on
`llm4s-knowledgegraph` instead of `llm4s-core`, which resolves for you.

### What `llm4s-core` sheds

Six dependencies leave the core classpath: **Tika, POI, PDFBox, jsoup, AWS S3 and AWS STS**.
If you depend on `llm4s-core` and use any of those directly, declare them yourself rather
than relying on the transitive edge.

### Source breaks

Three, all of them in this slice on purpose — pre-1.0 is when a duplicate is cheapest to
remove.

**1. The two document extractors are now one.** `org.llm4s.rag.extract.DocumentExtractor`
and `org.llm4s.llmconnect.extractors.UniversalExtractor` were independent implementations of
one job: two Tika instances, two sets of MIME constants, two PDFBox paths, two POI paths.
They are now `org.llm4s.extract`.

| Before | After |
|---|---|
| `org.llm4s.rag.extract.DocumentExtractor` | `org.llm4s.extract.DocumentExtractor` |
| `org.llm4s.rag.extract.DefaultDocumentExtractor` | `org.llm4s.extract.TikaDocumentExtractor` |
| `UniversalExtractor.extract(path)` → `Either[ExtractorError, String]` | `TikaDocumentExtractor.extractFromPath(path)` → `Result[ExtractedDocument]` (text in `.text`) |
| `UniversalExtractor.extractFromBytes(bytes, name, mime)` | `TikaDocumentExtractor.extract(bytes, name, mime)` |
| `UniversalExtractor.extractFromStream(in, name, mime)` | `TikaDocumentExtractor.extractFromStream(in, name, mime)` (returns `ExtractedDocument`) |
| `UniversalExtractor.isTextLike(mime)` | `TikaDocumentExtractor.canExtract(mime)` — also true for legacy `.doc` |
| `UniversalExtractor.detectMimeType(bytes, name)` | unchanged |
| `UniversalExtractor.extractAny(path)` and its `Extracted` / `TextContent` / `ImageContent` / `AudioContent` / `VideoContent` ADT | `org.llm4s.extract.MediaExtractor` |
| `org.llm4s.llmconnect.model.ExtractorError` | `org.llm4s.error.ProcessingError` |

The package is `org.llm4s.extract`, not `org.llm4s.rag.extract`: extraction has two real
consumers — RAG document loading and multimodal embedding — and it quarantines the three
heaviest dependencies in the build. Naming it outside the `rag` namespace makes any later
decision to give it its own artifact a build-file change rather than a code change.

**2. `EmbeddingClient.encodePath` is now `FileEmbedder.encodeFromPath`.** `EmbeddingClient`
keeps the pure vector API; file reading, MIME sniffing and chunking live in
`org.llm4s.rag.embed`. The six-parameter signature — which included an
`experimentalStubsEnabled: Boolean`, a deployment decision arriving at a call site — became
a config object.

```scala
// Before
client.encodePath(path, textModel, chunkingCfg, stubsEnabled, localModels)

// After
import org.llm4s.rag.embed.{ FileEmbedder, FileEmbeddingConfig, TextChunkingConfig }

FileEmbedder.encodeFromPath(
  path,
  client,
  FileEmbeddingConfig(
    textModel = textModel,
    localModels = localModels,
    chunking = TextChunkingConfig(enabled = true, size = 1000, overlap = 100),
    experimentalStubs = stubsEnabled
  )
)
```

`UniversalEncoder.TextChunkingConfig` is now the top-level `org.llm4s.rag.embed.TextChunkingConfig`.

**3. `Llm4sConfig.pgSearchIndex()` is now `PgSearchIndexConfigLoader.default()`.** It
returned a `SearchIndex.PgConfig`, which is RAG's type; `Llm4sConfig` stays in `llm4s-core`
and cannot name it. The loader itself keeps its package (`org.llm4s.config`) and its
`load(source)` method, and moves to `llm4s-rag`.

```scala
// Before
val pg = Llm4sConfig.pgSearchIndex()

// After
import org.llm4s.config.PgSearchIndexConfigLoader
val pg = PgSearchIndexConfigLoader.default()
```

### Configuration keys

`llm4s.rag.permissions.pg.*` and `llm4s.rerank.*` now ship in `llm4s-rag`'s `reference.conf`
rather than core's. HOCON merges `reference.conf` across jars, so the key paths are
unchanged and nothing in your `application.conf` needs editing — but a build that reads
those keys without depending on `llm4s-rag` no longer gets the defaults.

`llm4s.embeddings.*` — including `chunking` and `experimentalStubs` — stays in core, because
`Llm4sConfig` still reads it there.

---

## Artifact coordinate rename (v0.4.0)

### Breaking change

Every **published** artifact under the `org.llm4s` group was renamed to carry an `llm4s-`
prefix and a consistent kebab-case suffix. This is a **coordinate-only** change: there are
no API changes, no package moves and no source changes in this release. Update your
`build.sbt`, recompile, and you are done.

| Old coordinate | New coordinate |
|---|---|
| `"org.llm4s" %% "core"` | `"org.llm4s" %% "llm4s-core"` |
| `"org.llm4s" %% "workspaceShared"` (published as `workspaceshared`) | `"org.llm4s" %% "llm4s-workspace-shared"` |
| `"org.llm4s" %% "workspaceClient"` (published as `workspaceclient`) | `"org.llm4s" %% "llm4s-workspace-client"` |
| `"org.llm4s" %% "trace-opentelemetry"` | `"org.llm4s" %% "llm4s-observability-otel"` |
| `"org.llm4s" %% "knowledgegraph-neo4j"` | `"org.llm4s" %% "llm4s-knowledgegraph-neo4j"` |

Maven users: the `artifactId` gains the same prefix, so `core_3` becomes `llm4s-core_3`
(and `core_2.13` becomes `llm4s-core_2.13`).

### Why

Two reasons:

1. **Consistency.** `org.llm4s:core` is a poor coordinate to read in somebody else's build
   file, and the module names were an inconsistent mix of camelCase (silently lowercased by
   the publish into `workspaceclient`) and kebab-case.
2. **Escaping a bad publish.** The `core_3` / `core_2.13` artifacts carry an accidental
   mis-published version `2.1.593` (a typo). Maven Central publishes are immutable, so that
   version cannot be retracted, and some resolvers sort it as the "latest" release. A fresh
   artifact name is the only way out; documentation is not.

### Nobody is stranded

Releases up to and including **0.3.4** remain published, unchanged and resolvable under the
old coordinates. Pinning `"org.llm4s" %% "core" % "0.3.4"` keeps working indefinitely — you
only need to change coordinates when you move to 0.4.0 or later.

If you are pinning `core` with a floating or range version, pin an explicit `0.3.4` before
upgrading, so the phantom `2.1.593` is never selected.

### Migration steps

1. Replace the old coordinate with the new one in your `build.sbt` (see the table above).
2. Set the version to `0.4.0` or later.
3. Recompile. No imports, types or method signatures changed.

```scala
// Before
libraryDependencies += "org.llm4s" %% "core" % "0.3.4"

// After
libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.4.0"
```

### Note on `"org.llm4s" %% "llm4s"`

The aggregate `llm4s` artifact (`llm4s_3` / `llm4s_2.13`) is **not** published and has not
been since 0.2.9 — the root project sets `publish / skip := true`. Any build file or
documentation that depends on `"org.llm4s" %% "llm4s"` is wrong independently of this
rename and should be changed to `"org.llm4s" %% "llm4s-core"`.

### Note on `llm4s-observability-otel`

The OpenTelemetry integration is published as `llm4s-observability-otel` rather than
`llm4s-trace-opentelemetry`. The name anticipates the `llm4s-observability` module that the
modularisation work will carve out of `trace` + `metrics`, so the integration is named once
rather than twice.

### Unpublished modules

`samples`, `workspaceRunner`, `workspaceSamples`, `config-policy`, `it` and `benchmarks` set
`publish / skip := true` and never reached Maven Central. Their `name` values were made
consistent in the same change, but this has no effect on any downstream build.

---

## MessageRole Enum Changes (v0.2.0)

### Breaking Change
The `MessageRole` has been converted from string-based constants to a proper enum type for better type safety.

### Before (v0.1.x)
```scala
import org.llm4s.llmconnect.model.Message

val message = Message(role = "assistant", content = "Hello")
message.role match {
  case "assistant" => // handle assistant
  case "user" => // handle user
  case _ => // handle other
}
```

### After (v0.2.0)
```scala
import org.llm4s.llmconnect.model.{Message, MessageRole}

val message = AssistantMessage(content = "Hello")
// or
val message = Message(role = MessageRole.Assistant, content = "Hello")

message.role match {
  case MessageRole.Assistant => // handle assistant
  case MessageRole.User => // handle user
  case MessageRole.System => // handle system
  case MessageRole.Tool => // handle tool
}
```

### Migration Steps

1. **Update imports**: Add `MessageRole` to your imports
   ```scala
   import org.llm4s.llmconnect.model.MessageRole
   ```

2. **Replace string comparisons**: Update pattern matches and comparisons
   ```scala
   // Before
   if (message.role == "assistant") { ... }
   
   // After
   if (message.role == MessageRole.Assistant) { ... }
   ```

3. **Update message creation**: Use the typed constructors
   ```scala
   // Before
   Message(role = "user", content = "Hello")
   
   // After
   UserMessage(content = "Hello")
   // or
   Message(role = MessageRole.User, content = "Hello")
   ```

## Error Hierarchy Changes (v0.2.0)

### New Error Categorization
Errors are now categorized using traits for better type safety and recovery strategies.

### Before (v0.1.x)
```scala
error match {
  case e: LLMError if e.isRecoverable => // retry logic
  case e: LLMError => // handle non-recoverable
}
```

### After (v0.2.0)
```scala
error match {
  case e: RecoverableError => // retry logic
  case e: NonRecoverableError => // handle non-recoverable
}
```

### Error Recovery Pattern
```scala
import org.llm4s.error._

def handleError(error: LLMError): Unit = error match {
  case _: RateLimitError => // wait and retry
  case _: TimeoutError => // retry with backoff
  case _: ServiceError with RecoverableError => // retry
  case _: AuthenticationError => // refresh token or fail
  case _: ValidationError => // fix input and retry
  case _ => // non-recoverable, fail
}
```

### Migration Steps

1. **Replace `isRecoverable` checks**: Use pattern matching on traits
   ```scala
   // Before
   if (error.isRecoverable) { ... }
   
   // After
   error match {
     case _: RecoverableError => { ... }
     case _ => { ... }
   }
   ```

2. **Update error handling**: Use the new trait-based categorization
   ```scala
   // Before
   case e: ServiceError if e.isRecoverable =>
   
   // After
   case e: ServiceError with RecoverableError =>
   ```

3. **Use smart constructors**: Create errors using the companion object methods
   ```scala
   // Before
   new RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   
   // After
   RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   ```

## Configuration Changes (v0.2.0+)

> `Llm4sConfig.provider()` with no argument, used below, has since become
> `Llm4sConfig.defaultProvider()`, and providers are configured as named sections - see
> [From `LLM_MODEL` to named provider sections](#from-llm_model-to-named-provider-sections).

### EnvLoader and legacy ConfigReader → Llm4sConfig

Older versions used `EnvLoader` and a custom `ConfigReader` abstraction. These have been superseded by `Llm4sConfig` (PureConfig‑based) and typed helpers.

### Before (v0.1.x)
```scala
import org.llm4s.config.EnvLoader

val apiKey = EnvLoader.get("OPENAI_API_KEY")
val model  = EnvLoader.getOrElse("LLM_MODEL", "gpt-4")
```

or:

```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

### After (post‑0.2.0)

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Typed Config: recommended patterns

- Tracing (typed):
  ```scala
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.trace.{ Tracing, EnhancedTracing, TracingMode }

  val tracerResult: org.llm4s.types.Result[Tracing] =
    Llm4sConfig.tracing().map(Tracing.create)
  ```

- Provider model for display (typed):
  ```scala
  val modelNameResult = Llm4sConfig.provider().map(_.model)
  // Prefer completion.model after the API call when available
  ```

- Workspace (samples):
  ```scala
  import org.llm4s.codegen.WorkspaceConfigSupport

  val ws = WorkspaceConfigSupport.load().getOrElse(
    throw new IllegalArgumentException("Failed to load workspace settings")
  )
  ```

- Embeddings (samples):
  ```scala
  val ui      = org.llm4s.samples.embeddingsupport.EmbeddingUiSettings.loadFromEnv()
    .getOrElse(throw new IllegalArgumentException("Failed to load UI settings"))
  val targets = org.llm4s.samples.embeddingsupport.EmbeddingTargets.loadFromEnv()
    .fold(err => throw new IllegalArgumentException(err.toString), _.targets)
  val query   = org.llm4s.samples.embeddingsupport.EmbeddingQuery.loadFromEnv()
    .fold(_ => None, _.value)
  ```

## Configuration: legacy reader → `Llm4sConfig` / typed helpers (post‑0.2.0)

Earlier versions used a custom `ConfigReader`-style abstraction as a catch‑all for configuration. With PureConfig in place and typed helpers available, the preferred path is now:

- Use `org.llm4s.config.Llm4sConfig` in core code.
- Use explicit typed loaders plus `LLMConnect.getClient` in application/sample code.

### Provider configuration and client creation

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Typed path using Llm4sConfig
val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Tracing configuration

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.trace.Tracing

val tracer: Tracing =
  ConfigReader.TracingConf().map(Tracing.create).getOrElse(Tracing.noop)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.Tracing

val tracer: org.llm4s.types.Result[Tracing] =
  Llm4sConfig.tracing().map(Tracing.create)
```

### Embeddings: provider and client

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  ConfigReader.Embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  Llm4sConfig.embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

### Workspace settings

**Before**
```scala
import org.llm4s.codegen.WorkspaceSettings

val ws = WorkspaceSettings.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

**After**
```scala
import org.llm4s.codegen.WorkspaceConfigSupport

val ws = WorkspaceConfigSupport.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

### API keys and types

**Before (legacy reader-based API)**
```scala
// Legacy pattern: API key resolved from a generic config reader
def loadApiKey(reader: /* legacy ConfigReader */ Any): Result[ApiKey] =
  ApiKey.unsafe("sk-legacy-key") // placeholder for old behavior
```

**After**
```scala
import org.llm4s.config.Llm4sConfig

val cfgResult = Llm4sConfig.provider() // Result[ProviderConfig]
```

- For **new code**, do not introduce new parameters of reader/ConfigReader types. Prefer:
  - `Llm4sConfig` in core libraries.
  - Typed helpers plus `LLMConnect.getClient` (and `Llm4sConfig.tracing().map(Tracing.create)` / `.map(EnhancedTracing.create)` for tracing) in applications and samples.
- For **existing code** that currently depends on a `ConfigReader`-style abstraction:
  - Start by swapping call sites to use typed helpers (e.g., `Llm4sConfig.provider()`).
  - Where you need fine-grained control, switch to `Llm4sConfig` functions instead of calling the legacy reader directly.
