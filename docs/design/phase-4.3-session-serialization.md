# Phase 4.3: Session Serialization Enhancements

> **Superseded in part:** this document describes the pre-#1328 agent API (`new Agent(client)`, `AgentState`, per-run tools and guardrails). `Agent` now runs on the graph runtime and is built with `Agent.builder(...)`; see [typed-agent-runtime-design.md §4.13](typed-agent-runtime-design.md) and the [Stage 1 migration note](../reference/migration.md#stage-1-migration-agent-runtime). Kept as history.

> **Status:** Complete
> **Last Updated:** 2025-11-26
> **Related:** Agent Framework Roadmap, Phase 4.1 Reasoning Modes
> **Dependencies:** Phase 4.1 (Reasoning Modes)

## Executive Summary

Phase 4.3 enhances AgentState serialization to fully support all fields introduced in Phase 4.1 (Reasoning Modes), ensuring that agent state can be persisted and restored with complete fidelity, including reasoning configuration.

## Motivation

### Background

Phase 4.1 added reasoning mode support to CompletionOptions:
- `reasoning: Option[ReasoningEffort]` - configurable reasoning effort level
- `budgetTokens: Option[Int]` - explicit token budget for Anthropic extended thinking

These fields were not originally included in AgentState serialization, meaning:
1. Saved agent states lost reasoning configuration
2. Restored sessions could not continue with the same reasoning settings
3. ReasoningEffort enum had no ReadWriter for upickle serialization

### Goals

1. **Complete Serialization**: All CompletionOptions fields serialize/deserialize correctly
2. **Backward Compatibility**: Old JSON without new fields loads successfully
3. **Type Safety**: ReasoningEffort has proper upickle ReadWriter
4. **Comprehensive Testing**: Full test coverage for serialization round-trips

## Implementation

### 1. ReasoningEffort ReadWriter

Added upickle ReadWriter for ReasoningEffort serialization:

```scala
// modules/core/src/main/scala/org/llm4s/llmconnect/model/ReasoningEffort.scala

import upickle.default.{ ReadWriter => RW, readwriter }

object ReasoningEffort {
  // ... existing code ...

  /**
   * Upickle ReadWriter for serialization/deserialization.
   * Serializes to/from the string name (e.g., "none", "low", "medium", "high").
   */
  implicit val rw: RW[ReasoningEffort] = readwriter[ujson.Value].bimap[ReasoningEffort](
    effort => ujson.Str(effort.name),
    {
      case ujson.Str(s) =>
        fromString(s).getOrElse(
          throw new IllegalArgumentException(s"Invalid ReasoningEffort: $s")
        )
      case other =>
        throw new IllegalArgumentException(s"Expected string for ReasoningEffort, got: $other")
    }
  )
}
```

### 2. CompletionOptions Serialization

Updated `serializeCompletionOptions` in AgentState:

```scala
private def serializeCompletionOptions(opts: CompletionOptions): ujson.Value =
  ujson.Obj(
    "temperature"      -> ujson.Num(opts.temperature),
    "topP"             -> ujson.Num(opts.topP),
    "maxTokens"        -> opts.maxTokens.map(ujson.Num(_)).getOrElse(ujson.Null),
    "presencePenalty"  -> ujson.Num(opts.presencePenalty),
    "frequencyPenalty" -> ujson.Num(opts.frequencyPenalty),
    "reasoning"        -> opts.reasoning.map(r => writeJs(r)).getOrElse(ujson.Null),
    "budgetTokens"     -> opts.budgetTokens.map(ujson.Num(_)).getOrElse(ujson.Null)
    // Note: tools are NOT serialized (contain function references)
  )
```

### 3. CompletionOptions Deserialization

Updated `deserializeCompletionOptions` with backward compatibility:

```scala
private def deserializeCompletionOptions(json: ujson.Value): CompletionOptions =
  CompletionOptions(
    temperature = json("temperature").num,
    topP = json("topP").num,
    maxTokens = json("maxTokens") match {
      case ujson.Num(n) => Some(n.toInt)
      case _            => None
    },
    presencePenalty = json("presencePenalty").num,
    frequencyPenalty = json("frequencyPenalty").num,
    reasoning = json.obj.get("reasoning").flatMap {
      case ujson.Null => None
      case v          => Some(read[ReasoningEffort](v))
    },
    budgetTokens = json.obj.get("budgetTokens").flatMap {
      case ujson.Num(n) => Some(n.toInt)
      case _            => None
    }
  )
```

