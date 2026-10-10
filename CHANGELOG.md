# Changelog

All notable changes to llm4s are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **Java completions with usage, model and tool calls, and a typed error kind**
  ([#1487](https://github.com/llm4s/llm4s/issues/1487)): `llm4s-java-api`'s `JLlmClient.completion(String)`,
  `completion(Conversation)` and `completion(Conversation, JCompletionOptions)` return an
  `LlmResult<JCompletion>`: the reply's `content()`, the `model()` that answered, its `toolCalls()` as the
  `JToolCall`s the agent's messages already use, `usage()` as an `Optional<JTokenUsage>` (`promptTokens()`,
  `completionTokens()`, `totalTokens()`, `thinkingTokens()`, and the prompt-cache counts `cachedTokens()` and
  `cacheCreationTokens()`, each `int` and zero where the provider reports none), `estimatedCost()` as an `Optional<BigDecimal>` (the
  figure an agent turn adds to `JUsageSummary.totalCost()`), `thinking()` and `id()`. The `complete` methods keep
  returning the text. There is one options overload of `completion`, so a literal `null` options compiles and
  fails as a result. `LlmException` gains `getKind()`, the Java enum `LlmErrorKind` (`AUTHENTICATION`,
  `RATE_LIMIT`, `TIMEOUT`, `NETWORK`, `SERVICE`, `VALIDATION`, `CONFIGURATION`, `CANCELLED`, `OTHER`),
  `isRecoverable()`, `getRetryAfter()` as an `Optional<Duration>` and `getStatusCode()` as an `OptionalInt`. A
  provider's `400`, `401`/`403` or `429` reported as a `ServiceError` or `APIError` reads as `VALIDATION`,
  `AUTHENTICATION` or `RATE_LIMIT` and stays recoverable, as its class is: always call `isRecoverable()` rather
  than infer it from the kind. Every concrete error class in `org.llm4s.error` has a kind, and
  `LlmErrorKindSpec` scans the package and fails when a new one has none. `JCompletionOptions.toString` now prints
  an unset value as `unset`, so an unset reasoning level no longer reads like `NONE`. The Java guide has
  [The whole reply](docs/guide/java.md#the-whole-reply) and a rewritten
  [Handling a failure](docs/guide/java.md#handling-a-failure) section.
- **Completion options from Java** ([#1488](https://github.com/llm4s/llm4s/issues/1488)): `llm4s-java-api`'s
  `JCompletionOptions.builder()` sets `temperature`, `topP`, `maxTokens`, `presencePenalty`, `frequencyPenalty`,
  `reasoning` (the Java enum `JReasoningEffort`: `NONE`, `LOW`, `MEDIUM`, `HIGH`) and `budgetTokens`, and
  `JLlmClient.complete(Conversation, JCompletionOptions)` sends them. A value that may be absent is read as an
  `Optional` / `OptionalInt` and can be cleared with an empty one, so no `scala.Option` is involved. The builder is
  immutable, `toBuilder()` starts one from existing options, and a value no provider accepts (a negative or
  non-finite temperature, a top-p outside `0..1`, a token count below 1) throws `IllegalArgumentException` when it
  is set. The options map onto core's `CompletionOptions` through its `apply` and `with*` methods only. The
  `complete(Conversation, CompletionOptions)` overload stays. The Java guide has a
  [Completion options](docs/guide/java.md#completion-options) section, and the `gradle-java` sample uses the builder.
- **Cookbook recipe: several agents in one graph** ([#1330](https://github.com/llm4s/llm4s/issues/1330)):
  `MultiAgentGraphRecipe` runs two specialist agents in one superstep and an editor agent behind a static join,
  and its spec checks update order, the barrier, step boundaries and cancellation with no API key.
- **Suspended agent turns from Java and Kotlin** ([#1392](https://github.com/llm4s/llm4s/issues/1392)):
  `llm4s-java-api`'s `JAgent.pending(result)` returns a `java.util.List<PendingInterrupt>`. For a
  `Suspended` turn it lists the approvals, then the questions. For any other turn the list is empty.
  Each `PendingInterrupt` has `id()`, `kind()` (the Java enum `InterruptKind`, `APPROVAL` or `QUESTION`),
  `toolName()`, `argumentsJson()`, and `reason()` or `questionJson()` as an `Optional<String>`. No Scala
  or ujson type is involved. `JAgent.resume(threadId, List<Answer>)` and `JAgent.recover(threadId)` are
  blocking versions of `streamResume` and `streamRecover`. They return `LlmResult<JAgentResult>` (#1393); an
  interrupted caller gets a `CancelledError` and the turn is cancelled, leaving the thread for `recover`
  (#1330). A partial resume returns `Suspended` again, with the unanswered
  items still pending. The Kotlin API adds `AgentKt.pending(result)` and the `suspend` functions
  `resume(threadId, answers)` and `recover(threadId)`. These run the turn as the streams do, so
  cancelling the caller cancels the turn and leaves the thread for `recover`. The Java sample approves
  what a suspended turn waits for. See
  [Suspended turns from Java and Kotlin](docs/guide/agents/index.md#suspended-turns-from-java-and-kotlin).
- **Thinking stays in the conversation and goes back to the provider** ([#1381](https://github.com/llm4s/llm4s/issues/1381)):
  `AssistantMessage` carries the model's reasoning as `thinking: Seq[ThinkingBlock]` - `ThinkingBlock.Text(text,
  signature)`, `ThinkingBlock.Redacted(data)` or `ThinkingBlock.Opaque(provider, data)` (provider-specific replay
  data only that provider's client sends back), `@Stable` - with `withThinking(blocks)` /
  `withThinking(text)`, `thinkingText` and `hasThinking`; its codec writes `thinking` only when present and reads
  JSON without it as none, so stored conversations and agent checkpoints still load. Clients put the thinking on the
  message they return, streamed or not: Anthropic and Bedrock as blocks with their signatures and redacted thinking,
  Ollama, DeepSeek, Z.ai (newly read from `reasoning_content`), OpenRouter and Mistral as text, and OpenRouter's
  `reasoning_details` (whole or streamed, joined by `index`) as one opaque block per item. They send it back
  where the provider takes it: Anthropic and Bedrock replay signed and redacted blocks first in the assistant turn
  (unsigned thinking is left out), Ollama as `thinking`, DeepSeek and Z.ai as `reasoning_content`, OpenRouter as
  `reasoning` plus its `reasoning_details` unchanged, Mistral as a thinking chunk; new `OpenAICompatibleDialect` hooks
  decide - `encodeThinking` (given the turn's blocks), `thinkingDetails` and `decodeThinkingDetails` - and drop it by
  default. The agent's tool loop stores the completion's message unchanged, so a run sends a tool-call turn's
  thinking in the call after the tool results, and later turns read it back from the checkpoint.
- **Agent event streams for Java and Kotlin** ([#1377](https://github.com/llm4s/llm4s/issues/1377)):
  `llm4s-java-api`'s `JAgent.stream(threadId, query, listener)`, `streamResume(threadId, answers, listener)`
  and `streamRecover(threadId, listener)` return an `LlmResult<AgentStream>` at once and hand the turn's events
  to an `AgentStreamListener` - `onEvent` for each, then one of `onComplete(AgentResult)` or
  `onError(LlmException)` - on the stream's own thread. Only `onEvent` is abstract, so a lambda is a listener.
  `AgentStream.await()` returns the outcome once the listener has returned from its last call; `cancel()`
  cancels the turn and returns once it has ended. Resume answers are `Answer.approve(id)`, `reject(id, reason)`,
  `edit(id, argumentsJson)` and `reply(id, json)`. `StreamEvents.decode(eventType, event)` reads an event as an
  `Optional`. `Llm4s.createAgent(client, tools, streaming)` builds an agent whose model calls stream, so its
  turns carry text deltas, and `Llm4s.wrapAgent(agent)` puts an agent built with `Agent.builder` behind the
  facade. The Kotlin API's `AgentKt.stream`, `streamResume` (a `List<Answer>`) and `streamRecover` are cold
  `Flow<AgentStreamItem>`s (`Event(event)`, then `Done(result)`); cancelling the collection (its scope,
  `take(n)`, a timeout) cancels the turn, a turn cancelled otherwise fails it with `LLMException`, and Kotlin's
  `Llm4s.createAgent(client, tools, streaming)` and `wrapAgent` match Java's. As in the fs2 and ZIO streams, a consumer too slow for the stream's 256-event buffer loses live
  events and receives a `StreamEvent.LiveGap` with their count - it never cancels the run - and a run that
  ends without a terminal event still ends the stream with its error. The Java sample streams a turn.
- **`llm4s-speech`: opt-in MP3 output for cloud TTS** ([#1307](https://github.com/llm4s/llm4s/issues/1307)):
  `TTSOptions(outputFormat = AudioFormat.Mp3)` makes the OpenAI, ElevenLabs and Azure clients request the
  service's MP3 and return its bytes untouched. PCM stays the default. `AudioFormat.Mp3` is a new case
  (`llm4s-speech` is Experimental), `GeneratedAudio.isPcm` / `requirePcm`, and `AudioIO.saveMp3`. WAV
  writing, `AudioIO.saveWav` / `saveRawPcm16` and the new `AudioPreprocessing.standardizeForSTT(audio, rate)`
  reject MP3 with a `ValidationError`; Tacotron2 refuses it. A caller with an exhaustive `match` on
  `AudioFormat` needs a case for `Mp3`. The `@Cloud` smoke suites check MP3 magic bytes.
- **`llm4s-java-api`: Java interop module** (Beta, `modules/java-api`, package `org.llm4s.javaapi`,
  [#934](https://github.com/llm4s/llm4s/issues/934)): a facade for Java callers over the client and agent API. `Llm4s.createDefaultClient()`
  and `createClient(config)` return an `LlmResult<JLlmClient>`; `JLlmClient` (`AutoCloseable`) offers
  `complete(...)`, `JAgent` offers `run(...)`, and `ConversationBuilder` builds a conversation. Client and
  agent calls do not throw: a failure comes back inside the `LlmResult`, which has `isSuccess()`,
  `get()` (throws the `LlmException` on failure), `getOrNull()`, `getError()`, `toOptional()`, `map`,
  `ifSuccess` / `ifFailure` and `toCompletableFuture()` (an adapter over an already finished result, not
  an asynchronous call). It depends on core, `llm4s-agent` and the OpenAI, Anthropic, Ollama, Gemini
  and OpenAI-compatible provider modules.
- **`llm4s-ollama`: tool calling in the native client** ([#1219](https://github.com/llm4s/llm4s/issues/1219)):
  `OllamaClient` sends `CompletionOptions.tools` as `/api/chat`'s `tools`, reads `message.tool_calls` - whole
  or streamed - into `ToolCall`s, and sends a `ToolMessage` as `role: tool` (with `tool_call_id` and `tool_name`) instead of
  dropping it; an assistant turn's tool calls go back with their ids, consecutive `function.index` values and object
  arguments, as Ollama's native history records them. Ollama sends no call ids, so
  the client synthesizes unique ones (`call_<12 hex>_<index>`); an id the server sends is kept. A
  malformed `tool_calls` entry is a `ProcessingError`. Agents on `provider = "ollama"` can now run tools; the
  `openai-compatible` `/v1` route remains an alternative. **Behaviour change:** tools are now sent, so a model
  without the *tools* capability (such as `llama3:latest`, the samples' default) fails the request: Ollama's
  HTTP 400 `... does not support tools` is reported as a `ValidationError` on `tools` naming the model. Use a
  tool-capable model (for example `llama3.1`) or send no tools; the request is not retried without them.
- **config-policy: per-provider pins, anchored patterns, caps that fit current models**
  ([#1220](https://github.com/llm4s/llm4s/issues/1220)): `ConfigPolicy.withRequiredBaseUrlPattern(env, provider, pattern)` and
  `withMaxContextWindow(env, provider, max)` take precedence over the environment-wide value. Model and base-URL patterns now
  must match the **whole** value (previously a substring match, so `openai/gpt-4o` also allowed `gpt-4o-mini` and a lookalike
  host passed a URL pin). **Migration:** a pattern that relied on a prefix needs a suffix: end a base-URL pin with `/.*`, never a
  bare `.*` (`https://api\.openai\.com.*` still accepts `https://api.openai.com.evil.example/v1`,
  `https://api.openai.com:x@evil.example/` and `https://api.openai.com./v1`; `https://api\.openai\.com/.*` rejects all three), and
  note that the `prod` preset's model patterns are now exact, so `openai/gpt-4o` no longer allows dated snapshots such as
  `gpt-4o-2024-08-06` (write `openai/gpt-4o(-.*)?`). The `prod` preset caps each provider at its current models' window
  (anthropic 200000, gemini 1048576, deepseek 131072, openai/azure 128000) and keeps an environment-wide fallback of 1048576 for a
  provider with no entry of its own (one opted in with `withAllowedProviders`), so allowed models are not rejected for their
  native window and no provider is uncapped; `dev` caps at 1048576. Provider names in a policy are canonicalised like provider ids
  (`Locale.ROOT`, aliases such as `google` folded onto `gemini`), and a per-provider cap or pin naming no registered or allowed
  provider is an `unknownProvider` violation instead of being silently ignored. A per-provider pin replaces the environment-wide
  one for that provider, so a loose provider pin weakens a strict global one.
- **`llm4s-spring-boot-starter`: Spring Boot auto-configuration** (Beta, `modules/spring-boot-starter`,
  [#936](https://github.com/llm4s/llm4s/issues/936)): built on `llm4s-java-api`. Properties under `llm4s.*` (`provider`, `model`, `apiKey`,
  `baseUrl`, `organization`, `contextWindow`, `reserveCompletion`) produce a `JLlmClient` and an
  `LLM4STemplate` with `complete`, `tryComplete` and a truly asynchronous `completeAsync` (a
  `CompletableFuture` on the `llm4sTaskExecutor` bean, sized by `llm4s.async.maxThreads` and
  `queueCapacity`; it is shut down with `shutdownNow`, which interrupts calls still running, and an
  `ExecutorService` bean named `llm4sTaskExecutor` of your own replaces it, as a `JLlmClient` or
  `LLM4STemplate` bean of your own replaces those). `llm4s.enabled=false` switches the starter off.
  With Spring Boot Actuator present, an `LlmHealthIndicator` reports the client; a real one-token provider
  call per probe is opt-in (`llm4s.health.probe`, off by default because it bills; result reused for
  `probeTtl`, 60 s, and cancelled after `probeTimeout`, 10 s).
- **Kotlin coroutine API** (Experimental, `modules/kotlin-api`, [#937](https://github.com/llm4s/llm4s/issues/937)): `LLMClientKt` and `AgentKt`
  over `llm4s-java-api` with `suspend` functions and `Flow`, built from `Llm4s`. Cancelling a coroutine or
  a `Flow` collector interrupts the blocking provider call. It is a separate Gradle build (`gradle check`,
  JaCoCo coverage check, the `Kotlin API` CI job) that consumes `llm4s-java-api` from local Maven, **not
  part of the sbt build or the MiMa baseline and not yet published to Maven Central**.
- **`llm4s-effect` and `llm4s-zio`: cats-effect and ZIO integration** (Beta, `modules/llm4s-effect`,
  `modules/llm4s-zio`, [#935](https://github.com/llm4s/llm4s/issues/935)): `LLMClientIO[F]` and `AgentIO[F]` (package
  `org.llm4s.effect.cats`, cats-effect 3 and fs2) and `LLMClientZ` and `AgentZ` (package `org.llm4s.zio`,
  ZIO 2 and ZIO Streams) wrap `LLMClient` and `Agent`. Streaming is incremental through a bounded queue
  with backpressure, calls are interruptible and cancelling interrupts the provider call, the ZIO client
  acquires its resources inside `acquireRelease` and leaks no fiber on cancel, and a failure is an
  `LLMException` carrying the `LLMError`. Coverage floors are 65 for each (measured 65.22% and 65.91%).
- **`LLMClient.completeStructured[A]` and `LLMClient.extractJson`** ([#932](https://github.com/llm4s/llm4s/issues/932), https://github.com/llm4s/llm4s/pull/977): sends a
  `ResponseFormat.JsonSchema`, parses the reply into `A`, and returns a `ValidationError` for bad JSON, a
  null reply or a schema mismatch; `extractJson` strips markdown fences and prose and recovers the first
  balanced parseable JSON block. OpenAI, Gemini and Vertex AI, and the OpenAI-compatible providers
  constrain the schema natively. Ollama sends its native `format` field (see the entry below).
  **Anthropic has no native structured output here: it falls back to a prompt-level instruction that is
  best effort and NOT schema-enforced, and that fallback has not been run against real Claude.**
- **Gemini vision client** (Experimental, `llm4s-image`, [#1005](https://github.com/llm4s/llm4s/issues/1005)): `GeminiVisionClient`,
  `GeminiRequestBody`, `GeminiVisionConfig` and `ImageProcessing.geminiVisionClient`, an
  `ImageProcessingClient` like the OpenAI and Anthropic ones. The API key travels in the
  `x-goog-api-key` header and is redacted everywhere; an interrupt returns `CancelledError`; a blocked
  prompt or an empty or non-text reply is an error naming `blockReason` or `finishReason`; HTTP errors
  keep their status code. **The default model, `gemini-3.6-flash`, was chosen from Google's deprecation
  notes and has not been verified against the live API** (`gemini-2.0-flash` was shut down on 2026-06-01);
  set `model` explicitly if it is not available to you. `llm4s-image`'s coverage floor is now 75.
- **Agent middleware for graph runs** (Experimental, `org.llm4s.agent.graph.middleware`,
  [#1279](https://github.com/llm4s/llm4s/issues/1279)): `AgentMiddleware` is one ordered extension
  point with four pass-through hooks - `beforeAgent`, `afterAgent`, `wrapModelCall` and
  `wrapToolCall` - plus the `tools` it contributes and the state keys (`writes`) its tool wrapper
  may add. `MiddlewareStack.of` orders middleware by `runsBefore`/`runsAfter` (ties by
  registration) and reports every invalid or duplicate id, unknown constraint, cycle and clashing
  contributed tool in one `ValidationError`. `ToolLoop.build(..., middleware)` runs `beforeAgent` in
  the `input` node, `wrapModelCall` around each model call, the `wrapToolCall` chain around each
  tool after argument validation, and `afterAgent` in a new `finish` node. `ApprovalMiddleware`
  asks for approval when a function of the call returns a reason (`unlessReadOnly` asks for every
  tool not hinted read-only). `GuardrailMiddleware` runs input and output guardrails at the run
  boundary, each in order on the previous one's returned value, with failures collected and
  reported exactly as `CompositeGuardrail.all` reports them. Unlike the legacy `Agent`, which never
  applied any guardrail's transformation on input or output (so `PIIMasker` masked nothing), the
  middleware applies them; the legacy `Agent` and `GuardrailApplicator` are unchanged.
  `AgentToolSpec.withHints(ToolHints(readOnly, destructive, idempotent, openWorld))` carries
  MCP-style hints with conservative defaults. A cancellation thrown by a tool or a wrapper restores
  the interrupt flag at once, and a wrapper that retries never runs a cancelled tool again. A hook
  that throws fails the run with `GraphError.MiddlewareFailed(middleware, cause)`; a throwing
  `ModelStep` is not reported as a middleware failure. Design:
  `docs/design/typed-agent-runtime-design.md` §4.8.
- **`@Stable` and `@Experimental`: the tier of a public type, in the code** ([#1281](https://github.com/llm4s/llm4s/issues/1281),
  `org.llm4s.annotation` in `llm4s-core`): Java annotations with runtime retention, so an IDE, a tool or a
  Java caller can read them. Every top-level public type of `llm4s-core`, `llm4s-openai`,
  `llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama` is now `@Stable`
  (268 types), except the Mistral and Cohere dialects of `llm4s-openai-compatible`, which 1.0 Scope does
  not freeze and which are `@Experimental` (6). `sbt stabilityTierCheck`, a CI quick check, fails the build
  for a new top-level public type of those modules with neither annotation or both. Adding the
  annotations changes no behaviour and no binary signature. **`llm4s-agent` is not covered yet**: its tier
  waits on the typed graph runtime ([#1266](https://github.com/llm4s/llm4s/issues/1266)). See
  [docs/reference/api-stability.md](docs/reference/api-stability.md#tiers-in-the-code).
- **Provider HTTP timeouts are configurable** ([#712](https://github.com/llm4s/llm4s/issues/712)): a
  provider section, and an embedding section, takes an optional `timeouts` block,
  `timeouts { request = 3m, stream = 15m }`, each value a duration. A value left out keeps that client's own
  default, so **a section without the block behaves exactly as before**. Values must be positive and finite
  and are refused at load, naming the key (`llm4s.providers.<name>.timeouts.request`); a misspelt key inside
  the block is refused too. Every chat client reads it - the OpenAI-compatible family (DeepSeek, Z.ai,
  OpenRouter, Mistral, Cohere and the generic provider), Gemini, Vertex AI, Ollama, and OpenAI, Azure,
  Requesty and Anthropic on their SDKs - and so do the OpenAI, Ollama, Voyage, Jina and Cohere embedding
  providers. The HTTP-based clients bound the wait for the response to begin; the SDK-based clients bound the
  whole call, per call, so a short `request` does not cut a stream. New public types in `llm4s-core`:
  `ProviderTimeouts` (a `FiniteDuration` each for `request` and `stream`, `None` meaning the client's
  default), `NamedProviderConfig.timeouts`, `EmbeddingProviderConfig.timeouts` and
  `EmbeddingProviderSection.timeouts`; `ProviderConfig` gains `timeouts` and `withTimeouts`, both with
  defaults, so a provider supplied by another module compiles unchanged and ignores the block until it
  overrides them (`docs/guide/writing-a-provider.md`). `timeouts` joins `ProviderConfigSpec.BuiltinKeys`, so
  a provider can no longer declare an extra of that name. The provider configs (`DeepSeekConfig`,
  `ZaiConfig`, `MistralConfig`, `CohereConfig`, `OpenAICompatibleConfig`, `OpenAIConfig`, `AzureConfig`,
  `AnthropicConfig`, `GeminiConfig`, `VertexAIConfig`, `OllamaConfig`, `BedrockConfig`, `WatsonXConfig`) and
  `EmbeddingProviderConfig` gain a trailing `timeouts` field and a `withTimeouts` setter. **Migration:** Scala
  source that constructs them or calls `apply` compiles unchanged (the field defaults to
  `ProviderTimeouts.default`), but the constructor's arity changed, so code compiled against the old one must
  be recompiled; Java and Kotlin callers, which cannot use Scala defaults, pass the default timeouts as the last
  argument - `ProviderTimeouts.default()` from Kotlin, `ProviderTimeouts.apply(Option.empty(), Option.empty())`
  from Java, where `default` is a keyword; a
  pattern match on one needs the extra field. No overload keeps the old arity: nothing is frozen before 1.0
  (see `docs/migrations/0x-to-1x.md`). The OpenAI and Anthropic SDKs retry a timed-out call twice by default, which llm4s does
  not change. Model listing, the Vertex AI token request and the watsonx IAM exchange keep their fixed
  timeouts. Bedrock applies `request` as the AWS SDK's API-call timeout and `stream` as a deadline on the
  whole `ConverseStream` call; an expiry there is a `TimeoutError`.
- **Agent tool contract for graph runs** (Experimental, `org.llm4s.agent.graph.tool`,
  [#1278](https://github.com/llm4s/llm4s/issues/1278)): `AgentTool[A]` and `AgentToolSpec[A]`
  replace the prototype `LoopTool`. A tool's arguments are typed by a core `SchemaDefinition[A]`
  and a `ReadWriter[A]`; `ToolLoop` validates the raw arguments against the spec's
  `argumentSchema` (the non-strict rendering, so a call may omit an optional field), decodes them
  and runs the spec's optional `withValidation` check, and any failure is an error result the
  model sees, before the policy or the tool runs. A tool receives a
  `ToolContext` (run, call id, thread state, `approved`) and returns `ToolOutcome.Success(content,
  update)` - the update limited to the keys it declares in `writes` - `Error`, `NeedsApproval`,
  `Ask` or `Fatal`; it does not route. `ToolArgumentValidator.default` checks exactly the JSON
  Schema subset core emits, and `ToolSet.of` refuses invalid or duplicate names, a non-object
  argument schema and any schema keyword the validator cannot check (unknown, or with a malformed
  value), in one `ValidationError`, when the set is built. A tool that extends
  `AgentTool.Asking[A, Q, Ans]` asks typed questions: `Ask(q)` suspends the call at an
  `ask/<tool>` resume node, `ToolLoop.questions`, `ToolLoop.question[Q]` and `ToolLoop.answer`
  find, read and answer them, and the tool continues in `resume`. `Fatal`, an update to an undeclared key and an undeclared or
  mistyped question fail the run with `GraphError.ToolFailed` (inside `NodeFailed`), leaving it
  recoverable; a thrown exception is an error result; a cancellation cancels the run. Edited
  approval arguments are validated again, and only a policy `Deny` refuses them.
  `AgentTool.fromToolFunction` adapts a core `ToolFunction`, and `ToolSet.toolFunctions` is what
  `ModelStep.fromClient` sends as `CompletionOptions.tools`. Legacy handoffs (`org.llm4s.agent`)
  have explicit, stable ids:
  the tool for `Handoff.to("physics", agent)` is `handoff_to_physics`, invalid
  (`[a-zA-Z0-9_-]{1,52}`) or duplicate ids fail a run before any model call, and `detectHandoff`
  matches the exact id. A stop's interrupt can no longer land on a run's closing commit: the run
  acknowledges its stop before clearing its interrupt flag, and `stop` interrupts only before
  that. Migration: `LoopTool` -> `AgentTool[A]` (`AgentTool(spec)((args, context) => ...)`) and
  `LoopTool.fromToolFunction` -> `AgentTool.fromToolFunction`; `toolloop.ToolOutcome`
  (`Completed`, `Failed`, `NeedsApproval`) -> `tool.ToolOutcome` (`Success`, `Error`,
  `NeedsApproval`, `Ask`, `Fatal`); `ToolLoop.build(..., tools: Seq[LoopTool], ...)` ->
  `ToolLoop.build(..., tools: ToolSet, ...)`, which refuses a tool that declares the loop's results
  or messages key; `ModelStep.next(messages)` -> `next(messages, tools)`, and
  `ModelStep.fromClient` replaces `options.tools` with the tool set's; `Handoff(agent, ...)` /
  `Handoff.to(agent, ...)` -> `Handoff(id, agent, ...)` / `Handoff.to(id, agent, ...)` (or
  `Handoff.of(id, agent, reason)` for a `Result`), and `handoffId` is `handoff_to_<id>` rather
  than `handoff_to_agent_<hash>`. Design: `docs/design/typed-agent-runtime-design.md` §4.7, with
  the Stage 0 carry-forward in §4.8.
- **RAG chunking and fusion: what is validated, and how weighted scores are combined**
  ([#1318](https://github.com/llm4s/llm4s/issues/1318), items 4 to 6; the validation shipped in #1358, and the one
  fix this found is under Fixed): `docs/guide/vector-store.md` now states that an invalid `ChunkingConfig` or `WeightedScore` throws
  `IllegalArgumentException` (decided, because the `RAGConfig` builders cannot return a `Left`), how to turn user
  input into a `Left` (`ChunkingUtils.chunkTextValidated`, or `Try(...)` through `toResult`), that every
  configuration that can be built is safe for every chunker, and that `WeightedScore` rescales each channel so its
  weakest hit scores `0.1`, not the `0.0` of a miss. The snippets are compiled and run by `RagValidationGuideSpec`.
  New property tests pin the guarantees: `ScoreNormalisationSpec` (the floor and the best hit, nothing scores like a
  miss, the channel's order is kept, scale and offset do not matter) and `ChunkersAcceptValidConfigsSpec` (no
  chunker throws for any valid config, and chunk indices run from 0 without gaps, which re-ingest relies on).
- **Compatibility and Deprecation Policy** ([docs/reference/compatibility-policy.md](docs/reference/compatibility-policy.md),
  [#1281](https://github.com/llm4s/llm4s/issues/1281)): one page for what you can rely on when you upgrade, by
  tier; how versions are read (`early-semver`, 0.5.0 as the MiMa baseline); what the promise covers (public
  types, the provider-author SPI, the error model, documented configuration keys) and what it does not; the
  rules for changing a Frozen API without breaking it; and how an API is deprecated (`@deprecated` with the
  replacement and the release, a CHANGELOG entry) and removed (never within a major version). It collects
  what `1.0 Scope`, `API Stability`, the provider guide and `CLAUDE.md` already said, and links to each.
- **Gemini and Vertex AI keep and replay thought signatures** ([#1389](https://github.com/llm4s/llm4s/issues/1389)):
  a thinking Gemini model attaches an opaque `thoughtSignature` to a part of its turn (the `functionCall`
  part when it calls a function, sometimes the last text part) and expects it back on the same part; Gemini 3
  models answer HTTP 400 when a required signature is missing, so thinking context used to be lost, or the
  request failed, across tool calls. Each signature is now kept on `AssistantMessage.thinking` as a sealed
  `ThinkingBlock.Opaque("gemini" | "vertexai", ...)`, bound to the provider and model that produced it
  alone: Google asks for signatures to be preserved when history is modified or trimmed (the reverse of
  Anthropic's prefix rule), so pruning or compressing earlier turns leaves them in place, and only a change
  of provider or model, or an edit to the carrying message itself, unseals one. A function call's signature is sent on that call's part (on the
  first call only, for parallel calls, as Gemini returns it); a text part's signature is sent with the text
  split where it sat. The Gemini API and Vertex AI are separate signing authorities, so a conversation moved
  from one to the other keeps its text and drops the signatures. The sources, the Anthropic-to-Bedrock failover
  finding (left sealed per provider, with the reasons) and the remaining limits are in
  [Thinking in conversation history](docs/guide/providers.md#thinking-in-conversation-history).
  Behaviour changes in the same clients:
  - a stream chunk holding several function calls now yields all of them (only the first was kept);
  - a streamed `Completion` carries its tool calls on `Completion.toolCalls`, as OpenAI's and Ollama's do (they
    were on the message only);
  - a thought-summary part (`"thought": true`) is thinking text and no longer part of the answer;
  - a `functionCall` without `args` is accepted, and a signed call's part is replayed exactly as
    returned (`args` stay absent if they were);
  - a populated `functionCall.id` becomes the tool call's id and is echoed on its `functionResponse`.
- **Ollama honours `responseFormat`** ([#932](https://github.com/llm4s/llm4s/issues/932)):
  `OllamaClient` now sends the `/api/chat` `format` field for streaming and non-streaming requests,
  so `completeStructured` is constrained natively. `ResponseFormat.Json` sends `"format": "json"`
  and `ResponseFormat.JsonSchema` sends the schema object (requires Ollama 0.5 or later); `name` and
  `strict` have no Ollama equivalent and are ignored. Previously the field was silently dropped.
- **`sbt frozenDependencyCheck`: a frozen module may not resolve what the carves moved out**
  ([#1126](https://github.com/llm4s/llm4s/issues/1126), [#1281](https://github.com/llm4s/llm4s/issues/1281)):
  a CI quick check that reads the resolved runtime dependencies, transitive ones included, of `llm4s-core`,
  `llm4s-agent`, `llm4s-openai`, `llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini` and
  `llm4s-ollama`, and fails the build for a document-parsing (Tika, POI, PDFBox, jsoup), speech (Vosk, JNA),
  cloud (AWS SDK, Azure SDK), database (Postgres, SQLite, HikariCP, Neo4j), observability-backend
  (Prometheus, OpenTelemetry) or WebSocket dependency, or for another provider's vendor SDK
  (`com.openai` only in `llm4s-openai`, `com.anthropic` only in `llm4s-anthropic`). It says whether the module
  declares the dependency or reaches it transitively. The programme's definition of done and `CLAUDE.md`
  already required this and nothing enforced it; today every frozen module passes. See
  [docs/reference/api-stability.md](docs/reference/api-stability.md#what-a-frozen-module-may-depend-on).
- **`llm4s-bedrock`: AWS Bedrock chat provider** (`modules/providers/bedrock`,
  [#1008](https://github.com/llm4s/llm4s/issues/1008), rebuilt from #1029 as a `ProviderDescriptor`,
  so `llm4s-core` is untouched and gains no dependency). `provider = "bedrock"` over the Converse API
  and, for `streamComplete`, ConverseStream (true streaming: text, tool-call and reasoning deltas,
  usage). A section needs a `region` (a provider-specific key, never defaulted) and a `model`;
  credentials come from the AWS default credential chain, a `profile`, or explicit
  `accessKeyId` / `secretAccessKey` with a `sessionToken` for temporary credentials; `baseUrl`
  overrides the endpoint. Context windows come from the model registry, including the `us.` / `eu.` /
  `apac.` / `global.` inference-profile ids. `ThrottlingException` and `ServiceQuotaExceededException`
  map to `RateLimitError`, `ValidationException` to `ValidationError`, `AccessDeniedException` (and any
  401/403) to `AuthenticationError`, other service errors to `ServiceError`, a failure to reach
  Bedrock to `NetworkError`; an interrupted call or stream is `CancelledError`. A conversation with only
  a system message is a `ValidationError`, which Converse cannot accept. Add the dependency
  `"org.llm4s" %% "llm4s-bedrock"`, which brings the AWS SDK v2 `bedrockruntime` artifact
  (Apache-2.0, the SDK release train `llm4s-rag` already uses for S3); nothing else changes.
- **Embedding requests say whether the input is a query or a document** ([#1218](https://github.com/llm4s/llm4s/issues/1218)):
  `EmbeddingRequest` has a `purpose`, `InputPurpose.Document` (the default, so existing callers are
  unchanged) or `InputPurpose.Query`. Voyage sends it as `input_type`, Jina as `task`
  (`retrieval.passage` / `retrieval.query`) and Cohere as `input_type` (`search_document` / `search_query`);
  OpenAI and Ollama ignore it. An explicit `JinaTask` or `CohereInputType` passed to `fromConfig` still wins
  over the purpose. `RAG` and `RAGPipeline` embed the question they answer as a query and what they index as
  documents, the memory stores embed the text they search with as a query (`EmbeddingService.embedQuery`,
  which delegates to `embed` by default, so existing implementations are unaffected), and `CachedEmbeddingClient`
  keeps a query and a document with the same text in separate cache entries. **Behaviour changes:** Voyage now sends `input_type` (`document` by
  default; it sent none before), so re-index for the best retrieval quality - older document vectors still
  work; and a Jina or Cohere provider built without an explicit task now follows each request's purpose
  instead of always sending the document type. `EmbeddingRequest` becomes a growth-prone type (private
  constructor and `copy`, `with*` setters, `apply` with defaults): construct it with `EmbeddingRequest(...)`
  and change it with `withInput`, `withModel` or `withPurpose`.
- **`llm4s-jina`: Jina AI embedding provider** (`modules/providers/jina`,
  [#1028](https://github.com/llm4s/llm4s/issues/1028), rebuilt from #1060 as an
  `EmbeddingProviderDescriptor`, so `llm4s-core` is untouched). `EMBEDDING_MODEL=jina/jina-embeddings-v3`
  with `JINA_API_KEY` (bound to `llm4s.credentials.jina.apiKey`), `JINA_EMBEDDING_BASE_URL` (default
  `https://api.jina.ai/v1`) and `JINA_EMBEDDING_MODEL`. The Jina `task` is a typed setting,
  `JinaTask` (`RetrievalQuery`, `RetrievalPassage`, `TextMatching`, `Classification`,
  `Separation`), passed as `JinaEmbeddingProvider.fromConfig(config, task)`; the provider the
  registry builds uses `RetrievalPassage` until an input-purpose parameter lands on the embedding
  request ([#1218](https://github.com/llm4s/llm4s/issues/1218)). Add the dependency
  `"org.llm4s" %% "llm4s-jina"`; nothing else changes. HTTP 401 and 429 map to `EmbeddingError`
  with codes `"401"` and `"429"`.
- **Metrics section in the monitoring guide** ([#700](https://github.com/llm4s/llm4s/issues/700)):
  `docs/guide/observability/index.md` now covers `MetricsCollector` and `llm4s-observability-prometheus`: the
  `llm4s.metrics` block and what `MetricsConfigLoader` returns for each setting, wiring a collector into a
  client, what a call records (the request always; tokens and cost only on success; a stream once, at its end),
  the ten Prometheus series with their labels and a sample scrape, scrape and PromQL examples, `compose`, and
  the limits (retry, circuit-breaker and `ReliableClient` error events are not exported; fixed latency buckets;
  an endpoint with no authentication). `ObservabilityMetricsGuideSpec` runs the configuration block and the
  recording rules and compares the sample scrape, line for line, with what a real `/metrics` endpoint serves.
  The guide's other two pages named in #700, Basic Usage and Providers, and its tracing, Langfuse and
  OpenTelemetry sections were already complete.
- **`llm4s-cohere`: Cohere embedding provider** (`modules/providers/cohere`, rebuilt from #1068 as an
  `EmbeddingProviderDescriptor`, so `llm4s-core` is untouched). `EMBEDDING_MODEL=cohere/embed-english-v3.0`
  with `COHERE_API_KEY` (bound to `llm4s.credentials.cohere.apiKey`, the key `llm4s-openai-compatible`
  and `llm4s-rag` already share), `COHERE_EMBEDDING_BASE_URL` (default `https://api.cohere.com`) and
  `COHERE_EMBEDDING_MODEL`. It is a module of its own because Cohere's native `/v2/embed` (`texts`,
  `input_type`, `embedding_types`, vectors keyed by type) is not OpenAI-compatible; Cohere chat stays a
  dialect in `llm4s-openai-compatible`. The `input_type` is a typed setting, `CohereInputType`
  (`SearchDocument`, `SearchQuery`, `Classification`, `Clustering`), passed as
  `CohereEmbeddingProvider.fromConfig(config, inputType)`; the provider the registry builds uses
  `SearchDocument` until an input-purpose parameter lands on the embedding request
  ([#1218](https://github.com/llm4s/llm4s/issues/1218)). Texts go in requests of at most 96, Cohere's
  limit, and the billed tokens are reported as usage. Add the dependency
  `"org.llm4s" %% "llm4s-cohere"`; nothing else changes. HTTP 429 is a `RateLimitError` carrying
  `Retry-After` when Cohere sends it in seconds; other failures are `EmbeddingError` with the status as
  its code.
- **Cloud speech smoke and integration tests** ([#1011](https://github.com/llm4s/llm4s/issues/1011)):
  `@Cloud` suites in `modules/it` (`org.llm4s.speech`: OpenAI TTS, OpenAI STT, ElevenLabs, Azure
  TTS/STT with a synthesise-then-transcribe round trip), run by `sbt testSmoke` and gated by
  `Tier.require`. TTS output is asserted on structure (24 kHz 16-bit mono PCM, plausible duration,
  audible samples, a WAV header that matches the data), STT on a known spoken phrase. The no-network
  counterpart is `CloudSpeechProviderIntegrationSpec` in `llm4s-speech`.
- **`sbt publishedArtifactsCheck`: every published artifact has a tier and an install line**
  ([#1281](https://github.com/llm4s/llm4s/issues/1281)): a CI quick check that fails when a published
  `llm4s-*` artifact (a project that does not set `publish / skip`) is not named in `docs/reference/v1-scope.md`
  or `docs/getting-started/installation.md`. `sbt ci-release` publishes the root aggregate as tagged and a Maven
  Central release cannot be amended, yet nothing connected what the build publishes to the docs that name it:
  four published artifacts were in neither place. They now are: `llm4s-knowledgegraph-neo4j` and the workspace
  modules (`llm4s-workspace-client`, `llm4s-workspace-shared`) are **Experimental** in 1.0 Scope, and the
  installation guide has lines for `llm4s-knowledgegraph-neo4j`, `llm4s-provider-testkit` and
  `llm4s-workspace-shared`.
- **`Completion.citations`: the sources a model cited** ([#1216](https://github.com/llm4s/llm4s/issues/1216)):
  a new `Citation(url, title, citedText, startIndex, endIndex)` (`@Stable`, growth-prone like `Completion`: private
  constructor, `apply` with defaults, `with*` setters) and `Completion.citations: List[Citation]` with
  `withCitations` and `hasCitations`. Models that search the web by themselves report `url_citation` annotations
  on the message, and the OpenAI client (so Azure and Requesty too, for a deployment that returns them) and the
  shared OpenAI-compatible client (OpenRouter's `:online` models, which also return the source passage as
  `citedText`) now read them, in order. A reply without annotations gives an empty list, and a citation without a
  `url` is dropped rather than made up. Source compatible: `citations` is the last parameter of
  `Completion.apply` and defaults to empty, so existing callers compile unchanged (`.copy` stays private; use
  `withCitations`). Not read, and listed on the issue: streamed chunks (neither provider documents where a stream
  carries them), Anthropic's citations and Gemini's `groundingMetadata` (llm4s cannot yet request documents, web
  search or grounding, so no response can carry them), and Perplexity's top-level `citations` (#1026).
- **Cloud speech providers** (`llm4s-speech`, [#1010](https://github.com/llm4s/llm4s/issues/1010)):
  `OpenAITTSClient`, `ElevenLabsTTSClient`, `AzureTTSClient` (text-to-speech) and `OpenAISTTClient`,
  `AzureSTTClient` (speech-to-text), selected through `SpeechProviderSelector.tts()` / `.stt()` by a
  `provider/model` string (`SPEECH_TTS_MODEL=openai/tts-1`, `SPEECH_STT_MODEL=azure/en-US`).
  Credentials and endpoints come from the new `llm4s.speech` block of this module's `reference.conf`
  (`OPENAI_API_KEY`, `ELEVENLABS_API_KEY`, `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION`) through
  `SpeechConfigLoader`. TTS output is raw 24 kHz 16-bit mono PCM in `GeneratedAudio`, not MP3. HTTP
  failures map as the chat providers' do (`AuthenticationError`, `RateLimitError`, `ValidationError`,
  `ServiceError`). See [docs/guide/speech.md](docs/guide/speech.md#cloud-providers).
- **`scripts/verify-release.sh`: check that a release is on Maven Central** ([#1281](https://github.com/llm4s/llm4s/issues/1281)):
  `scripts/verify-release.sh 0.5.0` asks the build which artifacts it publishes (the new
  `sbt -error listPublishedArtifacts`: 31 artifacts and 5 relocation stubs today) and checks that each
  resolves at that version, the POM and jar for an artifact and a POM carrying a `<relocation>` for a stub,
  exiting non-zero with each miss. The release guide's "Verify Release" step was three links. Against 0.4.0
  it reports all five stubs missing, the known 0.4.0 failure ([#1150](https://github.com/llm4s/llm4s/issues/1150));
  against 0.4.1 it passes.
- **`llm4s-watsonx`: IBM watsonx.ai provider (Beta, built on deprecated endpoints)** ([#1019](https://github.com/llm4s/llm4s/issues/1019)):
  IBM's [February 2026 release notes](https://www.ibm.com/docs/en/software-hub/5.3.x?topic=new-watsonxai)
  deprecate the "Infer text" and "Infer text event stream" endpoints this module uses; it has never
  been run against the live service, its API is not frozen, and tools are unsupported because of the
  endpoint. Migration to the chat API is tracked in [#1314](https://github.com/llm4s/llm4s/issues/1314).
  A new provider module under the id `watsonx` (`WatsonXClient`, `WatsonXConfig`, `WatsonXProvider`,
  `Llm4sWatsonXModule`, declared in `META-INF/services`), so adding the dependency is all it takes
  to use `provider = "watsonx"`; `llm4s-core` is unchanged. It speaks the text-generation API
  (`/ml/v1/text/generation` and `/generation_stream`), which is not OpenAI-compatible, so it is not
  a dialect of `llm4s-openai-compatible`. The IBM Cloud API key is exchanged for an IAM bearer
  token, cached and refreshed five minutes before it expires. A section sets `projectId` (or
  `spaceId`) and optionally `baseUrl` (default `https://us-south.ml.cloud.ibm.com`), `apiVersion`
  and `iamUrl`; `WATSONX_API_KEY` binds to `llm4s.credentials.watsonx.apiKey`. The conversation is
  flattened into one prompt string; tool calling is not supported, so requests that carry tools are
  rejected with a `ValidationError` before any HTTP call. Requests send `stop_sequences` for the role
  markers, a stream that ends without a terminal event or with `error`, `cancelled` or `time_limit`
  is a `Left(ServiceError)`, `baseUrl` and `iamUrl` must be `https` (except localhost), the API key
  is trimmed, and setting both `projectId` and `spaceId` is a configuration error.
- **Run API and event dispatch for graph runs** (Experimental, `org.llm4s.agent.graph`,
  [#1277](https://github.com/llm4s/llm4s/issues/1277)): `GraphRuntime.start`/`recover`/`resume`
  admit a run on the caller's thread and return `Result[RunHandle[O]]` once the thread is claimed;
  the run executes on a runtime-owned virtual thread. `RunHandle` has `await` (retained result; an
  interrupted waiter gets `Left(CancelledError)` and the run continues), non-blocking `status`,
  idempotent `cancel()` and `subscribe(capacity)`, which replays the run from its start. Admission
  never throws: a non-fatal throwable is `Left(GraphError.RunCrashed)`, an interrupt is
  `Left(CancelledError)` with the flag set, and a checkpointer whose commit throws is
  `CheckpointWriteFailed`. `RunConfig` carries the run id, tenant, principal, metadata and
  `RunBudgets` (`maxSupersteps`, `timeout`, `maxConcurrency`, each validated by `apply`, `of` and the
  `with*` setters); a timeout is measured from the claim and ends the run with the recoverable
  `GraphError.DeadlineExceeded` and a new `RunEvent.RunTimedOut`, and the first of cancel and expiry
  wins. Once a run has begun committing its outcome (completed, suspended or failed), a cancel or
  expiry sends no interrupt into that commit and the run ends with its outcome; a cancel or expiry that
  interrupts a superstep's commit ends the run `Cancelled` or `DeadlineExceeded`, even if the store
  reports that commit as failed. `RunContext(config, position)`
  replaces `NodeContext`, with `emit`, `progress` and `isCancelled`; dependencies stay captured by
  node closures. The tenant is recorded on every checkpoint (format 3, with a migration) and a
  mismatch is refused at admission with `GraphError.TenantMismatch(threadId, requested)`, which never
  names the owning tenant, before any status error and in
  place of `ThreadBusy`, so a caller from another tenant learns nothing about the thread; `RunStarted`, `RunRecovered`
  and `RunResumed` record `tenantId` and `principal`. Each subscription has its own ordered
  dispatcher thread and a queue of `capacity` (at least 2) entries: a lagging subscriber is
  disconnected with its last delivered `seq`, dropped live events are reported as `LiveGap(n)`, and
  a throwing listener is disconnected, so a listener never runs on, or holds up, a committing
  thread. `TracingSubscriber.attach` projects durable run events onto core's
  `TraceEvent.CustomEvent` (`graph.run_started`, ...). A subscription belongs to the thread and holds
  its dispatcher until cancelled. Every lock in the runtime is a
  `ReentrantLock`, so none pins a virtual thread's carrier. Migration: `NodeContext` ->
  `RunContext`, and `context.taskId`/`nodeId`/`superstep` -> `context.position.*`;
  `compile(entry, maxSupersteps)` -> `compile(entry)` and `ToolLoop.build` drops `maxSupersteps`
  (limits are `RunBudgets` in `RunConfig`); `CompiledGraph.run(input)` ->
  `GraphRuntime.inMemory().start(threadId, graph, input).flatMap(_.await())`; `step(execution)` ->
  `step(threadId, execution, config)`; `GraphRuntime.start/recover/resume(..., runId, durability)`
  -> `(..., config, durability)`, returning `Result[RunHandle[O]]`, cancelled with
  `handle.cancel()` rather than by interrupting the caller; `recover(graph, threadId, ...)` ->
  `recover(threadId, graph, ...)` and `resume(graph, threadId, answers, ...)` ->
  `resume(threadId, graph, answers, ...)`, matching `start`; `subscribe` gains `capacity`, listeners
  run on a dispatcher thread, a throwing listener is disconnected, and `StreamEvent` gains `LiveGap`
  and `Disconnected`; `GraphError` now extends `LLMError` rather than `NonRecoverableError` - every
  case is still a `NonRecoverableError` except `DeadlineExceeded`, which is a `RecoverableError`;
  new `GraphError` and `RunEvent` cases break exhaustive matches. Design:
  `docs/design/typed-agent-runtime-design.md` §4.6, with the Stage 0 carry-forward in §4.8.
- **CI re-runs a step that failed on a transient download error, and nothing else**:
  `scripts/retry-on-transient-network.sh` wraps the `Check formatting` step (`scalafmt` fetches scalafmt-core at
  run time) and the MiMa step. It re-runs the command at most twice (`RETRY_MAX`, capped at 5; waits of 15 s and
  30 s, `RETRY_BACKOFF_SECONDS`, capped at 120 s), and only when an `[error]` line carries a network marker
  (`failed to download [`, `Connection reset`, `Read timed out`, `UnknownHostException`, ...). A formatting error,
  a failing test, a compile error, a missing dependency or a marker on any other line is never retried, and the
  last attempt's own exit status is the step's, so the wrapper cannot turn a failure into a pass. Over three days
  (300 runs) 2 of 267 `Quick Checks` runs failed this way, both with a warm 450 MB sbt cache restored, plus one MiMa
  run; each needed a manual re-run. `scripts/test-retry-on-transient-network.sh`, run in `quick-checks`, covers the
  retry bounds and every case that must not be retried against a fake command, with no network.
- **CI verifies the documented support matrix** ([#967](https://github.com/llm4s/llm4s/issues/967)):
  `scripts/check-doc-support.sh`, in the `quick-checks` job, fails when the docs say something the build does not
  do. The build's side comes from sbt itself: a new `dumpBuildModel <file>` command (`project/BuildModel.scala`)
  writes the loaded build as JSON - projects, base directories, aggregates, configurations and every defined key,
  commands, aliases with their bodies and Scala versions. The script checks that `Scala N` in the docs agrees with
  the build's `scalaVersion` (a version named only to say it is unsupported or deferred is allowed), that no doc
  claims cross-building the build does not do, that every `JDK N` is one `ci.yml` runs and a documented floor
  (`JDK N+`, `JDK N or newer`, `requires JDK N`) is the oldest of them, that the modules in CLAUDE.md's
  repository-structure block and the projects' base directories match in both directions, and it replays every
  `sbt` command quoted in the docs (and every alias body) against the model - `project X` switches persist and
  keys resolve through configuration, `ThisBuild`/`Global` delegation and aggregation. Prose is matched with
  simple patterns; the script header lists what is deliberately not checked (version lists, ranges and
  ceilings, sbt behind wrappers or in YAML block scalars, `set`/`eval` expressions). A line can opt out with
  `doc-support: ignore`. `scripts/test-check-doc-support.sh` runs each check against a fixture model, with no
  sbt. Three stale claims it found are fixed: `sbt dependencyCheck` (no such task) in the review guidelines,
  `sbt run "Explain ..."` in the g8 guide (sbt reads the quoted text as a second command; it is now
  `sbt "run Explain ..."`), and `modules/gradle-demo`, which CLAUDE.md did not name.
- **CI keeps the documented stability tiers in step with the build** ([#1281](https://github.com/llm4s/llm4s/issues/1281)):
  `scripts/check-tier-drift.sh`, in the `quick-checks` job and with no sbt, fails when the tiers the docs state
  disagree with the code. `publishedArtifactsCheck` only needed a published artifact to be *mentioned* in
  `docs/reference/v1-scope.md`; nothing read the tier a page gave, nothing noticed a page naming a module the
  build no longer has, and the frozen set (the `mimaFrozen` call sites in `build.sbt`) was repeated by hand in
  `stabilityTierModules`, in `frozenDependencyCheck`'s module list, in 1.0 Scope's Package Map, in the
  compatibility policy and in two places in the API stability page. The script compares all of them: a frozen
  module needs a Package Map row marked *Frozen at 1.0* and a module that is not frozen must have none, every
  `llm4s-*` the Package Map names is a project in the build, every published artifact has a row in the table, and
  the module lists agree with the frozen set (`stabilityTierModules` is the frozen set minus the module the
  comment above `stabilityTierCheck` exempts). A page it cannot read fails the check instead of passing it, and a
  Package Map row can opt out with `tier-drift: ignore`. It found no drift: the four files agree today.
  `scripts/test-check-tier-drift.sh` mutates copies of the real files, one kind of drift at a time.
- **CI checks every internal link in `docs/`, against a baseline that can only shrink**
  ([#1482](https://github.com/llm4s/llm4s/issues/1482)): `scripts/check-docs-site-links.sh`, in the `quick-checks`
  job, resolves each link in `docs/**/*.md` the way llm4s.org serves it - relative links against the page's URL,
  site-absolute links, `permalink:` front matter, `/dir/` index pages, heading anchors - and fails on a broken link
  that `scripts/docs-link-baseline.txt` does not list and on a listed link that no longer breaks. The rules were
  checked against the live site: a `.md` link to a rendered page is a 404 (the site is built without
  `jekyll-relative-links`), and a page without front matter is served raw, so a link to it is reported too. The
  baseline starts with 168 known-broken links (70 `.md` links, 59 links to pages without front matter, 23 pages
  never written, 8 links that leave `docs/`, 8 stale anchors); fixing one means deleting its line.
  `--print` lists what is broken now and `--update-baseline` rewrites the file.
  `scripts/test-check-docs-site-links.sh` runs 33 cases against fixture trees, with no network.
- **Built-in tools guide** ([#1296](https://github.com/llm4s/llm4s/issues/1296)):
  `docs/guide/builtin-tools.md` lists every built-in tool with its parameters and result, the bundles that hold
  them (`coreSafe`, `withHttpSafe()`, `withFilesSafe()`, `developmentSafe()`, `customSafe(...)`), how to register
  them with a `ToolRegistry` and an `Agent`, how to configure the search tools, and what each tool can do, with
  the defaults of `FileConfig`, `WriteConfig`, `HttpConfig` and `ShellConfig`. The bundle tables, parameter table,
  defaults and safety statements are asserted against the real code by `BuiltinToolsGuideSpec`, and the agent
  snippet is run by `BuiltinToolsGuideAgentSpec`. The Agents guide described `BuiltinTools.core`, `safe()`,
  `withFiles()` and `development()`, which were removed in favour of the `Safe` variants, and tools that do not
  exist (`WebSearchTool`, `FileReadTool`); it now shows the real names and links to the guide.
- **Error handling guide** ([#960](https://github.com/llm4s/llm4s/issues/960)):
  `docs/guide/error-handling.md` teaches `Result[A]` and `LLMError` in practice: the basic pattern,
  for-comprehensions, a table of the error types in `org.llm4s.error` with whether each is recoverable and
  when it is raised, the errors other modules define that carry no recoverability marker (`EmbeddingError`, `RerankError`, ..., on which
  `LLMError.isRecoverable` throws a `MatchError`, so the guide matches on `RecoverableError`), matching
  specific errors, converting to and from exceptions, combining results, retry and circuit breaking, and
  testing. Its snippets after the first section are compiled and run by `ErrorHandlingGuideSpec`. The Basic Usage
  guide listed error types that do not exist (`ProviderConnectionError`, `InvalidApiKeyError`, ...) and
  called `LLMError` sealed; it now shows the real ones and links to the guide.
- **RAG query transformer guide** ([#1545](https://github.com/llm4s/llm4s/issues/1545)):
  `docs/guide/rag-query-transformers.md` explains how a RAG pipeline rewrites the query before it is embedded:
  the built-in `LLMQueryRewriter` (temperature 0, default or custom system prompt, a `ProcessingError` on
  `query-rewrite` when the LLM call fails) and `IdentityTransformer`, adding them with `withQueryTransformer`
  (appends) or `withQueryTransformers` (replaces the chain), how `QueryTransformer.applyChain` runs the chain in
  order and stops at the first error, and how to write a custom transformer. Contributed by @wanjinhao1.
- **Caching guide** ([#1297](https://github.com/llm4s/llm4s/issues/1297)): `docs/guide/caching.md` explains the embedding
  cache (`CachedEmbeddingClient`, `InMemoryEmbeddingCache`, custom keys and backends) and the semantic completion cache
  (`CachingLLMClient`, `CacheConfig`): what a hit needs, what the key and the prompt contain, TTL, eviction, the cases
  that bypass the cache, what it reports through tracing, and its limits. Its snippets are compiled and run by
  `CachingGuideSpec`. The caching changes it brought with it are listed under Changed.
- **Structured output guide** ([#1310](https://github.com/llm4s/llm4s/issues/1310)):
  `docs/guide/structured-output.md` explains `LLMClient.completeStructured[A]`: a minimal example, how the
  reply is recovered from a fence or prose, that the schema is derived with `strict = true` and so lists every
  property as required (including ones declared optional), that only `responseFormat` is overridden, how to
  set the schema name or strictness by calling `complete` directly, the `ValidationError` on `structured_output`
  for a reply that is not JSON, is `null` or does not match, and what each provider does with the schema
  (OpenAI, Azure, Requesty, the OpenAI-compatible providers, Cohere, Gemini, Vertex AI and Ollama send it;
  Anthropic only instructs the model; watsonx and Bedrock send nothing). The code it shows is mirrored in, and run by,
  `StructuredOutputGuideSpec`. The Scaladoc of `completeStructured` now states the all-fields-required behaviour
  and the error contract (a reply is deserialised, not validated against the schema), and no longer claims every
  OpenAI-compatible server enforces the schema; no code changed.
- **Cancellation by interrupt for graph runs and providers** (Experimental, `org.llm4s.agent.graph`,
  [#1270](https://github.com/llm4s/llm4s/issues/1270)): each superstep runs in a bounded Ox scope on
  virtual threads (Ox is a new implementation dependency of `llm4s-agent`). Interrupting the thread
  that called `start`/`recover`/`resume` or `CompiledGraph.run` interrupts and joins every task and
  returns `Failed(GraphError.Cancelled)` with the interrupt flag set; in a durable run, tasks that
  finished first keep their results and `recover` continues the run (an in-memory
  `CompiledGraph.run` keeps nothing from the cancelled superstep); an interrupted task records
  nothing. The thread claim is always released, and an interrupted Async close still drains its
  queue first. New `RunEvent.RunCancelled`. The provider testkit gains `assertCancelsWhenInterrupted` and
  `assertCancelsStreamWhenInterrupted`, and `LocalProviderTestServer.holdOpen`/`streamThenHold`.
  Design: `docs/design/typed-agent-runtime-design.md` §4.4.
- **Resumable approval and tool-call barriers for graph runs** (Experimental,
  `org.llm4s.agent.graph`, [#1269](https://github.com/llm4s/llm4s/issues/1269)): nodes can suspend
  with a typed question (`NodeResult.Suspend`, `GraphBuilder.declareResume`, `ResumeRef`). The run
  pauses after that superstep, and `CompiledGraph.resume` / `GraphRuntime.resume` answer any subset
  of the parked interrupts. A continuation fills the suspended task's join arrival, so a barrier stays
  closed until every parked call is answered. Checkpoints gain a `Suspended` status and format 2,
  with a migration from format 1. Every run now claims its thread with a synchronous commit, so a
  racing `start`/`resume`/`recover` fails with `ThreadBusy` - as does any call on a thread whose run
  is still executing in the same `GraphRuntime`, so `recover` cannot re-run a live run's work - and
  every returned suspension has been persisted. `org.llm4s.agent.graph.toolloop.ToolLoop` prototypes the model/tool loop on the
  runtime: one task per call; exactly one runtime-written result per call, including denial,
  rejection, unknown tools and failures; policy- and tool-raised approvals both resuming at one
  approval node; and edited approvals amending the source assistant message. Design:
  `docs/design/typed-agent-runtime-design.md` §4.5, with the Stage 0 carry-forward in §4.8.
- **A guide to provider exchange logging** ([#1298](https://github.com/llm4s/llm4s/issues/1298)):
  `docs/guide/observability/provider-exchange-logging.md`, linked from the Monitoring page, describes the opt-in
  capture of raw provider request and response bodies: the fields of `ProviderExchange`, which clients record,
  turning it on from `llm4s.exchangeLogging` (read with `Llm4sConfig.exchangeLogging()` and passed in
  `LlmClientOptions`; nothing does so automatically) or from code, the JSON Lines file, and writing a
  `ProviderExchangeSink`, with a privacy warning. It states what the file sink redacts and that it truncates
  bodies to 1000 characters, that a sink you write receives the bodies untouched, and the limitations found while
  checking it: a throwing sink fails silently, `Cancelled` is never produced, `requestId` and `correlationId` are
  never set, and `duration_ms` is written as a string. Its snippets and claims are compiled and run by
  `ProviderExchangeLoggingGuideSpec` (`llm4s-openai-compatible`) and `ProviderExchangeLoggingGuideCoreSpec`
  (`llm4s-core`). Monitoring becomes a section with children in the docs navigation.
- **A staged-deployment template: dev, staging, prod** ([#846](https://github.com/llm4s/llm4s/issues/846),
  reworked from #857 by @Shivampal157): `modules/deploy-service` (unpublished) serves `GET /health` and
  `GET /llm-check` (a configuration check, not a connectivity check: `200` when a default provider is
  configured and a client can be built, `503` otherwise), with its image built by the sbt Docker plugin like
  the workspace-runner image, a numeric non-root user, and its port from `PORT` through
  `llm4s.deploy-service.port`. `deploy/` has Kustomize manifests (a hardened pod, rolling updates that never
  drop capacity, `dev` / `staging` / `prod` overlays) and `deploy/scripts/deploy.sh`, which applies them, waits
  for the rollout, rolls back with `kubectl rollout undo` if it fails, and smoke-checks the result.
  `.github/workflows/deploy-staged.yml` is opt-in (`workflow_dispatch` or `workflow_call`, never a push or a
  pull request): it builds and smoke-tests the image, and with `deploy` pushes it tagged with the commit SHA
  and rolls it out dev, then staging, then prod, with GitHub Environments' required reviewers as the promotion
  gate. See [deploy/README.md](deploy/README.md). **Not run against a real cluster or registry**; the script's
  rollout, rollback and smoke logic was tested against a fake `kubectl`.
- **Durable graph runs: checkpoints and commit-gated event replay** (Experimental,
  `org.llm4s.agent.graph`, [#1268](https://github.com/llm4s/llm4s/issues/1268)):
  `GraphRuntime.start`/`recover`/`subscribe` over a `Checkpointer` SPI that owns each thread's latest
  checkpoint, its per-task pending writes and a durable event log, committed atomically
  (`InMemoryCheckpointer` is the reference store). Checkpoints are versioned JSON
  (`Checkpoint.fromJson`), and state, update and node-input codecs carry a `SchemaVersion` with
  migrations. Per-thread event sequence numbers are allocated in the commit, durable events are
  delivered only after it in every durability mode (`Sync`, `Async`, `OnExit`), and live-only
  progress is never replayed. `recover` continues an incomplete execution without re-running
  completed siblings. Design: `docs/design/typed-agent-runtime-design.md` §4.3.
- **Typed graph kernel prototype in `llm4s-agent`** (Experimental, `org.llm4s.agent.graph`)
  ([#1267](https://github.com/llm4s/llm4s/issues/1267), Stage 0 of
  [#1266](https://github.com/llm4s/llm4s/issues/1266)): typed state keys with operation-valued
  updates (`StateKey[A, U]`, `StateUpdate`, `ThreadState`); builder-issued typed node handles and
  routes (`GraphBuilder`, `NodeRef[I]`, `Route.Goto`/`Send`/`FanOut`); declared write sets;
  static and dynamic join barriers; a superstep scheduler that commits updates in deterministic
  task and emission order; and `GraphSnapshot` restore with validation against the compiled graph.
  It prototypes the contracts in `docs/design/typed-agent-runtime-design.md` §4.2. The existing agent loop does not
  use it yet, and the API will change as the remaining Stage 0 issues land.
- **`llm4s-provider-testkit`** (Beta, new published module, `org.llm4s.testkit`)
  ([#1133](https://github.com/llm4s/llm4s/issues/1133)): the checks every provider module's
  `Llm4s<Name>ModuleSpec` makes, so a provider published outside this repository can prove itself
  the way the built-in ones do - discovery through `META-INF/services`, sole ownership of its ids,
  explicit `ProviderRegistry.ofModules` registration, the config-to-client round trip, refusing
  another provider's config, real streaming, and that `reference.conf` binds each `apiKeyEnv`
  variable to `llm4s.credentials.<id>.apiKey` (`ProviderModuleChecks`); config loading from a
  HOCON string with an injected environment (`ProviderTestConfig`, `CredentialsRoundTrip`); and a
  local stub HTTP server (`LocalProviderTestServer`). Every in-repo provider module uses it.
- **The cookbook: five runnable recipes, executed in CI** ([#1476](https://github.com/llm4s/llm4s/issues/1476)):
  `sbt "samples/runMain org.llm4s.samples.cookbook.ToolCallingRecipe"` (and `StructuredOutputRecipe`,
  `GuardrailsRecipe`, `DocumentQaRecipe`, `MemoryRecipe`) runs a complete example with no API key, against a
  `ScriptedClient` that stands in for the model; `--live` runs the same code against the provider chosen by the
  configuration. Each recipe has a spec that runs it against the script and checks what it did (the tool ran, a
  request was refused before the model was called, only the best passage reached the prompt), and `CookbookDocsSpec`
  fails when `docs/examples/cookbook.md` lists different recipes or embeds code that is not the code in the source
  files. This is five of the eight recipes the issue asks for.
- **`CostTrackingExample`: cost tracking end to end** ([#516](https://github.com/llm4s/llm4s/issues/516),
  reworked from #898 by @gudiwadasruthi): `sbt "samples/runMain org.llm4s.samples.metrics.CostTrackingExample"`
  shows what a call costs per request (`Completion.estimatedCost`), per agent run (`AgentState.usageSummary`
  after a real `Agent.run` that calls a tool) and per session (a `CostTracker`); how to price a model the
  registry does not know with a `ModelRegistryService` built from your own `ModelMetadata`, an immutable
  snapshot passed as the given registry (nothing global is changed); and `MetricsCollector.compose` feeding two
  trackers from one client. A model with no price is reported as *unknown*, not as a guessed number, and the
  output says when a total of `0` means "no price". The sample's logic is tested against a fake provider with
  fixed token counts and prices (`CostTrackingExampleSpec`).
- **`MultiProviderComparisonExample`: one prompt, several providers, side by side**
  ([#957](https://github.com/llm4s/llm4s/issues/957), reworked from #1081 by @vansh7nvc):
  `sbt "samples/runMain org.llm4s.samples.basic.MultiProviderComparisonExample"` asks each named provider
  (`openai-main`, `anthropic-main` and `gemini-main` by default, or the section names you pass) the same prompt
  and prints each answer with its reported tokens and the latency of the `complete` call alone. Providers are
  named sections under `llm4s.providers`; a section that is missing or has no key is reported with the reason
  and the others still run, and every client is closed. Tested against fake providers
  (`MultiProviderComparisonExampleSpec`).
- **Vendor credentials: `llm4s.credentials.<provider>.apiKey`**
  ([#1132](https://github.com/llm4s/llm4s/issues/1132), [#1126](https://github.com/llm4s/llm4s/issues/1126)).
  Credentials belong to a vendor, keyed by provider id; clients belong to a use. Each provider
  module's `reference.conf` binds its vendor's conventional variable to a shared key -
  `OPENAI_API_KEY`, `AZURE_OPENAI_API_KEY`, `REQUESTY_API_KEY` (`llm4s-openai`),
  `ANTHROPIC_API_KEY` (`llm4s-anthropic`), `GOOGLE_API_KEY` then `GEMINI_API_KEY` (`llm4s-gemini`),
  `DEEPSEEK_API_KEY`, `ZAI_API_KEY`, `OPENROUTER_API_KEY`, `MISTRAL_API_KEY`, `COHERE_API_KEY`
  (`llm4s-openai-compatible`; `COHERE_API_KEY` also `llm4s-rag`) and `VOYAGE_API_KEY`
  (`llm4s-voyage`); none for `openai-compatible`, `ollama` or `vertexai`. A client's own `apiKey`
  (chat section, `llm4s.embeddings.<id>`, `llm4s.rerank.cohere`) wins; otherwise it uses the
  shared key of its canonical provider id (so `provider = "google"` uses `gemini`'s); otherwise
  the missing-key error names both, e.g. `apiKey: set OPENAI_API_KEY, or set apiKey under
  llm4s.providers.openai-main in application.conf`. The key's source path is logged at INFO,
  never its value. Additive for users: with `OPENAI_API_KEY` set, a chat section needs only
  `provider` and `model`, and OpenAI embeddings need no `llm4s.embeddings.openai.apiKey` line -
  configs that failed for want of one now load. Keys only: `baseUrl`, `endpoint`, `model` and
  `apiVersion` are never defaulted from the block.

  Moved bindings: Voyage's `VOYAGE_API_KEY` from `llm4s.embeddings.voyage.apiKey`, and the Cohere
  reranker's `COHERE_API_KEY` from `llm4s.rerank.cohere.apiKey`, to `llm4s.credentials.voyage` /
  `.cohere` - one `COHERE_API_KEY` now serves Cohere chat and the reranker. An explicit key at the
  old paths still wins. Renamed variable: Azure's key is bound as `AZURE_OPENAI_API_KEY`, the
  openai SDK's name; `AZURE_API_KEY` is not read (keep `apiKey = ${?AZURE_API_KEY}` in the section
  to go on using it).

  Also new: `org.llm4s.config.RerankerConfigLoader` (`load(source)` / `default()`) in `llm4s-rag`,
  the first code to read `llm4s.rerank` - its `reference.conf` bound the keys, but nothing read
  them; `Llm4sConfig.apiKeySources()` / `apiKeySourcesFrom(source)` and `ApiKeySource`
  (`Section` / `Credentials`), reporting where each chat section's key comes from;
  `ProviderConfigSpec.apiKeyEnv`; `GeminiConfigKeys`, `OpenAIConfigKeys.REQUESTY_API_KEY` and
  `OpenAICompatibleConfigKeys.OPENROUTER_API_KEY` / `ZAI_API_KEY` / `COHERE_API_KEY`; and the
  config-policy rule `ownApiKey` (`ConfigPolicy.withOwnApiKeyRequired`,
  `ConfigPolicyEngine.checkApiKeySources`), enabled in the `prod` preset only, which fails any chat
  section of a key-requiring provider that sets no `apiKey` of its own - so a section meant for a
  second account cannot silently bill the default one.

  Source breaks (pre-MiMa): `EmbeddingConfigSpec.apiKeyPath` and its resolution are removed - the
  shared credentials key replaces it; `EmbeddingConfigSpec.apiKeyEnv` is a `Seq[String]` (was
  `Option[String]`); `ProviderConfigSpec` gained a defaulted trailing `apiKeyEnv`;
  `OpenAIConfigKeys.AZURE_API_KEY` is now `AZURE_OPENAI_API_KEY`; the chat and embeddings
  missing-key messages changed. See the
  [migration note](docs/reference/migration.md#vendor-credentials-a-shared-api-key-per-provider).
- **`openai-compatible` takes its context window from the model registry** ([#1217](https://github.com/llm4s/llm4s/issues/1217)):
  without a `contextWindow`, the generic provider used 8192 and never asked the registry, though the registry
  holds the window of most of the popular compatible hosts' models (`groq/`, `together_ai/`, `fireworks_ai/`,
  `xai/`, `perplexity/`). A section now takes it from, in order: its own `contextWindow`; the registry's entry for
  `model` under a new optional `registryProvider` key; the entry under the provider inferred from the `baseUrl`
  host (an exact match on `api.groq.com`, `api.together.xyz`, `api.together.ai`, `api.fireworks.ai`, `api.x.ai` or
  `api.perplexity.ai`, never a substring); then 8192. An explicit `registryProvider` switches the inference off.
  The lookup is strict: only the named provider's own entry for exactly that model id counts, never a partial
  name or another provider's entry for the same name (new `ContextWindowResolver.strictContextWindow`; the
  forgiving `resolve` would have picked up a neighbour's window). Only the window is taken: `reserveCompletion`
  keeps its rule. A registry window below 8192 is ignored, since many Fireworks and Perplexity entries carry a
  4096 placeholder, so the registry can only enlarge the window. The Groq, Fireworks and Perplexity recipes in the providers guide no longer set `contextWindow`;
  the Together, xAI and NVIDIA NIM recipes keep it, as the registry has no input limit for those models.
  **Behaviour change:** a section for one of those hosts that set no `contextWindow` now gets the registry's
  window instead of 8192; set `contextWindow` to keep the old value.
- **Provider-specific config keys for named providers** - a descriptor declares keys of its own
  in `ProviderConfigSpec.extras` (`ProviderConfigKey`: name, description, `required`, `default`,
  `env`, `deprecatedAliases`), and reads them from the new `NamedProviderConfig.extras` (or
  `ProviderDescriptor.requireExtra`), instead of reusing `endpoint`, `organization` or `baseUrl`
  ([#1215](https://github.com/llm4s/llm4s/issues/1215), designed in
  [#1131](https://github.com/llm4s/llm4s/issues/1131)). They travel the path `headers` does - raw
  section, normaliser, `NamedProviderConfig` - and are validated like the built-in fields: a
  missing required key fails with its name, the section and its description, defaults are filled
  in, and a deprecated alias maps to the current name with a warning. Keys neither built-in nor
  declared, silently ignored until now, are ignored with a warning naming them. Unblocks Bedrock
  (`region`, `profile`, [#1008](https://github.com/llm4s/llm4s/issues/1008)) and watsonx
  (`projectId`, `spaceId`, `iamUrl`, [#1019](https://github.com/llm4s/llm4s/issues/1019)).

  Vertex AI moves to `project` (required) and `location` (default `us-central1`); `endpoint` and
  `organization` still work as deprecated aliases, with a warning, for a release. The
  missing-`baseUrl` message no longer tells users to "set `<PROVIDER>_BASE_URL`", a variable
  nothing read: it names the config key, and - when the descriptor declares a conventional
  variable (`ProviderConfigSpec.baseUrlEnv`, `ProviderConfigKey.env`) - the binding that reads it,
  e.g. `add baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL} to the section` for `openai-compatible`.

  Source breaks: `RawNamedProviderSection` and `NamedProviderConfig` gained a defaulted trailing
  `extras` parameter (exhaustive pattern matches need one more field); `ProviderConfigSpec` gained
  defaulted `baseUrlEnv` and `extras`; `VertexAIProvider.configSpec` no longer sets
  `requiresEndpoint`, and a hand-built `NamedProviderConfig` passed straight to its `buildConfig`
  must carry `project` in `extras`. An extra key whose value is an object or list is now a load
  error. See the
  [migration note](docs/reference/migration.md#named-providers-provider-specific-keys-vertex-ai-project-and-location).
- **`streamUsage` in a generic `openai-compatible` section.** `OpenAICompatibleConfig.streamUsage`
  can now be set from config: `streamUsage = false` in the named section stops a streaming
  request sending `stream_options.include_usage`, for an endpoint that rejects the field. It is a
  provider-specific key declared by `OpenAICompatibleProvider` (default `true`); `true`/`false`
  and HOCON's `yes`/`no`/`on`/`off` are accepted, and any other value fails with an error naming
  the key and section. Before this, the key was reported as unknown and ignored
  ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **A contributing guide for OpenAI-compatible providers**:
  [CONTRIBUTING.md](CONTRIBUTING.md#adding-an-openai-compatible-provider-a-dialect) now covers
  checking the generic `openai-compatible` provider first, the `OpenAICompatibleDialect` hooks
  and their defaults, a worked example (Cohere), the config, descriptor and
  `Llm4sOpenAICompatibleModule` registration, the tests and `@Cloud` smoke spec to write, and the
  docs to update ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **A standalone Java sample with Gradle** ([#968](https://github.com/llm4s/llm4s/issues/968), reworked from #981
  by @krrish175-byte): `modules/samples/gradle-java` is a Gradle project with a Java `main` that calls llm4s through
  `llm4s-java-api`: a client, a question, a conversation built with `ConversationBuilder`, and `LlmResult` error
  handling, with no Scala `Either`, `Option` or `Nil$.MODULE$` in the code and an exit status of 1 when a call
  fails. It depends only on `llm4s-java-api`, whose POM brings the provider modules and the Scala 3 library, and
  it is built in CI by the existing `Kotlin API` job against the library it just published. No Gradle wrapper
  jar is committed. Until 0.5.0 ships `llm4s-java-api`, it resolves it from local Maven (see its README).
- **`llm4s-media`, a shared vocabulary for multimodal code** - landed as part of
  [#1130](https://github.com/llm4s/llm4s/issues/1130), ahead of `llm4s-image` and
  `llm4s-speech` so those carves are pure file moves. `org.llm4s.media.MediaType` (MIME string,
  canonical extension, category, and lookups by extension, path or MIME type, refining into
  `ImageMediaType` and `AudioMediaType`) and `org.llm4s.media.MediaCategory`. Vocabulary only:
  no I/O, no content sniffing, no third-party dependencies - Tika-based sniffing stays in
  `llm4s-rag` and resolves its result through `MediaType.fromMimeType`.

  This replaces three overlapping enumerations of the same handful of image formats that
  `llm4s-core` had accumulated - `imagegeneration.ImageFormat`, `imageprocessing.ImageFormat`
  (structurally identical to the first, different package) and `imageprocessing.MediaType` -
  plus `MediaExtractor` matching on raw MIME prefixes with no type to name the answer.
- **Gradle integration guide and `gradle-demo`** ([#938](https://github.com/llm4s/llm4s/issues/938)):
  `docs/getting-started/gradle.md` and `docs/reference/dependency-conflicts.md`, a Gradle section in
  the installation guide, and `modules/gradle-demo` (not published): reference `build.gradle`,
  `build.gradle.kts` and `settings.gradle.kts`, plus `GradleSnippets` and `ConversationTemplates`,
  small helpers that return `Result`. The snippets name `llm4s-core_3:0.4.1`, the latest release, and
  pin `scala3-library_3` only, because a rule over the whole `org.scala-lang` group makes Gradle look
  for a `scala-library` 3.x that does not exist. Bump the version in the guide, the reference files
  and `GradleSnippets.LLM4S_VERSION` when 0.5.0 publishes the split modules. No change to any
  published module.
- **`JSONValidator` checks property types** ([#922](https://github.com/llm4s/llm4s/issues/922)).
  A schema's `properties.<field>.type` is now enforced on the top-level fields the output
  contains - `string`, `number`, `integer` (a number with no fractional part), `boolean`,
  `object`, `array` and `null`, or a list such as `["string", "null"]` - and every mismatch is
  reported, in the order the schema lists the properties: `Field 'name' has type 'number',
  expected 'string'`. A field the output does not contain is not checked (list it under
  `required`). Everything the validator cannot judge is still ignored - nested `properties`,
  `items`, `enum`, and a `type` that is not one of those names - so no schema rejects output
  for something it does not understand. **Behaviour change:** a schema that already declared
  `properties` with a `type` was ignored on those fields until now, so output that passed may
  now be rejected. Reworked from #923 by @Shubha9807.

### Changed
- **An interrupted `Agent.run`, `continueConversation`, `recover` or `resume` cancels its turn, from Java too** ([#1330](https://github.com/llm4s/llm4s/issues/1330)):
  the call returns `Left(CancelledError)` with the interrupt flag set, as before, and now also cancels the turn it
  was waiting on instead of leaving it running, returning once that turn has ended (waiting up to 5 seconds for the turn to end), so `recover`
  can follow at once; a caller already interrupted starts no turn. Cancelling a graph run therefore cancels the
  agent turns its nodes are waiting on. Use `start`/`startRecover`/`startResume` and await the `AgentRun` to keep a turn past an interrupt.
  With tracing, the cancelled turn's trace is complete (its last events delivered, its subscription detached) when
  the call returns. A turn that had already begun committing its outcome cannot be cancelled: the call then returns
  that outcome, with the interrupt flag still set. `run(query)`, whose random thread id a `Left` does not carry,
  forgets the thread of a turn that failed or was cancelled once it has ended.
  **Java-visible:** `llm4s-java-api`'s blocking `JAgent.run`, `continueConversation`, `resume` and `recover` go
  through these calls, so an interrupted Java caller now cancels its turn too (they used to stop only the wait and
  leave the turn running), as Kotlin's suspend functions already did. `AgentStream.cancel()` still cancels a streamed
  turn without interrupting any thread. Its bounded wait, which Kotlin's cancellation uses, and Kotlin's one-shot
  `run(query)` forgetting a failed turn's thread came later ([#1682](https://github.com/llm4s/llm4s/issues/1682),
  [#1688](https://github.com/llm4s/llm4s/issues/1688), under Fixed).
- **Java and Kotlin agent results use Java types only** ([#1393](https://github.com/llm4s/llm4s/issues/1393),
  BREAKING, `llm4s-java-api`, Kotlin API). Every agent turn the Java facade returns - `JAgent.run`,
  `continueConversation`, `resume`, `recover`, `AgentStream.await()`, `AgentStreamListener.onComplete` - is now a
  `JAgentResult`, not the Scala `org.llm4s.agent.AgentResult`. `answer()` is an `Optional<String>`; `threadId()`,
  `runId()` and `activeAgent()` are `String`s; `messages()` is an unmodifiable `java.util.List<JMessage>` (`role()`,
  the Java enum `JMessageRole`, `content()`, `toolCalls()` as `JToolCall`s with `argumentsJson()`, `toolCallId()`,
  `thinking()`); `usage()` is a `JUsageSummary` with `long` counts, a `java.math.BigDecimal` cost and a sorted
  `java.util.Map<String, JModelUsage>` per model. `status()` is a `JAgentStatus`: `kind()` is the Java enum
  `AgentStatusKind` (`COMPLETED`, `BLOCKED`, `STEP_LIMIT_REACHED`, `SUSPENDED`) for a `switch` or a Kotlin `when`, with
  `answer()`, `guardrail()` and `reason()` as `Optional<String>` and `pending()` the `java.util.List<PendingInterrupt>`
  that `JAgent.pending` (#1392) returned - built by the same `PendingInterrupt` factory, so `JAgent.pending(result)`
  is now a shortcut for `result.status().pending()`. `JAgent.continueConversation`, `forget` and `pending`, and the
  Kotlin `AgentKt` functions and `AgentStreamItem.Done`, take or carry a `JAgentResult`. `JAgent`'s thread-id
  parameters are declared `String` (they already were in bytecode, so Java and Kotlin callers see no change).
  `JavaInteropSpec` now walks every type reachable from a value the facade hands a caller and fails on any
  `scala.*` or `ujson.*` type; three types are named boundaries it does not enter: `LLMError` behind
  `LlmException.error()` ([#1487](https://github.com/llm4s/llm4s/issues/1487)), the `Conversation` that
  `ConversationBuilder.build()` hands back to `complete` ([#1488](https://github.com/llm4s/llm4s/issues/1488)), and
  the listener's `StreamEvent`s, read with `StreamEvents.decode`. The client facade's results were already Java
  types (`LlmResult<String>`, `JLlmClient`, `JAgent`, `AgentStream`). No shims (pre-0.5.0). **Migration:** replace
  `import org.llm4s.agent.AgentResult` with `org.llm4s.javaapi.JAgentResult`; `r.answer().get()` on a
  `scala.Option` becomes `r.answer().orElseThrow()` (or `orElse`), `Option.apply(x)` comparisons become
  `Optional.of(x)`; `r.status() instanceof AgentStatus.Completed` (or `getClass().getSimpleName()`) becomes
  `r.status().kind() == AgentStatusKind.COMPLETED`, and `Blocked`'s fields are `status().guardrail()` / `reason()`;
  iterate `r.messages()` directly instead of converting a Scala `Vector`, and branch on `m.role()` instead of
  `instanceof ToolMessage`; read `usage().inputTokens()` and friends as `long`s and `totalCost()` as a
  `BigDecimal`. Scala code that needs the Scala `AgentResult` uses `Agent` directly, not the Java facade. Scala callers
  of `JAgent.stream`, `streamResume`, `resume`, `recover` and `streamRecover` that passed a `ThreadId` now pass its
  `.value` (a `String`). `argumentsJson()` (on `JToolCall` and `PendingInterrupt`) is the arguments as JSON text: an
  object as a model sends them, but a call built with a `ujson.Str` renders as a JSON string literal. A `JMessage`'s
  `content()` is never `null` (a Scala message's `null` text reads as empty), and a `null` answer, guardrail, reason or
  tool-call id reads as an empty `Optional`. A turn's result that does not convert fails the stream through `onError`
  and `await()`. `JUsageSummary` and `JModelUsage` compare costs by numeric value (`1.0` equals `1.00`) and print them
  in plain notation. The `J*` types are not `Serializable`, and their `toString` prints full content, as the Scala
  types do.
- **Embedding cache keys are unambiguous; the completion cache refuses a NaN threshold and serves an entry exactly
  `ttl` old** ([#1297](https://github.com/llm4s/llm4s/issues/1297)). **Breaking:** `CacheKeyGenerator.sha256(parts*)`
  length-prefixes every part instead of joining text and model with `:` (under which the text `a:b` with model `c`
  and the text `a` with model `b:c` shared a key), and `CachedEmbeddingClient`'s key function takes
  `(text, modelName, purpose: InputPurpose)` instead of a `#query`-suffixed model name (under which a query for model
  `m` and a document for a model named `m#query` shared a key); the default is `CacheKeyGenerator.embeddingKey`.
  `sha256` also hashes every UTF-16 code unit of the parts rather than their UTF-8 bytes, under which an isolated
  surrogate (`"\uD800"`, `"\uD801"`) was replaced by `?` and distinct strings shared a key.
  **Migration:** a custom key function gains the `InputPurpose` parameter and should include it in the key; vectors
  stored under the old keys in a persistent `EmbeddingCache` are no longer found and are re-embedded on first use.
  `CacheConfig.create` now refuses a `NaN` similarity threshold, which it accepted before (every similarity check
  then failed, so nothing was ever served from the cache), and `CachingLLMClient` serves an entry
  exactly `ttl` old instead of counting it as expired, matching `InMemoryEmbeddingCache`.
- **`AssistantMessage` is a growth-prone data type; `Completion.thinking` comes from the message**
  ([#1381](https://github.com/llm4s/llm4s/issues/1381)): `AssistantMessage` is `final case class AssistantMessage
  private (contentOpt, toolCalls, thinking)` with a companion `apply` (named arguments, defaults as before, plus the
  `apply(content)` / `apply(content, toolCalls)` overloads) and `withContent`, `withToolCalls`, `withThinking`;
  `.copy` is private, so replace `msg.copy(contentOpt = Some(t))` with `msg.withContent(t)`. A positional pattern
  takes four fields: `case AssistantMessage(content, toolCalls, thinking, thinkingBinding)`. `Completion` loses its `thinking`
  constructor parameter and `withThinking`: `Completion.thinking` is now `message.thinkingText`, so set it with
  `completion.withMessage(completion.message.withThinking(...))`, or build the message with it.
  Signed, redacted or opaque thinking is *sealed*: Anthropic, Bedrock and OpenRouter accept it only beside the exact
  content and tool calls it came with, so `withContent` / `withToolCalls` given a changed value drop redacted and
  opaque blocks and signatures
  (keeping the reasoning text). Sealed thinking is also valid only after the history it was produced after
  (Anthropic checks the system prompt, tools and every earlier message; Bedrock's signature is a hash of the
  conversation), so the Anthropic, Bedrock and OpenAI-compatible clients bind it to a fingerprint of the request
  (`AssistantMessage.thinkingBinding`) and, at send time, replay it only while the conversation before it still has
  that fingerprint, sent to the provider and model that produced it - the fingerprint covers the provider id and
  model each client is configured with (never the model a response reports, so an alias's snapshot or the model
  `openrouter/auto` or a fallback chose keeps the replay OpenRouter requires on tool-call continuations), so a conversation continued with another client or model is sent unsealed rather
  than with a foreign signature. Pruning, compression, summarisation, an edit or an inserted message anywhere earlier therefore
  unseals every later turn, whoever made the change; `hasSealedThinking` reports the state. Token estimates
  (`ConversationTokenCounter`, the agent's default pruning counter) now count thinking, which providers resend.
- **Every published module's POM carries a one-sentence description, and the POM URLs are normalised**
  ([#1455](https://github.com/llm4s/llm4s/issues/1455)): each `llm4s-*` artifact used to publish its own name as its
  `<description>` (`llm4s-core` described as "llm4s-core"); the descriptions now live in one table,
  `project/PomDescriptions.scala`, and `sbt publishedArtifactsCheck` fails for a published module without its own
  distinct description. The POM `<url>` is `https://llm4s.org` (it was the GitHub organization page), and `<scm>` is
  `https://github.com/llm4s/llm4s` with an `https` connection string (the URL had a trailing slash and the connection
  was an SSH form). The organization URL and every `<dependencies>` block are unchanged. Maven Central search and IDEs
  show the descriptions; Scaladex ranks by the GitHub description and topics, which a maintainer sets in the
  repository settings.
- **`llm4s-anthropic`: tool calls and results as content blocks** ([#1381](https://github.com/llm4s/llm4s/issues/1381)):
  an assistant turn's tool calls go to Anthropic as `tool_use` blocks after its text, and each `ToolMessage` as a
  `tool_result` block, consecutive results in one user turn. Before, a tool-call turn was dropped and its results
  sent as `[Tool result for <id>]: ...` user text, which left nowhere to replay the turn's signed thinking. A call
  is sent only when its result is in the run of tool messages straight after it; otherwise the call is left out and
  the result goes as prefixed user text, after the turn's `tool_result` blocks. `llm4s-bedrock` pairs calls and
  results by the same rule (it sent every call and result before, which Converse rejects when they do not pair).
- **`OpenAIConfig`, `AnthropicConfig` and `OllamaConfig` use the growth-prone data type pattern**
  ([#1388](https://github.com/llm4s/llm4s/issues/1388), `llm4s-openai-compatible`, `llm4s-anthropic`,
  `llm4s-ollama`): Scala default arguments are invisible to Java and Kotlin, so a field added to one of these
  plain case classes broke every Java or Kotlin caller that built it, as `tokenExchange` did to
  `OpenAICompatibleConfig` in #1357. Each is now `final case class X private (...)` with a private `copy`, a
  companion `apply` with the full field list (unchanged, so Scala calls - positional or named - still compile), a
  short `apply` for Java and Kotlin (`OpenAIConfig.apply(apiKey, model)`, `AnthropicConfig.apply(apiKey, model)`,
  `OllamaConfig.apply(model, baseUrl)`) that takes the base URL default (`OpenAIConfig.DEFAULT_BASE_URL` is new)
  and a context window and completion reserve from the model name, and a `with*` setter per field;
  `OpenAIConfig.withOrganization` and `withExplicitProviderId` take the value or an `Option`. `fromValues` is
  unchanged and still the way to get the bundled model catalogue's context window. **Migration:** replace
  `config.copy(baseUrl = url)` with `config.withBaseUrl(url)` (likewise `withApiKey`, `withModel`,
  `withOrganization`, `withContextWindow`, `withReserveCompletion`, `withExplicitProviderId`); `new OpenAIConfig(...)`
  becomes `OpenAIConfig(...)`; Java and Kotlin call the companion's `apply` - `OpenAIConfig.apply(key, "gpt-4o")
  .withOrganization("org-1")` - instead of the constructor. Pattern matching (`case OpenAIConfig(...)`) is unchanged.
- **`LLMError.isRecoverable` is total** ([#1380](https://github.com/llm4s/llm4s/issues/1380), `llm4s-core`,
  `llm4s-agent`, `llm4s-speech`): it matched only `RecoverableError` and `NonRecoverableError` and threw a
  `MatchError` on any other `LLMError` (`EmbeddingError`, `RerankError`, `EvaluationError`, the orchestration and
  speech errors, a custom error), and so did `recoverableErrors` / `nonRecoverableErrors` on a list holding one.
  An unmarked error is now not recoverable, as `RetryPolicy.isRetryable` and `ErrorRecovery` already treated it.
  Markers are added where the answer is clear: `OrchestrationError.AgentTimeoutError`, `STTError.EngineNotAvailable`
  and `TTSError.EngineNotAvailable` are `RecoverableError`s (so the library's retries, such as a graph node's
  default retry or `recoverWithBackoff`, now retry them); `PlanValidationError`, `TypeMismatchError`,
  `STTError.UnsupportedFormat`, `STTError.InvalidInput`, `WavFileGenerator.WavError` and `AudioIO.AudioIOError` are
  `NonRecoverableError`s (no behaviour change). `NodeExecutionError` (its own `recoverable` flag),
  `PlanExecutionError`, `STTError.ProcessingFailed`, `TTSError.SynthesisFailed`, `EmbeddingError`, `RerankError` and
  `EvaluationError` stay unmarked: each is one type whose answer depends on a value, not the type. The error
  handling and Basic Usage guides drop the `MatchError` caveat, and Basic Usage calls `isRecoverable` directly.
- **A crashed run's event stream ends at a barrier, not after a quiet period**
  ([#1378](https://github.com/llm4s/llm4s/issues/1378), `llm4s-agent`, `llm4s-effect`, `llm4s-zio`): a run
  that commits no terminal event (`GraphError.RunCrashed`, or `CheckpointWriteFailed` on its terminal
  commit) used to end its run-scoped listeners - `Agent.stream*`, `AgentRun.subscribe`, agent tracing,
  `AgentIO.stream*`, `AgentZ.stream*` - once they had been idle for 1 s, which added a second to the
  stream's end and to `AgentRun.await`. As the run ends - after handing over its last event, before
  releasing the thread to a later run - each of the run's subscriptions now gets an end-of-run marker
  queued behind the run's last event (behind its replay, for a `subscribe` made after the run ended),
  and reaching it ends the listener: the stream ends, and `await` returns, as soon as the last event
  is delivered. The marker is never passed to a listener and is exempt from the queue's capacity, so
  it never makes a subscriber lag; a subscriber already lagging gets none and ends with its
  `Disconnected`. Thread-scoped `GraphRuntime.subscribe` and `RunHandle.subscribe` listeners never get
  one. No public API changes.
- **Stage 1 migration: agent runtime** ([#1328](https://github.com/llm4s/llm4s/issues/1328), BREAKING,
  `llm4s-agent`, `llm4s-effect`, `llm4s-zio`, `workspaceClient`): `Agent` runs on `GraphRuntime`
  through a generalised `ToolLoop`; the graph is the only agent loop, and `AgentState` and the
  legacy loop are deleted, with no shim. Tools, guardrails, handoffs and context pruning belong to
  the agent, set at build time, and a conversation is carried by `ThreadId`. Do not cut 0.5.0
  between #1328 and #1329, which adds the event stream #1328 removes. Slices 3 (#1329) and 4
  (#1330) extend this note; the full guide with examples is in `docs/reference/migration.md`.
  Design: `docs/design/typed-agent-runtime-design.md` §4.13. Replacements:
  - `new Agent(client).run(q, tools, ...)` -> `Agent.builder(id, client).withTools(tools)...build()`
    then `run(q)`; `run` also takes `(threadId, query)`, `(threadId, query, config)` and
    `(threadId, query, config, history)`. `agent.start(...)`, `startRecover` and `startResume`
    return an `AgentRun` (`threadId`, `runId`, `status`, `await()`, `cancel()`).
  - Per-run guardrails -> `.withMiddleware(new GuardrailMiddleware(input, output))`. A block is
    the runtime's Block (see "A guardrail Block finishes the run"), which `Agent` reports as `Right` with
    `AgentStatus.Blocked(guardrail, reason)`: the thread stays usable, an input block stores nothing
    of the turn and an output block removes it (any handoff made in it too), and `usage` keeps its
    model calls. Another middleware's `beforeAgent`/`afterAgent` `Left` blocks too, returned as that
    `Left`. A blank query, given or produced by `beforeAgent`, is a `ValidationError` and stores
    nothing. A transforming guardrail (`PIIMasker`) now applies. The root agent's guardrails and other run-boundary
    middleware guard the whole handoff family, whichever agent is active.
  - `continueConversation(state, q)` -> `continueConversation(result, q)`, which reads only
    `result.threadId`.
  - Threads stay in the agent's runtime until `agent.forget(threadId)` (`GraphRuntime.deleteThread`)
    removes them - one-shot `run` threads too; `Checkpointer` gains `deleteThread`.
  - `runMultiTurn` with `contextWindowConfig` -> an agent built with
    `new ContextWindowMiddleware(config)`. It prunes only what is sent; the current turn is never
    pruned (the strategy, `Custom` included, sees only the history before it), the request always
    starts with a user message, and the system prompt is outside the budget.
  - `AgentState` fields -> `AgentResult`: `conversation` is `messages`, `status` is `status`,
    `usageSummary` is `usage`, `logs` is removed (use `withTracing`).
  - `AgentStatus` -> `Completed(answer)`, `Blocked`, `StepLimitReached`, `Suspended`; `Failed` is
    `Left(GraphError...)` (provider errors as `GraphError.NodeFailed(cause)`); `InProgress`,
    `WaitingForTools` and `HandoffRequested` are gone.
  - `AgentContext` is removed: `tracing` is `withTracing`, which emits the `graph.*` events;
    `debug` and `traceLogPath` are gone. `TraceEvent.AgentStateUpdated` is no longer emitted.
  - `Handoff(agent)` -> `Handoff.to(id, builder, reason)` (the id must equal the target builder's
    id, `preserveContext` optional) or `Handoff.toId(id, reason?, preserveContext?)` for a cycle; `transferSystemMessage` is
    removed; a self-handoff, and a handoff mixed with other tool calls, are refused.
  - `runStep`, `initializeSafe`, `runWithStrategy`, `continueConversationWithStrategy`: removed;
    `RunBudgets.maxConcurrency` bounds parallel tool calls. `ToolExecutionStrategy` stays in core as
    a `ToolRegistry` feature.
  - `runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `AgentEvent`,
    `AgentStreamingExecutor`: removed, pending #1329.
  - Session files: `AgentState.saveToFile`/`loadFromFile` -> save `result.messages` and import them
    as `history` of a new thread; `history` is refused on an existing thread and may hold no system
    message.
  - `AgentIO`/`AgentZ` wrap the new `Agent`: `LLMClientIO.agent(id)(configure)` and
    `LLMClientZ.agent(id)(configure)`; `run`, `continueConversation`, `recover`, `resume`; fiber
    cancellation cancels the run; a thrown exception arrives as `NodeFailed` carrying the original.
  - `CodeWorker.executeTask` returns `Result[AgentResult]` and loses `traceLogPath`;
    `WorkspaceSettings.traceLogPath` and `WORKSPACE_TRACE_LOG` are removed.
  - `ToolLoop.build(id, version, root, agents: Vector[LoopAgent])` builds an agent family;
    `ModelStep.next` returns the `Completion`.
  - Samples `StreamingAgentExample`, `StreamingWithToolsExample`, `EventCollectionExample` and
    `AsyncToolAgentExample` are deleted (the first three return in #1329, on `Agent.stream`).
  - `GuardrailMiddleware`'s Block error is `GuardrailBlocked(guardrail, reason)` (the first failing
    guardrail's name, every failure's error joined), no longer `CompositeGuardrail`'s aggregate.
  - `llm4s-java-api`: `JAgent.run(query)` returns `LlmResult<AgentResult>`; tools are given to
    `Llm4s.createAgent(client, tools)` (`run(query, tools)` is removed); `continueConversation` and
    `forget` are new. The Kotlin `AgentKt` follows (`run`, `continueConversation`, `forget`).
- **`Result.traverse` short-circuits** ([#960](https://github.com/llm4s/llm4s/issues/960)): it
  stops calling the function at the first `Left`, where it used to call it on every element and
  then return the first failure. **Behaviour change:** side effects in the function no longer run
  for the elements after a failure. `Result.sequence` returns the same results as before.
- **`ErrorRecovery.recoverWithBackoff` returns a non-retried error unchanged on every attempt**
  ([#960](https://github.com/llm4s/llm4s/issues/960)): an error it does not retry, such as a
  `ValidationError`, came back wrapped in an `ExecutionError` when it happened on the last attempt
  (always, with `maxAttempts = 1`), losing its type. Only `RateLimitError`, `TimeoutError` and a
  `ServiceError` that exhaust the attempts are wrapped now. **Behaviour change:** a `ServiceError` is
  retried only when `isRecoverableStatus` (5xx, 429, 408), as `ReliableClient`'s `RetryPolicy` already
  did; a 404 or other permanent status comes back unchanged at once. The Scaladoc no longer calls the
  schedule exponential and describes each type's delay.
- **Agent run events, streaming and run-end tracing** ([#1329](https://github.com/llm4s/llm4s/issues/1329),
  BREAKING, `llm4s-core`, `llm4s-agent`, `llm4s-observability`, `llm4s-observability-otel`,
  `llm4s-effect`, `llm4s-zio`): slice 3 of the Stage 1 migration, which restores the event stream
  #1328 removed, on the runtime's own events. Design: `docs/design/typed-agent-runtime-design.md`
  §4.14; guide: `docs/guide/agents/streaming.md`. Durable agent events (`agent.*`) carry no message
  content; content is live-only and, for tracing, in `AgentRunEnded.messages`. Source breaks, with no shims:
  - `RunContext.progress(payload)` -> `progress(name, version, payload)`, or an `EventType`;
    `StreamEvent.Live` gains `name` and `version`.
  - `ModelStep.next(messages, tools)` -> `next(messages, tools, call)`.
  - `GraphRuntime.start`/`recover`/`resume` gain a defaulted `observer` parameter (source-compatible
    for callers, not for subclasses).
  - `TraceEvent.AgentStateUpdated` is removed, with `AgentState#toTraceEvent`: use
    `TraceEvent.AgentRunEnded`.
  - `TracingSubscriber` no longer serves `Agent`; `withTracing` traces each run through
    `AgentTracing`, with `agent.*` event names where the kernel subscriber uses `graph.custom`.
  New:
  - `AgentBuilder.withStreaming()`; `Agent.stream`, `streamResume` and `streamRecover` take a
    listener, subscribed at admission so it sees every event of the run; `AgentRun.subscribe(capacity)`
    is run-scoped; `Agent.StreamCapacity` is 1024. `AgentRun.await` returns once each listener has
    returned from the run's last event (at most 5 s, then a WARN).
  - `org.llm4s.agent.events.AgentEvents` (`ModelCallStarted`, `ModelCallCompleted`, `TextDelta`,
    `ThinkingDelta`, `ToolCallStarted`, `ToolCallResult`, `ToolExecuted`, `HandedOff`,
    `GuardrailBlocked`) with typed extractors; `EventType[A]` and `Observer` in
    `org.llm4s.agent.graph`.
  - `AgentIO.stream*` (fs2) and `AgentZ.stream*` (ZIO ZStream) yield `AgentStreamItem.Event` or
    `Done`; interrupting or stopping early cancels the turn. A consumer too slow for the buffer loses
    live events and gets one `StreamEvent.LiveGap` with their count; it does not cancel the run.
  - `TraceEvent.AgentRunEnded(threadId, runId, agent, status, messages, usage)`, sent once per
    traced run, with `TokenUsageRecorded` per model call. `usage` is the run's own usage, summed from
    its `ModelCallCompleted` events (which carry the completion's `estimatedCost`), never the
    thread's cumulative usage. A durable `ToolExecuted` names a tool the agent does not have as
    `<unknown>`.
    Langfuse traces now use the run id as the trace id and the thread id as the session id (a
    conversation's turns group); OpenTelemetry gets an "Agent Run" span; `TraceCollector` an
    `AgentCall` span.
  - Samples `StreamingAgentExample`, `StreamingWithToolsExample` and `EventCollectionExample` are
    back, with `AgentStreamIOExample` and `AgentStreamZIOExample`.
  Limits: the kernel's `TaskFailed`/`RunFailed` events
  store error messages, which may quote content.
- **Approval resumes through the middleware chain; `ToolLoop` gains a `finish` node**
  ([#1279](https://github.com/llm4s/llm4s/issues/1279)): `Approve` now runs the whole middleware
  chain again with `ToolContext.approved = true`, where it skipped the policy; a deny rule that
  depends only on the call refuses the same calls as before. `ToolLoop` has a new `finish` node, so
  checkpoints from an earlier build of the loop do not restore (pre-1.0; no migration is provided). A final
  answer with blank content and no tool calls now fails the run at the `model` node before it is
  stored, rather than completing with a message the next turn's `Message.validateConversation`
  refuses; `recover` asks the model again.
- **Binary compatibility is checked by MiMa** ([#924](https://github.com/llm4s/llm4s/issues/924),
  [#1281](https://github.com/llm4s/llm4s/issues/1281)): a `mima-check` CI job runs
  `sbt mimaReportBinaryIssues` and gates `all-tests-pass`. The baseline is set per frozen module
  (`mimaFrozen` in `build.sbt`: `llm4s-core`, `llm4s-agent`, `llm4s-openai`,
  `llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`) and is not set yet:
  it is 0.5.0, the first release with the split coordinates, so the job checks nothing until 0.5.0
  is published. See [API stability](docs/reference/api-stability.md).
- **`RegexValidator` constructor** (`org.llm4s.agent.guardrails.builtin`): the primary constructor
  now takes a compiled `Pattern` and a pattern description, so it is not binary compatible with
  earlier releases. Source is compatible: secondary constructors keep the `Regex`-based
  `new RegexValidator(regex)`, `(regex, errorMessage)` and `(regex, errorMessage, fallbackError)`
  forms. Recompile code that calls it. This predates the 0.5.0 MiMa baseline, so no filter is needed.
- **An interrupted call returns `CancelledError`** ([#1270](https://github.com/llm4s/llm4s/issues/1270)):
  new `org.llm4s.error.CancelledError` (non-recoverable, never retried) with the interrupt flag kept.
  A `SocketTimeoutException` on its own stays a timeout. `Llm4sHttpClient` returns it where it
  returned `ExecutionError` (and `NetworkError` mid-stream); `ReliableClient`, `LLMClientRetry` and
  `ErrorRecovery` return it instead of `TimeoutError`, `ExecutionError` or `SimpleError` and do not
  count it against the circuit breaker; every chat client returns it instead of throwing
  `InterruptedException` or reporting `UnknownError`; `ToolRegistry` returns
  `ToolCallError.Cancelled`. New `ErrorKind.Cancelled` metric label `cancelled`. Anthropic streams
  are now closed on every path. `DefaultErrorMapper`, and so `Try(...).toResult`, now returns
  `CancelledError` for an exception mapped while the thread is interrupted, or one caused by
  `InterruptedException` or `ClosedByInterruptException`; a bare `InterruptedIOException` (such as
  OkHttp's call timeout) stays a timeout unless the thread is interrupted. Mapping never sets the
  interrupt flag. `ReliableClient` returns `CancelledError` (was the local `RateLimitError`) for an
  interrupt while waiting for a local rate-limit token. A provider call made with the interrupt flag
  already set returns `CancelledError` without sending the request.
- **Graph supersteps run concurrently by default**
  ([#1270](https://github.com/llm4s/llm4s/issues/1270)): a superstep's tasks, which ran one after
  another, now run concurrently on virtual threads (at most 16 at a time). Node code must be
  thread-safe; `ThreadLocal`/MDC context is not inherited by a task; and in `Sync` durability,
  durable events and live progress are delivered on the task threads.
- **Every client sends through `Llm4sHttpClient`, and a 503's `Retry-After` is honoured**
  ([#1133](https://github.com/llm4s/llm4s/issues/1133)). `CohereReranker`, the OpenAI and Ollama
  embedding providers, the OpenAI and Anthropic vision clients and `OpenAICompatibleClient` called
  the JDK `HttpClient` directly with their own error handling; they now share the one transport, so
  a timeout, I/O failure or interruption is a `Left` (interrupt flag restored), and a stream that
  fails mid-read is classified as `OllamaClient`'s and `GeminiClient`'s are. Their error types and
  messages are unchanged. `ServiceError` gains `retryAfter` (and `withRetryAfter`), which
  `HttpErrorMapper` fills from the response's `Retry-After`; every `RetryPolicy`, `ErrorRecovery`
  and `LLMClientRetry` wait for it, and a service error without one keeps the policy's own backoff.
  `Llm4sHttpClient.create(connectTimeout)` limits connecting separately from each request.
  `OpenAICompatibleClient.RequestTimeout` and `StreamTimeout` are `FiniteDuration` (were
  `java.time.Duration`).
- **`llm4s-agent`: the agent runtime leaves `llm4s-core`** - the second slice 7 carve
  ([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D4). `org.llm4s.agent` - `Agent`,
  `AgentState`, `AgentContext`, guardrails, handoffs, orchestration and streaming events - and
  `org.llm4s.assistant`, the console assistant (Beta), move to a new module; package names are
  unchanged. `agent.memory` was already `llm4s-memory`, which does not depend on the new module.
  Nothing in `llm4s-core` referred to either package, and neither reads configuration, so the
  carve is a move: core keeps what the agent is built on - `LLMClient`, the tool API, and the
  tracing contract, which takes the `TraceEvent.AgentStateUpdated` that `AgentState#toTraceEvent`
  builds. `fansi`, used only by the assistant, leaves core with it. `llm4s-workspace-client`
  gains the dependency for its `codegen` package.

  Source break (pre-MiMa): code that uses the agent runtime adds `llm4s-agent`; no import
  changes. See the
  [migration note](docs/reference/migration.md#slice-7-llm4s-agent---the-agent-runtime-leaves-core).
- **`llm4s-agent-tools`: the built-in tools leave `llm4s-core`** - the first slice 7 carve
  ([#1242](https://github.com/llm4s/llm4s/issues/1242), decisions D2 and D3). `BuiltinTools` and
  all of `org.llm4s.toolapi.builtin` (DateTime, Calculator, UUID, JSON, filesystem, HTTP, shell, and
  the Brave, DuckDuckGo and Exa search tools) and the demo `org.llm4s.toolapi.tools.WeatherTool`
  move to a new module; package names are unchanged. They are integrations with third-party APIs,
  so they leave the frozen spine and version as Beta; core keeps the tool API they implement
  (`ToolFunction`, `ToolRegistry`, schemas, execution). The module depends on `llm4s-core` only,
  not on the agent runtime, so plain `ToolRegistry` tool calling can use the tools without it.
  The search tools' config moves with them: `ToolsConfigLoader` (now public), the three
  `*SearchToolConfig` types, and the `llm4s.tools` block of `reference.conf`, whose keys, defaults
  and `BRAVE_SEARCH_*` / `EXA_*` / `DUCK_DUCK_GO_SEARCH_API_URL` bindings are unchanged.

  Source breaks (pre-MiMa): `Llm4sConfig.loadBraveSearchTool()`, `loadDuckDuckGoSearchTool()` and
  `loadExaSearchTool()` are removed - they returned types that left core; call the same methods on
  `ToolsConfigLoader`. `ConfigKeys.BRAVE_SEARCH_API_KEY` is `ToolsConfigKeys.BRAVE_SEARCH_API_KEY`
  (with `EXA_API_KEY` beside it). See the
  [migration note](docs/reference/migration.md#slice-7-llm4s-agent-tools---the-built-in-tools-leave-core).
- **`UsageSummary` and `ModelUsage` move to `org.llm4s.llmconnect.model`** - the preparation
  step of slice 7 ([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D1), which carves
  the agent runtime into `llm4s-agent`. They were in `org.llm4s.agent`, but depend only on
  `TokenUsage`, and `llm4s-observability`'s `CostTracker` builds them: left in the agent package,
  the carve would make observability - and through it every `llm4s-rag` user - depend on the agent
  runtime. They stay in `llm4s-core`, beside `TokenUsage`. Their JSON form is unchanged, so saved
  `AgentState`s still load.

  Source break (pre-MiMa): import `org.llm4s.llmconnect.model.{ UsageSummary, ModelUsage }`
  instead of `org.llm4s.agent.{ UsageSummary, ModelUsage }`. `AgentState.usageSummary` is
  unchanged. See the
  [migration note](docs/reference/migration.md#slice-7-usagesummary-and-modelusage-move-to-orgllm4sllmconnectmodel).
- **llm4s artifacts no longer choose your logging backend** - found by the slice 6 spine audit
  ([#1133](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5878475544)). Every published
  module declared `logback-classic` and the `log4j-to-slf4j` bridge at compile scope, so depending
  on llm4s put logback on the classpath - a second backend for an application on log4j2, and a
  conflict with `log4j-core`. They now declare only `slf4j-api` (2.0.17); logback and the bridge
  are test-scoped, and added only to the unpublished samples, workspace runner, config-policy CLI
  and benchmarks. Also removed: `monocle-core` and `monocle-macro`, declared on every module and
  imported by none, and `commons-io`, declared by `llm4s-core` and no longer used by it. `fansi` is
  declared by `llm4s-core` alone, for `assistant`, instead of by every module.

  Behaviour: an application with no SLF4J backend of its own gets SLF4J's no-op logger and its
  one-line "no providers were found" warning instead of logback's default console output; add
  `"ch.qos.logback" % "logback-classic" % "1.5.34"` (or any SLF4J 2 backend) to keep log output. See the
  [migration note](docs/reference/migration.md#llm4s-no-longer-brings-a-logging-backend).
- **`llm4s-observability-prometheus`: Prometheus leaves `llm4s-core`** - the second slice 6 carve
  ([#1133](https://github.com/llm4s/llm4s/issues/1133), decisions D3 and D4). `PrometheusMetrics`,
  `PrometheusEndpoint` and `MetricsConfigLoader` move, package names unchanged, to a new module that
  carries the Prometheus client and HTTP server, so `llm4s-core` now declares no observability
  dependency. It is a module of its own rather than part of `llm4s-observability` so Prometheus does
  not reach every `llm4s-rag` user. Core keeps the `MetricsCollector` contract (with `Outcome`,
  `ErrorKind`, `noop` and `compose`), which every client and middleware takes. The `llm4s.metrics`
  block, whose defaults were hard-coded in the loader because core had none, is now in the module's
  `reference.conf`: keys and defaults are unchanged (`enabled = false`, `prometheus.enabled = true`,
  `prometheus.port = 9090`).

  Source break (pre-MiMa): `Llm4sConfig.metrics()` is removed - it returned a `PrometheusEndpoint`,
  so it could not stay in core. `MetricsConfigLoader`, previously `private[config]`, is public and
  replaces it with the same result type: `MetricsConfigLoader.default()` (or `load(source)`). See the
  [migration note](docs/reference/migration.md#slice-6-llm4s-observability-prometheus---prometheus-leaves-core).
- **`llm4s-observability`: Langfuse, the trace collector and `CostTracker` leave `llm4s-core`** -
  the slice 6 carve ([#1133](https://github.com/llm4s/llm4s/issues/1133),
  [#1126](https://github.com/llm4s/llm4s/issues/1126)). The new module holds `LangfuseTracing`,
  `LangfuseBatchSender`, `LangfuseTracingBackend` (now registered by the module's own
  `META-INF/services` entry; core's temporary one is deleted), `TraceCollectorTracing`,
  `org.llm4s.trace.model`, `org.llm4s.trace.store`, `CostTracker` and `LangfuseConfig`, and no
  third-party dependency; `llm4s-rag` depends on it for `RAGASLangfuseObserver`. Package names
  are unchanged. Core keeps the contract - `Tracing`, `TraceEvent`, `TracingComposer`,
  `TracingMode`, the `TracingBackend` SPI, `NoOpTracing`, `ConsoleTracing`, `TracingSettings`,
  `MetricsCollector` - and builds only `Console` and `NoOp` itself. The `llm4s.tracing.langfuse`
  block and its `LANGFUSE_*` bindings moved to `llm4s-observability`'s `reference.conf`, and
  `llm4s.tracing.opentelemetry` with its `OTEL_*` bindings (and `OpenTelemetryConfig`) to
  `llm4s-observability-otel`'s; each backend reads its block from `TracingSettings.extras`. Keys
  and variables are unchanged: a Langfuse user adds the dependency and changes no config. New:
  `LangfuseConfigLoader` (`load(source)` / `default()`), reading `llm4s.tracing.langfuse` whatever
  the mode; `LangfuseConfig.fromExtras`, `OpenTelemetryConfig.fromExtras`; `LangfuseConfigKeys`.

  Behaviour: `TRACING_MODE=langfuse` without the module gives `NoOpTracing` and an error naming
  `llm4s-observability`, as OpenTelemetry without its module already did; with the module but
  without `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY`, the backend refuses to start with an
  error naming the missing keys, rather than building a tracer that drops every batch. And when
  the selected mode's `llm4s.tracing.<mode>` is present but not an object (say `opentelemetry =
  "http://collector:4317"`), or cannot be read, `Llm4sConfig.tracing()` returns a
  `ConfigurationError` naming the path, where it used to pass the backend an empty block and let
  it start on its defaults; an absent block is still empty.

  Source breaks (pre-MiMa): `TracingMode.Langfuse` and `TracingMode.OpenTelemetry` are removed -
  they are `TracingMode.Named("langfuse")` / `Named("opentelemetry")`, which `fromString` returns;
  `TracingSettings` loses its `langfuse` and `openTelemetry` fields (now `TracingSettings(mode,
  extras)`); `LangfuseConfig` and `OpenTelemetryConfig` move module; `DefaultConfig` is removed
  (its constants are `LangfuseConfig.DEFAULT_*`); `ConfigKeys.LANGFUSE_*` are
  `LangfuseConfigKeys.LANGFUSE_*`; `RAGASLangfuseObserver.fromTracingSettings` is removed; and
  two unused Langfuse JSON builders are deleted from core rather than moved,
  `TraceEvent.createTraceEvent` and `org.llm4s.llmconnect.model.TraceHelper`. See the
  [migration note](docs/reference/migration.md#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core).
- **Tracing backends are discovered, and agent state is a `TraceEvent`** - the tracing extension
  point, landed ahead of the slice 6 carve as slice 4 did for providers
  ([#1133](https://github.com/llm4s/llm4s/issues/1133), decisions D2 and D5).
  `Tracing.create` builds `NoOp` and `Console` itself and dispatches every other mode to an
  `org.llm4s.trace.spi.TracingBackend` found through
  `META-INF/services/org.llm4s.trace.spi.TracingBackend`. `llm4s-observability-otel` registers
  `OpenTelemetryTracingBackend`, replacing the `Class.forName` reflection core used to load it;
  Langfuse is registered by core until it is carved. `TracingMode` gains `Named(name)`, so a
  third-party backend is selected by `TRACING_MODE=<name>` with no edit to core, and
  `Tracing.fromSettings` returns a missing or failing backend as an error where `Tracing.create`
  logs it and falls back to `NoOpTracing`. `TracingBackends.of` / `withBackend` register a backend
  explicitly. A backend reads its own settings from `TracingSettings.extras`: the block
  `llm4s.tracing.<mode>` for the selected mode, flattened to strings, with defaults in the
  backend module's `reference.conf` - as provider descriptors read `NamedProviderConfig.extras`.

  Source breaks: `Tracing.traceAgentState(AgentState)` is removed - trace
  `state.toTraceEvent` (a `TraceEvent.AgentStateUpdated`, which gains a `messages` field before
  `timestamp`) through `traceEvent`; `TracingMode` gains the `Named` case and a `name` member; and
  `TracingMode.fromString` returns `Named` rather than `NoOp` for an unrecognised value. The span
  an agent run records for its state is renamed in OpenTelemetry (`Agent State Snapshot` becomes
  `Agent State Updated`) and `TraceCollectorTracing` (`agent-state-update` becomes
  `agent_state_updated`). See the
  [migration note](docs/reference/migration.md#slice-6-tracing-backends-are-discovered-and-agent-state-is-a-traceevent).
- **Mistral, Cohere and Voyage leave `llm4s-core`, which now ships no provider** - the end of
  core's provider clients in slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
  Mistral and Cohere become dialects in `llm4s-openai-compatible`: Mistral over its OpenAI-format
  `/v1/chat/completions` (nine-character tool-call ids, no empty assistant turns, Magistral
  content chunks read as text and thinking), Cohere over Cohere's OpenAI-compatibility API
  (`developer` system role, `json_object` + `schema` response format). Voyage AI embeddings are
  carved as-is into `llm4s-voyage` (`modules/providers/voyage`), the first community provider
  module, with its `llm4s.embeddings.voyage` block. Class names, packages and constructors are
  unchanged; add `llm4s-openai-compatible` for Mistral or Cohere, `llm4s-voyage` for Voyage.

  Fixed ([#925](https://github.com/llm4s/llm4s/issues/925)): **Mistral and Cohere stream** - their
  `streamComplete` returned "not supported" - and gain tool calling and structured output; their
  descriptors no longer declare `streaming = false`. Fixed for every provider on
  `OpenAICompatibleClient`: streamed completions report token usage and a cost estimate (they
  always had `usage = None`).

  Behaviour changes: Cohere calls `https://api.cohere.ai/compatibility/v1` by default instead of
  the native `/v2/chat`, and a configured native root (`https://api.cohere.com`, with or without
  `/v1` or `/v2`) is mapped to `<root>/compatibility/v1` and logged. For Mistral and Cohere, reply
  text is no longer trimmed, a reply with no text is an empty completion rather than an error, a
  missing `id`/`created` is `""`/`0` rather than invented, and a `ToolMessage` is sent rather than
  refused (Mistral) or dropped (Cohere). A Mistral `baseUrl` ending in `/v1` is no longer doubled.
  An empty conversation fails before any request on the shared client.

  Source breaks: `BuiltinProviders`, `BuiltinProviderModule`, core's
  `META-INF/services` entry and `ProviderRegistry.builtin` are removed - use
  `ProviderRegistry.ofModules(...)` where discovery cannot run; `ProviderModelListers.Mistral` is
  now `MistralModelLister`; `ConfigKeys.MISTRAL_*` moved to `OpenAICompatibleConfigKeys` and
  `ConfigKeys.VOYAGE_*` to `VoyageConfigKeys` (strings unchanged); `CohereConfig.DEFAULT_BASE_URL`
  changed value; `OpenAICompatibleDialect` gained `sendEmptyAssistantTurns`, `encodeToolCallId`,
  `systemRole` and `encodeResponseFormat`, all defaulting to the standard format. See the
  [migration note](docs/reference/migration.md#slice-5-mistral-cohere-and-voyage-leave-core-core-ships-no-provider).
- **`llm4s-openai-compatible`: DeepSeek, Z.ai and OpenRouter leave `llm4s-core` on one shared
  client, and a generic `openai-compatible` provider joins them** - the fifth provider module of
  slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)), and a consolidation rather than
  a pure move. The three providers each had a ~400-line copy of the same SDK-free
  chat-completions client; they are now thin subclasses of one `OpenAICompatibleClient`, each
  with a small `OpenAICompatibleDialect` for what differs. `DeepSeekConfig`, `ZaiConfig` and
  `OpenAIConfig` move with them (package unchanged; `llm4s-openai` now depends on this SDK-free
  module for `OpenAIConfig`). The clients' constructors and `apply` overloads, the descriptors
  and the configs keep their shapes. The new `provider = "openai-compatible"` serves any
  endpoint speaking the OpenAI chat-completions API from config alone: `baseUrl` and `model`
  required, `apiKey` optional (no `Authorization` header without one), and `contextWindow`,
  `reserveCompletion` and `headers` read from the section - which named provider sections may
  now carry.

  Behaviour changes from the consolidation: DeepSeek returns `deepseek-reasoner`'s
  `reasoning_content` as thinking; the stream body is closed on every failure (Z.ai and
  OpenRouter leaked it on an error status); each call records exactly one provider exchange;
  OpenRouter sends assistant content as a string (it sent `["text"]` by accident); Z.ai reads
  usage given as an array.

  Source breaks: `ProviderModelListers.DeepSeek` / `.OpenRouter` are now `DeepSeekModelLister` /
  `OpenRouterModelLister`; `DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL` and
  `DEFAULT_OPENROUTER_BASE_URL` are now `DeepSeekConfig.DEFAULT_BASE_URL` and
  `OpenRouterProvider.DEFAULT_BASE_URL`; `ConfigKeys.DEEPSEEK_*` and `OPENROUTER_BASE_URL` are on
  `OpenAICompatibleConfigKeys`; `NamedProviderConfig` and `RawNamedProviderSection` gained three
  defaulted trailing fields; `ProviderRegistry.builtin` no longer includes these providers.
  Unused public classes are removed from `llm4s-core`: `OpenRouterToolCallDeserializer` (no
  client used it; `StandardToolCallDeserializer` stays), and `StreamingResponseHandler` with
  `BaseStreamingResponseHandler`, `OpenAIStreamingHandler`, `AnthropicStreamingHandler` and
  `StreamingResponseHandler.forProvider` - no client streamed through them; every client
  accumulates with `StreamingAccumulator`. No configuration key or environment variable changed.

  Fixed: a streamed tool call split across deltas lost every argument fragment after the first
  in the DeepSeek, Z.ai and OpenRouter clients (continuations carry only an `index`, and the
  missing id was defaulted to `""`, which `StreamingAccumulator` skips). The shared client maps
  each index to its call's id for the life of the stream, and a streamed `Completion` now
  reports its tool calls in `toolCalls` as a non-streaming one does.

  `llm4s-config-policy`'s `dev` preset now allows `openai-compatible`; the `prod` preset does not,
  since the provider can point at any endpoint - production allows it explicitly. See the
  [migration guide](docs/reference/migration.md#slice-5-llm4s-openai-compatible).

- **`llm4s-openai` sends `reasoning_effort`, and asks for token usage on streams**
  ([#1132](https://github.com/llm4s/llm4s/issues/1132), follow-ups to #1209).
  `OpenAIClient` now sends `CompletionOptions.reasoning` as `reasoning_effort` (`Low`, `Medium`,
  `High` as `low`, `medium`, `high`; `None` sends nothing) to OpenAI reasoning models - the
  o-series and the gpt-5 family, as the model registry's `supports_reasoning` flag says, with
  OpenAI's naming as the fallback for models newer than the bundled metadata - and never to
  other models, which reject it. Before, the setting was ignored. A reasoning model's request
  now also sends `maxTokens` as `max_completion_tokens` (core's transformer already did this for
  `o1`, `o3` and `gpt-5`; it now covers `o4-mini` and anything else flagged) and leaves out
  `temperature`, `top_p` and the penalties, which those models reject: a gpt-5 request with the
  default options was failing on `temperature`. A fine-tuned model (`ft:o4-mini-...:org:suffix:id`)
  is judged by the model it was trained from. On Azure, a deployment name the registry cannot
  resolve gets `reasoning_effort` whenever a reasoning effort is asked for. Streaming requests
  set `stream_options.include_usage` (Azure from api-version `2024-09-01-preview` on), so
  OpenAI's streams report usage and an estimated cost, which they had not, since OpenAI sends
  none unless asked. Streamed usage now also carries reasoning and cached tokens and the
  service's own total. See the [providers guide](docs/guide/providers.md#reasoning-models).

- **`llm4s-openai` moves from Microsoft's deprecated Azure OpenAI SDK to OpenAI's official Java
  SDK** ([#1132](https://github.com/llm4s/llm4s/issues/1132)). `OpenAIClient` now runs on
  `com.openai:openai-java` 4.69.3 for all three providers it serves - OpenAI, Azure OpenAI and
  Requesty - in place of `com.azure:azure-ai-openai` 1.0.0-beta.16, which Microsoft
  [has deprecated](https://learn.microsoft.com/en-us/java/api/overview/azure/ai-openai-readme?view=azure-java-preview)
  in favour of `openai-java` (its last release was 2025-03-26). Azure uses the SDK's own Azure
  support: an `api-key` header, the deployment in the path and `api-version` as a query
  parameter - the same URL as before, for any endpoint host. `OpenAIClient`'s constructors and
  `apply` overloads, the three descriptors, `OpenAIConfig` and `AzureConfig` are unchanged, as
  are every configuration key and environment variable. The module now brings OkHttp, Jackson
  and the Kotlin standard library instead of the Azure core libraries; `llm4s-core` and
  `llm4s-openai-compatible` still depend on no vendor SDK.

  Source break: **`AzureToolHelper` is replaced by `OpenAIToolHelper`** (same package,
  `org.llm4s.toolapi`), because its signatures were Azure SDK types. `addToolsToOptions(registry,
  options: ChatCompletionsOptions)` becomes `addToolsToParams(registry,
  builder: ChatCompletionCreateParams.Builder)`, and `convertToolRegistryToAzureTools(registry):
  java.util.List[ChatCompletionsToolDefinition]` becomes `convertToolRegistryToOpenAITools(registry):
  java.util.List[ChatCompletionTool]`, both over `com.openai.models.chat.completions` types. No
  other public signature named an SDK type.

  Fixed: **streamed tool calls lost their arguments.** `OpenAIClient` keyed streamed tool calls
  by `id`, which only a call's first delta carries, so every continuation fragment was dropped;
  it now matches continuations by `index`, passes fragments through verbatim, and fills
  `Completion.toolCalls` on streams as `complete` does. Also fixed, in `llm4s-core`:
  `StreamingAccumulator` returned several streamed tool calls in hash order (it kept them in an
  unordered map), so `Completion.toolCalls` and the message's tool calls could come back out of
  the provider's index order; they now keep the order the stream first named them, for every
  client that accumulates with it, `OpenAICompatibleClient` included. Behaviour changes: `AzureConfig.apiVersion`
  accepts the wire form (`2024-10-21`, as the docs show) as well as the old constant name
  (`V2024_10_21`), where only the latter worked before; an Azure endpoint ending in `/openai/v1`
  uses Azure's unified v1 API; `OpenAIConfig.organization` is now sent as `OpenAI-Organization`
  (the Azure SDK ignored it); Azure and Requesty clients label their errors, metrics and
  exchange log `azure` and `requesty` (every one said `openai`); HTTP errors map by status (401/403 `AuthenticationError`, 429
  `RateLimitError`, 400 `ValidationError`, otherwise `ServiceError`) rather than by matching the
  message; streamed token usage is read from whichever chunk carries it; and `close()` releases
  the HTTP client. See the
  [migration guide](docs/reference/migration.md#slice-5-llm4s-openai-moves-to-openai-java).

- **`llm4s-openai`: OpenAI, Azure OpenAI and Requesty leave `llm4s-core`, and take the Azure
  OpenAI SDK with it** - the fourth provider module of slice 5
  ([#1132](https://github.com/llm4s/llm4s/issues/1132)). The providers that share `OpenAIClient`
  move to the new `llm4s-openai` artifact: `OpenAIClient`, `OpenAIProvider`, `AzureProvider`,
  `RequestyProvider`, `AzureConfig`, `OpenAIEmbeddingProvider`, `AzureToolHelper` and the OpenAI
  and Requesty model listers, with their tests, the `openai-main`, `requesty-main` and
  `azure-main` examples and the `llm4s.embeddings.openai` block from `reference.conf`, and an
  `Llm4sOpenAIModule` declared in `META-INF/services`. Package names are unchanged.
  `llm4s-core` no longer depends on `com.azure:azure-ai-openai`, and so depends on no vendor SDK.
  OpenRouter, DeepSeek and Z.ai have their own SDK-free clients and stay in core for now; so do
  `OpenAIConfig`, which OpenRouter shares, and `OpenAIStreamingHandler`.

  Source breaks: `ToolRegistry.addToAzureOptions` is removed - call
  `AzureToolHelper.addToolsToOptions(registry, options)` from `llm4s-openai` instead, since it
  exposed an Azure SDK type from core's `ToolRegistry`. `ProviderModelListers.OpenAI` /
  `.Requesty` are now `OpenAIModelLister` / `RequestyModelLister` (still in `org.llm4s.config`);
  `DefaultConfig.DEFAULT_OPENAI_BASE_URL`, `DEFAULT_REQUESTY_BASE_URL` and
  `DEFAULT_AZURE_V2025_01_01_PREVIEW` are now `OpenAIProvider.DEFAULT_BASE_URL`,
  `RequestyProvider.DEFAULT_BASE_URL` and `AzureConfig.DEFAULT_API_VERSION`; and the OpenAI,
  Requesty, Azure and OpenAI-embedding names on `ConfigKeys` are now on `OpenAIConfigKeys`
  (`ConfigKeys.OPENROUTER_BASE_URL` stays). `ProviderRegistry.builtin` no longer includes these
  providers; `ProviderRegistry.builtin.withModule(new Llm4sOpenAIModule)` restores them. No
  configuration key or environment variable changed, but `llm4s-rag`'s `RAGConfig.default`
  embeds with `openai` and so now needs `llm4s-openai` on the classpath. See the
  [migration guide](docs/reference/migration.md#slice-5-llm4s-openai).

- **`llm4s-anthropic`: Anthropic leaves `llm4s-core`, and takes the Anthropic SDK with it** -
  the third provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
  `AnthropicClient`, `AnthropicProvider`, `AnthropicConfig` and the Anthropic model lister move
  to the new `llm4s-anthropic` artifact, with their tests, the `anthropic-main` example from
  `reference.conf`, and an `Llm4sAnthropicModule` declared in `META-INF/services` - so adding the
  dependency is the whole of registration. Package names are unchanged. `llm4s-core` no longer
  depends on `com.anthropic:anthropic-java`; nor does `llm4s-workspace-client`, which declared
  it (and the Azure OpenAI SDK) without importing either.

  Three names move because the objects they were members of stay in core:
  `ProviderModelListers.Anthropic` is now `AnthropicModelLister` (still in `org.llm4s.config`),
  `DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` is now `AnthropicConfig.DEFAULT_BASE_URL`, and
  `ConfigKeys.ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` are now on `AnthropicConfigKeys`.
  `ProviderRegistry.builtin` no longer includes Anthropic;
  `ProviderRegistry.builtin.withModule(new Llm4sAnthropicModule)` restores it where classpath
  discovery is unavailable. No configuration key or environment variable changed. See the
  [migration guide](docs/reference/migration.md#slice-5-llm4s-anthropic).

- **`llm4s-gemini`: Gemini and Vertex AI leave `llm4s-core`** - the second provider module of
  slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)). `GeminiClient`,
  `GeminiProvider`, `GeminiConfig`, `VertexAIClient`, `VertexAIProvider`,
  `VertexAIAuthProvider`, `VertexAIConfig` and the Gemini model lister move to the new
  `llm4s-gemini` artifact, with their tests, the `gemini-main` example from `reference.conf`,
  and an `Llm4sGeminiModule` declared in `META-INF/services` - so adding the dependency is the
  whole of registration. Package names are unchanged, and the `google` and `vertex` provider
  spellings still resolve.

  Vertex AI ships in `llm4s-gemini` rather than its own module: it only calls Google's Gemini
  models, in the same JSON format as the Gemini API, and differs only in endpoint and in its
  OAuth authentication, which is hand-rolled rather than a Google SDK - so it adds no
  dependency for Gemini-API users, and splitting it out later would be the breaking direction.

  Three names move because the objects they were members of stay in core:
  `ProviderModelListers.Gemini` is now `GeminiModelLister` (still in `org.llm4s.config`),
  `DefaultConfig.DEFAULT_GEMINI_BASE_URL` is now `GeminiConfig.DEFAULT_BASE_URL`, and
  `DefaultConfig.DEFAULT_VERTEXAI_LOCATION` is gone in favour of the existing
  `VertexAIConfig.DEFAULT_LOCATION`. `ProviderRegistry.builtin` no longer includes Gemini or
  Vertex AI; `ProviderRegistry.builtin.withModule(new Llm4sGeminiModule)` restores them where
  classpath discovery is unavailable. No configuration key or environment variable changed.
  See the [migration guide](docs/reference/migration.md#slice-5-llm4s-gemini).

- **`ProviderConfig` factories return `Result` instead of throwing** - the other item deferred
  from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Every `fromValues` on a
  `ProviderConfig` subtype (`OpenAIConfig`, `AzureConfig`, `AnthropicConfig`, `ZaiConfig`,
  `GeminiConfig`, `DeepSeekConfig`, `CohereConfig`, `MistralConfig`, `VertexAIConfig`,
  `OllamaConfig`) validated with `require(...)`, throwing `IllegalArgumentException` for a blank
  key or endpoint - the one place a library built on `Result` still reported a configuration
  mistake by exception. They now return `Result[XConfig]`, with a `ConfigurationError` naming
  the provider and field (same message text, field in `missingKeys`). Source break: callers
  `flatMap` where they used the value directly, and a descriptor's `buildConfig` binds the
  result rather than yielding it. See the
  [migration guide](docs/reference/migration.md#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).

- **`llm4s-ollama`: Ollama leaves `llm4s-core`** - the first provider module of slice 5
  ([#1132](https://github.com/llm4s/llm4s/issues/1132)). `OllamaClient`, `OllamaProvider`,
  `OllamaEmbeddingProvider`, `OllamaConfig` and the Ollama model lister move to the new
  `llm4s-ollama` artifact, with their tests, the `llm4s.embeddings.ollama` `reference.conf`
  block, and an `Llm4sOllamaModule` declared in `META-INF/services` - so adding the dependency
  is the whole of registration. Package names are unchanged.

  Two names move because the objects they were members of stay in core:
  `ProviderModelListers.Ollama` is now `OllamaModelLister`, and `ConfigKeys.OLLAMA_*` are now
  on `OllamaConfigKeys`, both still in `org.llm4s.config`. `ProviderRegistry.builtin` no longer
  includes Ollama; `ProviderRegistry.builtin.withModule(new Llm4sOllamaModule)` restores it
  where classpath discovery is unavailable. No configuration key or environment variable
  changed. See the [migration guide](docs/reference/migration.md#slice-5-llm4s-ollama).

- **Embedding model dimensions move into the provider** - the last central provider list on
  the embedding side, and one of the two items deferred from slice 4
  ([#1131](https://github.com/llm4s/llm4s/issues/1131)). `EmbeddingProviderDescriptor` gains
  `modelDimensions` (and `dimensionsOf`, for a provider whose model names have variants), and
  `ModelDimensionRegistry.getDimension` answers from the descriptors in the caller's
  `ProviderRegistry` instead of a table in `llm4s-core`. A provider module now brings its
  dimensions with it, which is what lets them leave core with the provider in slice 5.

  `getDimension` takes an implicit `ProviderRegistry`, and so do `RAGASFactory.fromConfigs`
  and `basicFromConfigs`: binary-incompatible, source-compatible. `ModelDimensionRegistry.
  localDimension` answers the local non-text encoders without triggering provider discovery;
  `ModelSelector` uses it.

  **`RAGASFactory.fromConfigs` and `basicFromConfigs` no longer guess.** Both used to fall back
  to 1536 dimensions for any model the table lacked - including every Ollama model. They now
  return the lookup's `Left`; for a model its provider does not declare, build the
  `EmbeddingModelConfig` yourself and use `create` or `basic`.

- **Embedding configuration moves into the provider** - the fifth change of slice 4
  ([#1131](https://github.com/llm4s/llm4s/issues/1131)), completing what the fourth began. PR 4
  made an embedding provider resolvable from its own module; its *configuration* was still a
  typed case class, a PureConfig reader, a hard-coded default, a builder and two `match` arms
  per provider inside `EmbeddingsConfigLoader`, so a third-party provider could be registered
  and then had nothing to be configured with.

  `llm4s-core` now parses one uniform section shape - `apiKey`, `baseUrl`, `model` - and hands
  it to `EmbeddingProviderDescriptor.buildConfig`. Most providers never implement that method:
  declaring an `EmbeddingConfigSpec` (required fields, defaults, and the environment variables
  to name in errors) is enough, and the default implementation resolves the section against it.
  A descriptor whose credential lives outside its own section declares where with
  `apiKeyPath`, and `EmbeddingsConfigLoader` resolves it before calling `buildConfig` - which
  is how OpenAI's embeddings reach `llm4s.openai.apiKey` without the loader special-casing
  OpenAI, and without a descriptor reading configuration itself. `buildConfig` takes a parsed
  section and nothing else, so raw config access stays inside `org.llm4s.config`.

  Defaults and environment bindings are no longer duplicated. `reference.conf` used to state
  `baseUrl = "http://localhost:11434"` while the loader stated `DefaultOllamaEmbeddingBaseUrl`,
  with nothing keeping them in step; the default now lives only in the descriptor, and each
  provider's `reference.conf` block is reduced to the environment variables it binds. Because
  that block is keyed by provider id, it can travel with the provider when the provider moves
  to its own module - which chat config, keyed by the user's instance name, cannot do.

  `Llm4sConfig.embeddings()`, `loadTextEmbeddingModel()` and `textEmbeddingModel()` take an
  implicit `ProviderRegistry`: binary-incompatible, source-compatible. Without it an
  application's own registry could not reach the loader. All three resolve through the same
  registry, so a provider configurable by one is configurable by all of them.

  **No user-facing configuration changed.** `EMBEDDING_MODEL`, `EMBEDDING_PROVIDER`, every
  `llm4s.embeddings.<id>` key and every provider environment variable behave exactly as before.
  Error messages are more specific: unknown providers get the registry's message naming what is
  registered, and missing fields name both the config path and the environment variable.

- **Embedding providers join the provider SPI** - the fourth change of slice 4
  ([#1131](https://github.com/llm4s/llm4s/issues/1131)), and the last item on that issue's list.
  PRs 2 and 3 made a *chat* provider self-describing and discoverable; `EmbeddingClient.from` was
  still a `match` on a lowercased provider name, so an embedding provider was an edit to
  `llm4s-core` wherever its code lived.

  A new `org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor` is the embedding counterpart of
  `ProviderDescriptor`, and `Llm4sProviderModule` gained `embeddingProviders` alongside
  `chatProviders`. Registration is otherwise identical - same services file, same scan, same
  escape hatches - so an embedding provider in its own module is reachable with nothing in
  `llm4s-core` edited. `OpenAIEmbeddingProvider`, `VoyageAIEmbeddingProvider` and
  `OllamaEmbeddingProvider` now *are* their own descriptors; their `fromConfig` is unchanged.

  It is a separate trait rather than a method on `ProviderDescriptor` because the two provider
  sets overlap without either containing the other: OpenAI and Ollama supply both halves, Voyage
  only embeddings, Anthropic only chat. Chat and embedding ids therefore live in **separate
  namespaces** and the same id may appear in both - `ollama` names a chat client and an embedding
  provider that share nothing but a base URL. `ProviderRegistry` gained `findEmbedding`,
  `resolveEmbedding`, `embeddingIds`, `canonicalEmbeddingId`, `withEmbeddingProvider` and
  `ofEmbeddings`; `ProviderModuleReport` gained `embeddingProviderIds`. A provider that supplies
  no embeddings now fails as such, naming the embedding providers rather than the chat ones.

  `EmbeddingClient.from` takes an implicit `ProviderRegistry`: binary-incompatible,
  source-compatible via the companion's given, exactly as the `Llm4sConfig` methods in PR 3.

  Embedding **configuration** is unchanged - `llm4s.embeddings` still has typed `openai` /
  `voyage` / `ollama` sections - so a third-party embedding provider is resolvable but still needs
  its config built by the application. Moving that into the descriptor is the follow-up.

- **Providers are discovered on the classpath: adding a provider is adding a dependency** - the
  third change of slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 2 made a
  provider a self-describing `ProviderDescriptor`; this removes the last manual step.

  A module ships `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` naming a class
  with a public no-arg constructor (not a Scala `object`, whose instance is a `MODULE$` field that
  `ServiceLoader` cannot instantiate - the same requirement GraalVM's `ServiceLoaderFeature` has),
  and `ProviderRegistry.default` finds it. `llm4s-core` declares itself the same way through
  `BuiltinProviderModule`: there is no special case for the built-ins.

  **One broken jar cannot take out the others.** `ServiceLoader`'s iterator throws
  `ServiceConfigurationError` for an entry it cannot load, and the `for`-comprehension you would
  naturally write over it propagates the first such error and abandons every remaining provider.
  `ProviderRegistry.discover` drives the iterator by hand and guards each step - `hasNext`, `next`
  and the module's own `chatProviders` - so an unusable entry becomes a recorded failure and the
  scan continues. Failures are logged at WARN and collected in a new `ProviderRegistryReport`
  (`ProviderModuleReport`, `ProviderDiscoveryFailure`), which names each module, the jar it came
  from, and what it contributed.

  That report is what makes a missing provider diagnosable. Its summary is appended to the
  "provider is not registered" error, because the two most common causes are otherwise invisible:
  a dependency that was never added, and a fat jar whose services files were overwritten during
  shading rather than concatenated. For the second case `ProviderRegistry.builtin` (no classpath
  scan at all) and `ProviderRegistry.of(...)` remain the escape hatch, and the shipped services
  file carries the `sbt-assembly` and `maven-shade` merge configuration in a comment.

  **Binary-incompatible:** every `Llm4sConfig` method that reads `llm4s.providers` now takes an
  implicit `ProviderRegistry` - `provider`, `providerConfigs` (both overloads), `providers`,
  `defaultProviderName`, `defaultProvider`, `listModels` (both) and `providerFrom`. Call sites are
  source-compatible, since the companion supplies `ProviderRegistry.default`; passing one
  explicitly is how an application resolves providers its own modules supply, and how tests pin
  the set.

  See [docs/reference/migration.md](docs/reference/migration.md) for the worked example.
- **The provider registration SPI: adding a provider is one file, not eight** - the second
  change of slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 1 removed the closed
  `enum` and the `sealed` trait; this builds the extension point on top of them.

  New in `org.llm4s.llmconnect.spi`: `ProviderDescriptor` (a provider's id, aliases, config shape,
  features, model lister and its two builders), `ProviderConfigSpec` (which section fields it
  requires and its default base URL), `ProviderFeatures` (what its client actually implements),
  `ProviderRegistry` (an immutable set of descriptors, resolved through a `using` clause with a
  default given in its companion) and `Llm4sProviderModule` (the unit of registration - one module,
  several providers). All twelve built-in providers are now descriptors in
  `org.llm4s.llmconnect.provider`, listed in `BuiltinProviders` and nowhere else.

  What that replaces, all of it `private[llm4s]` and therefore free to delete: `ProviderCapabilities`
  (a trait plus twelve objects), `ProviderCapabilitiesRegistry`, `NamedProviderValidator` and its
  twelve `NamedProviderValidators` objects, the twelve-branch `match` in `NamedProviderLoader`, the
  two `match` expressions in `LLMConnect`, and the hard-coded `"google"`/`"vertex"` alias fold in
  `NamedProviderConfigNormalizer` - which providers now declare as `ProviderDescriptor.aliases`.
  Missing-field error messages are unchanged: they are generated from the spec rather than written
  out twelve times.

  Losing the exhaustive `match` means the compiler no longer checks that a new provider was handled
  everywhere. `BuiltinProvidersSpec` is the replacement for that guarantee: it round-trips every
  registered descriptor section -> config -> client, and a new built-in provider that is not listed
  in it fails the build.

  Four source breaks. `ReliableProviders`' seven per-provider factories (`openai`, `azureOpenAI`,
  `anthropic`, `gemini`, `ollama`, `openRouter`, `zai`) collapse to `ReliableProviders.wrap(config,
  reliabilityConfig, metrics)`, which covers all twelve providers instead of seven and works for a
  provider from another module; `wrap(client, providerName, ...)` is unchanged.
  `OpenAIConfig.providerId` is now derived from `baseUrl`, answering `openrouter` for an OpenRouter
  URL - which is the routing `LLMConnect` already performed with a hard-coded check, now stated by
  the config itself. `ProviderModelLister`/`ProviderModelListers` and
  `ProviderResultOps`/`ProviderExchangeRecorder` become public, because a provider outside
  `llm4s-core` needs all four; `ProviderModelListers` also gains an `openAICompatible(...)` factory,
  which the five near-identical OpenAI-shaped lister objects collapse into.

  Not changed: `Llm4sConfig`'s signatures, `DiscoveredModel`, every `ProviderConfig` subtype, and
  the `llm4s.providers.*` config format - a configuration that worked before works now, `provider =
  "google"` and `provider = "vertex"` included. Still deferred: classpath discovery via
  `META-INF/services` (PR 3), `ProviderConfig.fromValues`' throwing `require(...)`, and embeddings
  (`EmbeddingClient.from` and the fixed-arity reader behind it).

  See [docs/reference/migration.md](docs/reference/migration.md) for the before/after.
- **Breaking: `ProviderKind` is replaced by the opaque type `ProviderId`, and `ProviderConfig` is
  no longer `sealed`** - the first change of slice 4
  ([#1131](https://github.com/llm4s/llm4s/issues/1131)). No SPI yet; this only removes the two
  things that make a provider impossible to supply from outside `llm4s-core`.

  Adding one chat provider today costs ~20 files and ~26 edit sites across four sbt modules,
  measured from the open provider PRs (#1051 Bedrock touches 13 shared files; #1055, #1057,
  #1058, #1059 and #1061 each touch 11-13). Two structures account for most of that. `enum
  ProviderKind` is a closed enumeration, so nothing in another jar can extend it and everything
  keyed by it inherits that. `sealed trait ProviderConfig` is worse than it looks: in Scala 3
  `sealed` confines subtypes to the **same source file**, which is why all ten provider configs
  sit in one 756-line file rather than merely in one jar.

  `ProviderId` is an opaque `String`, canonicalised to trimmed lowercase under `Locale.ROOT`
  (a default-locale fold would spell `OpenAI` as `openaı` on a Turkish JVM, breaking canonical
  equality in that environment alone), and deliberately an
  **open vocabulary** - any string names a provider, and whether it can be resolved is answered
  at resolution time by what is on the classpath. Staying `opaque` keeps the no-boxing guarantee
  from [#1127](https://github.com/llm4s/llm4s/issues/1127). `ProviderConfig` gains `providerId`
  (replacing `val provider: ProviderKind`), `endpointUrl` and `withModel`, which let a caller
  describe a config without knowing the set of subtypes - so four exhaustive matches were
  **deleted rather than moved**: `ConfigPolicyEngine.providerName` and `.baseUrlOrEndpoint`,
  `PrometheusMetricsExample`, and `ProviderSetupRuntime.overrideModel`.

  **No deprecated `ProviderKind` shim.** A shim would keep a closed list of twelve providers
  inside the very module whose purpose is to remove it, and it only half-works: `case
  ProviderKind.OpenAI =>` and `def f(k: ProviderKind)` would compile, while `.values`,
  `.ordinal`, `.fromOrdinal` and exhaustivity would still fail. A clean compile error is better
  than a partly-working deprecated type. Source breaks are acceptable pre-1.0, and slice 5 will
  require a build edit regardless.

  Two behaviour changes worth knowing about. `toString` on a provider was `"OpenAI"` and is now
  `"openai"`, which shows up in error text (`providerId.asString.toUpperCase` still yields
  `"OPENAI"`, so env-var prefixes are unaffected). And an unrecognised `provider` string in
  HOCON is no longer a *parse* error: it produces a `ProviderId` and fails at resolution
  instead, with an error naming the registered ids. That is precisely what allows a provider to
  arrive from a module core has never heard of.

  Deferred to PR 2 and called out rather than slipped in: `ProviderConfig.fromValues` still
  uses `require(...)`, which throws (converting it is a throw-to-`Left` change);
  `ReliableProviders` still exposes seven per-provider factories; and
  `ProviderSetupRuntime.applyConfiguredSessionOverride` still matches per provider, because it
  edits provider-specific fields and wants the dashboard to rebuild from the config section
  rather than pattern-match a built config.

  See [docs/reference/migration.md](docs/reference/migration.md) for the full before/after.
- **`llm4s-workspace-client` sheds seven unused dependencies.** Tika, POI, PDFBox, jsoup,
  Postgres, HikariCP and commons-io were declared on `workspaceClient` but referenced by
  nothing in it. The module is nine source files that speak WebSocket JSON to a container;
  across all of them the only third-party imports are `org.java_websocket.*`,
  `org.slf4j.LoggerFactory`, `pureconfig.*`, `upickle.default._` and scalatest. There is no
  `Class.forName`, no `ServiceLoader`, no `src/main/resources` and so no
  `META-INF/services` - nothing that could reach these libraries reflectively - and no
  document-extraction or JDBC string anywhere in the module or in `workspaceShared`.

  These were the last remnants of the era when `commonSettings` put JDBC drivers on every
  module's classpath; they were declarations `workspace-client` inherited by copy rather than
  by need. `llm4s-workspace-client` is published, so this also stops those seven artifacts
  reaching downstream users through its POM - a Postgres driver and a 5 MB document-parsing
  stack that arrived with a container-execution client. Anyone who was relying on that
  transitively should declare what they actually use.

  Each removed dependency appeared exactly once in `workspaceClient/dependencyTree`, at the
  top level: none of them was a version pin winning a conflict over a transitive of something
  the module really uses, which is what made the JNA declaration removed above load-bearing
  until Vosk went with it. commons-io is the one exception worth naming - it stays on the
  runtime classpath at the same 2.22.0, because `llm4s-core` declares it and uses it in
  `assistant/SessionManager.scala`; removing the duplicate declaration here changes the POM,
  not the classpath.

  **`Deps.config` is deliberately kept**, with a comment in `build.sbt` saying why: pureconfig
  is a facade over Typesafe Config and needs it at runtime, but nothing here imports
  `com.typesafe.config`, so an import-based audit reads it as dead when it is not.
- **`llm4s-speech` is carved out of `llm4s-core`, completing slice 3**
  ([#1130](https://github.com/llm4s/llm4s/issues/1130)). `org.llm4s.speech` and its
  subpackages move whole - package names unchanged, no source breaks.

  Core sheds **Vosk (25 MB) and JNA**, the largest dependency the carve programme has moved.
  Vosk is imported by exactly one file, `speech/stt/VoskSpeechToText.scala`, and until now sat
  on the classpath of every `llm4s-core` user. `Deps.jna` travels with it but is not a second
  dependency: Vosk's POM already depends on `jna:5.7.0`, and the explicit declaration exists to
  win that conflict and pull 5.19.1 instead - dropping it would silently downgrade JNA rather
  than remove it. `llm4s-workspace-client` declared both and used neither; those declarations
  are removed too, so Vosk genuinely leaves the build for non-speech users.

  **The "Vosk Repository" resolver is deleted, not moved.** Vosk publishes to Maven Central,
  which is where every build has actually been resolving it from; the resolver added a
  third-party host to the lookup path for every artifact in the build - llm4s's own
  inter-module jars included - and resolved nothing. It was the project's only third-party
  resolver, so **the build now has none**. See the
  [migration note](docs/reference/migration.md#slice-3-llm4s-speech).

  With this, `llm4s-core` no longer contains `rag`, `knowledgegraph`, `agent/memory`, `mcp`,
  `imagegeneration`, `imageprocessing` or `speech`.
- **`llm4s-image` is carved out of `llm4s-core`** - the third artifact of slice 3
  ([#1130](https://github.com/llm4s/llm4s/issues/1130); `llm4s-speech` follows).
  `org.llm4s.imagegeneration` and `org.llm4s.imageprocessing` move together as two halves of
  one subsystem. Package names are unchanged and this carve adds no source breaks of its own -
  the image API's one break landed earlier in `llm4s-media`, so that this step is a pure file
  move. Core sheds no third-party dependency (the image clients are built on `Llm4sHttpClient`,
  uPickle and `javax.imageio`), but does shed 19 source files and its edge to `llm4s-media`,
  which existed only while the image packages were inside it. Core's measured statement
  coverage rises from 74.05% to 74.89% as a result. See the
  [migration note](docs/reference/migration.md#slice-3-llm4s-image).

  `org.llm4s.async.AsyncErrorHandlingSpec` moves with the code: despite its package name every
  assertion in it exercises an image client, so it belongs to the module that owns that
  behaviour.
- **Breaking: image formats are now `org.llm4s.media.MediaType`.** A deliberate source break
  ahead of the 1.0 API freeze: `ImageFormat.PNG`/`JPEG`/`WEBP`/`GIF` become
  `MediaType.Png`/`Jpeg`/`WebP`/`Gif`, `MediaType.value` becomes `.mimeType`, and
  `MediaType.fromPath`/`fromExtension` return `Option` instead of silently answering JPEG for
  anything unrecognised. Carving `image` and `speech` out of core first would have frozen the
  three copies into three artifacts, making this a cross-module break instead of an in-module
  one. See the [migration note](docs/reference/migration.md#slice-3-llm4s-media).
- **The published Scaladoc covers every module again, not just `core`.** `pages.yml` built the
  API site from `core/doc` alone, which was correct when core was the whole library. The carve
  slices have since moved public API out of it, so the site had silently lost `llm4s-rag`,
  `llm4s-knowledgegraph`, `llm4s-memory` and `llm4s-memory-postgres` - the pages were never
  generated, which reads to a user as "this API does not exist" rather than as a failure. A new
  unpublished `docs` project builds one aggregate Scaladoc across all published modules; the
  ScalaDoc CI job runs it on every PR, and the deploy now fails if a known package is absent
  instead of publishing a partial site. (Hand-rolled rather than sbt-unidoc, which still
  invokes `dotty.tools.dottydoc.Main` and cannot run under Scala 3.7.) The docs-deploy path
  filter also named only `modules/core/src/**`, so changes to any carved module stopped
  triggering a deploy; it now covers every module.
- **`llm4s-mcp` is carved out of `llm4s-core`** - the first artifact of the third module split
  tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126)
  ([#1130](https://github.com/llm4s/llm4s/issues/1130), which also carves `llm4s-image` and
  `llm4s-speech`; those follow separately). `org.llm4s.mcp` becomes `llm4s-mcp`. Package names
  are unchanged and there are no source breaks - nothing outside the package referenced it, so
  it moved whole. See the [migration note](docs/reference/migration.md#slice-3-llm4s-mcp).

  `llm4s-core` sheds **Java-WebSocket**, though not for the reason the slice issue predicted.
  MCP does not use WebSockets: its transports are stdio, HTTP and SSE. `Deps.websocket` was
  declared on core and imported by nothing in it - the repo's only WebSocket code is
  `ContainerisedWorkspace` in `llm4s-workspace-client`, which declares the dependency itself.
  So this removes an unused declaration rather than moving a dependency, and `llm4s-mcp` adds
  no third-party dependency of its own.
- **`llm4s-memory` and `llm4s-memory-postgres` are carved out of `llm4s-core`** - the second
  of the module splits tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126)
  ([#1129](https://github.com/llm4s/llm4s/issues/1129)). `org.llm4s.agent.memory` becomes
  `llm4s-memory`, except `PostgresMemoryStore`, which becomes `llm4s-memory-postgres`.
  Package names are unchanged and there are no source breaks in this slice - nothing outside
  the package referenced it, so it moved whole. See the
  [migration note](docs/reference/migration.md#slice-2-llm4s-memory-and-llm4s-memory-postgres).

  The store split is the point of the slice: `PostgresMemoryStore` was the one file needing a
  connection pool and a server-side driver, so shipping it with `InMemoryStore` would mean
  every user of agent memory inherits HikariCP and a JDBC driver. `llm4s-memory` carries
  sqlite-jdbc for the two file-backed stores and nothing else.

  `llm4s-core` sheds **HikariCP and the Postgres JDBC driver**, and sqlite-jdbc with them. All
  three had been declared in the build's shared settings, which put a driver and a pool on
  every module's classpath including those with no database code at all; they are now declared
  only by the modules that open a connection. A build that depends on `llm4s-core` and used
  any of the three transitively now has to declare it.

  `org.llm4s.vectorstore.PostgresVectorHelpers` stays in `llm4s-core` rather than moving to
  `llm4s-rag` with the rest of its package. It is a pure pgvector text codec naming no JDBC
  type, and its two consumers - `PgVectorStore` in `llm4s-rag` and `PostgresMemoryStore` in
  `llm4s-memory-postgres` - are in modules that must not depend on each other. Keeping the one
  copy in the module both already depend on retires the temporary duplicate slice 1 left
  behind in `org.llm4s.agent.memory`.
- **`llm4s-rag` and `llm4s-knowledgegraph` are carved out of `llm4s-core`** - the first of
  the module splits tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126)
  ([#1128](https://github.com/llm4s/llm4s/issues/1128)). `rag`, `vectorstore`, `chunking`,
  `reranker`, `eval` and the consolidated `extract` layer become `llm4s-rag`;
  `knowledgegraph` becomes `llm4s-knowledgegraph`. Package names are unchanged, so this is a
  build-file change rather than an import rewrite - see the
  [migration note](docs/reference/migration.md#slice-1-llm4s-rag-and-llm4s-knowledgegraph).

  `llm4s-core` sheds six of the heaviest dependencies in the build: **Tika, POI, PDFBox,
  jsoup, AWS S3 and AWS STS**. A build that depends on `llm4s-core` and used any of them
  transitively now has to declare them.

  `org.llm4s.knowledgegraph.graphrag` keeps its package name but ships in `llm4s-rag`.
  `GraphRAG` imported `vectorstore` while `rag` imported `graphrag`, which made the two
  modules inseparable; moving that one file broke the cycle.
- **A guardrail Block finishes the run and leaves the thread usable** ([#1328](https://github.com/llm4s/llm4s/issues/1328),
  slice 2 of [#1326](https://github.com/llm4s/llm4s/issues/1326); design §4.13, decision of
  [#1322](https://github.com/llm4s/llm4s/pull/1322)): a `beforeAgent` or `afterAgent` failure, and a blank answer from
  `afterAgent`, used to leave the thread `Running` with no way forward and, for an output Block, the blocked answer in
  state. It now ends the run as a *finished* failure. The run returns the guardrail's own error, no longer wrapped in
  `GraphError.NodeFailed`; the thread's closing checkpoint is the new `CheckpointStatus.Failed`; `start` accepts such a
  thread like a `Completed` one and `recover` refuses it with `NothingToRecover`. An output Block removes the blocked
  turn from the history - the input, the tool calls and results, and the answer - through the new
  `MessageUpdate.RemoveTurn`, so no blocked content is stored; an input Block stores nothing. A cancellation from a
  boundary hook is not a Block, and a tool or model-wrapper failure still leaves the run `Running` for `recover`. The
  node-level form is `NodeResult.Block(update, error)`: the superstep commits `update` and every sibling's result, then
  the run ends. **Breaking:** `CheckpointStatus` and `NodeResult` gain a case, so an exhaustive `match` over either needs
  one more branch; `Checkpoint.CurrentFormat` is 4 (a checkpoint written by this build is refused by an older one, and
  older checkpoints migrate unchanged); code that read a guardrail failure out of `NodeFailed` reads the error itself.
- **Graph kernel completion: per-node retry and cache policy, Mermaid export, growth-prone run types**
  ([#1327](https://github.com/llm4s/llm4s/issues/1327), `llm4s-agent`, Experimental; design §4.11).
  `GraphBuilder.implement`, `node` and `resumeNode` take `retry: RetryPolicy` and
  `cache: Option[CachePolicy]`, both defaulted, so a graph that sets neither runs as before.
  - **Retry.** A node that throws or returns `NodeResult.Fail` runs again, inside the run, while its error
    passes `retryOn` (default: recoverable errors) and attempts remain, waiting an exponential, capped
    backoff (`FiniteDuration`s, no jitter). The wait is interruptible, so cancelling the run or its deadline
    ends the retries. A cancellation, a result the kernel rejects and a suspension are never retried. A failed
    attempt's `RunContext.emit` events are discarded, so a retry commits one attempt's events. `recover` still
    re-runs a failed task, now with the node's full policy; it used to give it one more try.
  - **Cache.** A node declared a function of its input is answered from memory for an input it has seen: the key
    is the node, its input schema version and the encoded input, the entry's life is an optional `ttl` and
    `maxEntries` (least recently used out). The cache is process-local and is not checkpointed; only `Continue`
    results are stored; a hit does not run the node (so no `emit` or `progress`) but leaves an ordinary pending
    write and `TaskCompleted` event. Neither policy is part of the structural fingerprint.
  - **`CompiledGraph.toMermaid`** draws the declared structure as a flowchart, deterministically (nodes and
    joins by id, a node's edges by declaration); routes returned at run time are not drawn.
  - **Breaking (pre-1.0, no shims).** `ToolContext`, `GraphError.ToolFailed`, `ModelRequest` and
    `ToolCallRequest` have a private constructor and no public `copy`: build them with `X(...)` and change
    them with `withY(...)` (`ToolContext.approved`, `ModelRequest.tools` default). `ToolContext.toolCallId`
    and `GraphError.ToolFailed.tool` / `.toolCallId` are the new opaque `ToolCallId` and `ToolName` (`.value`
    for the string; `ToolCallId(call.id)` to make one), and `ToolCallRequest` gains `toolCallId` and `toolName`.

- **Embedding, reranker, MCP, image and speech clients return `CancelledError` when interrupted**
  ([#1331](https://github.com/llm4s/llm4s/issues/1331), slice 5 of [#1326](https://github.com/llm4s/llm4s/issues/1326);
  design §4.12): until now only core and the chat clients did, and the rest flattened an interrupt into an error of
  their own - an `EmbeddingError`, a `RerankError`, `UnknownError`, `Transport error: ...` - or, worse, reported a
  cancelled call as a success. Now an interrupted call returns `Left(CancelledError)` with the thread's interrupt flag
  still set, promptly, and never retried. Voyage, Jina, Ollama, OpenAI and Cohere embeddings, `CohereReranker`, every image
  generation and vision client, the MCP transports, client and registry, and Whisper and Tacotron2 follow the rule; the
  five cloud speech clients already did (now pinned by a spec). Behaviour changes: `LLMReranker` no longer carries
  on through the remaining batches, and returns `Right`, after a cancelled one (an ordinary batch failure still gets
  neutral scores); `MCPClientImpl.getTools` still swallows other failures into an empty list but returns a
  cancellation; `MCPToolRegistry` reports an interrupted MCP call as cancelled (never "no such tool" or a failed
  tool) and a cancelled stdio startup stops the half-started server; Ollama embeddings stop at the first failed text;
  Whisper and Tacotron2 stop the program they started when interrupted, where it used to be left running.
  **Breaking, no shims:** `ImageGenerationError` is an `LLMError`, and the cases whose names `org.llm4s.error`
  also uses carry an `Image` prefix - `ImageAuthenticationError`, `ImageRateLimitError`, `ImageServiceError`,
  `ImageValidationError`, `ImageUnknownError` - since a match on the wrong one of two same-named `LLMError`s compiles
  and never fires. `ImageServiceError`'s second field is `statusCode` (`code` is the derived `Option[String]`), and
  the image generation clients return `Either[LLMError, _]`, so a match on their result needs a case for other
  errors. Being an `LLMError`, each case says whether trying again can help, which `LLMError.isRecoverable` needs (it
  threw a `MatchError` on an image error otherwise): `ImageRateLimitError` and an `ImageServiceError` with a transient
  status (`0`, `408`, `429` or any `5xx`) are `RecoverableError`; the other `ImageServiceError`s and every other case
  are `NonRecoverableError`. `ImageServiceError` is a sealed type with two cases (`TransientImageServiceError`,
  `RejectedImageServiceError`) behind `ImageServiceError(message, status)` and `case ImageServiceError(message, status)`;
  `ToolRegistry` restores the interrupt flag when a tool throws an interruption wrapped in another exception (it
  already did for a bare `InterruptedException`), as `MCPToolRegistry` does; `MCPTransportImpl.sendRequest`, `sendNotification`,
  `MCPClient.initialize` and `getTools` return `Result` instead of `Either[String, _]` (read the old string as
  `error.message`; the messages are unchanged); an embedding provider's `embed` can return
  `Left(CancelledError)` where it returned only an `EmbeddingError` (its declared type, `Result[EmbeddingResponse]`,
  is unchanged). `llm4s-provider-testkit` gains `assertCallCancelsWhenInterrupted` and
  `assertEmbeddingCancelsWhenInterrupted`.
- **`ToolHints` are read from MCP tool annotations** ([#1331](https://github.com/llm4s/llm4s/issues/1331); design
  §4.12): `llm4s-mcp` reads `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint` and `title` from the
  annotations a server attaches to a tool (`MCPToolAnnotations`, leniently: an unknown key or a hint of the wrong type
  is ignored) and fills in the specification's defaults for the rest; `MCPClient.getToolHints` and
  `MCPToolRegistry.toolHints(name)` return them, and `AgentTool.fromToolFunction(tool, hints)` attaches them.
  **A server's annotations are untrusted by default**, as the MCP specification requires: `ApprovalMiddleware.unlessReadOnly`
  skips approval for a read-only tool, so a server that marked `delete_everything` read-only could have run it
  unapproved. Hints are reported only for a server configured with `MCPServerConfig(..., trustAnnotations = true)`
  (also a parameter of `stdio`, `streamableHTTP` and `sse`; new field, default `false`); for any other the registry and
  the client report none, so `ToolHints.default` (approval required) applies. A listing that fails, or `close()`,
  clears the hints, so a tool a server no longer advertises keeps none. **Breaking, no shim:** `ToolHints` moves from `org.llm4s.agent.graph.tool` in `llm4s-agent` to
  `org.llm4s.toolapi.ToolHints` in `llm4s-core` (`@Experimental`), because `llm4s-mcp` cannot depend on the agent runtime.

### Removed
- **`WorkspaceConfigSupport.loadSandboxConfig`** ([#1730](https://github.com/llm4s/llm4s/issues/1730)): it read a
  sandbox profile from the client's `llm4s.workspace.sandbox.profile`, which nothing passed to the runner and nothing
  called, so it suggested a client setting that enforced nothing. What the runner enforces is decided only by its own
  `WORKSPACE_SANDBOX_PROFILE` and `WORKSPACE_EXTRA_COMMANDS`. **Migration:** set `WORKSPACE_SANDBOX_PROFILE` on the
  runner's container (`ContainerisedWorkspace` starts it), and use `WorkspaceSandboxConfig.fromProfileName` and
  `WorkspaceSandboxConfig.validate` to turn a profile name into a config in your own code.
- **Orchestration: `PlanRunner`, `DAG`, `TypedAgent`, `Policies`, `OrchestrationError` and `CancellationToken`**
  ([#1330](https://github.com/llm4s/llm4s/issues/1330)): `org.llm4s.agent.orchestration` is deleted, with
  `org.llm4s.types.PlanId` and `org.llm4s.types.AgentId` from `llm4s-core` (`org.llm4s.agent.AgentId` is the agent's
  id). Build the same flows with `GraphBuilder` and run them on `GraphRuntime`; cancel with `RunHandle.cancel()`.
  See the migration guide's "Orchestration removed (#1330)" and the `multi-agent-graph` cookbook recipe.
- **`ToolCallPolicy` and `PolicyDecision`** ([#1279](https://github.com/llm4s/llm4s/issues/1279)),
  with `ApprovalSource.Policy` and `ToolLoop.build`'s `policy` parameter, replaced by
  `AgentMiddleware`. Migration: a policy becomes an `AgentMiddleware` overriding `wrapToolCall`:
  `Allow` is `next()`, `Deny(reason)` is `ToolOutcome.Error(s"Denied: $reason")`,
  `RequireApproval(reason)` is `if context.approved then next() else
  ToolOutcome.NeedsApproval(reason)`, or use `ApprovalMiddleware`; `ApprovalSource.Policy` becomes
  `ApprovalSource.Middleware(id)`.
- **Pre-baseline API cleanup, pass 8** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  `llm4s-agent`'s console UI (`ConsoleInterface`, `ConsoleConfig`, `MessageType`) is internal, so
  fansi and cats stay out of its public API; `AssistantAgent` loses its `consoleConfig` parameter
  and its compatibility constructor. `SessionState.localDateTimeRW` and core's `SimilarityUtils`
  are narrowed to llm4s. See the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-8).
- **Pre-baseline API cleanup, pass 7: typed reported times** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  Times the library reports are a `FiniteDuration` (a point in time an `Instant`) with no unit in
  the name: `TraceEvent`'s `ToolExecuted`/`RAGOperationCompleted`/`ImageGenerationCompleted`,
  `Tracing.traceRAGOperation`, `ProviderExchange`, `AgentEvent`'s `ToolCallCompleted`/
  `AgentCompleted`, the shell and HTTP tool results, `llm4s-rag`'s benchmark timings,
  `ServiceStatus.averageGenerationTime`, `Transcription.processingTime` and the workspace
  protocol's command durations. JSON, trace and wire formats keep their keys and millisecond
  values. `RateLimitError.requestsRemaining` and `resetTime`, which nothing could set, are removed.
  See the [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-7).
- **Pre-baseline API cleanup, pass 6: typed times** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  A time the caller supplies is a `FiniteDuration` (a point in time an `Instant`), and names drop
  their unit suffix. `RateLimitError.retryAfter` - documented as seconds, used as milliseconds -
  `RecoverableError.retryDelay` and every retry consumer carry a `FiniteDuration`
  (`RateLimitError("openai", 1.second)`); `resetTime` is an `Instant`; `ReliableClient` and
  `ErrorRecovery.CircuitBreaker` take an `Instant` clock and sleep a `FiniteDuration`; the
  reliability config, `RetryPolicy` delays and `TimeoutError` narrow `Duration` to
  `FiniteDuration`; `AgentTimeoutError` carries a `FiniteDuration`. The Beta modules follow
  (`timeoutMs` -> `timeout`, `delayMs` -> `delay`, `throttleSeconds` -> `throttle`, ...) in
  `llm4s-agent-tools`, `llm4s-rag`, `llm4s-mcp`, `llm4s-image` and the workspace. Defaults,
  HOCON keys and wire formats are unchanged. See the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-6).
- **Pre-baseline API cleanup, pass 5** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  The growth-prone data types (`CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`,
  `ModelCapabilities`, `ModelMetadata`, `ProviderConfigSpec`, `EmbeddingConfigSpec`,
  `ProviderFeatures`, `NamedProviderConfig`, `ReliabilityConfig`, `CircuitBreakerConfig`,
  `RateLimitConfig`, `ContextConfig`) get private constructors, defaulted companion `apply`s and
  `with*` setters, so a field can be added after the baseline without a binary break; `.copy` is
  no longer public. `StreamingAccumulator` is `final` (`create()`), its getters drop the `get`
  prefix, and `snapshot()`/`AccumulatorSnapshot`/`withInitialState` go.
  `TransformationResult.warnings` (never filled) goes, `transform` takes `transformer` before
  `dropUnsupported`, and `getDisallowedParams` is `disallowedParams`. `Llm4sConfig.providerFrom` /
  `apiKeySourcesFrom`, which exposed pureconfig, are internal. `Llm4sHttpClient` returns `Result`
  from every request method and never throws for a transport failure (`TimeoutError`,
  `NetworkError`, `ValidationError`, `ExecutionError`); timeouts are `FiniteDuration`;
  `HttpRawResponse` and `StreamingHttpResponse` carry headers; `getResult` is removed.
  `HttpErrorMapper.mapHttpError` takes the response headers, and a 429's `Retry-After` (seconds or
  HTTP date) becomes the `RateLimitError`'s delay for every built-in provider. See the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-5).
- **`NamedProviderConfig` carries no vendor-specific fields** - pre-baseline API cleanup, pass 4
  ([#1133](https://github.com/llm4s/llm4s/issues/1133)). `organization`, `endpoint`, `apiVersion`,
  `contextWindow` and `reserveCompletion` are removed from `NamedProviderConfig` and are now
  provider-specific keys declared in their providers' `ProviderConfigSpec.extras`: `endpoint`
  (required) and `apiVersion` by Azure, `organization` by OpenAI, Requesty and OpenRouter,
  `contextWindow` and `reserveCompletion` by the generic `openai-compatible` provider. HOCON is
  unchanged; read them with `section.extra("organization")` etc. `ProviderConfigSpec.requiresEndpoint`
  and `endpointDescription` are removed (declare a required `ProviderConfigKey`); `BuiltinKeys` is
  now `provider, model, baseUrl, apiKey, headers`. `ProviderModelListers` (`openAICompatible`) moved
  from `llm4s-core` to `llm4s-openai-compatible`, same package, and gained a `sectionHeaders`
  parameter; it no longer sends `OpenAI-Organization` by itself
  (`ProviderModelListers.openAIOrganizationHeader` does, for the providers that declare it). These
  keys in a section for any other provider are now reported as unknown and ignored. See the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-4).
- **Pre-baseline API cleanup, pass 3** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  `llmconnect.middleware` (pipeline, caching, logging, metrics, redaction, sanitisation,
  request-id and rate-limiting middleware) is removed: it had no users and duplicated `caching`
  and the metrics every client records. `ReliableProviders`, `ReliabilitySyntax` and
  `ReliableClient`'s companion factories go too; use `new ReliableClient(...)`. OpenAI's
  o-series and `max_completion_tokens` rules move from core's `RequestTransformer` to
  `llm4s-openai` (`requiresMaxCompletionTokens` leaves the trait and `TransformationResult`;
  `DefaultRequestTransformer` is package-private; new `RequestTransformer.adjusted` hook).
  `ResponseFormatMapper` and `ToolCallDeserializer` move to `llm4s-openai-compatible`;
  `ProviderResultOps` is `private[llm4s]`. The remaining provider plumbing is documented as a
  frozen provider-author SPI (`docs/guide/writing-a-provider.md`). Source break (pre-MiMa); see
  the [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-3).
- **Pre-baseline API cleanup, pass 2** ([#1133](https://github.com/llm4s/llm4s/issues/1133)).
  `ToolRegistry#getToolDefinitionsSafe(provider)`, core's last switch over provider names, is
  removed: use `getOpenAITools()`. `CancellationToken`'s `cancellationFuture`,
  `cachedCancellationFuture`, `throwIfCancelled()` and `CancellationException` give way to
  `whenCancelled: Future[Unit]`. `SqlIdentifier`, `ChunkingUtils` and `RateLimitedLogger` move to
  `llm4s-rag`, `ManagedResource` to `llm4s-speech` and `LiftToResult` to `llm4s-observability`,
  their only consumers, with unchanged packages; `ManagedResource` keeps only the factories speech
  uses, and loses `map`/`flatMap`, which never released the underlying resource. Source break
  (pre-MiMa); see the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-2).
- **Pre-baseline API cleanup, pass 1: dead, deprecated and accidental public API leaves the
  spine** ([#1133](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540)). Before
  0.5.0 sets the MiMa baseline, `llm4s-core` and `llm4s-agent` drop what nothing used or what was
  already deprecated, so it is not frozen:
  - `org.llm4s.types` keeps the `Result` type and its syntax and the newtypes the library uses;
    117 speculative aliases and wrappers go (MCP, image, audio, video, fine-tuning, plugin,
    workflow, code-gen, cache, metrics, HTTP and auth types, `Map[String, Any]` aliases, and
    `CompletionId`, `ToolName`, `ToolCallId`, `Url`, `PaginationInfo`, `CompressionTarget`,
    `TokenEstimate`, `ContextSummary`, `JwtToken`, `OAuthToken`). Several shadowed real types
    (`CancellationToken`, `CacheConfig`) or `scala.concurrent.duration.Duration`.
  - `llmconnect.utils.{ConnectionStatus, ProviderCapabilities, ClientHealth}`,
    `llmconnect.streaming.StreamingOptions` and `identity.{RuntimeId, ModelId}`: never used.
  - Deprecated members: `Result.fromTry`, `LLMError#isRecoverable` (use
    `LLMError.isRecoverable(e)` or match on `RecoverableError`), `LLMError.fromThrowable`,
    `ToolBuilder#build()`, `ToolRegistry#getToolDefinitions`, `LLMCompressor.compress` with
    `LLMCompressedConversation`, and the ten `Agent` overloads taking `debug`/`tracing`/
    `traceLogPath` instead of an `AgentContext`.
  - `ContextConfig.enableRollingSummary`, which nothing read, and `ContextConfig.legacy`.
  - cats on the public surface: `Safety.sequenceV`, the implicit `LLMError.llmErrorShow` and
    `LLMErrorDisplayOps` (`.show`/`.display`; use `formatted`).
  - Now `private[llm4s]`: `RateLimitedLogger`, `ProvidersConfigModel.RawNamedProviderSection` and
    `RawProvidersConfig`; in `llm4s-agent`, `MDCContext` and `assistant.ShowInstances`.

  Source break (pre-MiMa); see the
  [migration note](docs/reference/migration.md#pre-baseline-api-cleanup-pass-1).
- **`org.llm4s.rag.EmbeddingProvider` is gone; RAG names embedding providers by id** - the last
  closed provider list, deferred from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)).
  `llm4s-rag` kept its own `OpenAI` / `Voyage` / `Ollama` ADT alongside the `ProviderRegistry`,
  so an embedding provider from its own module could not be named through it, and it named
  `ollama` whether or not `llm4s-ollama` was present. `RAGConfig.embeddingProvider` is now a
  `ProviderId`, `withEmbeddings` takes the id as a string (`.withEmbeddings("openai")`), and
  `RAG.build` resolves it through an implicit `ProviderRegistry` - an id nothing registers fails
  with the registry's "not registered" error. The per-provider model and dimension tables in
  `RAG` go with it: without an explicit model, RAG uses the resolved config's model, then the
  provider's own default. Source break, no shim; see the
  [migration guide](docs/reference/migration.md#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).
- **The two document extractors are now one.** `org.llm4s.rag.extract.DefaultDocumentExtractor`
  and `org.llm4s.llmconnect.extractors.UniversalExtractor` were independent implementations
  of the same job - each constructing its own `Tika`, defining its own MIME constants, and
  carrying its own PDFBox and POI paths. They are replaced by
  `org.llm4s.extract.TikaDocumentExtractor` (documents) and `org.llm4s.extract.MediaExtractor`
  (the image/audio/video ADT). `org.llm4s.llmconnect.model.ExtractorError` is gone; failures
  are `org.llm4s.error.ProcessingError` like the rest of the library.

  This is the one deliberate source break in the modularisation programme: merging two public
  objects cannot be done compatibly, which is the argument for doing it before 1.0 rather than
  discovering the duplicate after the compatibility promise is made.
- `EmbeddingClient.encodePath` moves to `org.llm4s.rag.embed.FileEmbedder.encodeFromPath`, and
  its six parameters - including an `experimentalStubsEnabled: Boolean`, a deployment decision
  that was arriving at every call site - become a `FileEmbeddingConfig`. `EmbeddingClient`
  keeps the pure vector API. Nothing in the library depended on the file-embedding path; its
  only caller was a sample.
- `Llm4sConfig.pgSearchIndex()` becomes `PgSearchIndexConfigLoader.default()`. It returned
  `SearchIndex.PgConfig`, a `llm4s-rag` type that `Llm4sConfig` can no longer name from
  `llm4s-core`. The loader keeps its `org.llm4s.config` package and its `load(source)` method.

### Fixed
- **Security - workspace runner: an `mv` or `cp` of several sources cannot reach outside through a name an earlier
  source's operation makes** ([#1776](https://github.com/llm4s/llm4s/issues/1776)): the command policy checked every
  path against the file system as it was before the command ran, but `mv` and `cp` handle their sources one at a time.
  With `sub/l` -> `../outside` (inside the workspace while in `sub`), `mv sub/l l/secret.txt .` moved the link to
  `./l`, where it points out, and then moved `outside/secret.txt` into the workspace; GNU `cp -P` / `-d` / `-R` / `-a`
  could copy such a link and read a later source through the copy. Now, for an `mv` or `cp` of two sources or more,
  each source is taken to change its name in the destination directory (and any name starting with it, for
  `--backup`) and, for `mv`, its own entry; every other path argument is walked, links followed, and the command is
  refused (`ARGUMENT_NOT_ALLOWED`) when it looks up one of those entries, comparing names case- and
  normalisation-insensitively and directories by identity. An `mv` that moves a source holding the working directory
  is refused too. The existing refusal of a link-preserving `cp` of two sources with the same name now compares the
  names as the destination's file system does, ignoring letter case and Unicode normalisation (and, on Windows,
  taking a name with `~` for any): on macOS `cp -P a/b/x c/X d` copied the link `x` into `d` and then wrote `c/X`
  through it. Run one command per source instead. `rm`, `mkdir`, `touch` and `chmod` create no link and are
  unchanged. See [Several sources in one command](docs/reference/workspace-sandbox.md#several-sources-in-one-command).
- **Workspace runner: an agent can remove or rename a symbolic link that points out of the workspace**
  ([#1730](https://github.com/llm4s/llm4s/issues/1730)): the command policy resolved every argument through its
  links, so `rm outlink` and `mv outlink x` were refused (`PATH_ESCAPE_ATTEMPT`) although removing or renaming a link
  never touches what it points to, and an agent could not clean such a link up. On POSIX, an operand of `rm` or
  `unlink`, or a source of `mv`, whose last component is itself a symbolic link is now judged by the directory holding
  it (`lstat` semantics), over both the direct `executeCommand` path and the WebSocket executor, when: it has no
  trailing `/` (`mv outlink/ x` moves the directory the link points to, `rm -r outlink/` empties it); the directory
  holding it is inside the workspace under both readings of the path rule and they name the same directory; `rm` is
  not recursive; for `mv`, it is not the destination, which still follows links (`mv a.txt outlink`,
  `mv -t outlink a.txt` stay refused); and GNU's and macOS / FreeBSD's option tables both parse the arguments exactly
  and agree on the operands, so a GNU-only form (`rm --force outlink`, `mv -T`, `mv -t dir outlink`) keeps the link
  refused. A dangling link and the first link of a chain can be removed too. A link moved by an `mv` of several
  sources cannot be reached through by a later source of the same command (`mv evil d/evil/secret.txt d` is refused,
  see the #1776 entry above). Windows is unchanged: there a link or junction keeps the path rule. `cp` is unchanged. See
  [Removing a link](docs/reference/workspace-sandbox.md#removing-a-link). The same issue's follow-ups to #1720: the
  inline comments in `WorkspaceAgentInterfaceImpl.prepareCommand` now number the working-directory check layer 6 and
  `CommandPolicy` layers 7-9, as its Scaladoc does; and `WorkspaceConfigSupport.loadSandboxConfig`, which nothing
  called, is removed (see Removed).
- **Security - `llm4s-agent-tools` / `llm4s-core`: the HTTP tool forwards only safe headers across origins and
  refuses the remaining special-purpose address ranges** ([#1734](https://github.com/llm4s/llm4s/issues/1734)):
  on a redirect that left the original origin, `HTTPTool` stripped only `Authorization`, `Cookie` and
  `Proxy-Authorization`, so `X-Api-Key`, `Api-Key`, `X-Auth-Token`, `X-Goog-Api-Key` and any other credential header a
  model put in a call reached the other origin. From the first hop that leaves the origin (scheme, host and port, so a
  downgrade from `https` to `http` counts) and on every later hop, only the caller-set headers on the new
  `HttpConfig.redirectSafeHeaders` are sent (default `Accept`, `Accept-Language`, `Accept-Encoding`, `User-Agent`,
  and `Content-Type` with a re-sent body), and a credential header - one of those three, a name core's `Redaction`
  treats as sensitive, or one ending in `token` or `key` - never is, even if listed. Same-origin hops keep every
  header, except that a hop whose body a `301` or `302` dropped, on any origin, sends no `Content-Type`, neither a
  caller-set one nor the tool's `content_type` parameter.
  **Migration:** a custom header that must follow a cross-origin redirect now has to be listed in
  `redirectSafeHeaders`. `NetworkSecurity.isBlockedIP` now also refuses `240.0.0.0/4` (with `255.255.255.255`),
  `192.0.0.0/24` and `192.88.99.0/24`, in every IPv6 form that carries them; every IPv6 address outside global
  unicast `2000::/3`, which covers the SIIT form `::ffff:0:a.b.c.d` (`::ffff:0:7f00:1` passed), the dummy prefix
  `100:0:0:1::/64` and SRv6 `5f00::/16`; and the non-global part of `2001::/23` (deprecated ORCHID and unassigned
  space), keeping its globally reachable entries, as audited against the IANA IPv4 and IPv6 special-purpose
  registries. `HttpConfig`, `HTTPTool` and `NetworkSecurity.isBlockedHostname` compare names and methods in
  `Locale.ROOT`, so under a Turkish default locale `INTERNAL.example` no longer slips past a `blockedDomains` entry
  `internal.example`. The DNS lookup's uninterruptibility (a worker may outlive the deadline by the resolver's
  timeout) is documented in `NetworkSecurity` and the built-in tools guide.
- **Security - workspace runner: an option's value is consumed once, so every file `sort` reads is path-checked**
  ([#1763](https://github.com/llm4s/llm4s/issues/1763)): the command policy did not check the argument after a bare
  `sort -t` / `--field-separator`, taking it for the separator, even when an earlier option had already taken that
  `-t` as its own value. Then the next argument is a file sort reads, and it was never held to the workspace:
  `sort -T -t /etc/passwd` and `sort --random-source -t /etc/passwd` printed the file, over both the direct
  `executeCommand` path and the WebSocket executor. On POSIX, `sort`'s arguments are now parsed as `getopt` parses
  them, with GNU and BSD sort's option tables (`-k`, `-o`, `-S`, `-t`, `-T`, GNU's `-y`, and the long options that
  take a value, under any unambiguous abbreviation, attached, after `=` or as the next argument, clustered, up to the
  `--` that ends the options), and every operand and option value is checked; the separator is left out only when it
  really is `-t`'s value under every parse. It is checked too when an option is unknown or ambiguous, when an argument
  starts with `+` (BSD sort's obsolete `+POS1 -POS2`, which it rewrites before parsing, made `sort -T +0 -1t file`
  read `file`), and after the first operand (GNU sort with `POSIXLY_CORRECT` reads every later argument as a file), so
  `sort a.txt -t /` is now refused; write `sort -t / a.txt`. The same audit found and fixed two more cases: `cp`'s
  destination checks missed a `-R` after a `--` that `-S` took as its suffix (`cp -S -- -R src dst`), and GNU's
  `--path` spelling of `--parents`; and a short option such as `-f` was checked only by its tails, not as the whole
  name a program opens when it reads it as a file (`cat a.txt -f` on BSD, `cp -t -d`, `sort -T -d`). On Windows the
  argument after `--field-separator` is now checked; the other `sort.exe` rules are unchanged. `uniq`, `git branch`
  and the shell tool (`llm4s-agent-tools`, which checks the argument after `sort -t` already) needed no change. See
  [Option values](docs/reference/workspace-sandbox.md#option-values).
- **Security - `llm4s-agent-tools`: the shell tool checks option values attached to their flag**
  ([#1723](https://github.com/llm4s/llm4s/issues/1723)): under `ShellConfig.pathPolicy`, a value given as its own
  argument (`grep -f lout`) was held to the policy, but one attached to its flag (`grep -flout`, `grep -iflout`,
  `grep --file=lout`, `grep --fil=lout`, `sort --random-source=lout`) was refused only if it held a `/` or `\`, so a
  link out of the allowed directory, a blocked file in the working directory, or `..` passed. Every attached value is
  now resolved and checked as a path, like the workspace runner's command policy does (#1720): the value after `=` of
  a long option, and every tail of a short-option cluster, since any letter in it may take the rest as its value. A
  text value that happens to name such a file (`grep -e..`) is refused too. The checks run before the command
  starts, outside `ShellConfig.timeout`, so their cost is bounded per command, as the workspace runner's is: an
  argument over 4096 characters is refused, a value that is one plain path component costs one lookup (whether
  anything is in the working directory under that name; if nothing is, it cannot be a link, and the policy is
  applied to its spelling with no further lookup, which is exactly the full check's verdict, with no assumption about
  how long a file name can be; if something is, it is resolved in full), each distinct path is checked once, and a
  command whose checks would take more than 20000 file-system lookups is refused as too costly to check. A command of
  200 flags of 4096 letters is checked in well under a second, where it took over three minutes on macOS before these
  bounds, and one of 200 distinct such flags is refused as too costly just as fast. The value of `sort -t`
  is a separator, not options or a path (`sort -to`, `sort -t/` run). The refused options (`file -C`/`-m`/`-M`/`-f`,
  `date -f`/`-r`, `wc --files0-from`) are now also matched whatever the case of the program name or a Windows executable suffix (`FILE -C`, `file.exe -C`, which run `file -C` on Windows
  and on macOS's default file system), and `sort -o`, `--output`, `-T`, `--temporary-directory`,
  `--compress-program` and `--files0-from` (which write a file or temporary files, run a program, or read the names
  of the files to sort from a file the command does not name) join them, for allowlists that add `sort`, in every
  spelling (`-roout`, `--out=x`, `--files0=x`, after a consumed `--`), with or without a path policy; on Windows,
  where `sort` may be `sort.exe`, so do `sort -t` and the `/O` and `/T` switches.
- **`InMemoryStore` keyword search matches whole words, not substrings**
  ([#1594](https://github.com/llm4s/llm4s/issues/1594)): the store split the query on whitespace and tested each piece
  with `String.contains`, so short words matched inside longer ones - `i` in "Berlin", `or` in "works", `do` in
  "doing" - and punctuation stayed on the query's words, so "Which language do I prefer, Scala or Java?" returned
  "Works in the Berlin office" and `java?` never matched "Java". Query and memory are now split into words exactly as
  `SQLiteMemoryStore`'s FTS5 index (`unicode61`, `remove_diacritics=1`) splits them - the two stores now find the same
  memories for a query (ranked differently), which a new spec checks for every code point and query by query - and a
  memory scores the share of the query's distinct phrases (its whitespace-separated pieces, whose words must appear
  adjacent and in order) it contains, not of its words. A word is a run of letters, digits
  and private-use characters in any script; case is ignored (per code point, so the Greek final sigma matches `σ`), and
  an accent only on a Latin letter that has exactly one (`ecole` matches "école"; `αθηνα` does not match "Αθήνα", `мои`
  does not match "Мой", `viet` does not match "Việt"); other combining marks separate words (Devanagari "मुझे" is "म"
  and "झ"); and a phrase with punctuation inside needs its words adjacent (`berlin-based` does not match "based in
  Berlin").
  **Behaviour change:** `InMemoryStore.search`, `EmbeddingMemoryStore`'s keyword fallback and
  `SimpleMemoryManager.getRelevantContext` return fewer memories: a query word must now be a whole word of the memory,
  so `scala` no longer matches "scalability" and `prefer` no longer matches "Prefers" (there is no stemming). Case
  folding no longer depends on the JVM's default locale. The cookbook's `MemoryRecipe` no longer strips short words and
  punctuation from the question (`contentWords` is gone); it passes the question as it is.
- **Security - workspace runner: WebSocket commands go through the command policy, not a shell**
  ([#1756](https://github.com/llm4s/llm4s/issues/1756)): `RunnerMain` served the WebSocket protocol's
  `ExecuteCommandCommand`, the path `ContainerisedWorkspace.executeCommand` and `executeCommandWithStreaming` take, by
  running the raw command string through `sh -c` (`cmd.exe /c` on Windows) with the client's `environment` copied into
  the child unfiltered. Only `shellAllowed` was checked, so the `allowedCommands` allowlist, the forbidden shell
  characters, the per-program option, path, git and Windows rules of #1715, the environment allowlist and the
  null-device standard input of #1728 applied only to direct calls on `WorkspaceAgentInterfaceImpl`, and an agent
  driving a containerised workspace could run any program (`ls; cat /etc/passwd`, `cat a | sh`, `echo $(id)`,
  `find . -delete`, `LD_PRELOAD`). Both paths now share one function that tokenizes the command, applies every check
  and builds the process from the argument vector; the WebSocket path keeps its stdout/stderr streaming,
  cancellation and exit-code and duration reporting, and refuses with a `WorkspaceAgentErrorResponse` carrying the
  direct path's code (`FORBIDDEN_CHARACTERS`, `EXECUTABLE_NOT_ALLOWED`, `ARGUMENT_NOT_ALLOWED`,
  `PATH_ESCAPE_ATTEMPT`, `ENVIRONMENT_NOT_ALLOWED`, ...) followed by `CommandCompletedMessage` with exit code 1. The
  protocol is unchanged. A working directory outside the workspace is now `PATH_ESCAPE_ATTEMPT` rather than
  `INVALID_DIRECTORY`, and a WebSocket command sent with no timeout stops at the sandbox's `defaultCommandTimeout`
  instead of running until it ends. **Migration:** WebSocket commands no longer go through a shell, so pipes,
  redirection, `;`, `&&`, `$(...)`, backquotes and variable expansion are refused rather than interpreted, and only
  programs on the runner's allowlist run. Write each command as one program and its arguments (`grep -rn TODO src`,
  not `cd src && grep -rn TODO . | head`), quoting an argument that holds spaces; send a second command as a second
  request, use `workingDirectory` instead of `cd`, and `writeFile` instead of redirection. A program the agent needs
  that is not on the profile's list, such as a build tool, is added with the runner's new `WORKSPACE_EXTRA_COMMANDS`
  (`ContainerisedWorkspace` and `CodeWorker` take it as `extraAllowedCommands`; `WorkspaceSandboxConfig.withExtraCommands`
  parses it and refuses shells and launchers such as `env` and `xargs`); `CodeGenExample` adds `sbt` this way and
  asks for `sbt compile` and `sbt run` as separate commands. The `@Workspace` suites now send argv commands (`grep zzz
  <file>` for a non-zero exit, `cat <present> <missing>` for stderr, `tail -f <file>` for the timeout), and
  `WorkspaceCommandsSpec` runs each command string they and the workspace samples send through the runner's
  WebSocket executor on every PR.
- **Memory: `getRelevantContext` no longer writes a section heading with nothing under it** ([#1580](https://github.com/llm4s/llm4s/issues/1580)):
  under a tight `maxTokens` the context assembly wrote a section heading such as `## Relevant Knowledge`
  before checking whether its first memory fitted, so the result could be headings with no entries,
  and the `# Retrieved Context` line was not counted, so the text could run past `maxTokens * 4`
  characters. A heading is now written only together with its first memory, a section whose first
  memory does not fit is left out, the result is `""` when no memory fits, and the whole text stays
  within the budget. With a budget that fits everything the output is unchanged.
- **Workspace runner: a command that reads standard input gets end-of-file at once instead of hanging until the
  timeout** ([#1728](https://github.com/llm4s/llm4s/issues/1728)): `executeCommand` left the child's standard input an
  open pipe that nothing wrote to or closed, so `cat` with no operands, `cat -`, `sort`, `uniq`, `wc`, `head`,
  `tail` or `grep x` with no file (and `sort` or `findstr x` on Windows) blocked until the command timeout (10 s
  under the locked profile, 30 s under permissive) and failed with `TIMEOUT`, holding a runner thread meanwhile.
  The child's standard input is now the null device (`/dev/null`, or `NUL` on Windows, chosen by the host the runner
  runs on), so such a command sees an empty input and returns its normal result.
- **Security - workspace runner: allowlisted commands can no longer write, delete or run other programs through their
  arguments** ([#1715](https://github.com/llm4s/llm4s/issues/1715)): `executeCommand` checked only the executable
  name against `allowedCommands` and a set of shell metacharacters, so programs on `WorkspaceSandboxConfig.ReadOnlyCommands`
  could delete files (`find -delete`, `git clean`, `git checkout -- .`, `git reset --hard`), write them (`find -fprint`,
  `sort -o`, `uniq in out`, `git diff --output`), run programs that are not on the list (`find -exec`,
  `git -c alias.x=!cmd x`, `git -c core.fsmonitor=cmd status`, `git grep -O`, `git diff --ext-diff`, or `GIT_EXTERNAL_DIFF` /
  `PAGER` / `LD_PRELOAD` in `environment`), and read or write outside the workspace (`cat /etc/passwd`,
  `grep -r x ..`, a symbolic link out of it). The runner now also checks, before the process starts:
  - *Options* (`ARGUMENT_NOT_ALLOWED`): `find -delete -exec -execdir -ok -okdir -fprint -fprint0 -fprintf -fls
    -files0-from -follow -L`; `sort -o --output --compress-program --files0-from` (and Windows `sort /O`);
    `wc --files0-from`; `ls -L --dereference`; `grep -R --dereference-recursive -S`; `cp -L -H -s --dereference
    --symbolic-link`; `chmod -L -H --dereference`; `hostname` with an operand, `-F` or `-b`; `uniq` with more than
    one operand (every argument after the first operand counts, as BSD `uniq` does not reorder). `git` runs only `status`, `log`, `show`, `diff`, `ls-files`,
    `ls-tree`, `grep`, `blame`, `rev-parse` and a listing `branch`; refuses every global option except `--version`,
    `--no-pager`, `--no-optional-locks`, `--literal-pathspecs` and `--no-replace-objects` (so `-c`, `-C`,
    `--exec-path`, `--git-dir`, `--work-tree`, `-p`); and refuses `--output`, `--ext-diff`, `--textconv`,
    `--show-signature` and `git grep -O` / `--open-files-in-pager`; `git branch` takes the next argument as the
    value of `--merged`, `--contains`, `--points-at`, `--sort` or `--format`. Every argument is scanned, after `--` too; a
    short option is refused anywhere in a cluster (`-ro`) and a long one under any abbreviation (`--outp`), with or
    without `=value`.
  - *Paths* (`PATH_ESCAPE_ATTEMPT`, the code the file operations already use): each argument, the value of a
    `--name=value` option, and each tail of a short option (`-f/x`) is resolved from the working directory as the
    kernel resolves it - component by component, following symbolic links where they are met, so `link/..` is the
    parent of the link's target - and must stay inside the real workspace root (`sort -t` and `--field-separator`
    take a separator, not a path). The working directory is held to the same rule. `echo`, `pwd`, `whoami` and
    `hostname` are not checked. `cp`, which writes through a link it finds at the name it writes, also has the
    names it will write checked, and a recursive `cp` refuses a destination directory holding a link that leads
    outside. An argument over 4096 characters, or paths needing more than 20000 lookups, is refused
    (`ARGUMENT_NOT_ALLOWED`) rather than walked. A string that is not a valid path is refused, not thrown: an
    argument with a NUL character, or whose check fails, gets `ARGUMENT_NOT_ALLOWED`, and a working directory or file
    operation path the platform cannot parse gets `PATH_ESCAPE_ATTEMPT` (before, `InvalidPathException`, or on
    Windows an `IOError` for a drive-relative path on a drive that does not exist, such as the `G:` of
    `findstr /G:file`, escaped `executeCommand`).
    On Windows, a device or NT-namespace path (`\\?\C:\x`, `\??\C:\x`), a drive-relative path on another drive
    (`D:x`) and a wildcard argument leading outside (`..\*`) are refused, and so is an argument with a wildcard
    or `:` followed by a `..` component, or that leads outside with those characters replaced by `_`: Win32 removes
    `..` as text before it opens a name, so `type x*\..\..\outside\f` opened `..\outside\f` although the part
    before the `*` is inside. An argument holding `"` is refused on Windows (`ARGUMENT_NOT_ALLOWED`): the C runtime's
    argument parser and cmd.exe delete it as a quote, so `"..\outside\f` opened `..\outside\f` and a leading `"`
    hid an absolute path. On Windows, the built-ins started through `cmd.exe /c` (`dir`, `type`, `copy`, `move`,
    `echo`, ...) also refuse an argument holding `,`, `=`, `(`, `)`, `@`, `!`, a control character or a non-ASCII
    space (`ARGUMENT_NOT_ALLOWED`; `echo` may print `,`, `=` and parentheses): `ProcessBuilder` quotes an argument
    only for a space, tab, `"`, `<` or `>`, and cmd.exe splits on `,`, `;`, `=`, VT, FF and 0xFF too, so
    `type a.txt,..\outside\f` typed `..\outside\f` after `a.txt`. A path must also stay inside under that lexical reading (`..` removed as text, then
    links resolved) as well as the kernel's, so `l/../../x` with `l` -> `a/b` is refused on every platform.
  - *Windows* (`ARGUMENT_NOT_ALLOWED`): the policy refuses what it cannot reason about, and only the forms
    `docs/reference/workspace-sandbox.md#on-windows` lists are supported. Refused: a device name as a path component
    (`nul`, `sub\con`, `NUL.txt`, `COM1`, `LPT¹`, `CONIN$`), a component ending in `.` or a space (Win32 strips them,
    so `outside.` opened `outside`; this refuses `git log HEAD..` too); for programs that are not cmd.exe built-ins,
    which may run under a runtime that re-parses their command line (MSYS2, Cygwin, Git for Windows), an argument
    starting with `@` (a response file) or `~`, one holding `{ } [ ] ' ( )`, a path starting with `/`, and a
    wildcard outside a last component that has a literal character other than `.` (`*`, `.*`, `*/a.txt`);
    `findstr /F:list` (any switch with `F`) and a `/D:` directory list; and `sort` `/O`, `/T`, `-O`, `-T`, `-t`,
    `--temporary-directory`. 8.3 short names and alternate data streams (`a.txt:s`, judged by the file before the
    `:`) are not refused.
  - *git's repository* (every platform): git looks for its repository in the directories above the working
    directory, so a workspace inside a larger repository ran `git show HEAD:secret`, `git diff` and `git status` on
    that repository and read files outside the workspace. The runner now starts `git` with
    `GIT_CEILING_DIRECTORIES` set to the workspace root's parent and without any inherited `GIT_*` variable
    (`GIT_DIR`-style locations, `GIT_CONFIG_*` / `GIT_CONFIG_PARAMETERS` / `GIT_CONFIG_COUNT` configuration,
    `GIT_EXEC_PATH`, object directories); refuses `git` (`PATH_ESCAPE_ATTEMPT`) when the workspace root's parent
    path holds the path-list separator (`:` on POSIX, `;` on Windows), which `GIT_CEILING_DIRECTORIES` cannot
    escape, and when the nearest `.git` inside the workspace is a `gitdir:` file, a link, or a directory whose real
    path lies outside the workspace (a Windows junction); and refuses a git argument starting with `:` (`:/`,
    `:(top)`, `:a.txt`), which git resolves from the repository's top level.
  - *Environment* (`ENVIRONMENT_NOT_ALLOWED`): `environment` may set only `LANG`, `LANGUAGE`, `LC_*`, `TZ`, `TERM`,
    `COLUMNS`, `LINES` and `NO_COLOR`.

  The checks apply to every allowlist, `ReadWriteCommands` and custom ones included (a custom program gets the path
  and environment rules). Still open: `git` reads the repository's own `.git/config` and runs its hooks, so where
  the agent can write files it can set `core.fsmonitor`, `diff.external` or a filter, or add a hook such as
  `.git/hooks/post-index-change`, that `git status` / `git diff` then runs, or point git at files outside through
  `core.worktree`, `.git/commondir` or `.git/objects/info/alternates`
  ([#1721](https://github.com/llm4s/llm4s/issues/1721)); `diff -r` follows links inside the tree it walks; a relative
  link moved to another depth by the read-write list can come to point outside (paths through it are refused); and
  the checks do not see a link made by a concurrent command.

  **Migration.** The new error codes are plain strings in the existing `WorkspaceAgentErrorResponse`, so the protocol
  is unchanged. Commands that worked before and are now refused: the forms above; any argument that is, or
  resolves to, a location outside the workspace, including a `grep` pattern or option value that starts with `/` or
  has a `..` component (write `[/]api` for `/api`); `ls -L`, `grep -R`/`-S`, `find -L`, `cp -L`/`-H`/`-s` and
  `chmod -L`/`-H`; `uniq` with an option after its file (write `uniq -c a.txt`); a link-preserving `cp` (`-R`, `-a`,
  `-P`, `-d`) of two sources with the same name, or of several sources one of which is `dir/` or `dir/.`;
  `git branch <name>` without `--list`;
  `git` subcommands other than the read ones; a `git` argument starting with `:`; `git` in a workspace that has no
  repository of its own but lies inside one (it now reports `not a git repository`); `git` in a workspace whose
  parent path holds `:` (POSIX) or `;` (Windows); on Windows, the forms listed
  above; and `environment` variables outside the list. Run writes through the
  `writeFile` / `modifyFile` operations or the read-write allowlist's own programs instead.

  The same change corrected four statements in `docs/reference/security.md` and `workspace-sandbox.md`
  ([#1716](https://github.com/llm4s/llm4s/issues/1716)): the residual-risk example `git -c core.pager=...` runs nothing
  (git starts a pager only on a terminal), so the pages use `git -c alias.x='!cmd' x` and `-c core.fsmonitor=...`; the
  client's sandbox profile does not control enforcement, only the runner's `WORKSPACE_SANDBOX_PROFILE` does; an
  unknown profile name stops the runner (and makes the client loader return a `Left`) rather than falling back to
  `permissive`; and `SQLiteMemoryStore.inMemory()` is called as it is, not given `":memory:"`.
- **A subscription receives every live event sent after `subscribe` returns** ([#1731](https://github.com/llm4s/llm4s/issues/1731)):
  `GraphRuntime.subscribe` returned before its dispatcher joined the event hub's live set, which it
  did only after replaying the log, so live events (`RunContext.progress`, `StreamEvent.Live`) sent in
  between were silently lost - subscribing and then calling `start` lost the run's progress whenever
  the store's first read was slow or the dispatcher thread started late. The subscription now joins
  the live set before `subscribe` returns. Live events sent while it replays are held, up to its
  `capacity` (the rest dropped and reported as a `StreamEvent.LiveGap`, as for a full queue), and
  delivered in their place among the durable events: after those committed before them, before
  those committed after. Durable delivery is unchanged. No API change.
- **Security - `llm4s-core`, `llm4s-agent-tools`: the SSRF guard blocks IPv6 private ranges, redirect header
  stripping is sticky, and the HTTP tool's `timeout` bounds the whole call** ([#1408](https://github.com/llm4s/llm4s/issues/1408),
  findings F5, F7 and F8; F6 remains open):
  - *F5, SSRF guard (`NetworkSecurity.isBlockedIP`).* It relied on the JDK's `isSiteLocalAddress`, which for IPv6
    matches only the deprecated `fec0::/10`, so unique-local `fc00::/7` (`fd00::1`) passed, as did IPv6 forms that
    carry a blocked IPv4 address: IPv4-compatible `::127.0.0.1`, NAT64 `64:ff9b::7f00:1` and 6to4 `2002:7f00:1::1`.
    It now also refuses `fc00::/7`, the whole IPv4-compatible `::/96`, local-use NAT64 `64:ff9b:1::/48`, Teredo
    `2001::/32`, `2001:db8::/32` and `3fff::/20` (documentation), `2001:2::/48` (benchmarking), `100::/64` (discard)
    and `0.0.0.0/8`, and judges an IPv4-mapped (`::ffff:0:0/96`), NAT64 (`64:ff9b::/96`) or 6to4 (`2002::/16`)
    address by the IPv4 address it carries, against every blocked IPv4 range. No public signature changed.
  - *F7, redirect headers (`HTTPTool`).* With `followRedirects`, `Authorization`, `Cookie` and
    `Proxy-Authorization` were stripped only on a hop whose host differed from the previous hop's, and the next hop
    was sent the original headers again, so `127.0.0.1` -> `localhost` -> `localhost` delivered `Authorization` to
    the third hop; a hop to another port, or from `https` to `http`, on the same host kept them. They are now
    stripped from the first hop whose origin (scheme, host and port) differs from the original request's, and stay
    stripped for every later hop, including one back to the original origin.
  - *F8, timeout (`HTTPTool`).* `HttpConfig.timeout` was set as the connect and the per-read timeout, so a server that
    sent a byte every 100 ms held a 500 ms call for as long as it kept sending (and each redirect hop got a fresh
    timeout). It is now one deadline for the whole call: name resolution, connecting, every redirect hop and reading
    the body. The caller gets `TIMEOUT: HTTP request did not complete within <n> ms ...` at the deadline, and the
    abandoned connection is closed. The field keeps its name and `FiniteDuration` type.
  - **Migration.** A request to a host that resolves into one of the newly blocked ranges is now refused with
    `SSRF_BLOCKED` (use `HttpConfig.withInternalIPsAllowed` deliberately if you need one; `allowedDomains` does not lift the check). A
    redirect chain that relied on credentials surviving a change of host, port or scheme, or a return to the original
    host, no longer sends them. A `timeout` sized for a slow single read may now be too short for a whole download or
    a redirect chain: size it for the entire call. A zero `timeout` used to mean "no timeout" (`HttpURLConnection`'s
    `0`); it now fails every call at once - set a large one instead.
- **`llm4s-agent`: the PII Email pattern runs in linear time; an SSN stays within a line; `UTC+5` is not a phone number**
  ([#1713](https://github.com/llm4s/llm4s/issues/1713)):
  - `PIIType.Email`, in the default type set of `PIIMasker` and `PIIDetector`, began a match attempt at every
    character of a run of letters, digits or `._%+-` and scanned to the end of the run each time, so it took
    quadratic time: 10.9 s on 20,000 `a`s, and hours on a million-character base64 blob or minified line - a
    denial-of-service risk in a guardrail that runs on every message. An attempt now starts only at the start of
    such a run or where the previous match ended (`\G`), and a million-character run takes milliseconds. An attempt
    inside a run succeeds exactly when one at its start does, so the matches are unchanged, back-to-back addresses
    such as `a@b.com_x@y.org` included. The other PII patterns were measured on million-character adversarial runs
    and are linear.
  - `PIIType.SSN` separated its digit groups with `\s`, so `123\n45\n6789` was masked as one SSN. It now takes a
    dash or horizontal whitespace (`\h`), as the phone and card patterns do: groups split by LF, CR, a vertical tab
    or a form feed are no longer joined, and groups split by a no-break space or another Unicode horizontal space
    now are.
  - `PIIType.Phone` read a time-zone offset and the date after it as an international number, so
    `UTC+5 2026-10-09 12:30` became `UTC[REDACTED_PHONE]:30`. A `+` right after `UTC` or `GMT` (any case, with or
    without one space between) no longer starts an international number; a `+` after any other word still does, so
    `a@b.com+44 20 7946 0958` and `Phone+44 20 7946 0958` are still masked. The Scaladoc now says that a separated
    number longer than 15 digits is masked up to its 15th digit and the rest kept, which is what the pattern does.
- **Streamed tool-call arguments reach `onChunk` verbatim** ([#1212](https://github.com/llm4s/llm4s/issues/1212)):
  `OpenAICompatibleClient` (DeepSeek, Z.ai, OpenRouter, Mistral, Cohere, generic) and `OpenAIClient` (OpenAI,
  Azure, Requesty) used to hand `onChunk` each tool-call argument fragment already parsed, so a fragment that was
  valid JSON on its own (`":"`, `"Paris"`) lost its quotes and a consumer concatenating the fragments got corrupt
  arguments. Each fragment now arrives as a `ujson.Str` (an empty one as `{}`), as the Anthropic and Bedrock clients
  already did; reassemble them with a `StreamingAccumulator`. The returned `Completion` was not affected. The chat
  TUI sample now reassembles them that way before tool approval and execution.
- **`llm4s-anthropic`: extended thinking with the default temperature** ([#1212](https://github.com/llm4s/llm4s/issues/1212)):
  Anthropic accepts no temperature but 1 with thinking enabled, and the default `CompletionOptions` temperature of
  0.7 made every request with a thinking budget fail with HTTP 400. `AnthropicClient` now omits `temperature` when
  a thinking budget is set.
- **`llm4s-rag`: `SentenceChunker` keeps every character of the input** ([#1718](https://github.com/llm4s/llm4s/issues/1718)):
  it split with `Regex.split` on `([.!?])(\s+)([A-Z])`, which deleted the punctuation, the whitespace and the next
  sentence's first letter at every boundary and glued the parts back together, so
  `"Hello world. Next one. Third."` became the single sentence `"Hello worldext onehird."`. Boundaries are now
  found with lookarounds, so only the whitespace between sentences is matched, and sentences are cut from the input
  itself. Sentences in a chunk keep the whitespace that separated them (it used to be one space), so with no overlap
  and no force-split sentence every chunk is a slice of the input. Abbreviations (`Dr.`, `e.g.`) only match as whole
  words, so `summr.` or `first.` no longer hide a boundary, and a closing quote or bracket after the punctuation stays
  with its sentence: `He said "Hi." Then` is `He said "Hi."` and `Then`. `ChunkerFactory.default`, `"sentence"` and
  the semantic fallback all use this chunker. **Migration:** chunk text changes for any input with a sentence
  boundary, and chunk sizes with it; indexes built with `SentenceChunker` hold corrupted text and should be
  re-chunked and re-embedded.
- **`llm4s-openai`: OpenAI embeddings reach `/v1/embeddings` with the default base URL**
  ([#1413](https://github.com/llm4s/llm4s/pull/1413)): the default `llm4s.embeddings.openai.baseUrl` is
  `https://api.openai.com/v1`, the versioned root the chat provider uses too, but `OpenAIEmbeddingProvider`
  appended `/v1/embeddings` to it and posted to `https://api.openai.com/v1/v1/embeddings`. A base URL ending in
  `/v1` now gets `/embeddings`; one without it (`https://api.openai.com`, a proxy root) still gets
  `/v1/embeddings`, so a base URL that worked before is unchanged.
- **`llm4s-rag`: `SimpleChunker` and `ChunkingUtils.chunkText` no longer throw for a very large window**
  ([#1424](https://github.com/llm4s/llm4s/pull/1424)): the window end and the next start were computed in `Int`, so
  a valid configuration such as `ChunkingConfig(targetSize = Int.MaxValue, maxSize = Int.MaxValue,
  overlap = Int.MaxValue - 1)` overflowed on the second window and `substring` threw
  `StringIndexOutOfBoundsException`. Both are now computed in `Long` and clamped to the text length; the chunks
  produced for every other configuration are unchanged.
- **`llm4s-openai-compatible`: Z.ai honours `CompletionOptions.reasoning`** ([#1681](https://github.com/llm4s/llm4s/issues/1681)):
  it used to be ignored, so `ReasoningEffort.None` still thought (Z.ai's `thinking.type` defaults to `enabled`) and
  effort levels never reached a model that takes `reasoning_effort`. Each effort now goes out in the form the
  configured GLM model documents. On GLM-5.1, GLM-5 and GLM-4.5 to 4.7, `None` sends `"thinking": {"type": "disabled"}`
  and the other levels send nothing. GLM-5.2 is sent `reasoning_effort` `none`, `low`, `medium` or, for `High`, `max`
  (Z.ai currently runs `low` and `medium` as `high` on it). GLM-5.3 always thinks and rejects `disabled`, and accepts
  only `low`, `high` and `max`, so `None` and `Low` send `low`, `Medium` sends `high` and `High` sends `max`; `None`
  on GLM-5.3 logs a one-time warning that thinking tokens are still produced. `High` is Z.ai's maximum, its default,
  so no level reasons more than `High`. Other models are sent nothing. With replayed reasoning, `thinking`
  carries both `type` and `clear_thinking`. Without a `reasoning` option the request is unchanged.
- **`llm4s-rag`: chunkers never split a surrogate pair** ([#1711](https://github.com/llm4s/llm4s/issues/1711)):
  `SimpleChunker` and `ChunkingUtils.chunkText` cut by UTF-16 index, so a window end or an overlap start could fall
  between the two halves of an astral character (emoji, CJK Extension B), leaving a lone surrogate in each chunk; an
  embedding API rejects that or replaces it with U+FFFD. `SentenceChunker` could do the same at the start of its
  overlap. Such a cut now moves back by one unit (forward only for `targetSize = 1` at an astral character, the one
  case where a chunk is two units long), so chunks stay within their size and, with no overlap, still concatenate
  back to the input. The reranker prompt, the RAGAS Langfuse observer and `BenchmarkConfig.shortName` truncate the
  same way. Text without astral characters is chunked exactly as before. `SentenceChunker`, `MarkdownChunker` and
  `SemanticChunker` keep a single word longer than `maxSize` whole, as before; this is now documented on
  `ChunkingConfig.maxSize`.
- **Cancelled and failed agent turns no longer leave threads nobody can name, and every cancel wait is bounded**
  ([#1682](https://github.com/llm4s/llm4s/issues/1682), [#1688](https://github.com/llm4s/llm4s/issues/1688);
  follow-ups to [#1330](https://github.com/llm4s/llm4s/issues/1330)'s cancellation):
  - Kotlin `AgentKt.run(query)` runs its turn on a random thread id that nothing it throws carries. Once a failed or
    cancelled turn has ended it now forgets that thread, as Scala `Agent.run(query)` and Java `JAgent.run(query)`
    do; before, the thread stayed in the agent's runtime for the agent's lifetime. A forget that is refused - the
    turn of a provider that ignores its interrupt is still running - is added to the thrown exception as a
    suppressed one. `continueConversation`, `resume` and `recover` are unchanged: their thread is the caller's.
  - `AgentStream.cancel()`, through which a cancelled Kotlin coroutine cancels its turn, waited for the turn's end
    without a bound, so a provider that ignored its interrupt hung it. It now waits up to 5 seconds for the turn to
    end, as the blocking `JAgent` calls do, then logs a WARN and returns, the thread busy (`ThreadBusy`) until the
    turn ends. Java and Kotlin cancellation now behave the same.
  - `JAgent.forget(threadId)` (`llm4s-java-api`) forgets a conversation by its thread id - one named for `stream`,
    say, whose turn failed - as Scala `Agent.forget(threadId)` does; `forget(previous)` needed a result.
  - `Agent.runMultiTurn` forgets its random thread when a follow-up turn fails or is cancelled: the `Left` it
    returns carries no thread id, so the thread could be neither recovered nor forgotten.
  - The interrupt flag a cancelled blocking call keeps is no longer lost when forgetting the one-shot turn's thread
    throws (a `Checkpointer` whose `deleteThread` throws a fatal error the runtime does not turn into a `Left`).
  - Scaladoc, the Java threading guide and the #1330 entry say the calls wait "up to 5 seconds for the turn to end":
    with tracing, delivering the ended turn's last trace events can add to that.
- **`llm4s-openai-compatible`: Z.ai keeps replayed reasoning** ([#1384](https://github.com/llm4s/llm4s/pull/1384),
  [#1411](https://github.com/llm4s/llm4s/pull/1411)):
  a request that sends an earlier turn's `reasoning_content` back now also sets `"thinking": {"clear_thinking": false}`,
  merged into any existing `thinking` object. Z.ai's standard endpoint has preserved thinking off by default
  (`clear_thinking` defaults to `true`) and drops replayed reasoning without it; `thinking.type` is left unset.
- **Redaction redacts a JSON `Authorization` value containing an escaped quote in full**
  ([#1672](https://github.com/llm4s/llm4s/issues/1672)): `Redaction.redact` and `redactForLogging`, and so the
  exchange-log sink, took the value of a JSON `"Authorization"` field to end at the first `"`, including the `"` of
  an escaped `\"` inside it. Only the text before the escaped quote was replaced, and the rest of the credential was
  written in the clear: `{"authorization": "6FPVKYYYKXQ\"]WGMW"}` became `{"authorization": "[REDACTED]"]WGMW"}`.
  The value is now read as the body of a JSON string, in which `\"` is part of the value and a quote after an
  escaped backslash (`"abc\\"`) ends it, so the whole value is replaced: `{"authorization": "[REDACTED]"}`. A value
  with no closing quote, as in a payload cut off in the middle of it, is redacted to the end of the input, as other
  credential values already were. The `Authorization: ...` header line, an empty value and JSON inside a string are
  redacted as before. No signature changes.
- **Redaction keeps the escape on a quote after a redacted `key=value`, so nested JSON still parses**
  ([#1677](https://github.com/llm4s/llm4s/issues/1677)): the `key=value` pass of `Redaction.redact` and
  `redactForLogging`, and so of the exchange-log sink, took the backslash of an escaped closing quote as part of the
  value. Inside JSON that sits in a string, `{"c": "{\"note\": \"token=abc\", \"x\": \"y\"}"}` became
  `{"c": "{\"note\": \"token=[REDACTED]", \"x\": ...`: the bare `"` ended the outer string, the document no longer
  parsed, and the passes after it paired its quotes the wrong way round. Of the backslashes before the quote a value
  stops at, the pass now keeps those that escape it, at any depth of nesting (one for `\"`, three for `\\\"`), and
  replaces the rest with the value; the output is `{"c": "{\"note\": \"token=[REDACTED]\", \"x\": \"y\"}"}`. A value
  in plain JSON, and one ending in backslashes before anything but a quote, is redacted as before, and no text other
  than those backslashes is kept that was replaced before.
- **Redaction covers Python-repr credentials: single-quoted values with `:` or `=`, numbers, and double-quoted
  values under single-quoted keys** ([#1675](https://github.com/llm4s/llm4s/issues/1675),
  [#1687](https://github.com/llm4s/llm4s/issues/1687)): `Redaction.redact` and `redactForLogging`, and so the
  exchange-log sink, left three shapes of a credential in a Python dict (or a JavaScript literal) readable that the
  double-quoted forms redact. A single-quoted leaf holding a `:` or `=` under a single-quoted credential key was taken
  for a field, not a value, so `{'credentials': {'pass': 'SECRETX:SECRETY'}}` became
  `{'credentials': {'pass': 'SECRETX:'[REDACTED]''[REDACTED]`, `{'token': ['postgres://u:SECRETPW@h/db']}` kept the
  user, and inside a JSON string the whole value stayed; such a leaf is now replaced where it stands as a value (after
  `:` in a dict, after `[` or `,` in a list), `{'credentials': {'pass': '[REDACTED]'}}`, while an apostrophe of prose
  that a mentioned `'token': [` runs into is still not taken for one. A number under a single-quoted credential key,
  `{'password': 123456}`, was left as it was, also inside a string; it is now written back in the key's quote,
  `{'password': '[REDACTED]'}`. A double-quoted value under a single-quoted key, which `repr` writes for a string that
  holds a `'`, was not read at all: `{'Authorization': "Bearer x'y"}` kept `'y` and `{'password': "it's-secret"}`
  was unchanged. It is now redacted to its closing quote, honouring escapes, where it reads as a value of the dict
  (followed by `,` and the next key, by `}`, or cut off): `{'Authorization': "[REDACTED]"}`; the same holds inside a
  JSON string (`\"it's\"`), and for such a leaf of a single-quoted container there, which used to end the container.
  Double-quoted JSON is redacted as before. No signature changes.
- **Redaction reads query parameters after a JSON-escaped `&`** ([#1676](https://github.com/llm4s/llm4s/issues/1676)):
  Go's `encoding/json` writes `&` as `\u0026` by default, and HTML-safe serialisers do the same (some also write `=` as
  `\u003d` and `?` as `\u003f`). `Redaction.redact` and `redactForLogging` treated the escape as ordinary text, so in
  `{"url":"https://h/x?a=1\u0026token=SECRETPW"}` the `token` was not read as a parameter and its value was written in
  the clear. The query pass now reads `\u0026` as `&`, `\u003f` as `?` where a query can start and `\u003d` as the `=` between
  a key and its value. That includes the doubled-backslash forms in JSON that sits inside a string, and the hex digits
  in either case. The output keeps the escapes as they were written: `{"url":"https://h/x?a=1\u0026token=[REDACTED]"}`.
  A query value now ends at `\u0026` as it ends at `&`, so the parameters after a sensitive one stay readable. The
  `key=value`, `key="..."` and `key='...'` field passes read a key that follows one of these escapes as they read
  `&key=`. So do the `Bearer` and `Basic` token patterns after an escape, written with a lower-case `u` as JSON
  writes it. The value of a sensitive `key=value` pair ends at `\u0026` only where another pair, `key=` or
  `key\u003d`, follows it: a credential that holds an `&`, which these serialisers escape too, is redacted in full,
  as it was before, so `{"dsn":"host=db password=p\u0026ssW0rd dbname=x"}` becomes
  `{"dsn":"host=db password=[REDACTED] dbname=x"}`. A query value ends at `\u0026` only while it has held no
  quote: once a quote has been read, bare or escaped (System.Text.Json writes `'` as `\u0027` and `"` as `\u0022`),
  the value runs over `\u0026` as it did before, and after an escaped quote over `&` too. So
  `GET https://h/x?password='p&ss=QZXJ'` as System.Text.Json writes it,
  `{"msg":"GET https://h/x?password=\u0027p\u0026ss=QZXJ\u0027"}`, becomes
  `{"msg":"GET https://h/x?password=[REDACTED]"}`, and `x=1\u0026password=\u0027p\u0026ss=QZXJ\u0027 n=1` becomes
  `x=1\u0026password=[REDACTED] n=1`. A `key=value` value that opens with an escaped quote runs to the matching
  escaped quote, over whitespace, `&`, `,` and `;` as `key='...'` does, so
  `client_secret=\u0027a;b\u0027` is redacted whole, which it was not before; and the field passes read `\u003d`
  as the `=` of a pair, as Gson writes it.
  Where the input holds one of these escapes, or `\u0027` or `\u0022`, every pass of `redact` also reads the input
  as it was given, and what any pass replaces - alone, or in sequence as the passes ran before - is merged and
  replaced once. A pass that reads further than a value, as a query value that holds a quote runs over `\u0026`,
  can no longer take the key or the opening quote of the field after it from the pass that reads that field:
  `{"msg":"GET https://h/x?a=1\u0026token=ab'cd\u0026password='correct horse battery'"}`, as Go writes it, becomes
  `{"msg":"GET https://h/x?a=1\u0026token=[REDACTED]'"}`, where ` horse battery'` was left readable, and so for a
  `'passwd':'...'` or `\"password\":\"...\"` field after such a value. A `Bearer` or `Basic` token after an
  escape is read apart from the plain pattern, so it cannot take the keyword of the next token
  (`Basic \u003DBasic Basic 9K29`). A field of JSON whose quotes System.Text.Json writes as `\u0022` (or a dict's
  as `\u0027`) is read as `"token": "..."` is, and so is `key=` before a value in escaped quotes, so
  `{"msg":"{\u0022url\u0022:\u0022https://h/x?token=abc\u0026page=2\u0022,\u0022nested\u0022:{\u0022token\u0022:\u0022NESTEDSECRET\u0022}}"}`
  keeps `page=2` and redacts both tokens. The value of a `key=value` pair whose key is not sensitive ends at an
  escaped quote, `<` or `>`, as it ends at the character, so the pair after it is read.
  In an input that holds one of these escapes, a `key=value` key starts where it would in the text the escapes stand
  for: never at the `u` of an escape, and right after the escape of a character that ends a word (`'`, `<`,
  `é`, ...), as after the character. The escape itself was read as a key before, so after `'` and `=`
  the key `u0027` held `password=...` as its value and the credential was never read.
  The value of a key that is not sensitive is not skipped where its `=` is `=`: the text after the escape is
  read for pairs, so `password=password=...` (a key that starts at the escape of `p`) and
  `abc=password=...` have the credential redacted, as when the `=` is not read as one. A query read through its
  escapes ends at the escape of whitespace (` `, `\u000a`, ...) as at whitespace, so its key or value cannot run
  into the field after it, and a `Basic` token runs over `=` as over its `=` padding.
  An input that holds none of these escapes is redacted exactly as before.
- **Redaction reads a query parameter only inside a URL, so a `?` in prose no longer mangles the document**
  ([#1667](https://github.com/llm4s/llm4s/issues/1667)): `Redaction.redact` and `redactForLogging`, and so the
  exchange-log sink, read a query parameter as `[?&]`, a key of any characters up to the next `=`, and a value up
  to the next `&` or whitespace. A `?` in prose, such as a question in a chat message, started a "parameter" whose
  key ran across quotes, braces and lines to the next `=` anywhere later in the document. When that span held a
  sensitive word, its "value" replaced the closing quote and the fields after it. The field passes then read the
  mangled text out of step and left credentials readable: in
  `{"messages": [{"content": "Is this right?"}], "credentials": {"dsn": "postgres://u@h/db?sslmode=require",
  "password": "hunter2}SECRET", "keys": ["SECRETBB"]}}` the tail of `password` and the whole of `keys` were
  written in the clear. A key is now one run of the characters a query key can hold: no whitespace, quote, `?`,
  `&` or `=`. A value ends at `&`, at whitespace, or at the quote that ends the string the URL sits in, which is kept
  with the backslashes that escape it. RFC 3986 allows `'` unencoded in a query, so a run of `'` followed by a
  letter, a digit, one of `._~%+/-` or one of `!$*(@=` is part of the value (`?key=ab'cd`, `pa'(ss)w0rd`,
  `Xk9'!mQ2`, `ab''cd`, and `''Xk9` at its start); a `"` is part of it only before a letter, a digit or one of
  `._~%+/-`. One exception: a value that holds `'` before `,`, `)`, `;` or `:` (`?token=ab',cd`) is redacted only
  up to that quote, and the text after it is written, because such a quote cannot be told from the one that ends a
  string, as in `fetch('...?token=ab')`. A quoted value (`?key='abc'`, or `\"abc\"` in JSON inside a string) is redacted inside its quotes, and an empty
  value is left as it is. The value of a parameter that is kept is searched too, so `?next=/cb?token=...`, which
  was written in the clear, is redacted. The sensitive parameter names are unchanged; `?api_key=`, `&token=`,
  `?access_token=` and `?filter[api_key]=` are still redacted, and redacted JSON keeps its structure and still
  parses. No signature changes.
- **Provider response bodies are redacted before they are truncated for a log line or an error**
  ([#1674](https://github.com/llm4s/llm4s/issues/1674)). Many clients put a provider's error body into a log line
  or an error message through `Redaction.truncateForLog` alone, or not even that, so a body that echoed a request
  header, an API key or a token - `Authorization: Bearer ...`, `"api_key": "..."`, `?key=AIza...` - was written in
  the clear. Every such site now goes through one internal helper that redacts the whole text and then truncates
  it, as Cohere's and Jina's embedding providers already did: the OpenAI, Anthropic and Gemini vision clients, the
  OpenAI (DALL-E) image client's error message, raw body or JSON `message` alike, and the Stable Diffusion and
  Hugging Face image clients (`llm4s-image`); the Streamable HTTP and SSE MCP transports' HTTP error bodies, the
  message of a JSON-RPC error from any transport, and the DEBUG line for an unrecognised SSE line, now built only
  when DEBUG is on (`llm4s-mcp`); the Langfuse batch
  sender's and tracer's error-body log lines and the per-event rejection summary (`llm4s-observability`); the
  Ollama, OpenAI and Voyage embedding providers; Cohere's reranker and Qdrant's error messages (`llm4s-rag`);
  `GraphJsonParser`'s preview of a reply it could not parse (`llm4s-knowledgegraph`); Vertex AI's token
  refresh and JWT exchange errors (`llm4s-gemini`); watsonx's IAM exchange error, which removed only the
  configured key; the grounding, context-relevance and every `LLMGuardrail` judge's quote of a reply they could not
  parse, cut to 200 characters (`llm4s-agent`); and `HttpResponse.ensureSuccess`'s `ServiceError`, which the model
  listers use (`llm4s-core`). An SDK exception's message carries the response body too (anthropic-java writes
  `401: <body>`), so the same applies to the messages taken from one: Anthropic's `AuthenticationError` and
  `ValidationError` on both paths (`llm4s-anthropic`); every error Bedrock maps from an AWS exception
  (`llm4s-bedrock`); and `DefaultErrorMapper`'s `AuthenticationError` and `UnknownError`, which every client and
  `Safety.safely`, `Safety.fromTry`, `toResult` and `toLLMError` fall back on (`llm4s-core`). The `UnknownError`
  keeps the original exception as its cause, unredacted: a logger that prints the cause prints its message.
  `OllamaClient`'s "does not support tools" error cut the server's decoded message to 200 characters and never
  redacted it; it now redacts the whole message first. Redacting before the cut matters: a key that straddles the
  cut point leaves a fragment too short for its pattern to recognise, which the old order let through. A truncated
  body's `original length` now counts the redacted text. No public signature changes.
- **`DefaultErrorMapper` classifies 401 and 429 only when the message names an HTTP status, and keeps the
  cause otherwise** ([#1668](https://github.com/llm4s/llm4s/issues/1668)). The mapper behind `Safety.safely`,
  `Safety.fromTry`, `toResult` and `toLLMError` turned any exception whose message *contained* `401` into
  `AuthenticationError("unknown", "Authentication failed")` and `429` into `RateLimitError("unknown")`, dropping
  the exception. So `Index 4012 out of bounds for length 10`, `request 1700401234 timed out`, `port 14290` or
  `wrote 1429 tokens` became a non-retryable "your credentials are wrong", or a retryable rate limit that retry
  and circuit-breaker logic would repeat on a deterministic bug, with the stack trace lost. Behaviour change:
  the mapper now classifies a status only at a word boundary in an HTTP context - after `HTTP`, `status`,
  `status code`, `http status`, `error code` or `response code`, optionally as a quoted JSON key
  (`HTTP 401`, `HTTP/1.1 429`, `Status Code: 429`, `statusCode=401`, `"status": 429`, `http_status=401`),
  before its reason phrase (`401 Unauthorized`, `Error: 429 - Too Many Requests`), or leading the message
  as `openai-java` and `anthropic-java` write their service exceptions (`401: <body>`, also behind up to
  eight wrapping exceptions' `ClassName: `). Everything else is an `UnknownError` carrying the exception
  as its cause. Known false positive: that SDK shape has no other HTTP marker, so any message that
  *starts* with `401: ` or `429: ` is still classified. The mapper scans only a message's first 4 KiB and
  last 1 KiB (a status leads the message, or, from the AWS SDK, ends it), with patterns that run in
  linear time, so an exception carrying a large response body is cheap to map. An `AuthenticationError` from the mapper now has code `401` and keeps the
  original message, redacted (`Authentication failed for unknown: HTTP 401 Unauthorized`), where it said
  only `Authentication failed`; neither it nor `RateLimitError` has a field for a `Throwable`, so a
  classified exception's stack trace is still not kept. No public signature changes.
- **Kotlin: cancelling `AgentKt.run` or `continueConversation` cancels the turn, as `resume` and `recover` do**
  ([#1663](https://github.com/llm4s/llm4s/issues/1663), Kotlin API). `run` and `continueConversation` wrapped the
  blocking `JAgent` call in `runInterruptible`, so cancelling the caller - a cancelled scope, `withTimeout` -
  interrupted only the wait: the call threw `CancellationException`, but the turn kept running in the background,
  calling the model, and its conversation thread stayed busy, so a `recover` or a new turn on it failed with
  `ThreadBusy` until the turn finished. Both now run the turn as `stream` does (`run` on a new thread with a random
  id, as `JAgent.run` does; `continueConversation` on `previous.threadId()`), its events discarded: cancelling the
  caller cancels the turn and returns once it has ended, leaving the thread for `recover`; if the turn had already
  completed when the cancellation arrived, its result is committed to the thread and `recover` throws `LLMException`
  (no incomplete execution). Every `AgentKt` suspend function that runs a turn now behaves the same way, and
  collects the turn's discarded events on `Dispatchers.IO`, not the caller's dispatcher (`Dispatchers.Main`). Results and failures are otherwise unchanged - the same
  `JAgentResult`, and `LLMException` with the same message and cause. No public signature changes; Java's
  blocking `JAgent` methods are unchanged.
- **Kotlin: a stream whose listener fails fatally ends the `AgentKt` call instead of suspending it forever**
  ([#1671](https://github.com/llm4s/llm4s/issues/1671), Kotlin API). `stream`, `streamResume`, `streamRecover` -
  and the `run`, `continueConversation`, `resume` and `recover` built on them - closed their channel only from
  the Java facade listener's `onComplete` or `onError`. When delivery dies of a fatal error (one `Safety` does not
  capture: a `VirtualMachineError` such as `OutOfMemoryError` or `StackOverflowError`, a `LinkageError`),
  `AgentStream` skips that callback and `await()` reports "the stream's listener failed fatally", but nothing
  closed the channel, so the collector suspended until something outside cancelled it. The Kotlin side now
  closes the channel from `AgentStream.await()`'s outcome, on a virtual thread, once delivery ends; the call
  throws `LLMException` with the facade's report (the fatal error itself ended the facade's delivery thread and
  goes to its uncaught-exception handler). A normal end is unchanged. No public signature changes; the Java
  facade is unchanged.
- **`llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`: a deep or malformed model listing is a `Left`, not an
  exception** ([#1660](https://github.com/llm4s/llm4s/issues/1660)). `AnthropicModelLister`,
  `GeminiModelLister` and `OllamaModelLister` read the listing with the unbounded `HttpResponse.toJson` and
  then throwing accessors - `json("data")` / `json("models")` and `.arr` inside a `Try`, `.obj` on each entry
  and on the page outside one. `ujson.Value.InvalidData`'s message renders the value recursively, so a
  listing 10,000 levels deep - a top-level array, or one under `data` / `models` or as an entry of it -
  overflowed a 1 MB thread stack with a `StackOverflowError`, which `Try` does not catch; and a two-level
  `{"data":[[1]]}` or `{"models":["x"]}` threw `InvalidData` out of `listModels`. Each lister now reads the
  body through the depth-bounded `BoundedJson`, as `ProviderModelListers` does since #1658, and uses `Option`
  accessors only: a body that is not JSON, nested more than 512 levels deep, not an object, or without a
  `data` / `models` array is a `Left`, and an entry that is not an object is skipped, as an entry without an
  `id` / `name` always was. Ordinary listings, pagination and the `has_more` / `last_id` checks are
  unchanged. No public signature changes.
- **Redaction keeps the text after a merely mentioned credential key readable**
  ([#1654](https://github.com/llm4s/llm4s/issues/1654), part of
  [#1657](https://github.com/llm4s/llm4s/issues/1657)): three over-redactions in `Redaction.redact` and
  `redactForLogging`, and so the exchange-log sink; none exposed a credential. An unclosed `'password': '`
  inside a JSON string (`{"content": "use 'password': ' carefully", "model": "gpt-4o"}`) ran to the next `'`
  or the end of the input and took every field after the string; a single-quoted value (and a `key='`
  assignment) inside a double-quoted string now also ends at a `"` that ends that string - one followed, past
  whitespace, by the end of the input, by a `,` and the next `"key":`, or by a `}` or `]` that is itself followed
  by the end of the input, another `}` or `]`, or a `,` before a `"`, `{` or `[` - so the output is
  `{"content": "use 'password': '[REDACTED]", "model": "gpt-4o"}`. It ends there only where no `'` that could
  close the value (any `'` but the apostrophe between two letters or digits, as in `it's`) follows anywhere in the
  input, where that `"` is not the first character of the value, and where the passes before have replaced no
  `'`; otherwise it runs to the next `'` or the end of the input, as on main, so a credential holding a `"`
  (`'Qx"]]9secret'`, `'Qx", "k": 9secret'`, or `'Bearer abc"]}secret'`, whose token the header pattern replaces
  first) is still redacted whole. A `'token': [` mentioned inside a JSON string took the apostrophe of `it's` for a leaf
  that the string's end closed (`... Thanks, it'[REDACTED]"}`); the apostrophe of a word of prose there - between
  two letters, in a word after whitespace - is now kept when only the string's end would close it and no `\"`
  (JSON escaped in the string) comes before that end, while a quote after a bracket, a comma, a space or a Python
  prefix (`b'`, `rb'`) still opens a leaf. And a bare word after a
  backslash under a credential key (`{"token": [\a1, "x"], "password": "..."}`) had its replacement quote written
  straight after the backslash, which read as `\"` and lost the rest of the document; the escaped character is now
  kept, as before #1647, and the rest of the word replaced. Each of these redacts to the same output when redacted
  again. Left by decision: outside any string, the bare words after an unclosed `'token': [` are still replaced to
  the end of the input (`note: see 'token': [ for details` -> `note: see 'token': [ '[REDACTED]' '[REDACTED]'`),
  since they read as the leaves of a YAML flow sequence or a cut-off dict and nothing tells them from prose; and
  redacting twice still changes some inputs with unbalanced quotes, which the `redact` Scaladoc now says, where
  `redactPairs` had claimed redacting twice gives the same result. Two known trade-offs, pinned by tests: a
  *truncated* input - an unclosed single-quoted value inside a raw (unescaped) double-quoted string, holding a `"`
  followed by what follows a string's end, with no `'` that could close it after it (`msg="{'password': 'Qx"]]9secretPW`
  cut off) - shows the part after that `"` (`'[REDACTED]"]]9secretPW`), where main hid it by running to the end of
  the input; every llm4s call site (`redactForLogging`, the exchange-log sink, Cohere's and Jina's error bodies, the
  MCP payload preview) redacts the full text before truncating it, and a caller must do the same. And a closing `'`
  with a letter or digit on both sides (`'Qx"]]9SECRETPW'it"`) is read as an apostrophe, so it does not close the
  value and the same applies. No signature changes.
- **Redaction keeps a `$` or `\` in a query parameter, and writes a placeholder as it is**
  ([#1655](https://github.com/llm4s/llm4s/issues/1655)): `Redaction.redact` and `redactForLogging`, and so the
  exchange-log sink, returned a query parameter to `Regex.replaceAllIn` without `Regex.quoteReplacement`, so
  a `$` or `\` in it was read as a group reference or an escape. `?q=\` threw `IllegalArgumentException`,
  `?q=$5` threw `IndexOutOfBoundsException`, `?api$key=abc` threw `IllegalArgumentException` (illegal group
  reference), `?q=$1` became `?q=?` and `?path=C:\dir` became `?path=C:dir`. A caller's `placeholder` reached
  the Authorization, Bearer, Basic and API-key replacements unquoted too, so a placeholder of `$0` wrote back
  the credential it replaced. Every replacement in `Redaction` and `SecretPatterns.redactAll` /
  `redactAllWithPlaceholder` is now quoted: the parameters are kept exactly, a sensitive value is still
  redacted, and the placeholder is written literally. No signature changes.
- **A deeply nested provider error body falls back to the default message instead of overflowing the stack**
  ([#1658](https://github.com/llm4s/llm4s/issues/1658)). `HttpErrorMapper` read a non-2xx body with
  `ujson.read` and then `.obj`; parsing is iterative, but `.obj` on a top-level array throws
  `ujson.Value.InvalidData`, whose message renders the whole value recursively, so an error body of 10,000
  nested `[` (about 20 KB) overflowed a 1 MB thread stack - a `StackOverflowError`, which `Try` does not
  catch, out of every provider's non-2xx path, including `OpenAICompatibleClient`'s `complete` and
  `streamComplete` (DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and the generic `openai-compatible`
  provider). The body is now read through the depth-bounded `BoundedJson` with `Option` accessors only, so
  a body that is not JSON, not an object, or nested more than 512 levels deep yields `"<provider> API error
  (HTTP <status>)"`; ordinary `{"message": ...}`, `{"error": {"message": ...}}` and `{"error": "..."}`
  bodies read as before. The same hazard is closed in `llm4s-openai-compatible`: `OpenAICompatibleClient`
  refuses a 2xx reply or a stream event nested more than 512 levels deep with a `Left`, as it does
  malformed JSON, and `ProviderModelListers.openAICompatible` reads the listing through `BoundedJson` and
  returns `Missing or invalid models payload` for a body that is not an object with a `data` array. No
  public signature changes.
- **`llm4s-bedrock`: a `toolUse` input nested more than 512 levels deep is refused as a malformed tool
  call, never converted or sent back** ([#1648](https://github.com/llm4s/llm4s/issues/1648)). #1630
  ([#1562](https://github.com/llm4s/llm4s/issues/1562)) bounded every place model-written JSON is parsed
  and #1644 ([#1643](https://github.com/llm4s/llm4s/issues/1643)) closed the window left for Anthropic
  and Gemini, but a Converse response's `toolUse.input` arrives as an AWS SDK `Document`, provider-native
  rather than text, and was left out. The SDK's bundled Jackson parser admits it up to 1,000 levels, about
  twice the library's limit, and `BedrockClient` converted it with a recursive visitor and rendered it
  back on the next turn with a recursive builder, so an input 513 to ~998 levels deep became a `ToolCall`
  that overflowed the stack when it was traversed - a `StackOverflowError`, which is not an `Exception`
  and escaped `complete`'s `Result`. The `Document`'s depth is now measured iteratively before it is
  converted, and `complete` returns `ProcessingError("bedrock-tool-calls", "malformed tool call:
  arguments are nested more than 512 levels deep")` for one over the limit, as the Anthropic and Ollama
  clients do; an input at the limit is still accepted and sent back. The streaming path was already
  bounded, since `ConverseStream` delivers arguments as text that `StreamingAccumulator` reads through
  `BoundedJson`.
- **`llm4s-provider-testkit` says it requires JDK 21** ([#1582](https://github.com/llm4s/llm4s/issues/1582)):
  its interruption checks run the call on a virtual thread and `LocalProviderTestServer` answers on
  `Executors.newVirtualThreadPerTaskExecutor`, both JDK 21 APIs, but nothing said so. The provider guide, the
  installation page, the 1.0 scope and the compatibility policy now state it, as do the two Scaladocs. No
  build setting changes; the minimum JDK for the other artifacts, and where it is enforced, is decided in
  [#1493](https://github.com/llm4s/llm4s/issues/1493).
- **`llm4s-spring-boot-starter`: `LlmHealthIndicator.health()` reports DOWN instead of throwing when its thread is
  interrupted** ([#1636](https://github.com/llm4s/llm4s/issues/1636)): the probe waited on its future inside a
  `Try`, which does not catch `InterruptedException`, so interrupting the thread checking health while the
  provider call ran (Actuator shutting down, a management pool interrupting its worker) propagated the exception
  out of `health()`, with the interrupt flag cleared and the probe left running on the executor. The wait now goes
  through `CancelledError.attempt`, as `JLlmClient` does: the check is `DOWN` with `probe=cancelled` and an `error`
  detail, the interrupt flag is set again for the caller, and the abandoned probe is cancelled like a timed-out
  one. `health()` never throws `InterruptedException`; the README and the Spring Boot guide say so.
- **`llm4s-spring-boot-starter`: a cancelled health probe is not cached**
  ([#1642](https://github.com/llm4s/llm4s/issues/1642)): `probeCached` stored every outcome for
  `llm4s.health.probe-ttl`, so the `DOWN` / `probe=cancelled` that an interrupted thread gets was served to every
  other caller within the TTL - a thread queued behind it, the next uninterrupted Actuator poll - as if the provider
  were down. A cancellation is a fact about the interrupted thread, not the provider: `probeNow` now returns it apart
  from a provider outcome, it is reported to that caller only, and the next check probes again. A failed or timed-out
  probe is still cached. Found during review of #1640.
- **Redaction covers a credential whose value is an array or an object**
  ([#1576](https://github.com/llm4s/llm4s/issues/1576)): `Redaction.redact` and `redactForLogging` (and so the
  exchange-log file sink) replaced only a string or a number under a sensitive key, so `{"token": ["abc"]}`
  and `{"credentials": {"user": "u", "pass": "p"}}` were written in the clear. Every string and number leaf
  under such a key is now replaced, however deep, in plain JSON and in JSON that sits inside a string; the
  brackets, the keys of nested objects, `true`, `false` and `null` are kept, so the output still parses and
  keeps its shape, and a payload cut off inside the value is redacted to the end. A number under a sensitive
  key of JSON inside a string (`\"password\": 12345`) is redacted too. Keys that merely contain a credential
  word (`max_tokens`, `messages`) still keep their arrays. The scanner counts brackets on an explicit stack
  and visits each character once, so a megabyte-long array or one nested a hundred thousand levels deep
  redacts on a small thread stack. Twice-escaped JSON and a secret that is not under any key remain
  unredacted by decision; the exchange-logging guide says so.
- **A too-deep refusal is recognised structurally, not by equality with a freshly built error**
  ([#1651](https://github.com/llm4s/llm4s/issues/1651), found in review of #1644). The Ollama, Anthropic,
  Gemini and Vertex AI clients told a document `BoundedJson` refused as too deep from one it could not parse
  with `case Left(e) if e == BoundedJson.tooDeep()`, which holds only for the error of the default limit with
  its exact wording: a read with another limit, or a reworded message, would have fallen through to each
  site's generic arm - for the Gemini and Vertex AI streams, the arm that skips the chunk, which is the
  silent drop #1643 fixed. The four sites now match `BoundedJson.TooDeep()`, an extractor that recognises
  the error by the prefix `tooDeep` writes, whatever limit follows it; `BoundedJson` is `private[llm4s]`,
  so no public signature changes. The Gemini and Vertex AI warning now reads `a chunk is nested more than
  512 levels deep` rather than quoting the error's `Invalid json: ` message.
- **Redaction covers a single-quoted key with an array or an object value**
  ([#1647](https://github.com/llm4s/llm4s/issues/1647)): a single-quoted string value had been redacted
  (`{'api_key': 'x'}`), but `{'token': ['abc']}` and `{'credentials': {'user': 'u', 'pass': 'p'}}` - a
  Python dict or a JavaScript literal in a prompt - were written in the clear, and a single-quoted leaf under
  a double-quoted key, `{"token": ['abc123']}`, was mangled rather than redacted (`['abc"[REDACTED]"']`,
  the digits taken for a number). The leaves under a single-quoted credential key are now replaced like the
  double-quoted ones, and a leaf may be double- or single-quoted whatever the key's quote. Inside a string a
  single-quoted container ends where the string does, so a `'token': [` that a message merely mentions
  (`{"content": "see 'token': [ for details", "api_key": "..."}`) does not take the fields after it; a
  `\"`-quoted leaf under a single-quoted key inside a string is left, by decision, since a `"` there may be
  the end of a string inside the string. Outside a string, a bare value under a credential key (`token:
  [abc]`, an unquoted word as well as a number) is replaced too, so that a walk misled by a stray quote
  before the document leaves nothing readable; `true`, `false`, `null`, Python's `True`, `False`, `None`
  and an unquoted key are kept.
- **`llm4s-java-api`: `InterruptedException` is never thrown, and the Javadoc says so**
  ([#1591](https://github.com/llm4s/llm4s/issues/1591)): `catch (InterruptedException e)` around
  `JLlmClient.complete` or `JAgent.run` does not compile ("never thrown in body of corresponding try statement"),
  because the facade reports an interrupt as a `CancelledError` result with the thread's interrupt flag left set
  rather than throwing. That is now the contract for every client: an `InterruptedException` that a custom
  `LLMClient` let escape used to propagate out of `complete` as an undeclared checked exception that Java could
  not catch by name, and is now the same `CancelledError`, with the flag restored. The signatures are unchanged -
  declaring `throws InterruptedException` would have forced a catch of an exception that is never thrown on every
  caller. The Javadoc of `complete` and `run`, the Java guide and the threading guide now say what to do instead:
  test the result for a `CancelledError` (`ThreadingGuideSnippets.completeOrNull`); a snippet test pins it.
- **Docs: every page under a parent has its own `nav_order`, and CI checks it**
  ([#1623](https://github.com/llm4s/llm4s/issues/1623)): ten `nav_order` values were shared by two or three pages
  under the same parent (`Basic Usage` and `Agents` both 1, `Permission-Based RAG` and `RAG Evaluation` both 4,
  `Structured Output` and `Java` both 6, `Built-in Tools` and `Monitoring` both 10, `Context Window Pruning` and
  `Production Deployment` both 11, `Advanced Topics` and `Google Summer of Code` both 5, `Contribute`, `Migrations`
  and `People` all 9, among others), so the site ordered them by title and the number meant nothing. The colliding
  pages are renumbered, keeping the existing order where it was sensible, and the stale
  `docs/reference/review-guidelines.main.backup.md`, a second *Code Review Guidelines* page under Reference, is
  removed. A new check, `scripts/check-doc-nav-order.sh`, runs in CI quick checks and fails on a duplicate.
- Calculator decimal output uses a stable locale, including on JVMs configured with comma decimal separators.
- **`llm4s-agent-tools`: `CalculatorTool` rejects results that are not finite**
  ([#1516](https://github.com/llm4s/llm4s/issues/1516)): an overflow (`10^400`, `1e200 * 1e200`) or an undefined
  power (`(-8)^(1/3)`) was returned as a success whose text was `Infinity` or `NaN`, while division by zero and
  the square root of a negative were errors. Any result that is infinite or not a number is now an error,
  like those two, so a model is no longer handed `"Infinity"` as an answer; **a caller that relied on receiving
  `Infinity` or `NaN` now gets a `Left`** (the tool description says so).
- Credential redaction handles escaped quotes in embedded JSON, quoted assignments, and exponent-form numeric values.
- **`llm4s-agent-tools`: file tools confined by path component, not string prefix**
  ([#1296](https://github.com/llm4s/llm4s/issues/1296)): `FileConfig.isPathAllowed` and
  `WriteConfig.isPathAllowed` compared paths with `String.startsWith`, so an allowed `/srv/agent-data` also
  admitted `/srv/agent-data-secret`, and `developmentSafe(workingDirectory)` could read and write a sibling
  directory whose name began with the working directory's. Both now use `Path.startsWith` on normalised absolute
  paths; blocked paths are matched the same way (`/var` no longer blocks `/variable`).
- **`llm4s-agent-tools`: `get_current_datetime` reads the same on every host and checks its parameters**
  ([#1512](https://github.com/llm4s/llm4s/issues/1512), [#1513](https://github.com/llm4s/llm4s/issues/1513),
  [#1511](https://github.com/llm4s/llm4s/issues/1511)): the `human` format used the JVM's default locale, so the
  same call wrote localised month and weekday names (and a lower-case `am` under `en_GB`); it is now always English
  (`Locale.US`, so the text under `en_US` is unchanged). `timezone` and `format` were marked required in the tool
  schema although the handler defaults both, so a call with null arguments was refused with `NullArguments`; both
  are optional now, as the guide already said. **Behaviour changes:** an unsupported `format` is now an error that
  names the supported formats, where it used to answer in ISO; a `format` or `timezone` that is not a string is an
  error naming the parameter, where it used to be ignored (the answer came in UTC / ISO). A JSON `null` still counts
  as absent.
- **`llm4s-knowledgegraph`: a failed graph extraction logs a preview of the reply, not the whole reply**
  ([#1635](https://github.com/llm4s/llm4s/issues/1635)): `GraphJsonParser` used to put the entire model reply on
  the ERROR line when it did not parse, or parsed but was not a graph - the second site re-rendering the whole
  parsed value to do so. The reply is model output, so a megabyte reply became a megabyte log line, and it echoes
  the documents the graph was extracted from. Both lines now carry the reply's first 512 characters and its full
  length (`Redaction.truncateForLog`). The `ProcessingError` returned is unchanged.
- **`llm4s-core`: `ProcessingError.context` names the cause's class when the exception has no message**
  ([#1556](https://github.com/llm4s/llm4s/issues/1556)): the `cause` entry was the exception's `getMessage`, which
  is `null` for an exception built without one (`new RuntimeException()`, a bare `NullPointerException`), so the
  `Map[String, String]` held `"cause" -> null`, `formatted` printed `cause=null`, and any later read of the value
  (`toLowerCase`, JSON encoding) threw. The entry is now the class name (`cause=java.lang.RuntimeException`)
  when the message is `null` or empty; a cause with a message keeps its message, and an error without a cause
  has no `cause` entry, as before. `AssistantError.consoleInputFailed` and `consoleOutputFailed`, which
  interpolated the same `getMessage` into their message (`Failed to read user input: null`), fall back the same way.
- **`llm4s-knowledgegraph`: `GraphJsonParser` returns a `Left` for a reply that is not a JSON object**
  ([#1527](https://github.com/llm4s/llm4s/issues/1527)): the parser read `json.obj` outside the `Try` that guards the
  parse, so a model reply whose top-level value was an array, a string, a number, `true`, `false` or `null` threw
  `ujson.Value$InvalidData` out of `KnowledgeGraphGenerator` and `SchemaGuidedExtractor` instead of failing with a
  `ProcessingError`. Such a reply is now a `ProcessingError` under the caller's error code. The message for a document
  that lacks `nodes` or `edges` now reads "JSON must be an object containing 'nodes' and 'edges' fields"
  (it named the fields without saying the document must be an object).
- **`llm4s-agent-tools`: `list_directory` honours `followSymlinks = false`**
  ([#1296](https://github.com/llm4s/llm4s/issues/1296)): it checked the requested path with a link-following
  `Files.isDirectory`, so a directory symbolic link inside an allowed path listed the directory it pointed to.
  Without `followSymlinks` it now refuses the link with `Not a directory`, as `read_file` refuses a linked file.
- **`llm4s-agent-tools`: `http_request` reads at most `maxResponseSize` bytes**
  ([#1296](https://github.com/llm4s/llm4s/issues/1296)): `HTTPTool` read the whole body into a string and only then
  cut it to `maxResponseSize` characters, so a large or endless response could exhaust memory before `truncated`
  was set. It now reads at most `maxResponseSize` bytes from the stream and stops; the limit counts bytes, as
  documented, not decoded characters.
- **`llm4s-agent`: a burst of live events no longer disconnects a subscriber as `Lagging`**
  ([#1387](https://github.com/llm4s/llm4s/issues/1387)): a subscription's queue held durable and live
  events against one `capacity`, so a model streaming faster than the dispatcher thread was scheduled filled
  it with text deltas, and the next durable commit - which also needed a slot for the pending `LiveGap` - did
  not fit. The subscriber was dropped (`Disconnected(lastSeq, Lagging)`) and `Agent.stream*`, `AgentZ.stream`
  and `AgentIO.stream` failed the run with `the event subscription ended after seq n: Lagging`, though its
  listener never blocks. `capacity` now bounds durable and live events separately: live events that do not
  fit are still dropped and counted in a `LiveGap`, and only a subscriber `capacity` durable events behind
  lags. Live events dropped just before a durable event or an end-of-run barrier are carried in that
  item's slot and delivered as a `LiveGap` just before it, so a subscription queues at most `2 * capacity`
  events and gap markers - `capacity` durable, `capacity` live - plus one end-of-run barrier per run that
  ended while they were queued, however dropped live events and durable commits interleave.
- **`llm4s-core`: `MetricsCollector.compose` forwards the image-generation methods**
  ([#1609](https://github.com/llm4s/llm4s/issues/1609)): the composed collector overrode six of the trait's
  methods and left `observeImageGeneration` and `recordImageGenerationCost` at their no-op defaults, so
  `MetricsCollector.compose(prometheus, costTracker)` recorded requests, tokens, cost, errors, retries and
  circuit-breaker transitions in both and image metrics in neither, although each collector alone recorded
  them. It now forwards every method of the trait, so a composed collector handed to
  `InstrumentedImageGenerationClient` records image metrics in every child; a collector that throws still does
  not stop the others. `MetricsCollectorComposeSpec` pins the contract for the whole trait by reflection, so a
  method added to `MetricsCollector` without a forward in `compose` fails the build. The observability guide,
  which documented the gap as a limitation, and `ObservabilityMetricsGuideSpec`, which pinned it, now say and
  check forwarding.
- **A `tool_use` input or `functionCall.args` nested too deeply is refused instead of overflowing the
  stack** ([#1643](https://github.com/llm4s/llm4s/issues/1643), completing
  [#1562](https://github.com/llm4s/llm4s/issues/1562)). #1630 bounded every parse of model-written JSON at
  512 levels but left two where the model's JSON arrives inside the provider's envelope rather than as text:
  `AnthropicClient` read a `tool_use` block's `input` with a bare `ujson.read`, relying on the SDK's Jackson
  parser, whose own cap is 1,000, so an input 513 to about 998 levels deep became a `ToolCall` and overflowed
  the stack when the next turn rendered it back; and `GeminiClient` and `VertexAIClient`, which have no SDK,
  parsed the envelope with `ujson.read` and took `functionCall.args` - a native object in it - as the call's
  arguments at any depth. The Anthropic client now reads the input through `BoundedJson.read`, and one over
  the limit is a malformed tool call (a `ProcessingError` naming the limit, as for the Ollama client); the
  Gemini and Vertex AI clients read the envelope through it, so a reply nested more than 512 levels deep is
  a `ValidationError` naming the limit, streamed or not: a streamed chunk that deep fails the stream with
  the same error and a warning naming the reason, rather than vanishing from a completion that then
  reported success without its call (a chunk that is not JSON at all is still skipped, as before). No
  public signature changes.
- **A `tool_use` input or `functionCall.args` nested too deeply is refused instead of overflowing the
  stack** ([#1643](https://github.com/llm4s/llm4s/issues/1643), completing
  [#1562](https://github.com/llm4s/llm4s/issues/1562)). #1630 bounded every parse of model-written JSON at
  512 levels but left two where the model's JSON arrives inside the provider's envelope rather than as text:
  `AnthropicClient` read a `tool_use` block's `input` with a bare `ujson.read`, relying on the SDK's Jackson
  parser, whose own cap is 1,000, so an input 513 to about 998 levels deep became a `ToolCall` and overflowed
  the stack when the next turn rendered it back; and `GeminiClient` and `VertexAIClient`, which have no SDK,
  parsed the envelope with `ujson.read` and took `functionCall.args` - a native object in it - as the call's
  arguments at any depth. The Anthropic client now reads the input through `BoundedJson.read`, and one over
  the limit is a malformed tool call (a `ProcessingError` naming the limit, as for the Ollama client); the
  Gemini and Vertex AI clients read the envelope through it, so a reply nested more than 512 levels deep is
  a `ValidationError` naming the limit and a streamed chunk that deep is dropped, as any chunk that cannot be
  read is. No public signature changes.
- **`MemoryStore.storeAll` is all or nothing, and the SQL stores write a batch in one transaction**: `storeAll`
  was the trait's default, a loop of `store` calls. In `SQLiteMemoryStore` and `VectorMemoryStore` each `store` ran
  three statements (the row, then the full-text entry's delete and insert) under autocommit, so a batch of n
  memories paid 3n commits - and 3n file syncs, about a second for fifteen rows on Windows. In those two stores and
  `PostgresMemoryStore`, a batch that failed part way left the memories ahead of the failure stored while the call
  returned `Left`. All three now write the batch in one transaction, and a failed batch stores **nothing**; the
  trait documents `storeAll` as all or nothing (its default is, for an immutable store such as `InMemoryStore`).
  `VectorMemoryStore` computes the missing embeddings first, in `embedBatch` calls of at most 64 texts
  (`VectorMemoryStore.EmbeddingBatchSize`, so a large batch stays within a provider's input limit), so an
  embedding failure also stores nothing; and its `update` of a memory, keeping its id, now replaces it in one
  transaction after re-embedding, where it deleted the memory first and lost it if re-embedding failed. In the
  SQLite stores `store`, `deleteMatching` and opening the store are one transaction
  each too, so a memory's row and its full-text entry are written together. Their transactions are explicit
  `BEGIN IMMEDIATE` ... `COMMIT`, so **a write takes the database's write lock when it begins** and waits up to
  the connection's busy timeout (30 s for `VectorMemoryStore`, sqlite-jdbc's 3 s default for
  `SQLiteMemoryStore`) for a writer on another connection to the same file, failing with `Left` after that and
  leaving the store usable. `VectorMemoryStore`'s Scaladoc now states its thread safety: one instance is not
  safe for concurrent use; separate instances may share a file.
- **`RegexSafetyManager` returns an error instead of letting `StackOverflowError` escape** (#1379): the JDK
  regex engine recurses for patterns such as `(a|aa)*b` and overflowed the stack on long input before the
  character-access budget tripped; `scala.util.Try` does not catch that fatal error, so it escaped
  `safeFind` / `safeMatches` and `RegexValidator.validate`. The match guard now catches `StackOverflowError`
  explicitly and returns a `Left` (`Regex matching aborted: pattern recursed too deeply for the input (stack
  overflow)`), which `RegexValidator` reports as a `Regex security error` `ValidationError`. Other fatal errors
  still propagate. The workspace runner's `WorkspaceRegexSafetyManager` has the same fix.
- **`llm4s-agent`: a judge guardrail refuses a reply that is not one number from 0 to 1, instead of clamping it into a pass**
  ([#1405](https://github.com/llm4s/llm4s/issues/1405)): `LLMGuardrail` (and so `LLMSafetyGuardrail`,
  `LLMFactualityGuardrail`, `LLMQualityGuardrail` and `LLMToneGuardrail`) reduced the judge's reply to its digits
  and dots and clamped the number into 0.0 to 1.0, so `85`, `85%`, `8/10`, `1e-3` and `0,9` all read as 1.0 and
  passed any threshold up to 1.0, `0.7 out of 1` read as 0.71, and `-0.5` read as 0.5. A judge that answered on a
  0 to 100 scale approved everything. A reply is now a score only when the whole reply is one plain decimal
  number from 0 to 1 (`0.9`, `.5`, `1`) of at most 64 characters, with whitespace, markdown emphasis, quotes,
  brackets or bare code-fence backticks before or after it (not necessarily balanced) and optionally a `Score:`
  label before it (`Score: 0.9`, `**Score:** 0.9`, but not `**Score**: 0.9`). Anything else is a
  `ValidationError` on field `llm_response`, which fails the guardrail like an unreadable reply always did: a
  sign glued or apart (`-0.5`, `- 1`, `negative 1`), a percentage or other scale as a sign or in words (`85%`,
  `1 %`, `1 percent`, `1 per mille`, `1 per ten thousand`, `100 bps`), a fraction, an exponent, a decimal comma,
  a trailing full stop, another label or a sentence (`Rating: 0.9`, `The score is 0.9`), a code fence with a
  language tag, a number longer than 64 characters (refused before it is parsed), or more than one number. The score is compared at the precision the judge wrote it and the threshold as the decimal it is
  written as, so `1.0000000000000001` is out of range and `0.79999999999999999` does not reach a threshold of
  0.8. `LLMGuardrail.evaluateWithLLM` returns `Result[BigDecimal]` instead of `Result[Double]`. **Migration:** a
  judge that passed because it answered on another scale, or in a sentence, now fails with `Could not parse LLM
  judge score`: make it answer with only a number between 0 and 1, as the fixed system message already asks. A
  subclass that calls or overrides `evaluateWithLLM` takes a `BigDecimal` (`.toDouble` where a `Double` is
  needed). The score-reading rules are documented on `LLMGuardrail`.
- **`llm4s-agent`: a judge guardrail with a threshold outside 0.0 to 1.0 fails with a clear error, instead of silently
  blocking everything or passing everything** ([#1520](https://github.com/llm4s/llm4s/issues/1520)): the threshold of
  `LLMGuardrail` (and so of `LLMSafetyGuardrail`, `LLMFactualityGuardrail`, `LLMQualityGuardrail`,
  `LLMToneGuardrail` and `LLMGuardrail(...)`) was not validated: above 1.0 (or `+Infinity`), or NaN, nothing could
  pass, and below 0.0 (or `-Infinity`) everything that parsed passed. `validate` now returns a `ValidationError` on
  field `threshold` for a value below 0.0, above 1.0 (an infinity too) or NaN, **before** calling the judge, so no
  call is spent on a guardrail that cannot work. 0.0 and 1.0 are accepted, and a valid threshold is still compared
  as the decimal it is written as (#1405). The check runs when `validate` runs, not when the guardrail is built, so
  no constructor or factory signature changed and a subclass that overrides `threshold` is checked too.
  **Migration:** a guardrail whose threshold was out of range, and so was blocking everything or approving
  everything, now fails every `validate` with that error: correct the threshold.
- **`llm4s-gemini`: a signed function call with an empty `id` is replayed with its thought signature**
  ([#1615](https://github.com/llm4s/llm4s/issues/1615)): a `functionCall` returned with `"id": ""` got a generated
  tool-call id at parse, but the signed part was kept verbatim, so on the next turn its stored `""` was compared with
  the generated id, the part was taken for an edited call and rebuilt without its `thoughtSignature` - which a
  Gemini 3 model answers with HTTP 400, the failure #1416 fixed. An absent or empty stored id is now "no id" on replay
  too, as it already was at parse, so the part goes back exactly as Gemini signed it, `"id": ""` included; the
  `functionResponse` still carries no id. Same on Vertex AI.
- **`llm4s-gemini`: an empty replayed function-call `id` is never echoed onto a `functionResponse`**
  ([#1622](https://github.com/llm4s/llm4s/issues/1622)): the clients collect the ids of the `functionCall` parts they
  replay so a tool result can echo its call's id, and collected an empty one too, so a tool result hand-built as
  `ToolMessage(result, "")` after a replayed `"id": ""` call got `"id": ""` on its `functionResponse`, where parse and
  replay both read an empty id as "no id". The three sites now agree: an empty id is never collected, so no
  `functionResponse` carries one; the signed part still goes back verbatim. Same on Vertex AI.
- **`TokenizerMapping` reports a Claude model under the `azure/` prefix as approximate, as it tokenizes it**
  ([#1572](https://github.com/llm4s/llm4s/issues/1572)): `getTokenizerId` tested its Claude guard before the
  `azure/` prefix, while `getAccuracyInfo` tested the prefix first, so a name such as `azure/claude-3-sonnet` was
  tokenized as a Claude model but reported `Exact("Azure uses OpenAI tokenizers")`, and `isExactMapping` returned
  `true` for a model the class documentation's table calls approximate. `getAccuracyInfo` now takes the guards in
  `getTokenizerId`'s order, so `azure/claude-...` is `Approximate` with accuracy 0.75 and `isExactMapping` is
  `false` for it, as for `claude-...` and `anthropic/...`. The tokenizer selected does not change (`cl100k_base`
  either way), and an Azure deployment whose name does not contain `claude` is still reported exact.
- **`llm4s-agent-tools`: `json_tool` refuses what it used to mishandle**
  ([#1508](https://github.com/llm4s/llm4s/issues/1508), [#1509](https://github.com/llm4s/llm4s/issues/1509),
  [#1510](https://github.com/llm4s/llm4s/issues/1510)): a `query` path with an array index too large for an `Int`
  (`a[99999999999]`) threw a `NumberFormatException` out of the handler and out of `execute`; it is now an error naming
  the index. A path the parser could not read to the end was cut short and the value reached so far was returned as a
  success (`a..b`, `a[x]`, `a[-1]` all returned `a`); it is now an error naming the text it stopped at. A document
  nested more than 512 levels deep (arrays and objects together) made `parse` and `format` raise a
  `StackOverflowError`, which `Try` does not catch; every operation, `validate` included, now refuses such a document
  with an error before reading or writing it. A path that used to succeed only because its tail was ignored, such as
  `items.[0]`, is now an error: write `items[0]`.
- **`SafeParameterExtractor`: integer parameters reject fractions and overflow, and `validateRequired` checks
  types** ([#964](https://github.com/llm4s/llm4s/issues/964)): `getInt`, `getIntEnhanced` and `getOptionalInt`
  were `_.numOpt.map(_.toInt)`, so a tool argument of `3.14` returned `3` and `9223372036854775807` returned `-1`,
  silently. An integer parameter now accepts only a JSON number with no fractional part that fits in an `Int`
  (`3`, `3.0`, `1e2` and `-0.0` are accepted); a fraction, NaN, an infinity or an out-of-range value is a
  `TypeMismatch` (`expected integer, got number`), so a model that sends one gets an error it can correct.
  `validateRequired` passed `_ => Some(())` as its extractor, so it checked presence but never the declared type
  although its Scaladoc promised it; it now checks `string`, `integer`, `number`, `boolean`, `array` and `object`
  with the same rules as the typed getters and reports a wrong-typed value as a `TypeMismatch` beside the missing
  ones. A type name it does not know is still checked for presence only. **Migration:** a tool that read an
  integer with `getInt` and received a fractional or oversized number used to run with a truncated or wrapped
  value; it now gets a `Left`. The built-in tools that read an integer with `.fold(_ => default, identity)` or
  `.toOption` (`UUIDTool`'s `count`, `ListDirectoryTool`'s `max_entries`, `ReadFileTool`'s `max_lines`, the
  workspace and knowledge-graph tools) fall back to their default for such a value instead of using the truncated
  one. No public signature changed.
- **`llm4s-agent-tools`: file paths are judged by where they really are, a shell command gets a scrubbed
  environment, and the HTTP tool stops reading at its cap** ([#1408](https://github.com/llm4s/llm4s/issues/1408),
  findings F1 to F4; F5 to F9 remain open):
  - *File tools (`read_file`, `list_directory`, `file_info`, `write_file`).* `allowedPaths` and `blockedPaths` were
    compared as strings, so allowing `/srv/data` also allowed `/srv/data-secret`, and a symbolic link inside an
    allowed directory led out of it (reads, listings, `file_info` and writes, `followSymlinks = false` included).
    A path is now made absolute, every symbolic link is resolved, and the result is compared with each entry -
    resolved the same way - one path component at a time (the tools remove `..` as text first and open the location
    they judged; `isPathAllowed` reads a `..` after a link both as POSIX applies it, at the link target's parent,
    and as Windows does, removing it as text first, and allows the path only when both locations are allowed, on
    every OS);
    blocked entries are matched on the real location too. The tools open the resolved path, so a link swapped in after the check no longer redirects the
    open (a directory swapped for a link between the check and the open is a race that is narrowed, not closed).
    A link that cannot be resolved (a dangling link) is refused. A hard link inside an allowed directory to a file
    elsewhere is not contained: no path check can tell it from the file itself. `FileConfig.isPathAllowed` and
    `WriteConfig.isPathAllowed` keep their signatures and use the same rule.
  - *Shell tool.* A command no longer inherits the process environment, where provider API keys live: it receives
    only the variables in the new `ShellConfig.inheritedEnvironment` (default `PATH`, `LANG`, `LC_ALL`, `TERM`,
    `SystemRoot`) plus `environment`; `ShellConfig.development()` sets it to `None` and keeps inheriting everything.
    The new `ShellConfig.pathPolicy` (and the `ShellConfig.readOnlyWithin(policy, workingDirectory)` preset) holds
    every file-like argument of a command, and its working directory, to a `FileConfig`, judged as the program
    will hand it to the OS (so `linksub/../secret` is judged both at the link target's parent and, as Windows reads
    it, beside the link); `--` is not trusted to end the
    options, because an option that takes an argument consumes it (`file -F -- -f list`), so every argument is
    checked as a path and, when it starts with `-`, as a flag. Without a policy a command's file arguments are not
    checked, as before. `file -C`/`-m`/`-M`/`-f`, `date -f`/`-r` and `wc --files0-from`, which write a file or read
    one the command does not name, are refused, by the program's file name (so `/usr/bin/file -C` too), in any
    abbreviated long form GNU accepts (`date --fil`), and after a `--` too.
  - *HTTP tool.* A response body was read in full and then cut at `maxResponseSize`; reading now stops one byte
    past the cap, with the same result for any body.
  - **Migration.** A configuration that relied on a path prefix to cover sibling directories stops matching them
    (list each directory); a symbolic link inside an allowed directory works only if its real target is inside an
    allowed directory; a shell command that needs a process variable (for example `HOME`, `JAVA_HOME`) must be named
    in `inheritedEnvironment` or set in `environment`; `file -C` and friends no longer run in the read-only preset.
    On macOS `/var` is a link to `/private/var`, so the default `blockedPaths` (which includes `/var`) now also
    blocks the real-path per-user temporary directories under `/private/var/folders`, which `java.io.tmpdir` and
    `Files.createTempDirectory` return there; a configuration that reads or writes there must set its own
    `blockedPaths`. A path with a `..` after a symbolic link whose POSIX and Windows readings disagree, one inside
    and one outside, is refused on every OS (with `data/l -> data/a/b`, `data/l/../../x` is refused on Linux too);
    spell it without the `..`.
- **`llm4s-agent`: `PIIMasker` masks international phone numbers and 15-digit card numbers, and `PIIPatterns.maskAll`
  no longer corrupts or fails on overlapping matches** ([#1517](https://github.com/llm4s/llm4s/issues/1517),
  [#1568](https://github.com/llm4s/llm4s/issues/1568)): the
  phone pattern only knew US numbers and the card pattern only the 16-digit layout, so `+44 20 7946 0958` and the
  American Express number `3782 822463 10005` were passed through unmasked. `PIIType.Phone` now also matches a `+`
  followed by 8 to 15 digits with spaces, dashes, dots or parentheses between them (a digit run without the `+` is
  not treated as an international number), and `PIIType.CreditCard` the 4-6-5 layout with prefix 34 or 37, written
  with or without separators. Separately, `maskAll` replaced each match by its original indices, so when two types
  matched overlapping text it cut the wrong characters or threw: `PIIMasker.sensitive` and `PIIMasker.financial`
  failed with a `StringIndexOutOfBoundsException` on a plain 16-digit card number (a card is also account-shaped),
  and `PIIMasker.all` turned `123456789` into `[REDACTED_PASSPORT]_SSN]`. Overlapping matches are now merged into one
  stretch that is replaced once, under the type whose match starts first (the longest, then the type listed first,
  on a tie), so a plain 15- or 16-digit card number under those presets is `[REDACTED_CARD]`. No signature
  changes. The separators inside a phone or card number are now horizontal whitespace, dashes and (for phones) dots
  and parentheses only, so a line break no longer joins digit groups on separate lines into one number: before,
  `555`, `123` and `4567` on three lines were masked together, and the line breaks with them. The guide's table
  named the card placeholder `[REDACTED_CC]`; it is `[REDACTED_CARD]`.
- **Guardrail case folding no longer depends on the JVM default locale**: `ProfanityFilter`, `ToneValidator`
  and `PromptInjectionDetector` lower-cased text with the default locale, so under a Turkish locale `HI`,
  `INAPPROPRIATE` and `IGNORE PREVIOUS INSTRUCTIONS` folded to a dotless `ı` and went undetected. They (and the
  RAG guardrails' parsing of `YES` / `NONE` replies) now fold with `Locale.ROOT`. `Locale.ROOT` alone would
  have left `İGNORE` (Turkish capital dotted I) as `i` plus a combining dot, a locale-independent bypass, so
  the three keyword guardrails now match against a normalised copy of the text: Unicode NFKD, combining marks
  and format characters (zero-width space and joiners, byte-order mark, soft hyphen) removed, lower-cased with
  `Locale.ROOT`, and the dotless `ı` read as `i`. `İGNORE`, `ıgnore`, fullwidth `ＩＧＮＯＲＥ`, accented
  `ïgnöre` and `ig<U+200B>nore` are now all detected. Look-alike letters from other scripts are not mapped.
  `ProfanityFilter` normalises its word list the same way (in case-sensitive mode too, without the case fold),
  and a non-breaking or other compatibility space now separates tokens there and in `ToneValidator`.
- **`TokenizerMapping`: legacy `gpt-3` models are reported as exact, a `gpt-3` under another provider's prefix is no
  longer tokenized as OpenAI's, and the Azure documentation matches the code**
  ([#1557](https://github.com/llm4s/llm4s/issues/1557), [#1558](https://github.com/llm4s/llm4s/issues/1558)): the
  class documentation lists `gpt-3 (legacy)` as `r50k_base`, accuracy Exact, but `getAccuracyInfo` left it out of its
  OpenAI guard, so `gpt-3` and `gpt-3-davinci` were reported `Unknown` and `isExactMapping` returned `false` for them.
  A legacy `gpt-3` name now counts as OpenAI's only when it is plain or prefixed `openai/` or `azure/`: `gpt-3`,
  `gpt-3-davinci`, `openai/gpt-3` and `azure/gpt-3` keep `r50k_base` and are reported Exact (`azure/gpt-3` through
  the OpenAI guard now, so its description reads "Native OpenAI tokenizer" rather than "Azure uses OpenAI
  tokenizers"). **Tokenizer change:** `anthropic/gpt-3` and `ollama/gpt-3` selected `r50k_base` and now select
  `cl100k_base`, as their provider arms do, and stay Approximate; a `gpt-3` under any other prefix (`mistral/gpt-3`)
  falls to the `cl100k_base` fallback, with its warning, and stays Unknown. The class documentation's Azure example,
  `azure/my-gpt4o-deployment`, never selected `o200k_base`: a deployment name is matched on the hyphenated OpenAI
  spelling (`gpt-4o`), so the example is now `azure/my-gpt-4o-deployment` and the documentation says how the
  matching works. The private `getAzureTokenizerId` is gone: its `gpt-4o`, `gpt-4` and `gpt-3` branches could not be
  reached, because the guards above it already take every name that contains those texts, so `cl100k_base`, its
  default, was its only reachable result and is now the Azure arm; no Azure deployment's tokenizer changes.
- **RAG deletes only the chunks of the document you name** (https://github.com/llm4s/llm4s/issues/1000): `RAG.deleteDocumentChunks` deleted
  by a bare prefix, so deleting or re-syncing `doc-1` also deleted every chunk of `doc-10` and
  `doc-1-appendix`. It now matches the `<docId>-chunk-` prefix. Also, `FusionStrategy.WeightedScore(0, 0)`
  now throws `IllegalArgumentException` instead of producing `NaN` scores (https://github.com/llm4s/llm4s/pull/1036).
- **The SQL-backed memory stores answer every `MemoryFilter` the way `InMemoryStore` does** (https://github.com/llm4s/llm4s/issues/1320):
  `VectorMemoryStore` (the file store) treated `MemoryFilter.Custom` as match-all - `recall` ignored the predicate and
  `deleteMatching(Custom(...))` deleted every row - and `SQLiteMemoryStore` ignored it in `recall`, `count` and `search`.
  A `Custom` predicate (alone or inside `And`/`Or`/`Not`) is now decided by `MemoryFilter.matches` before any limit, count or delete.
  Filter values are taken literally: `%`, `_` and `\` in a `ContentContains`, `MetadataContains` or `ByMetadata` value no longer act as
  `LIKE` wildcards, `ContentContains(caseSensitive = true)` is now case sensitive on both stores, and `MetadataContains` can no longer
  match across keys on the file store. `SQLiteMemoryStore(...)` now closes its connection when schema setup fails, so a file that is not a
  database no longer stays locked (it blocked deleting the file on Windows). Also, `SQLiteMemoryStore.search` with a content or metadata
  filter no longer fails with an ambiguous column. **Behaviour change:** `VectorMemoryStore.search` returns a `ConfigurationError` naming
  both dimensions when **no** stored embedding has the query embedding's size, instead of silently falling back to keyword search;
  re-embed the store or use the embedding model it was written with. A store holding vectors of more than one size (the embedding
  model was changed part way) stays searchable: the memories whose embedding cannot be compared are left out, and counted in a
  warning in the log. The FTS-only fallback is gone with it.
  Review follow-up, same entry: SQL now only **narrows** and `matches` decides, as one rule for both stores. A filter next to a
  `Custom` still narrows in SQL (`And(ByEntity(e), Custom(p))` reads only the rows of `e`, with the limit applied after `p`), and
  `Not` is pushed to SQL only when what it negates is exact. Case-insensitive `ContentContains` now compares with the very
  `String.toLowerCase` that `matches` uses (a `java_lower` function registered on the SQLite connection): SQLite's `lower()` folds ASCII
  only, so `école` did not match `ÉCOLE`, and `i` did not match `İstanbul`. Comparisons with nullable columns are two-valued, so
  `Not(MinImportance(0.4))`, `Not(ByEntity(...))` and `Not(ByMetadata(...))` no longer drop the memories that have no importance, entity
  or metadata (both stores). A metadata key with a `.` or `[` in it is a name, not a JSON path step, on `SQLiteMemoryStore`; the file
  store's `ByMetadata` and `HasMetadata` are now case sensitive (they used `LIKE`, which ignores the case of ASCII letters).
  `deleteMatching` runs in one transaction on both stores: it is atomic, and 2,000 rows took about 0.3 s instead of about 2 s.
  **Fix:** `SQLiteMemoryStore` read back metadata containing a backslash sequence wrongly (`c:\temp` came back as `c:`, a tab and
  `emp`, because the decoder undid its escapes one after another); metadata is now written and read with a JSON parser, and rows
  written before are read correctly too.
  **Fix:** `MemoryFilter.ByTypes` with two or more types failed on both stores with an index error (the placeholders were built by
  mapping the `Set` of types, which collapsed them into one `?`).
- **`GuardrailAction.Warn` now logs in five more guardrails**: `Warn` is documented as "log a warning and let
  processing continue", but `PromptInjectionDetector`, `GroundingGuardrail`, `ContextRelevanceGuardrail`,
  `TopicBoundaryGuardrail` and `SourceAttributionGuardrail` passed the text through without a word, so a
  monitoring deployment (`PromptInjectionDetector.monitoring`, `GroundingGuardrail.monitoring`, ...) detected
  violations and told nobody. Each now logs one `WARN` through its own class logger, as `SecretLeakGuardrail`
  does: the categories and how many patterns matched, or the score, the threshold and how many claims - never the
  input, the response, the retrieved chunks, the topic the model read into a query, or the judge's explanation.
  The value returned is unchanged. `ContextRelevanceGuardrail` and `SourceAttributionGuardrail` also log when
  `Fix` is chosen, because for them `Fix` falls back to `Warn`. `PIIDetector` is a separate change; the four
  `LLM*Guardrail` judges have no `Warn` mode, so nothing changes for them.
- **MCP: a tool result flagged `isError` is a failure** (https://github.com/llm4s/llm4s/issues/1006): `MCPClientImpl` returned a
  server-reported failure to the agent as a successful tool result. It now returns
  `Left("Tool call failed: <text>")`, or `Left("Tool call failed: server reported an error")` when the
  result has no text (https://github.com/llm4s/llm4s/pull/1041).
- **`VectorMemoryStore` (SQLite) fixes** (https://github.com/llm4s/llm4s/issues/1002, https://github.com/llm4s/llm4s/pull/1306): metadata is stored as escaped JSON (a value
  containing a double quote or a brace was silently truncated on read; the old format stays readable); the
  JDBC connection is closed when opening the file fails (it leaked and kept the file locked, which aborted
  the Windows test suite); and `PRAGMA busy_timeout = 30000` makes concurrent writers wait instead of
  failing with `SQLITE_BUSY`. Remaining store issues are tracked in [#1320](https://github.com/llm4s/llm4s/issues/1320).
- **One retry rule: `LLMError.isRecoverable` and `RetryPolicy.isRetryable` no longer disagree**
  ([#1316](https://github.com/llm4s/llm4s/issues/1316)). Four recoverable errors - `APIError`, `ExecutionError`,
  `SystemError` and `OptimisticLockFailure` - were never retried by the default `RetryPolicy` (so by `ReliableClient`),
  while `LLMClientRetry` retried every recoverable error and the agent graph's node retries retried every
  recoverable error and a client-error `ServiceError`; a caller who branched on `isRecoverable` could not know what
  would be retried. There is now one rule, `RetryPolicy.isRetryable`, used by all three and by `ErrorRecovery.recoverWithBackoff`: an error is retried if it is
  recoverable, except a response with a client-error HTTP status (any 4xx but 408 and 429, on a `ServiceError` or an
  `APIError`) and an `OptimisticLockFailure`, both of which need the caller first. The `ScalaDoc` of
  `RecoverableError`, `LLMError.isRecoverable` and `RetryPolicy.isRetryable` states the contract, and
  `RetryContractSpec` pins it for every concrete `LLMError`, failing when a new error type has no row.
  **Behaviour changes:** the default policy now retries `ExecutionError`, `SystemError` and an `APIError` with no
  status or a retryable one (no LLM client produces these today, so `ReliableClient` is unaffected in practice);
  `RetryPolicy.custom` with no predicate of its own now uses the same rule (it retried only rate-limit, timeout,
  network and 5xx/408/429 `ServiceError` errors, so a policy that customised only the delay missed `ExecutionError`,
  `SystemError` and `APIError`); `LLMClientRetry` no longer retries a 4xx `APIError` or an `OptimisticLockFailure`; and the agent graph's default
  node retry no longer retries a 4xx `ServiceError` or an `OptimisticLockFailure`. `RetryPolicy.recoverableOnly`
  (graph) is renamed `RetryPolicy.transientOnly`, as it is no longer `isRecoverable`.
  `ErrorRecovery.recoverWithBackoff`, which retried only `RateLimitError`, `TimeoutError` and a 5xx/429/408
  `ServiceError`, now also retries `NetworkError`, `ExecutionError`, `SystemError` and an `APIError` with no status or a
  retryable one, waiting `baseDelay` times the attempt number (the schedules of the three it already retried are
  unchanged); docs/guide/error-handling.md describes the rule.
- **`ToolRegistry.executeAsync` and `executeAll` no longer park an execution-context thread while a retry waits out
  its backoff (#1066).** The delay was a `Thread.sleep` inside `blocking`, so with many calls retrying at once a
  fixed-size pool sat idle for the whole delay: six retrying calls with a 0.8 s backoff on a two-thread pool took
  2.4 s, and now take about 0.8 s. The backoff is a scheduled delay (the JDK's shared daemon delay thread, no pool of
  our own) and the next attempt is dispatched to the caller's `ExecutionContext` when it elapses; each attempt still
  holds a pool thread for its own duration. The synchronous `execute` is unchanged: it waits on its caller's thread,
  interruptibly, and an interruption ends the call as `Cancelled`. Which errors retry and how long each wait is are now
  decided in one place for both paths. Reworked from #1197 by @ipsitsahoo, whose split between a synchronous and an
  asynchronous retry path this keeps; the guide has a new "Tool Call Timeouts and Retries" section.
- **MCP: text stays text, tool failures are `isError` results, `getTools` returns a `Left`** ([#1319](https://github.com/llm4s/llm4s/issues/1319)):
  the client no longer turns a text result that parses as JSON into a JSON value (a tool that returned
  `"24"` is not handed back as the number 24); an object result also travels as the `structuredContent` the
  server now sends, and the client returns it as that value (the specification types `structuredContent` as a
  JSON object, so a number, array or `null` result is sent as its JSON text only). `MCPServer` reports a
  tool that fails as a normal `tools/call` result with `isError: true` and the message as text, as the MCP
  specification models it; an unknown tool stays a JSON-RPC error. `MCPClientImpl.getTools` returns a `Left`
  for a failed listing instead of an empty `Seq`, so "no tools" and "unreachable" differ, and it skips (and
  logs by name) a tool entry it cannot read instead of failing the whole listing (a listing none of whose
  entries can be read is still a `Left`); a `tools/call` result that
  carries only `structuredContent` is returned. `MCPToolRegistry` offers no tool of a server whose refresh
  failed: the failed client is closed, and the tools it served call through that client, so they are dropped
  with it and fetched again by the next lookup (a refresh that is only cancelled keeps them). Request ids were
  already unique and are now tested. **Migration:** a caller that read an MCP tool's text result as JSON must
  parse it itself; a caller of `getTools` must handle `Left`.
- **Install snippets follow the latest release** ([#1281](https://github.com/llm4s/llm4s/issues/1281)): the
  installation guide, the dependency-conflicts reference, the image-generation guide and the FAQ pinned a literal
  `0.4.0` or `0.4.1` in sbt, Maven and Gradle snippets, so they said different things (the latest release is
  0.4.1) and would have sent new users to the old release after 0.5.0. They now use
  `{{ site.data.project.latest_release }}`, which `pages.yml` sets from the latest GitHub Release at deploy.
  The committed `docs/_data/project.yml` defaults are 0.4.1. The "Snapshot Versions" section described
  `0.4.0-SNAPSHOT`, which nothing publishes (releases are cut from tags only, and the build names a snapshot
  `0.4.1+165-abc1234-SNAPSHOT`); it now says how to build and publish `main` locally. A new check,
  `scripts/check-doc-versions.sh`, runs in CI quick checks and fails on a literal pin.
- **`CompositeGuardrail.any` stops at the first guardrail that passes, and the composite sample no longer casts**
  ([#1315](https://github.com/llm4s/llm4s/issues/1315)): `any` documented "returns on first success" but mapped over every
  guardrail first, so one that costs a call (an LLM judge) ran even after an earlier one had passed. It now stops at the
  first pass, with the same result as before: the first passing guardrail's, or every failure together when none passes.
  `CompositeGuardrailExample` cast composites with `asInstanceOf[InputGuardrail]` to hand them to `GuardrailMiddleware`,
  which throws a `ClassCastException` (a `CompositeGuardrail` is a `Guardrail[String]`, not an `InputGuardrail`); it wraps
  them now, and the guardrails guide shows the wrapper. The guide also states how a guardrail list runs (every guardrail,
  even after a failure), what a block sends (one `agent.guardrail_blocked`, then `RunFailed`, never `RunCompleted`) and that
  `maxSteps` counts the same through every entry point; `AgentRunSemanticsSpec` pins each. The other findings of the issue
  described the loop the graph runtime replaced and no longer apply (`AgentFailed`, `runWithEvents` and `runCollectingEvents`
  are gone; see the migration guide). No migration is needed.
- **`ToneValidator` classified every multi-line text as Neutral**: it looked for its keywords with
  `lower.matches(".*\\b(...)\\b.*")`, and `.` does not match a line break, so a newline anywhere in the text
  made all four keyword checks fail, whatever the text said. `"Thank you for your inquiry.\nWe will respond
  shortly."` was refused by `ToneValidator.professionalOnly`, and LLM output is often several lines or
  paragraphs. The keywords are now found with `find`, over `\n`, `\r\n`, `\r` and the Unicode line and paragraph
  separators alike. Single-line text is classified as before, and so is the order of the checks (exclamation
  first, then Professional, Casual, Friendly, Formal). **Behaviour change:** a validator that allowed only
  `Neutral` used to accept multi-line text of any tone, and now refuses it when it carries a keyword.
- **Vertex AI token refresh is serialised** (https://github.com/llm4s/llm4s/pull/1191): concurrent callers with an expired token each fetched
  a new one; the first now refreshes under a lock and the rest reuse it.
- **`ToolRegistry` no longer loses a timeout that fires before the tool starts** (https://github.com/llm4s/llm4s/issues/1139, https://github.com/llm4s/llm4s/pull/1194): when
  the thread pool was busy and the scheduled timeout fired before the tool's worker had started, the
  timeout was silently dropped and the tool then ran unbounded. Which side records the outcome is now
  decided by a separate atomic flag, and a worker that starts after the timeout bails out at once.
- `PIIDetector` in `GuardrailAction.Warn` mode (the `PIIDetector.monitoring` preset) detected PII
  and then said nothing: the branch let the text through with a comment that it "would log to the
  trace system". It now logs one WARN naming the PII types found and how many of each - never
  the matched text - and still returns the text unchanged. `Block` and `Fix` are unchanged, and
  the `Block` error reads as before. `PromptInjectionDetector`, `GroundingGuardrail` and
  `ContextRelevanceGuardrail` still have a `Warn` that logs nothing. Reworked from #1094 by
  @Shubha9807.
- **The `workspace-runner` image builds again** (https://github.com/llm4s/llm4s/pull/1311): the SDKMAN install of Scala 2.13.14, which no
  longer exists, is dropped and the SDKMAN downloads are retried.
- **Voyage embeddings post to the right URL.** The default base URL (and the documented
  `VOYAGE_EMBEDDING_BASE_URL`) end in `/v1`, and the client appended `/v1/embeddings`, so every
  request went to `/v1/v1/embeddings`. It now appends `/embeddings`.
- **JSON a model or a tool produced is refused beyond 512 levels of nesting instead of overflowing
  the stack** ([#1562](https://github.com/llm4s/llm4s/issues/1562)). `ujson.read` itself is
  iterative, but every traversal of the value it builds - rendering it back to the provider or into
  a log line or error message, `upickle.default.read[A]`, equality - recurses once per level, and a
  `StackOverflowError` is not an `Exception`: `Try` does not catch it, so a reply such as 100,000
  nested `[` escaped `Result`-returning APIs as an `Error` (`GraphJsonParser.parse`,
  `GraphQueryTranslator`, `NativeQueryGenerator`, `ToolOutputCompressor` from a few thousand
  levels). A `private[llm4s]` `org.llm4s.util.BoundedJson.read` now measures the depth on the raw
  text first (an iterative, string-aware scan, as `json_tool`'s since #1510) and returns a `Left`
  naming the 512-level limit, and every place that parses model- or tool-produced text uses it:
  `LLMClient.completeStructured` (a `ValidationError` on `structured_output`), streamed and
  non-streamed tool-call arguments in `StreamingToolArgumentParser`, `StreamingAccumulator`,
  `OpenAICompatibleDialect.lenientToolCalls` (arguments become `{}`), `StandardToolCallDeserializer`
  (the completion fails, as for malformed arguments) and `OpenAIClient` (the call is dropped, as
  for malformed arguments), `ToolOutputCompressor` (the result is compressed as text), the
  `JSONValidator` guardrail (rejected as not valid JSON), the knowledge-graph parsers
  (`GraphJsonParser`, `GraphQueryTranslator`, `NativeQueryGenerator`, `GraphQAPipeline`,
  `EntityLinker`: a `ProcessingError`, or the existing fallback), the RAG evaluation metrics
  (`Faithfulness`, `AnswerRelevancy`, `ContextPrecision`, `ContextRecall`: an `EvaluationError`,
  where a deep claims reply used to overflow through the `InvalidData` message `.str` builds),
  `TestDataset` and `GroundTruthGenerator`, `LLMReranker` (neutral scores, as for any reply it
  cannot read), `LLMMemoryManager.extractEntities` (a `ProcessingError`), and the Ollama client's
  tool-call arguments when they arrive as a string: a reply's are a malformed call (a
  `ProcessingError` naming the limit), and an assistant turn's are sent as `{}` like any string that
  is not a JSON object, never parsed and rendered back. `json_tool` now measures its documents with
  the same scan. The parser's own failure is reported as a `ValidationError` on `json` carrying its
  message, mapped directly rather than through `DefaultErrorMapper`, which read the JSON path in
  that message (`$[429]`) as a rate limit. Configuration, model metadata, JSON the library wrote
  itself and provider response envelopes, in which the model's text sits inside string literals,
  are parsed as before.
- **`ReliableClient` applies `ReliabilityConfig.rateLimit`**
  ([#1133](https://github.com/llm4s/llm4s/issues/1133)). Only `ReliableProviders.wrap` honoured
  it, so `new ReliableClient(...)` with rate limiting enabled made every call unthrottled. The
  token bucket is now part of `ReliableClient`, consulted before every attempt, retries included.
  A local rejection does not count as a provider failure for the circuit breaker, and
  `ReliabilityConfig.disabled` turns rate limiting off with everything else.
- **A `PlanRunner` node cancelled while running is reported as cancelled**
  ([#1133](https://github.com/llm4s/llm4s/issues/1133)). The race against cancellation mapped a
  future that only ever failed, so the "Node <id> cancelled" `PlanExecutionError` was never
  produced and callers got a `NodeExecutionError` wrapping an exception instead.
- **A Langfuse batch that was only partly accepted was reported as a successful export.** The
  ingestion endpoint answers `207 Multi-Status` with a result per event, and events listed under
  `errors` are dropped; `LangfuseTracing` returned `Right(())` and `DefaultLangfuseBatchSender`
  logged "successful" for any 207. Both now read the body: a 207 that lists rejected events is a
  `Left` from `LangfuseTracing` (and an error log from the batch sender) naming each event, its
  status and message. A 207 with no rejections is still a success, and one whose body cannot be
  read is treated as accepted with a warning, so tracing does not fail on an unexpected shape
  (found in review of [#1239](https://github.com/llm4s/llm4s/pull/1239)).

- **`llm4s-core`: redaction covers credentials in more shapes** ([#1518](https://github.com/llm4s/llm4s/issues/1518)):
  the redaction behind `ProviderExchangeSink` and the logged request and response bodies replaced a credential only in
  `"key": "value"` JSON with a short list of exact key names, in URL query strings, in `Authorization` headers and in
  strings shaped like a provider key. It now also redacts the same JSON when it sits inside a prompt or response
  string (`\"api_key\": \"...\"`), single-quoted JSON, a number under a credential key (written back as a string, so
  the JSON still parses), `key=value` pairs and `key: value` header lines outside a query string (including dotted
  property names such as `spring.datasource.password=...`), and compound key names such as `client_secret`,
  `x-api-key`, `refresh_token` and `db_password`. A value that contains an escaped quote used to be cut at the quote,
  leaving the rest of the credential in the log; it is now redacted whole, and so is a value cut off before its closing
  quote, as a truncated payload leaves it. A key is matched as a whole name, never as a
  substring, so `max_tokens`, `prompt_tokens`, `token_count` and `next_page_token` are not redacted. Key names are now
  lower-cased with `Locale.ROOT`, so a Turkish default locale no longer stops `API_KEY` from being recognised. Redaction
  is still pattern-based and best effort: it does not detect a secret that is not under a key, and JSON escaped twice
  is not recognised.
- **Docs: the Reference section's Migration Guide, Release Process, Scalafix Rules and Test Coverage pages no longer
  404** ([#444](https://github.com/llm4s/llm4s/issues/444)): `migration.md`, `release.md`, `scalafix.md` and
  `test-coverage.md` (and `security.md`, `workspace-sandbox.md` and `benchmarks.md`) had no front matter, so Jekyll
  served them as raw files and their links on llm4s.org returned 404. They now have a title, `parent: Reference` and a
  `nav_order`, and are listed in the Reference index. `test-coverage.md` described a single 50% threshold that no
  longer exists; it now describes the per-module `coverageFloor`, `coveragePolicyCheck` and the Codecov statuses.
  Links inside the Reference pages that ended in `.md`, or pointed at repository-root files, now use the form the
  site serves. Links to the newly rendered pages from `installation.md`, `providers.md`, `0x-to-1x.md` and
  `migration.md` itself were changed from `.md` to the page URL in the same change, since a `.md` URL is a 404 once
  its page is rendered, and the 55 entries `scripts/docs-link-baseline.txt` held for the links this fixes are removed.
- **`AudioPreprocessing.resamplePcm16` could hang, and its output length was wrong**
  ([#1308](https://github.com/llm4s/llm4s/issues/1308)): a target rate of `-8000`, or a source rate of `-1`, sent
  Java Sound's converter into a loop that never ended (a test JVM spun at 100% CPU for twenty minutes), a target
  of `0` or `-1` "succeeded" with that nonsense as the new sample rate, and a source rate of `0` or no channels threw
  `ArithmeticException: / by zero` inside it. The arguments are now checked first - both rates between 1 and
  768000 Hz, 1 to 64 channels, a bit depth that is a multiple of 8 - and anything else is a `Left(ValidationError)`
  naming the field (`targetRate`, `source.sampleRate`, `source.numChannels`, `source.bitDepth`), as is an output
  above 256 MiB (a 10 MB input declared at 100 Hz and converted to 16 kHz would be 1.6 GB, and used to run a small
  JVM out of memory, an `Error` that no `Result` catches; the bound is checked from the expected frame count before
  anything is allocated, and the output is written into a single array of exactly that size). Reading the
  converter's output now stops at the end of the stream, at a read that returns nothing, and at the expected
  length, so it cannot spin; a converter that delivers more than 8 frames fewer than expected, or none, is a
  `Left(ProcessingError)` instead of being padded with silence and reported as a success. The output has **exactly**
  `round(frames * targetRate / sourceRate)` frames (it was longer: 2 frames more at 24 to 16 kHz, 4 at 16 to 24, 16 at
  8 times up), empty input gives empty output (it gave 2 zero frames), equal rates return a copy, and a trailing
  partial frame is ignored as it is by `toMono` and `trimSilence`. **Behaviour change:** a caller that passed a rate
  or format outside those bounds used to get a wrong "success" or a generic `ProcessingError` and now gets a
  `ValidationError`; the output is shorter by the converter's padding, and the source's last fraction of a
  millisecond (at most 0.3 ms) is no longer in it.
- **The docs deploy is no longer rejected on a release tag** ([#1152](https://github.com/llm4s/llm4s/issues/1152)):
  `release.yml` called `pages.yml` as a reusable workflow, which runs on the caller's ref, the release tag, and the
  `github-pages` environment allows deployments only from `main`, so the deploy was rejected before it started
  ("Tag "v0.4.1" is not allowed to deploy to github-pages") and the release run ended red after a successful
  publish. The release's `docs` job now dispatches `pages.yml` on `main` with the tag as a new `ref` input
  (`scripts/dispatch-docs-deploy.sh`), so the run, and the deploy, are on `main`, while the build still checks out
  the tag, so the version and the install snippets are the release's. The job waits for that run and fails when the
  deploy fails, keeping the order #1147 built (docs only after the artifacts are on Maven Central and the GitHub
  Release exists). No repository setting is needed; an environment rule for `v*` tags remains an equally valid
  alternative. `pages.yml` is no longer callable with `workflow_call`, and a manual run takes an optional release
  tag. A pushed docs change on `main` deploys exactly as before. Not yet exercised by a real release: the next one
  is its first run (see `docs/reference/release.md` for a way to check it sooner).
- **`RAG.refresh` emptied the index when its loader failed, and `RAG.sync` deleted documents it
  could not read** (follow-up to [#1236](https://github.com/llm4s/llm4s/pull/1236)).
  `refresh` and `refreshAsync` cleared the index before reading the loader, so a listing
  failure left it empty, and one on a later S3 page left it half-rebuilt. They now read the
  whole loader first and return a `ListingFailure` - or, under `failFast`, any read failure - as
  the `Left` with the index and registry untouched; the price is that `refresh` holds every
  loaded document in memory before clearing (`sync` still streams). `sync` and `syncAsync`
  deleted a previously indexed document whose read failed (a transient S3 `GetObject` error,
  a failed extraction), because it never reached the set of documents seen.
  `LoadResult.Failure` gains `documentId: Option[String] = None`; `SourceBackedLoader`,
  `UrlLoader` and `FileLoader` (on extraction failure) set it, and sync keeps that document's
  indexed version, uncounted in `SyncStats`. A failure without one - a `WebCrawlerLoader` page,
  whose unfollowed links hide other pages, or a custom loader's - makes sync skip its deletion
  pass for that run. Read failures are logged at WARN. `syncAsync` also no longer deletes a
  listed document whose registry lookup failed. A match on `LoadResult.Failure` needs a fourth
  argument; see the
  [migration note](docs/reference/migration.md#a-failed-read-no-longer-deletes-or-clears-indexed-documents).
- **An S3 listing that failed was reported as a successful sync of 0 documents, and could wipe
  the index** ([#1231](https://github.com/llm4s/llm4s/pull/1231)). With no AWS credentials, a
  missing bucket or access denied, `S3DocumentSource` returned the listing error, but
  `SourceBackedLoader` turned it into an ordinary per-document `LoadResult.Failure("list-error",
  ...)`, which `RAG.sync` skipped like any other failure - so `sync` returned
  `Right(SyncStats(0, 0, 0, 0))`, and then, having seen no documents, deleted every document it
  had previously indexed from that source. A new `LoadResult.ListingFailure(source, error)` now
  marks "the loader could not enumerate its documents", distinct from one document failing to
  read or extract. `SourceBackedLoader` emits it for any source's listing error, including one
  on a later S3 page, and `DirectoryLoader` for a missing directory or a path that is not one.
  `RAG.sync` stops at it, returns its error as the `Left` and deletes nothing; `RAG.ingest` and
  `RAG.refresh` return it as the `Left` whatever `failFast` says, as do `ingestAsync`,
  `syncAsync` and `refreshAsync`. An empty bucket still syncs with 0 documents, and a single
  unreadable object is still skipped. `S3LoaderExample` now reports the failure and stops,
  instead of querying an empty index. See the
  [migration note](docs/reference/migration.md#a-failed-listing-fails-the-sync).
- **`MCPClientImpl` fell back to the HTTP+SSE transport for a dead server whose port number contained `404`
  or `405`**, and for any HTTP error whose body mentioned them. It read "the server answered 404/405" off the
  error text with `contains`, and a refused-connection message carries the URL, so about one run in 150 on an
  ephemeral port such as `40413` reported "Failed to connect with both transports" instead of the connection
  error (this failed `MCPErrorPathsSpec` in CI on unrelated pull requests). The check now matches only the
  messages `StreamableHTTPTransportImpl` writes for those statuses, anchored to the start of the message.
  A real 404 or 405 still falls back. The `MultiProviderComparisonExample` sample takes its clock as a
  parameter, so its latency test no longer compares a cold first call with a sleeping one. The `Test` and
  `Code Coverage` CI jobs have a 90-minute limit instead of GitHub's default 360.
- **`RAG.build` failed on a vectors table created by `PgSearchIndex`** with
  `column "created_at" does not exist` (found in [#1231](https://github.com/llm4s/llm4s/pull/1231)).
  `RAGConfig.withSearchIndex` points `PgVectorStore` at the `PgSearchIndex` table, but the two had
  separate `CREATE TABLE` statements: `PgSchemaManager.extendVectorsTable` omitted `created_at`
  and made `content` `NOT NULL`, so after `PgSearchIndex.initializeSchema()` the vector store's
  `created_at` index failed. Both now create the table from one definition
  (`org.llm4s.vectorstore.PgVectorTableSchema`, internal). Existing tables created by the old
  permission DDL are upgraded in place when either side opens them: `created_at` is added (existing
  rows get the upgrade time) and `content` becomes nullable. Each step checks the catalog first, so
  an up-to-date table is not locked, and the column is added with `ADD COLUMN IF NOT EXISTS`, so
  replicas initialising at once do not fail on a duplicate column. `PgSchemaManager.extendVectorsTable` now also rejects a table
  name that is not a valid SQL identifier, as `PgSearchIndex` and `PgVectorStore` already did.
- **`llm4s-rag`: re-ingesting a document replaces it, and several inputs return `Left` instead of throwing**
  ([#1318](https://github.com/llm4s/llm4s/issues/1318)): `RAG.ingestText` / `ingestChunks` / `ingest` upserted by
  chunk id, so a document that came back with fewer chunks (or none) kept its old tail and went on matching
  queries. Indexing now embeds, writes the new chunks over the old ones, then removes the old version's tail.
  Nothing is deleted first, and a write that fails in either store - including after the other store was written -
  is rolled back, so a document that cannot be embedded or stored keeps its previous version in both stores.
  The rollback covers the failing write itself, since a store can commit a batch and lose the response (a
  timed-out Qdrant upsert); if the rollback fails too, that is logged at ERROR and the returned error names both
  failures. Telling whether a document is already stored is one lookup
  by id, so ingesting a new document costs no scan of the store. `sync` / `syncAsync` no longer delete a changed
  document's chunks before re-ingesting it, and a failed ingest no longer registers the document's new version,
  so the next sync retries it instead of treating it as unchanged. `deleteByPrefix` on the SQLite and
  pgvector stores and keyword indexes used the prefix as a `LIKE` pattern, so ids containing `_` or `%` deleted
  other documents' chunks; they are matched literally now. On SQLite it is also case-sensitive (`GLOB`): SQLite's
  `LIKE` folds ASCII case, so deleting or re-ingesting `Doc-A` removed `doc-a`'s chunks too. A reranker returning an out-of-range index made
  `HybridSearcher` throw `IndexOutOfBoundsException`; that result is now dropped with a WARN, as
  `AsyncHybridSearcher` always did. A candidate the reranker names twice is returned once, with its first score, and
  a non-empty reranker response that names no candidate at all is now a `Left(ProcessingError)` in both searchers
  instead of an empty success (an empty response stays an empty success). `WeightedScore` fusion no longer scores a channel's weakest genuine hit `0`, the
  score of a miss: it maps to `0.1`, the best to `1`, so weighted scores shift. New
  `ChunkingUtils.chunkTextValidated` returns a `Left(ValidationError)` for a non-positive size or an overlap that is
  not smaller than it, and `FileEmbedder.encodeFromPath` uses it, so an unusable text-chunking configuration is a
  `Left` instead of an `IllegalArgumentException`. The `ChunkingConfig` and `WeightedScore` constructors keep
  throwing on an invalid value (decided, #1318 items 4-5): `RAGConfig.withChunking` and `withWeightedScore` are
  chainable builders that return a `RAGConfig`, which a `Left` cannot be, so validate such values from user input
  first. `WeightedScore` weights, and their sum, must be finite as well as non-negative: an infinite weight or sum
  scored `Inf` or `NaN`. The Postgres stores' prefix delete now writes its escape character as `E'\\'`, which does
  not depend on the server's `standard_conforming_strings` setting. Known limitation: `RAG.deleteDocument("a")` also
  removes the chunks of a document whose id looks like `a-chunk-<n>`; avoid ids of that shape. `documentCount` / `chunkCount` count each
  document once with its current chunks: a re-ingest replaces its count, one re-ingested empty or deleted (by
  `deleteDocument` or a sync) no longer counts, and an ingest that produced no chunks never did.
- **The docs taught a configuration route that no longer exists.** Since
  [#903](https://github.com/llm4s/llm4s/pull/903) (0.3.2) nothing in llm4s reads `LLM_MODEL` or a
  provider's API-key variable, yet the README, CLAUDE.md, every getting-started page and most
  samples still told users to set them, so a first-time user following the README got a
  configuration error ([#1132](https://github.com/llm4s/llm4s/issues/1132)). They now teach named
  provider sections in `application.conf` (`apiKey = ${?OPENAI_API_KEY}`, selected by
  `llm4s.providers.provider`), list the environment variables that some `reference.conf` really
  binds, and say where a tool reads `LLM_MODEL` itself (the chat-tui sample, the config-policy env
  check). `DocumentedProviderConfigSpec` loads the documented configuration. See the
  [migration note](docs/reference/migration.md#from-llm_model-to-named-provider-sections).
- **One bad provider section broke every provider.** Every section under `llm4s.providers` was
  validated on every load, so a section whose `${?VAR}` API key was unset, whose provider module
  was not on the classpath, or which had a key of the wrong type failed `defaultProvider()`,
  `provider(name)`, `defaultProviderName()` and `listModels` for every section: a config holding
  `openai-main` and `anthropic-main` could not load either with only `OPENAI_API_KEY` set. Only
  the section being loaded is validated now, and a section that cannot even be read fails only
  its own lookups; the error still names the section. `providerConfigs()` reports each section's
  problem in its error map instead of failing whole. `providers()`, which returns every section,
  still validates them all, and a providers block that is not an object - or a default naming no
  section - still fails every lookup. As a consequence only the loaded section's
  deprecated-alias and unknown-key warnings are logged. Source break: `defaultProviderName()`
  no longer takes a `ProviderRegistry`, since reading a name needs none; drop an explicit
  `(using registry)` argument ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **OpenAI embeddings' missing-key error named `OPENAI_API_KEY` and a key nothing reads.** The
  key was read from `llm4s.embeddings.openai.apiKey`, falling back to `llm4s.openai.apiKey` - the
  single-provider chat key nothing has read or bound since
  [#903](https://github.com/llm4s/llm4s/pull/903) - and the error said "Missing openai
  embeddings apiKey (llm4s.openai.apiKey / OPENAI_API_KEY)", though llm4s reads no provider
  API-key variable on its own. The fallback is removed, and the error names only
  `llm4s.embeddings.openai.apiKey`, which the application binds with
  `llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}` as before. Behaviour change: a config
  that set only `llm4s.openai.apiKey` must set `llm4s.embeddings.openai.apiKey` instead
  ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **The missing-field error pointed at `llm4s.conf`**, a file nothing loads, and at
  `providers.<name>` rather than the section's real path. A named section missing a required
  `apiKey`, `baseUrl` or provider-specific key now says `set it in application.conf under
  llm4s.providers.<name>.<key>` ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **Core's `reference.conf` said OTLP headers could be set with `OTEL_EXPORTER_OTLP_HEADERS`**,
  which nothing reads. The comment now says what the docs already did: headers are the
  `llm4s.tracing.opentelemetry.headers` map (or `OpenTelemetryConfig.headers`), bound from a
  variable of your choosing if you like ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **Every provider-config warning was logged twice** by `Llm4sConfig.defaultProvider`,
  `providerFrom` and the default-provider `listModels`: they read the default provider's name and
  then its section through two separate loads of `llm4s.providers`, and each load validated the
  whole block, logging its deprecated-alias and unknown-key warnings again. The block is now
  loaded once and both are read from that result. Results are unchanged
  ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
- **`complete` on `OpenAICompatibleClient` could wait for ever.** It sent its request with no
  timeout - as the old `DeepSeekClient`, `ZaiClient` and `OpenRouterClient` had
  ([#912](https://github.com/llm4s/llm4s/issues/912)) - so an endpoint that accepted the
  connection and never answered hung the caller. Every provider on the shared client (DeepSeek,
  Z.ai, OpenRouter, Mistral, Cohere and the generic `openai-compatible`) now times out after two
  minutes, what the old Mistral and Cohere clients used; streaming keeps its five minutes.
  Configurable timeouts remain [#712](https://github.com/llm4s/llm4s/issues/712).
- **Streamed completions from vLLM, Ollama's `/v1`, Perplexity's Router and other servers that
  follow OpenAI carried no token usage**: they report it only when asked with
  `stream_options.include_usage`, which `OpenAICompatibleClient` never sent. A streaming request
  now sends `"stream_options": {"include_usage": true}` where the provider accepts it - the
  generic `openai-compatible` provider and DeepSeek - through a new dialect hook,
  `OpenAICompatibleDialect.streamUsageOption` (default `true`). Z.ai, OpenRouter, Mistral and
  Cohere answer `false`: Mistral rejects unknown fields with a 422, Z.ai and Cohere do not
  document it, and OpenRouter always streams usage. For an endpoint that rejects the field,
  `OpenAICompatibleConfig` has `streamUsage` (default `true`), settable from a named section
  (see Added).
- **A Requesty config reported `providerId` = `openai`**, because `OpenAIConfig` inferred its id
  from the base URL, which is neither OpenAI's nor OpenRouter's. `OpenAIConfig` gains a trailing
  `explicitProviderId: Option[ProviderId] = None` (and `fromValues` a defaulted `providerId`),
  which the Requesty and OpenRouter descriptors set: a `provider = "requesty"` section now
  reports `requesty` - in `llm4s-config-policy` too, whose `allowedProviders` and model patterns
  must name it - and a `provider = "openrouter"` section with a proxy `baseUrl` is no longer
  routed to OpenAI. `None` infers the id from the base URL as before. See the
  [migration note](docs/reference/migration.md#requesty-configs-report-requesty).
- **`OPENAI_COMPATIBLE_BASE_URL` was named but read by nothing.** It and
  `OPENAI_COMPATIBLE_API_KEY` are now `OpenAICompatibleConfigKeys` constants, the conventional
  names for binding a generic `openai-compatible` section from the environment. The chat-tui
  sample reads `LLM_MODEL=openai-compatible/<model>` with them (base URL required, key optional,
  split on the first `/` so `openai-compatible/openai/gpt-oss-120b` keeps its model id), as it
  does `deepseek/`, `mistral/` and the rest, and the config-policy env check takes
  `OPENAI_COMPATIBLE_BASE_URL` as the endpoint for that provider. `Llm4sConfig` still reads no
  provider's variables and no `LLM_MODEL`: named sections are its only route.
- The Groq example in `llm4s-openai-compatible`'s `reference.conf` and the
  `OpenAICompatibleProvider` Scaladoc named `llama-3.3-70b-versatile`, which Groq shut down for
  free and developer tiers on 2026-08-16; they now use `openai/gpt-oss-120b`, as the providers
  guide's Groq recipe does.
- **`EMBEDDING_MODEL=ollama/nomic-embed-text` failed `Llm4sConfig.textEmbeddingModel()`** with
  `Unknown model 'nomic-embed-text' for provider 'ollama'`. The configuration is documented in
  the README and `CLAUDE.md`, but the central dimension table covered only `openai`, `voyage`
  and `local`. Ollama now declares `nomic-embed-text` (768), `mxbai-embed-large` (1024) and
  `all-minilm` (384), with a `:latest` tag folded onto the untagged name.
- **`voyage-3-large` was recorded as 1536-dimensional**; its default output is 1024. `voyage-3`
  (1024), the model `CLAUDE.md` documents, and `voyage-3-lite` (512) were missing and are now
  declared, as is OpenAI's `text-embedding-ada-002` (1536).
- **`provider = "vertexai"` failed config validation outright, making Vertex AI unreachable.**
  `ProviderKind.VertexAI` existed, `NamedProviderLoader` built a `VertexAIConfig` from it and
  `LLMConnect` built a `VertexAIClient` from that - but Vertex AI was absent from
  `ProviderCapabilitiesRegistry` (which listed 11 of 12) and had no `NamedProviderValidators`
  object. Because `NamedProviderConfigValidator.validate` routes through that registry, every
  Vertex AI configuration was rejected before any of the supporting code could run, so the
  provider was supported everywhere except at the one point that decides whether a config loads.
  Found while scoping [#1131](https://github.com/llm4s/llm4s/issues/1131); both are now present
  and the config path is covered by tests.
- The three same-named spec pairs under `rag/loader` and `rag/loader/internal`
  (`HtmlContentExtractorSpec`, `GlobPatternMatcherSpec`, `UrlNormalizerSpec`) are merged into
  one suite each, in the package of the code they test. They were two independent test sets
  per class, not duplicates - so the merge keeps every case except the three that asserted
  the same behaviour twice.
- **`ShellConfig.readOnly()` no longer allows `env`** ([#872](https://github.com/llm4s/llm4s/pull/872),
  reworked from #872 by @kallal79, whose `sh -c` fix landed as #897): the shell tool checks the program a
  command starts with, and `env` starts whatever follows it, so `env sh -c '...'` ran `sh` under a
  configuration that allows only read-only programs, and a bare `env` printed the process environment, API
  keys included. `env` is gone from the read-only list, the Scaladoc names the launchers an allowlist must not
  contain (`env`, `xargs`, `nice`, `nohup`, `timeout`), and `development()` says plainly that it is not a
  sandbox. **Behaviour change:** `ShellConfig.isCommandAllowed` takes a program name, which is what the tool
  always passed it: `isCommandAllowed("ls -la")` is now `false` (it was `true`), `isCommandAllowed("ls")` is
  unchanged. Tool behaviour is the same apart from `env`.

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
