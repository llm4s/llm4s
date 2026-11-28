---
layout: page
title: Context Window Pruning
description: "Keep a long conversation inside the model's context window with LLM4S pruning strategies: oldest first, middle out, recent turns and adaptive."
search_title: "Context window pruning for LLM conversations in Scala"
nav_order: 211
parent: Agents
grand_parent: User Guide
---

# Context Window Pruning Strategies

Complete guide to LLM4S context window management and pruning strategies. `ContextWindowConfig`,
`PruningStrategy` and `ContextWindowMiddleware` are in the agent runtime, `llm4s-agent`:

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-agent" % "{{ site.data.project.latest_release }}"
```

## Overview

As conversations grow, token counts increase, leading to:
- **Higher costs** - Most LLM providers charge per token
- **Slower responses** - Longer context = slower processing
- **Context window limits** - Models have maximum input token limits

LLM4S provides context window management via `ContextWindowConfig` and `ContextWindowMiddleware`, with multiple pruning strategies to keep conversations within budget while preserving important context. The middleware trims what is sent to the model on each call; the thread keeps its full history.

Two rules hold for every strategy: the current turn (the latest user message onward) is never pruned, so the request always starts with a user message and may exceed the budget; and a tool call is never separated from its result: a call is kept only with the tool results straight after it, matched by position as providers match them, so a provider that reuses ids (`call_0` on every turn) cannot keep a call whose result was pruned (#3249). The agent's system prompt is outside the budget.

## Configuration Basics

```scala
import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

val contextConfig = ContextWindowConfig(
  maxTokens = Some(4096),              // Keep max 4K tokens
  maxMessages = Some(20),              // And max 20 messages: both limits apply
  preserveSystemMessage = true,        // Always keep system prompt
  minRecentTurns = 3,                  // Never prune the last 3 turns
  pruningStrategy = PruningStrategy.OldestFirst  // How to prune
)

val agent = Agent.builder("assistant", client)
  .withMiddleware(new ContextWindowMiddleware(contextConfig))
  .build()

agent.flatMap(_.runMultiTurn("What is a monad?", Seq("And a functor?", "How do the two relate?")))
```

Two rules hold under every strategy:

- **`maxTokens` and `maxMessages` are both limits.** With both set, the history is pruned when either is
  exceeded, and what is kept satisfies both. `AdaptiveWindowing`'s window is one more token limit: with
  `maxTokens` set as well, the smaller applies.
- **The last `minRecentTurns` turns are never pruned**, even past a limit. A turn runs from a user message to the
  next, so it includes the tool calls and results that answered it. The default is 1: the current turn is always
  sent whole. A higher value keeps the recent conversation coherent, and can send more than the limits allow, so
  leave room for it under the model's context window. The strategy prunes only the history before those turns,
  with whatever budget they leave.

## Pruning Strategies

### 1. OldestFirst (Recommended for most use cases)

**How it works:** Removes the oldest messages first while preserving the system message and most recent turns.

**Best for:**
- ✅ General-purpose multi-turn conversations
- ✅ FAQ-bot with rolling history
- ✅ Customer support chats
- ✅ When you want to keep latest context fresh

**Cost:**
- 🟢 Good - Removes old/repetitive context first
- 🟢 Predictable token savings

**Quality:**
- 🟡 Medium - May lose context from early turns
- ✅ Recent context stays intact

**Example:**
```scala
val config = ContextWindowConfig(
  maxMessages = Some(15),
  pruningStrategy = PruningStrategy.OldestFirst
)

// Conversation with 30 messages →  last 15 messages kept
// System message is always preserved
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐ | Removes metadata/greetings first |
| Context continuity | ⭐⭐⭐ | Recent context preserved |
| Implementation | ⭐⭐⭐⭐⭐ | Simple FIFO removal |
| Use case fit | Broad | Works for most scenarios |

---

### 2. MiddleOut (For complex reasoning)

**How it works:** Keeps the start (system + initial setup) and end (recent exchanges), removes middle messages.

**Best for:**
- ✅ Complex problem-solving requiring both initial context and recent progress
- ✅ Long code reviews or technical documents
- ✅ When you need to preserve both "setup" and "current state"
- ✅ Scientific or analytical conversations

