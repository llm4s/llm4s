---
layout: page
title: Migrating from 0.x to 1.0
description: "The upgrade path to LLM4S 1.0 from any 0.x release: which migration notes apply to your starting version, release by release."
search_title: "Migrate LLM4S from 0.x to 1.0"
parent: Migrations
nav_order: 10
cites_issues: all
---

# Migrating from 0.x to 1.0
{: .no_toc }

The upgrade path to 1.0 from any 0.x release. Find the release you are on, then work through its section and every
section above it. The notes themselves are in the [Migration Guide](../reference/migration), the canonical record of
every breaking change; this page says which of them apply to you.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Upgrading to 1.0.0

**1.0.0** is released. Every 0.x release could break source or binary compatibility, each break listed under the
CHANGELOG's *Breaking changes* with a note in the [Migration Guide](../reference/migration); **0.7.0** set the
binary-compatibility baseline (MiMa) for the seven frozen modules, `llm4s-agent` included, and 1.0.0 followed it with
no release in between. From 1.0.0 the frozen modules are source and binary compatible across 1.x: what that covers is
in the [Compatibility and Deprecation Policy](../reference/compatibility-policy).

**Scalafix.** The mechanical source breaks since 0.4.1 have Scalafix rules
([Scalafix: migration rules](../reference/scalafix#migration-rules)), published from 1.0.0 as
`org.llm4s::llm4s-scalafix`. Run the one umbrella rule for the version you start from, while your code still compiles
against that version, with [sbt-scalafix](../reference/scalafix#running-a-migration-rule) and
`semanticdbEnabled := true` in the build:

```bash
sbt "scalafixAll dependency:Llm4sMigrateFrom_0_7@org.llm4s::llm4s-scalafix:1.0.0"
```

That is the rule for 0.7.x; from 0.6.x it is `Llm4sMigrateFrom_0_6`, and from 0.5.x `Llm4sMigrateFrom_0_5`. Then set
the llm4s version to 1.0.0, compile, and work through the notes of your release's section and every section above it:
a rule reports a lint where it cannot rewrite safely, and the
[changes no rule automates](../reference/migration#changes-since-051-that-no-rule-automates) are made by hand.

---

## From 0.7.0 to 1.0.0

0.7.0 is the baseline, so little changed after it: a frozen API found wrong was fixed before 1.0.0 made the promise.
Nothing in the frozen modules changed binary-incompatibly. The Scalafix rule `Llm4sMigrateFrom_0_7` rewrites the
mechanical part ([Scalafix: migration rules](../reference/scalafix#migration-rules)).

- [An error's `provider` is a `ProviderId`](../reference/migration#an-errors-provider-is-a-providerid-2936):
  `RateLimitError(ProviderId("openai"))`, and `error.provider.asString` where a `String` is wanted; a provider
  module passes its `ProviderId` to `HttpErrorMapper`, `PartEncoding`, `ensureSuccess` and `mapServiceError` too. The
  one source break; the rule `ErrorProviderIdTyped` rewrites it.
- [A cut-off answer ends `Truncated`, Anthropic's `max_tokens` default, and reported penalties](../reference/migration#a-cut-off-answer-ends-truncated-anthropics-max_tokens-default-and-reported-penalties-2933):
  behaviour, not signatures. An agent with `withOutput` whose answer is cut off ends `AgentStatus.Truncated` where
  0.7.0 returned a `Left`, and `llm4s-anthropic` no longer sends `max_tokens = 2048` when `maxTokens` is unset;
  the OpenRouter client no longer fits a Claude model's thinking budget under 2048.

---

## From 0.6.0 to 0.7.0

0.7.0 is the last release that breaks freely: the API review finished here, so that the baseline it sets holds the
designs 1.0 keeps. The Scalafix rule `Llm4sMigrateFrom_0_6` rewrites the mechanical part
([Scalafix: migration rules](../reference/scalafix#migration-rules)). The breaking changes of 0.7.0, each under
*Breaking changes* in its CHANGELOG section:

- Generation and model calls:
  [`budgetTokens` and `ReasoningEffort.None` are replaced by `Thinking`](../reference/migration#budgettokens-and-reasoningeffortnone-are-replaced-by-thinking-2115),
  [finish reasons are typed](../reference/migration#finish-reasons-are-typed-2613),
  [every provider's content-filter stop is an error](../reference/migration#every-providers-content-filter-stop-is-an-error-2677),
  [a stream that did not finish is an error](../reference/migration#a-stream-that-did-not-finish-is-an-error-2613),
  [a broken or cut tool call is an error](../reference/migration#a-broken-or-cut-tool-call-is-an-error-2613),
  [`TokenUsage` and `ModelPricing` gain a prompt-cache field](../reference/migration#tokenusage-and-modelpricing-gain-a-prompt-cache-field-2115),
  [the forwarding `apply` overloads are removed](../reference/migration#the-forwarding-apply-overloads-are-removed-1281).
- Names and types, the last renames before the 0.7.0 baseline:
  [`LLMClient` accessors are named for their values, and `ToolBuilder.build`](../reference/migration#llmclient-accessors-are-named-for-their-values-and-toolbuilderbuild-2908),
  [`ThinkingBlock.Opaque` and `BaseLifecycleLLMClient` take a `ProviderId`](../reference/migration#thinkingblockopaque-and-baselifecyclellmclient-take-a-providerid-2908),
  [errors, `Result` helpers and `AgentResult.messages` are `Seq`s](../reference/migration#errors-result-helpers-and-agentresultmessages-are-seqs-2908),
  [`CachedEmbeddingClient`'s key function takes the request](../reference/migration#cachedembeddingclients-key-function-takes-the-request-2908).
- Agents:
  [a cut-off answer ends `Truncated`, not `Completed`](../reference/migration#a-cut-off-answer-ends-truncated-not-completed-2613),
  [`CompressionRule`'s `apply` field is `transform`](../reference/migration#compressionrules-apply-field-is-transform-1281),
  [context pruning enforces both limits and `minRecentTurns`](../reference/migration#context-pruning-enforces-both-limits-and-minrecentturns-2728-2729),
  [the PostgreSQL checkpoint schema moves to version 6](../reference/migration#the-postgresql-checkpoint-schema-moves-to-version-6-2453),
  [a workspace container has no egress](../reference/migration#a-workspace-container-has-no-egress-870).
- RAG:
  [a RAG with a `SearchIndex` ingests only with permissions](../reference/migration#a-rag-with-a-searchindex-ingests-only-with-permissions-2716),
  [`DocumentRegistry` is source-scoped](../reference/migration#documentregistry-is-source-scoped-2714),
  [RAG tracing covers every model call and each query's stages](../reference/migration#rag-tracing-covers-every-model-call-and-each-querys-stages-2734),
  [`RAG.ingestChunks` takes `DocumentChunk`s](../reference/migration#ragingestchunks-takes-documentchunks-2732),
  [`SQLiteVectorStore` escapes metadata and filters exactly](../reference/migration#sqlitevectorstore-escapes-metadata-and-filters-exactly-2759),
  [RAG loaders take a `UrlPolicy`, and `DirectoryLoader` stays in its root](../reference/migration#rag-loaders-take-a-urlpolicy-and-directoryloader-stays-in-its-root-2724).
- Configuration:
  [the legacy embedding provider selector is removed](../reference/migration#the-legacy-embedding-provider-selector-is-removed),
  [Vertex AI no longer reads `endpoint` and `organization`](../reference/migration#vertex-ai-no-longer-reads-endpoint-and-organization).
- Errors and retries:
  [one retry rule: `LLMError.isRetryable` replaces the recoverable markers](../reference/migration#one-retry-rule-llmerrorisretryable-replaces-the-recoverable-markers-2916),
  [every `LLMError` reports `httpStatus`, `retryAfter` and `requestId`](../reference/migration#every-llmerror-reports-httpstatus-retryafter-and-requestid-2916),
  [a context-length overflow and an unknown model are typed errors](../reference/migration#a-context-length-overflow-and-an-unknown-model-are-typed-errors-2916),
  [`StreamedChunk` names its deltas](../reference/migration#streamedchunk-names-its-deltas-2916),
  [`LlmException.isRetryable`, and three new `LlmErrorKind`s](../reference/migration#llmexceptionisretryable-and-three-new-llmerrorkinds-2916),
  [one retry mechanism: `LLMClientRetry` and `ErrorRecovery` are removed](../reference/migration#one-retry-mechanism-llmclientretry-and-errorrecovery-are-removed-2798),
  [`Result` helpers and conversions move, and `ErrorMapper` is removed](../reference/migration#result-helpers-and-conversions-move-and-errormapper-is-removed-2821),
  [error hierarchy cleanup](../reference/migration#error-hierarchy-cleanup-2819),
  [`NetworkSecurity` returns the addresses it vetted, and a refusal is not retryable](../reference/migration#networksecurity-returns-the-addresses-it-vetted-and-a-refusal-is-not-retryable-2826),
  [an open circuit is a `CircuitOpenError`](../reference/migration#an-open-circuit-is-a-circuitopenerror-2797),
  [`ReliableClient` has a private constructor](../reference/migration#reliableclient-has-a-private-constructor-2797).
- Messages, completions and streaming:
  [message parts, citations and adjustments are `Seq`s](../reference/migration#message-parts-citations-and-adjustments-are-seqs-2809),
  [`CompletionOptions`' sampling parameters are optional, and unset ones are not sent](../reference/migration#completionoptions-sampling-parameters-are-optional-and-unset-ones-are-not-sent-2807),
  [`Completion` reads `content` and `toolCalls` from its message](../reference/migration#completion-reads-content-and-toolcalls-from-its-message-2807),
  [a `Completion` without a finish reason is `FinishReason.Unknown`](../reference/migration#a-completion-without-a-finish-reason-is-finishreasonunknown-2807),
  [`Completion.estimatedCost` is a `BigDecimal`; `UsageSummary` counts cache tokens](../reference/migration#completionestimatedcost-is-a-bigdecimal-usagesummary-counts-cache-tokens-2807),
  [`AssistantMessage.text`, and a flat stored assistant message](../reference/migration#assistantmessagetext-and-a-flat-stored-assistant-message-2809),
  [one `Conversation.create`; `Conversation.empty` is a value](../reference/migration#one-conversationcreate-conversationempty-is-a-value-2809),
  [`ProviderExchange` and `ProviderExchangeRecorder`](../reference/migration#providerexchange-and-providerexchangerecorder-2809),
  [`MessageRole` is a Scala 3 `enum`](../reference/migration#messagerole-is-a-scala-3-enum-2809),
  [`LLMConnect.getClient` is the one way to get a client](../reference/migration#llmconnectgetclient-is-the-one-way-to-get-a-client-2810),
  [`LLMClient.streamComplete` takes its callback in a second parameter list](../reference/migration#llmclientstreamcomplete-takes-its-callback-in-a-second-parameter-list-2810),
  [`StreamingAccumulator` takes the model and reports usage one way](../reference/migration#streamingaccumulator-takes-the-model-and-reports-usage-one-way-2813).
- Newtypes and names:
  [model ids are `ModelName`, provider ids `ProviderId`, and provider configs' base URLs `BaseUrl`](../reference/migration#model-ids-are-modelname-provider-ids-providerid-and-provider-configs-base-urls-baseurl-2822),
  [provider ids are `ProviderId`](../reference/migration#provider-ids-are-providerid),
  [newtypes in `org.llm4s.types` are opaque, and `HeadroomPercent` is always valid](../reference/migration#newtypes-in-orgllm4stypes-are-opaque-and-headroompercent-is-always-valid-2822),
  [a tool call's id is a `ToolCallId`, and its idempotency key an `IdempotencyKey`](../reference/migration#a-tool-calls-id-is-a-toolcallid-and-its-idempotency-key-an-idempotencykey-2815).
- Configuration:
  [`Llm4sConfig.providerConfigs()` returns each section's `Result`; `providers()` and `ProvidersConfig` are internal](../reference/migration#llm4sconfigproviderconfigs-returns-each-sections-result-providers-and-providersconfig-are-internal-2804),
  [`llm4s.providers.provider` is now `llm4s.defaultProvider`](../reference/migration#llm4sprovidersprovider-is-now-llm4sdefaultprovider-2806),
  [`Llm4sConfig` takes `ProviderName`, and `embeddings()` returns the config alone](../reference/migration#llm4sconfig-takes-providername-and-embeddings-returns-the-config-alone-2804),
  [the config-policy `prod` preset allows model generations](../reference/migration#the-config-policy-prod-preset-allows-model-generations-2885).
- Providers and the provider SPI:
  [`Modality` is an enum, multimodal embedding lives in `llm4s-rag`, and `EmbeddingClient` is final](../reference/migration#modality-is-an-enum-multimodal-embedding-lives-in-llm4s-rag-and-embeddingclient-is-final-2808),
  [provider SPI: one shape for both descriptors, typed embedding sections, credential suppliers](../reference/migration#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811),
  [provider SPI: error mapping, published helpers, report types](../reference/migration#provider-spi-error-mapping-published-helpers-report-types-2811),
  [`Llm4sHttpClient` has one method to implement, `send`](../reference/migration#llm4shttpclient-has-one-method-to-implement-send-2802),
  [`ProviderConfig` has no default members, and `EmbeddingProviderConfig` is typed](../reference/migration#providerconfig-has-no-default-members-and-embeddingproviderconfig-is-typed-2805),
  [the caching types report failure, and tool loops bypass the cache](../reference/migration#the-caching-types-report-failure-and-tool-loops-bypass-the-cache-2803),
  [model registry sources, exact lookup and a sealed `RequestTransformer`](../reference/migration#model-registry-sources-exact-lookup-and-a-sealed-requesttransformer-2823),
  [`LLMClient.validate()` checks the provider](../reference/migration#llmclientvalidate-checks-the-provider-2830),
  [`ProviderRegistry` lookups accept aliases](../reference/migration#providerregistry-lookups-accept-aliases-2812),
  [provider clients are final, with one factory](../reference/migration#provider-clients-are-final-with-one-factory-2910),
  [`CachingLLMClient` and `CachedEmbeddingClient` have private constructors](../reference/migration#cachingllmclient-and-cachedembeddingclient-have-private-constructors-2910).
- Tools:
  [`ToolRegistry` is a trait built with `ToolRegistry.of`](../reference/migration#toolregistry-is-a-trait-built-with-toolregistryof-2816),
  [`SchemaDefinition` gains a union and a raw JSON Schema case](../reference/migration#schemadefinition-gains-a-union-and-a-raw-json-schema-case-2814),
  [a tool handler has one error type](../reference/migration#a-tool-handler-has-one-error-type-2815).
- Context management:
  [the context pipeline is Experimental, and token counters are built over a tokenizer](../reference/migration#the-context-pipeline-is-experimental-and-token-counters-are-built-over-a-tokenizer-2818),
  [context trimming fails when nothing fits, and externalizes only into a store](../reference/migration#context-trimming-fails-when-nothing-fits-and-externalizes-only-into-a-store-2817).
- Agents:
  [`AgentBuilder` tools are additive](../reference/migration#agentbuilder-tools-are-additive-2916),
  [`AgentResult.runUsage`, and tighter `PromptInjectionDetector` defaults](../reference/migration#agentresultrunusage-and-tighter-promptinjectiondetector-defaults-2922-2923),
  [`withToolSet` and `validateIn`, and the Java facade's guardrails sit innermost](../reference/migration#withtoolset-and-validatein-and-the-java-facades-guardrails-sit-innermost-2835),
  [a guardrail's reason is never stored](../reference/migration#a-guardrails-reason-is-never-stored-2853),
  [a turn's query is admitted before the turn is stored](../reference/migration#a-turns-query-is-admitted-before-the-turn-is-stored-2854),
  [an agent run's trace and metrics report one status](../reference/migration#an-agent-runs-trace-and-metrics-report-one-status-2829),
  [`AssistantError` and the assistant's newtypes live in `llm4s-agent`](../reference/migration#assistanterror-and-the-assistants-newtypes-live-in-llm4s-agent-2820),
  [a guardrail block's message names only the guardrail](../reference/migration#a-guardrail-blocks-message-names-only-the-guardrail-2793),
  [`RegexSafetyManager` returns `Result`](../reference/migration#regexsafetymanager-returns-result-2825),
  [`SecretPatterns` is in `llm4s-agent`](../reference/migration#secretpatterns-is-in-llm4s-agent-2824),
  [`llm4s-agent` types are tiered and growth-safe](../reference/migration#llm4s-agent-types-are-tiered-and-growth-safe-2778),
  [the middleware SPI is consistent and testable](../reference/migration#the-middleware-spi-is-consistent-and-testable-2795),
  [`Secret` or `Secret.token`: which one stays registered](../reference/migration#secret-or-secrettoken-which-one-stays-registered-2824).
- Tracing, metrics and images:
  [the Langfuse backend sends OpenTelemetry spans](../reference/migration#the-langfuse-backend-sends-opentelemetry-spans-2883),
  [`TraceEvent` carries an error as it is, and `AgentInitialized` is removed](../reference/migration#traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800),
  [`ReliableClient`'s metrics are generic metrics](../reference/migration#reliableclients-metrics-are-generic-metrics-2801),
  [the Hugging Face image client calls Inference Providers](../reference/migration#the-hugging-face-image-client-calls-inference-providers-2832),
  [the OpenAI image client defaults to `gpt-image-2.6-sunburst`](../reference/migration#the-openai-image-client-defaults-to-gpt-image-26-sunburst-2884),
  [image metrics are `ImageMetrics` in `llm4s-image`](../reference/migration#image-metrics-are-imagemetrics-in-llm4s-image-2801),
  [`Tracing` has one abstract method](../reference/migration#tracing-has-one-abstract-method-2799),
  [a tracing backend declares its aliases, and `Tracing.create` is removed](../reference/migration#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799),
  [Langfuse traces are private and name the run's user](../reference/migration#langfuse-traces-are-private-and-name-the-runs-user-2828).
---

## From 0.5.1 to 0.6.0

The opening of the [Migration Guide](../reference/migration) lists every 0.6.0 break with its note: chiefly the
growth-safe types (build with `apply`, change with `with*`, no public `copy`), `Secret` credentials, the `fromValues`
argument order, `Completion.created` as an `Instant`, content-free console tracing and the OpenTelemetry GenAI span
names, and `llm4s-java-api` and the Spring Boot starter no longer bringing the provider modules. The behaviour changes
that no rewrite covers are collected in
[Changes since 0.5.1 that no rule automates](../reference/migration#changes-since-051-that-no-rule-automates); the
subagent and production-adapter changes in [Subagents and isolation](../reference/migration#subagents-and-isolation-phase-4)
and [Production adapters](../reference/migration#production-adapters-stage-5).

---

## From 0.5.0 to 0.5.1

Two source breaks:
[multimodal content parts](../reference/migration#multimodal-content-parts-1913) (`UserMessage`/`ToolMessage` carry
`parts`) and [harness profile and middleware placement](../reference/migration#harness-profile-and-middleware-placement-1915).

---

## From 0.4.1 to 0.5.0

The modularisation release: `llm4s-core` splits into per-concern artifacts, the agent runtime is rewritten and API
keys are read from each vendor's variable. Follow [Upgrading from 0.4.1 to 0.5.0](0-4-1-to-0-5-0), one page grouped by
what you use. Two of its changes are detailed here.

### Agent event stream replaced (#1329)

The `AgentEvent` hierarchy and `runWithEvents` are replaced by `Agent.stream` and the `AgentEvents`
vocabulary; `TraceEvent.AgentStateUpdated` is replaced by `TraceEvent.AgentRunEnded`.

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

`await()` returns once the listener has returned from the run's last event, so whatever it collected
is complete. `AgentRunEnded.usage` is the run's own usage, not the thread's: a dashboard that summed
the old cumulative figure per run double counted. A slow `AgentIO.stream`/`AgentZ.stream` consumer
loses live events and gets a `StreamEvent.LiveGap` with their count; it no longer cancels the run.

See the [streaming guide](../guide/agents/streaming).

### Provider and embedding configs gain `timeouts` (#712)

The provider config case classes (`OpenAIConfig`, `AzureConfig`, `AnthropicConfig`, `GeminiConfig`,
`VertexAIConfig`, `OllamaConfig`, `DeepSeekConfig`, `ZaiConfig`, `MistralConfig`, `CohereConfig`,
`OpenAICompatibleConfig`, `BedrockConfig`, `WatsonXConfig`) and `EmbeddingProviderConfig` take
`timeouts: ProviderTimeouts`, defaulting to `ProviderTimeouts.default` (each client's own defaults). In 0.6.0 these
types became growth-safe: build them with `apply` and set the field with `withTimeouts(ProviderTimeouts(...))`; from
config, use the section's `timeouts { request = 3m, stream = 15m }` block (see
[Timeouts](../getting-started/configuration#timeouts)).

---

## From 0.4.0 or earlier

- **Artifact coordinates.** 0.4.0 renamed every artifact to `llm4s-*`:
  [Artifact coordinate rename (v0.4.0)](../reference/migration#artifact-coordinate-rename-v040).
- **`LLM_MODEL` is not read** (since 0.3.2): chat providers are named sections under `llm4s.providers`, and each
  provider module binds its vendor's API-key variable to `llm4s.credentials.<provider>.apiKey`. See
  [From `LLM_MODEL` to named provider sections](../reference/migration#from-llm_model-to-named-provider-sections).
- Then the 0.4.1 to 0.5.0 page above, and every section after it.

### `LLMProvider` replaced by `ProviderKind` (0.3.2)

The `LLMProvider` sealed trait was replaced by `ProviderKind` in 0.3.2, and `ProviderKind` itself by the opaque type
`ProviderId` in 0.5.0 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Coming from any release before 0.5.0, go
straight to [`ProviderKind` becomes `ProviderId`](../reference/migration#providerkind--providerid): a `match` on the
provider becomes a comparison with a `ProviderId`.

### Throwing APIs removed (0.3.0)

The methods that returned values directly and threw `IllegalStateException` on failure were removed in 0.3.0 in favour
of their `Result`-returning variants: [Migrating from v0.2.9 to v0.3.0](v0.2.9-to-v0.3.0). Names have moved on since
(the built-in tools are in `llm4s-agent-tools`, and the agent API was rewritten in 0.5.0), so apply that page and then
the 0.4.1 to 0.5.0 page.

### `DBx` module removed (0.1.14)

The `DBx` module (PostgreSQL and vector-store scaffolding) was removed. Its replacement is `llm4s-rag`, with
`PgVectorStore` and `PgSearchIndex`; see
[Slice 1: `llm4s-rag`](../reference/migration#slice-1-llm4s-rag-and-llm4s-knowledgegraph).

### Module layout change (0.1.13)

The sbt modules moved from the repository root into `modules/` (`core/` to `modules/core/`, and so on). Artifact
coordinates did not change in 0.1.13; they changed in 0.4.0, above.
