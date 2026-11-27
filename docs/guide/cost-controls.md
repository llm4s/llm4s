---
layout: page
title: Cost controls
description: "Cut what LLM calls cost in LLM4S: Thinking and ReasoningEffort per provider, provider-side prompt caching, service tiers, and reports of what was not sent."
search_title: "Control thinking and reasoning effort in Scala"
parent: Core
nav_order: 110
grand_parent: User Guide
---

# Cost controls
{: .no_toc }

Thinking tokens are output tokens: a model that thinks before it answers can cost several times as much as one that
does not. llm4s gives you two controls for it, and tells you when a provider could not send one as you asked.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## Thinking and reasoning effort

`CompletionOptions` has two separate controls ([RFC 0008](../rfcs/0008-generation-controls)):

- **`thinking: Option[Thinking]`** says *whether* the model thinks, and within what bound: `Thinking.Off`,
  `Thinking.Adaptive` (the model decides whether and how much), or `Thinking.budget(n)` (a token budget).
- **`reasoning: Option[ReasoningEffort]`** says *how hard* to think: `Minimal`, `Low`, `Medium`, `High`, `XHigh` or
  `Max`.

```scala
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

// let the model decide whether to think, at low effort
val adaptive = CompletionOptions().withThinking(Thinking.Adaptive).withReasoning(ReasoningEffort.Low)

// think within a budget; a budget of 0 or less is a ValidationError, so this is a Result
val budgeted: Result[CompletionOptions] = Thinking.budget(8000).map(CompletionOptions().withThinking)

// do not think
val off = CompletionOptions().withThinking(Thinking.Off)
```

From Java, `JCompletionOptions.builder().thinking(JThinking.adaptive()).reasoning(JReasoningEffort.LOW)`;
`JThinking.budget(n)` throws `IllegalArgumentException` for `n <= 0`. From Kotlin, the `completionOptions { }` DSL
has a `thinking` property.

### Which one wins

**An explicit `thinking` wins.** Without one, `reasoning` decides: a provider that thinks within a budget gets the
level's default budget. A pair that contradicts itself on a provider with one control for both, such as `thinking = Off` with
`reasoning = High` on OpenAI, is resolved by this rule and the level is reported as `Adjusted`.