**Cost:**
- 🟡 Medium - Loses middle context but keeps important bookends
- 🟡 Less predictable than OldestFirst

**Quality:**
- 🟡 Medium - Loses intermediate steps
- ✅ Good - Keeps initial and recent context

**Example:**
```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.MiddleOut
)

// Conversation:
// [System] [Initial Context] [Step 1] [Step 2] ... [Step N] [Recent Q&A]
//                                      ↑                    ↑
//                         These middle steps are pruned; keeps ends
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐ | Medium removal |
| Context continuity | ⭐⭐⭐⭐ | Preserves bookends |
| Implementation | ⭐⭐⭐⭐ | Straightforward middle removal |
| Use case fit | Specialized | Good for reasoning chains |

---

### 3. RecentTurnsOnly (For strict turn-based systems)

**How it works:** Keeps only the last N complete conversation turns (user message + assistant response + tool calls).
The turn count is a limit of its own: a longer history is pruned with or without `maxTokens` or `maxMessages`. When
one of those is set and the N turns exceed it, the oldest of them are dropped until they fit.

**Best for:**
- ✅ Turn-based games or dialogues
- ✅ Systems that value latest N interactions equally
- ✅ When conversation structure matters more than time
- ✅ Predictable conversation patterns

**Cost:**
- 🟢 Good - Focused token reduction
- ✅ Very predictable

**Quality:**
- 🟡 Medium - Only recent exchanges (may need earlier context)
- ✅ Good - Full recent turns preserved

**Example:**
```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
)

// Sends at most the last 5 turns, however long the conversation
// A "turn" = [User message] → [Assistant response] + optional [Tool messages]

// Turn 1: User asks → Assistant responds (+ tool calls)
// Turn 2: User asks → Assistant responds (+ tool calls)
// ...
// Turn 5: User asks → Assistant responds (+ tool calls)
// Turns 1-N: Pruned
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐ | Removes entire turn groups |
| Context continuity | ⭐⭐ | May lose early turns |
| Implementation | ⭐⭐⭐⭐ | Turn-based filtering |
| Use case fit | Specific | Best for turn-based systems |

---

### 4. Custom (Maximum flexibility)

**How it works:** You provide a pure function that decides which messages to keep.

**Best for:**
- ✅ Domain-specific pruning logic
- ✅ Marketing messages removal
- ✅ Metadata-first removal
- ✅ Complex importance scoring
- ✅ Hybrid strategies

**Cost:**
- 🟢 Excellent - You decide what's expensive
- ⭐ Depends on your logic

**Quality:**
- 🟢 Excellent - You decide what matters
- ⭐ Depends on your logic

**Example:**
```scala
// Remove verbose debug messages, keep concise ones
val pruneDebugMessages: Seq[Message] => Seq[Message] = { messages =>
  messages.filter { msg =>
    !msg.content.toLowerCase.contains("[debug]") ||
    msg.content.length < 100  // Keep short debug messages
  }
}

val config = ContextWindowConfig(
  maxMessages = Some(50),
  pruningStrategy = PruningStrategy.Custom(pruneDebugMessages)
)
```

**Advanced Example (importance scoring):**
```scala
val scoreImportance: Seq[Message] => Seq[Message] = { messages =>
  messages.map { msg =>
    val score = msg.role match {
      case MessageRole.System      => 100  // Always keep
      case MessageRole.User        => 80   // Keep user messages
      case MessageRole.Assistant   => 70   // Keep responses
      case MessageRole.Tool        => 40   // Lower priority
      case _                       => 0
    }
    (msg, score)
  }
  .sortBy(_._2)  // Sort by importance
  .dropWhile(_ => tokenCount > limit)  // Remove until under limit
  .map(_._1)
}
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐⭐ | Complete control |
| Context continuity | ⭐⭐⭐⭐⭐ | Complete control |
| Implementation | ⭐⭐ | Requires custom code |
| Use case fit | Very specific | Best for special requirements |

---

### 5. AdaptiveWindowing (a token budget from the model's size and prices)

**How it works:** `AdaptiveWindowing` turns a model's context window, and optionally its prices, into a token
budget, then prunes oldest first to fit it. You pass the numbers; the
[model registry](../reference/model-metadata) has them for the models it knows. The budget is:

1. a share of `contextWindowSize` by size tier: 60% up to 32K tokens, 70% up to 100K, 75% up to 200K, 80% above;
2. when both prices are given, reduced by up to 20%: by `inputCost / (inputCost + outputCost) * costSensitivity * 0.2`,
   so expensive input and a high `costSensitivity` shrink it most;
3. never below 1,000 tokens.

`costSensitivity` (0.0 to 1.0, default 0.5) has an effect only when both prices are given.

**Best for:**
- Deployments that switch models: derive the budget from the model's own metadata instead of a hand-set `maxTokens`
- Cost-conscious systems where input tokens dominate the bill

**Example:**
```scala
import org.llm4s.agent.{ ContextWindowConfig, PruningStrategy }

