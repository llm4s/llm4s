---
layout: page
title: Scalafix Rules
description: "The Scalafix rules LLM4S enforces to keep configuration loading at the application edge and prefer Result[A] over exceptions, and how to suppress a finding."
parent: Reference
nav_order: 4
---

# Scalafix: Configuration Boundary Enforcement

## Overview
- Purpose: Keep configuration loading at the application edge and discourage direct exception handling in favour of `Result[A]`.
- Enforcement is global; legitimate exemptions are opt-in via `// scalafix:off` markers in source files.

## Rules enforced (`.scalafix.conf`, `DisableSyntax.regex`)
- `NoConfigFactory` - bans `ConfigFactory` references.
- `NoSysEnv` - bans `sys.env` references.
- `NoSystemGetenv` - bans `System.getenv` references.
- `NoPureConfigDefault` - bans `ConfigSource.default` references.
- `NoKeywordTry` / `NoKeywordCatch` / `NoKeywordFinally` - prefer `Try(...).toResult` and `Result[A]` combinators.

## Configuration shape
- All rules live under the canonical top-level path `DisableSyntax.regex`. scalafix only reads its regex list from this exact path; custom-named subblocks like `DisableSyntax.MyRules { regex = [...] }` are silently ignored. `ScalafixConfigurationBoundarySpec` enforces this structurally.
- scalafix's `DisableSyntax` rule has no `excludePackages` or per-rule `fileFilter`. To exempt a file, use a `// scalafix:off` marker (optionally with a rule id) in the source.

## Plugin and rule type
- Plugin: `sbt-scalafix` (see `project/plugins.sbt`).
- Rule type: syntactic regex (no SemanticDB required; currently run on the Scala 3.7.1 build).

## Running Scalafix
- Compile-time enforcement: enabled in CI only (`scalafixOnCompile := sys.env.getOrElse("CI", "false").toBoolean`).
- Manual run:
  - `sbt scalafixAll` to lint every module.
  - `sbt core/scalafix` to lint just core main sources.

## Suppressing a finding
- File-wide: put `// scalafix:off` (silences all rules) or `// scalafix:off DisableSyntax.NoSysEnv` (silences one) at the top of the file, before the `package` declaration.
- Block: wrap the region with `// scalafix:off RuleId` / `// scalafix:on RuleId`.
- Name only the rules the region needs, and remove the marker when the code no longer trips them: `.scalafix.conf` sets `lint.error = [UnusedScalafixSuppression]`, so a suppression that suppresses nothing fails `scalafix`. A Scala 3 brace-less `try ... catch` does not match `NoKeywordTry`/`NoKeywordCatch` (their patterns need a `{`), so it needs no marker.
- Use sparingly; prefer fixing the violation. App-edge code (samples, workspace, the `org.llm4s.config` package) is the most common legitimate exemption.

## Migration guidance
- Load configuration at the app/test edge via `Llm4sConfig`.
- Inject typed settings into core services rather than reading env vars or `ConfigSource.default` from library code.
- Replace `try/catch` with `Try(...).toResult` and combinators on `Result[A]`.

## Migration rules

