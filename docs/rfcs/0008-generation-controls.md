---
layout: page
title: "RFC 0008: Generation controls and reported adjustments"
parent: RFCs
grand_parent: Reference
nav_order: 8
---

# RFC 0008: Generation controls and reported adjustments

- **Status:** Accepted
- **Author(s):** @jwgcooke
- **Tracking issue:** [#2115](https://github.com/llm4s/llm4s/issues/2115)
- **Pull request:** [#2445](https://github.com/llm4s/llm4s/pull/2445)

## Summary

The design has three **parts**, and they ship as six **PRs** (see [Delivery](#delivery)). Parts are numbered 1–3
and PRs 1–6 throughout, so the two never share a number.

- **Part 1: Reported adjustments.** Today, when a provider or model can't honour a request option, it is dropped and
  logged only at DEBUG. This part reports each such drop to the caller instead. `RequestTransformer`'s existing
  violations become a public value, `RequestAdjustment`. Every `Completion`, streamed or not, carries them, and so
  does an agent's `ModelCallCompleted` event. Additive, apart from one provider-SPI change (decision 13).
- **Part 2: Thinking, separated from effort.** `CompletionOptions` gains `thinking: Option[Thinking]` (`Off`,
  `Adaptive`, `Budget(n)`). `ReasoningEffort` keeps its name and its meaning (how hard to think) and gains the levels
  providers now accept. Two members that blur the two ideas go, as a clean break before the MiMa baseline:
  `budgetTokens` (now `Thinking.Budget(n)`) and `ReasoningEffort.None` (now `Thinking.Off`).
- **Part 3: Provider-side prompt caching.** `CompletionOptions` gains `promptCache: Option[PromptCache]`, mapped by
  each provider that supports it and reported as an adjustment by each that doesn't. Purely additive.

Service tier, and making `temperature`/`topP` optional, are out of scope (see [Not changed](#not-changed)). The
issue's `GenerationControls` wrapper, `ControlMapper` trait, `UnsupportedPolicy` and `ControlOutcome` are not
adopted; this RFC reuses existing infrastructure instead, as the issue discussion asked.

## Motivation

From #2115 and its discussion:

- **Options are dropped silently.** `DefaultRequestTransformer` drops an unsupported `temperature`, `top_p`,
  penalty, `tools` or `responseFormat` with `logger.debug`, and every provider calls it with
  `dropUnsupported = true`. Beyond the transformer, `CompletionOptions.reasoning` is not read at all by the Gemini,
  Vertex AI, Ollama and Bedrock clients, nor by the standard `OpenAICompatibleDialect.addReasoning` (a no-op). A
  caller running a multi-provider workload has no way to learn which providers ignored a setting.
- **Effort and thinking share one type.** `ReasoningEffort` means `reasoning_effort` on OpenAI, a thinking-token
  budget on Anthropic (`effectiveBudgetTokens`), and a per-model choice of `thinking.type` or `reasoning_effort` on
  Z.ai (#1691). `ReasoningEffort.None` means "don't think" on some providers and "lowest effort" on others. On
  Anthropic, llm4s can send only `budget_tokens`. It can't send `thinking: adaptive`, `thinking: disabled` or
  `output_config.effort`, and some current models reject `budget_tokens`.
- **No provider-side prompt caching.** A large repeated system prompt is billed at full input price on every
  Anthropic call. This is different from `CachingLLMClient`, which caches whole responses on the client side. With
  #2348 and #2428 merged, every provider that reports cache counts fills `TokenUsage.cachedTokens` and
  `cacheCreationTokens`, so the effect of caching can be measured. Nothing can request it.

**The size of the caching lever.** At Anthropic's published rates, a 5-minute cache write costs 1.25× the base
input price and a cache read 0.1×. The issue's workload is a 16-way fan-out sharing one system prompt of _P_ tokens:

| | Input cost of the shared prompt |
|---|---|
| Today, no caching | 16 × _P_ = **16 _P_** |
| Caching on, all 16 calls sent at once (cold) | 16 × 1.25 _P_ = **20 _P_**, worse than no caching |
| Caching on, one call warms the cache, then 15 in parallel | 1.25 _P_ + 15 × 0.1 _P_ = **2.75 _P_** |

**The cache must be warmed before a fan-out.** A cache entry exists only once the first response has started, so
16 calls sent together all miss and each pays the write premium. A caller that fans out sends one call first and
starts the rest when it returns. That costs one call's latency, and is about 83% cheaper on the shared prefix than
no caching. The saving grows with every agent-loop turn that reuses the prefix. This is the caller's job, not
llm4s's: `PromptCache` asks for a breakpoint and does not schedule calls. `docs/guide/cost-controls.md` shows the
warm-up pattern.

**Below a minimum length, nothing is cached.** Anthropic caches a prefix only when it is at least the model's
minimum cacheable length (1024 or 2048 tokens, depending on the model). Below it, `cache_control` is ignored
without an error, so a short prompt never shows a cache hit. Part 3 reports this case (see
[Part 3](#part-3-provider-side-prompt-caching)).

Once PR 5 lands, the `cachedTokens` and `cacheCreationTokens` counts show whether a deployment gets the saving.

If nothing changes, the issue author's workload, and anyone else's that needs these controls, has to bypass llm4s
for Anthropic, and multi-provider callers keep losing settings without notice.

## Detailed design

### Part 1: Reported adjustments

A public, `@Stable` value describing what happened to one requested option. The option is typed, so a
caller's `match` on it is checked by the compiler and survives a field rename:

```scala
package org.llm4s.llmconnect.model

@Stable
enum RequestOption derives ReadWriter {
  case Temperature, TopP, MaxTokens, PresencePenalty, FrequencyPenalty, Tools, ResponseFormat,
       Reasoning, Thinking, PromptCache
}

@Stable
enum RequestAdjustment derives ReadWriter {
  /** The option was not sent: the provider or model cannot honour it. */
  case Dropped(option: RequestOption, reason: String)

  /** The option was sent with a different value, e.g. temperature clamped, effort Max sent as High. */
  case Adjusted(option: RequestOption, requested: String, sent: String, reason: String)

  /** The option was not sent because the provider already does it, e.g. OpenAI's automatic prompt caching. */
  case NotNeeded(option: RequestOption, reason: String)

  def option: RequestOption
}
```

`RequestOption` names each `CompletionOptions` field that can be adjusted. A field added later adds a case. Both
types are `@Stable` because `Completion` and `TransformationResult`, which are `@Stable`, expose them, and a `@Stable`
signature may not name an `@Experimental` type (#1818; decision 12). `reason`, `requested` and `sent` stay strings:
they are for people and logs, not for matching.

**Where they come from.** There are two sources, both pure, and the result is one list:

- **`RequestTransformer`.** The private `Violation` case class already holds `drop` and a description. It gains the
  `RequestAdjustment` it represents. `TransformationResult`, a growth-prone `@Stable` type, gains a field under the
  RFC 0002 pattern:

  ```scala
  final case class TransformationResult private (
    options: CompletionOptions,
    messages: Seq[Message],
    requiresFakeStreaming: Boolean,
    adjustments: List[RequestAdjustment]         // new; List.empty in the existing apply overload
  )
  def withAdjustments(adjustments: List[RequestAdjustment]): TransformationResult
  ```

  `transformOptions(..., dropUnsupported = false)` keeps returning `Left(ValidationError)`. `dropUnsupported = true`
  now records what it dropped instead of only logging it.
- **The provider's own request mapping.** A provider that receives an option it doesn't map (Gemini given
  `reasoning` today, Ollama given `promptCache` later) records a `Dropped` when it builds the request. The vendor
  logic stays in the vendor's module, and no shared mapper trait is introduced. For the OpenAI-compatible family,
  the dialect hooks that already exist (`addReasoning`, and an `addPromptCache` added in Part 3) return what they
  could not apply, with a default that reports `Dropped`. This follows the "a hook with a default" rule.

**Where they go.**

- **`Completion`**, a growth-prone `@Stable` type, gains `adjustments: List[RequestAdjustment]`, empty by default,
  with `withAdjustments`. `List` matches `Completion`'s other collections (`toolCalls`, `citations`) and
  `TransformationResult.adjustments`. Streaming is covered because a streamed completion is built by the same
  client, from the same `TransformationResult`.
- **Agent runs.** `Agent.run` does not return `Completion`s, so an agent caller would otherwise never see an
  adjustment. `AgentEvents.ModelCallCompleted` gains `adjustments: List[RequestAdjustment] = List.empty`, filled from
  the call's `Completion`. The payload derives `ReadWriter`, so `RequestOption` and `RequestAdjustment` derive it too,
  and a stored event written before the field existed still reads, with an empty list. This lands in PR 2, straight
  after PR 1. `AgentResult` gets no summary for now: a caller that wants one folds the events.

**Logging: warn once, per client, bounded.** The client logs each adjustment at WARN the first time it sees a
(provider, model, option) triple, so the shared mapping functions never log. The record of what has been logged
lives in each client instance, not in a global, so it is dropped with the client and two clients never share
state. It is a thread-safe set (`ConcurrentHashMap.newKeySet`) capped at 256 entries. When it is full, the client
logs one WARN saying so and logs further first sightings at DEBUG. The cap bounds a long-running process that sees
many model ids. Adjustments are still recorded on every `Completion`, whatever the log state.

**No new policy type.** The default is unchanged: send what can be sent, and now say what wasn't. A caller that
must not run with a dropped option checks `completion.adjustments` and treats a `Dropped` as a failure. A
refuse-before-send option (the issue's `Strict`) is left out until a caller needs one (decision 2 below).

### Part 2: Thinking, separated from effort

```scala
@Stable   // decision 14
sealed trait Thinking derives ReadWriter
object Thinking {
  case object Off      extends Thinking      // Anthropic thinking: disabled; Z.ai thinking.type: disabled; Gemini thinkingBudget 0
  case object Adaptive extends Thinking      // Anthropic thinking: adaptive; Gemini thinkingBudget -1
  final case class Budget private (tokens: Int) extends Thinking   // Anthropic budget_tokens; Gemini thinkingBudget n

  /** A thinking budget. Fails for `tokens <= 0`: `Off` and `Adaptive` already say what 0 and -1 mean. */
  def budget(tokens: Int): Result[Thinking]
}
```

**Budgets are validated where they are made.** Zero and negative values mean different things to different
providers (on Gemini, `0` turns thinking off and `-1` makes it dynamic), so `Thinking.budget` rejects `tokens <= 0`
with a `ValidationError`, and `Budget`'s constructor is private. A positive budget a provider can't take as given is
fitted to the provider's range and reported as `Adjusted`, never sent as is. For example, Anthropic requires
`1024 <= budget_tokens < max_tokens`, so a budget of 500 is sent as 1024, with
`Adjusted(Thinking, "Budget(500)", "Budget(1024)", "Anthropic minimum is 1024")`.

**Added.** `CompletionOptions` gains `thinking: Option[Thinking]`, default `None` (provider default), with
`withThinking(Thinking)` and `withThinking(Option[Thinking])`. `ReasoningEffort` keeps its name and meaning, *how
hard to think*, and gains `Minimal`, `XHigh` and `Max`.

**Removed**, as a clean break with a CHANGELOG line and a migration note, and no deprecation or shim:

- **`budgetTokens`** (field, `withBudgetTokens(Int)`, `withBudgetTokens(Option[Int])`). A budget already means
  "think, with this budget" wherever it is read (Anthropic, and OpenRouter for Anthropic models), which is exactly
  `Thinking.Budget(n)`.
- **`ReasoningEffort.None`.** It means "don't think" on some providers and "lowest effort" on others.
  `Thinking.Off` says what it means. `ReasoningEffort.fromString("none")` returns `None`. The `ReasoningEffort`
  reader rejects `"none"` with a message that names the replacement:
  `"none" is no longer a ReasoningEffort; use Thinking.Off`, so anyone with a stored or hand-written value knows
  what to change.

`effectiveBudgetTokens` and `hasReasoning` keep their signatures, redefined over the new fields: the budget is
`thinking`'s `Budget(n)` if set, else the default budget of `reasoning` as today; reasoning is on when `reasoning` is
set or `thinking` is set and not `Off`.

**One precedence rule**, on `withThinking`'s Scaladoc: an explicit `thinking` wins. Without one, `reasoning` decides
as it does today, so a caller that sets only `reasoning` sends the same request as before, with one exception,
below. A contradictory pair, such as `thinking = Off` with `reasoning = High`, is resolved by the rule and reported
as an `Adjusted` on `Reasoning`.

**The exception: a model that rejects a manual budget.** Some current Anthropic models reject `budget_tokens`, so a
`reasoning`-only request sent to them today fails with a 400. For a model whose capabilities say it does not take
a manual budget (`supportsBudgetThinking = Some(false)`, below), `reasoning` alone maps to `output_config.effort`
instead, reported as `Adjusted(Reasoning, "budget_tokens", "output_config.effort", "model does not accept a thinking
budget")`. This changes no request that works today, because every request it changes is one that fails today.
Without it, the precedence rule would keep sending a field the model refuses.

Mapping, each provider in its own module. Anything not listed is reported as `Dropped`:

| | `reasoning` (effort) | `thinking` |
|---|---|---|
| Anthropic | `output_config.effort` where the model supports it, else a `budget_tokens` budget, as today (`Adjusted`); on a model that rejects a budget, always `output_config.effort` (see the exception above) | `Off` → `disabled`, `Adaptive` → `adaptive`, `Budget(n)` → `budget_tokens: n`, fitted to `[1024, max_tokens)` (`Adjusted` when fitted) |
| OpenAI / Azure | `reasoning_effort`; an unsupported level `Adjusted` to the nearest supported one | `Off` → the lowest supported effort (`Adjusted` unless it is `none`); `Budget` `Dropped` |
| Z.ai | as #1691 | `Off` → `thinking.type: disabled` (or `low` on GLM-5.3, `Adjusted`) |
| OpenRouter | as today | Anthropic models: `Budget(n)` → `thinking.budget_tokens: n`, as today; `Off` → no thinking block. Others `Dropped` |
| Gemini / Vertex AI | `thinking_level` where supported | `thinkingBudget`: `Off` → 0, `Adaptive` → -1, `Budget(n)` → n, fitted to the model's range (`Adjusted` when fitted) |
| Bedrock, Ollama, watsonx, others | `Dropped` until mapped | `Dropped` until mapped (Ollama `think` in a follow-up) |

Apart from the exception above, Anthropic's `output_config.effort` applies only when a caller sets one of the new
levels or sets `thinking` explicitly. A caller using `reasoning` alone on a model that takes a budget keeps today's
`budget_tokens` request, so the new mapping can't change an existing working call's cost.

Which levels a model accepts comes from `ModelCapabilities`, which starts reading metadata the bundled
`litellm_model_metadata.json` already carries but `fromJson` ignores. These are added as new fields under the
growth pattern:

```scala
supportsAdaptiveThinking: Option[Boolean]                 // supports_adaptive_thinking
supportsOutputConfig: Option[Boolean]                     // supports_output_config
supportedReasoningEfforts: Option[Set[ReasoningEffort]]   // supports_{minimal,low,...,xhigh,max}_reasoning_effort
supportsThinkingOff: Option[Boolean]                      // supports_none_reasoning_effort
supportsBudgetThinking: Option[Boolean]                   // no litellm key; set in llm4s_model_overrides.json
```

The litellm metadata has no key for "rejects a manual budget", so `supportsBudgetThinking` comes only from
`llm4s_model_overrides.json`, and `None` (unknown) keeps today's behaviour. It is set there by patch entries
(`"llm4s_patch": true`), which add the key and keep the rest of the litellm entry, so the flag does not freeze the
entry's prices against a refresh. Where the litellm metadata is missing or
wrong, the overrides file corrects it, and a provider's `RequestTransformer.adjusted` adds rules that follow from
its model naming. No hard-coded model checks in core.

**`ThinkingReplay`.** The replay fingerprint today covers the system messages, tools, `responseFormat` and the
messages before the assistant turn. `thinking` joins it if a change of `thinking` makes a replayed signed block
invalid for the provider (decision 4 below). PR 4 settled this against Anthropic's documentation, which Bedrock
shares: it does not, so `thinking` stays out of the fingerprint, as recorded in `ThinkingReplay`'s Scaladoc. Adding `thinking` when it isn't needed would unseal blocks that could
have been replayed.

### Part 3: Provider-side prompt caching

Purely additive.

```scala
@Stable   // decision 16
final case class PromptCache private (scope: Set[PromptCache.Scope], ttl: PromptCache.Ttl)
object PromptCache {
  enum Scope { case System, Tools, Conversation }
  enum Ttl   { case Short, Long }                 // Anthropic: 5 minutes, 1 hour
  val default: PromptCache                        // System + Tools, Short
  def apply(scope: Set[Scope], ttl: Ttl): PromptCache
}
```

- `CompletionOptions` gains `promptCache: Option[PromptCache]`, default `None`, with `withPromptCache`. With `None`,
  no provider's request changes.
- **Anthropic** puts `cache_control` breakpoints on the last system block, the last tool and, for `Conversation`, the
  last message, with the TTL.
- **OpenAI**, **Gemini** and **Vertex AI** cache automatically, so they report `NotNeeded`.
- **Bedrock** (`cachePoint`) follows in its own PR. Until then it, like every other provider, reports `Dropped`.
- **A prefix too short to cache is reported.** Anthropic ignores `cache_control` below the model's minimum cacheable
  length without an error. When a request asked for `promptCache` and the response's `cachedTokens` and
  `cacheCreationTokens` are both zero, the client adds
  `Dropped(PromptCache, "nothing was cached: the prefix may be below the model's minimum cacheable length")` to
  the `Completion`. The check runs after the response, from counts every provider now fills, so it needs no
  tokenizer and no per-model table.
- The name is `promptCache`, never `cache`, and its Scaladoc contrasts it with `CachingLLMClient`. It also says that
  a fan-out must warm the cache with one call first (see [Motivation](#motivation)).
- `CostEstimator` gains the 1-hour cache-write price.

**`CachingLLMClient`'s key comes first.** Today an entry matches when its `CompletionOptions` is equal to the
request's (`entry.options == options`), so every new field joins the key implicitly. Before `promptCache` lands, the
key becomes an explicit calculation that lists the fields that change the answer: `reasoning` and `thinking` are in
it, `promptCache` is not, because provider-side caching does not change the response (decision 3 below).

### Not changed

`temperature`, `topP` and the penalties stay as they are, so llm4s keeps sending `temperature` by default and no
governance vote is needed. Service tier is left for later. Both can be added under this RFC's pattern, one field and
one `RequestOption` case each, without revisiting it. `TraceEvent` does not carry adjustments yet (decision 5
below).

## Alternatives

- **The issue's original design: a `GenerationControls` wrapper with a `ControlMapper[Req]` trait,
  `UnsupportedPolicy` and `ControlOutcome`.** Not adopted. It adds a second reporting path next to
  `RequestTransformer`, a shared mapper that pulls vendor logic out of the vendor modules, and a policy type that
  duplicates `dropUnsupported`. Making sampling optional also requires a governance vote.
- **Rename `ReasoningEffort` to `Effort`.** A clearer name, but it breaks every caller and both the Java and Kotlin
  facades for no change in meaning.
- **Keep `budgetTokens` and `ReasoningEffort.None`, each redefined over `thinking`.** This avoids a source break, but
  leaves two ways to state each thing, and `None` keeps its two readings in existing code and documentation. Before
  the MiMa baseline a bad API is fixed outright, so the removals are preferred.
- **`RequestAdjustment.option` as the field name in a `String`.** Simpler, but callers match on it, and a string
  drifts silently when a field is renamed. The `RequestOption` enum keeps the match checked.
- **A global warn-once set.** Simpler, but it grows without bound in a long-running process with many model ids,
  and it couples unrelated clients. Per-client and capped is preferred.
- **Report adjustments only in logs or tracing.** It doesn't reach a caller at run time. `Completion.adjustments` is
  the caller's channel. A trace event can read the same list later.
- **Do nothing.** Prompt caching on Anthropic stays impossible, and silent drops continue.

## Compatibility impact

- **Modules and tiers:** `llm4s-core` (Frozen at 1.0): `CompletionOptions`, `ReasoningEffort`, `Completion`,
  `TransformationResult` and `ModelCapabilities` change. `RequestOption` and `RequestAdjustment` are new and
  `@Stable` (decision 12), and so are `Thinking` (decision 14) and `PromptCache` (decision 16). `TokenUsage` gains
  `longCacheCreationTokens` and `ModelPricing` `cacheCreationInputTokenCostAbove1hr`, defaulted (PR 6). `llm4s-agent`: `AgentEvents.ModelCallCompleted` gains a defaulted field.
  Provider modules change only internally, except `llm4s-openai-compatible`: `OpenAICompatibleDialect.addReasoning`
  returns the `List[RequestAdjustment]` it could not send instead of `Unit` (decision 13). `llm4s-java-api` and `kotlin-api` follow core: `JReasoningEffort` loses
  `NONE` and gains `MINIMAL`, `XHIGH` and `MAX`, `JCompletionOptions` loses `budgetTokens` and gains `thinking`, and
  the Kotlin `CompletionOptionsDsl` swaps its `budgetTokens` property for `thinking`.
- **Binary:** there is no baseline yet (#1281), so no MiMa filter is needed.
- **Source:** Parts 1 and 3 are additive, apart from the usual exception for any growth-pattern addition: a
  positional pattern match on a case-class extractor whose arity changes (`TransformationResult`, `Completion`,
  `ModelCallCompleted`). Part 2 breaks code that uses `budgetTokens`, `withBudgetTokens` or `ReasoningEffort.None`
  (PR 4), and an exhaustive `match` on `ReasoningEffort` warns about the three new levels. Part 1 also breaks an
  `OpenAICompatibleDialect` that overrides `addReasoning` (PR 1, decision 13).
- **Behaviour:** a call that compiles after PR 4 sends the same request as before, except a `reasoning`-only call to
  a model that rejects a thinking budget, which fails today and now succeeds with `output_config.effort`. The new
  parts come into play only through the new setters. Two visible additions from PR 1:
  - a dropped option now produces a WARN, once per client, provider, model and option;
  - a dropped option now produces an entry in `Completion.adjustments`.
- **Wire formats:** no checkpoint or config key changes. A `ModelCallCompleted` written before PR 2 reads with
  `adjustments = List.empty`. The `ReasoningEffort` JSON reader no longer accepts `"none"` (with a message naming
  `Thinking.Off`), and accepts `"minimal"`, `"xhigh"` and `"max"`.

## Migration

Part 3 needs none. Part 1 needs one change, and only for an `OpenAICompatibleDialect` that overrides
`addReasoning`: it returns what it did not send (PR 1, decision 13; `docs/reference/migration.md` has the
before and after). Part 2 (PR 4):

```scala
// Before
CompletionOptions().withBudgetTokens(8000)
CompletionOptions().withReasoning(ReasoningEffort.None)

// After
Thinking.budget(8000).map(CompletionOptions().withThinking)   // Result[CompletionOptions]
CompletionOptions().withThinking(Thinking.Off)
```

```java
// Java, before / after
JCompletionOptions.builder().reasoning(JReasoningEffort.NONE).budgetTokens(8000)
JCompletionOptions.builder().thinking(JThinking.budget(8000))   // throws IllegalArgumentException for <= 0
```

One behaviour to note: on OpenAI, `ReasoningEffort.None` sent no `reasoning_effort` (the model's default effort),
while `Thinking.Off` asks for the lowest effort the model accepts. A caller who wants the provider default sets
neither.

The CHANGELOG gets **Added** entries for each PR and, for PR 1 and PR 4, one line each under
`### Breaking changes`. `docs/reference/migration.md` gets a section for each of those two, and `docs/guide/cost-controls.md` documents the mapping
table, the precedence rule and its exception, and the cache warm-up pattern.

New controls, for reference:

```scala
CompletionOptions().withThinking(Thinking.Adaptive).withReasoning(ReasoningEffort.Low)
CompletionOptions().withPromptCache(PromptCache.default)
completion.adjustments                                 // what was dropped, adjusted or not needed
```

## Delivery

Each PR has its own review:

1. **PR 1 (Part 1).** `RequestOption` and `RequestAdjustment` on `TransformationResult` and `Completion`, the
   transformer's existing drops reported, a `reasoning` that Gemini, Vertex AI, Bedrock, Ollama and the standard
   OpenAI-compatible dialect do not send reported as `Dropped` (decision 13), the sampling options OpenAI leaves out of a
   reasoning model's request reported when set away from their defaults, and per-client, capped warn-once
   logging. No new options.
2. **PR 2 (Part 1).** `AgentEvents.ModelCallCompleted.adjustments`, filled from each call's `Completion`.
3. **PR 3 (Part 2).** `ModelCapabilities` reads the metadata keys above, plus `supportsBudgetThinking` from the
   overrides file. `supportedReasoningEfforts` holds the levels `ReasoningEffort` has at that point (`low`, `medium`,
   `high`, which litellm implies for a reasoning model unless a `supports_<level>_reasoning_effort` key says `false`);
   PR 4 reads the `minimal`, `xhigh` and `max` keys when it adds those levels.
4. **PR 4 (Part 2).** `Thinking` with `Thinking.budget` validation and per-provider fitting, the new
   `ReasoningEffort` levels, the budget-rejecting-model exception, and the removal of `budgetTokens` and
   `ReasoningEffort.None`, for Anthropic and OpenAI, with the `ThinkingReplay` check. Other providers report `Dropped`
   until each maps them in a follow-up. The Java facade changes land here, with tests that keep `llm4s-java-api` at
   its 100% coverage floor:
   - `JThinking.off()`, `JThinking.adaptive()`, `JThinking.budget(n)` for a positive `n`, and
     `JThinking.budget(0)` and `JThinking.budget(-1)` throwing `IllegalArgumentException`;
   - `JReasoningEffort.MINIMAL`, `XHIGH` and `MAX`, each round-tripped to `ReasoningEffort`;
   - `JCompletionOptions.builder().thinking(...)` carried onto `CompletionOptions.thinking`;
   - the Kotlin `CompletionOptionsDsl` `thinking` property.
5. **PR 5 (Part 3).** `CachingLLMClient`'s explicit cache key.
6. **PR 6 (Part 3).** `PromptCache`, for Anthropic, the too-short-to-cache report, and `NotNeeded` for OpenAI and
   Gemini.

Follow-ups, one provider or one control per PR, under this RFC: Bedrock `cachePoint`, Ollama `think`, Gemini
thinking levels, and service tier.

The docs (`docs/guide/cost-controls.md` with the mapping table, precedence rule and warm-up pattern, rows in
`providers.md`, and the adjustment contract in `writing-a-provider.md`) land with PRs 1, 4 and 6.

## Decisions

The issue discussion settled the questions an earlier draft left open
([comment](https://github.com/llm4s/llm4s/issues/2115#issuecomment-6517359913)):

1. **`ReasoningEffort.None` is removed**, not redefined as "lowest effort". `Thinking.Off` says what it means.
   `budgetTokens` goes with it.
2. **No refuse-before-send option for now.** Reported adjustments come first; a `Strict` flag or an up-front
   capability query can follow once a caller needs one.
3. **`CachingLLMClient`'s key becomes an explicit calculation** before `promptCache` lands (PR 5).
4. **`thinking` joins the `ThinkingReplay` fingerprint** if a change of `thinking` makes a replayed thinking block
   invalid for the provider. PR 4 checked: it does not. Anthropic's documentation, which covers Bedrock too, says a
   change of thinking configuration between turns is allowed and one within a turn "doesn't error" but silently
   disables thinking for that request; a change invalidates only the prompt cache. So `thinking` stays out.
5. **`TraceEvent` gets adjustments when a backend reads them**, not now.

The review of this PR settled these:

6. **`RequestAdjustment.option` is a `RequestOption` enum**, not a `String`.
7. **`List` throughout**, for `TransformationResult.adjustments` and `Completion.adjustments`.
8. **Agent runs see adjustments on `ModelCallCompleted`** (PR 2). `AgentResult` gets no summary yet.
9. **`Thinking.budget` rejects `tokens <= 0`**, and a positive budget a provider can't take is fitted and reported
   as `Adjusted`.
10. **Warn-once state is per client, thread-safe and capped at 256 entries.**
11. **A `reasoning`-only request to a model that rejects a thinking budget maps to `output_config.effort`**, driven
    by `supportsBudgetThinking` in the overrides file.

PR 1 settled these:

12. **`RequestOption` and `RequestAdjustment` are `@Stable`**, not `@Experimental` as first written. `Completion`
    and `TransformationResult` are `@Stable` and expose them, and `stabilityTierCheck` (#1818) rejects a `@Stable`
    signature that names an `@Experimental` type unless the member itself is marked `@Experimental`. Marking
    `adjustments` and `withAdjustments` that way was the alternative; the maintainers chose promotion in review,
    since the design is settled by this RFC.
13. **PR 1 also reports the provider-side drops that #2115's three-PR plan put in its first step**: a `reasoning`
    ignored by Gemini, Vertex AI, Bedrock, Ollama and the standard OpenAI-compatible dialect. The OpenAI client reports the
    sampling options (`temperature`, `top_p`, the penalties) a reasoning model's request leaves out, replacing the
    transformer's report for the same option, since nothing is sent for it. Each is reported only when set away from
    its `CompletionOptions` default, so a default call reports no `Dropped` that a caller treating one as a failure
    would trip on; an explicit value equal to the default goes unreported, since it cannot be told apart. `addReasoning` returns
    what it could not send, as "Where they come from" describes, which for xAI includes the penalties it strips from
    a reasoning model's request. The dialects that map reasoning themselves (Z.ai,
    xAI, OpenRouter) report nothing new yet; a level one of them leaves unsent is a follow-up under this RFC.

PR 4 settled these:

14. **`Thinking` is `@Stable`**, not `@Experimental`, for decision 12's reason: the `@Stable` `CompletionOptions`
    exposes it as a field. `stabilityTierCheck` reads a case class's header as one member, so the field alone cannot
    be marked `@Experimental`; leaving it out of `CompletionOptions(...)` and marking only the setters was the
    alternative. `PromptCache` (PR 6) will meet the same choice.
15. **A provider that sent something for `ReasoningEffort.None` sends the same for `Thinking.Off`**, so a call migrated
    from one to the other sends the request it sent before (the compatibility promise above): Z.ai `thinking.type:
    disabled` or `reasoning_effort: none` by model, xAI and watsonx `low`, Perplexity `minimal`. Where that still thinks
    it is reported `Adjusted`, replacing the per-process warnings those clients logged. OpenAI differs, as the
    migration says: `Off` asks for the lowest effort. Perplexity and watsonx report no adjustments yet, so theirs are a
    follow-up. Claude Fable 5 rejects `thinking: disabled`, which its metadata does not say, so its patch entries carry
    `supports_none_reasoning_effort: false` beside PR 3's `supports_budget_thinking: false`.

PR 6 settled these:

16. **`PromptCache` is `@Stable`**, for decision 14's reason: `CompletionOptions` exposes it as a field.
17. **`Conversation` is the request's top-level `cache_control`**, not a breakpoint written onto the last message:
    Anthropic places it on the last block it can cache, walking back past a block that cannot be (a thinking block),
    which a client-side choice would have to repeat. With `System` and `Tools` that is three of the four breakpoints a
    request may have, all with one TTL, so the rule that a longer TTL comes first never applies. Anthropic documents
    that `cache_control` markers are not part of the prompt a thinking signature is checked against, so they stay out
    of the `ThinkingReplay` fingerprint (decision 4). Bedrock's legacy runtime rejects the top-level field; Bedrock
    maps `promptCache` in its own follow-up.
18. **The 1-hour write price needs the 1-hour write count.** `CostEstimator` gains the price as the RFC says, and
    `TokenUsage` gains `longCacheCreationTokens` (Anthropic's `cache_creation.ephemeral_1h_input_tokens`), the writes
    to price at it; a usage that does not break them out is priced as before.

## Open questions

None. Review comments on this PR may reopen any of the above.