val strategy = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 922_000,           // gpt-5.6-terra's input limit
  inputCostPerToken = Some(0.000005),    // $5 per 1M input tokens
  outputCostPerToken = Some(0.000015),   // $15 per 1M output tokens
  costSensitivity = 0.5                  // the default
)

val config = ContextWindowConfig(
  pruningStrategy = strategy,
  minRecentTurns = 3     // Kept even past the window
)

println(strategy.calculateOptimalWindow)  // 93600: 128000 * 0.75 (tier) * 0.975 (cost)
println(strategy.explanation)             // AdaptiveWindowing: using 93600 tokens (73% of 128K large model)
```

The same numbers from the model registry, for whichever model a section configures:

```scala
import org.llm4s.agent.PruningStrategy
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ModelName

def adaptiveFor(registry: ModelRegistryService, model: String): Result[PruningStrategy] =
  registry.lookup(ModelName(model)).map { metadata =>
    PruningStrategy.AdaptiveWindowing(
      contextWindowSize = metadata.contextWindow.getOrElse(8192),
      inputCostPerToken = metadata.pricing.inputCostPerToken,
      outputCostPerToken = metadata.pricing.outputCostPerToken
    )
  }
```

**Size tiers** (without prices):

| `contextWindowSize` | Share | Budget for a window of that size |
|---|---|---|
| up to 32,000 | 60% | 8,000 → 4,800; 32,000 → 19,200 |
| 32,001 to 100,000 | 70% | 100,000 → 70,000 |
| 100,001 to 200,000 | 75% | 128,000 → 96,000; 200,000 → 150,000 |
| above 200,000 | 80% | 1,000,000 → 800,000 |

**Cost sensitivity examples** (128K window, input $5 and output $15 per 1M tokens, so input is a quarter of the
combined price):

```scala
import org.llm4s.agent.PruningStrategy

def adaptive(sensitivity: Double) = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 128_000,
  inputCostPerToken = Some(0.000005),
  outputCostPerToken = Some(0.000015),
  costSensitivity = sensitivity
)

adaptive(0.1).calculateOptimalWindow  // 95520: quality first, the window nearly untouched
adaptive(0.5).calculateOptimalWindow  // 93600: balanced (the default)
adaptive(0.9).calculateOptimalWindow  // 91680: cost first
```

Without prices, `costSensitivity` changes nothing: all three would be 96000.

## Strategy Comparison Matrix

| Strategy | Pros | Cons | Cost | Quality | When to Use |
|----------|------|------|------|---------|------------|
| **OldestFirst** | Simple, predictable | May lose early context | ⭐⭐⭐⭐ | ⭐⭐⭐ | Default choice |
| **MiddleOut** | Preserves bookends | Loses intermediate steps | ⭐⭐⭐ | ⭐⭐⭐⭐ | Complex reasoning |
| **RecentTurnsOnly** | Consistent turn count | Loses older turns | ⭐⭐⭐⭐ | ⭐⭐ | Turn-based systems |
| **Custom** | Maximum flexibility | Requires implementation | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Special needs |
| **AdaptiveWindowing** | Budget follows the model's size and prices | You supply the numbers | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | Several models |

---

## Configuration Patterns

### Pattern 1: Token-based Limiting (Recommended)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(4096),
  pruningStrategy = PruningStrategy.OldestFirst
)
// More precise - based on actual token usage
// Handles variable-length messages correctly
```

