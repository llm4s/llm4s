# Phase 4.1: Reasoning Modes

> **Status:** Complete
> **Last Updated:** 2025-11-26
> **Related:** Agent Framework Roadmap
> **Note:** Core types, API, and provider integrations are complete.

## Executive Summary

Phase 4.1 adds support for reasoning/extended thinking modes to llm4s, enabling:
- **OpenAI o1/o3 models**: Configurable reasoning effort levels (low, medium, high)
- **Anthropic Claude**: Extended thinking with budget tokens
- **Transparent API**: Unified configuration across providers
- **Thinking content**: Access to model's reasoning process when available

## Motivation

### Current Limitations

Users cannot:
- Configure reasoning effort for o1/o3 models
- Enable extended thinking for Claude models
- Access the model's reasoning/thinking output
- Optimize latency vs. quality tradeoff for complex tasks

### Benefits of Reasoning Modes

1. **Better Quality**: Extended reasoning improves accuracy on complex tasks
2. **Latency Control**: Choose reasoning effort based on task complexity
3. **Transparency**: See the model's thought process
4. **Provider Flexibility**: Same API works across OpenAI and Anthropic

## Architecture

### Design Principles

1. **Provider-Agnostic API**: Single configuration works across providers
2. **Opt-in**: Reasoning modes are optional, defaults to no extra reasoning
3. **Safe Defaults**: Invalid configurations are handled gracefully
4. **Thinking Visibility**: Thinking content is available but separate from main output

### Reasoning Effort Levels

```scala
sealed trait ReasoningEffort {
  def name: String
}

object ReasoningEffort {
  /** No extra reasoning - standard completion */
  case object None extends ReasoningEffort { val name = "none" }

  /** Minimal reasoning - for simple tasks requiring slight deliberation */
  case object Low extends ReasoningEffort { val name = "low" }

  /** Moderate reasoning - balanced quality vs latency */
  case object Medium extends ReasoningEffort { val name = "medium" }

  /** Maximum reasoning - for complex tasks requiring deep thinking */
  case object High extends ReasoningEffort { val name = "high" }

  /** Parse from string (case-insensitive) */
  def fromString(s: String): Option[ReasoningEffort] = s.toLowerCase match {
    case "none"   => Some(None)
    case "low"    => Some(Low)
    case "medium" => Some(Medium)
    case "high"   => Some(High)
    case _        => scala.None
  }
}
```

### Provider Mapping

| ReasoningEffort | OpenAI o1/o3 | Anthropic Claude |
|-----------------|--------------|------------------|
| None | Standard completion | Standard completion |
| Low | `reasoning_effort: "low"` | `thinking.budget_tokens: 2048` |
| Medium | `reasoning_effort: "medium"` | `thinking.budget_tokens: 8192` |
| High | `reasoning_effort: "high"` | `thinking.budget_tokens: 32768` |

**Note:** For Anthropic, exact budget values can be overridden with `budgetTokens` parameter.

## Implementation

### Package Structure

```
modules/core/src/main/scala/org/llm4s/llmconnect/
├── model/
│   ├── CompletionOptions.scala  # Add reasoning fields
│   ├── Completion.scala         # Add thinking content
│   └── ReasoningEffort.scala    # NEW: Reasoning effort enum
├── provider/
│   ├── AnthropicClient.scala    # Extended thinking support
│   ├── OpenAIClient.scala       # o1/o3 reasoning support
│   └── OpenRouterClient.scala   # Reasoning support via JSON
└── streaming/
    └── StreamingAccumulator.scala  # Thinking chunk accumulation
```

### Extended CompletionOptions

```scala
case class CompletionOptions(
  temperature: Double = 0.7,
  topP: Double = 1.0,
  maxTokens: Option[Int] = None,
  presencePenalty: Double = 0.0,
  frequencyPenalty: Double = 0.0,
  tools: Seq[ToolFunction[_, _]] = Seq.empty,
  // NEW: Reasoning configuration
  reasoning: Option[ReasoningEffort] = None,
  budgetTokens: Option[Int] = None  // Override for Anthropic thinking budget
) {
  /**
   * Enable reasoning with specified effort level.
   */
  def withReasoning(effort: ReasoningEffort): CompletionOptions =
    copy(reasoning = Some(effort))

  /**
   * Enable reasoning with explicit token budget (Anthropic).
   */
  def withBudgetTokens(tokens: Int): CompletionOptions =
    copy(budgetTokens = Some(tokens))
}
```

