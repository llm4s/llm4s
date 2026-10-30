---
layout: page
title: "RFC 0008: Generation controls and reported adjustments"
parent: RFCs
grand_parent: Reference
nav_order: 8
---

# RFC 0008: Generation controls and reported adjustments

- **Status:** Proposed
- **Author(s):** @jwgcooke
- **Tracking issue:** [#2115](https://github.com/llm4s/llm4s/issues/2115)
- **Pull request:** [#2445](https://github.com/llm4s/llm4s/pull/2445)

## Summary

Three changes to `llm4s-core`, each shipped as its own PR:

1. **Reported adjustments.** Today, when a provider or model can't honour a request option, it is dropped and logged
   only at DEBUG. This RFC reports each such drop to the caller instead. `RequestTransformer`'s existing violations
   become a public value, `RequestAdjustment`. Every `Completion`, streamed or not, carries them. Purely additive.
2. **Thinking, separated from effort.** `CompletionOptions` gains `thinking: Option[Thinking]` (`Off`, `Adaptive`,
   `Budget(n)`). `ReasoningEffort` keeps its name and its meaning (how hard to think) and gains the levels providers
   now accept. Two members that blur the two ideas go, as a clean break before the MiMa baseline:
   `budgetTokens` (now `Thinking.Budget(n)`) and `ReasoningEffort.None` (now `Thinking.Off`).
3. **Provider-side prompt caching.** `CompletionOptions` gains `promptCache: Option[PromptCache]`, mapped by each
   provider that supports it and reported as an adjustment by each that doesn't. Purely additive.

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
| One write, 15 reads | 1.25 _P_ + 15 × 0.1 _P_ = **2.75 _P_** |

That is about 83% less on the shared prefix, and the saving grows with every agent-loop turn that reuses it. Once
step 4 lands, the `cachedTokens` and `cacheCreationTokens` counts show whether a deployment gets it.

If nothing changes, the issue author's workload, and anyone else's that needs these controls, has to bypass llm4s
for Anthropic, and multi-provider callers keep losing settings without notice.

## Detailed design

### 1. Reported adjustments

A public, `@Experimental` value describing what happened to one requested option:

```scala
package org.llm4s.llmconnect.model

@Experimental
enum RequestAdjustment {
  /** The option was not sent: the provider or model cannot honour it. */
  case Dropped(option: String, reason: String)

  /** The option was sent with a different value, e.g. temperature clamped, effort Max sent as High. */
  case Adjusted(option: String, requested: String, sent: String, reason: String)

  /** The option was not sent because the provider already does it, e.g. OpenAI's automatic prompt caching. */
  case NotNeeded(option: String, reason: String)

  def option: String
}
```

`option` is the `CompletionOptions` field name (`"temperature"`, `"thinking"`, `"promptCache"`), so a caller can
match on it without a provider-specific vocabulary.

**Where they come from.** There are two sources, both pure, and the result is one list:

- **`RequestTransformer`.** The private `Violation` case class already holds `drop` and a description. It gains the
  `RequestAdjustment` it represents. `TransformationResult`, a growth-prone `@Stable` type, gains a field under the
  RFC 0002 pattern:

  ```scala
  final case class TransformationResult private (
    options: CompletionOptions,
    messages: Seq[Message],
    requiresFakeStreaming: Boolean,
    adjustments: Seq[RequestAdjustment]          // new; Seq.empty in the existing apply overload
  )
  def withAdjustments(adjustments: Seq[RequestAdjustment]): TransformationResult
  ```

  `transformOptions(..., dropUnsupported = false)` keeps returning `Left(ValidationError)`. `dropUnsupported = true`
  now records what it dropped instead of only logging it.
- **The provider's own request mapping.** A provider that receives an option it doesn't map (Gemini given
  `reasoning` today, Ollama given `promptCache` later) records a `Dropped` when it builds the request. The vendor
  logic stays in the vendor's module, and no shared mapper trait is introduced. For the OpenAI-compatible family,
  the dialect hooks that already exist (`addReasoning`, and an `addPromptCache` added in section 3) return what they
  could not apply, with a default that reports `Dropped`. This follows the "a hook with a default" rule.

**Where they go.** `Completion`, a growth-prone `@Stable` type, gains `adjustments: List[RequestAdjustment]`, empty by
default, with `withAdjustments`. Streaming is covered because a streamed completion is built by the same client, from
the same `TransformationResult`.

The client logs each adjustment at WARN once per (provider, model, option), so the shared mapping functions never
log.

**No new policy type.** The default is unchanged: send what can be sent, and now say what wasn't. A caller that
must not run with a dropped option checks `completion.adjustments` and treats a `Dropped` as a failure. A
refuse-before-send option (the issue's `Strict`) is left out until a caller needs one (decision 2 below).

### 2. Thinking, separated from effort

```scala
@Experimental
enum Thinking {
  case Off                        // Anthropic thinking: disabled; Z.ai thinking.type: disabled; Gemini thinkingBudget 0
  case Adaptive                   // Anthropic thinking: adaptive; Gemini thinkingBudget -1
  case Budget(tokens: Int)        // Anthropic budget_tokens; Gemini thinkingBudget n
}
```

**Added.** `CompletionOptions` gains `thinking: Option[Thinking]`, default `None` (provider default), with
`withThinking(Thinking)` and `withThinking(Option[Thinking])`. `ReasoningEffort` keeps its name and meaning, *how
hard to think*, and gains `Minimal`, `XHigh` and `Max`.

**Removed**, as a clean break with a CHANGELOG line and a migration note, and no deprecation or shim:

- **`budgetTokens`** (field, `withBudgetTokens(Int)`, `withBudgetTokens(Option[Int])`). A budget already means
  "think, with this budget" wherever it is read (Anthropic, and OpenRouter for Anthropic models), which is exactly
  `Thinking.Budget(n)`.
- **`ReasoningEffort.None`.** It means "don't think" on some providers and "lowest effort" on others.
  `Thinking.Off` says what it means. `ReasoningEffort.fromString("none")` returns `None`, and the `ReasoningEffort`
  reader rejects `"none"`.

`effectiveBudgetTokens` and `hasReasoning` keep their signatures, redefined over the new fields: the budget is
`thinking`'s `Budget(n)` if set, else the default budget of `reasoning` as today; reasoning is on when `reasoning` is
set or `thinking` is set and not `Off`.

**One precedence rule**, on `withThinking`'s Scaladoc: an explicit `thinking` wins. Without one, `reasoning` decides
as it does today, so a caller that sets only `reasoning` sends the same request as before. A contradictory pair, such
as `thinking = Off` with `reasoning = High`, is resolved by the rule and reported as an `Adjusted` on `reasoning`.

Mapping, each provider in its own module. Anything not listed is reported as `Dropped`:

| | `reasoning` (effort) | `thinking` |
|---|---|---|
| Anthropic | `output_config.effort` where the model supports it, else a `budget_tokens` budget, as today (`Adjusted`) | `Off` → `disabled`, `Adaptive` → `adaptive`, `Budget(n)` → `budget_tokens: n` |
| OpenAI / Azure | `reasoning_effort`; an unsupported level `Adjusted` to the nearest supported one | `Off` → the lowest supported effort (`Adjusted` unless it is `none`); `Budget` `Dropped` |
| Z.ai | as #1691 | `Off` → `thinking.type: disabled` (or `low` on GLM-5.3, `Adjusted`) |
| OpenRouter | as today | Anthropic models: `Budget(n)` → `thinking.budget_tokens: n`, as today; `Off` → no thinking block. Others `Dropped` |
| Gemini / Vertex AI | `thinking_level` where supported | `thinkingBudget`: 0 / -1 / n |
| Bedrock, Ollama, watsonx, others | `Dropped` until mapped | `Dropped` until mapped (Ollama `think` in a follow-up) |

Anthropic's `output_config.effort` applies only when a caller sets one of the new levels or sets `thinking`
explicitly. A caller using `reasoning` alone keeps today's `budget_tokens` request, so the new mapping can't change
an existing call's cost.

Which levels a model accepts comes from `ModelCapabilities`, which starts reading metadata the bundled
`litellm_model_metadata.json` already carries but `fromJson` ignores. These are added as new fields under the
growth pattern:

```scala
supportsAdaptiveThinking: Option[Boolean]                 // supports_adaptive_thinking
supportsOutputConfig: Option[Boolean]                     // supports_output_config
supportedReasoningEfforts: Option[Set[ReasoningEffort]]   // supports_{minimal,low,...,xhigh,max}_reasoning_effort
supportsThinkingOff: Option[Boolean]                      // supports_none_reasoning_effort
```

Where the metadata is missing or wrong, `llm4s_model_overrides.json` corrects it, and a provider's
`RequestTransformer.adjusted` adds rules that follow from its model naming. No hard-coded model checks in core.

**`ThinkingReplay`.** The replay fingerprint today covers the system messages, tools, `responseFormat` and the
messages before the assistant turn. `thinking` joins it if a change of `thinking` makes a replayed signed block
invalid for the provider (decision 4 below). The step 3 PR settles this against the Anthropic and Bedrock APIs and
records the result in `ThinkingReplay`'s Scaladoc. Adding `thinking` when it isn't needed would unseal blocks that
could have been replayed.

### 3. Provider-side prompt caching

Purely additive.

```scala
@Experimental
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
- The name is `promptCache`, never `cache`, and its Scaladoc contrasts it with `CachingLLMClient`.
- The result shows up in the `TokenUsage.cachedTokens`/`cacheCreationTokens` counts that every provider now fills.
  `CostEstimator` gains the 1-hour cache-write price.

**`CachingLLMClient`'s key comes first.** Today an entry matches when its `CompletionOptions` is equal to the
request's (`entry.options == options`), so every new field joins the key implicitly. Before `promptCache` lands, the
key becomes an explicit calculation that lists the fields that change the answer: `reasoning` and `thinking` are in
it, `promptCache` is not, because provider-side caching does not change the response (decision 3 below).

### Not changed

`temperature`, `topP` and the penalties stay as they are, so llm4s keeps sending `temperature` by default and no
governance vote is needed. Service tier is left for later. Both can be added under this RFC's pattern, one field and
one adjustment kind each, without revisiting it. `TraceEvent` does not carry adjustments yet (decision 5 below).

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
- **Report adjustments only in logs or tracing.** It doesn't reach a caller at run time. `Completion.adjustments` is
  the caller's channel. A trace event can read the same list later.
- **Do nothing.** Prompt caching on Anthropic stays impossible, and silent drops continue.

## Compatibility impact

- **Modules and tiers:** `llm4s-core` (Frozen at 1.0): `CompletionOptions`, `ReasoningEffort`, `Completion`,
  `TransformationResult` and `ModelCapabilities` change. `RequestAdjustment`, `Thinking` and `PromptCache` are new
  and `@Experimental`. Provider modules change only internally. `llm4s-java-api` and `kotlin-api` follow core:
  `JReasoningEffort` loses `NONE` and gains `MINIMAL`, `XHIGH` and `MAX`, and `JCompletionOptions` loses
  `budgetTokens` and gains `thinking`, and the Kotlin `CompletionOptionsDsl` swaps its `budgetTokens` property for
  `thinking`.
- **Binary:** there is no baseline yet (#1281), so no MiMa filter is needed.
- **Source:** steps 1 and 3 are additive, apart from the usual exception for any growth-pattern addition: a
  positional pattern match on a case-class extractor whose arity changes. Step 2 breaks code that uses
  `budgetTokens`, `withBudgetTokens` or `ReasoningEffort.None`, and an exhaustive `match` on `ReasoningEffort` warns
  about the three new levels.
- **Behaviour:** a call that compiles after step 2 sends the same request as before; the new parts come into play only
  through the new setters. Two visible additions from step 1:
  - a dropped option now produces a WARN, once per provider, model and option;
  - a dropped option now produces an entry in `Completion.adjustments`.
- **Wire formats:** no checkpoint, persisted schema or config key changes. The `ReasoningEffort` JSON reader no
  longer accepts `"none"`, and accepts `"minimal"`, `"xhigh"` and `"max"`.

## Migration

Steps 1 and 3 need none. Step 2:

```scala
// Before
CompletionOptions().withBudgetTokens(8000)
CompletionOptions().withReasoning(ReasoningEffort.None)

// After
CompletionOptions().withThinking(Thinking.Budget(8000))
CompletionOptions().withThinking(Thinking.Off)
```

```java
// Java, before / after
JCompletionOptions.builder().reasoning(JReasoningEffort.NONE).budgetTokens(8000)
JCompletionOptions.builder().thinking(JThinking.budget(8000))
```

One behaviour to note: on OpenAI, `ReasoningEffort.None` sent no `reasoning_effort` (the model's default effort),
while `Thinking.Off` asks for the lowest effort the model accepts. A caller who wants the provider default sets
neither.

The CHANGELOG gets **Added** entries for each step and, for step 2, one line under `### Breaking changes`.
`docs/reference/migration.md` gets a section for step 2, and `docs/guide/cost-controls.md` documents the mapping
table and the precedence rule.

New controls, for reference:

```scala
CompletionOptions().withThinking(Thinking.Adaptive).withReasoning(ReasoningEffort.Low)
CompletionOptions().withPromptCache(PromptCache.default)
completion.adjustments                                 // what was dropped, adjusted or not needed
```

## Delivery

Each step is its own PR with its own review:

1. `RequestAdjustment` on `TransformationResult` and `Completion`, the transformer's existing drops reported, and
   WARN-once logging in the clients. No new options.
2. `ModelCapabilities` reads the metadata keys above.
3. `Thinking`, the new `ReasoningEffort` levels, and the removal of `budgetTokens` and `ReasoningEffort.None`, for
   Anthropic and OpenAI, with the `ThinkingReplay` check. Other providers report `Dropped` until each maps them in a
   follow-up.
4. `CachingLLMClient`'s explicit cache key.
5. `PromptCache`, for Anthropic, plus `NotNeeded` for OpenAI and Gemini.
6. Follow-ups, one provider or one control per PR: Bedrock `cachePoint`, Ollama `think`, Gemini thinking levels,
   and service tier.

The docs (`docs/guide/cost-controls.md` with the mapping table and precedence rule, rows in `providers.md`, and the
adjustment contract in `writing-a-provider.md`) land with steps 1, 3 and 5.

## Decisions

The issue discussion settled the questions an earlier draft left open
([comment](https://github.com/llm4s/llm4s/issues/2115#issuecomment-6517359913)):

1. **`ReasoningEffort.None` is removed**, not redefined as "lowest effort". `Thinking.Off` says what it means.
   `budgetTokens` goes with it.
2. **No refuse-before-send option for now.** Reported adjustments come first; a `Strict` flag or an up-front
   capability query can follow once a caller needs one.
3. **`CachingLLMClient`'s key becomes an explicit calculation** before `promptCache` lands (Delivery step 4).
4. **`thinking` joins the `ThinkingReplay` fingerprint** if a change of `thinking` makes a replayed thinking block
   invalid for the provider. Step 3 checks and records the answer.
5. **`TraceEvent` gets adjustments when a backend reads them**, not now.

## Open questions

None. Review comments on this PR may reopen any of the above.