### Pattern 2: Message-based Limiting (Simpler)

```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.OldestFirst
)
// Simpler to understand
// Less precise but easier to reason about
```

### Pattern 3: Hybrid (Both limits)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(4096),
  maxMessages = Some(50),
  pruningStrategy = PruningStrategy.OldestFirst
)
// Enforces BOTH limits
// Prunes when either limit is exceeded, and keeps what satisfies both
```

### Pattern 4: Preserve Minimum Context

```scala
val config = ContextWindowConfig(
  maxMessages = Some(15),
  preserveSystemMessage = true,
  minRecentTurns = 3,                    // Always keep last 3 turns
  pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
)
// At most 5 turns are sent, fewer when they exceed maxMessages;
// the last 3 are always sent, even past it
```

### Pattern 5: Adaptive Mode (Multi-model)

Derive the budget from the configured model's metadata (`adaptiveFor` from
[AdaptiveWindowing](#5-adaptivewindowing-a-token-budget-from-the-models-size-and-prices)), so a change of model
changes the budget with it:

```scala
val config = adaptiveFor(registry, providerConfig.model.asString).map { strategy =>
  ContextWindowConfig(pruningStrategy = strategy)
}
```

---

## Cost Analysis

### Calculate conversation cost

```scala
import org.llm4s.context.ConversationTokenCounter
import org.llm4s.llmconnect.model.Conversation
import org.llm4s.llmconnect.provider.OpenAITokenizers
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.llm4s.types.ProviderModelTypes.ModelName

// the input cost of sending `conversation` once, at the registry's price for the model:
// exact for an OpenAI model (llm4s-openai), the cl100k_base approximation for any other
def inputCost(registry: ModelRegistryService, model: String, conversation: Conversation): Result[Option[Double]] =
  for {
    counter  <- OpenAITokenizers.counterFor(model).orElse(ConversationTokenCounter.cl100k())
    metadata <- registry.lookup(ModelName(model))
  } yield {
    val tokens = counter.countConversation(conversation)
    metadata.pricing.inputCostPerToken.map(_ * tokens)
  }
```

### Compare strategies

```scala
// Without pruning: 10,000 tokens = $0.05
// With OldestFirst (keeping 50 messages): ~3,000 tokens = $0.015 (70% savings)

// For 100 conversations per day:
// Without: $5.00/day
// With pruning: $1.50-2.00/day (savings: $3-3.50/day)
```

---

## Best Practices

### ✅ DO

1. **Use token-based limits** - More accurate for variable-length conversations
   ```scala
   maxTokens = Some(4096)  // Better than maxMessages
   ```

2. **Preserve system message** - Keeps instructions intact
   ```scala
   preserveSystemMessage = true  // Default, always set this
   ```

3. **Take window sizes from the model registry** - so a model change changes the budget
   ```scala
   adaptiveFor(registry, model)   // see AdaptiveWindowing above
   ```

4. **Monitor pruning events** - Log when and what gets removed
   ```scala
   // See next section on observability
   ```

5. **Test with real conversations** - Benchmark different strategies

### ❌ DON'T

1. **Set maxTokens too low** - Breaks context coherence
   ```scala
   maxTokens = Some(256)  // Too small!
   ```

2. **Forget about output budget** - Leave room for model responses
   ```scala
   // If context is 100K, use ~70K for input (leave 30K for output)
   maxTokens = Some(70_000)
   ```

3. **Treat the default count as exact for every model** - The default counter is `cl100k_base`, an approximation
   for non-OpenAI models; pass your model's counter for an exact one (`OpenAITokenizers.counterFor` in
   `llm4s-openai`, or `ConversationTokenCounter.of` over your own `StringTokenizer`)
   ```scala
   val middleware: Result[ContextWindowMiddleware] =
     OpenAITokenizers.counterFor("gpt-5.6-terra").map(counter => new ContextWindowMiddleware(config, counter.countMessage))
   ```

4. **Hard-code window sizes** - Derive them from the model's metadata instead
   ```scala
   // Bad: a fixed 4K window suits only some models
   // Good: AdaptiveWindowing from registry.lookup(model)
   ```

---

## Observability & Monitoring

### Inspect pruning results

Pruning happens in `ContextWindowMiddleware`, on each model call, and never changes the thread.
The thread's `AgentResult.messages` is the full history; to see what was sent, wrap the model call
with a small middleware of your own, placed after the context window middleware with `runsAfter`:

```scala
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  ContextWindowMiddleware,
  MiddlewareId,
  MiddlewareRef,
  ModelContext,
  ModelRequest,
  ModelResponse
}
import org.llm4s.types.Result
import org.slf4j.Logger