### Extended Completion Response

```scala
case class Completion(
  id: String,
  created: Long,
  content: String,
  model: String,
  message: AssistantMessage,
  toolCalls: List[ToolCall] = List.empty,
  usage: Option[TokenUsage] = None,
  // NEW: Thinking/reasoning content
  thinking: Option[String] = None
) {
  /**
   * Check if completion includes thinking content.
   */
  def hasThinking: Boolean = thinking.exists(_.nonEmpty)

  /**
   * Get full response including thinking (if available).
   */
  def fullContent: String = thinking match {
    case Some(t) if t.nonEmpty => s"<thinking>\n$t\n</thinking>\n\n$content"
    case _ => content
  }
}
```

### Extended TokenUsage

```scala
case class TokenUsage(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  // NEW: Thinking token tracking
  thinkingTokens: Option[Int] = None
) {
  /**
   * Total output tokens including thinking.
   */
  def totalOutputTokens: Int = completionTokens + thinkingTokens.getOrElse(0)
}
```

### Provider Implementations

#### Anthropic Extended Thinking

```scala
// In AnthropicClient.scala
private def addThinkingConfig(
  paramsBuilder: MessageCreateParams.Builder,
  options: CompletionOptions
): Unit = {
  // Calculate budget tokens based on reasoning effort or explicit budget
  val budgetTokens = options.budgetTokens.orElse {
    options.reasoning.map {
      case ReasoningEffort.None   => 0
      case ReasoningEffort.Low    => 2048
      case ReasoningEffort.Medium => 8192
      case ReasoningEffort.High   => 32768
    }
  }

  // Enable extended thinking if budget > 0
  budgetTokens.filter(_ > 0).foreach { budget =>
    // Anthropic API: enable thinking with budget
    paramsBuilder.thinking(
      ThinkingConfig.builder()
        .type("enabled")
        .budgetTokens(budget)
        .build()
    )
  }
}
```

#### OpenAI o1/o3 Reasoning

```scala
// In OpenAIClient.scala
private def addReasoningConfig(
  chatOptions: ChatCompletionsOptions,
  options: CompletionOptions,
  modelName: String
): Unit = {
  // Only apply reasoning for o1/o3 models
  if (isReasoningModel(modelName)) {
    options.reasoning.foreach { effort =>
      if (effort != ReasoningEffort.None) {
        // OpenAI API: set reasoning_effort parameter
        // Note: Temperature is ignored for reasoning models
        chatOptions.setReasoningEffort(effort.name)
      }
    }
  }
}

private def isReasoningModel(model: String): Boolean =
  model.startsWith("o1") || model.startsWith("o3")
```

### Streaming Support

Extended thinking content is streamed separately from main content:

```scala
case class StreamedChunk(
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None,
  // NEW: Thinking content delta
  thinkingDelta: Option[String] = None
)
```

The `StreamingAccumulator` is extended to accumulate thinking content:

```scala
class StreamingAccumulator {
  private val thinkingBuilder = new StringBuilder

  def addThinkingDelta(delta: String): Unit =
    thinkingBuilder.append(delta)

  def toCompletion: Result[Completion] = {
    // ... existing completion building ...
    completion.copy(thinking =
      if (thinkingBuilder.isEmpty) None
      else Some(thinkingBuilder.toString)
    )
  }
}
```

## Usage Examples

### Basic Reasoning

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

// Enable reasoning for complex tasks
val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)
  .copy(maxTokens = Some(4096))

for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(
    Conversation.user("Solve: What is the probability of drawing 3 aces from a deck of cards?"),
    options
  )
} yield {
  println(s"Thinking: ${response.thinking.getOrElse("N/A")}")
  println(s"Answer: ${response.content}")
  println(s"Thinking tokens: ${response.usage.flatMap(_.thinkingTokens).getOrElse(0)}")
}
```

### Anthropic with Custom Budget

```scala
// For Claude models, explicitly set thinking budget
val options = CompletionOptions()
  .withBudgetTokens(16000)  // Custom budget
  .copy(maxTokens = Some(4096))