**Key Design Decisions:**
- Uses `json.obj.get()` for new fields to handle old JSON format gracefully
- Returns `None` for missing fields (backward compatibility)
- Null values map to `None` (explicit absence)

### 4. Bug Fix: ToolMessage Deserialization

During testing, discovered a pre-existing bug in Message.scala where ToolMessage deserialization had parameters reversed:

```scala
// Before (incorrect)
case "tool" => ToolMessage(obj("toolCallId").str, obj("content").str)

// After (correct - matches case class order)
case "tool" => ToolMessage(obj("content").str, obj("toolCallId").str)
```

## Testing

### Test Coverage

Created comprehensive test file: `AgentStateSerializationSpec.scala`

**33 tests covering:**

1. **ReasoningEffort Serialization** (11 tests)
   - Serialize None/Low/Medium/High to strings
   - Deserialize strings back to enums
   - Round-trip all values
   - Error handling for invalid strings and non-string values

2. **CompletionOptions Serialization** (6 tests)
   - Basic fields preservation
   - Reasoning field when set
   - BudgetTokens field when set
   - Both fields together
   - None values as null in JSON

3. **AgentState Full Round-Trip** (3 tests)
   - Basic state with all fields
   - State with all CompletionOptions including reasoning
   - Conversation with tool messages

4. **AgentStatus Serialization** (5 tests)
   - InProgress, WaitingForTools, Complete
   - Failed with error message
   - Round-trip all statuses

5. **Backward Compatibility** (1 test)
   - JSON without reasoning fields loads successfully

6. **Message Serialization** (5 tests)
   - UserMessage, SystemMessage, AssistantMessage, ToolMessage
   - AssistantMessage with tool calls

7. **Conversation Serialization** (2 tests)
   - Empty conversation
   - Multi-message conversation

### Running Tests

```bash
# Run serialization tests only
sbt "core/testOnly *AgentStateSerializationSpec"

# Run on both Scala versions
sbt "+core/testOnly *AgentStateSerializationSpec"

# Full test suite
sbt "+test"
```

## Files Changed

| File | Change |
|------|--------|
| `ReasoningEffort.scala` | Added upickle ReadWriter implicit |
| `AgentState.scala` | Updated serialization to include reasoning/budgetTokens |
| `Message.scala` | Fixed ToolMessage deserialization parameter order |
| `AgentStateSerializationSpec.scala` | NEW: 33 comprehensive tests |

## Usage Examples

### Saving State with Reasoning Configuration

```scala
import org.llm4s.agent.{ Agent, AgentState }
import org.llm4s.llmconnect.model._

// Create state with reasoning configuration
val state = AgentState(
  conversation = Conversation(Seq(UserMessage("Complex math problem"))),
  tools = ToolRegistry.empty,
  completionOptions = CompletionOptions()
    .withReasoning(ReasoningEffort.High)
    .withBudgetTokens(16000)
)

// Save to file
AgentState.saveToFile(state, "/tmp/session.json")

// Later: Load and continue with same reasoning settings
for {
  loadedState <- AgentState.loadFromFile("/tmp/session.json", ToolRegistry.empty)
} yield {
  // loadedState.completionOptions.reasoning == Some(ReasoningEffort.High)
  // loadedState.completionOptions.budgetTokens == Some(16000)
}
```

### JSON Format

```json
{
  "conversation": { ... },
  "initialQuery": null,
  "status": "Complete",
  "logs": [],
  "systemMessage": null,
  "completionOptions": {
    "temperature": 0.7,
    "topP": 1.0,
    "maxTokens": 4096,
    "presencePenalty": 0.0,
    "frequencyPenalty": 0.0,
    "reasoning": "high",
    "budgetTokens": 16000
  }
}
```

## Success Criteria

- [x] ReasoningEffort has upickle ReadWriter for serialization
- [x] CompletionOptions serializes reasoning and budgetTokens fields
- [x] Backward compatibility: old JSON without new fields loads correctly
- [x] ToolMessage deserialization bug fixed
- [x] Comprehensive test coverage (33 tests)
- [x] Tests pass on both Scala 2.13 and Scala 3.7.1
- [x] Full test suite passes (771 tests)

## Related Documentation

- [Phase 4.1: Reasoning Modes](phase-4.1-reasoning-modes.md)
- [CLAUDE.md - Working with Multi-Turn Conversations](../../CLAUDE.md#working-with-multi-turn-conversations)