`modules/scalafix-rules` holds semantic Scalafix rules that rewrite *user* code across llm4s's breaking changes
([#2330](https://github.com/llm4s/llm4s/issues/2330)). They are first published at **1.0**, as
`org.llm4s::llm4s-scalafix`, with a one-line command in the 1.0 upgrade notes; 0.7.0 does not publish them. Until then
they exist only as source in this repository (the `scalafixRules` project); the rewrites each rule makes, listed below,
can also be applied by hand.

`Llm4sMigrate_0_5` runs every rule for the [0.4.1 to 0.5.0 guide](../migrations/0-4-1-to-0-5-0):

| Rule | Rewrites |
|---|---|
| `CopyToWithSetters` | `.copy(maxTokens = Some(500), temperature = 0.2)` on a growth-prone type to `.withMaxTokens(500).withTemperature(0.2)`; `message.copy(text = Some(t))` to `message.withContent(t)` |
| `MillisToFiniteDuration` | `CrawlerConfig(delayMs = 500, timeoutMs = 30000)` to `CrawlerConfig(delay = 500.millis, timeout = 30.seconds)` (also `UrlLoader`, `HttpConfig`, `ShellConfig` and the search tool configs), `RateLimitError("openai", 1000L)` to `RateLimitError("openai", 1.second)`, and a read of `config.timeoutMs` to `config.timeout.toMillis` |
| `IsRecoverableToTotal` | `error.isRecoverable` to `LLMError.isRecoverable(error)` |
| `MovedConfigLoaders` | `Llm4sConfig.loadBraveSearchTool()` (and the DuckDuckGo and Exa loaders) to `ToolsConfigLoader`, `Llm4sConfig.metrics()` to `MetricsConfigLoader.default()`, `Llm4sConfig.pgSearchIndex()` to `PgSearchIndexConfigLoader.default()` |
| `MessageExtractorToTypePattern` | `case UserMessage(content) =>` to `case userMessage: UserMessage =>`, reading `userMessage.content` in the guard and body (also `ToolMessage(content, toolCallId)`) |
| `RemovedApis` | Lint only: the orchestration package (`PlanRunner`, `DAG`, `TypedAgent`, `CancellationToken`), `org.llm4s.types.AgentId` and `PlanId`, and `getToolDefinitionsSafe`, each with a link to the guide section |

Where a rewrite cannot be made safely, the rule reports a lint instead: a `copy` with a positional argument, a field
that has no setter any more (`Completion.thinking`, `NamedProviderConfig.organization`), or a message extractor
nested inside another pattern. These 0.5 rules change names, not types; the `Secret` keys and the `Instant` of
`Completion.created` are `Llm4sMigrateFrom_0_5`'s.

From 0.5.x on there is one umbrella rule per starting version. Each runs every rule needed to bring code from that
version to the current release, so you run one rule, once, and no rule runs twice:

| Your code is on | Run | Runs |
|---|---|---|
| 0.5.x | `Llm4sMigrateFrom_0_5` | every rule in the table below |
| 0.6.x | `Llm4sMigrateFrom_0_6` | the rules marked "Since 0.6.0" below, and `PositionalPatternsGainFields` |
| 0.7.x | `Llm4sMigrateFrom_0_7` | added at 0.7.0: the rules for the breaks since 0.7.0 |

A rule for a later break is added to every umbrella whose starting version is older than the break. Code on 0.4.x runs
`Llm4sMigrate_0_5` first, compiles against 0.5.1, then runs `Llm4sMigrateFrom_0_5`.

`Llm4sMigrateFrom_0_5` runs every rule for the breaking changes since 0.5.1 (the notes in the
[Migration Guide](migration)), those since 0.6.0 among them, and grows as the 1.0 breaks land:

| Rule | Rewrites |
|---|---|
| `CopyToWithSetters_1_0` | `.copy(field = value)` to `.withField(value)` on the 94 types made growth-safe since 0.5.1 (`ToolExecutionConfig`, the OpenAI-compatible, Gemini, Vertex AI and Azure configs, `TraceEvent` and `AssistantError` cases, tool schemas, context types, ...); a `String` `apiKey` is wrapped in `Secret(...)`. Lints a positional `copy` and `new X(...)` |
| `CredentialsToSecret` | `OpenAIConfig(apiKey = key, ...)` and `config.withApiKey(key)` to `Secret(key)` on the provider configs (`Some(Secret(key))` where the key is an `Option`), and a read of `config.apiKey` to `config.apiKey.reveal` |
| `FromValuesKeyFirst` | `OpenAIConfig.fromValues(model, key, organization, baseUrl, ...)` to `fromValues(model, Secret(key), baseUrl, organization, ...)`, dropping a `None` organization; `AzureConfig.fromValues(deployment, endpoint, key, apiVersion)` to `fromValues(deployment, Secret(key), endpoint, apiVersion)` |
| `CompletionCreatedToInstant` | `Completion(created = seconds)`, `withCreated(seconds)` and `toCompletion(seconds)` to `Instant.ofEpochSecond(seconds)` (`0L` to `Instant.EPOCH`), and a read of `completion.created` to `completion.created.getEpochSecond` |
| `PositionalPatternsGainFields` | A positional pattern on one of the 41 types whose extractor gained fields since 0.5.1 gets a `_` for each: `case ServiceError(m, s, p, r)` to `case ServiceError(m, s, p, r, _)`, also the `TraceEvent` cases, the provider configs and `ModelCapabilities`; 0.6.0's ten-field `Completion` and eight-field `ModelCallCompleted` patterns gain `finishReason` and `servedTier`, its fourteen-field `TraceEvent.CompletionReceived` pattern `servedTier`, its `TokenUsage`, `ModelPricing`, `TurnState` and `RAGConfig` patterns their new last field, and its `CompletionOptions` patterns `promptCache` and `serviceTier` |
| `DuplicateApisRemoved` | Deletes `import ServiceError.ServiceErrorOps`; `Llm4sConfig.loadTextEmbeddingModel()` to `textEmbeddingModel()` |
| `RemovedApis_1_0` | Lint only: `org.llm4s.types.typeclass`, `CompletionChunk`/`ChunkDelta`, `OpenAIToolHelper`, `AnsiColors`, the embeddings sample's settings on `Llm4sConfig`, `APIError`, `SystemError` and the `LLMError.processingFailed` / `invalidImageInput` / `apiCallFailed` constructors, and `ErrorMapper`, `DefaultErrorMapper`, the `AsyncResult` object, `Result.combine` / `fromBoolean` / `fromBooleanWithValue` / `fromFuture`, and `TraceEvent.AgentInitialized`, each with a link to its note |
| `ThinkingReplacesBudgetTokens` | Since 0.6.0: `withReasoning(ReasoningEffort.None)` and `CompletionOptions(reasoning = Some(ReasoningEffort.None))` to `Thinking.Off`, unless the same chain sets `withReasoning` again afterwards (`Thinking.Off` takes precedence over `reasoning`). Lints `budgetTokens` and `withBudgetTokens` (`Thinking.budget(n)` is a `Result`), any other `ReasoningEffort.None`, and a `match` on `ReasoningEffort` with no catch-all, which needs `Minimal`, `XHigh` and `Max` |
| `AgentStatusGainsTruncated` | Since 0.6.0, lint only: a `match` (or partial function) on `AgentStatus` with no catch-all, which needs a case for `AgentStatus.Truncated`, the status of an answer cut off at the model's output limit ([note](migration#a-cut-off-answer-ends-truncated-not-completed-2613)) |
| `FinishReasonTyped` | Since 0.6.0: `chunk.finishReason.contains("stop")`, `== Some("length")`, `exists(_ == "tool_calls")` and `case Some("stop")` to the `FinishReason` cases; `withFinishReason("stop")` and `StreamedChunk(..., finishReason = Some("stop"))` likewise (0.6.0's raw Bedrock or Gemini value, such as `"end_turn"`, to the case it maps to now, another literal to `FinishReason.Other(...)`); any other read to `chunk.finishReason.map(_.name)`, which keeps its type. The cases assume a client that maps its reason; a Spring AI model gives `Other(raw)` for any value. Lints a reason set from a non-literal `String` or a filter's value, and, in the comparison a read is part of, a literal no rewrite gets right (and any `stop`, `tool_calls` or `length` beside it, left as a `String`): 0.6.0's raw Bedrock and Gemini values (`end_turn`, `tool_use`, `max_tokens`, `MAX_TOKENS`, ...), which now map to a case while a Spring AI model still gives `Other(raw)`; a content filter's (`content_filter`, `SAFETY`, ...), a `ContentFilteredError` now; and Gemini's `STOP`, `Stop` or `ToolCalls` |
| `CompressionRuleTransform` | Since 0.6.0: `CompressionRule`'s field `apply` to `transform`: `rule.apply(messages)` to `rule.transform(messages)`, `withApply(f)` to `withTransform(f)`, and the named argument `CompressionRule(name, apply = f)` to `transform = f` ([note](migration#compressionrules-apply-field-is-transform-1281)) |
| `AgentCopyToWithSetters` | Since 0.6.0: `.copy(field = value)` to `.withField(value)` on the 36 `llm4s-agent` types made growth-safe by [#2778](https://github.com/llm4s/llm4s/issues/2778) (`ContextWindowConfig`, `Handoff`, the `AgentEvents` payloads, `EventRecord`, `GraphSnapshot`, `Command` and others); a positional `copy` and `new X(...)` are reported |
| `ResultSyntaxMoved` | Since 0.6.0, imports only: `import org.llm4s.Result` to `org.llm4s.types.Result`; `import org.llm4s.types.TryOps` (or `OptionOps`, `FutureOps`) to `import org.llm4s.syntax.toResult`; `import org.llm4s.error.ThrowableOps._` to `import org.llm4s.syntax.toLLMError`; `import org.llm4s.syntax.syntax._` to `import org.llm4s.syntax.*`; `org.llm4s.core.safety.Safety` to `org.llm4s.error.Safety` and `NetworkSecurity` to `org.llm4s.security.NetworkSecurity`; `org.llm4s.identity.TokenizerId` to `org.llm4s.context.tokens.TokenizerId`; `org.llm4s.llmconnect.model.EmbeddingError` to `org.llm4s.error.EmbeddingError`; `org.llm4s.types.TraceId` to `org.llm4s.trace.model.TraceId` (`llm4s-observability`) |
| `ProviderConfigNewtypes` | Since 0.6.0: a provider config's named `model = "gpt-4o"` argument and `config.withModel("gpt-4o")` to `ModelName("gpt-4o")`; `Llm4sConfig.provider("main")` and `listModels("main")` (and `Llm4sSettings`) to `ProviderName("main")`; adds the imports. A model passed by position is left for the compiler to report ([#2805](https://github.com/llm4s/llm4s/issues/2805), [#2804](https://github.com/llm4s/llm4s/issues/2804)) |
| `ToolFunctionOneTypeParameter` | Since 0.6.0: drops the arguments type parameter of `ToolFunction` and `ToolBuilder`: `ToolBuilder[Map[String, Any], String](...)` to `ToolBuilder[String](...)`, `ToolFunction[A, R]` to `ToolFunction[R]`, `Seq[ToolFunction[_, _]]` to `Seq[ToolFunction[_]]`. A type parameter of your own that only named the arguments is left declared ([note](migration#a-tool-handler-has-one-error-type-2815)) |
| `SamplingOptionsOptional` | Since 0.6.0: `CompletionOptions(temperature = 0.2, topP = 0.9)` to `CompletionOptions(temperature = Some(0.2), topP = Some(0.9))`, and the two penalties likewise ([#2807](https://github.com/llm4s/llm4s/issues/2807)). The setters still take the value; a read of `options.temperature`, now an `Option`, is left to you |
| `CompletionFromMessage` | Since 0.6.0: drops the `content` and `toolCalls` arguments of `Completion(...)`, named or positional, which are now read from its `message` (check that the message carries them); `completion.asText` to `completion.content`; `withEstimatedCost(cost)` to `withEstimatedCost(BigDecimal.decimal(cost))`; a positional `case Completion(...)` loses its third and sixth patterns. Lint: `completion.withContent`/`withToolCalls`, and a pattern that binds `content` or `toolCalls` |
| `MessageAndConversationShapes` | Since 0.6.0: `message.contentOpt` and `AssistantMessage(contentOpt = ...)` to `text`; `AssistantMessage()` to `AssistantMessage(None)` and `AssistantMessage(toolCalls = calls)` to `AssistantMessage(text = None, toolCalls = calls)`; `Conversation.empty()` to `Conversation.empty`; `Conversation.create(a, b)` to `Conversation.create(Seq(a, b))`; `ProviderExchangeLogging.enabled(sink)` to `ProviderExchangeLogging.Enabled(sink)` ([#2809](https://github.com/llm4s/llm4s/issues/2809)) |
| `ClientEntryPoints` | Since 0.6.0: `LLMConnect.fromConfig(...)` and `fromProvider(...)` to `getClient(...)`; `getClient(config, metrics)` to `getClient(config, LlmClientOptions(metrics = metrics))`; `getClient(provider, config)` to `getClient(provider, config, LlmClientOptions.default)`; `client.streamComplete(conversation, options, onChunk)` to `client.streamComplete(conversation, options)(onChunk)`, and an overriding `def streamComplete` to the same two parameter lists ([#2810](https://github.com/llm4s/llm4s/issues/2810)) |

A key passed by position (`OpenAIConfig("sk-...", "gpt-4o")`), and the other credentials that became `Secret`s
(Langfuse, search-tool and store keys), still need a hand edit. The breaks that change behaviour or add cases have no
rule: see [Changes since 0.5.1 that no rule automates](migration#changes-since-051-that-no-rule-automates).

Run the umbrella for the version your code compiles against, not an older one. `CredentialsToSecret`,
`CompletionCreatedToInstant` and `FromValuesKeyFirst` cannot tell 0.6.x code, where the key is already a `Secret` and
`created` an `Instant`, from 0.5.x code (Scalafix sees no types from Scala 3 dependencies), and would rewrite it again:
`config.apiKey.reveal` to `config.apiKey.reveal.reveal`. `Llm4sMigrateFrom_0_6` leaves those three out:
`ThinkingReplacesBudgetTokens`, `FinishReasonTyped`, `CompressionRuleTransform`, `AgentCopyToWithSetters`,
`ResultSyntaxMoved`, `ProviderConfigNewtypes`, `ToolFunctionOneTypeParameter`, `AgentStatusGainsTruncated`,
`PositionalPatternsGainFields`, `SamplingOptionsOptional`, `CompletionFromMessage`,
`MessageAndConversationShapes` and `ClientEntryPoints`.

The rules are compiled with scalac 2.13, the only compiler `scalafix-core` is published for; they read the SemanticDB of Scala 3 sources. Scalafix's
Scala 3 support resolves the symbol at each name, which every rule here relies on, but has no synthetics or inferred
types, so the rules match on the symbols the source names. Scala 3's SemanticDB also records no symbol for some
calls, such as the `copy` in `CompletionOptions().copy(maxTokens = Some(500))`; the rules then read the method from a
named argument's symbol (`CompletionOptions#copy().(maxTokens)`), so a positional `copy` on such a receiver is
neither rewritten nor reported.

Each rule is tested by `scalafixTests` (`sbt scalafixTests/test`): the files in `modules/scalafix-rules/input` are
compiled with SemanticDB against llm4s-core 0.4.1 from Maven Central, each rule named in a file's header is run on
it, and the result must equal the file of the same name in `modules/scalafix-rules/output`, which is compiled against
the current sources. `scalafixTests05` does the same for the `Llm4sMigrateFrom_0_5` rules, with
`modules/scalafix-rules/input-0.5` compiled against the 0.5.1 artifacts and its expected output under
`output/.../fix/v1`, and `scalafixTests06` for `Llm4sMigrateFrom_0_6` and the rules for the breaks since 0.6.0, with `modules/scalafix-rules/input-0.6`
compiled against the 0.6.0 artifacts and its expected output under `output/.../fix/v06`
(`Llm4sMigrateFrom_0_6KeepsSecretsTest` pins that it leaves code already past the 0.5.1 breaks alone).

## Notes
- CI and pre-commit run scalafix checks.
- If you hit a false positive (e.g. a doc comment containing a banned token), prefer a `// scalafix:off DisableSyntax.<RuleId>` over loosening the regex.