**The exception.** Some current models reject a manual thinking budget with a 400: Claude Opus 4.7 and later and
every Claude 5 model (Sonnet 5 and 5.5, Haiku 5.5, Opus 5 and 5.5, Fable, Mythos). For a model whose capabilities say
so (`ModelCapabilities.supportsBudgetThinking = Some(false)` in the bundled model metadata, or, for an Anthropic model
id the metadata does not know, its name), a `reasoning`-only request is sent as adaptive thinking at that effort
instead, reported as `Adjusted(Reasoning, "budget_tokens", "output_config.effort", ...)`. The same models take no
`temperature`: see [Anthropic](providers#sampling-parameters-thinking-and-output-length).

### What each provider sends

| | `reasoning` | `thinking` |
|---|---|---|
| Anthropic | `output_config.effort` where the model takes it and you set `thinking` or a new level; otherwise a `budget_tokens` budget. A level Anthropic lacks (`minimal`) is sent as the nearest. | `Off` → `disabled` (a model that cannot turn thinking off gets its default, reported); `Adaptive` → `adaptive` (a model from before adaptive thinking gets a budget, reported); `Budget(n)` → `budget_tokens`, fitted to `[1024, max_tokens)` (to half of `max_tokens` when you set no `maxTokens`; a level's budget always so), or `adaptive` on a model that rejects a budget, each reported when changed |
| OpenAI, Azure | `reasoning_effort`; a level the model lacks is sent as the nearest it accepts | `Off` → the lowest effort the model accepts (`none` where it can turn thinking off); `Adaptive` not needed; `Budget` dropped |
| OpenRouter | a budget for Claude models, `reasoning_effort` for OpenAI o-series | Claude models: `Budget(n)` → `thinking.budget_tokens`, `Off` → no thinking block; otherwise dropped |
| Z.ai, xAI | by model family; see [Providers](providers) | `Off` → `thinking.type: disabled` or the lowest effort, as the model allows (reported where the model still thinks) |
| Perplexity, watsonx | the Agent API's or chat API's levels, the nearest where one is missing | `Off` → the lowest effort; the others are not sent |
| Gemini, Vertex AI | Gemini 3 and later: `thinkingLevel`, the nearest the model has. Gemini 2.5 takes no level: dropped | Gemini 2.5: `thinkingBudget` (`Off` → 0, or 2.5 Pro's minimum, which cannot turn off; `Adaptive` → -1; `Budget(n)` fitted to the model's range). Gemini 3 and later: `Off` → the lowest level; `Adaptive` not needed; `Budget` dropped (deprecated there). Other models: dropped |
| Ollama | `think`: the nearest level the model takes (from `/api/show`), or `true` on a model that takes only on and off | `Off` → `false`, or the lowest level on a model that cannot turn thinking off; `Adaptive` → `true`; `Budget` → `true` or dropped; see [Providers](providers#thinking-think) |
| Bedrock, other OpenAI-compatible | dropped | dropped |

A provider's own mapping, and which models take which levels, is on its section of [Providers](providers). Which
levels a model accepts comes from the bundled model metadata (`ModelCapabilities.supportedReasoningEfforts`,
`supportsAdaptiveThinking`, `supportsOutputConfig`, `supportsThinkingOff`, `supportsBudgetThinking`); a model the
metadata does not know is sent what you asked.

### Seeing what was sent

Whatever a provider could not send as asked is on `Completion.adjustments` (and on an agent's
`ModelCallCompleted` events), and logged at WARN once per client, model and option:

```scala
client.complete(conversation, CompletionOptions().withReasoning(ReasoningEffort.Max)).foreach { completion =>
  completion.adjustments.foreach {
    case RequestAdjustment.Adjusted(option, requested, sent, reason) => println(s"$option: $requested -> $sent ($reason)")
    case RequestAdjustment.Dropped(option, reason)                   => println(s"$option not sent: $reason")
    case RequestAdjustment.NotNeeded(option, reason)                 => println(s"$option not needed: $reason")
  }
}
```

A caller that must not run with an option it did not get treats a `Dropped` as a failure. An empty list means
nothing was reported, not that every option was sent: a few providers (Perplexity, watsonx) report nothing yet.

---

## Provider-side prompt caching

A request that starts the same way as an earlier one (the same system prompt, the same tools, the same conversation
so far) can have that start cached by the provider, so the next request reads it at a fraction of the input price
and answers sooner. Ask for it with `promptCache`:

```scala
import org.llm4s.llmconnect.model._

// the system prompt and the tools, for the provider's short lifetime (5 minutes on Anthropic)
val cached = CompletionOptions().withPromptCache(PromptCache.default)

// the conversation so far too, for the long lifetime (1 hour on Anthropic, at a higher write price)
val all = CompletionOptions().withPromptCache(PromptCache(PromptCache.Scope.values.toSet, PromptCache.Ttl.Long))
```

This is not [`CachingLLMClient`](caching), which keeps whole answers on your side and returns one without calling
the provider. A prompt cache calls the provider every time; it changes what the call costs, not what it returns, so
`CachingLLMClient` ignores `promptCache` when it matches a cached answer.

| | `promptCache` |
|---|---|
| Anthropic | `cache_control` breakpoints: the last system block (`System`), the last tool (`Tools`), and the conversation so far through the request's top-level `cache_control` (`Conversation`), all with the one TTL |
| OpenAI, Azure, Gemini, Vertex AI | reported `NotNeeded`: they cache a repeated prefix on their own |
| Bedrock (Converse) | `cachePoint` blocks: after the system blocks (`System`), after the tools (`Tools`) and at the end of the last message (`Conversation`), with `ttl: 1h` for `Long`. Claude models take all three; Claude 3.x, Sonnet 4, Opus 4 and 4.1 keep a cache for 5 minutes only, so `Long` is sent as `Short` (`Adjusted`). Amazon Nova takes no tools checkpoint and 5 minutes only (`Adjusted`). Other models, and a model the registry lists without prompt caching, report `Dropped` |
| Ollama, OpenAI-compatible, others | reported `Dropped` |

**A prefix that is too short is not cached.** Anthropic and Bedrock cache nothing below the model's minimum cacheable length
(from 512 to 4,096 tokens, by model) and returns no error. When a request asked for a prompt cache and the response
neither read from nor wrote to it, the completion reports
`Dropped(PromptCache, "nothing was cached: the prefix may be below the model's minimum cacheable length")`.

**Cost.** `TokenUsage.cachedTokens` are the tokens read from the cache and `cacheCreationTokens` those written to it,
of which `longCacheCreationTokens` were written with the long lifetime. `CostEstimator` prices each at the model's own
rate: a read at the cache-read price, a write at the cache-write price, and a long write at the 1-hour write price.
Both are parts of `promptTokens`, which is always the whole input; the rest is priced at the input rate.

### Warm the cache before a fan-out

A cache is written when a response starts. Requests sent at the same moment all find it empty, and each pays to write
it. To send many requests that share a start (one document, many questions), send one first and wait for it, then send
the rest, which read what the first one wrote:

```scala
val options = CompletionOptions().withPromptCache(PromptCache.default)

val first = client.complete(withDocument(questions.head), options)          // writes the cache
val rest  = questions.tail.map(q => client.complete(withDocument(q), options)) // read it; these may run in parallel
```

## Service tiers

Several providers sell the same model at more than one processing tier: a cheaper, slower one that is served when
there is spare capacity, and a faster one at a higher price. `CompletionOptions.serviceTier` asks for one; the answer
is the same either way, only its price and its scheduling change.

```scala
import org.llm4s.llmconnect.model._

// half price on OpenAI and Gemini, for a batch job that can wait and retry
val cheap = CompletionOptions().withServiceTier(ServiceTier.Flex)

// faster under load, where the account has access to it
val fast = CompletionOptions().withServiceTier(ServiceTier.Priority)
```

| | `Auto` | `Default` | `Flex` | `Priority` |
|---|---|---|---|---|
| OpenAI | `service_tier: auto` | `default` | `flex` | `priority` |
| Azure OpenAI | `auto` | `default` | dropped | `priority` |
| Anthropic | `service_tier: auto` | `standard_only` | dropped | `auto` (Priority Tier where the organization has a commitment; reported `Adjusted`) |
| Gemini | not needed (standard) | `serviceTier: standard` | `flex` | `priority` |
| Vertex AI | not needed | not needed | `X-Vertex-AI-LLM-Shared-Request-Type: flex` (`global` endpoint only) | `X-Vertex-AI-LLM-Shared-Request-Type: priority` |
| Bedrock | not needed | `serviceTier.type: default` | `flex` | `priority` |
| Others | dropped | dropped | dropped | dropped |

What each tier costs, and which models and accounts may use it, is the provider's. A provider may serve a request at
another tier than the one asked for (Gemini and Vertex AI serve Priority over its limit at standard), and a Flex
request may be shed under load: expect, and retry, 429 and 503 replies. `CachingLLMClient` ignores `serviceTier` when
it matches a cached answer.

`Completion.servedTier` reports the tier that did serve the call, on `complete` and on a stream, and an agent's
`ModelCallCompleted` event carries it too. OpenAI and Azure read the reply's `service_tier`, Anthropic its
`usage.service_tier` (`standard` is `Default`), Gemini the `x-gemini-service-tier` header, Vertex AI
`usageMetadata.trafficType` (`ON_DEMAND` is `Default`) and Bedrock `serviceTier.type`. A tier the enum does not name,
such as OpenAI's `scale`, Anthropic's `batch` or Vertex AI's `PROVISIONED_THROUGHPUT`, is `ServiceTier.Other(raw)`; a
provider that reports none leaves it `None`. Set on `serviceTier`, `Other(raw)` is sent as written.

```scala
client.complete(conversation, CompletionOptions().withServiceTier(ServiceTier.Auto)).map { c =>
  if (c.servedTier.contains(ServiceTier.Priority)) println("served at priority, billed at its rate")
}
```
