# Changelog

All notable changes to llm4s are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

<!-- The net change since 1.0.0, by area. Add an entry to its area's list; a follow-up to unreleased work edits
     its feature's entry instead. Rules: CLAUDE.md, "Reading files and output efficiently". -->

### Breaking changes

- `llm4s-schema-derivation` (Experimental): a `@description`, `@toolName` or `@upickle.implicits.key` whose argument is not a constant string is now a compile error where the schema, partial type or tool is derived; it was silently ignored ([#2951](https://github.com/llm4s/llm4s/issues/2951); [migration](docs/reference/migration.md#an-annotation-that-is-not-a-constant-string-is-a-compile-error-2951)).

### Providers

- **Added** an `openai` section with `api = "responses"` uses OpenAI's Responses API, where reasoning and tools work together, a turn's reasoning is kept across the thread's requests, and `reasoningSummary` asks for summary text; `streamComplete` there streams text, tool-call arguments and reasoning summaries ([#2900](https://github.com/llm4s/llm4s/issues/2900)).
- **Fixed** `llm4s-openai` calls tools on `gpt-6.1-terra`, `gpt-5.6-terra` and `gpt-5.6-luna`: a request with tools to a GPT 5.6, 6 or 6.1 model is sent `reasoning_effort: none` (reported as a `RequestAdjustment`), as `gpt-6-luna`'s already was. 1.0.0 refused `gpt-6.1-terra` with a `ValidationError` and sent the gpt-5.6 models an effort OpenAI rejects with tools [#2940](https://github.com/llm4s/llm4s/issues/2940).

### Observability

- **Added** `TRACING_MODE=file` in `llm4s-observability-otel`: each trace appended to `<dir>/<trace-id>.jsonl` as OTLP JSON Lines, unplaced events to one shared file per run, with no tracing service ([#2948](https://github.com/llm4s/llm4s/issues/2948); [Trace files](docs/getting-started/configuration.md#trace-files-no-tracing-service)).

### Agent runtime

- **Added** `AgentBuilder.withOutput` takes a schema whose root is an `anyOf` of objects, so an agent answers with a sealed trait or an enum with fields (`withOutput(SchemaOf[Turn].definition)`, `decodeOutput[Turn]`): the union is sent inside an envelope object and stored, checked and decoded without it; a root still refused says what to do instead ([#2953](https://github.com/llm4s/llm4s/issues/2953); [guide](docs/guide/agents/index.md#a-union-as-the-answer)).

### Tools

- **Fixed** `SchemaOf`, `PartialOf` and derived tools read a `@description`, `@toolName` or key given as any constant string: a literal, a `final val` or `inline val`, a `+` of constants, or an `s"..."` of constants. Only a single literal was read, and anything else was dropped without a warning ([#2951](https://github.com/llm4s/llm4s/issues/2951); [Derived schemas](docs/guide/derived-schemas.md#descriptions)).

### Build & tooling

- **Changed** the MiMa baseline is 1.0.0: `sbt mimaReportBinaryIssues` compares the seven frozen modules with their 1.0.0 artifacts, with no filter ([#1281](https://github.com/llm4s/llm4s/issues/1281); [API Stability](docs/reference/api-stability.md#the-baseline)).
- **Added** the umbrella Scalafix rule `Llm4sMigrateFrom_1_0`, for code on 1.0.x; it runs no rule yet, since nothing has broken since 1.0.0 ([#2330](https://github.com/llm4s/llm4s/issues/2330); [Scalafix](docs/reference/scalafix.md#one-umbrella-rule-per-starting-version)).

## [1.0.0] - 2026-11-25

### Highlights

1.0.0 is the stability release. From here llm4s follows semantic versioning, and the seven frozen modules
(`llm4s-core`, `llm4s-agent`, `llm4s-openai`, `llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini` and
`llm4s-ollama`) are source and binary compatible across 1.x: a `@Stable` API is removed only in a major release, and
only after a release that deprecated it
([Compatibility and Deprecation Policy](docs/reference/compatibility-policy.md); [1.0 Scope](docs/reference/v1-scope.md)).
Every other module is Beta or Experimental and may change in a minor release, with a migration note; in `llm4s-agent`
the `@Stable` core is building and running an agent, and the graph runtime, middleware and checkpointers are
`@Experimental`. The API is the one 0.7.0 set the baseline at, with the fixes below: an error's `provider` is a
`ProviderId`, an answer cut off at the output limit ends `AgentStatus.Truncated` for an agent with `withOutput` too,
Anthropic's default `max_tokens` follows the model, and a penalty a provider cannot take is reported instead of
dropped. The Scalafix migration rules are published from this release, as `org.llm4s::llm4s-scalafix`, so one command
migrates code from 0.5.x, 0.6.x or 0.7.x ([Migrating from 0.x to 1.0](docs/migrations/0x-to-1x.md)). 1.0.x is the
supported line; 0.7.x gets critical security fixes until 2027-02-25, and older 0.x lines are end of life
([Support Policy](docs/reference/support-policy.md)).

### Breaking changes

- An error's `provider` is a `ProviderId`, not a `String`, on the nine errors that name one (`RateLimitError(ProviderId("openai"))`, `error.provider.asString`), and on the helpers that build one (`HttpErrorMapper.toError` and `mapStreamError`, `PartEncoding.check` and `encodeAll`, `HttpResponse`'s `ensureSuccess` and `mapServiceError`); the Scalafix rule `ErrorProviderIdTyped` rewrites them
  ([migration](docs/reference/migration.md#an-errors-provider-is-a-providerid-2936)) [#2936](https://github.com/llm4s/llm4s/pull/2936), [#2939](https://github.com/llm4s/llm4s/pull/2939).
- Behaviour, no signature: a `withOutput` agent's cut-off answer ends `AgentStatus.Truncated` where 0.7.0 returned `Left(GraphError.OutputInvalid)`, and `llm4s-anthropic`'s default `max_tokens` follows the model where it was 2048, so a call can return and cost more
  ([migration](docs/reference/migration.md#a-cut-off-answer-ends-truncated-anthropics-max_tokens-default-and-reported-penalties-2933)) [#2933](https://github.com/llm4s/llm4s/issues/2933).

### Core

- **Changed** `AuthenticationError`, `RateLimitError`, `ServiceError`, `CircuitOpenError`, `EmbeddingError`, `ContentFilteredError`, `ContextLengthError`, `ModelNotFoundError` and `UnsupportedContentPartError` take and return their provider as a `ProviderId`, and `HttpErrorMapper`, `PartEncoding` and `HttpResponse.ensureSuccess` / `mapServiceError` take it as one; messages and `context` keep their text, except that a `ContentFilteredError` now names the provider's id (`gemini`, `vertexai`) rather than a display name ([#2936](https://github.com/llm4s/llm4s/pull/2936); [migration](docs/reference/migration.md#an-errors-provider-is-a-providerid-2936)).

### Agent runtime

- **Fixed** an agent with `withOutput` (or `withOutputCheck` / `withOutputJsonCheck`) whose answer is cut off at the output limit ends the turn `AgentStatus.Truncated`, as any agent does: the cut-off answer is not checked against the schema and no corrective retry is spent on it, where 0.7.0 returned `Left(GraphError.OutputInvalid)`. A reply cut off before any text (all output spent on thinking) is `Truncated("")` for every agent, not a `ValidationError`; `decodeOutput` and a WARN log say the output limit was the cause, with the token counts ([#2933](https://github.com/llm4s/llm4s/issues/2933); [migration](docs/reference/migration.md#a-cut-off-answer-ends-truncated-anthropics-max_tokens-default-and-reported-penalties-2933)).

### Providers

- **Changed** `llm4s-anthropic`'s default `max_tokens`, sent when `CompletionOptions.maxTokens` is unset, follows the model instead of being 2048: its maximum output tokens from the model registry on a streamed call, the same capped at 16,000 on a non-streamed call, 8,192 for an unknown model. An explicit `maxTokens` is sent unchanged. OpenRouter, which sends no `max_tokens` then, no longer fits a Claude model's thinking budget under 2048: it is fitted below the model's maximum output when the registry states it, and otherwise below 32,000, reported `Adjusted` ([#2933](https://github.com/llm4s/llm4s/issues/2933); [Anthropic](docs/guide/providers.md#sampling-parameters-thinking-and-output-length)).
- **Fixed** `presencePenalty` and `frequencyPenalty` are no longer dropped silently: the Anthropic, Gemini, Vertex AI and Bedrock clients report each as `RequestAdjustment.Dropped` on `Completion.adjustments`, Ollama sends them, and `llm4s-provider-testkit`'s `assertModelCallContract` fails a client that neither sends nor reports them ([RFC 0008](docs/rfcs/0008-generation-controls.md); [#2933](https://github.com/llm4s/llm4s/issues/2933)).

### Build & tooling

- **Added** `llm4s-scalafix`: the Scalafix migration rules are published with each release, as `org.llm4s::llm4s-scalafix` (`llm4s-scalafix_2.13`, a build-tool artifact outside the BOM), so `sbt "scalafixAll dependency:Llm4sMigrateFrom_0_7@org.llm4s::llm4s-scalafix:<version>"` migrates code on 0.7.x ([#2330](https://github.com/llm4s/llm4s/issues/2330); [Scalafix](docs/reference/scalafix.md#running-a-migration-rule)).
- **Changed** the MiMa baseline is 0.7.0: `sbt mimaReportBinaryIssues` compares the seven frozen modules with their 0.7.0 artifacts, with no filter, and a binary break to one now fails CI ([#1281](https://github.com/llm4s/llm4s/issues/1281); [API Stability](docs/reference/api-stability.md#the-baseline)).

## [0.7.0] - 2026-11-25

### Highlights

0.7.0 is the release the binary-compatibility baseline is set at: the designs of the seven frozen modules are final,
and 1.0 is the next release. It is the last one that breaks freely, and it breaks a lot: the API review renamed
accessors, closed constructors and typed what was a `String`, so read the
[migration guide](docs/reference/migration.md) and run the Scalafix rule `Llm4sMigrateFrom_0_6`, which rewrites the
mechanical part. Every model call now follows one contract ([RFC 0009](docs/rfcs/0009-model-call-contract.md)): a
typed `FinishReason` on every `Completion`, and a content-filter stop, a stream that did not finish and a broken tool
call are errors on every provider, not partial answers. Errors have one retry rule, `LLMError.isRetryable`, and carry
`httpStatus`, `retryAfter` and `requestId`; a context-length overflow and an unknown model are typed. Generation
controls are provider-neutral ([RFC 0008](docs/rfcs/0008-generation-controls.md)): `thinking`, `promptCache` and
`serviceTier` on `CompletionOptions`, with what a provider could not honour reported on `Completion.adjustments`.
Model ids, provider ids and base URLs are newtypes across the public API, provider clients are final with one factory,
and `LLMClient.validate()` checks the provider. In the agent runtime, `llm4s-agent` has a small `@Stable` core, input
guardrails run before anything of a turn is stored, a cut-off answer ends `AgentStatus.Truncated`, checkpoints can be
encrypted at rest, and `Agent.abandon` closes a run that will not be recovered. RAG answers stream, take a
conversation history, and a `RAG` with a `SearchIndex` no longer ingests or queries without permissions. Releases
carry SLSA build provenance from this one on.

### Breaking changes

- `LLMError.isRetryable` is the one retry rule: `RecoverableError`, `NonRecoverableError`, `LLMError.isRecoverable`, `recoverableErrors`, `nonRecoverableErrors` and `ServiceError.isRecoverableStatus` are removed, and a `ServiceError` with a 404 is no longer "recoverable"
  ([migration](docs/reference/migration.md#one-retry-rule-llmerrorisretryable-replaces-the-recoverable-markers-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- Every `LLMError` has `httpStatus`, `retryAfter` (only a delay the server gave) and `requestId`: `ServiceError.httpStatus` is `status`, `CircuitOpenError.retryAfter` is `remaining`, `retryDelay` and the errors' `DefaultRetryDelay` are removed (`RetryPolicy.DefaultRateLimitDelay`), and a 400 mapped from a response carries its status
  ([migration](docs/reference/migration.md#every-llmerror-reports-httpstatus-retryafter-and-requestid-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- A request too long for the model is a `ContextLengthError` and an unknown model a `ModelNotFoundError`, where they were a `ValidationError` or a `ServiceError`
  ([migration](docs/reference/migration.md#a-context-length-overflow-and-an-unknown-model-are-typed-errors-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- `AgentBuilder.withTools` and `withToolSet` add to the agent's tools instead of replacing them; `build()` refuses a tool name defined twice
  ([migration](docs/reference/migration.md#agentbuilder-tools-are-additive-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- `StreamedChunk.content` is `contentDelta` and `toolCall` is `toolCallDelta`, with `withContentDelta` and `withToolCallDelta`
  ([migration](docs/reference/migration.md#streamedchunk-names-its-deltas-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- Java: `LlmException.isRecoverable()` is `isRetryable()`, and `LlmErrorKind` gains `CONTENT_FILTER`, `CONTEXT_LENGTH` and `MODEL_NOT_FOUND`, which an exhaustive `switch` or Kotlin `when` must handle
  ([migration](docs/reference/migration.md#llmexceptionisretryable-and-three-new-llmerrorkinds-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- `AgentResult` has a seventh field, `runUsage`, so a positional pattern on it takes one more binder (Scalafix's `PositionalPatternsGainFields` adds it), and `PromptInjectionDetector`'s default patterns match fewer texts
  ([migration](docs/reference/migration.md#agentresultrunusage-and-tighter-promptinjectiondetector-defaults-2922-2923)) [#2922](https://github.com/llm4s/llm4s/issues/2922), [#2923](https://github.com/llm4s/llm4s/issues/2923).
- `LLMClient.getContextWindow()`, `getReserveCompletion()` and `getContextBudget(...)` are `contextWindow`, `reserveCompletion` and `contextBudget(...)`; `ConversationTokenCounter.getTokenBreakdown` is `tokenBreakdown`, `ModelDimensionRegistry.getDimension` is `dimension`, and `ToolBuilder.buildSafe()` is `build()`
  ([migration](docs/reference/migration.md#llmclient-accessors-are-named-for-their-values-and-toolbuilderbuild-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- `ThinkingBlock.Opaque.provider` is a `ProviderId`, `BaseLifecycleLLMClient.providerName: String` is `provider: ProviderId`, and `MetricsRecording.withMetrics` takes a `ProviderId` and a `ModelName`; stored JSON is unchanged
  ([migration](docs/reference/migration.md#thinkingblockopaque-and-baselifecyclellmclient-take-a-providerid-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- `ValidationError.violations`, `ConfigurationError.missingKeys`, the `ToolParameterError` and `ToolCallError.InvalidArguments` error lists, `Result.sequence`, `traverse` and `validateAll`, `AgentResult.messages` and `OpenAICompatibleClient.parseCitations` are `Seq`s
  ([migration](docs/reference/migration.md#errors-result-helpers-and-agentresultmessages-are-seqs-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- `CachedEmbeddingClient`'s key function is `(String, EmbeddingRequest) => String`, the text and its request; the default keys are unchanged
  ([migration](docs/reference/migration.md#cachedembeddingclients-key-function-takes-the-request-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- `UserMessage.parts` and `ToolMessage.parts` are `Seq[ContentPart]`, and `Completion.citations`, the `RequestAdjustment` lists (`Completion`, `TransformationResult`, `OpenAICompatibleDialect.addReasoning`, `ModelCallCompleted`) are `Seq`s; stored JSON is unchanged
  ([migration](docs/reference/migration.md#message-parts-citations-and-adjustments-are-seqs-2809)) [#2809](https://github.com/llm4s/llm4s/issues/2809).
- The `@Experimental` overloads of Stable methods are renamed, so MiMa checks the Stable ones: `AgentBuilder.withTools(toolSet)` is `withToolSet`, `Guardrail.validate(value, context)` is `validateIn`; the Java facade's guardrails sit innermost, as the Scala builder's do
  ([migration](docs/reference/migration.md#withtoolset-and-validatein-and-the-java-facades-guardrails-sit-innermost-2835)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- `LangfuseBatchSender` has `sendTraces` and `sendScores` in place of `sendBatch`, `LANGFUSE_URL` is the host (`LangfuseConfig.DEFAULT_URL` is `https://cloud.langfuse.com`), and an unplaced run's Langfuse trace id is `TraceContext.rootFor(runId).traceId`
  ([migration](docs/reference/migration.md#the-langfuse-backend-sends-opentelemetry-spans-2883)) [#2883](https://github.com/llm4s/llm4s/issues/2883).
- A model id is a `ModelName` wherever it crosses a public API (`Completion.model`, `ModelMetadata.modelId`, `ModelRegistryService.lookup`, trace events, every `fromValues`), and a provider config's base URL is a `BaseUrl`; stored JSON is unchanged
  ([migration](docs/reference/migration.md#model-ids-are-modelname-provider-ids-providerid-and-provider-configs-base-urls-baseurl-2822)) [#2822](https://github.com/llm4s/llm4s/issues/2822).
- A provider id is a `ProviderId` in `ModelMetadata.provider`, `ModelRegistryService.lookup(provider, model)`, `listByProvider` and `listProviders`, `ContextWindowResolver`, `ModelDimensionRegistry.getDimension` and `ModelRef`
  ([migration](docs/reference/migration.md#provider-ids-are-providerid)) [#2822](https://github.com/llm4s/llm4s/issues/2822).
- `CompletionOptions.temperature`, `topP`, `presencePenalty` and `frequencyPenalty` are `Option[Double]`, `None` unless set, and no client sends an unset one
  ([migration](docs/reference/migration.md#completionoptions-sampling-parameters-are-optional-and-unset-ones-are-not-sent-2807)) [#2807](https://github.com/llm4s/llm4s/issues/2807).
- `Completion` reads `content` and `toolCalls` from its `message`: `apply` takes neither, and `withContent`, `withToolCalls` and `asText` are removed
  ([migration](docs/reference/migration.md#completion-reads-content-and-toolcalls-from-its-message-2807)) [#2807](https://github.com/llm4s/llm4s/issues/2807).
- A `Completion` built without a finish reason is `FinishReason.Unknown`, not `Stop`; `Unknown` replaces `FinishReason.Other("")`
  ([migration](docs/reference/migration.md#a-completion-without-a-finish-reason-is-finishreasonunknown-2807)) [#2807](https://github.com/llm4s/llm4s/issues/2807).
- `Completion.estimatedCost`, `CostEstimator.estimate`, `UsageSummary.add` and the agent's `recordUsage` take a `BigDecimal` cost, not a `Double`
  ([migration](docs/reference/migration.md#completionestimatedcost-is-a-bigdecimal-usagesummary-counts-cache-tokens-2807)) [#2807](https://github.com/llm4s/llm4s/issues/2807).
- `AssistantMessage.contentOpt` is `text`, its stored JSON is flat (the old shape still reads), `AssistantMessage()` no longer compiles and `ToolMessage.findToolCallName` returns an `Option`
  ([migration](docs/reference/migration.md#assistantmessagetext-and-a-flat-stored-assistant-message-2809)) [#2809](https://github.com/llm4s/llm4s/issues/2809).
- `Conversation.create` takes one `Seq[Message]`, and `Conversation.empty` is a value
  ([migration](docs/reference/migration.md#one-conversationcreate-conversationempty-is-a-value-2809)) [#2809](https://github.com/llm4s/llm4s/issues/2809).
- `ProviderExchange.provider` is a `ProviderId` and `duration` is derived; `ProviderExchangeRecorder.record` takes a `ProviderExchangeCall`; `ProviderExchangeLogging.enabled` is removed
  ([migration](docs/reference/migration.md#providerexchange-and-providerexchangerecorder-2809)) [#2809](https://github.com/llm4s/llm4s/issues/2809).
- `MessageRole` is a Scala 3 `enum`: code that used a case as a type (`MessageRole.User.type`) or from Java (`User$.MODULE$`) changes
  ([migration](docs/reference/migration.md#messagerole-is-a-scala-3-enum-2809)) [#2809](https://github.com/llm4s/llm4s/issues/2809).
- `LLMConnect.fromConfig`, `fromProvider` and the `getClient` overloads taking a `MetricsCollector` are removed: `getClient(config, LlmClientOptions(metrics = m))`
  ([migration](docs/reference/migration.md#llmconnectgetclient-is-the-one-way-to-get-a-client-2810)) [#2810](https://github.com/llm4s/llm4s/issues/2810).
- `LLMClient.streamComplete(conversation, options)(onChunk)`: the callback is a second parameter list, in every call and every `LLMClient` implementation
  ([migration](docs/reference/migration.md#llmclientstreamcomplete-takes-its-callback-in-a-second-parameter-list-2810)) [#2810](https://github.com/llm4s/llm4s/issues/2810).
- `MetricsRecording.withMetrics` has no defaulted parameters: pass `extractUsage` and `extractCost`
  ([migration](docs/reference/migration.md#llmclientstreamcomplete-takes-its-callback-in-a-second-parameter-list-2810)) [#2810](https://github.com/llm4s/llm4s/issues/2810).
- The context compression pipeline in `org.llm4s.context` is `@Experimental`: `ContextManager` is built only with `ContextManager.create`, `ContextStep.name` is `stage: ContextStage`, and `StructuredInfo`, `HistoryDigest` and `PipelineStep` are private
  ([migration](docs/reference/migration.md#the-context-pipeline-is-experimental-and-token-counters-are-built-over-a-tokenizer-2818)) [#2818](https://github.com/llm4s/llm4s/issues/2818).
- `ConversationTokenCounter.forModel`, `TokenizerMapping` and `TokenizerAccuracy` leave `llm4s-core` (use `OpenAITokenizers.counterFor(model)` in `llm4s-openai`); the counter's factories are `create(tokenizerId)`, `cl100k()` and `o200k()`, and `TokenizerId` moves to `org.llm4s.context.tokens`
  ([migration](docs/reference/migration.md#the-context-pipeline-is-experimental-and-token-counters-are-built-over-a-tokenizer-2818)) [#2818](https://github.com/llm4s/llm4s/issues/2818).
- `Modality`'s cases are nested (`Modality.Text`, ...); multimodal embedding (`MultimodalEmbeddingProvider`, `MultimediaEmbeddingRequest`, `EmbeddingVector`) moves to `llm4s-rag`, and `EmbeddingProvider.embedMultimodal` and the `*Input` types are removed
  ([migration](docs/reference/migration.md#modality-is-an-enum-multimodal-embedding-lives-in-llm4s-rag-and-embeddingclient-is-final-2808)) [#2808](https://github.com/llm4s/llm4s/issues/2808).
- `EmbeddingClient` is final with a private constructor (`EmbeddingClient(provider)` and its `with*` setters); `EmbeddingClient.from` reports an unknown provider as a `ConfigurationError`, and `EmbeddingError` moves to `org.llm4s.error`
  ([migration](docs/reference/migration.md#modality-is-an-enum-multimodal-embedding-lives-in-llm4s-rag-and-embeddingclient-is-final-2808)) [#2808](https://github.com/llm4s/llm4s/issues/2808).
- `org.llm4s.types`: `HeadroomPercent` is built only with `create` or a preset and read with `asRatio`; the aliases `TokenBudget`, `ContextWindowSize`, `ByteCount` and `ExternalizationThreshold` are removed; `SemanticBlockId`, `ArtifactKey` and `ContentSize` are opaque types; `TraceId` moves to `llm4s-observability`
  ([migration](docs/reference/migration.md#newtypes-in-orgllm4stypes-are-opaque-and-headroompercent-is-always-valid-2822)) [#2822](https://github.com/llm4s/llm4s/issues/2822).
- `ProviderDescriptor.buildConfig` takes `(section, context: ProviderConfigContext)` and `buildClient` is `build(config, context: ProviderBuildContext)`; `EmbeddingProviderDescriptor`'s two methods take an `EmbeddingConfigContext` and an `EmbeddingBuildContext`
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- `Llm4sProviderModule.chatProviders` is `providers`; `EmbeddingProviderSection.baseUrl` / `model` are
  `Option[BaseUrl]` / `Option[ModelName]` ([#2811](https://github.com/llm4s/llm4s/issues/2811));
  [migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811).
- `AccessTokenProvider.token()` and `IdentityTokenSource.fetch()` are `current(): Result[Secret]`, `IdentityTokenSource` is `IdentityTokenProvider`, and their token arguments are `Secret`s, not `String`s
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- `AgentStatus.Blocked` and `TaskRefusal.Blocked` take `reason: Option[String]`, and `RunOutcome.Blocked` holds the guardrail's name only: a guardrail's reason is on the result of the run that was blocked and is never stored. Checkpoint format 9 ([#2853](https://github.com/llm4s/llm4s/issues/2853)); [migration](docs/reference/migration.md#a-guardrails-reason-is-never-stored-2853).
- Input guardrails run when a turn is admitted, before it is stored and before any middleware's `beforeAgent`; `GuardrailBlocked.reason` is an `Option`; `Guardrail.validateIn(value, context)` (the stage form of `validate`, renamed by [#2835](https://github.com/llm4s/llm4s/issues/2835)) takes a `UsageAccounting` ([#2854](https://github.com/llm4s/llm4s/issues/2854)); [migration](docs/reference/migration.md#a-turns-query-is-admitted-before-the-turn-is-stored-2854).
- `llm4s.agent.runs.ended` and `llm4s.agent.run.duration` report the status the run's trace does: a guardrail block is
  `blocked`, not `failed`, and a turn that hit its step or token limit or was cut off is `step_limit_reached`,
  `token_limit_reached` or `truncated`, not `completed`; check alerts on `status="failed"` and `status="completed"`;
  [migration](docs/reference/migration.md#an-agent-runs-trace-and-metrics-report-one-status-2829).
- `TraceEvent.AgentRunEnded.status` is a `TraceEvent.RunStatus`, not a `String`; a blocked run's status is `blocked`
  with the guardrail in the new `blockedBy`, where it was `blocked:<guardrail>`, in `toJson` and every backend;
  [migration](docs/reference/migration.md#an-agent-runs-trace-and-metrics-report-one-status-2829).
- `TraceEvent.AgentRunEnded.agent` is the run's root agent, not the agent active when it ended (the new `activeAgent`),
  so the `invoke_agent <agent>` span of a run that handed off is named after the agent it was started on;
  [migration](docs/reference/migration.md#an-agent-runs-trace-and-metrics-report-one-status-2829).
- `TraceEvent.ErrorOccurred` holds `message`, `errorType: String` and an optional `cause` instead of a `Throwable`:
  build one with `ErrorOccurred.of(error, context)`, from an `LLMError` or a `Throwable`; an `LLMError` the library
  traces is recorded under its own type, not `RuntimeException`, and with no stack trace;
  [migration](docs/reference/migration.md#traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800).
- `TraceEvent.ImageGenerationCompleted.provider` is an `Option[ProviderId]` and the event's last field, not a `String`
  in second place;
  [migration](docs/reference/migration.md#traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800).
- `TraceEvent.AgentInitialized` is removed (nothing in the library emitted it), and with it the `LLM4S Agent Run`
  trace and span the Langfuse and OpenTelemetry backends made of it; trace your own with `TraceEvent.CustomEvent`;
  [migration](docs/reference/migration.md#traceevent-carries-an-error-as-it-is-and-agentinitialized-is-removed-2800).
- `LLMClientRetry` and `ErrorRecovery` (`recoverWithBackoff`, `CircuitBreaker`, `CircuitState`) are removed:
  `ReliableClient` with a `RetryPolicy` is the one retry and circuit breaker; exhausted attempts return the last
  error unchanged, not an `ExecutionError`, and an open circuit is a `CircuitOpenError`, which is not retried
  ([#2798](https://github.com/llm4s/llm4s/issues/2798));
  [migration](docs/reference/migration.md#one-retry-mechanism-llmclientretry-and-errorrecovery-are-removed-2798).
- `HttpErrorMapper.mapHttpError(...): Result[Nothing]` is `HttpErrorMapper.toError(status, body, provider, headers):
  LLMError`, with no default for `headers`; `StreamingToolArgumentParser` is removed (`ToolCallRules.parseArguments`);
  `CostEstimator.estimateFromMetadata` is internal ([#2811](https://github.com/llm4s/llm4s/issues/2811));
  [migration](docs/reference/migration.md#provider-spi-error-mapping-published-helpers-report-types-2811).
- `ProviderConfigSpec.keylessWhen` is an `Option[KeylessSetting]`, not a tuple; the provider registry's report types have no public `apply` or setters and carry `ProviderId`s; `EmbeddingProviderDescriptor.configSpec` is abstract
  ([migration](docs/reference/migration.md#provider-spi-error-mapping-published-helpers-report-types-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- The `Result` object is `org.llm4s.types.Result`; `toResult` and `toLLMError` are extensions in `org.llm4s.syntax`
  (`TryOps`, `OptionOps`, `FutureOps`, `ThrowableOps` and `syntax.syntax` are gone); `ErrorMapper` is removed and
  `Try(...).toResult` no longer reads a 401 or 429 out of an exception's message; `Safety` is in `org.llm4s.error` and
  `NetworkSecurity` in `org.llm4s.security`; [migration](docs/reference/migration.md#result-helpers-and-conversions-move-and-errormapper-is-removed-2821).
- `AssistantError`, `SessionId`, `FilePath` and `DirectoryPath` move from `llm4s-core` to `llm4s-agent`, packages
  unchanged; `AssistantError` is an `LLMError` and formats as one;
  [migration](docs/reference/migration.md#assistanterror-and-the-assistants-newtypes-live-in-llm4s-agent-2820).
- `APIError` and `SystemError` are removed (a provider's error response is a `ServiceError`), with the
  `LLMError.processingFailed` / `invalidImageInput` / `apiCallFailed` shims, `LLMError.correlationId` and `show`,
  `RecoverableError.maxRetries` and the factories nothing called; `OptimisticLockFailure.memoryId` is `recordId`;
  `SimpleError`'s extractor binds only the message; [migration](docs/reference/migration.md#error-hierarchy-cleanup-2819).
- `Llm4sHttpClient` implementations and test doubles implement `send`; its eight request methods are final, `MultipartPart.FilePart.path` is `content`, and `Llm4sHttpClient.create` takes `HttpClientOptions` ([#2802](https://github.com/llm4s/llm4s/issues/2802)); [migration](docs/reference/migration.md#llm4shttpclient-has-one-method-to-implement-send-2802).
- `NetworkSecurity.validateUrl`/`validateHostname` return `Result[Seq[InetAddress]]` and refuse with a `ValidationError`; `validateIP`, `DefaultBlockedHostnames` and the default arguments are gone ([#2826](https://github.com/llm4s/llm4s/issues/2826)); [migration](docs/reference/migration.md#networksecurity-returns-the-addresses-it-vetted-and-a-refusal-is-not-retryable-2826).
- `Llm4sConfig.providerConfigs()` returns `Result[Map[ProviderName, Result[ProviderConfig]]]`; `providers()`, `providerConfigs(map)` and `ProvidersConfig` are no longer public ([#2804](https://github.com/llm4s/llm4s/issues/2804)); [migration](docs/reference/migration.md#llm4sconfigproviderconfigs-returns-each-sections-result-providers-and-providersconfig-are-internal-2804).
- `llm4s.providers.provider` is now `llm4s.defaultProvider` (Spring and Quarkus: `llm4s.default-provider`): the default section's name sits beside the providers block ([#2806](https://github.com/llm4s/llm4s/issues/2806)); [migration](docs/reference/migration.md#llm4sprovidersprovider-is-now-llm4sdefaultprovider-2806).
- `Llm4sConfig.provider` and `listModels` take a `ProviderName`; `embeddings()` returns the `EmbeddingProviderConfig` alone, which `EmbeddingClient.from` takes; `ProvidersConfigModel`, `Llm4sConfig.TextEmbeddingModelSettings` and `ConfigKeys` are removed
  ([migration](docs/reference/migration.md#llm4sconfig-takes-providername-and-embeddings-returns-the-config-alone-2804)) [#2804](https://github.com/llm4s/llm4s/issues/2804), [#2806](https://github.com/llm4s/llm4s/issues/2806).
- `ProviderConfig` has no default members: `model` is a `ModelName`, `endpointUrl` an `Option[BaseUrl]`, and `withApiKeyProvider` returns a `Result`; `EmbeddingProviderConfig` is `(providerId, baseUrl: BaseUrl, model: ModelName, apiKey: Option[Secret], ...)` ([#2805](https://github.com/llm4s/llm4s/issues/2805)); [migration](docs/reference/migration.md#providerconfig-has-no-default-members-and-embeddingproviderconfig-is-typed-2805).
- `new ToolRegistry(tools)` is `ToolRegistry.of(tools)`, a `Result` that rejects duplicate names; timeouts, retries,
  `executeAsync` and `executeAll` move to `ToolExecutor`; `toOpenAITool`/`getOpenAITools` are
  `toolDefinition(strict)`/`ToolFunction.toolDefinitions(tools, strict)`;
  [migration](docs/reference/migration.md#toolregistry-is-a-trait-built-with-toolregistryof-2816).
- `SchemaDefinition` gains `AnyOfSchema` and `RawJsonSchema` (an exhaustive match needs both); `withRange`,
  `withExclusiveRange`, `withLengthConstraints` and `withSizeConstraints` are removed; a strict schema makes optional
  properties nullable; [migration](docs/reference/migration.md#schemadefinition-gains-a-union-and-a-raw-json-schema-case-2814).
- A tool handler returns `Either[ToolHandlerError, R]` (its own failure is `Left(ToolHandlerError.failed("message"))`, not a `String`); `ToolFunction` and `ToolBuilder` have one type parameter, the result's; `executeEnhanced` and the `getXEnhanced` getters are removed
  ([String](...), [migration](docs/reference/migration.md#a-tool-handler-has-one-error-type-2815)).
- `ToolCall.id`, `ToolMessage.toolCallId` and `ToolCallInfo.toolCallId` are a `ToolCallId`, and `ToolCallInfo.idempotencyKey` an `IdempotencyKey`, both in core; the agent's own types are removed; [migration](docs/reference/migration.md#a-tool-calls-id-is-a-toolcallid-and-its-idempotency-key-an-idempotencykey-2815).
- `EmbeddingCache` has no type parameter and returns `Result`s; `InMemoryEmbeddingCache.create` replaces its constructors; `CachedEmbeddingClient`'s `cacheStats` is an `Option` and `clearCache` a `Result`
  ([migration](docs/reference/migration.md#the-caching-types-report-failure-and-tool-loops-bypass-the-cache-2803)) [#2803](https://github.com/llm4s/llm4s/issues/2803).
- `ModelRegistryConfig` holds one `ModelRegistrySource`; `lookup` is exact and `search` finds ids by substring; `ModelMetadata` loses `inputCostPerToken`/`outputCostPerToken` (use `pricing`) and its `deprecationDate` is a `LocalDate`; `RequestTransformer` is sealed
  ([migration](docs/reference/migration.md#model-registry-sources-exact-lookup-and-a-sealed-requesttransformer-2823)) [#2823](https://github.com/llm4s/llm4s/issues/2823).
- `GuardrailBlocked.message` no longer holds the guardrail's reason: read `reason`;
  [migration](docs/reference/migration.md#a-guardrail-blocks-message-names-only-the-guardrail-2793).
- `LLMClient.validate()` no longer returns `Right(())` unchecked: it sends the provider a request, and is
  `Left(ValidationUnsupportedError)` for a client with none, your own included;
  [migration](docs/reference/migration.md#llmclientvalidate-checks-the-provider-2830).
- `TokenWindow.trimToBudget` (and so `ContextManager`) returns a `ContextError` when the pinned system and digest messages, or they and the newest turn, do not fit, where it returned an empty or digest-only conversation, as does `ContextManager` for a system prompt that does not fit, which it used to digest; with no `ArtifactStore` nothing is externalized
  ([migration](docs/reference/migration.md#context-trimming-fails-when-nothing-fits-and-externalizes-only-into-a-store-2817)) [#2817](https://github.com/llm4s/llm4s/issues/2817), [#2918](https://github.com/llm4s/llm4s/issues/2918).
- `StreamingAccumulator.create()` is `StreamingAccumulator(model)`; `updateTokens`, `updateTokensWithThinking` and
  `addThinkingDelta` are removed for `updateUsage`; `SSEEvent.retry` is a `FiniteDuration`; `SSEParser.parseStream` and
  `getEvents` are removed; [migration](docs/reference/migration.md#streamingaccumulator-takes-the-model-and-reports-usage-one-way-2813).
- `ProviderRegistry.get` is removed: use `resolve`, which with `find`, `findEmbedding` and `resolveEmbedding` now
  accepts an alias as well as an id;
  [migration](docs/reference/migration.md#providerregistry-lookups-accept-aliases-2812).
- `MetricsCollector.recordRetryAttempt`, `recordCircuitBreakerTransition` and `recordError` are removed: `ReliableClient` records
  `ReliabilityMetrics.Retries`, `CircuitBreakerTransitions` and `Errors` through `increment`, so a collector that overrode them overrides `increment`;
  [migration](docs/reference/migration.md#reliableclients-metrics-are-generic-metrics-2801).
- `HuggingFaceClient` calls Hugging Face Inference Providers (`hf-inference`); `HuggingFaceConfig` gains `baseUrl`, and
  `Parameters.inferenceSteps` is `num_inference_steps`; [migration](docs/reference/migration.md#the-hugging-face-image-client-calls-inference-providers-2832).
- `OpenAIConfig` (image) defaults to `gpt-image-2.6-sunburst`, and `OpenAIImageClient.deprecationWarningMessage` is `retirementWarning`;
  [migration](docs/reference/migration.md#the-openai-image-client-defaults-to-gpt-image-26-sunburst-2884).
- `MetricsCollector.observeImageGeneration` and `recordImageGenerationCost` are removed, with `MicrometerMetrics.Names`' five image constants: the image metrics are `ImageMetrics` in `llm4s-image`, recorded through `increment`/`observe`; the exported series are unchanged;
  [migration](docs/reference/migration.md#image-metrics-are-imagemetrics-in-llm4s-image-2801).
- `Tracing` has one abstract method, `traceEvent(TraceEvent)`: `traceToolCall`, `traceError`, `traceCompletion` and `traceTokenUsage` are `final`
  (delete your overrides), `traceToolCall` takes the call's `duration` and `success`, `shutdown()` is `close()` (`Tracing` is `AutoCloseable`), the `TracingComposer`
  trait is gone (the object stays) and the tracing mode `print` is no longer read; [migration](docs/reference/migration.md#tracing-has-one-abstract-method-2799).
- `Tracing.create` is removed (`Tracing.fromSettings` is the only factory; write the `NoOpTracing` fallback yourself), `TracingMode.fromString("otel")` is `Named("otel")` (the alias is `llm4s-observability-otel`'s), `TracingBackends.find` returns `Result[Option[TracingBackend]]`, and two discovered backends for one mode are an error, not "the later wins";
  [migration](docs/reference/migration.md#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799).
- Langfuse traces are private: `LangfuseTracing` sent every trace `public: true` and as user `llm4s-user`; it now sends
  `public: false` unless `llm4s.tracing.langfuse.public = true`, and the run's own user or none;
  [migration](docs/reference/migration.md#langfuse-traces-are-private-and-name-the-runs-user-2828).
- `ReliableClient` refuses a call on an open circuit with `CircuitOpenError`, not `ServiceError(503, "circuit-breaker")`; `RateLimitConfig.default` is disabled like `RateLimitConfig()`; `ReliabilityConfig.withoutDeadline` and the instance `disabled` are removed
  ([migration](docs/reference/migration.md#an-open-circuit-is-a-circuitopenerror-2797)) [#2797](https://github.com/llm4s/llm4s/issues/2797).
- `new ReliableClient(...)` no longer compiles: the constructor is private. Use `ReliableClient(client, name, config)` and `.withMetrics(collector)`; the `clock` and `sleep` parameters are no longer public
  ([migration](docs/reference/migration.md#reliableclient-has-a-private-constructor-2797)) [#2797](https://github.com/llm4s/llm4s/issues/2797).
- `new AnthropicClient(...)`, and `new` on any other provider client, no longer compiles: the clients are final with one factory, `X(config, options: LlmClientOptions)`; an Azure client is `OpenAIClient.azure(config, options)`, and the vendor client types on `OpenAICompatibleClient` are no longer public
  ([migration](docs/reference/migration.md#provider-clients-are-final-with-one-factory-2910)) [#2910](https://github.com/llm4s/llm4s/issues/2910).
- `new CachingLLMClient(...)` and `new CachedEmbeddingClient(...)` no longer compile: both are final with a private constructor. Drop the `new`; `CachingLLMClient`'s `clock` parameter is `.withClock(clock)`
  ([migration](docs/reference/migration.md#cachingllmclient-and-cachedembeddingclient-have-private-constructors-2910)) [#2910](https://github.com/llm4s/llm4s/issues/2910).
- `RegexSafetyManager.safeCompile` and `safeFind` return `Result` (a `ValidationError`), not `Either[String, _]`; `safeFind`'s `maxSteps`, `safeMatches`, `compileLiteralCaseInsensitive`, `replaceAllLiteral`, `replaceFirstLiteral` and `RegexComplexityException` are removed
  ([migration](docs/reference/migration.md#regexsafetymanager-returns-result-2825)) [#2825](https://github.com/llm4s/llm4s/issues/2825).
- `SecretPatterns` (`SecretType`, `SecretMatch`) moved from `org.llm4s.util` in `llm4s-core` to `org.llm4s.agent.guardrails.patterns` in `llm4s-agent`; `redactAllWithPlaceholder` and `SecretMatch`'s setters are removed, and `SecretType.PrivateKeyPem` matches the whole key
  ([migration](docs/reference/migration.md#secretpatterns-is-in-llm4s-agent-2824)) [#2824](https://github.com/llm4s/llm4s/issues/2824).
- 36 `llm4s-agent` case classes have a private constructor and `copy` (use `X(...)` and the `with*` setters); `GuardrailResult` is removed; `AgentStatus.Suspended`, eight `RunEvent` cases, `StreamEvent.Live` and `ToolOutcome.Success` each hold one growth-safe class in place of positional fields
  ([migration](docs/reference/migration.md#llm4s-agent-types-are-tiered-and-growth-safe-2778)) [#2781](https://github.com/llm4s/llm4s/issues/2781).
- `LengthCheck`, `ProfanityFilter`, `JSONValidator` and `RegexValidator` are final with private constructors: build them with their companions (`LengthCheck(1, 100)`, `ProfanityFilter.withCustomWords(...)`, `RegexValidator("...".r, "message")`). `ThreadId.child` is internal;
  [migration](docs/reference/migration.md#llm4s-agent-types-are-tiered-and-growth-safe-2778).
- Middleware SPI: `runsBefore`/`runsAfter` are `Set[MiddlewareRef]`; `ModelRequest.messages` no longer holds the system prompt (`systemPrompt`); `BoundaryResult.withUpdate`, `ToolSuccess.withUpdate` and `ToolCallRequest.withSpec`/`withCall`/`withResources` are removed; a middleware id the agent reserves is refused
  ([migration](docs/reference/migration.md#the-middleware-spi-is-consistent-and-testable-2795)).
- `CompletionOptions.budgetTokens` and `withBudgetTokens`, and `ReasoningEffort.None`, are removed: use `thinking`
  (`Thinking.budget(n)`, `Thinking.Off`); Java's `budgetTokens` and `JReasoningEffort.NONE` likewise give way to
  `JThinking`. On OpenAI, `Thinking.Off` asks for the lowest effort where `None` sent none (RFC 0008, PR 4);
  [migration](docs/reference/migration.md#budgettokens-and-reasoningeffortnone-are-replaced-by-thinking-2115).
- `StreamedChunk.finishReason` is an `Option[FinishReason]`, no longer an `Option[String]`; a positional
  pattern on `Completion` or `ModelCallCompleted` gains a field ([RFC 0009](docs/rfcs/0009-model-call-contract.md) PR 1);
  [migration](docs/reference/migration.md#finish-reasons-are-typed-2613).
- A content-filter stop is a `Left(ContentFilteredError)` on every client, not a `Completion`; so is a stream that ends without its end marker or reports an error event, and a tool call whose arguments are not a JSON object or were cut by the output limit (RFC 0009). `ProviderModuleChecks.assertStreams` is removed: use `assertModelCallContract`
  ([migration](docs/reference/migration.md#every-providers-content-filter-stop-is-an-error-2677), [migration](docs/reference/migration.md#a-stream-that-did-not-finish-is-an-error-2613), [migration](docs/reference/migration.md#a-broken-or-cut-tool-call-is-an-error-2613)).
- A positional pattern gains fields on `TokenUsage` and `ModelPricing` (the 1-hour prompt-cache write), `CompletionOptions` (`promptCache`, `serviceTier`) and `Completion`, `ModelCallCompleted` and `TraceEvent.CompletionReceived` (`servedTier`)
  ([RFC 0008](docs/rfcs/0008-generation-controls.md), [migration](docs/reference/migration.md#tokenusage-and-modelpricing-gain-a-prompt-cache-field-2115)) [#2726](https://github.com/llm4s/llm4s/issues/2726), [#2746](https://github.com/llm4s/llm4s/issues/2746), [#2750](https://github.com/llm4s/llm4s/issues/2750).
- An agent turn whose answer was cut off at the output limit ends `AgentStatus.Truncated(partialAnswer)`, not
  `Completed`, and `AgentStatusKind` gains `TRUNCATED`; a `match` on `AgentStatus` needs the case (RFC 0009 open
  question 6); [migration](docs/reference/migration.md#a-cut-off-answer-ends-truncated-not-completed-2613).
- The forwarding `apply` overloads kept for types that gained a field (`Completion`, `NamedProviderConfig`, `RunConfig`,
  four `TraceEvent` cases and 14 more types) are removed: each type has one `apply`, with defaults, and Java and Kotlin pass
  every argument [#1281](https://github.com/llm4s/llm4s/issues/1281); [migration](docs/reference/migration.md#the-forwarding-apply-overloads-are-removed-1281).
- `CompressionRule`'s function field `apply` is `transform`, and `withApply` is `withTransform`
  [#1281](https://github.com/llm4s/llm4s/issues/1281); [migration](docs/reference/migration.md#compressionrules-apply-field-is-transform-1281).
- On a `RAG` with a `SearchIndex`, plain `ingest*`, `sync`, `refresh`, `query`, `queryWithAnswer(Streaming)` and
  `clear` return a `ConfigurationError`, and a hybrid
  `queryWithPermissions` needs `SearchIndex.keywordQuery`, which replaces `queryHybrid` (#2716);
  [migration](docs/reference/migration.md#a-rag-with-a-searchindex-ingests-only-with-permissions-2716).
- A `DocumentRegistry` of your own implements `register(docId, version, sourceId)` and `documentIds(sourceId)`
  (#2714); [migration](docs/reference/migration.md#documentregistry-is-source-scoped-2714).
- A positional pattern on `RAGAnswerResult` or `TraceEvent.RAGOperationCompleted` gains a field (`metrics`,
  `stageDurations`) (#2734); [migration](docs/reference/migration.md#rag-tracing-covers-every-model-call-and-each-querys-stages-2734).
- `RAG.ingestChunks` takes `Seq[DocumentChunk]` and returns `Seq[IndexedChunk]`, and `SearchIndex.ingest` returns the
  stored ids (#2732); [migration](docs/reference/migration.md#ragingestchunks-takes-documentchunks-2732).
- `SQLiteVectorStore`'s metadata filters are case-sensitive and read `%` and `_` literally, as `MetadataFilter`
  describes (#2759);
  [migration](docs/reference/migration.md#sqlitevectorstore-escapes-metadata-and-filters-exactly-2759).
- `DirectoryLoader` skips symbolic links that leave its root, and a positional pattern on `UrlLoader`, `CrawlerConfig`
  or `DirectoryLoader` gains a field (#2724); [migration](docs/reference/migration.md#rag-loaders-take-a-urlpolicy-and-directoryloader-stays-in-its-root-2724).
- Opening a `PostgresCheckpointer` moves its schema from version 5 to 6, which an older build refuses: upgrade every
  process sharing the database together (#2453); [migration](docs/reference/migration.md#the-postgresql-checkpoint-schema-moves-to-version-6-2453).
- A `ContainerisedWorkspace` with `networkAllowed = false` (the default) has no egress, and needs this release's image
  and a Docker host that grants `NET_ADMIN` (#870); [migration](docs/reference/migration.md#a-workspace-container-has-no-egress-870).
- `ContextWindowConfig.minRecentTurns` is honoured and defaults to 1, not 3 (`AdaptiveWindowing.preserveMinTurns` is removed in its favour), and `maxTokens` and `maxMessages` set together are both enforced
  ([migration](docs/reference/migration.md#context-pruning-enforces-both-limits-and-minrecentturns-2728-2729)) [#2728](https://github.com/llm4s/llm4s/issues/2728), [#2729](https://github.com/llm4s/llm4s/issues/2729).
- `EMBEDDING_PROVIDER` (`llm4s.embeddings.provider`) is removed: select embeddings with `EMBEDDING_MODEL=provider/model`; so are the per-provider model settings
  (`llm4s.embeddings.<id>.model`, the `*_EMBEDDING_MODEL` variables, `EmbeddingConfigSpec.modelEnv`, `EmbeddingProviderSection.model`) ([#2835](https://github.com/llm4s/llm4s/issues/2835));
  [migration](docs/reference/migration.md#the-legacy-embedding-provider-selector-is-removed).
- Vertex AI no longer reads `endpoint` and `organization` (use `project` and `location`); a section that sets `organization` and no `location` fails ([#2835](https://github.com/llm4s/llm4s/issues/2835)), and `ProviderConfigKey`'s
  `deprecatedAliases` mechanism is removed; [migration](docs/reference/migration.md#vertex-ai-no-longer-reads-endpoint-and-organization).

### Providers

- **Changed** `MistralModelLister` is `@Experimental`, as the Mistral dialect it lists for is [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Fixed** A `temperature` or thinking budget set for Claude Sonnet 5 and 5.5, Haiku 5.5, Opus 5 and 5.5, Fable or Mythos was sent and answered 400: no sampling parameter and no `budget_tokens` goes to these models (Anthropic, Bedrock, an OpenAI-compatible gateway), by the catalog's `supports_sampling_params` (new `ModelCapabilities.supportsSamplingParams`) or the model's name, and each drop is a `RequestAdjustment`. [#2909](https://github.com/llm4s/llm4s/issues/2909)
- **Changed** the provider clients are final, or closed to subclasses outside their module, with one factory taking `LlmClientOptions`; a config a client refuses is a `Left`, never an exception, and the six vendor factories on `OpenAICompatibleClient` return an `OpenAICompatibleClient`
  ([migration](docs/reference/migration.md#provider-clients-are-final-with-one-factory-2910)) [#2910](https://github.com/llm4s/llm4s/issues/2910).
- **Fixed** `HuggingFaceClient` posted to `api-inference.huggingface.co`, which Hugging Face retired: it calls
  `router.huggingface.co/hf-inference` in the documented text-to-image format, defaults to a model `hf-inference` serves
  and checks health through the Hub API ([migration](docs/reference/migration.md#the-hugging-face-image-client-calls-inference-providers-2832)) [#2832](https://github.com/llm4s/llm4s/issues/2832).
- **Fixed** `OpenAIImageClient` defaulted to `dall-e-2`, which OpenAI shut down, and `editImage` always sent it: the default is
  `gpt-image-2.6-sunburst`, `editImage` sends the configured model, and a GPT Image request carries no `response_format` or `style` ([migration](docs/reference/migration.md#the-openai-image-client-defaults-to-gpt-image-26-sunburst-2884)) [#2884](https://github.com/llm4s/llm4s/issues/2884).
- **Fixed** `llm4s-openai` sends `max_completion_tokens` to the GPT-6 models; a request with tools to `gpt-6-luna` is sent
  `reasoning_effort: none` (reported as a `RequestAdjustment`), and one to `gpt-6.1-terra`, which calls tools only on the Responses API, is refused with a `ValidationError` [#2886](https://github.com/llm4s/llm4s/issues/2886).
- **Changed** The bundled model metadata is refreshed from LiteLLM upstream (2,784 to 7,162 entries, with `claude-sonnet-5-5`, `claude-haiku-5-5`,
  `gpt-5.6-luna`/`-terra` and `gemini-3.8-flash`); the refresh is a release-checklist step [#2832](https://github.com/llm4s/llm4s/issues/2832) [#2889](https://github.com/llm4s/llm4s/issues/2889).
- **Changed** With the refreshed metadata, the context window an `openai-compatible` section takes from the model registry changes for some models
  (Together's `meta-llama/Llama-3.3-70B-Instruct-Turbo` now gets 131072, not the 8192 default) [#2889](https://github.com/llm4s/llm4s/issues/2889).
- **Changed** No client sends `temperature`, `top_p` or a penalty the caller did not set (OpenAI, the OpenAI-compatible providers, Anthropic, Gemini, Vertex AI, Bedrock, Ollama, watsonx, Perplexity); Jlama, whose engine needs a temperature, uses `0.7` when none is set. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Changed** A provider module's client reports `FinishReason.Unknown` where it read no finish reason, and Perplexity ignores a reported cost that is not a number in favour of the estimate. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Fixed** A key xAI refuses (HTTP 400) is an `AuthenticationError`, not a `ValidationError` ([#2855](https://github.com/llm4s/llm4s/issues/2855)).
- **Changed** `HttpErrorMapper.mapHttpError` is `toError`, returning the `LLMError`; `ToolCallRules`, `PartEncoding`
  and `HttpErrorMapper.mapStreamError` are public; `StreamingToolArgumentParser` is removed
  ([migration](docs/reference/migration.md#provider-spi-error-mapping-published-helpers-report-types-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Changed** `ProviderConfigSpec.keylessWhen` is a `KeylessSetting`; the registry report types are read-only with
  `ProviderCollisionKind` and `ProviderId`; `EmbeddingProviderDescriptor.configSpec` is abstract;
  `AccessTokenProvider.invalidate` has a default; `CostEstimator.estimateFromMetadata` is internal
  ([migration](docs/reference/migration.md#provider-spi-error-mapping-published-helpers-report-types-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Changed** Both provider descriptors have the same two methods, `buildConfig(section, context)` and `build(config, context)`; the loose `using` parameters are fields of the four context types
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Added** `EmbeddingClientOptions.withHttpClient`: `EmbeddingClient.from(provider, config, options)` sends an
  embedding provider's requests through the caller's own HTTP client. OpenAI, Ollama, Voyage, Jina and Cohere
  honour it, and `llm4s-provider-testkit` has `assertEmbeddingUsesSuppliedHttpClient`
  [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Changed** `EmbeddingProviderSection.baseUrl` and `model` are a `BaseUrl` and a `ModelName`. **Added**
  `EmbeddingConfigSpec.extras`: an embedding provider declares its own `llm4s.embeddings.<id>` keys, which reach it
  in `EmbeddingProviderSection.extras` and `EmbeddingProviderConfig.extras`
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Changed** `Llm4sProviderModule.chatProviders` is `providers`: across the SPI an unprefixed name is the chat half
  and `embedding*` the embedding half
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Changed** `AccessTokenProvider.token()` and `IdentityTokenSource.fetch()` are `current()` and return a `Secret`;
  `IdentityTokenSource` is `IdentityTokenProvider`; `AccessToken.value`, `AccessTokenProvider.invalidate` and
  `IdentitySource.Literal` hold a `Secret`
  ([migration](docs/reference/migration.md#provider-spi-one-shape-for-both-descriptors-typed-embedding-sections-credential-suppliers-2811)) [#2811](https://github.com/llm4s/llm4s/issues/2811).
- **Added** `OpenAITokenizers` in `llm4s-openai`: `tokenizerFor(model)` and `counterFor(model)` map an OpenAI model
  (or an Azure deployment named after one) to its tokenizer. **Fixed** `gpt-4.1`, `gpt-4.5`, `gpt-5`, `o3` and
  `o4-mini` count with `o200k_base`; they counted with `cl100k_base`, some after an "Unknown model" warning
  ([#2818](https://github.com/llm4s/llm4s/issues/2818)).
- **Removed** Vertex AI's `endpoint` / `organization` aliases for `project` / `location`, and with them
  `ProviderConfigKey.deprecatedAliases` and `ProviderConfigSpec.BuiltinAliasKeys`; a section that sets `organization` and no `location` is a configuration error naming the fix, not run in `us-central1` ([#2835](https://github.com/llm4s/llm4s/issues/2835), [migration](docs/reference/migration.md#vertex-ai-no-longer-reads-endpoint-and-organization)).
- **Fixed** `SSEParser`'s streaming parser reads CRLF and CR line endings, where a CRLF stream fed in raw chunks yielded
  no event until `flush()`, and ends an event only at a blank line, where a chunk ending at a line break split it; a streamed `Completion.model` is the model the client was created for, never `"unknown"`,
  and `StreamingAccumulator` reports usage only through `updateUsage`, which keeps cache counts [#2813](https://github.com/llm4s/llm4s/issues/2813).
- **Fixed** `ProviderRegistry`: an id beats an alias, so another provider's alias can no longer make a provider
  unreachable; alias collisions are reported like id collisions; a descriptor whose `id` or `aliases` throws is
  recorded instead of aborting `ProviderRegistry.default`; and `discover()` also scans core's own class loader
  when the context loader is set [#2812](https://github.com/llm4s/llm4s/issues/2812).
- **Fixed** watsonx, Perplexity and Jlama leaving options out without reporting them: each now puts on
  `Completion.adjustments` what it does not send as asked (`reasoning` and `thinking`; Perplexity and Jlama also the
  penalties, Jlama also `top_p` and `responseFormat`), on `complete` and streams; watsonx's per-process warnings give way to the reports ([RFC 0008](docs/rfcs/0008-generation-controls.md) decision 15) [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Fixed** Anthropic under-pricing cached requests: `TokenUsage.promptTokens` is now the whole input, cache reads and
  writes included, on `complete` and streams, as every other provider reports it, through `SpringAiLlmClient` too (a
  Spring AI Anthropic or Bedrock model) [#2745](https://github.com/llm4s/llm4s/issues/2745), [#2765](https://github.com/llm4s/llm4s/issues/2765).
- **Added** `ModelCapabilities` reads the thinking and effort metadata: `supportsAdaptiveThinking`, `supportsOutputConfig`,
  `supportedReasoningEfforts`, `supportsThinkingOff`, and `supportsBudgetThinking`, which the overrides file's new patch
  entries set to `false` for Claude Opus 4.7/4.8 and Fable 5 ([RFC 0008](docs/rfcs/0008-generation-controls.md) PR 3) [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Added** `CompletionOptions.thinking` (`Thinking.Off`, `Adaptive`, or `Thinking.budget(n)`) and the effort levels `Minimal`, `XHigh` and `Max`, mapped by Anthropic, OpenAI, Gemini, Vertex AI and Ollama to what each model accepts and reported on `Completion.adjustments`; `CachingLLMClient` matches a cached answer on them
  ([cost controls](docs/guide/cost-controls.md), [RFC 0008](docs/rfcs/0008-generation-controls.md)) [#2726](https://github.com/llm4s/llm4s/issues/2726), [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Added** `CompletionOptions.promptCache` (`PromptCache`: scope `System`, `Tools` or `Conversation`, TTL `Short` or `Long`): Anthropic sends `cache_control` breakpoints and Bedrock `cachePoint` blocks, other providers report it `NotNeeded` or `Dropped`, and `TokenUsage.longCacheCreationTokens` prices a 1-hour cache write
  ([cost controls](docs/guide/cost-controls.md#provider-side-prompt-caching), [RFC 0008](docs/rfcs/0008-generation-controls.md)) [#2726](https://github.com/llm4s/llm4s/issues/2726), [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Added** `CompletionOptions.serviceTier` (`ServiceTier`: `Auto`, `Default`, `Flex`, `Priority`; Java `JServiceTier`, Kotlin `serviceTier`), sent by OpenAI, Azure, Anthropic, Gemini, Vertex AI and Bedrock and reported `Dropped` elsewhere; `Completion.servedTier` reports the tier that served the call, in traces too
  ([cost controls](docs/guide/cost-controls.md#service-tiers)) [#2726](https://github.com/llm4s/llm4s/issues/2726), [#2746](https://github.com/llm4s/llm4s/issues/2746), [#2751](https://github.com/llm4s/llm4s/issues/2751), [#2750](https://github.com/llm4s/llm4s/issues/2750).
- **Changed** Every member of `ProviderConfig` is abstract (`model: ModelName`, `endpointUrl: Option[BaseUrl]`), and `withApiKeyProvider` returns `Result[ProviderConfig]`: a `ConfigurationError` from a config that cannot take one, instead of the config unchanged
  ([migration](docs/reference/migration.md#providerconfig-has-no-default-members-and-embeddingproviderconfig-is-typed-2805)) [#2805](https://github.com/llm4s/llm4s/issues/2805).
- **Fixed** A section's `timeouts`, or its own `http` block, that its provider's config does not take (`jlama`) fails the load of that section instead of being dropped [#2805](https://github.com/llm4s/llm4s/issues/2805).
- **Changed** `ContextWindowResolver` is final; `resolve` returns `ContextLimits(contextWindow, reserveCompletion)`, takes `fallbackResolver: ModelName => ContextLimits` and has no `logPrefix` [#2805](https://github.com/llm4s/llm4s/issues/2805).
- **Fixed** Anthropic sends a tool's whole schema as its `input_schema`, so the top-level `required` list reaches Claude, and a `Schema.raw` tool on Anthropic or Bedrock is sent as written instead of throwing a `ClassCastException` (a non-object schema on Anthropic is a `ValidationError`) [#2903](https://github.com/llm4s/llm4s/issues/2903).

### Java & Kotlin APIs

- **Changed** `LlmException.isRecoverable()` is `isRetryable()`, backed by `LLMError.isRetryable`; `getStatusCode()` and `getRetryAfter()` answer for every error that carries the fact, and `getRequestId()` is new. **Added** `LlmErrorKind.CONTENT_FILTER` (was `VALIDATION`), `CONTEXT_LENGTH` and `MODEL_NOT_FOUND` ([migration](docs/reference/migration.md#llmexceptionisretryable-and-three-new-llmerrorkinds-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Changed** the Java facade's `inputGuardrails` and `outputGuardrails` sit innermost in the agent's middleware, where the Scala builder's `withInputGuardrails` and `withOutputGuardrails` put them, not first: an output guardrail checks the answer before any other middleware's `afterAgent` ([migration](docs/reference/migration.md#withtoolset-and-validatein-and-the-java-facades-guardrails-sit-innermost-2835)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Changed** `JCompletionOptions.temperature()`, `topP()`, `presencePenalty()` and `frequencyPenalty()` return `OptionalDouble`, empty when unset. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Added** `JUsageSummary.cachedTokens()`, `cacheCreationTokens()` and the same on `JModelUsage`. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Added** `JThinking` (`off()`, `adaptive()`, `budget(n)`) and `JCompletionOptions.Builder.thinking`, the Kotlin
  `completionOptions { thinking = ... }` property, and `JReasoningEffort.MINIMAL`, `XHIGH` and `MAX`, replacing
  `budgetTokens` and `NONE` ([RFC 0008](docs/rfcs/0008-generation-controls.md) PR 4) [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Added** `JRag.askStream`, which streams a RAG answer to a `JRagStreamListener` with its sources first and can be
  cancelled or awaited, and Reactor's `RagStreams.toFlux`, a `Flux<RagAnswerEvent>` over it [#2727](https://github.com/llm4s/llm4s/issues/2727).
- **Changed** The Spring Boot starter and the Quarkus extension name the default section with `llm4s.default-provider` (`llm4s.defaultProvider` is read too), replacing `llm4s.providers.provider` ([migration](docs/reference/migration.md#llm4sprovidersprovider-is-now-llm4sdefaultprovider-2806)) [#2806](https://github.com/llm4s/llm4s/issues/2806).

### Core

- **Changed** One error model: `LLMError.isRetryable` is the single retry rule, and every error reports `httpStatus`, `retryAfter` and `requestId`, which `HttpErrorMapper` and the OpenAI and Anthropic clients fill in; Scalafix's `ErrorIsRetryable` migrates call sites
  ([migration](docs/reference/migration.md#one-retry-rule-llmerrorisretryable-replaces-the-recoverable-markers-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Added** `ContextLengthError` (with `limit` and `requestedTokens`) and `ModelNotFoundError`, mapped by OpenAI, Anthropic, Gemini, Vertex AI, Ollama, Bedrock and the OpenAI-compatible client; `RetryPolicy.DefaultRateLimitDelay`, the wait for a rate limit whose provider gave no `Retry-After` ([migration](docs/reference/migration.md#a-context-length-overflow-and-an-unknown-model-are-typed-errors-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Changed** `StreamedChunk.content` is `contentDelta` and `toolCall` is `toolCallDelta`; Scalafix's `StreamedChunkDeltaNames` migrates call sites ([migration](docs/reference/migration.md#streamedchunk-names-its-deltas-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Changed** An `EmbeddingError` whose `code` is the status 408, 429 or 5xx is retryable, as a `ServiceError` with it is [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Changed** Accessors are named for their values: `LLMClient.contextWindow`, `reserveCompletion` and `contextBudget(...)`, `ConversationTokenCounter.tokenBreakdown`, `ModelDimensionRegistry.dimension`; `ToolBuilder.build()` replaces `buildSafe()`. The scalafix rule `AccessorRenames` rewrites callers and overriders ([migration](docs/reference/migration.md#llmclient-accessors-are-named-for-their-values-and-toolbuilderbuild-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- **Changed** `ThinkingBlock.Opaque` holds a `ProviderId`, a `BaseLifecycleLLMClient` declares `provider: ProviderId`, and `MetricsRecording.withMetrics` takes a `ProviderId` and a `ModelName`; `ThinkingBlockWireFormatSpec` pins the stored JSON, which is unchanged ([migration](docs/reference/migration.md#thinkingblockopaque-and-baselifecyclellmclient-take-a-providerid-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- **Changed** The error types' lists (`ValidationError.violations`, `ConfigurationError.missingKeys`, `ToolParameterError`, `ToolCallError.InvalidArguments`), `Result.sequence`, `traverse` and `validateAll`, `AgentResult.messages` and `OpenAICompatibleClient.parseCitations` are `Seq`s ([migration](docs/reference/migration.md#errors-result-helpers-and-agentresultmessages-are-seqs-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- **Changed** `CachedEmbeddingClient`'s key function takes the text and its `EmbeddingRequest`, so a key can use anything a request carries; `CacheKeyGenerator.embeddingKey(text, request)` is the default ([migration](docs/reference/migration.md#cachedembeddingclients-key-function-takes-the-request-2908)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- **Changed** the model's public collections are `Seq`s: message parts (were `Vector`), `Completion.citations` and the request adjustments (were `List`); `MessagePartsWireFormatSpec` pins the stored JSON, which is unchanged [#2809](https://github.com/llm4s/llm4s/issues/2809).
- **Changed** a tool call's id is a `ToolCallId` (`ToolCall.id`, `ToolMessage.toolCallId`, `ToolCallInfo.toolCallId`, the agent's events) and `ToolCallInfo.idempotencyKey` an `IdempotencyKey`, opaque types in core that the agent now shares; stored JSON is unchanged, and Scalafix's `ToolCallIdTyped` migrates call sites ([migration](docs/reference/migration.md#a-tool-calls-id-is-a-toolcallid-and-its-idempotency-key-an-idempotencykey-2815)) [#2815](https://github.com/llm4s/llm4s/issues/2815)
- **Changed** Model ids are `ModelName` and provider configs' base URLs `BaseUrl` across the public API, from `Completion` and `UsageSummary` to the registry, embeddings, trace events, the provider SPI and every `fromValues`; the scalafix rule `ModelIdNewtypes` wraps the common call sites ([migration](docs/reference/migration.md#model-ids-are-modelname-and-provider-configs-base-urls-are-baseurl-2822)) [#2822](https://github.com/llm4s/llm4s/issues/2822).
- **Changed** Provider ids are `ProviderId` in `ModelMetadata`, the model registry, `ContextWindowResolver` and `ModelDimensionRegistry`; a `ProviderId` reads and writes as its string ([migration](docs/reference/migration.md#provider-ids-are-providerid)) [#2822](https://github.com/llm4s/llm4s/issues/2822).
- **Changed** `CompletionOptions.temperature`, `topP`, `presencePenalty` and `frequencyPenalty` are `Option[Double]`, `None` unless set; every client sends a sampling parameter only when the caller set it, and a set one that a model cannot take is reported as a `RequestAdjustment` whatever its value (an explicit value equal to the old default used to go unreported). [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Changed** `Completion.content` and `Completion.toolCalls` are read from `Completion.message` instead of being stored beside it, so they cannot disagree with it. **Removed** `Completion.withContent`, `withToolCalls` and `asText`. **Fixed** a completion assembled from a stream reports its tool calls on `toolCalls`, which each client used to copy over by hand. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Added** `FinishReason.Unknown`, what a `Completion` carries when the client read no reason; `Completion.apply` defaults to it instead of `Stop`, and it replaces `FinishReason.Other("")`. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Changed** `Completion.estimatedCost` and `CostEstimator.estimate` are `BigDecimal`: a cost is exact and sums of costs do not drift. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Added** `UsageSummary.cachedTokens` and `cacheCreationTokens`, and the same on `ModelUsage`: what a prompt cache read and wrote, added up across a run. A summary stored before they existed reads with both at zero. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Changed** `AssistantMessage.contentOpt` is `text`, and an assistant message's stored JSON is flat like the other roles' (`content`, `toolCalls`); JSON of the old nested shape still reads, and thinking bindings made before stay valid. `AssistantMessage`'s text has no default; `ToolMessage.findToolCallName` returns an `Option`. [#2809](https://github.com/llm4s/llm4s/issues/2809)
- **Changed** `Conversation.create` takes one `Seq[Message]`; `Conversation.empty` is a value. `Message.validateConversation` and `Message.assistant` take a `Seq`. [#2809](https://github.com/llm4s/llm4s/issues/2809)
- **Changed** `ProviderExchange.provider` is a `ProviderId` and its `duration` is derived from its two instants; `ProviderExchangeRecorder.record` takes one `ProviderExchangeCall` (**Added**) in place of eleven parameters. **Removed** `ProviderExchangeLogging.enabled`. [#2809](https://github.com/llm4s/llm4s/issues/2809)
- **Changed** `MessageRole` is a Scala 3 `enum`. The derived JSON of `FinishReason`, `ServiceTier`, `RequestOption` and `RequestAdjustment`, which durable agent events store, is pinned by tests. [#2809](https://github.com/llm4s/llm4s/issues/2809)
- **Changed** `LLMConnect.getClient(config, options = LlmClientOptions.default)` and `getClient(provider, config, options)` are the only entry points. **Removed** `LLMConnect.fromConfig`, `fromProvider` and the `MetricsCollector` overloads. [#2810](https://github.com/llm4s/llm4s/issues/2810)
- **Changed** `LLMClient.streamComplete(conversation, options)(onChunk)`: the callback has its own parameter list, so the default options are reachable without named arguments. `MetricsRecording.withMetrics` has no defaulted parameters. [#2810](https://github.com/llm4s/llm4s/issues/2810)
- **Changed** The context compression pipeline (`ContextManager`, `ContextConfig`, `ContextStep`,
  `ManagedConversation`, `TokenWindow`, `DeterministicCompressor`, `ToolOutputCompressor`, `LLMCompressor`,
  `HistoryCompressor`, `SemanticBlocks`, `ArtifactStore`) is `@Experimental`; token counting stays `@Stable`.
  `ContextManager.create` is its one factory and validates the config; `ContextStep.stage` is a `ContextStage`
  ([#2818](https://github.com/llm4s/llm4s/issues/2818)).
- **Added** `ConversationTokenCounter.of(tokenizer)` counts with any `StringTokenizer`, and `StringTokenizer.count`
  lets a tokenizer count without building the token list (the bundled jtokkit ones do)
  ([#2818](https://github.com/llm4s/llm4s/issues/2818)).
- **Removed** `TokenizerMapping`, `TokenizerAccuracy`, `ConversationTokenCounter.forModel` and the
  `org.llm4s.identity` package: core names no vendor's models. `TokenizerId` is in `org.llm4s.context.tokens`
  ([#2818](https://github.com/llm4s/llm4s/issues/2818)).
- **Removed** `ContextConfig.enableSubjectiveEdits`, which `ContextManager` never read; call
  `DeterministicCompressor.compressToCap(..., enableSubjectiveEdits = true)` for the subjective rules
  ([#2818](https://github.com/llm4s/llm4s/issues/2818)).
- **Changed** `HeadroomPercent` cannot hold an invalid value: `create` or a preset is the only way to build one.
  The newtypes of `org.llm4s.types` are opaque types, as `ProviderModelTypes` already were; the bare aliases
  `TokenBudget`, `ContextWindowSize`, `ByteCount` and `ExternalizationThreshold` are gone
  ([#2822](https://github.com/llm4s/llm4s/issues/2822)).
- **Removed** `LLMClientRetry` and `ErrorRecovery` (`recoverWithBackoff`, `CircuitBreaker`, `CircuitState`):
  `ReliableClient` with a `RetryPolicy` is the one retry and circuit-breaker mechanism
  ([migration](docs/reference/migration.md#one-retry-mechanism-llmclientretry-and-errorrecovery-are-removed-2798)) [#2798](https://github.com/llm4s/llm4s/issues/2798).
- **Changed** `TraceEvent.AgentRunEnded.status` is a `TraceEvent.RunStatus` with the guardrail in `blockedBy`, not a
  `String` encoding `blocked:<guardrail>` [#2800](https://github.com/llm4s/llm4s/issues/2800).
- **Changed** `TraceEvent.ErrorOccurred` carries `message`, `errorType` and an optional `cause` instead of a `Throwable`,
  so an `LLMError` is traced with its own type; `ErrorOccurred.of` and `Tracing.traceError` take either
  [#2800](https://github.com/llm4s/llm4s/issues/2800).
- **Changed** `TraceEvent.ImageGenerationCompleted.provider` is an `Option[ProviderId]`
  [#2800](https://github.com/llm4s/llm4s/issues/2800).
- **Removed** `TraceEvent.AgentInitialized`, which nothing in the library emitted
  [#2800](https://github.com/llm4s/llm4s/issues/2800).
- **Changed** `Result`'s helper object is in `org.llm4s.types`, and the conversions (`toResult`, `toLLMError`) and combinators are Scala 3 extensions in `org.llm4s.syntax`; `Safety` moves to `org.llm4s.error` and `NetworkSecurity` to `org.llm4s.security`. **Removed** `ErrorMapper`, `org.llm4s.core.safety`, the `AsyncResult` object and `Result.combine`, `fromBoolean` and `fromFuture`
  ([migration](docs/reference/migration.md#result-helpers-and-conversions-move-and-errormapper-is-removed-2821)) [#2821](https://github.com/llm4s/llm4s/issues/2821).
- **Changed** `AssistantError` and the newtypes `SessionId`, `FilePath` and `DirectoryPath`, which only the interactive
  assistant uses, live in `llm4s-agent` (same packages). `AssistantError` is an `LLMError` (non-recoverable) and each
  case has an explicit fixed-arity `unapply`
  ([migration](docs/reference/migration.md#assistanterror-and-the-assistants-newtypes-live-in-llm4s-agent-2820)) [#2820](https://github.com/llm4s/llm4s/issues/2820).
- **Removed** from `org.llm4s.error`: `APIError` (the vision clients return a `ServiceError`), `SystemError`, the `LLMError` shims, `correlationId`, `show` and `maxRetries`. **Changed**: every error has an explicit fixed-arity `unapply`, and `OptimisticLockFailure.memoryId` is `recordId`
  ([migration](docs/reference/migration.md#error-hierarchy-cleanup-2819)) [#2819](https://github.com/llm4s/llm4s/issues/2819).
- **Changed** `Llm4sHttpClient` has one method to implement, `send(HttpRequest): Result[StreamingHttpResponse]`; `get`, `post` and the rest are final conveniences. A `timeout` bounds the wait for the response head and each read of the body, and `create` takes one `HttpClientOptions`
  ([migration](docs/reference/migration.md#llm4shttpclient-has-one-method-to-implement-send-2802)) [#2802](https://github.com/llm4s/llm4s/issues/2802).
- **Fixed** `NetworkSecurity.validateUrl` and `validateHostname` return the addresses they vetted, so a caller can connect to the address that was checked, and a refusal is a non-recoverable `ValidationError` instead of a retryable `NetworkError`. `validateIP` is removed and `DefaultBlockedHostnames` is private ([migration](docs/reference/migration.md#networksecurity-returns-the-addresses-it-vetted-and-a-refusal-is-not-retryable-2826)) [#2826](https://github.com/llm4s/llm4s/issues/2826).
- **Changed** `Llm4sSettings` is the one implementation behind `Llm4sConfig`, and it and `Llm4sConfig.from` are `@Stable`. `providerConfigs()` returns each section's `Result` by name, a provider section's `toString` shows no header, extra or auth value, and `NamedProviderConfig` is top-level in `org.llm4s.config`
  ([migration](docs/reference/migration.md#llm4sconfig-takes-providername-and-embeddings-returns-the-config-alone-2804), [migration](docs/reference/migration.md#llm4sconfigproviderconfigs-returns-each-sections-result-providers-and-providersconfig-are-internal-2804)) [#2804](https://github.com/llm4s/llm4s/issues/2804).
- **Fixed** An Ollama session built by `llm4s-repl` is routed by the `llm4s.http` proxy and TLS settings, which the section-map loader dropped ([#2804](https://github.com/llm4s/llm4s/issues/2804)).
- **Changed** The default provider section is named by `llm4s.defaultProvider`; every key of `llm4s.providers` is a section, so a section may be called `provider`. A configuration still setting `llm4s.providers.provider` gets a no-default error that says where the key went ([migration](docs/reference/migration.md#llm4sprovidersprovider-is-now-llm4sdefaultprovider-2806)) [#2806](https://github.com/llm4s/llm4s/issues/2806).
- **Removed** `org.llm4s.config.ConfigKeys` (one constant, no user), and the `llm4s.workspace` block from core's `reference.conf`: `llm4s-workspace-client`, its only reader, binds `WORKSPACE_DIR`, `WORKSPACE_IMAGE` and `WORKSPACE_PORT` itself [#2806](https://github.com/llm4s/llm4s/issues/2806).
- **Fixed** The error for a shared `llm4s.credentials.<id>` entry that sets both `apiKey` and `apiKeyFile` names the variables the provider's spec declares (`apiKeyEnv`) instead of two vendors hard-coded in core [#2806](https://github.com/llm4s/llm4s/issues/2806).
- **Fixed** `CachingLLMClient` bypasses the cache for a conversation with a tool call or tool result, so a tool loop's next step is no longer served the first step's completion; `CachedEmbeddingClient` keeps the base response's `usage`, `metadata` and `dim` and survives a failing `EmbeddingCache`
  ([migration](docs/reference/migration.md#the-caching-types-report-failure-and-tool-loops-bypass-the-cache-2803)) [#2803](https://github.com/llm4s/llm4s/issues/2803).
- **Fixed** `ModelRegistryConfig.default.withFilePath(...)` (or `withUrl`) loads that source instead of failing with "exactly one of resourcePath, filePath, or url", and `ModelRegistryService.lookup` no longer resolves an unknown id to the one model whose id contains it, so an unknown model never takes another's prices and capabilities [#2823](https://github.com/llm4s/llm4s/issues/2823).
- **Fixed** `LLMClient.validate()` checks something: one model lookup, model listing or key lookup per provider, with no completion; a client with no such request (Bedrock, Z.ai) returns the new `ValidationUnsupportedError` instead of `Right(())`
  ([guide](docs/guide/observability/index.md#startup-validation), [migration](docs/reference/migration.md#llmclientvalidate-checks-the-provider-2830)) [#2855](https://github.com/llm4s/llm4s/issues/2855), [#2830](https://github.com/llm4s/llm4s/issues/2830).
- **Fixed** context compression loses no content it cannot give back: nothing is externalized without an `ArtifactStore`, binary tool output is detected by content, `TokenWindow` pins every leading system and digest message and trims whole tool-call turns, and the subjective rules no longer rewrite user messages
  [#2817](https://github.com/llm4s/llm4s/issues/2817).
- **Fixed** `TracingComposer.filter` and `transform` now apply to `traceToolCall`, `traceError`, `traceCompletion` and `traceTokenUsage`, which went
  straight to the wrapped tracer: those four are `final` and built on `traceEvent`, the one method a `Tracing` implements, and
  `traceToolCall` records the duration and outcome it is given [#2799](https://github.com/llm4s/llm4s/issues/2799).
- **Fixed** `ReliableClient`'s fast-fail on an open circuit was a retryable 503, so an outer retry repeated it, and several callers arriving after the recovery time all probed the service; it is a `CircuitOpenError` carrying `retryAfter` that no retry rule repeats, and exactly one caller probes
  ([migration](docs/reference/migration.md#an-open-circuit-is-a-circuitopenerror-2797)) [#2797](https://github.com/llm4s/llm4s/issues/2797).
- **Added** `ReliabilityConfig.validate` (and `CircuitBreakerConfig.validate`, `RateLimitConfig.validate`): a `failureThreshold` or `burstCapacity` below 1 or a negative `requestsPerMinute` is a `ValidationError`, which a `ReliableClient` returns from every call [#2797](https://github.com/llm4s/llm4s/issues/2797).
- **Changed** `ReliableClient`'s constructor is private: build one with `ReliableClient(underlying, providerName, config)` (`config` defaults to `ReliabilityConfig.default`) and add a collector with `.withMetrics(collector)`
  ([migration](docs/reference/migration.md#reliableclient-has-a-private-constructor-2797)) [#2797](https://github.com/llm4s/llm4s/issues/2797).
- **Changed** `CachingLLMClient` and `CachedEmbeddingClient` are final with a private constructor: build one with the companion's `apply`, and give a `CachingLLMClient` a clock of your own with `withClock`
  ([migration](docs/reference/migration.md#cachingllmclient-and-cachedembeddingclient-have-private-constructors-2910)) [#2910](https://github.com/llm4s/llm4s/issues/2910).
- **Changed** A `TracingBackend` declares its own `aliases`, and `llm4s-observability-otel` declares `otel`, which core's mode parser no longer names; `TRACING_MODE=otel` works as before. Two backends answering to one name are a `ConfigurationError` naming both
  ([migration](docs/reference/migration.md#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799)) [#2799](https://github.com/llm4s/llm4s/issues/2799).
- **Removed** `Tracing.create`, which logged a missing or failing backend and returned `NoOpTracing`: `Tracing.fromSettings` returns the error
  ([migration](docs/reference/migration.md#a-tracing-backend-declares-its-aliases-and-tracingcreate-is-removed-2799)) [#2799](https://github.com/llm4s/llm4s/issues/2799).
- **Changed** `Secret`'s Scaladoc and the security guide state the registry contract (`Secret(value)` is never evicted, `Secret.token(value)` may be past 4096 values, so a per-tenant or per-request key is a `Secret.token`), and the over-cap warning names that cause
  ([migration](docs/reference/migration.md#secret-or-secrettoken-which-one-stays-registered-2824)) [#2824](https://github.com/llm4s/llm4s/issues/2824).
- **Fixed** `RegexSafetyManager.safeCompile` refused every quantified alternation, so `RegexValidator("(foo|bar)+")` failed every value and a `FileBackend`'s or the workspace's regex search for `(jpg|png)*` was refused or run as a literal; only overlapping alternatives such as `(a|ab)*` are refused now
  ([migration](docs/reference/migration.md#regexsafetymanager-returns-result-2825)) [#2825](https://github.com/llm4s/llm4s/issues/2825).
- **Added** `Completion.finishReason` (`FinishReason`: `Stop`, `ToolCalls`, `Length`, `Other(raw)`, and `Unknown` where the client read none), mapped by every chat client, carried in traces and on each agent call's `ModelCallCompleted`, and checked per provider by `llm4s-provider-testkit`'s `assertModelCallContract`
  ([migration](docs/reference/migration.md#finish-reasons-are-typed-2613), [RFC 0009](docs/rfcs/0009-model-call-contract.md)) [#2613](https://github.com/llm4s/llm4s/issues/2613).
- **Changed** with `Completion.finishReason`: Ollama reads `done_reason`, Anthropic streams trace the real `stop_reason`, a content-filter stop is a `ContentFilteredError` carrying the billed `usage`, a stream with no end marker is a `NetworkError`, and a broken or cut tool call is a `ProcessingError` where it was `{}` or dropped
  ([migration](docs/reference/migration.md#every-providers-content-filter-stop-is-an-error-2677), [migration](docs/reference/migration.md#a-stream-that-did-not-finish-is-an-error-2613), [migration](docs/reference/migration.md#a-broken-or-cut-tool-call-is-an-error-2613)) [#2613](https://github.com/llm4s/llm4s/issues/2613), [#2677](https://github.com/llm4s/llm4s/issues/2677), [#2676](https://github.com/llm4s/llm4s/issues/2676).
- **Fixed** `LLMCompressor.squeezeDigest` keeps each compressed `[HISTORY_SUMMARY]` digest in its original position instead of
  moving every digest to the end of the conversation [#2481](https://github.com/llm4s/llm4s/issues/2481).
- **Fixed** `HistoryCompressor`, `LLMCompressor` and `TokenWindow` recognise a history digest the same way: a `SystemMessage`
  starting with `[HISTORY_SUMMARY]`, so `TokenWindow` no longer pins a message that merely quotes the marker [#2505](https://github.com/llm4s/llm4s/issues/2505).
- **Fixed** message and conversation validation name the field they reject (`content`, `toolCallId`, `toolMessage`,
  `conversation`) and give the reason as the violation, where field and reason were swapped [#2796](https://github.com/llm4s/llm4s/issues/2796).
- **Fixed** the credential registry no longer keeps each value it refuses for being under 8 characters: it counts them and
  logs the first refusal only, so many short `Secret`s neither grow memory nor stay held [#2888](https://github.com/llm4s/llm4s/issues/2888).
- **Fixed** `ContextManager` no longer digests the leading system messages, so the system prompt survives history
  compression; the digests it made are dropped when the newest turn fits only without them [#2918](https://github.com/llm4s/llm4s/issues/2918).

### Agent runtime

- **Changed** `AgentBuilder.withTools` and `withToolSet` add to the agent's tools, as the middleware, handoff and guardrail setters do, and `build()` returns a `Left` naming a tool defined twice ([migration](docs/reference/migration.md#agentbuilder-tools-are-additive-2916)) [#2916](https://github.com/llm4s/llm4s/issues/2916).
- **Added** `AgentResult.runUsage` (`JAgentResult.runUsage()` for Java and Kotlin): what the run that just finished used, beside `usage`, the thread's total; a `resume`'s or `recover`'s is that run's alone [#2922](https://github.com/llm4s/llm4s/issues/2922).
- **Added** `AgentBuilder.withOutputCheck[O](check)` and `withOutputJsonCheck`: an application check on a structured answer that shares `withOutput`'s corrective retry and ends `GraphError.OutputInvalid` with its problems ([Agents](docs/guide/agents/index.md#a-typed-answer)) [#2920](https://github.com/llm4s/llm4s/issues/2920).
- **Changed** `PromptInjectionDetector`'s defaults no longer match ordinary narrative sentences ("ask Dan to act as lookout", "repeat the instructions"); `PromptInjectionDetector.defaults.without(names*)` drops default patterns by name and `defaultPatternNames` lists them ([Guardrails](docs/guide/agents/guardrails.md#prompt-injection-protection)) [#2923](https://github.com/llm4s/llm4s/issues/2923).
- **Changed** `AgentBuilder.withTools(ToolSet)` is `withToolSet` and `Guardrail.validate(value, context)` is `validateIn`, so no `@Experimental` method shares a name with a `@Stable` one, which MiMa would skip with it ([What MiMa Covers](docs/reference/api-stability.md#what-mima-covers)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Changed** `ToolArgumentValidator.default` supports `anyOf` and accepts `null` for a property that is not required,
  which is how a model sent a strict tool definition leaves one out [#2814](https://github.com/llm4s/llm4s/issues/2814).
- **Fixed** the JWT secret pattern is linear (60 KB of `eyJ` took 4.7 s in `SecretLeakGuardrail` and in the redaction of every log and error message), a PEM private key is redacted whole, not only its header line, and OpenAI project keys and Anthropic keys holding `-` or `_` are matched whole [#2824](https://github.com/llm4s/llm4s/issues/2824).
- **Changed** `SecretType` is open, so `SecretLeakGuardrail` takes an application's own credential pattern: `new SecretType(name, pattern, placeholder)`
  ([migration](docs/reference/migration.md#secretpatterns-is-in-llm4s-agent-2824)) [#2824](https://github.com/llm4s/llm4s/issues/2824).
- **Fixed** A guardrail's reason, which may quote the text it refused, no longer reaches the durable event log or a trace: `GuardrailBlocked.message`, which `TaskFailed` and `RunFailed` record, names only the guardrail
  ([limits](docs/guide/agents/streaming.md#limits), [migration](docs/reference/migration.md#a-guardrail-blocks-message-names-only-the-guardrail-2793)) [#2793](https://github.com/llm4s/llm4s/issues/2793).
- **Fixed** A guardrail's reason, which may quote the text it refused, is no longer stored: a blocked run's closing checkpoint records the guardrail's name (`RunOutcome.Blocked(guardrail)`), and `EncryptingCheckpointer` seals the error text of a failed run's outcome
  ([guide](docs/guide/agents/guardrails.md), [design](docs/design/typed-agent-runtime/4.62-admission-and-stored-outcomes.md)) [#2853](https://github.com/llm4s/llm4s/issues/2853).
- **Added** `AgentMiddleware.admit` (experimental): a stage that runs before anything of a turn is stored and may rewrite or refuse its query. Input guardrails run there, so a blocked query is in no checkpoint, event, queue entry or trace; `Agent.admit` admits a query before it is queued
  ([guide](docs/guide/agents/middleware.md#admission), [design](docs/design/typed-agent-runtime/4.62-admission-and-stored-outcomes.md)) [#2854](https://github.com/llm4s/llm4s/issues/2854).
- **Added** `GuardedContentContract` in `llm4s-agent-testkit`: what a guarded agent may leave in a store, for any `Checkpointer` and its queue ([guide](docs/guide/agents/contract-suites.md)) [#2853](https://github.com/llm4s/llm4s/issues/2853), [#2854](https://github.com/llm4s/llm4s/issues/2854).
- **Added** RAG guardrails run inside an agent (experimental): `RAGGuardrail.withTurnContext` is an output guardrail for `GuardrailMiddleware` that checks each final answer against the turn's retrieval-tool results; `GuardrailConfig.outputGuardrailsWithContext` does it for a preset
  ([guide](docs/guide/agents/guardrails.md#rag-guardrails)) [#2781](https://github.com/llm4s/llm4s/issues/2781).
- **Changed** `llm4s-agent` is tiered: a small `@Stable` core (`Agent`, `AgentBuilder`, `AgentResult`, `AgentStatus`, `AgentRun`, `Handoff`, the run ids, `RunConfig`, the guardrail traits and four validators) and the rest `@Experimental`, covered by `sbt stabilityTierCheck`; its Stable case classes are growth-safe
  ([tiers](docs/reference/api-stability.md#tiers-in-the-code), [migration](docs/reference/migration.md#llm4s-agent-types-are-tiered-and-growth-safe-2778)) [#2781](https://github.com/llm4s/llm4s/issues/2781), [#2778](https://github.com/llm4s/llm4s/issues/2778), [#2785](https://github.com/llm4s/llm4s/issues/2785).
- **Added** `AgentBuilder.withInputGuardrails` and `withOutputGuardrails`, the Stable way to attach guardrails; `withMiddleware(new GuardrailMiddleware(...))` is the Experimental form for choosing the position ([guide](docs/guide/agents/guardrails.md)) [#2785](https://github.com/llm4s/llm4s/issues/2785).
- **Changed** the middleware SPI (`@Experimental`) is consistent: every hook returns its state update with `addUpdate`, `ModelRequest` carries the system prompt as `systemPrompt`, `runsBefore`/`runsAfter` name a middleware by id or type with `MiddlewareRef`, and `ToolCallRequest` is read-only
  ([guide](docs/guide/agents/middleware.md), [migration](docs/reference/migration.md#the-middleware-spi-is-consistent-and-testable-2795)) [#2795](https://github.com/llm4s/llm4s/issues/2795).
- **Added** `AgentMiddleware.block(reason)` ends a turn `AgentStatus.Blocked` without a guardrail; `MiddlewareId.reserved` lists the ids the agent uses itself; and `MiddlewareTestKit` (`llm4s-agent-testkit`) calls a middleware's hooks with no agent or model
  ([guide](docs/guide/agents/middleware.md#testing-a-middleware)) [#2795](https://github.com/llm4s/llm4s/issues/2795).
- **Fixed** context pruning (`ContextWindowMiddleware`): `ContextWindowConfig.minRecentTurns`, which nothing read, keeps the last N turns whole under every strategy (default 1), and `maxTokens` and `maxMessages` set together are both limits
  ([guide](docs/guide/context-window-pruning.md)) [#2728](https://github.com/llm4s/llm4s/issues/2728), [#2729](https://github.com/llm4s/llm4s/issues/2729).
- **Added** checkpoint encryption at rest (`org.llm4s.agent.encryption`, experimental): `EncryptingCheckpointer`, `EncryptingRunQueue` and `EncryptingLongTermStore` wrap any store and seal what it holds with AES-256-GCM under keys from an `EncryptionKeySource` (per-tenant keys, rotation by key id)
  ([guide](docs/guide/agents/durable-checkpointers.md#encrypting-what-the-store-holds), [design](docs/superpowers/specs/2026-11-22-checkpoint-encryption-design.md)) [#2449](https://github.com/llm4s/llm4s/issues/2449).
- **Added** `Agent.abandon(threadId, reason)` closes a cancelled, failed or crashed run instead of recovering it: each unfinished tool call gets `reason` as its result, nothing runs again and the thread takes a new turn. Also on the Java, Kotlin, cats-effect, ZIO and Pekko wrappers, and for a dead worker's queued run
  ([guide](docs/guide/agents/index.md), [design 4.57](docs/design/typed-agent-runtime/4.57-stage-5-slice-w3-cancel-from-any-machine-and-the-busy-thread-policy.md)) [#1750](https://github.com/llm4s/llm4s/issues/1750).
- **Added** `Checkpointer.owner`: a thread's tenant and parent (`ThreadOwner`) without decoding its latest checkpoint, which
  `Agent.forget` and `GraphRuntime.deleteThread` now check with; a default over `latest` keeps other stores compiling, and the in-memory, SQLite and PostgreSQL stores read it from their indexed columns [#2642](https://github.com/llm4s/llm4s/issues/2642).
- **Changed** a turn whose final answer ended `FinishReason.Length` ends `AgentStatus.Truncated(partialAnswer)`, with no `answer` (Java `AgentStatusKind.TRUNCATED`); the `AgentStatusGainsTruncated` Scalafix lint reports a `match` without it
  ([migration](docs/reference/migration.md#a-cut-off-answer-ends-truncated-not-completed-2613), [RFC 0009](docs/rfcs/0009-model-call-contract.md)) [#2613](https://github.com/llm4s/llm4s/issues/2613).

### Configuration

- **Fixed** the config-policy `allowedProviders` check: it compares provider names trimmed and case-insensitively on both sides, and
  `allowedProviders`/`allowedModels` report an unset provider or model as such [#2504](https://github.com/llm4s/llm4s/issues/2504).

### Agent harness

- **Added** `PostgresCheckpointer.longTermStore`: a `LongTermStore` in the checkpoint database, so every machine of an
  agent service shares `/memories/` files (PostgreSQL schema 6), and `LongTermStoreContract` in `llm4s-agent-testkit`, the suite every store passes
  [#2453](https://github.com/llm4s/llm4s/issues/2453); [spec](docs/superpowers/specs/2026-11-22-postgres-long-term-store-design.md); [migration](docs/reference/migration.md#the-postgresql-checkpoint-schema-moves-to-version-6-2453).
- **Added** `ContainerisedWorkspace(commandCgroups = true)`: on Linux each workspace command runs in a cgroup of its own and is
  killed whole, so a daemon it starts cannot outlive it [#2008](https://github.com/llm4s/llm4s/issues/2008); [design §4.39](docs/design/typed-agent-runtime/4.39-workspace-command-confinement.md).
- **Fixed** cancelling a `ContainerisedWorkspace` command (an interrupted `executeCommand`, as when an agent run is cancelled)
  now cancels it on the runner too; it used to keep running, with any daemon it started [#2656](https://github.com/llm4s/llm4s/pull/2656).
- **Changed** a workspace container whose sandbox allows no network (`networkAllowed = false`, the default) has no egress: the image's entry point blocks every connection out. The path policy resolves `..` and links before `allowedPaths`/`readOnlyPaths` and returns its refusals as values
  ([sandbox](docs/reference/workspace-sandbox.md#container-egress), [migration](docs/reference/migration.md#a-workspace-container-has-no-egress-870)) [#870](https://github.com/llm4s/llm4s/issues/870).

### Tools

- **Changed** `ToolRegistry` is a trait built with `ToolRegistry.of`, which rejects duplicate tool names; the async,
  batch, timeout and retry surface is the experimental `ToolExecutor`, and `ToolRetryPolicy` (now with `maxDelay`) and
  `ToolExecutionStrategy.parallelWithLimit` return a `Result` instead of throwing
  ([migration](docs/reference/migration.md#toolregistry-is-a-trait-built-with-toolregistryof-2816)) [#2816](https://github.com/llm4s/llm4s/issues/2816).
- **Added** `AnyOfSchema` (`Schema.anyOf`) and `RawJsonSchema` (`Schema.raw`); `SchemaOf` describes a sum type as an
  `AnyOfSchema`, so a typed tool's arguments may hold one. **Fixed** a strict schema renders an optional property as
  nullable instead of dropping `required = false`
  ([migration](docs/reference/migration.md#schemadefinition-gains-a-union-and-a-raw-json-schema-case-2814)) [#2814](https://github.com/llm4s/llm4s/issues/2814).
- **Changed** a tool handler returns `Either[ToolHandlerError, R]`, a sealed trait Java and Scala 2.13 callers can read: a getter's `ToolParameterError` is reported as `ToolCallError.InvalidArguments`, the tool's own `ToolHandlerError.failed(message)` as `HandlerError`; `ToolFunction[R]` and `ToolBuilder[R]` drop the arguments type parameter
  ([migration](docs/reference/migration.md#a-tool-handler-has-one-error-type-2815)) [#2815](https://github.com/llm4s/llm4s/issues/2815).
- **Fixed** the Windows workspace command policy no longer reads the `C:` in a `findstr /IC:text` switch as a drive
  path; a real drive path in a `/IG:` value is still confined [#1738](https://github.com/llm4s/llm4s/issues/1738), [#2625](https://github.com/llm4s/llm4s/pull/2625).
- **Fixed** `file_info` describes the filesystem root instead of throwing, and its `sizeHuman` is `1.5 KB` under every
  default locale [#2670](https://github.com/llm4s/llm4s/issues/2670), [#2669](https://github.com/llm4s/llm4s/issues/2669).

### MCP

- **Fixed** `MCPToolRegistry` returns a local tool's own error instead of `UnknownFunction`, lists a shadowed tool
  name once, and gets timeouts and retries through `ToolExecutor` for its remote tools too [#2816](https://github.com/llm4s/llm4s/issues/2816).
- **Fixed** an MCP tool's nested object, `anyOf` and nullable properties are described to the model as they are, not
  as strings [#2814](https://github.com/llm4s/llm4s/issues/2814).
- **Fixed** DEBUG logging of an MCP payload over 128 KiB no longer redacts it whole (about a second per megabyte,
  counted against the request's timeout): the preview is redacted from a prefix, or omitted where none is safe [#2774](https://github.com/llm4s/llm4s/pull/2774).

### Embeddings & RAG

- **Changed** `Modality` is an `enum`; a wildcard import of `org.llm4s.llmconnect.model` no longer brings a bare
  `Text` and `Image` into scope ([#2808](https://github.com/llm4s/llm4s/issues/2808)).
- **Changed** `MultimediaEmbeddingRequest`, `RawMediaInput` and `EmbeddingVector` live in `llm4s-rag` with
  `FileEmbedder`, their one consumer. `RawMediaInput` copies its bytes and compares by content;
  `EmbeddingVector.values` is a `Seq[Double]` ([#2808](https://github.com/llm4s/llm4s/issues/2808)).
- **Changed** Multimodal embedding is the separate `MultimodalEmbeddingProvider` capability in `llm4s-rag`, passed to
  `FileEmbedder.encodeFromPath`; `EmbeddingProvider.embedMultimodal` and its `501` default are removed
  ([#2808](https://github.com/llm4s/llm4s/issues/2808)).
- **Changed** `EmbeddingClient` is final: `EmbeddingClient(provider)` with `withTracing`, `withOperation` and
  `withProviderId`. **Fixed** `EmbeddingClient.from` returns the registry's `ConfigurationError` for an unknown
  provider, where it rewrote it to an `EmbeddingError` with an invented HTTP status. `EmbeddingError` is in
  `org.llm4s.error` ([#2808](https://github.com/llm4s/llm4s/issues/2808)).
- **Removed** the provider-only `EMBEDDING_PROVIDER` / `llm4s.embeddings.provider` selector and the per-provider model settings only it read (`llm4s.embeddings.<id>.model`, the `*_EMBEDDING_MODEL` variables); a config still setting them fails, naming `EMBEDDING_MODEL`
  ([migration](docs/reference/migration.md#the-legacy-embedding-provider-selector-is-removed)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Fixed** `RAGConfig.withTracing` traces the answer, LLM-rerank and `LLMQueryRewriter` calls, tagged `rag.answer`,
  `rag.rerank`, `rag.rewrite`, and each query's `RAGOperationCompleted` with stage durations; **Added**
  `RAGAnswerResult.metrics` (`RAGQueryMetrics`) [#2734](https://github.com/llm4s/llm4s/issues/2734).
- **Added** `UrlPolicy` on `UrlLoader`, `WebCrawlerLoader` and robots.txt (allow-listed hosts, extra blocked hosts,
  `allowPrivateNetworks`), checked on every request and redirect and connected to the approved address against DNS
  rebinding; **Fixed** `DirectoryLoader` stays in its root and reads only regular files [#2724](https://github.com/llm4s/llm4s/issues/2724).
- **Added** `RAGConfig.withCompletionOptions`: the options `queryWithAnswer` and `queryWithPermissionsAndAnswer` generate
  the answer with, which each call can override (`options = ...`); the default is still temperature 0.0. Java:
  `JRagBuilder.completionOptions` [#2715](https://github.com/llm4s/llm4s/issues/2715).
- **Added** `RAG.queryWithAnswerStreaming` and `queryWithPermissionsAndAnswerStreaming`: the contexts to one callback
  first, so a UI can show citations at once, then each `StreamedChunk` of the answer, then the whole
  `RAGAnswerResult` [#2717](https://github.com/llm4s/llm4s/issues/2717).
- **Added** an optional `history: Seq[Message]` on `queryWithAnswer`, `queryWithPermissionsAndAnswer` and their streaming forms, and `RAGConfig.withQuestionCondenser` to retrieve with a standalone question built from it; Java's `JRag.ask`/`askStream` take a `List<JMessage>` history
  [#2733](https://github.com/llm4s/llm4s/issues/2733), [#2767](https://github.com/llm4s/llm4s/issues/2767), [#2766](https://github.com/llm4s/llm4s/issues/2766).
- **Fixed** a `RAG` with a `SearchIndex` refuses plain ingest, sync, `query` and `clear` (a `ConfigurationError`) instead of writing rows no permission query finds or searching every collection unfiltered; `queryWithPermissions` runs the configured fusion, keyword search and reranking
  ([migration](docs/reference/migration.md#a-rag-with-a-searchindex-ingests-only-with-permissions-2716)) [#2716](https://github.com/llm4s/llm4s/issues/2716).
- **Added** `RAGConfig.withDocumentRegistry` with `PgDocumentRegistry` (can share a `DataSource`) and
  `SQLiteDocumentRegistry`, so `sync` survives a restart; `sync(loader, sourceId)` deletes only its own source's
  documents [#2714](https://github.com/llm4s/llm4s/issues/2714);
  [migration](docs/reference/migration.md#documentregistry-is-source-scoped-2714).
- **Added** pre-chunked ingest that keeps each chunk's `ChunkMetadata` in its stored record: `RAG.ingestChunks` and
  `ingestChunksWithPermissions` take `DocumentChunk`s and return the stored ids, with `RAG.chunk` and
  `ingestBytesWithPermissions` [#2732](https://github.com/llm4s/llm4s/issues/2732);
  [migration](docs/reference/migration.md#ragingestchunks-takes-documentchunks-2732).
- **Changed** `EmbeddingProviderConfig` carries `providerId: ProviderId`, `baseUrl: BaseUrl`, `model: ModelName` and `apiKey: Option[Secret]`; `EmbeddingClient.from(config, options)` resolves the provider from it, and `RAGASFactory.fromConfigs` takes the config alone
  ([migration](docs/reference/migration.md#providerconfig-has-no-default-members-and-embeddingproviderconfig-is-typed-2805)) [#2805](https://github.com/llm4s/llm4s/issues/2805), [#2804](https://github.com/llm4s/llm4s/issues/2804).
- **Changed** `LocalEmbeddingModels` and the local encoders' dimension table (`LocalEmbeddingModels.dimension`) moved to `llm4s-rag`; `ModelDimensionRegistry` answers from registered providers only, and `localDimension` / `LocalProvider` are removed [#2805](https://github.com/llm4s/llm4s/issues/2805).
- **Fixed** `SQLiteVectorStore` metadata with a quote, backslash or control character survives a write and a read, and
  its filters compare exactly instead of with `LIKE` on the JSON text; earlier rows still read
  [#2759](https://github.com/llm4s/llm4s/issues/2759);
  [migration](docs/reference/migration.md#sqlitevectorstore-escapes-metadata-and-filters-exactly-2759).
- **Fixed** An S3 listing goes on past a page whose every object is filtered out, so `RAG.sync` no longer deletes the
  documents after it; a truncated page without a next one is an error [#2914](https://github.com/llm4s/llm4s/issues/2914).

### Observability

- **Changed** The OpenTelemetry request attributes `gen_ai.request.temperature`, `top_p` and the penalties are set only when the caller set the option. [#2807](https://github.com/llm4s/llm4s/issues/2807)
- **Changed** `TraceId` is `org.llm4s.trace.model.TraceId` in `llm4s-observability`, its only user; it was in
  `org.llm4s.types` in `llm4s-core` ([#2822](https://github.com/llm4s/llm4s/issues/2822)).
- **Changed** `llm4s.agent.runs.ended` and `llm4s.agent.run.duration` report the status the run's trace does: a guardrail
  block is `blocked`, not `failed`, and a limited or cut-off turn `step_limit_reached`, `token_limit_reached` or
  `truncated`, not `completed`
  ([migration](docs/reference/migration.md#an-agent-runs-trace-and-metrics-report-one-status-2829)) [#2829](https://github.com/llm4s/llm4s/issues/2829).
- **Changed** A run that hands off keeps its `invoke_agent <agent>` span under the agent it was started on:
  `TraceEvent.AgentRunEnded.agent` is the root agent, and the new `activeAgent` the one active at the end
  [#2829](https://github.com/llm4s/llm4s/issues/2829).
- **Fixed** An agent run's `chat <model>` span has the model call's duration, where it was a point: the durable
  `ModelCallCompleted` event gains `duration`, measured around the client (zero in an event stored before), and a
  positional pattern on it gains a field, which `PositionalPatternsGainFields` adds
  ([guide](docs/guide/observability/agents.md#span-names-and-attributes)) [#2829](https://github.com/llm4s/llm4s/issues/2829).
- **Fixed** `PrometheusMetrics` dropped `ReliableClient`'s retries, circuit-breaker transitions and errors: they are now generic metrics
  (`org.llm4s.reliability.ReliabilityMetrics`) that every backend exports, as `llm4s_retries_total`, `llm4s_circuit_breaker_transitions_total`
  and `llm4s_reliability_errors_total` [#2801](https://github.com/llm4s/llm4s/issues/2801).
- **Changed** The image generation metrics are `ImageMetrics` in `llm4s-image`, generic metrics every backend exports; `MetricsCollector` has no image method. Every exported series keeps its name, type, labels and buckets, pinned by `ImageMetricsSeriesSpec`
  ([migration](docs/reference/migration.md#image-metrics-are-imagemetrics-in-llm4s-image-2801)) [#2801](https://github.com/llm4s/llm4s/issues/2801).
- **Added** `MetricsCollector.Errors`, the failed-operations counter (`llm4s_errors_total`) as a `Metric`, so a module above core adds its failed operations to it [#2801](https://github.com/llm4s/llm4s/issues/2801).
- **Fixed** The `langfuse` tracing mode sends OTLP/HTTP JSON to Langfuse's OpenTelemetry endpoint (`/api/public/otel/v1/traces`),
  as Langfuse removed `/api/public/ingestion` from Langfuse Cloud on 2026-11-16; `RAGASLangfuseObserver` scores go to `/api/public/scores`
  ([migration](docs/reference/migration.md#the-langfuse-backend-sends-opentelemetry-spans-2883)) [#2883](https://github.com/llm4s/llm4s/issues/2883).
- **Fixed** Langfuse traces were created public - readable, messages included, by anyone holding a trace's URL - and all as user
  `llm4s-user`: they are private unless `llm4s.tracing.langfuse.public = true`, and `userId` is the run's `RunConfig.principal`
  (new `TraceEvent.AgentRunEnded.userId`), or not sent [#2828](https://github.com/llm4s/llm4s/issues/2828).

### Documentation

- **Added** The compatibility policy names the Stable sealed types and enums that may gain a case in a minor release (`LLMError`, `ContentPart`, `TraceEvent`, `AgentStatus`, `StreamEvent` and the others listed there), so a match on one needs a default case; removing or renaming a case stays a break ([compatibility policy](docs/reference/compatibility-policy.md#sealed-types-that-may-gain-a-case)) [#2908](https://github.com/llm4s/llm4s/issues/2908).
- **Added** the [support-assistant tutorial](docs/tutorials/support-assistant/index.md)'s parts 6 (testing: `llm4s-testkit` fakes, a judged evaluation) and 7 (an HTTP endpoint, tracing, Prometheus metrics, deployment), with exercises that `Part6Spec` and `Part7Spec` check offline ([#2879](https://github.com/llm4s/llm4s/issues/2879)).
- **Changed** Examples use current model ids (`gpt-5.6-luna`/`gpt-5.6-terra`, `claude-sonnet-5-5`, `gemini-3.8-flash`, Bedrock `us.anthropic.claude-sonnet-5-5`); the watsonx pages say it ships unverified, and the observability guide that Langfuse Cloud removed the ingestion API the `langfuse` mode uses
  [#2832](https://github.com/llm4s/llm4s/issues/2832), [#2887](https://github.com/llm4s/llm4s/issues/2887).
- **Added** The contributing guide states the naming conventions: what `apply`, `of`, `create`, `from` and `forX`
  mean for a factory, `enum` or `sealed trait` for a sum type, plain accessor names, `PascalCase` constants and
  opaque-type newtypes ([#2822](https://github.com/llm4s/llm4s/issues/2822)).
- **Changed** the docs site groups the User Guide sidebar, and the contributor pages moved from `/reference/` to `/contribute/` (old addresses redirect); the watsonx, Perplexity and Bedrock setup sections, typed tools and the media and store-contract pages are filled in, and `LLMClient`, `AgentStatus`, `ModelRegistryService` and `MCPClient` say how to obtain and use them [#2834](https://github.com/llm4s/llm4s/issues/2834).
- **Changed** the support table (`docs/_data/support.yml`) has one entry per release line, with `security_until` and `ended_on` dates that the Support Policy page shows; `scripts/check-support-table.sh` checks the fields each status needs, and the release guide says what to change at each release ([Update the support table](docs/reference/release.md#update-the-support-table)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Changed** the Compatibility and Deprecation Policy no longer says provider modules aim to version independently: every module shares the release version, which `llm4s-bom` pins [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Changed** the policy pages state one release plan: 0.7.0 sets the MiMa baseline and 1.0 is the next release, with a break in between recorded as a MiMa filter and a migration note; the artifacts are documented as classpath libraries (no JPMS or OSGi metadata), and pre-1.0 release cadence, the 1.0 Scope `util` and `core` rows and the pre-tag checklist are corrected [#1281](https://github.com/llm4s/llm4s/issues/1281).
- **Fixed** 23 more guide pages have their Scala blocks compiled (agents, streaming, durable checkpointers, memory, ZIO, cats-effect, RAG, speech, tracing and others), which corrected code that did not compile against the API; guide specs fail when their copy of a page drifts ([#2833](https://github.com/llm4s/llm4s/issues/2833)).

### Build & tooling

- **Fixed** the config-policy `prod` and `staging-balanced` presets allowed only retired models and capped windows below current ones: they allow each vendor's current model generations (`ConfigPolicy.ModelFamilies`), and each cap is the largest current window in the bundled model metadata
  ([migration](docs/reference/migration.md#the-config-policy-prod-preset-allows-model-generations-2885)) [#2885](https://github.com/llm4s/llm4s/issues/2885).
- **Changed** the Beta parts inside frozen modules, `org.llm4s.assistant` and the Mistral, Cohere and xAI dialects, are left out of the 0.7.0 MiMa baseline: `stabilityTierCheck` keeps each of their types `@Experimental` (`stabilityExperimentalFiles`), which MiMa skips with its nested types ([The Baseline](docs/reference/api-stability.md#the-baseline)) [#2835](https://github.com/llm4s/llm4s/issues/2835).
- **Added** `scripts/check-docs.sh` runs every documentation check of Quick Checks that needs no sbt; every docs page needs a `description`, and a page that links an llm4s issue must declare how it cites it ([#2833](https://github.com/llm4s/llm4s/issues/2833)).
- **Added** Scalafix rules `SamplingOptionsOptional`, `CompletionFromMessage`, `MessageAndConversationShapes` and `ClientEntryPoints`, part of `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6` ([#2807](https://github.com/llm4s/llm4s/issues/2807), [#2809](https://github.com/llm4s/llm4s/issues/2809), [#2810](https://github.com/llm4s/llm4s/issues/2810)). `PositionalPatternsGainFields` no longer covers `Completion`, whose extractor also lost two fields.
- **Changed** The scalafix rule `ResultSyntaxMoved` also rewrites the imports of `TokenizerId`, `EmbeddingError`
  and `TraceId` to where they moved ([#2818](https://github.com/llm4s/llm4s/issues/2818),
  [#2808](https://github.com/llm4s/llm4s/issues/2808), [#2822](https://github.com/llm4s/llm4s/issues/2822)).
- **Fixed** `scripts/mima-dry-run.sh`'s negative control resolves its probe baseline again: only `llm4s-core` takes the probe version, so its POM names `llm4s-media` at a version that was published;
  a control that fails before MiMa compares anything is reported as that, and `scripts/test-mima-dry-run.sh` models dependency resolution [#2794](https://github.com/llm4s/llm4s/issues/2794).
- **Changed** a growth-prone type that gains a field adds no forwarding `apply` overload before the MiMa baseline (0.7.0), so the baseline freezes one `apply` per type; eight agent types gain their missing `with*` setters, `CompressionRule.apply` is renamed `transform`, and `OllamaConfig.apply` gains defaults and `timeouts`
  [#1281](https://github.com/llm4s/llm4s/issues/1281).
- **Changed** `llm4s-agent` takes the MiMa baseline with the other frozen modules, not a later one of its own: RFC 0001 is
  rejected now that the graph runtime is complete ([RFC 0001](docs/rfcs/0001-agent-baseline.md)) [#1281](https://github.com/llm4s/llm4s/issues/1281).
- **Added** SLSA build provenance for every release's jars and POMs, the SBOMs on its GitHub Release and the `workspace-runner` image, `verify-release.sh --attestations`,
  and a guide with the PGP key fingerprint [#2305](https://github.com/llm4s/llm4s/issues/2305); [Verifying Releases](docs/reference/verifying-releases.md).
- **Changed** each published module's `-javadoc.jar` is a sub-1 KB pointer to [llm4s.org/scaladoc](https://llm4s.org/scaladoc/), not its own Scaladoc, and
  its CycloneDX SBOM and the signatures' checksums are no longer uploaded to Maven Central (SBOMs: GitHub Release); IDEs read the sources jar ([release guide](docs/reference/release.md#what-is-published-to-maven-central)).
- **Changed** CI costs fewer runner minutes: each module's tests run once, drafts run nothing, a pull request skips the test jobs its change cannot affect, and a push to main leaves the Windows, performance, ScalaDoc and consumer jobs to a nightly run. `sbt testPerformance` runs only core and agent
  ([testing guide](docs/reference/testing-guide.md)).
- **Added** `llm4s-kotlin-api` publishes from its own workflow (`publish-kotlin.yml`), which a release calls and which can be run by hand for a tag whose Kotlin publish failed: it builds the tag's code with the current upload rules ([release guide](docs/reference/release.md#re-triggering-a-failed-release)).
- **Added** One umbrella Scalafix rule per starting version, `Llm4sMigrateFrom_0_5`, `Llm4sMigrateFrom_0_6` and `Llm4sMigrateFrom_0_7`, each running every rule from that version once; the first two cover the breaks since 0.6.0 (`ThinkingReplacesBudgetTokens`, `AgentStatusGainsTruncated`, `FinishReasonTyped`, the positional patterns that gain fields), tested against 0.6.0
  ([Scalafix](docs/reference/scalafix.md#migration-rules)) [#2835](https://github.com/llm4s/llm4s/issues/2835), [#2330](https://github.com/llm4s/llm4s/issues/2330).
- **Fixed** `llm4s-reactor` was never published (it was missing from the root aggregate, so 0.6.0 lacks it); `publishedArtifactsCheck` now fails on a published module outside the aggregate.
- **Added** `llm4s-testkit` has extractors for `ScriptedLLMClient.respondingTo` rules - `EndsWithUser`, `LastUser`, `AfterToolResult`, `AfterToolResults`, `AfterCorrection()` -
  and `conversation.lastUserText` [#2730](https://github.com/llm4s/llm4s/issues/2730); [Testing with the Testkit](docs/guide/testing-with-the-testkit.md#choose-the-reply-from-the-conversation).
- **Added** Scalafix rule `ProviderConfigNewtypes`, part of `Llm4sMigrateFrom_0_5` and `Llm4sMigrateFrom_0_6`: a provider config's named `model` argument and `withModel(...)` to `ModelName(...)`, `Llm4sConfig.provider/listModels("x")` to `ProviderName("x")`; `CopyToWithSetters` and `CopyToWithSetters_1_0` wrap a copied `model` the same way [#2805](https://github.com/llm4s/llm4s/issues/2805), [#2804](https://github.com/llm4s/llm4s/issues/2804).

## [0.6.0] - 2026-11-21

### Highlights

0.6.0 makes the agent runtime ready to deploy and widens who can use llm4s. Agents gain typed subagents (child
threads, token budgets, supervision, nested streams) and production adapters: a PostgreSQL checkpointer shared across
machines, a durable run queue with workers, and one OpenTelemetry trace per run. Credentials are `Secret`s and can come
from a file or an `ApiKeyProvider`; console and OpenTelemetry tracing withhold message content unless you opt in. New
providers and features: xAI, Perplexity, keyless Entra ID for Azure OpenAI, web search for Anthropic and Gemini, one
proxy and CA setting for every client, and your own HTTP client. The Java facade gains agents, streaming, annotated
tools and RAG (`llm4s-java-rag`), and `llm4s-kotlin-api` is published for the first time. New experimental modules
include `llm4s-ox`, `llm4s-reactor`, `llm4s-capabilities`, `llm4s-prompt`, `llm4s-schema-iron`, `llm4s-tapir`,
`llm4s-repl`, `llm4s-sttp`, `llm4s-spring-ai`, `llm4s-jlama` (an in-process local model) and `llm4s-secrets-vault`, along
with the Elasticsearch/OpenSearch and Redis vector stores and `llm4s-doctor`. A `Completion` reports the options a provider
could not honour (`adjustments`), and thinking tokens are counted once in usage and cost. Many data types are now
growth-safe (private constructors and `with*` setters), which breaks `copy` and positional patterns: read the
[migration guide](docs/reference/migration.md) before upgrading. 0.6.0 sets no MiMa baseline.

### Breaking changes

- `TokenUsage.completionTokens` no longer includes reasoning tokens for OpenAI, OpenAI-compatible and Perplexity replies,
  and output token counts (`UsageSummary.outputTokens`, token budgets, metrics, OpenTelemetry, Langfuse, Spring AI) include
  thinking for every provider; [migration](docs/reference/migration.md#completiontokens-no-longer-includes-thinking-tokens-2564).
- A Gemini or Vertex AI reply a content filter stopped, or a blocked prompt, is a `Left(ContentFilteredError)`, not a
  partial answer or `UnknownError`; [migration](docs/reference/migration.md#a-gemini-reply-a-content-filter-stopped-is-an-error-2563).
- A `Llm4sHttpClient` passed to an SDK-free provider client is no longer closed with it, and `LlmClientOptions` and
  `ProviderFeatures` gain a field; [migration](docs/reference/migration.md#a-supplied-http-client-is-the-callers-to-close-2341).
- `RerankProviderConfig` (`llm4s-rag`) has a private constructor and `copy`, and gains `apiKeyProvider`; use its `with*` setters;
  [migration](docs/reference/migration.md#rerankproviderconfig-is-growth-safe-2478).
- `ApiKey` and every config credential field (`apiKey`, Langfuse keys, search-tool keys) are `org.llm4s.security.Secret`, not `String`; [migration](docs/reference/migration.md#credentials-are-secret-1932).
- `FileConfig()` blocks more by default: each platform's system and credential locations (`FileConfig.DefaultBlockedPaths`),
  matched regardless of case; [migration](docs/reference/migration.md#the-file-tools-default-blockedpaths-covers-each-platform-1408).
- `HTTPS_PROXY` and `NO_PROXY` are now honoured by every provider client (`llm4s.http.proxy`), `http` is a built-in
  section key, and `BedrockConfig`, `WatsonXConfig`, `EmbeddingProviderConfig` and `OpenAICompatibleClient.Settings`
  gain a `network` field; [migration](docs/reference/migration.md#one-proxy-and-tls-setting-for-every-provider-client-2273).
- `LLM4STemplate.completeAsync` refused by its executor fails with an `LlmException` of kind `OTHER` wrapping the
  `RejectedExecutionException`; [migration](docs/reference/migration.md#the-spring-boot-starters-refused-completeasync-fails-with-an-llmexception-2276).
- The Postgres stores take a `javax.sql.DataSource`, not a `com.zaxxer.hikari.HikariDataSource`: `PgVectorStore.create`
  and `PgVectorStore.apply(dataSource, tableName)`, and `PgKeywordIndex.apply(dataSource, ...)`. Source-compatible for
  callers passing a Hikari pool; recompile against the new signatures. `PgSearchIndex.close()` no longer closes a pool it
  did not create; [migration](docs/reference/migration.md#postgres-stores-take-a-javaxsqldatasource-2300).
- `OpenAIConfig.fromValues` takes `baseUrl` before `organization` (now defaulted to `None`), and `AzureConfig.fromValues` and
  `fromTokenProvider` take the key (or token provider) before `endpoint`; [migration](docs/reference/migration.md#provider-fromvalues-take-model-key-then-base-url-2340).
- The default console tracer prints metadata only: queries, completion content, tool input/output, custom event data
  and error messages are withheld unless `llm4s.tracing.console.captureContent = true`
  (`LLM4S_TRACING_CONSOLE_CAPTURE_CONTENT`) or `new ConsoleTracing(captureContent = true)`; see the new
  [data protection guide](docs/guide/data-protection.md);
  [migration](docs/reference/migration.md#console-tracing-prints-no-content-by-default-2311).
- OpenTelemetry spans of model calls follow the GenAI conventions: `LLM Completion` is `chat <model>` and
  `Embedding Usage - <op>` is `embeddings <model>`, and no span carries message content unless
  `llm4s.tracing.opentelemetry.captureContent = true`; positional patterns on four `TraceEvent`s break;
  [migration](docs/reference/migration.md#opentelemetry-spans-follow-the-genai-conventions-content-is-opt-in-2275).
- OpenTelemetry spans of events that call no remote service (`ToolExecuted`, `CostRecorded`, `CacheHit`/`CacheMiss`,
  `RAGOperationCompleted`, an unplaced `CustomEvent`, an `ErrorOccurred` with no operation) are `INTERNAL`, not `CLIENT`:
  dashboards filtering on span kind change; [migration](docs/reference/migration.md#only-remote-calls-are-client-spans-2531).
- `org.llm4s.types.typeclass` (`Encoder`, `EncoderOps`, `LLMCapable`) and `CompletionChunk`/`ChunkDelta` are removed
  from `llm4s-core`: nothing used them, and streaming uses `StreamedChunk`;
  [migration](docs/reference/migration.md#unused-speculative-core-types-removed-2345).
- `OpenAICompatibleDialect.addReasoning` returns the `List[RequestAdjustment]` it could not send, not `Unit`; the
  standard dialect reports a set `reasoning` as `Dropped` (RFC 0008, PR 1); recompile a dialect that overrides it;
  [migration](docs/reference/migration.md#openaicompatibledialectaddreasoning-returns-what-it-did-not-send-2115).
- Tier hygiene before the baseline: `org.llm4s.trace.AnsiColors` is `private[llm4s]`; `org.llm4s.core.safety.UsingOps`
  moved to `llm4s-agent-tools` and `org.llm4s.llmconnect.utils.ModelSelector` to `llm4s-rag`, their only consumers,
  with packages unchanged; the `org.llm4s.assistant` types are `@Experimental`, matching their Beta tier;
  [migration](docs/reference/migration.md#tier-hygiene-before-the-baseline-2358).
- `Completion.created` is a `java.time.Instant`, not epoch seconds as a `Long`; `withCreated` and
  `StreamingAccumulator.toCompletion(created)` take one, and the wire's epoch seconds are converted in each provider
  client; [migration](docs/reference/migration.md#completioncreated-is-an-instant-2262).
- `WorkspaceTools` is removed (use `FileTools` over `WorkspaceFileBackend` and `ExecuteTool`); `WorkspaceAgentInterface` gains `fileSnapshot`, `deleteFile` and compare-and-set writes; the runner enforces
  `allowedPaths`/`readOnlyPaths`; [migration](docs/reference/migration.md#workspace-backends-1906).
- Checkpoint format 8 and SQLite schema 9: an older build refuses what this one writes; [migration](docs/reference/migration.md#child-threads-2045).
- `Checkpointer.sweep` is a new abstract method every store implements, and a tombstone is no longer kept for good;
  [migration](docs/reference/migration.md#store-wide-retention-and-tombstone-reclamation-2059).
- `Checkpointer.threads` and `Checkpointer.incompleteThreads` are new abstract methods every store implements, and
  `Commit` carries `metadata`; [migration](docs/reference/migration.md#the-thread-registry-2132).
- `Checkpointer.latestOfRuns` is a new abstract method every store implements, with SQLite schema 10 and PostgreSQL schema 4;
  [migration](docs/reference/migration.md#the-run-queue-2134).
- `RetentionPolicy.maxAge` is judged by when the store committed each checkpoint and event, not the producer's timestamps,
  and `GraphError.UnsupportedStoreSchema` is a new case; [migration](docs/reference/migration.md#the-postgresql-checkpointer-2130).
- `Checkpointer.children` is a new abstract method every store implements, `Checkpointer.deleteThread` takes a
  `fence` on the claim above, checked atomically with the delete, and a store checks `Commit.fence` atomically with
  the commit; [migration](docs/reference/migration.md#child-threads-2045).
- `deleteThread` and `Agent.forget` cascade to child threads; [migration](docs/reference/migration.md#child-threads-2045).
- Child threads refuse direct `start`, `recover`, `resume`, `fork`, `updateState` and `deleteThread` (`GraphError.ChildThread`); [migration](docs/reference/migration.md#child-threads-2045).
- `DeepSeekConfig`, `ZaiConfig`, `XaiConfig`, `MistralConfig` and `CohereConfig` are growth-prone types: build them with
  `X(...)` or `X.fromValues` and change them with `with*`; `new` and `.copy` are private, and the classes are `final`;
  [migration](docs/reference/migration.md#the-openai-compatible-provider-configs-are-growth-safe-2264).
- `ToolRetryPolicy` is a growth-prone type: build it with `ToolRetryPolicy(...)` and change it with `with*`; `new` and
  `.copy` are private; [migration](docs/reference/migration.md#toolretrypolicy-is-growth-safe-1708).
- `MemoryManagerConfig` is a growth-prone type: build it with `MemoryManagerConfig(...)` and change it with `with*`;
  `new` and `.copy` are private; [migration](docs/reference/migration.md#memorymanagerconfig-is-growth-safe-1847).
- `GeminiConfig`, `VertexAIConfig` (`llm4s-gemini`) and `AzureConfig` (`llm4s-openai`) are growth-prone types: build
  them with `X(...)` and change them with `with*`; `new` and `.copy` are private; [migration](docs/reference/migration.md#geminiconfig-vertexaiconfig-and-azureconfig-are-growth-safe-2263).
- `ToolExecutionConfig`, `LlmClientOptions`, `EmbeddingProviderConfig`, `EmbeddingModelConfig`, `LocalEmbeddingModels`,
  `ModelRegistryConfig` and `TracingSettings` are growth-prone types: build them with `X(...)` and change them with
  `with*`; `new` and `.copy` are private, and the classes are `final`;
  [migration](docs/reference/migration.md#core-config-types-are-growth-safe-2346).
- `HttpResponse`, `HttpRawResponse`, `StreamingHttpResponse`, `JsonHttpResponse`, `EmbeddingResponse`, `EmbeddingUsage`,
  `ModelPricing`, `UsageSummary`, `ModelUsage`, `ProviderExchange` and `DiscoveredModel` are growth-prone types: build
  them with `X(...)` and change them with `with*`; `new` and `.copy` are private, and the classes are `final`. JSON is
  unchanged. `UsageSummary.bigDecimalRw` is private;
  [migration](docs/reference/migration.md#core-data-types-are-growth-safe-2356).
- `Conversation`, `SystemMessage`, `ToolCall`, `ResponseFormat.JsonSchema`, `EmbeddingVector`,
  `MultimediaEmbeddingRequest`, `ImageInput`, `AudioInput`, `VideoInput` and `RawMediaInput` are growth-prone types:
  build them with `X(...)` and change them with `with*`; `new` and `.copy` are private, and the classes are `final`.
  JSON is unchanged; [migration](docs/reference/migration.md#llmconnectmodel-types-are-growth-safe-2378).
- The `TraceEvent` cases (`AgentInitialized`, `CompletionReceived`, `ToolExecuted`, `ErrorOccurred`, `TokenUsageRecorded`,
  `AgentRunEnded`, `CustomEvent`, `EmbeddingUsageRecorded`, `CostRecorded`, `RAGOperationCompleted`,
  `ImageGenerationCompleted`, `CacheHit`, `CacheMiss`) are growth-prone types: build them with `TraceEvent.X(...)` and
  change them with `with*`; `new` and `.copy` are private, and the classes are `final`. Pattern matching and `toJson`
  are unchanged; [migration](docs/reference/migration.md#traceevent-cases-are-growth-safe-2377).
- The `AssistantError` cases (`IOError`, `EOFError`, `DisplayError`, `SessionError`, `FileError`,
  `SerializationError`, `CommandParseError`) are growth-prone types: build them with `AssistantError.X(...)` or the
  smart constructors and change them with `with*`; `new` and `.copy` are private. Pattern matching, `context` and
  `formatted` are unchanged; [migration](docs/reference/migration.md#assistanterror-cases-are-growth-safe-2383).
- `JsonlProviderExchangeSink`, `CacheEntry`, `CacheStats`, `OpenAICompatibleClient.Settings`, `SSEParser.SSEEvent`
  and the provider SPI's `EmbeddingProviderSection`, `ProviderConfigKey`, `ProviderModuleReport`,
  `ProviderDiscoveryFailure`, `ProviderIdCollision` and `ProviderRegistryReport` are growth-prone types: build them with
  `X(...)` and change them with `with*`; `new` and `.copy` are private;
  [migration](docs/reference/migration.md#provider-spi-caching-streaming-and-exchange-sink-types-are-growth-safe-2379).
- The tool schemas (`StringSchema`, `NumberSchema`, `IntegerSchema`, `BooleanSchema`, `ArraySchema`, `ObjectSchema`),
  `PropertyDefinition`, `SafeParameterExtractor`, `ToolCallRequest` and `ToolFunction` are growth-prone types: build
  them with `X(...)` (or `Schema.*`) and change them with `with*`; `new` and `.copy` are private, and the classes are
  `final`. `SafeParameterExtractor`'s Java constructor is now `SafeParameterExtractor.apply(params)`;
  [migration](docs/reference/migration.md#tool-schemas-and-tool-call-types-are-growth-safe-2381).
- `ToolCallError.UnknownFunction` and `ToolParameterError.MissingParameter` are growth-prone types: build them with
  `X(...)` and change them with `with*`; `new` and `.copy` are private, and the classes are `final`. Pattern matching and
  `ToolCallErrorJson` are unchanged. The other `ToolCallError` and `ToolParameterError` cases stay closed records;
  [migration](docs/reference/migration.md#toolcallerror-and-toolparametererror-cases-2382).
- The `org.llm4s.context` result types (`CompressionRule`, `ContextStep`, `PipelineStep`, `ManagedConversation`,
  `ConversationWindow`, `TokenUsageInfo`, `StructuredInfo`, `HistoryDigest`, `TokenBreakdown`, `MessageTokenInfo`,
  `SemanticBlock`) are growth-prone types: build them with `X(...)` and change them with `with*`; `new` and `.copy` are
  private, and the classes are `final`;
  [migration](docs/reference/migration.md#context-types-are-growth-safe-2380).
- `Llm4sConfig.TextEmbeddingModelSettings`, `ProvidersConfigModel.ProvidersConfig`, `TransformationResult` and
  `SecretPatterns.SecretMatch` are growth-prone types: build them with `X(...)` and change them with `with*`; `new` and
  `.copy` are private, and `TransformationResult` is `final`;
  [migration](docs/reference/migration.md#config-model-and-util-types-are-growth-safe-2384).
- `OpenAIToolHelper` (`llm4s-openai`) is `private[llm4s]`: its signatures named openai-java types; use
  `ToolRegistry.getOpenAITools()`; [migration](docs/reference/migration.md#openaitoolhelper-is-internal-2360).
- `RunEvent.RunFailed`, `RunCancelled` and `RunTimedOut` gain `steps` (a failed attempt's agent model steps): positional
  patterns need another binder; [migration](docs/reference/migration.md#explicit-inheritance-2049).
- `AgentStatus.TokenLimitReached` (Java `AgentStatusKind.TOKEN_LIMIT_REACHED`) and `RunBudgets.maxTokens`: exhaustive
  matches and `RunBudgets` patterns break; [migration](docs/reference/migration.md#token-budgets-and-the-runs-deadline-2047).
- `AgentStatus.Suspended` gains a fifth field, `delegated`: positional patterns need another binder; [migration](docs/reference/migration.md#child-suspension-through-the-parent-2050).
- `StreamEvent.Nested`, two fields on `TraceEvent.AgentRunEnded`, and OpenTelemetry span names `invoke_agent <agent>` /
  `execute_tool <tool>`: exhaustive matches, positional patterns and dashboards break; [migration](docs/reference/migration.md#nested-streams-and-tracing-2051).
- `HarnessProfile.deepAgents` offers a `task` tool (a tool named `task` clashes; permission rules need an `Invoke` rule for it),
  and `withDelegation` beside a harness is refused; [migration](docs/reference/migration.md#subagents-in-the-harness-profile-2052).
- `LLMClientPekko.streamComplete` materializes `Future[Done]` instead of `NotUsed`: code that names the type, or keeps
  the materialized value, changes; [migration](docs/reference/migration.md#llmclientpekkostreamcomplete-materializes-futuredone-1848).
- `TraceEvent.CustomEvent`, `TokenUsageRecorded`, `ErrorOccurred` and `AgentRunEnded` gain a `span` field, and an agent
  run's OpenTelemetry spans and Langfuse trace ids change: positional patterns and dashboards break; [migration](docs/reference/migration.md#one-trace-per-run-2137).
- `Llm4sConfig.embeddingsChunking()` / `embeddingsInputs()` / `embeddingsUi()` (and their `load*` twins and settings
  types), `experimentalStubsEnabled`, `localEmbeddingModels()`, the duplicate `loadTextEmbeddingModel()`, five sample-only
  `ConfigKeys` and their `llm4s.embeddings.*` `reference.conf` keys are removed from `llm4s-core`; the embeddings sample
  reads them itself; [migration](docs/reference/migration.md#the-embeddings-samples-settings-left-llm4sconfig-2265).
- `ServiceError`'s extractor binds five fields (`retryAfter` added) and `ServiceError.ServiceErrorOps` is gone
  (`isRecoverableStatus` is a method of `ServiceError`): four-field patterns and the import break; [migration](docs/reference/migration.md#serviceerror-extractor-and-isrecoverablestatus-2266).
- `llm4s-java-api` and `llm4s-spring-boot-starter` no longer bring the provider modules: declare `llm4s-openai`,
  `llm4s-ollama`, ... yourself; [migration](docs/reference/migration.md#llm4s-java-api-and-the-spring-boot-starter-no-longer-bring-the-provider-modules-1496).

### Capabilities

- **Added** `llm4s-capabilities` (Experimental): `client.requireCapabilities[Vision & Tools](provider, model)` checks the model's metadata once and
  returns a `Capable[Vision & Tools]`, so passing a client not checked for vision to `describe` fails to compile; `model"openai/gpt-4o"` is typed from the bundled model data at compile time, and `completeStructuredOf` is gated on `StructuredOutput` ([guide](docs/guide/capabilities.md)) [#2331](https://github.com/llm4s/llm4s/issues/2331).

### Observability

- **Added** OpenTelemetry GenAI semantic conventions (`1.37.0`) for plain and streamed completions and embeddings:
  `CLIENT` spans `chat <model>` / `embeddings <model>` with `gen_ai.provider.name`, request and response attributes and
  `error.type`; `TracingLLMClient` traces a client's calls; content capture is opt-in and redacted; agent spans gain
  `gen_ai.provider.name` ([#2275](https://github.com/llm4s/llm4s/issues/2275)).
- **Changed** OpenTelemetry's span kind defaults to `INTERNAL`: only a model, embedding or image call, or its failure, is a
  `CLIENT` span ([#2531](https://github.com/llm4s/llm4s/issues/2531)).

### Agent subagents (Phase 4)

- **Added** child threads: `RunOrigin` on checkpoints (format 8), `ThreadId.child`, `Checkpointer.children` /
  `Agent.children`, cascade delete, child retention and a durable `Checkpoint.outcome` [#2045](https://github.com/llm4s/llm4s/issues/2045); [design §4.41](docs/design/typed-agent-runtime/4.41-stage-4-slice-k1-child-threads.md).
- **Added** a per-run token budget, `RunBudgets.maxTokens`, ending the turn `AgentStatus.TokenLimitReached`, and the run's
  deadline as `RunContext.deadline` [#2047](https://github.com/llm4s/llm4s/issues/2047); [design §4.43](docs/design/typed-agent-runtime/4.43-stage-4-slice-k3-token-budgets.md).
- **Added** child-run supervision: a child started at most once and reattached on replay, cancelled with its parent,
  held to its deadline (`RunContext.deadline`), usage rolled up once; a tool's own model calls now count in the thread's usage [#2046](https://github.com/llm4s/llm4s/issues/2046); [design §4.42](docs/design/typed-agent-runtime/4.42-stage-4-slice-k2-child-run-supervision.md).
- **Added** `TypedAgent[I, O]` subagents, offered with `AgentBuilder.withSubagents` through the `task` / `task_<id>` tools,
  run in parallel on child threads within `DelegationConfig`'s bounds, or alone with `BoundAgent.run` (on a caller-chosen thread too, replay-stable for graph nodes) / `resume` / `recover` [#2048](https://github.com/llm4s/llm4s/issues/2048), [#2069](https://github.com/llm4s/llm4s/issues/2069), [#2077](https://github.com/llm4s/llm4s/issues/2077); [design §4.44](docs/design/typed-agent-runtime/4.44-stage-4-slice-d1-typed-agent-and-task.md).
- **Added** explicit inheritance: `TypedAgent.withInheritance` grants parent tools, middleware, a whole-life `TaskBudget` capped by the
  parent's remainder and delegation; ancestors' permissions only narrow; `Agent.delegation` shows the `DelegationPlan` [#2049](https://github.com/llm4s/llm4s/issues/2049); [design §4.45](docs/design/typed-agent-runtime/4.45-stage-4-slice-d2-inheritance.md).
- **Added** child suspension through the parent: a child's approvals and questions suspend the parent, listed in
  `AgentStatus.Suspended.delegated` under composite ids that the answer helpers and `Agent.resume` take [#2050](https://github.com/llm4s/llm4s/issues/2050); [design §4.46](docs/design/typed-agent-runtime/4.46-stage-4-slice-d3-child-suspension.md).
- **Added** nested streams (`nested = true`, `StreamEvent.Nested`, `EventHandlers.onChild`, `nestedEvents`), `Agent.subscribeChild`,
  `SubagentEvents.TaskStarted` / `TaskEnded` (the child-task metric) and parent ids in tracing [#2051](https://github.com/llm4s/llm4s/issues/2051); [design §4.47](docs/design/typed-agent-runtime/4.47-stage-4-slice-d4-nested-streams-and-tracing.md).
- **Added** subagents in `HarnessProfile` (`withSubagents(SubagentConfig)`): `deepAgents` offers the general-purpose subagent
  through `task`, plus specialists and `llm4s.agent.harness.subagents` config [#2052](https://github.com/llm4s/llm4s/issues/2052); [design §4.48](docs/design/typed-agent-runtime/4.48-stage-4-slice-p1-harness-subagents.md).
- **Added** the exit example, `ParallelDeepResearchExample`: typed specialists in parallel under bounds, a crash, a child's failure,
  approval and cancel, nested streams, with an acceptance suite on a scripted model [#2053](https://github.com/llm4s/llm4s/issues/2053); [design §4.49](docs/design/typed-agent-runtime/4.49-stage-4-slice-p2-exit-example.md).

### Production adapters (Stage 5)

- **Added** `llm4s-agent-checkpoint-postgres`: `PostgresCheckpointer`, a `Checkpointer` shared by runtimes on several machines,
  claims and retention by the database's clock [#2130](https://github.com/llm4s/llm4s/issues/2130); [design §4.50](docs/design/typed-agent-runtime/4.50-stage-5-slice-s1-postgresql-checkpointer.md).
- **Added** cross-machine change notification: `PostgresCheckpointer.awaitEventsAfter` wakes on any store's commit by `LISTEN`/`NOTIFY`,
  one listening connection per store, polling while it is down [#2131](https://github.com/llm4s/llm4s/issues/2131); [design §4.51](docs/design/typed-agent-runtime/4.51-stage-5-slice-s2-cross-machine-change-notification.md).
- **Added** the thread registry: thread metadata (`RunConfig.metadata` at start, `GraphRuntime.updateMetadata`), tenant-scoped
  `Checkpointer.threads` search and `incompleteThreads`, indexed in every store [#2132](https://github.com/llm4s/llm4s/issues/2132); [design §4.52](docs/design/typed-agent-runtime/4.52-stage-5-slice-s3-thread-registry.md).
- **Added** `Checkpointer.sweep`: store-wide retention in bounded pages, reclaiming old tombstones while their readers are still
  told the thread was deleted [#2059](https://github.com/llm4s/llm4s/issues/2059); [design §4.53](docs/design/typed-agent-runtime/4.53-stage-5-slice-s4-store-wide-retention-and-tombstone-reclamation.md).
- **Added** the checkpoint store chosen in `application.conf` (`llm4s.agent.checkpointer.kind`: `memory`, `sqlite`, `postgres`) through a `CheckpointerBackend` SPI, its `CheckpointStore` carrying the store's `runQueue`, and `ClaimPolicy`/`WatchPolicy` from `llm4s.agent.runtime` (`CheckpointerConfigLoader`, `RuntimeConfigLoader`) [#2133](https://github.com/llm4s/llm4s/issues/2133); [design §4.54](docs/design/typed-agent-runtime/4.54-stage-5-slice-s5-store-configuration.md).
- **Added** the durable run queue: `RunQueue` and `RunSubmitter` (`org.llm4s.agent.service`) in the in-memory, SQLite and PostgreSQL
  stores, entries carrying the submitter's `TraceContext`, `RunQueueContract`, and `llm4s.agent.queue` settings [#2134](https://github.com/llm4s/llm4s/issues/2134); [design §4.55](docs/design/typed-agent-runtime/4.55-stage-5-slice-w1-run-submission-and-the-durable-run-queue.md).
- **Added** `AgentWorker` (`org.llm4s.agent.service`): takes queued runs, recovers a dead worker's entries once their leases
  expire, settles exhausted ones, runs the retention sweep, drains on shutdown and answers health; `llm4s.agent.worker`
  settings (`WorkerConfigLoader`) [#2135](https://github.com/llm4s/llm4s/issues/2135); [design §4.56](docs/design/typed-agent-runtime/4.56-stage-5-slice-w2-workers.md).
- **Added** cancel from any machine (`RunSubmitter.cancel`, `RunSubmitter.deleteThread`) and the busy-thread policy per
  submission: `Reject` (also a direct run's thread), `Enqueue`, `Interrupt` at the next superstep boundary; a stopped queued run settles its thread [#2136](https://github.com/llm4s/llm4s/issues/2136); [design §4.57](docs/design/typed-agent-runtime/4.57-stage-5-slice-w3-cancel-from-any-machine-and-the-busy-thread-policy.md).
- **Added** one trace per agent run: an OpenTelemetry span tree (`invoke_agent` over `chat` and `execute_tool`, a child
  run under its `task` call) and Langfuse nesting, continued across machines through `TraceContext` on
  `RunConfig.traceContext` and `RunOrigin.trace` ([#2137](https://github.com/llm4s/llm4s/issues/2137), [design 4.58](docs/design/typed-agent-runtime/4.58-stage-5-slice-o1-one-trace-per-run-across-machines-and-child-runs.md)).
- **Added** run, claim, commit, child-task and run-queue metrics (`org.llm4s.agent.metrics`: `MeteredCheckpointer`, `AgentWorker(metrics = ...)`,
  `RunQueue.depth`), exported by Prometheus and Micrometer through core's new generic `Metric` and `MetricsCollector.increment`/`observe`/`set` ([#2138](https://github.com/llm4s/llm4s/issues/2138), [design 4.59](docs/design/typed-agent-runtime/4.59-stage-5-slice-o2-run-claim-queue-and-child-task-metrics.md)).
- **Added** an example agent service in `modules/deploy-service` (unpublished): submit, follow over SSE, cancel and list runs
  on the store's run queue with an in-process `AgentWorker`, plus a three-replica Postgres overlay in `deploy/` ([#2139](https://github.com/llm4s/llm4s/issues/2139), [design 4.60](docs/design/typed-agent-runtime/4.60-stage-5-slice-p1-example-service-and-a-three-instance-deployment.md)).
- **Added** the Stage 5 exit test (`MultiMachineExitSpec`, three service processes on one Postgres), the [several machines guide](docs/guide/agents/multiple-machines.md)
  and the [platform parity matrix](docs/reference/agent-platform-parity.md) ([#2140](https://github.com/llm4s/llm4s/issues/2140), [design 4.61](docs/design/typed-agent-runtime/4.61-stage-5-slice-p2-exit-test-guide-and-parity-matrix.md)).

### Agent harness (Phase 3)

- **Added** workspace backends: `WorkspaceFileBackend` (`Verifiable`, compare-and-set in the runner), `WorkspaceSandboxBackend`
  and the `execute` tool; `WorkspaceTools` **removed**, `CodeWorker` on the new tools [#1906](https://github.com/llm4s/llm4s/issues/1906); [design 4.33](docs/design/typed-agent-runtime/4.33-stage-3-slice-f2-workspace-backends.md).
- **Added** `HostFileBackend`, a `FileBackend` over a host directory with one writer per root: `open` locks the root
  against any overlapping open, in any process [#1964](https://github.com/llm4s/llm4s/issues/1964); [design §4.40](docs/design/typed-agent-runtime/4.40-host-file-backend.md).
- **Changed** on Linux workspace commands run under Landlock and seccomp: `readOnlyPaths`/`allowedPaths` bind them, and
  **commands have no network unless `networkAllowed = true`** [#1983](https://github.com/llm4s/llm4s/issues/1983); from
  Landlock ABI v6 they cannot signal the runner nor reach abstract Unix sockets outside them [#2009](https://github.com/llm4s/llm4s/issues/2009).
- **Fixed** the workspace runner's `git` accepts a workspace owned by another user (a Linux host's bind mount), naming the
  workspace root as `safe.directory` [#2014](https://github.com/llm4s/llm4s/issues/2014).
- **Added** the exit examples, `DeepResearchExample` and `CodingWorkspaceExample` with acceptance suites on a scripted model, and
  `StaticFileBackend` (read-only application files, e.g. skills) [#1916](https://github.com/llm4s/llm4s/issues/1916); [design §4.38](docs/design/typed-agent-runtime/4.38-stage-3-slice-p2-exit-examples.md).
- **Fixed** `HostFileBackend` on Windows refusing a write or edit as "permission denied" when its replace met a target
  another call had open: the replace is retried while the target is unchanged and writable, a target changed meanwhile
  is `EffectReplayRefused` (as on POSIX), and a real denial is still `BackendRefused` [#2127](https://github.com/llm4s/llm4s/issues/2127).

### Streaming integrations

- **Fixed** `LLMClientIO.streamComplete` letting the provider run far past its 64-chunk buffer after the consumer stopped: each pull of the queue is now limited to the buffer size.
- **Fixed** a `llm4s-pekko` stream leaking its producer thread when provider code swallows the interrupt: once the
  stream stops, every later chunk is refused at once; `ThreadBusy` refusals are tested in pekko, effect and zio [#1815](https://github.com/llm4s/llm4s/issues/1815).
- **Changed** `LLMClientPekko.streamComplete` to materialize a `Future[Done]` that completes once the provider call has
  returned, as `AgentPekko`'s streams already do, decided before the Pekko API is frozen [#1848](https://github.com/llm4s/llm4s/issues/1848).
- **Added** `StreamingBridgeContract`, one conformance suite that `llm4s-effect`, `llm4s-zio` and `llm4s-pekko` each run:
  errors, cancellation, early stop, mid-stream failure, agent event order and tool threads match core; the
  [pick-your-stack page](docs/guide/effect-systems.md) shows one program in plain `Result`, cats-effect, ZIO and Pekko, run by a spec [#2334](https://github.com/llm4s/llm4s/issues/2334).
- **Added** `llm4s-ox` (Experimental): a streamed completion as an Ox `Flow`, `parComplete`, `raceProviders` and `RetrySchedule`
  for direct-style code on virtual threads, and the [direct-style guide](docs/guide/direct-style.md) [#2332](https://github.com/llm4s/llm4s/issues/2332).

### Agent runtime

- **Fixed** the per-step cost of hex-encoding effect keys, trace ids and other digests (one `String.format` per byte), of `KeyDigest.canonical`'s sorted copy of the JSON, and of an Ox scope for a superstep with one task (it gets one virtual thread); every digest is unchanged [#2633](https://github.com/llm4s/llm4s/issues/2633), [#2642](https://github.com/llm4s/llm4s/issues/2642).
- **Fixed** `ContextWindowMiddleware` and `SummarizationMiddleware` count tokens with `cl100k_base` by default, not words x 1.3,
  which counted minified JSON as ~1 token; core's tokenizer reads special-token names as text [#2587](https://github.com/llm4s/llm4s/issues/2587).
- **Added** `InputGuardrail.from` and `OutputGuardrail.from`, which adapt a `CompositeGuardrail` (or any
  `Guardrail[String]`) for `GuardrailMiddleware`, passing the hook's context on; running a whole guardrail list is
  recorded as the default [#1521](https://github.com/llm4s/llm4s/issues/1521).
- **Added** the call's id and idempotency key for `ToolFunction` tools run by an agent: `SafeParameterExtractor.call`
  (a `ToolCallInfo`, `None` outside an agent) and `ToolFunction.execute(args, call)`; `HTTPTool` sends it as
  `Idempotency-Key` on writes, and MCP tools forward it in `tools/call` `_meta` (`llm4s.org/idempotencyKey`), which an
  llm4s `MCPServer` reads back [#1740](https://github.com/llm4s/llm4s/issues/1740).
- **Fixed** `SqliteCheckpointer.open` failing on a pre-release version-1 file whose latest checkpoint is not valid JSON: that
  thread is left out of the seeded history, with a warning, and seeding streams the rows [#1849](https://github.com/llm4s/llm4s/issues/1849).
- **Fixed** `RunContext.progress` from a node that keeps its context past its run reaching a later run's subscribers:
  once the run has released the thread it is a no-op, as it already was once the claim was lost [#1737](https://github.com/llm4s/llm4s/issues/1737).

### Configuration

- **Added** `Llm4sConfig.from(config)` (experimental): an `Llm4sSettings` with `Llm4sConfig`'s methods, reading a `Config` the app already
  has (Play, Pekko, per tenant or test) in place of `application.conf`, or set up in code: a `ModelRegistryService` in scope
  supplies `fromValues`' `ContextWindowResolver`; guide `docs/getting-started/configuration-in-code.md` with ciris and
  zio-config examples [#2340](https://github.com/llm4s/llm4s/issues/2340).
- **Fixed** `CheckPolicies` CLI now rejects unknown `--env` arguments with exit code 2 before loading any
  configuration, and maps aliases such as `dev-sandbox` and `prod-safe` to their canonical environment [#2260](https://github.com/llm4s/llm4s/issues/2260).
- **Fixed** `EnvCheckPolicies` exits with 2, not 0, when every policy in the preset is skipped for the chosen `--env`
  (for example `--env=production` with `prod-safe`, whose policies apply only to `prod`): nothing was checked, so a
  mistyped `--env` no longer passes a CI gate [#2261](https://github.com/llm4s/llm4s/issues/2261).
- **Added** `apiKeyFile` and `ApiKeyProvider`: every built-in chat provider that takes a key, the embedding providers and the
  Cohere reranker re-read a rotated key per request, without a restart. `apiKeyFile` is accepted in chat sections, embeddings blocks,
  `llm4s.rerank.cohere` and `llm4s.credentials.<id>`; testkit `assertHonoursApiKeyProvider` and `assertEmbeddingHonoursApiKeyProvider`
  [#2313](https://github.com/llm4s/llm4s/issues/2313), [#2477](https://github.com/llm4s/llm4s/issues/2477), [#2478](https://github.com/llm4s/llm4s/issues/2478).
- **Added** `llm4s-secrets-vault` (experimental): `VaultApiKeyProvider` reads a provider's API key from HashiCorp Vault's KV engine
  over its HTTP API (no SDK), cached and read again every refresh interval [#2479](https://github.com/llm4s/llm4s/issues/2479).
- **Added** `llm4s-secrets-aws` (experimental): `AwsSecretsManagerApiKeyProvider` reads a provider's API key from AWS Secrets Manager
  with `GetSecretValue`, signed with SigV4 (no AWS SDK), cached and read again every refresh interval [#2479](https://github.com/llm4s/llm4s/issues/2479).

### Providers

- **Added** `Completion.adjustments`: each requested option a provider or model could not honour, as an
  `@Stable` `RequestAdjustment` (`Dropped`, `Adjusted`, `NotNeeded`) naming a `@Stable` `RequestOption`, also on
  `TransformationResult`, and on each agent call's `ModelCallCompleted` (an event stored before reads with none);
  logged at WARN once per client, model and option, where it was DEBUG. Gemini, Vertex AI,
  Bedrock, Ollama and the standard OpenAI-compatible dialect (Mistral, Cohere, DeepSeek, generic) report a requested
  `reasoning` they do not send as `Dropped`, and OpenAI reports the `temperature`, `top_p` and penalties set away
  from their defaults that a reasoning model's request leaves out, and xAI the penalties it leaves out of one, where
  each was dropped silently. An empty list means nothing was reported, not that every option was sent: other
  omissions follow under RFC 0008. Both types are promoted from the `@Experimental` tier RFC 0008 first gave them,
  because the `@Stable` `Completion` exposes them (#1818); the RFC is amended to match ([RFC 0008](docs/rfcs/0008-generation-controls.md) decisions 12 and 13, PR 1) [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Fixed** every reply of a model the registry does not know (a local, custom or gateway name) rescanning the whole model registry to estimate its cost: a registry now remembers how it resolved an id that is not an exact key [#2632](https://github.com/llm4s/llm4s/issues/2632).
- **Fixed** the OpenAI-compatible, Gemini and Vertex AI clients redacting every request and response body for a debug log line even with DEBUG off; a chat call allocates less than half as much [#2631](https://github.com/llm4s/llm4s/issues/2631).
- **Fixed** the Gemini and Vertex AI clients failing `complete` with `UnknownError("key not found: parts")` when a thinking
  model spent the whole `maxOutputTokens` thinking: it is an empty answer with its usage, and a stream now passes on its `MAX_TOKENS` finish reason;
  a reply a content filter stopped (`SAFETY` and the like), even after text, or a blocked prompt is the new `ContentFilteredError`,
  which `ReliableClient`'s circuit breaker does not count, and another abnormal finish with nothing produced a `ValidationError`, the same from `complete` and a stream;
  [migration](docs/reference/migration.md#a-gemini-reply-a-content-filter-stopped-is-an-error-2563) [#2563](https://github.com/llm4s/llm4s/pull/2563).
- **Changed** `TokenUsage.completionTokens` never includes `thinkingTokens`: the OpenAI, OpenAI-compatible (not xAI) and
  Perplexity clients take reasoning tokens out of it, so cost counts thinking once, and every output token count includes thinking [#2564](https://github.com/llm4s/llm4s/pull/2564).
- **Fixed** `ReliableClient.streamComplete` no longer retries a stream once a chunk has reached the caller, so a failure
  mid-reply is not delivered twice ("HelHello") or run and billed again, and the caller's own `onChunk` failing does not
  count against the circuit breaker [#2562](https://github.com/llm4s/llm4s/pull/2562).
- **Fixed** the OpenAI-compatible client returning `Right` for a stream that never finished: one with no `finish_reason` or
  `[DONE]`, or carrying an `error` event, is now a `Left`, so a tool call cut before its arguments no longer reaches an agent [#2550](https://github.com/llm4s/llm4s/pull/2550).
- **Added** `LlmClientOptions.withHttpClient`: the SDK-free chat providers send their requests through your own
  `Llm4sHttpClient`, which you keep owning; `ProviderFeatures.customHttpClient` (opt-in, `false` by default) says which honour it; `llm4s-sttp` (Experimental) supplies one over an sttp 4 synchronous backend [#2341](https://github.com/llm4s/llm4s/issues/2341); [guide](docs/guide/http-transport.md).
- **Fixed** signed image URLs with a query string or fragment retain their actual media type instead of falling back to
  JPEG [#2491](https://github.com/llm4s/llm4s/issues/2491).
- **Added** keyless Microsoft Entra ID authentication for Azure OpenAI: `authentication = "entra-id"` with the new
  `llm4s-azure-identity` (`DefaultAzureCredential`, any `TokenCredential`), `AzureConfig.withTokenProvider`, embeddings, Java and Spring [#2274](https://github.com/llm4s/llm4s/issues/2274).
- **Added** one proxy and custom-CA setting honoured by every provider client - the JDK client, `openai-java`,
  `anthropic-java` and the AWS SDK: `llm4s.http` (`HTTPS_PROXY`, `NO_PROXY`, a trust store, an mTLS key store), a
  section's `http` override, the Java builders' `proxy`/`trustStore` and the Spring `llm4s.http.*` properties;
  [Corporate networks](docs/getting-started/configuration.md#corporate-networks-a-proxy-and-a-custom-ca) [#2273](https://github.com/llm4s/llm4s/issues/2273).
- **Changed** first-run configuration errors and provider 401/403, 429 and 404 errors (and a refused Ollama connection)
  now say the next step and link their [First-Run Troubleshooting](docs/getting-started/troubleshooting.md) entry; configuration errors also name the doctor [#2285](https://github.com/llm4s/llm4s/issues/2285).
- **Removed** `OpenAIToolHelper` from the public API (now `private[llm4s]`), so no openai-java type is part of
  `llm4s-openai`'s Stable surface [#2360](https://github.com/llm4s/llm4s/issues/2360).
- **Added** an xAI (Grok) provider (Beta), `provider = "xai"` in `llm4s-openai-compatible`: Chat Completions at
  `https://api.x.ai/v1` with `XAI_API_KEY` bound to `llm4s.credentials.xai.apiKey`; `reasoning_content` as thinking,
  reasoning tokens, `CompletionOptions.reasoning` as `reasoning_effort` on the Grok models that take it, and model
  listing [#1023](https://github.com/llm4s/llm4s/issues/1023).
- **Added** `llm4s-perplexity` (Beta): `provider = "perplexity"` on Perplexity's Agent API (`/v1/agent`, which replaced
  Sonar Chat Completions), with `web_search`, its sources on `Completion.citations`, tool calls and streaming [#1026](https://github.com/llm4s/llm4s/issues/1026).
- **Added** `llm4s-jlama` (Experimental): `provider = "jlama"` runs a small model inside the JVM with Jlama, so a first
  answer needs no API key and no model server; `complete` and streaming, no tools ([quick start](docs/getting-started/local-jlama.md)) [#2284](https://github.com/llm4s/llm4s/issues/2284).
- **Added** Google Search grounding for `gemini` and `vertexai` sections (`googleSearch = true`), and their
  `groundingMetadata` sources on `Completion.citations`, from `complete` and from a stream [#1216](https://github.com/llm4s/llm4s/issues/1216).
- **Added** web search for `anthropic` sections (`webSearch = true`), and the sources the answer's text blocks cite
  on `Completion.citations`, from `complete` and from a stream [#1216](https://github.com/llm4s/llm4s/issues/1216).
- **Changed** an `ollama` section's `baseUrl` to default to `http://localhost:11434`, so a local section needs only
  `provider` and `model`; `OLLAMA_BASE_URL` is the one documented variable (`baseUrl = ${?OLLAMA_BASE_URL}`) [#1991](https://github.com/llm4s/llm4s/issues/1991).
- **Changed** a `llm4s.providers.<name>` section's and a `llm4s.embeddings.<id>` block's `baseUrl` to be checked when
  that section is loaded: it must be an absolute `http` or `https` URL with a host, or the load fails with a
  `ConfigurationError` naming the key and the value (its userinfo, query and fragment redacted). A config whose
  `baseUrl` was already broken (`localhost:11434`, `htp://...`, a space) now fails at load instead of on the first call;
  a blank `baseUrl` is still unset, and other sections are unaffected [#2347](https://github.com/llm4s/llm4s/issues/2347).
- **Changed** the OpenAI, Ollama, Voyage, Jina and Cohere embedding providers to return an unreachable or slow provider
  as the HTTP client's `NetworkError`/`TimeoutError` (recoverable) rather than a code-less `EmbeddingError` [#1781](https://github.com/llm4s/llm4s/issues/1781).
- **Changed** `llm4s-watsonx` (Beta) to send `presencePenalty` and `frequencyPenalty` (when not 0.0) and `responseFormat`
  (`json_object`, `json_schema`) on the chat API instead of dropping them, to send `reasoning` as `reasoning_effort` to
  the `openai/gpt-oss-*` models (other models get nothing and a one-time warning), and to read a reply's
  `reasoning_content` as thinking, on `complete` and streamed [#1314](https://github.com/llm4s/llm4s/issues/1314).
- **Fixed** the `openai`, `azure`, `requesty` and `anthropic` clients returning a request that hit its `timeouts.request`
  as a non-retryable `UnknownError` (OkHttp's call timeout, `InterruptedIOException("timeout")`): it is now a recoverable
  `NetworkError("Request timeout")`, as a socket timeout already was, so `ReliableClient` retries it. The `anthropic` client
  also returns an unreachable server as a recoverable `NetworkError` instead of an `UnknownError`, and a 429's
  `Retry-After` on its `RateLimitError`, as the other providers do [#380](https://github.com/llm4s/llm4s/issues/380).
- **Fixed** `HttpResponse#ensureSuccess` and `Result#mapServiceError` dropping a failed response's request id and
  `Retry-After`: `ensureSuccess` reads `x-request-id` / `request-id` / `x-amzn-requestid` / `apim-request-id` and a
  `Retry-After` of delta-seconds or an HTTP-date into the `ServiceError`, and `mapServiceError` keeps both, so the
  model listers' errors carry the provider's correlation id and retry hint [#2266](https://github.com/llm4s/llm4s/issues/2266).
- **Fixed** streamed `TokenUsage` losing cached and thinking tokens: the provider SPI's `StreamingAccumulator` gains
  `updateUsage(TokenUsage)`, which merges every field (prompt, completion, thinking, `cachedTokens`,
  `cacheCreationTokens` and the provider's total) and which `toCompletion` reports; `updateTokens` and
  `updateTokensWithThinking` are unchanged. The `openai`, `azure` and `requesty` stream uses it. The `gemini` and
  `vertexai` clients now read `usageMetadata.thoughtsTokenCount` as `thinkingTokens` and `cachedContentTokenCount` as
  `cachedTokens`, from `complete` and from a stream, so their cost estimates count thinking and cache reads
  [#2348](https://github.com/llm4s/llm4s/issues/2348).
- **Fixed** a streamed Anthropic completion's usage, which reported `promptTokens = 0` and no prompt-cache counts:
  it now passes `message_start`'s and `message_delta`'s counts to `StreamingAccumulator.updateUsage`, so its estimated cost is complete [#2115](https://github.com/llm4s/llm4s/issues/2115).
- **Fixed** the `ValidationError` of a `completeStructured` (and `completeStructuredOf`) reply that does not fit `A`: its message named only the
  JSON path (`$['age']`); it now adds the reader's reason, including a bridged decoder's own message [#2295](https://github.com/llm4s/llm4s/issues/2295).

### Java & Kotlin APIs

- **Added** `quarkus-llm4s` (`modules/quarkus`, Maven, not yet published): `@Named` `JLlmClient` beans per
  `llm4s.providers` section and a `JEmbeddingClient` from `application.properties` in the Spring starter's spelling,
  checked at build time, and a SmallRye Health readiness check; JVM mode, Quarkus 3.40.1. Part of [#2280](https://github.com/llm4s/llm4s/issues/2280); see the [Quarkus guide](docs/guide/quarkus.md).
- **Added** `Llm4s.agentBuilder(id, client)` to `llm4s-java-api`: a `JAgent` with a system prompt, tools, input and output
  guardrails (`JGuardrails`: length, profanity, regex, JSON), a context window (`JContextWindow.lastMessages`) and
  streaming, and a step limit, checkpointer and tracer (`maxSteps`, `checkpointer`, `tracing`, #2279) [#2476](https://github.com/llm4s/llm4s/issues/2476); see the [Java guide](docs/guide/java.md#an-agent-with-a-system-prompt-guardrails-and-a-context-window).
- **Added** declarative AI services to `llm4s-java-api` (experimental): `Llm4s.aiService(MyService.class)` implements an
  interface annotated `@SystemMessage`/`@UserMessage`/`@V`/`@MemoryId` (`org.llm4s.javaapi.services`), with typed returns, memory and tools, checked at `build()` [#2277](https://github.com/llm4s/llm4s/issues/2277); see [Java AI services](docs/guide/java-ai-services.md).
- **Added** `CompletableFuture` variants to `llm4s-java-api`: `completeAsync`/`completionAsync` (structured output
  included), `JAgent.runAsync`/`continueConversationAsync`/`resumeAsync`/`recoverAsync` and `embedAsync`, failing with
  `LlmException`, `cancel(true)` interrupting the call, on a virtual thread or a `withAsyncExecutor` executor;
  `LLM4STemplate.completeAsync` delegates to it [#2276](https://github.com/llm4s/llm4s/issues/2276).
- **Added** a Kotlin DSL to `llm4s-kotlin-api` over the Java facade's builders: `tool`/`tools` with `param<T>`,
  `optionalParam<T>` and a `suspend` `handler` whose `ToolArgs` reads typed arguments and the call's `idempotencyKey`
  and `toolCallId` (the Kotlin half of [#1740](https://github.com/llm4s/llm4s/issues/1740)), `completionOptions { ... }`
  for the new `LLMClientKt.complete(query, options)`, client blocks `Llm4s.openAi { ... }` (and `anthropic`, `gemini`,
  `ollama`, `openAiCompatible`, `azureOpenAi`) and `Llm4s.createAgent(client, tools)`; cancelling the coroutine that
  runs a turn cancels a suspended handler [#1495](https://github.com/llm4s/llm4s/issues/1495); see the
  [Java guide](docs/guide/java.md#the-kotlin-dsl).
- **Added** `agent(id, client) { ... }` to `llm4s-kotlin-api`, over `Llm4s.agentBuilder`: a system prompt, tools,
  input and output guardrails, `contextWindow(lastMessages = n)` and `streaming()`, returning a `Result<AgentKt>`
  rather than throwing [#2534](https://github.com/llm4s/llm4s/issues/2534); see the [Java guide](docs/guide/java.md#an-agent-block).
- **Fixed** `JLlmClient.streamAsPublisher` (and so `llm4s-reactor`'s `Streams.toFlux`) delivering a failed call's
  `onError` ahead of pieces still buffered: every piece streamed before the failure now reaches the subscriber, in
  order, before the error, with any demand and with a full buffer, and a subscriber that cancels gets no signal after it
  [#2198](https://github.com/llm4s/llm4s/issues/2198), [#2394](https://github.com/llm4s/llm4s/issues/2394).
- **Added** the call's id and idempotency key for Java tools run by an agent: `JToolArgs.toolCallId()` and
  `JToolArgs.idempotencyKey()` (`Optional<String>`, empty outside an agent run), read by a `JTool.builder` lambda from
  its `args` and by a `@Tool` method from a `JToolArgs` parameter the model does not see, and by a Kotlin DSL tool's
  handler as `ToolArgs.idempotencyKey` and `ToolArgs.toolCallId` (`null` outside an agent run) [#1495](https://github.com/llm4s/llm4s/issues/1495) [#1740](https://github.com/llm4s/llm4s/issues/1740).
- **Added** `llm4s-reactor` (Experimental): `Streams.toFlux(client, query | conversation[, options])` returns a streamed
  reply as a Reactor `Flux<String>` over `JLlmClient.streamAsPublisher`, with backpressure, and cancellation that
  interrupts the provider call [#1492](https://github.com/llm4s/llm4s/issues/1492); see [Java Reactor](docs/guide/java-reactor.md).
- **Added** `llm4s-spring-ai` (Experimental, Spring AI 2.0.x provided): `Llm4sChatModel` / `Llm4sEmbeddingModel` (an llm4s client as a Spring AI
  model), `SpringAiLlmClient` (a Spring AI `ChatModel` as an llm4s client, its options a copy of the model's own [#2645](https://github.com/llm4s/llm4s/issues/2645)) and a `ChatModel` bean auto-configuration [#2278](https://github.com/llm4s/llm4s/issues/2278); see [Using LLM4S with Spring AI](docs/guide/spring-ai.md).
- **Added** `FakeLlmClient` to `llm4s-java-api`: a scripted fake model for testing Java code that calls llm4s, whose
  `client()` is a real `JLlmClient`, with a call log and scripted failures of each `LlmErrorKind`, and a JUnit 5
  "Testing your code" section in the Java guide [#1497](https://github.com/llm4s/llm4s/issues/1497).
- **Added** agent tools defined from Java: `@Tool`/`@Param` methods (`JTool.fromAnnotated`) and lambdas
  (`JTool.builder(...).param(...).handler(...)`), given to `Llm4s.createAgentWithTools`; arguments are read into Java
  values without a JSON library, and a handler's exception is a tool error the model sees [#1484](https://github.com/llm4s/llm4s/issues/1484).
- **Added** token streaming to the Java facade: `JLlmClient.stream(..., CompletionStreamListener)` returns a
  cancellable `CompletionStream` (`onPartial`, then `onComplete` or `onError` once), and `streamAsPublisher` a cold
  `Flow.Publisher<String>` that holds the provider for a slow subscriber [#1485](https://github.com/llm4s/llm4s/issues/1485).
- **Added** structured output into a Java record: `JLlmClient.complete(query, Record.class)` and the conversation
  overloads derive the record's JSON Schema and read the answer back, with failures naming the field [#1486](https://github.com/llm4s/llm4s/issues/1486).
- **Added** `llm4s-java-rag` (Experimental), a Java facade for RAG: `JRag.builder()` over a `JEmbeddingClient`, `ingest`
  files or text, `ask` for an answer with its sources [#1491](https://github.com/llm4s/llm4s/issues/1491); see [Java RAG](docs/guide/java-rag.md).
- **Added** Spring Boot configuration metadata to `llm4s-spring-boot-starter`, so IDEs complete and document the
  `llm4s.*` properties; a spec keeps it in step with the bound properties [#1467](https://github.com/llm4s/llm4s/issues/1467).
- **Added** `Llm4s.createEmbeddingClient("provider/model")`, a Java embedding client for a model you name; `llm4s.embeddings.model`
  need not be set [#1781](https://github.com/llm4s/llm4s/issues/1781); see the [Java guide](docs/guide/java.md#embeddings).
- **Changed** a Java embedding client to accept a model its provider module does not list (Ollama's `bge-m3`): BREAKING,
  `JEmbeddingClient.dimensions()` is an `OptionalInt`, empty for such a model [#1781](https://github.com/llm4s/llm4s/issues/1781).
- **Added** `Llm4s.wrapClient(JLlmClient)` to `llm4s-kotlin-api`: a client built in code with the Java builders
  (`ollama()`, `openAi()`, ...) becomes an `LLMClientKt` [#1850](https://github.com/llm4s/llm4s/issues/1850); see the [Java guide](docs/guide/java.md#configure-in-code).
- **Added** Spring Boot 4 support to `llm4s-spring-boot-starter`, in the same artifact: on Boot 4, where `HealthIndicator`
  moved to `org.springframework.boot.health.contributor`, the `llmHealthIndicator` bean is an `LlmBoot4HealthIndicator`
  reporting what `LlmHealthIndicator` does on 3.x. CI runs the starter's specs on Spring Boot 4.0.8 and 4.1.1 as well as
  3.3.6 and 3.5.16 [#1467](https://github.com/llm4s/llm4s/issues/1467); see the [Spring Boot guide](docs/guide/spring-boot.md#what-the-starter-gives-you).
- **Changed** `llm4s-java-api` and `llm4s-spring-boot-starter` to make the provider modules optional dependencies: a Java
  user who calls only Ollama no longer receives the OpenAI and Anthropic SDKs, and a builder whose module is missing
  fails with a `CONFIGURATION` error naming it [#1496](https://github.com/llm4s/llm4s/issues/1496).
- **Added** an agent to `llm4s-spring-boot-starter`: `llm4sTools` collects the `@Tool` methods of the application's beans
  and `llm4sAgent` is a `JAgent` over them, set by `llm4s.agent.*` (with `max-steps`, a replaceable `llm4sCheckpointer`),
  plus `llm4sEmbeddingClient` (`llm4s.embeddings.*`), `llm4sTracing` (`llm4s.tracing.*`) and `Flux`/`SseEmitter` streaming
  templates [#2279](https://github.com/llm4s/llm4s/issues/2279); see the [Spring Boot guide](docs/guide/spring-boot.md#an-agent-with-tools).

### Tools

- **Fixed** (security) HTTP tool domain matching (`HttpConfig.isDomainAllowed`) ignores a leading `www.` and trailing dots
  on both sides, so `www.example.com` and `example.com.` are blocked (or allowed) with `example.com` [#2496](https://github.com/llm4s/llm4s/issues/2496), [#2581](https://github.com/llm4s/llm4s/issues/2581).
- **Added** typed tools to `llm4s-schema-derivation` (Experimental): `Tool.of[Args, R](name, description)(f)`, `f` an `Args => Either[String, R]`, builds the existing `ToolFunction` from a function over a case class, with the parameter schema
  derived from `Args` and argument errors reported as `SafeParameterExtractor`'s `ToolParameterError`s; no change to
  core's tool API. A derived `Long` is now described through `NumberSchema(isInteger = true)`, whose JSON is the same
  `integer` [#1473](https://github.com/llm4s/llm4s/issues/1473); [guide](docs/guide/derived-schemas.md#typed-tools),
  sample `TypedCalculatorExample`.
- **Added** sum types to `llm4s-schema-derivation`: enums and sealed traits whose cases have fields derive an `anyOf`
  tagged with uPickle's discriminator, which `completeStructuredOf` sends wrapped in a `value` property and unwraps
  [#2327](https://github.com/llm4s/llm4s/issues/2327); [guide](docs/guide/derived-schemas.md#sum-types).
- **Added** named tuples to `llm4s-schema-derivation`: `SchemaOf[(a: A, b: B)]` and a `ReadWriter` in
  `org.llm4s.schema.namedtuples` (explicit import) make a named tuple a structured output, a typed tool's arguments or
  result, or an agent's answer, with no case class to declare [#2328](https://github.com/llm4s/llm4s/issues/2328);
  [guide](docs/guide/derived-schemas.md#named-tuples).
- **Added** scoped typed tools to `llm4s-schema-derivation`: `Tool.scoped` builds a typed tool from a function that
  returns its result and runs in a `ToolScope`, with `reject`, `ensure` and `.?` for early exit and
  `ToolScope.idempotencyKey` for the call's key; outside one they do not compile [#2336](https://github.com/llm4s/llm4s/issues/2336);
  [guide](docs/guide/derived-schemas.md#scoped-tools).
- **Added** `llm4s-schema-iron` (Experimental): Iron refinement types (`Int :| Interval.Closed[1, 5]`) add their bounds to
  a derived schema and are refused on decode when broken [#2294](https://github.com/llm4s/llm4s/issues/2294); [guide](docs/guide/derived-schemas.md#iron-refinements).
- **Added** tools derived from ordinary methods in `llm4s-schema-derivation`: `Tool.fromMethod(service.method)` and
  `Tools.derive[S](service)`, named and described from `@toolName`, `@description` and Scaladoc, refusing unsupported
  methods at compile time [#2326](https://github.com/llm4s/llm4s/issues/2326); [guide](docs/guide/derived-schemas.md#tools-from-methods).
- **Added** streamed structured output to `llm4s-schema-derivation`: `client.streamStructuredOf[A](conversation)(onPartial)`
  hands `onPartial` typed partial values (`PartialOf[A]`, a named tuple of `Option`s) as the reply streams, parsed with the
  new `PartialJson` [#2329](https://github.com/llm4s/llm4s/issues/2329); [guide](docs/guide/structured-output.md#10-streaming-structured-output).
- **Fixed** `generate_uuid` (`UUIDTool`) refuses a `count` that is not a whole number (`3.5`, `"5"`, `null`) instead
  of silently generating one UUID; an absent `count` still gives one, and out-of-range values are still clamped to 1..10
  [#1514](https://github.com/llm4s/llm4s/issues/1514).
- **Added** optional jitter to tool retries: `ToolRetryPolicy(jitter = ...)` / `.withJitter`, a fraction from 0.0 (the
  default, exact delays as before) to 1.0 ("full jitter") of each backoff that is randomised, so concurrent retries do
  not hit a backend together [#1708](https://github.com/llm4s/llm4s/issues/1708); [guide](docs/guide/reliability.md).
- **Added** `llm4s-prompt` (Experimental): `prompt"..."`/`system"..."` interpolators that render each value through a
  `Promptable` checked at compile time (`derives Promptable`) and fence `Untrusted` input; `PromptTemplate` ([#2325](https://github.com/llm4s/llm4s/issues/2325), [guide](docs/guide/prompts.md)).
- **Added** `llm4s-tapir` (Experimental): `EndpointTools.registry` turns tapir endpoints into tools whose schemas derive
  from the endpoints' inputs, called through sttp [#2299](https://github.com/llm4s/llm4s/issues/2299); [guide](docs/guide/tapir-tools.md).
- **Fixed** `ContainerisedWorkspace.executeCommandWithStreaming` could drop all of a command's streamed stdout: the
  first stream to end (often the empty stderr) unregistered the handler; it now stays until the response arrives.

### MCP

- **Fixed** the Streamable HTTP client taking the first SSE event that parsed as a response as the reply: it now
  returns only the response to its own request id, skipping server-to-client requests and other ids, and bounds a
  reply at 10 MiB [#1846](https://github.com/llm4s/llm4s/issues/1846).

### Documentation & samples

- **Added** [OpenSSF Best Practices](docs/reference/best-practices.md): each passing criterion marked Met, Unmet or N/A with
  its evidence, and the follow-ups before the badge ([#2307](https://github.com/llm4s/llm4s/issues/2307)).
- **Added** the [support-assistant tutorial](docs/tutorials/support-assistant/index.md), parts 1-5 (first call, typed
  answers, tools and an agent, help-centre answers with RAG, safety and memory), with exercises; `Part1Spec` to
  `Part5Spec` run every block and solution offline ([#2292](https://github.com/llm4s/llm4s/issues/2292)).
- **Added** an offline evaluation dataset sample covering validation, selection, snapshots and JSONL import/export [#2234](https://github.com/llm4s/llm4s/issues/2234).
- **Added** the [support policy](docs/reference/support-policy.md) and its table of lines (`docs/_data/support.yml`), which CI
  and the release workflow check, linked from every release post; cadence and post-1.0 lines are to be decided ([#2289](https://github.com/llm4s/llm4s/issues/2289)).
- **Added** `JavaOfflineExample`: a ticket classifier tested with `FakeLlmClient`, demonstrating scripted replies,
  unknown-label fallback, rate-limit errors and recorded prompts [#2247](https://github.com/llm4s/llm4s/issues/2247).
- **Added** `llms.txt`, `llms-full.txt` and a rules file (`llm4s-rules.md`), built from the docs by the Pages workflow, per
  minor release, and the [AI coding assistants](docs/ai-assistants.md) page ([#2287](https://github.com/llm4s/llm4s/issues/2287)).
- **Added** a split sitemap for llm4s.org (`sitemap.xml` for the guide, `scaladoc-sitemap.xml` for the API pages, both in
  `robots.txt`, checked in the Pages deploy), a custom 404 page, and `redirect_from` for a moved page ([#2318](https://github.com/llm4s/llm4s/issues/2318)).
- **Added** guide/API cross-links: Scaladoc source links (release tag), links to JDK, Scala, cats, upickle and ZIO types and a
  root page to the guides; guides link core types to the Scaladoc (`api.html`); one site search over both ([#2291](https://github.com/llm4s/llm4s/issues/2291)).
- **Added** the [Mill quick start](docs/getting-started/mill.md) and its `build.mill` sample (`modules/samples/mill`), built
  and tested against Maven Central by `mill-quickstart.yml`, with an offline check that it pins `latest_release` ([#2293](https://github.com/llm4s/llm4s/issues/2293)).
- **Added** two offline Almond notebooks (`modules/samples/notebooks`), executed by `notebooks.yml`, and the [Jupyter Notebooks](docs/guide/notebooks.md)
  guide; an offline check keeps them on `latest_release` and free of outputs ([#2296](https://github.com/llm4s/llm4s/issues/2296)).
- **Added** [Using LLM4S from Scala 2.13](docs/getting-started/scala-2-13.md) (call-only, via the TASTy reader), with
  `llm4s-java-api` workarounds; a CI workflow runs the 2.13 probes against the build and fails on any outcome change ([#2301](https://github.com/llm4s/llm4s/issues/2301)).
- **Added** the [compatibility matrix](docs/reference/compatibility-matrix.md): provider capabilities and tested
  versions generated from the descriptors and the build by `sbt generateCompatibilityMatrix`, with a CI drift check;
  the comparison page is refreshed and a weekly check fails when an issue it cites as open closes (#2290).
- **Added** [Enterprise Evaluation](docs/reference/enterprise-evaluation.md): intake-questionnaire answers linked to
  their evidence, a paste-ready answer set and a dated known-gaps list the cited-issues check keeps current ([#2317](https://github.com/llm4s/llm4s/issues/2317)).
- **Added** [Legal and IP FAQ](docs/reference/legal-faq.md): licence, copyright, DCO, patents, third-party licences, provider terms and the cryptography llm4s uses (for export review), with what the project has not decided ([#2309](https://github.com/llm4s/llm4s/issues/2309)).
- **Changed** the reliability guide (`docs/guide/reliability.md`) and the vector store guide
  (`docs/guide/vector-store.md`) to compile: their `???` placeholders are real code (a `ScriptedLLMClient` from
  `llm4s-testkit` where a test needs a client, `Result` for-comprehensions where a store or searcher is built), and
  both pages are in `DocSnippets.pages`, so `sbt samples/Test/compile` fails when one of their snippets stops compiling.
  Fixing them also corrected snippets that had drifted from the API: the complete RAG example now embeds through an
  `EmbeddingRequest`, the RAGAS example passes the embedding client and model `RAGASEvaluator` takes, and the
  metrics collectors implement `observeRequest`, `addTokens` and `recordCost` (#2359).
- **Changed** the embeddings sample to load its own settings: `EmbeddingSampleConfig` in
  `org.llm4s.samples.embeddingsupport` reads `llm4s.samples.embeddings.*`, which the samples' `application.conf` binds to the
  same variables as before (`EMBEDDING_INPUT_PATH(S)`, `EMBEDDING_QUERY`, `CHUNK_*`, the UI knobs,
  `ENABLE_EXPERIMENTAL_STUBS`, `LLM4S_EMBEDDINGS_LOCAL_*`) [#2265](https://github.com/llm4s/llm4s/issues/2265).
- **Changed** the front of llm4s.org: a redesigned home page and new **Why LLM4S** (`/why-llm4s/`), **Learn**
  (`/learn/`) and **Showcase** (`/showcase/`) pages, and a rewritten **Foundation** page (`/foundation/`) on what
  support funds, ways to give and partner on internships, and how funds are handled through Open Collective. They use
  a new full-width `landing` layout (`docs/_layouts/landing.html`, styles scoped to `body.landing` in
  `_sass/custom/custom.scss`); the documentation pages keep their layout and gain a **Sponsor** header link. The
  numbers in the home page's stats band are in `docs/_data/stats.yml` (as of Oct 2026) and the showcased open-source
  projects in `docs/_data/showcase.yml`; ADOPTERS.md is unchanged. The home page's Scala sample is compiled with the
  other documentation snippets (`index.md` joins `DocSnippets.pages`). Existing permalinks are unchanged.
- **Added** a Quarkus guide ([docs/guide/quarkus.md](docs/guide/quarkus.md)) and a runnable Maven sample,
  `modules/samples/quarkus-java`, on the `quarkus-llm4s` extension: a `@Blocking` `POST /chat` resource; `mvn quarkus:dev`
  answers from a local Ollama. Its `@QuarkusTest` runs against a stub Ollama server on a loopback port, and CI builds and
  tests it and checks that the guide's snippets are excerpts of it (`scripts/check-quarkus-guide-snippets.sh`)
  [#1468](https://github.com/llm4s/llm4s/issues/1468), [#2280](https://github.com/llm4s/llm4s/issues/2280).
- **Added** Micronaut 4 and Helidon SE 4 guides ([micronaut](docs/guide/micronaut.md), [helidon](docs/guide/helidon.md))
  with runnable Gradle samples, tested in CI with `FakeLlmClient` [#2281](https://github.com/llm4s/llm4s/issues/2281).
- **Added** `DocumentExtractionInspector`, an offline sample that runs `TikaDocumentExtractor` over a file or a
  directory (or generated .txt/.md/.html/.docx/.pdf samples) and prints each file's detected MIME and `MediaType`, the
  extractor used, character and line counts, metadata, a preview and any extraction error
  [#478](https://github.com/llm4s/llm4s/issues/478).
- **Added** an http4s reference app, the unpublished `modules/examples-http`: an Ember server over `llm4s-effect`'s
  `AgentIO[IO]` with `POST /chat` (JSON) and `POST /chat/stream` (server-sent events of the answer), the provider from
  `Llm4sConfig` at startup or `--fake`, `LLMError` mapped to HTTP statuses (400, 429 with `Retry-After`, 504, 502, 503),
  and the run cancelled when a streaming client disconnects. Tested on an ephemeral port against scripted clients;
  guide: `docs/guide/http-reference-apps.md`. ZIO HTTP and Tapir apps follow [#1459](https://github.com/llm4s/llm4s/issues/1459).
- **Added** a ZIO HTTP reference app to `modules/examples-http`: a ZIO HTTP 3.2 (Netty) server over `llm4s-zio`'s
  `AgentZ` with the http4s app's API and behaviour - `POST /chat` (JSON), `POST /chat/stream` (`delta`, `tool_call`,
  `done`/`error` events and a keep-alive comment), the shared error-to-status mapping, `--fake`, and the run cancelled
  when a streaming client disconnects. Run it with
  `sbt "examplesHttp/runMain org.llm4s.examples.http.ziohttp.ZioChatServerMain --fake"`; the ZIO guide links it.
  Tapir follows [#1459](https://github.com/llm4s/llm4s/issues/1459).
- **Added** a Tapir reference app to `modules/examples-http`, completing the set: `POST /chat` and `POST /chat/stream`
  as Tapir endpoint definitions (`ChatEndpoints`), with a typed error output - one variant per status `HttpErrors`
  chooses, `Retry-After` on `429` - interpreted by `tapir-http4s-server` on the http4s app's Ember server over the same
  `AgentIO[IO]` and event stream, so a disconnecting client cancels the run in the same way. The same definitions
  generate the OpenAPI document, served with a Swagger UI at `/docs` (`/docs/openapi.yaml`). Tapir 1.11.20, the last
  release on the module's cats-effect 3.5, fs2 3.11 and http4s 0.23.30. Run it with
  `sbt "examplesHttp/runMain org.llm4s.examples.http.tapir.TapirChatServerMain --fake"`
  [#1459](https://github.com/llm4s/llm4s/issues/1459).
- **Changed** the Testing Guide to test with `llm4s-testkit`: its eight hand-written `LLMClient` mocks are now
  `ScriptedLLMClient` scripts (canned replies, replies by request, injected errors, a scripted tool call), compiled with
  the page's other snippets [#1475](https://github.com/llm4s/llm4s/issues/1475).
- **Added** `ProviderDiscoveryExample` demonstrating dynamic provider discovery via `ProviderRegistry.discover()`,
  `ProviderRegistryReport` diagnostics and explicit module registration [#1548](https://github.com/llm4s/llm4s/issues/1548).
- **Documented** that a tool argument above 2^53 reaches the handler already rounded (send large ids as strings), with
  tests pinning it, in `SafeParameterExtractor`, `ToolCall` and the structured-output limits [#1597](https://github.com/llm4s/llm4s/issues/1597).
- **Added** a lightweight RFC process for public API changes ([docs/rfcs](docs/rfcs/README.md)): when one is required, a
  template, a 7-day final comment period, an index with the earlier frozen-API decisions, and RFC 0001 on when
  `llm4s-agent` gets its MiMa baseline [#1480](https://github.com/llm4s/llm4s/issues/1480).
- **Added** `scripts/dependency-footprint.sh` and the footprint table it prints in
  [Dependency Conflicts](docs/reference/dependency-conflicts.md#dependency-footprint): `llm4s-java-api` 0.5.1 is 55 jars
  and 130 MB, against 22 jars and 30 MB for core, the agent and Ollama [#1496](https://github.com/llm4s/llm4s/issues/1496).
- **Added** `ScriptedLLMClient.assertExhausted()`, `remaining` and `unusedReplies` (a scripted reply never asked for), and
  [testing guide](docs/guide/testing-with-the-testkit.md#other-test-frameworks) fixtures for MUnit, munit-cats-effect,
  weaver and ZIO Test, each a suite CI runs [#2335](https://github.com/llm4s/llm4s/issues/2335).
- **Added** a `description` for every page under getting-started, guide, examples, reference, migrations, advanced, api and community, a
  `search_title` front-matter key (rendered into `<title>`, `og:title` and `twitter:title` by `docs/_plugins/search_title.rb`; `title` stays the
  navigation label) and `scripts/check-doc-seo.sh`, which fails CI on a missing, duplicate or over-long title or description [#2314](https://github.com/llm4s/llm4s/issues/2314).
- **Added** [Moving a Java app from LangChain4j or Spring AI](docs/guide/java-migration.md): concepts, compiled Java for
  each common task, provider configuration and the gaps; the Scala migration and comparison pages corrected for the Java API [#2282](https://github.com/llm4s/llm4s/issues/2282).
- **Added** a jsoniter-scala section to the [JSON libraries guide](docs/guide/json-libraries.md#jsoniter-scala): a `JsonValueCodec` as the `Reader` of
  `completeStructured` and a tool's `ReadWriter`, tested, and the sample `JsoniterStructuredOutputExample` [#2295](https://github.com/llm4s/llm4s/issues/2295).
- **Added** [Coming from sttp-ai or openai-scala-client](docs/guide/migrating-from-scala-llm-clients.md): their calls next to
  LLM4S code compiled and run by `ScalaClientMigrationGuideSpec`, and when to stay on your client [#2343](https://github.com/llm4s/llm4s/issues/2343).
- **Added** [Coming from Python](docs/guide/for-python-developers.md): OpenAI SDK, LangChain, LlamaIndex and Pydantic code next to
  Scala 3 + LLM4S, one Scala concept per section, run by `PythonDevelopersGuideSpec` [#2344](https://github.com/llm4s/llm4s/issues/2344).
- **Added** [Replacing a Python sidecar](docs/guide/replacing-a-python-sidecar.md): vLLM, TGI and llama.cpp models as `openai-compatible` sections, and a Python
  function as an agent tool over MCP stdio, with the CI-run sample `PythonToolBridgeExample` [#2333](https://github.com/llm4s/llm4s/issues/2333).
- **Added** the [Enterprise Reference Architecture](docs/guide/reference-architecture.md) (AI gateway, secret injection, egress, failover, spend) with a tested
  `deploy/overlays/enterprise` (LiteLLM, NetworkPolicy, ExternalSecret) validated by `kubeconform`, its gateway path driven through a real LiteLLM container in `modules/it`; stale *Production Deployment* sections corrected [#2315](https://github.com/llm4s/llm4s/issues/2315).
- **Added** a [Scala AI](docs/scala-ai/index.md) section: five short pages (Scala LLM framework and LangChain, agents, RAG, Java and
  Kotlin, providers) whose code `ScalaAiPagesSpec` compiles and runs offline; the provider list renders `capabilities.yml` [#2320](https://github.com/llm4s/llm4s/issues/2320).
- **Added** a News section on llm4s.org (`/news/`, posts for 0.5.0 and 0.5.1) with an Atom feed, per-tag feeds and
  `scripts/new-release-post.sh`, which drafts a release's post from its CHANGELOG section [#2319](https://github.com/llm4s/llm4s/issues/2319); [release step](docs/reference/release.md#write-the-news-post).
- **Added** [Enrich a Kafka topic with an LLM](docs/guide/kafka.md): an fs2-kafka pipeline (`org.llm4s.samples.kafka`) with bounded concurrency, retries,
  a dead-letter topic and offsets committed after the produce, tested offline and against a broker in `modules/it` [#2297](https://github.com/llm4s/llm4s/issues/2297).
- **Added** a Play 3 reference app to `modules/examples-http` over `llm4s-pekko`: Guice wiring with a stop hook, `llm4s` config in Play's
  `application.conf`, a blocking dispatcher, `Ok.chunked` SSE; [guide](docs/guide/http-reference-apps.md#the-play-app) [#2339](https://github.com/llm4s/llm4s/issues/2339).
- **Added** llm4s.org share cards (a large Open Graph/X card on every page, rendered by `scripts/render-social-images.sh`), `SoftwareSourceCode`,
  `Organization` and `BreadcrumbList` JSON-LD, and `scripts/check-site-share-cards.sh` in the Pages deploy [#2316](https://github.com/llm4s/llm4s/issues/2316).
- **Added** [Using LLM4S from Apache Flink](docs/guide/flink.md): a Flink 2.x job in Scala 3 with `AsyncDataStream`, dead letters
  as a side output, and a sample (`modules/samples/flink`) tested on Flink's MiniCluster by `flink-sample.yml` [#2298](https://github.com/llm4s/llm4s/issues/2298).
- **Added** a [press kit](docs/press.md) on llm4s.org (`/press/`: logo, 50- and 150-word descriptions, fact sheet,
  contact), linked from the footer and `/foundation/`, and the [1.0 launch plan](docs/community/launch-1.0.md) for JVM channels beyond Scala [#2323](https://github.com/llm4s/llm4s/issues/2323).
- **Added** a dev container and [Codespaces playground](docs/getting-started/playground.md): JDK 21, sbt, Scala CLI, Metals and a local Ollama model,
  no API key; the `devcontainer.yml` workflow builds it and runs the page's five commands inside it [#2286](https://github.com/llm4s/llm4s/issues/2286).

### Embeddings & RAG

- **Fixed** `GraphQAPipeline` always enforces `maxContextEdges`, including when ranking is disabled or unnecessary
  [#2495](https://github.com/llm4s/llm4s/issues/2495).
- **Added** caller-owned connection pools for every Postgres store and index: `PgVectorStore`, `PgKeywordIndex`,
  `HybridSearcher.pgvectorShared(dataSource, vectorTableName, keywordTableName[, strategy])` and
  `PgSearchIndex.fromDataSource(dataSource, vectorTableName)` take any `javax.sql.DataSource` and never close it; no public
  signature names HikariCP. `HybridSearcher.pgvectorShared(connectionString, ...)` now closes the pool it creates when
  the searcher closes, and a config-built store closes its pool when the schema cannot be created
  [#2300](https://github.com/llm4s/llm4s/issues/2300); see
  [Vector Store](docs/guide/vector-store.md#sharing-your-applications-connection-pool).
- **Added** `llm4s-vectorstore-elasticsearch` (Experimental): `ElasticsearchVectorStore`, a `VectorStore` over the
  Elasticsearch REST API with no client library - a `dense_vector` index created on the first upsert with the configured
  similarity, kNN search with every `MetadataFilter` applied inside it, `_bulk` writes, API-key or basic authentication,
  configured from `llm4s.vectorstore.elasticsearch` (`ELASTICSEARCH_URL`, ...) by `ElasticsearchVectorStoreConfigLoader`
  [#1469](https://github.com/llm4s/llm4s/issues/1469); see [Vector Store](docs/guide/vector-store.md#elasticsearch).
- **Added** `OpenSearchVectorStore` to `llm4s-vectorstore-elasticsearch` (Experimental), sharing the Elasticsearch
  store's code: an index created with `index.knn: true` and an HNSW `knn_vector` field (`spaceType` `cosinesimil`,
  `innerproduct` or `l2`; `engine` `lucene`, `faiss` or `nmslib`; optional `m` and `efConstruction`), the `knn` query
  with the `MetadataFilter` inside it on Lucene and Faiss (efficient filtering) and as a `bool` post-filter on NMSLIB,
  scores mapped onto `[0, 1]` per space type, basic or bearer-token authentication (AWS SigV4 is not supported),
  configured from `llm4s.vectorstore.opensearch` (`OPENSEARCH_URL`, `OPENSEARCH_INDEX`, `OPENSEARCH_USERNAME`,
  `OPENSEARCH_PASSWORD`, `OPENSEARCH_TOKEN`) by `OpenSearchVectorStoreConfigLoader`
  [#1469](https://github.com/llm4s/llm4s/issues/1469); see [Vector Store](docs/guide/vector-store.md#opensearch).
- **Added** `llm4s-vectorstore-redis` (Experimental): `RedisVectorStore`, a `VectorStore` over Redis with the Redis Query
  Engine (Redis Stack, Redis 8+) or Valkey with `valkey-search` (vector search and operations by ID; Valkey 9.0's
  `valkey-search` answers no query without a vector, so not `count`, `list` or `deleteByFilter`) - an `FT.CREATE`
  index over hashes created on the first upsert (HNSW or FLAT; COSINE, IP or L2), KNN `FT.SEARCH` with every `MetadataFilter` as a pre-filter of encoded `TAG`
  clauses (`Contains` needs the Redis Query Engine), pipelined writes, TLS and ACL credentials, configured from
  `llm4s.vectorstore.redis` (`REDIS_HOST`, `REDIS_PORT`, ...) by `RedisVectorStoreConfigLoader`. It talks to the server
  through Jedis, which stays out of its API [#1470](https://github.com/llm4s/llm4s/issues/1470); see
  [Vector Store](docs/guide/vector-store.md#redis-and-valkey).

### Memory

- **Added** `PostgresMemoryStore.fromDataSource(dataSource, tableName, embeddingService)`: the store over a caller-owned
  `javax.sql.DataSource` (a doobie `HikariTransactor`'s `kernel`, a Play or Slick pool), which `close()` leaves open; a
  store built from a `Config` closes its own pool, also when the schema cannot be created
  [#2300](https://github.com/llm4s/llm4s/issues/2300); see
  [Postgres Memory Store](docs/reference/postgres-memory-store.md#sharing-your-applications-connection-pool).
- **Changed** `PostgresMemoryStore.storeAll` sends its rows as JDBC batches (up to 1,000 per round trip) instead of one
  statement per memory [#637](https://github.com/llm4s/llm4s/issues/637); see
  [bulk ingestion](docs/reference/postgres-memory-store.md#bulk-ingestion).
- **Changed** `MemoryManagerConfig` to a private constructor with `with*` setters (`.copy` is private) [#1847](https://github.com/llm4s/llm4s/issues/1847).
- **Fixed** `autoExtractEntities` swallowing a cancellation: `recordMessage` returns the `CancelledError` (also a failure ending
  while the thread is interrupted, or a thrown `InterruptedException`) and the interrupt flag stays set [#1847](https://github.com/llm4s/llm4s/issues/1847).

### Security

- **Added** `Secret`: every credential llm4s holds (configured keys, runtime tokens) is registered and removed by exact match, whatever the syntax around it, from the error messages, exchange logs and diagnostic text llm4s builds (traces in a follow-up) ([#1932](https://github.com/llm4s/llm4s/issues/1932), [design](docs/superpowers/specs/2026-10-15-layered-redaction-design.md)).
- **Changed** (security) the file tools' default `blockedPaths` is per platform, `FileConfig.DefaultBlockedPaths`:
  Linux, macOS and Windows system locations and the credential stores under the user's home; blocked entries match
  regardless of case [#1408](https://github.com/llm4s/llm4s/issues/1408); [migration](docs/reference/migration.md#the-file-tools-default-blockedpaths-covers-each-platform-1408).
- **Added** an OpenSSF Scorecard workflow, badge and baseline score on the security page; every workflow is read-only at
  the top level and pins its actions by commit SHA, enforced by `scripts/check-workflow-security.sh` [#2306](https://github.com/llm4s/llm4s/issues/2306); [security](docs/reference/security.md).
- **Fixed** (security) on POSIX the workspace runner refuses a `sort` operand after `--` that starts with `-t`
  (`sort -- -tNAME`): uutils coreutils (Ubuntu 25.10 and later) rewrites it on its unreleased main branch into
  `--field-separator=NAME` and opens a file of that name, which was never path-checked. Write `./-tNAME` instead.
  The runner image, whose Ubuntu 26.04 base ran uutils `sort`, now installs GNU coreutils, which the policy models
  [#1774](https://github.com/llm4s/llm4s/issues/1774); [sandbox](docs/reference/workspace-sandbox.md).
- **Fixed** (security) `Redaction` left the value of a sensitive key on an indented YAML line in the clear
  (`  apiKey: ...` under a section, `  - password: ...` in a list), whatever the key and quoting style; only a key at the
  start of a line was read [#1844](https://github.com/llm4s/llm4s/issues/1844).
- **Fixed** (security) the workspace runner refuses more ways round `WORKSPACE_EXTRA_COMMANDS`: rsync's daemon mode
  (`--daemon`, `--config`, `--dparam`, `-M`), sftp's `-b` batch file and scp's `-D`, a `tar` or `rsync` argument naming
  a file on another host (`host:path`, `host::module`, `rsync://`), Python front-ends, notebook tooling, package and
  project runners (`ipython`, `pydoc`, `jupyter`, `npx`, `uv`, `poetry`, `conda`, ...; `npm`, `yarn`, `pnpm` stay
  allowed build tools), and musl's loader as
  `libc.musl-*.so.1` [#1845](https://github.com/llm4s/llm4s/issues/1845); [sandbox](docs/reference/workspace-sandbox.md).
- **Fixed** (security) `Redaction` left the value of a sensitive key in the clear where TOML, YAML or dotenv text sits
  in a JSON string and the key starts a line after an escaped line break or tab (`\n`, `\r`, `\t`, `\u000a`, `\u000d`,
  also doubly escaped in JSON inside a JSON string): `token = "..."`, `password: ...`, `token='...'`. The rest of such
  a line is now replaced, and the JSON still parses [#1765](https://github.com/llm4s/llm4s/issues/1765),
  [#1799](https://github.com/llm4s/llm4s/issues/1799).
- **Fixed** `Redaction` read the quote that closes a JSON string as the opening quote of an empty `key=` value at its
  end (`{"q":"a=1&token=","n":"x"}`, also escaped in JSON inside a string) and masked the `,` or `}` after it, so the
  JSON no longer parsed; the value is now empty [#1800](https://github.com/llm4s/llm4s/issues/1800).

### Build & tooling

- **Added** comparative benchmarks (unpublished, outside the aggregate): llm4s, LangChain4j and Spring AI against a local mock
  OpenAI-compatible server, `sbt "benchmarksComparative/comparativeBenchmarks"` and a manual/per-release workflow [#2288](https://github.com/llm4s/llm4s/issues/2288); [Comparative Benchmarks](docs/reference/comparative-benchmarks.md).
- **Added** Scalafix migration rules (`modules/scalafix-rules`, not yet published): `Llm4sMigrate_0_5` rewrites `.copy`
  to `with*` setters, millisecond fields to `FiniteDuration`, `isRecoverable`, moved config loaders and message
  extractors, and lints removed APIs; `Llm4sMigrate_1_0` does the same for the breaks since 0.5.1 (growth-safe setters,
  `Secret` keys, `fromValues` order, `Completion.created`, widened patterns); tested with scalafix-testkit against 0.4.1
  and 0.5.1 [#2330](https://github.com/llm4s/llm4s/issues/2330); [Scalafix](docs/reference/scalafix.md#migration-rules).
- **Added** a per-module dependency licence report (`sbt licenseReport`, SPDX ids, attached to each GitHub Release) and
  `sbt licenseCheck`, a CI quick check that fails on a denied or unknown runtime licence [#2308](https://github.com/llm4s/llm4s/issues/2308); [Dependency licences](docs/reference/licences.md).
- **Added** `stabilityTierCheck` enforces RFC 0002 growth safety: it fails for a `case class` in a `@Stable` type of a
  frozen module (top-level or nested) whose constructor is not `private` / `private[x]`, and for a public `copy` on
  one, naming the file, line and type. Case classes with no fields are exempt; `GrowthExempted` in
  `project/StabilityTiers.scala` lists the deliberately closed types with a reason, and the 75 types that predate the
  check with `TODO(#2369)` to be converted before the baseline. A stale entry fails the check, and
  `StabilityTiers.selfTest` covers a passing type, a public constructor, a public `copy` and an allow-listed type
  [#2369](https://github.com/llm4s/llm4s/issues/2369); [API stability](docs/reference/api-stability.md#tiers-in-the-code).
- **Added** `stabilityTierCheck` enforces RFC 0004: it fails for a public member of a `@Stable` type in a frozen module
  whose signature names a type from fansi, `cats`, pureconfig, `com.typesafe.config`, jtokkit, slf4j, OkHttp, Jackson,
  `com.openai`, `com.anthropic` or `software.amazon`, written fully qualified or through an imported (or renamed) name.
  `ThirdPartyAllowed` in `project/StabilityTiers.scala` lists the third-party types that are API on purpose, each with
  its reason (upickle and ujson), and `StabilityTiers.selfTest` has a violating
  fixture for every deny-listed package [#2357](https://github.com/llm4s/llm4s/issues/2357);
  [API stability](docs/reference/api-stability.md#tiers-in-the-code).
- **Added** CI time limits: every job of the workflows that run on pull requests has a `timeout-minutes` of about twice
  its usual duration, instead of GitHub's six-hour default, and the specs that wait on futures, actors or threads (agent
  runtime, effect bridges, `java-api`, `java-rag`, workspace runner) fail a test that hangs under its own name after two
  minutes (`TestTimeLimit`, ZIO's `TestAspect.timeout`); [how to raise one](CONTRIBUTING.md#ci-time-limits)
  [#2370](https://github.com/llm4s/llm4s/issues/2370).
- **Added** a per-module MiMa baseline: `mimaBaselineOverrides` in `build.sbt` gives a frozen module its own baseline
  release (`"llm4s-<name>" -> Some("X.Y.Z")`) or none (`-> None`) while the others use `mimaBaselineVersion`, so
  `llm4s-agent` can take a later baseline than the other frozen modules ([RFC 0001](docs/rfcs/0001-agent-baseline.md)).
  No baseline is chosen: the map is empty and `mimaBaselineVersion` stays `None`, so nothing changes yet.
  `scripts/mima-dry-run.sh` compares each module with its own baseline, publishing at a second throwaway version when one
  has its own, checks that a held-back module is compared with nothing, prints each module's baseline with `--list-frozen`,
  and refuses an entry it cannot read or that names no frozen module; `scripts/test-mima-dry-run.sh` covers each case.
  API Stability no longer calls 0.4.1 the last release [#2355](https://github.com/llm4s/llm4s/issues/2355).
- **Changed** the Integration Tests job uploads coverage: it instruments `rag`, `vectorstoreElasticsearch`,
  `vectorstoreRedis`, `memoryPostgres`, `knowledgegraphNeo4j` and `agentCheckpointPostgres` (its `COVERAGE_MODULES`),
  runs the `@Docker` tier, and uploads each module's report under the module's existing Codecov flag, so code reachable
  only against Postgres, Qdrant, Elasticsearch, OpenSearch, Redis, Valkey or Neo4j counts as covered. Floors are not
  enforced from these partial reports; the coverage legs still enforce them. `scripts/check-coverage-legs.sh` allows
  this second upload of a flag for exactly those modules and checks its steps [#2349](https://github.com/llm4s/llm4s/issues/2349).
- **Changed** the release's `publish` job checks the Scaladoc log before `sbt ci-release`, as CI's ScalaDoc job does, so
  a release cannot package a new Scaladoc warning; and `scripts/check-scaladoc-warnings.sh` now fails on a third-party
  warning (a doc comment inherited from ScalaTest or cats-effect that Scaladoc cannot resolve) unless
  `scripts/scaladoc-third-party-warnings.txt` lists it with its reason. That file holds the ones that cannot be silenced
  from sbt, since Scaladoc ignores `-Wconf` [#1967](https://github.com/llm4s/llm4s/issues/1967).
- **Fixed** sbt's `Found intransitive dependency (spring-boot-health)` warning on every resolution: the starter's
  provided Spring Boot 4 health API now drops its dependencies with a `*:*` exclusion, which the published POM states,
  instead of `.intransitive()`, which a POM cannot; the classpath is unchanged [#1967](https://github.com/llm4s/llm4s/issues/1967).
- **Added** `ProviderSwitchingFailoverSpec`, a `@Local` suite in `modules/it` (part of `sbt test`): OpenAI, Anthropic and
  Ollama clients built from named sections switch in one JVM against local mock servers, with streaming, tool calls, token
  usage and 401/429/500 mapped alike, an unreachable or silent server a retryable error, config errors naming the section,
  a section reloaded from system properties, and failover through `ReliableClient` plus a fallback provider
  [#380](https://github.com/llm4s/llm4s/issues/380).
- **Fixed** `llm4s-kotlin-api` is published, first in 0.6.0: the release's version check now reads sbt's tab-indented
  version, which left Kotlin out of 0.5.0 and 0.5.1.
- **Added** `@Cloud` smoke suites for Azure OpenAI (classic, custom-domain and `/openai/v1` endpoints), Requesty, the generic
  `openai-compatible` provider (Groq, plus a keyless local run), Voyage embeddings and Vertex AI, and Mistral dialect checks [#1213](https://github.com/llm4s/llm4s/issues/1213).
- **Changed** CI runs the `@Workspace` tier on PRs that change the workspace, and All Tests Pass requires it there; a manual
  run picks its tiers with `-f tier=workspace|mcp-reference|cloud-smoke|all|none` [#1982](https://github.com/llm4s/llm4s/issues/1982).
- **Changed** every artifact to JDK 21 bytecode (class-file version 65; Scala `-java-output-version 21`, `javac --release 21`,
  Kotlin and the Gradle sample `21`, from 52/17), so JDK 21, already the minimum, is enforced at load time; `scripts/check-doc-support.sh`
  checks the targets and docs agree [#1493](https://github.com/llm4s/llm4s/issues/1493); [supported JDKs](docs/reference/v1-scope.md#scala-and-jdk-support).
- **Added** `llm4s-doctor`: the doctor, published and run from your own project (`sbt "Test/runMain org.llm4s.doctor.DoctorCli"`,
  `cs launch`, Maven, Gradle) or code (`Doctor.run`) [#2283](https://github.com/llm4s/llm4s/issues/2283); [Doctor](docs/reference/doctor.md#running-it), [migration](docs/reference/migration.md#the-doctor-is-llm4s-doctor-2283).
- **Added** `llm4s-repl` (experimental): `ask`, `extract`, `chat()`, `history` and `lastUsage` behind one import for the REPL, worksheets and scripts, with an offline `Session` that needs no key and `Session.ollama(model)` for a local server
  [#2342](https://github.com/llm4s/llm4s/issues/2342); [Try llm4s in the REPL](docs/getting-started/repl.md), [migration](docs/reference/migration.md#llm4s-repl-a-new-module-2342).

## [0.5.1] - 2026-10-15

### Breaking changes

- `build()` refuses a `SemanticMemoryMiddleware` inside guardrail middleware (`AgentMiddleware.placement`);
  [migration](docs/reference/migration.md#harness-profile-and-middleware-placement-1915).
- `UserMessage`/`ToolMessage` carry `parts` and have no extractor; `ToolOutcome.Success` takes a `ToolContent`;
  [migration](docs/reference/migration.md#multimodal-content-parts-1913).

### Agent harness (Phase 3)

- **Added** the harness profile: `HarnessProfile` (`deepAgents` preset, `HarnessConfigLoader`) installed by `AgentBuilder.withHarness`, and
  `RunConfig.withRetention`, and middleware ordering checked at `build()` (`AgentMiddleware.placement`) [#1915](https://github.com/llm4s/llm4s/issues/1915); [design §4.37](docs/design/typed-agent-runtime/4.37-stage-3-slice-p1-harness-profile.md), [guide](docs/guide/agents/index.md#harness-profile).
- **Added** instruction memory: `InstructionMemoryMiddleware` shows `AGENTS.md` files read through a `FileBackend`
  [#1910](https://github.com/llm4s/llm4s/issues/1910); [design §4.36](docs/design/typed-agent-runtime/4.36-stage-3-slice-f6-instruction-memory.md), [guide](docs/guide/agents/index.md#instruction-memory).
- **Added** Agent Skills: `SkillsMiddleware` lists `SKILL.md` skills found through a `FileBackend`
  [#1909](https://github.com/llm4s/llm4s/issues/1909); [design §4.35](docs/design/typed-agent-runtime/4.35-stage-3-slice-f5-skills.md), [guide](docs/guide/agents/index.md#skills).
- **Added** context management: `SummarizationMiddleware` (a kept, bounded summary in place of older messages) and
  `ToolOutputOffload` (large results to a read-only `OffloadBackend`, every tool result sent capped)
  [#1908](https://github.com/llm4s/llm4s/issues/1908); [design §4.34](docs/design/typed-agent-runtime/4.34-stage-3-slice-f4-context-management.md).
- **Added** `FileTools(backend)`: `ls`, `read_file`, `write_file`, `edit_file`, `delete`, `glob` and `grep` over any `FileBackend`, bounded by `FileToolLimits`, and `FileToolsContract`
  [#1905](https://github.com/llm4s/llm4s/issues/1905); [design §4.32](docs/design/typed-agent-runtime/4.32-stage-3-slice-f1-filesystem-tools.md).
- **Added** multimodal content parts: user messages and tool results carry images and documents, which each provider
  sends or refuses before sending [#1913](https://github.com/llm4s/llm4s/issues/1913); [design 4.27](docs/design/typed-agent-runtime/4.27-stage-3-slice-f8b-multimodal-content-parts.md).
- **Added** backend SPIs: `FileBackend` (state, composite, store), `SandboxBackend`, `LongTermStore` and `FileBackendContract`
  [#1904](https://github.com/llm4s/llm4s/issues/1904); [design §4.25](docs/design/typed-agent-runtime/4.25-stage-3-slice-c3-backend-spis.md).

### Build & tooling

- **Changed** the release's version check strips terminal escapes. `llm4s-kotlin-api` still did not ship in 0.5.1 (the
  check missed sbt's tab-indented version), so `llm4s-bom` 0.5.1's Kotlin entry does not resolve either.

## [0.5.0] - 2026-10-15

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

### Capabilities

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
  [#1932](https://github.com/llm4s/llm4s/issues/1932).
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
- **Added** durable runs: run claims with fencing tokens and tenant isolation, store calls bounded by the store
  (a stuck closing commit ends the run `CommitTimedOut`), and `CheckpointerContract` in the new
  `llm4s-agent-testkit` [#1699](https://github.com/llm4s/llm4s/issues/1699)
  [#1700](https://github.com/llm4s/llm4s/issues/1700) [#1918](https://github.com/llm4s/llm4s/issues/1918)
  [#1919](https://github.com/llm4s/llm4s/issues/1919) [#1926](https://github.com/llm4s/llm4s/issues/1926); [design
  §4.16](docs/design/typed-agent-runtime/4.16-stage-2-slice-1-the-durable-checkpointer-contract.md),
  [guide](docs/guide/agents/durable-checkpointers.md).
- **Added** `llm4s-agent-checkpoint-sqlite`: `SqliteCheckpointer`, so a run survives a restart and `recover` continues
  it [#1701](https://github.com/llm4s/llm4s/issues/1701) [#1951](https://github.com/llm4s/llm4s/issues/1951)
  [#1919](https://github.com/llm4s/llm4s/issues/1919); [design
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
- **Added** MiMa wiring on the frozen modules (0.5.0 sets no baseline), a MiMa dry run, `sbt stabilityTierCheck`, `frozenDependencyCheck` and
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

[Unreleased]: https://github.com/llm4s/llm4s/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/llm4s/llm4s/compare/v0.7.0...v1.0.0
[0.7.0]: https://github.com/llm4s/llm4s/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/llm4s/llm4s/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/llm4s/llm4s/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/llm4s/llm4s/compare/v0.4.1...v0.5.0
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