for {
  providerConfig <- Llm4sConfig.provider()  // LLM_MODEL=anthropic/claude-sonnet-4-5-latest
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(conversation, options)
} yield response.thinking
```

### Agent with Reasoning

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.toolapi.ToolRegistry

val options = CompletionOptions()
  .withReasoning(ReasoningEffort.Medium)

for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  state <- agent.run(
    "Analyze this complex problem step by step",
    ToolRegistry.empty,
    options = options
  )
} yield state
```

### Streaming with Thinking

```scala
val options = CompletionOptions().withReasoning(ReasoningEffort.High)

client.streamComplete(
  conversation,
  options,
  onChunk = { chunk =>
    chunk.thinkingDelta.foreach(t => print(s"[thinking] $t"))
    chunk.content.foreach(print)
  }
)
```

## Safety Considerations

### Model Compatibility

1. **Reasoning Effort**: Only applies to o1/o3 models; ignored for other OpenAI models
2. **Extended Thinking**: Only applies to Claude models; ignored for others
3. **Temperature**: Reasoning models (o1/o3) ignore temperature settings
4. **Tool Calling**: Some reasoning modes may not support tool calling

### Token Budget

1. **Budget Limits**: Anthropic has minimum (1024) and maximum budget tokens
2. **Max Tokens**: Total output (thinking + response) is limited by maxTokens
3. **Cost**: Thinking tokens are billed at same rate as output tokens

### Error Handling

```scala
// Invalid configurations are handled gracefully
val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)
  .copy(temperature = 0.9)  // Ignored for reasoning models

// If model doesn't support reasoning, it's silently ignored
// No error thrown - just standard completion
```

## Testing

### Unit Tests

- ReasoningEffort parsing and serialization
- CompletionOptions builder methods
- TokenUsage with thinking tokens
- Completion with thinking content

### Integration Tests

- OpenAI o1/o3 with reasoning effort
- Anthropic Claude with extended thinking
- Streaming with thinking content
- Agent runs with reasoning enabled

### Test Coverage

```
modules/core/src/test/scala/org/llm4s/llmconnect/
├── model/
│   └── ReasoningEffortSpec.scala
├── ReasoningModesSpec.scala  # Integration tests
└── streaming/
    └── ThinkingStreamSpec.scala
```

## Samples

Two sample applications demonstrate reasoning modes:

1. **ReasoningModesExample**: Direct reasoning usage
   ```bash
   export LLM_MODEL=openai/o1-preview
   export OPENAI_API_KEY=sk-...
   sbt "samples/runMain org.llm4s.samples.reasoning.ReasoningModesExample"
   ```

2. **ReasoningModesExample**: Anthropic extended thinking
   ```bash
   export LLM_MODEL=anthropic/claude-sonnet-4-5-latest
   export ANTHROPIC_API_KEY=sk-ant-...
   sbt "samples/runMain org.llm4s.samples.reasoning.ReasoningModesExample"
   ```

## Success Criteria

- [x] ReasoningEffort enum with all levels
- [x] CompletionOptions extended with reasoning fields
- [x] Completion extended with thinking content
- [x] TokenUsage extended with thinking tokens
- [x] AnthropicClient supports extended thinking (SDK upgraded to 2.11.1)
- [x] OpenAIClient documented (Azure SDK doesn't yet support reasoning_effort)
- [x] OpenRouterClient supports reasoning for both Anthropic and OpenAI models
- [x] Streaming accumulates thinking content
- [x] Unit tests for all new types (39 tests, 694 total passing)
- [x] Sample applications
- [x] Documentation complete

## Future Enhancements

1. **Streaming Callbacks**: Separate callback for thinking vs content
2. **Reasoning Inspection**: Tools for analyzing thinking quality
3. **Cost Tracking**: Thinking token cost estimation
4. **Provider Auto-Detection**: Automatically enable reasoning for compatible models
