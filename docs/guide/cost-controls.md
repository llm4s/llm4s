---
layout: page
title: Cost controls
description: "Control how much an LLM thinks in LLM4S: Thinking (off, adaptive or a token budget) and ReasoningEffort, mapped per provider, with what was not sent reported on the completion."
search_title: "Control thinking and reasoning effort in Scala"
parent: User Guide
nav_order: 60
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

`CompletionOptions` has two separate controls ([RFC 0008](../rfcs/0008-generation-controls.md)):

- **`thinking: Option[Thinking]`** says *whether* the model thinks, and within what bound: `Thinking.Off`,
  `Thinking.Adaptive` (the model decides whether and how much), or `Thinking.budget(n)` (a token budget).
- **`reasoning: Option[ReasoningEffort]`** says *how hard* to think: `Minimal`, `Low`, `Medium`, `High`, `XHigh` or
  `Max`.

```scala
import org.llm4s.llmconnect.model._

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

**An explicit `thinking` wins.** Without one, `reasoning` decides as it always has: a provider that thinks within a
budget gets the level's default budget, so a request that sets only `reasoning` sends what it sent before `thinking`
existed. A pair that contradicts itself on a provider with one control for both, such as `thinking = Off` with
`reasoning = High` on OpenAI, is resolved by this rule and the level is reported as `Adjusted`.

**The exception.** Some current models reject a manual thinking budget with a 400 (Claude Opus 4.7, Opus 4.8 and
Fable 5 among those in the bundled model metadata). For a model whose capabilities say so
(`ModelCapabilities.supportsBudgetThinking = Some(false)`), a `reasoning`-only request is sent as adaptive thinking at
that effort instead, reported as `Adjusted(Reasoning, "budget_tokens", "output_config.effort", ...)`. Every request
this changes is one that failed before.

### What each provider sends

| | `reasoning` | `thinking` |
|---|---|---|
| Anthropic | `output_config.effort` where the model takes it and you set `thinking` or a new level; otherwise a `budget_tokens` budget, as before. A level Anthropic lacks (`minimal`) is sent as the nearest. | `Off` → `disabled` (a model that cannot turn thinking off gets its default, reported); `Adaptive` → `adaptive` (a model from before adaptive thinking gets a budget, reported); `Budget(n)` → `budget_tokens`, fitted to `[1024, max_tokens)`, or `adaptive` on a model that rejects a budget, each reported when changed |
| OpenAI, Azure | `reasoning_effort`; a level the model lacks is sent as the nearest it accepts | `Off` → the lowest effort the model accepts (`none` where it can turn thinking off); `Adaptive` not needed; `Budget` dropped |
| OpenRouter | as before: a budget for Claude models, `reasoning_effort` for OpenAI o-series | Claude models: `Budget(n)` → `thinking.budget_tokens`, `Off` → no thinking block; otherwise dropped |
| Z.ai, xAI | by model family; see [Providers](providers.md) | `Off` → `thinking.type: disabled` or the lowest effort, as the model allows (reported where the model still thinks) |
| Perplexity, watsonx | the Agent API's or chat API's levels, the nearest where one is missing | `Off` → the lowest effort; the others are not sent |
| Gemini, Vertex AI | Gemini 3 and later: `thinkingLevel`, the nearest the model has. Gemini 2.5 takes no level: dropped | Gemini 2.5: `thinkingBudget` (`Off` → 0, or 2.5 Pro's minimum, which cannot turn off; `Adaptive` → -1; `Budget(n)` fitted to the model's range). Gemini 3 and later: `Off` → the lowest level; `Adaptive` not needed; `Budget` dropped (deprecated there). Other models: dropped |
| Bedrock, Ollama, other OpenAI-compatible | dropped | dropped |

A provider's own mapping, and which models take which levels, is on its section of [Providers](providers.md). Which
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
