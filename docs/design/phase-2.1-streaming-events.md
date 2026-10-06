# Phase 2.1: Event-based Streaming

> **Superseded:** the agent event stream (`runWithEvents`, `AgentEvent`) described here was removed in [#1328](https://github.com/llm4s/llm4s/issues/1328) when the agent loop moved onto the graph runtime; its replacement is [#1329](https://github.com/llm4s/llm4s/issues/1329). Kept as history.

> **Status:** Complete
> **Last Updated:** 2025-11-26
> **Related:** Agent Framework Roadmap

## Executive Summary

Phase 2.1 adds event-based streaming to the agent framework, enabling:
- Real-time token streaming during LLM generation
- Progress updates during multi-step agent execution
- Tool call visibility (start/complete events)
- Better UX for long-running agent operations

## Motivation

### Why Streaming Events Matter

1. **User Experience**: Users expect real-time responses, not waiting for complete answers
2. **Progress Visibility**: Long-running agents need progress indicators
3. **Tool Transparency**: Users should see which tools are being invoked
4. **Cancellation**: Streaming enables early termination if needed
5. **Debugging**: Events help trace agent execution in real-time

### Current State

LLM4S already has:
- Callback-based streaming at `LLMClient` level via `streamComplete()`
- `StreamedChunk` type for incremental content
- `StreamingAccumulator` for chunk accumulation
- Provider-specific streaming handlers (OpenAI, Anthropic, etc.)

**Gap**: Agent class doesn't expose streaming to callers.

## Architecture

### Event Hierarchy

```scala
sealed trait AgentEvent {
  def timestamp: Instant
}

object AgentEvent {
  // Text generation events
  case class TextDelta(delta: String, timestamp: Instant) extends AgentEvent
  case class TextComplete(fullText: String, timestamp: Instant) extends AgentEvent

  // Tool events
  case class ToolCallStarted(
    toolCallId: String,
    toolName: String,
    arguments: String,
    timestamp: Instant
  ) extends AgentEvent

  case class ToolCallCompleted(
    toolCallId: String,
    toolName: String,
    result: String,
    timestamp: Instant
  ) extends AgentEvent

  // Agent lifecycle events
  case class AgentStarted(query: String, timestamp: Instant) extends AgentEvent
  case class StepStarted(stepNumber: Int, timestamp: Instant) extends AgentEvent
  case class StepCompleted(stepNumber: Int, timestamp: Instant) extends AgentEvent
  case class AgentCompleted(finalState: AgentState, timestamp: Instant) extends AgentEvent
  case class AgentFailed(error: LLMError, timestamp: Instant) extends AgentEvent

  // Handoff events
  case class HandoffStarted(
    fromAgent: String,
    toAgent: String,
    reason: Option[String],
    timestamp: Instant
  ) extends AgentEvent
}
```

### Streaming API Options

#### Option A: Callback-based (Consistent with LLMClient)

```scala
class Agent(client: LLMClient) {
  def runWithEvents(
    query: String,
    tools: ToolRegistry,
    onEvent: AgentEvent => Unit,  // Callback for each event
    // ... other params
  ): Result[AgentState]
}
```

**Pros:**
- Consistent with existing `streamComplete(onChunk: ...)` pattern
- Simple to understand and use
- Works with any effect system

**Cons:**
- Callbacks are side-effecting
- Harder to compose

#### Option B: Iterator-based (Functional)

```scala
class Agent(client: LLMClient) {
  def streamRun(
    query: String,
    tools: ToolRegistry,
    // ... other params
  ): Iterator[Result[AgentEvent]]  // Lazy event stream
}
```

**Pros:**
- More functional (lazy evaluation)
- Can be consumed with standard Iterator methods
- Composable

**Cons:**
- Thread management is tricky
- LLM streaming is push-based, Iterator is pull-based

#### Option C: Both (Recommended)

Provide callback-based for compatibility and a helper for Iterator conversion.

```scala
class Agent(client: LLMClient) {
  // Primary API: callback-based
  def runWithEvents(
    query: String,
    tools: ToolRegistry,
    onEvent: AgentEvent => Unit
  ): Result[AgentState]

  // Convenience: collect events
  def runCollectingEvents(
    query: String,
    tools: ToolRegistry
  ): Result[(AgentState, Seq[AgentEvent])]
}
```

### Implementation Design

#### Agent Integration

```scala
class Agent(client: LLMClient) {

  def runWithEvents(
    query: String,
    tools: ToolRegistry,
    onEvent: AgentEvent => Unit,
    handoffs: Seq[Handoff] = Seq.empty,
    systemPromptAddition: Option[String] = None,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    completionOptions: CompletionOptions = CompletionOptions(),
    maxSteps: Option[Int] = None,
    debug: Boolean = false
  ): Result[AgentState] = {

    // Emit start event
    onEvent(AgentEvent.AgentStarted(query, Instant.now()))

    // Initialize state
    val state = initialize(query, tools, handoffs, systemPromptAddition, completionOptions)

    // Run with streaming
    runStepsWithEvents(state, onEvent, maxSteps.getOrElse(10), 0, debug)
  }

  private def runStepsWithEvents(
    state: AgentState,
    onEvent: AgentEvent => Unit,
    maxSteps: Int,
    currentStep: Int,
    debug: Boolean
  ): Result[AgentState] = {

    if (currentStep >= maxSteps) {
      onEvent(AgentEvent.AgentFailed(LLMError.MaxStepsReached, Instant.now()))
      return Left(LLMError.MaxStepsReached)
    }

    onEvent(AgentEvent.StepStarted(currentStep, Instant.now()))

    // Use streaming completion
    val accumulator = StreamingAccumulator.create()

    val streamResult = client.streamComplete(
      state.toApiConversation,
      state.completionOptions,
      onChunk = { chunk =>
        chunk.content.foreach { delta =>
          onEvent(AgentEvent.TextDelta(delta, Instant.now()))
        }
        chunk.toolCall.foreach { tc =>
          // Tool call detection happens incrementally
          onEvent(AgentEvent.ToolCallStarted(tc.id, tc.name, tc.arguments, Instant.now()))
        }
        accumulator.addChunk(chunk)
      }
    )

    streamResult.flatMap { completion =>
      // Text complete
      onEvent(AgentEvent.TextComplete(completion.text, Instant.now()))
      onEvent(AgentEvent.StepCompleted(currentStep, Instant.now()))

      // Process tool calls if any
      completion.toolCalls match {
        case calls if calls.nonEmpty =>
          // Execute tools with events
          val toolResults = calls.map { tc =>
            onEvent(AgentEvent.ToolCallStarted(tc.id, tc.name, tc.arguments, Instant.now()))
            val result = tools.execute(tc)
            onEvent(AgentEvent.ToolCallCompleted(tc.id, tc.name, result.toString, Instant.now()))
            result
          }

          // Add to state and continue
          val newState = state.addMessages(...)
          runStepsWithEvents(newState, onEvent, maxSteps, currentStep + 1, debug)

        case _ =>
          // Complete
          val finalState = state.withStatus(AgentStatus.Complete)
          onEvent(AgentEvent.AgentCompleted(finalState, Instant.now()))
          Right(finalState)
      }
    }
  }
}
```

### Multi-Turn Streaming

```scala
def continueConversationWithEvents(
  previousState: AgentState,
  newUserMessage: String,
  onEvent: AgentEvent => Unit,
  maxSteps: Option[Int] = None,
  contextWindowConfig: Option[ContextWindowConfig] = None
): Result[AgentState]
```

### Usage Example

```scala
import org.llm4s.agent._
import org.llm4s.agent.streaming._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)

  finalState <- agent.runWithEvents(
    query = "What's the weather in London?",
    tools = weatherTools,
    onEvent = {
      case AgentEvent.TextDelta(delta, _) =>
        print(delta)  // Stream to console

      case AgentEvent.ToolCallStarted(_, name, _, _) =>
        println(s"\n[Calling tool: $name]")

      case AgentEvent.ToolCallCompleted(_, name, result, _) =>
        println(s"[Tool $name returned: $result]")

      case AgentEvent.AgentCompleted(state, _) =>
        println(s"\n[Agent completed with ${state.conversation.messageCount} messages]")

      case _ => // Ignore other events
    }
  )
} yield finalState
```

## File Structure

```
modules/core/src/main/scala/org/llm4s/agent/streaming/
├── AgentEvent.scala           # Event type hierarchy
├── AgentEventEmitter.scala    # Helper for emitting events (if needed)
└── package.scala              # Package docs
```

## Testing Strategy

1. **Unit Tests**: Event emission for each event type
2. **Integration Tests**: Full agent run with event collection
3. **Timing Tests**: Event ordering guarantees
4. **Error Tests**: Events during failures

## Backward Compatibility

- Existing `Agent.run()` method unchanged
- New `runWithEvents()` method is opt-in
- No breaking changes to existing APIs

## Implementation Summary

### Files Created

| File | Description |
|------|-------------|
| `modules/core/.../agent/streaming/AgentEvent.scala` | Event type hierarchy |
| `modules/core/.../agent/streaming/package.scala` | Package documentation |
| `modules/core/.../test/agent/streaming/AgentEventSpec.scala` | Event tests |
| `modules/samples/.../streaming/StreamingAgentExample.scala` | Real-time streaming sample |
| `modules/samples/.../streaming/EventCollectionExample.scala` | Event collection sample |

### API Methods Added to Agent

| Method | Description |
|--------|-------------|
| `runWithEvents()` | Run agent with streaming events via callback |
| `continueConversationWithEvents()` | Continue conversation with streaming |
| `runCollectingEvents()` | Run and collect all events |

### Running the Samples

```bash
sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"
sbt "samples/runMain org.llm4s.samples.streaming.EventCollectionExample"
```

## Future Extensions

- WebSocket integration for real-time UIs
- Event filtering and transformation
- Event persistence for replay
- Metrics collection from events