class LogSentMessages(logger: Logger) extends AgentMiddleware {
  val id = MiddlewareId("log-sent-messages")
  override def runsAfter = Set(MiddlewareRef.ofType[ContextWindowMiddleware])

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] = {
    logger.info(s"Sending ${request.messages.length} messages")
    next(request)
  }
}
```

### Log pruning performance

`ContextWindowMiddleware` takes an optional token counter (`new ContextWindowMiddleware(config, tokenCounter)`;
the default counts with `cl100k_base`), and `AgentResult.usage` reports the tokens the provider actually billed across the thread, which is
the number to compare with and without pruning:

```scala
logger.info(s"Thread usage: ${result.usage}")
```

---

## Troubleshooting

### Issue: Conversation keeps growing despite pruning

**Cause:** the agent was built without a `ContextWindowMiddleware`

**Solution:**
```scala
// ❌ Wrong: pruning not enabled
Agent.builder("assistant", client).build()

// ✅ Right: pruning enabled
Agent.builder("assistant", client)
  .withMiddleware(new ContextWindowMiddleware(config))  // Must add it!
  .build()
```

### Issue: Important context is being removed

**Cause:** Too aggressive pruning strategy

**Solution:**
```scala
// Increase limits
val config = ContextWindowConfig(
  maxTokens = Some(8192),  // Was 4096
  pruningStrategy = PruningStrategy.MiddleOut  // Preserves bookends
)

// Or use Custom strategy to protect important messages
```

### Issue: Conversations are too short

**Cause:** the limits are too tight for the recent turns, and only the current turn is protected by default

**Solution:**
```scala
val config = ContextWindowConfig(
  maxTokens = Some(8192),          // A larger budget
  minRecentTurns = 3,              // The last 3 turns are sent whatever the budget
  pruningStrategy = PruningStrategy.OldestFirst
)
```

---

## Advanced Examples

### Example 1: Cost-conscious chatbot

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.AdaptiveWindowing(
    contextWindowSize = 8000,              // Smaller model
    inputCostPerToken = Some(0.00001),     // Expensive
    outputCostPerToken = Some(0.00003),
    costSensitivity = 0.9                  // Minimize cost
  )
)
```

### Example 2: Research assistant (preserve all context)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(200_000),               // Large window
  pruningStrategy = PruningStrategy.MiddleOut,  // Keep setup + recent
  preserveSystemMessage = true
)
```

### Example 3: Code review assistant

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.Custom { messages =>
    messages.filter { msg =>
      // Keep code snippets and review comments
      msg.role == MessageRole.System ||      // Keep instructions
      msg.content.contains("```") ||         // Keep code blocks
      msg.content.contains("review") ||      // Keep review comments
      messages.indexOf(msg) >= messages.length - 10  // Keep recent
    }
  }
)
```

### Example 4: Turn-based game

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.RecentTurnsOnly(10),  // Last 10 turns
  preserveSystemMessage = true,
  minRecentTurns = 1  // Always keep last turn
)
```

---

## Summary

| Need | Strategy | Reason |
|------|----------|--------|
| I just want a working solution | **OldestFirst** | Simplest, most reliable |
| I switch between models | **AdaptiveWindowing** | The budget follows each model's size and prices |
| I have complex reasoning | **MiddleOut** | Preserves bookends |
| I need precise control | **Custom** | Maximum flexibility |
| I have turn-based logic | **RecentTurnsOnly** | Natural for turns |

Choose **AdaptiveWindowing**, built from the model registry, when the model changes between deployments. Choose
**OldestFirst** with a `maxTokens` for everything else.
