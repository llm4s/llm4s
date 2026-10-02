---
layout: page
title: Streaming Events
nav_order: 5
parent: Agents
grand_parent: User Guide
---

# Streaming Events
{: .no_toc }

Real-time visibility into agent execution for responsive UIs.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The streaming events system provides real-time feedback during agent execution:

- **Text streaming** - Token-by-token output as the LLM generates
- **Tool events** - Know when tools start, complete, or fail
- **Lifecycle events** - Track agent steps and completion
- **Guardrail events** - Monitor validation progress
- **Handoff events** - Track agent-to-agent delegation

---

## Quick Start

```scala
import org.llm4s.agent.streaming._

agent.runWithEvents(query, tools) {
  case TextDelta(text, _) =>
    print(text)  // Real-time token output

  case ToolCallStarted(_, name, _, _) =>
    println(s"\nCalling $name...")

  case ToolCallCompleted(_, name, result, _, duration, _) =>
    println(s"$name completed in ${duration.toMillis}ms")

  case AgentCompleted(state, steps, duration, _) =>
    println(s"\nDone in $steps steps (${duration.toMillis}ms)")

  case _ => ()  // Ignore other events
}
```

---

## Event Types

### Text Events

| Event | Description | Fields |
|-------|-------------|--------|
| `TextDelta` | Token-level streaming chunk | `delta`, `timestamp` |
| `TextComplete` | Full text generation finished | `fullText`, `timestamp` |

```scala
case TextDelta(delta, timestamp) =>
  // delta: The new text chunk
  // timestamp: When this chunk was received
  print(delta)

case TextComplete(fullText, timestamp) =>
  // fullText: Complete generated text
  println(s"\n--- Complete: ${fullText.length} chars ---")
```

### Tool Events

| Event | Description | Fields |
|-------|-------------|--------|
| `ToolCallStarted` | Tool execution beginning | `toolCallId`, `toolName`, `arguments`, `timestamp` |
| `ToolCallCompleted` | Tool finished successfully | `toolCallId`, `toolName`, `result`, `success`, `duration` (a `FiniteDuration`), `timestamp` |
| `ToolCallFailed` | Tool execution failed | `toolCallId`, `toolName`, `error`, `timestamp` |

```scala
case ToolCallStarted(id, name, args, _) =>
  println(s"[$id] Starting $name with args: $args")

case ToolCallCompleted(id, name, result, success, duration, _) =>
  println(s"[$id] $name: $result (${duration.toMillis}ms)")

case ToolCallFailed(id, name, error, _) =>
  println(s"[$id] $name FAILED: $error")
```

### Agent Lifecycle Events

| Event | Description | Fields |
|-------|-------------|--------|
| `AgentStarted` | Agent execution beginning | `query`, `toolCount`, `timestamp` |
| `StepStarted` | New reasoning step | `stepNumber`, `timestamp` |
| `StepCompleted` | Step finished | `stepNumber`, `hasToolCalls`, `timestamp` |
| `AgentCompleted` | Agent finished successfully | `finalState`, `totalSteps`, `duration` (a `FiniteDuration`), `timestamp` |
| `AgentFailed` | Agent execution failed | `error`, `stepNumber`, `timestamp` |

```scala
case AgentStarted(query, toolCount, _) =>
  println(s"Starting agent with $toolCount tools: $query")

case StepStarted(stepNum, _) =>
  println(s"--- Step $stepNum ---")

case StepCompleted(stepNum, hasToolCalls, _) =>
  println(s"Step $stepNum done, tools called: $hasToolCalls")

case AgentCompleted(state, steps, duration, _) =>
  println(s"Completed in $steps steps (${duration.toMillis}ms)")
  println(s"Final answer: ${state.lastAssistantMessage}")

case AgentFailed(error, stepNum, _) =>
  println(s"Failed at step $stepNum: $error")
```

### Guardrail Events

| Event | Description | Fields |
|-------|-------------|--------|
| `InputGuardrailStarted` | Input validation starting | `guardrailName`, `timestamp` |
| `InputGuardrailCompleted` | Input validation done | `guardrailName`, `passed`, `timestamp` |
| `OutputGuardrailStarted` | Output validation starting | `guardrailName`, `timestamp` |
| `OutputGuardrailCompleted` | Output validation done | `guardrailName`, `passed`, `timestamp` |

```scala
case InputGuardrailStarted(name, _) =>
  println(s"Validating input: $name")

case InputGuardrailCompleted(name, passed, _) =>
  println(s"Input $name: ${if (passed) "PASS" else "FAIL"}")

case OutputGuardrailStarted(name, _) =>
  println(s"Validating output: $name")

case OutputGuardrailCompleted(name, passed, _) =>
  println(s"Output $name: ${if (passed) "PASS" else "FAIL"}")
```

### Handoff Events

| Event | Description | Fields |
|-------|-------------|--------|
| `HandoffStarted` | Agent delegation starting | `targetAgentName`, `reason`, `preserveContext`, `timestamp` |
| `HandoffCompleted` | Delegation finished | `targetAgentName`, `success`, `timestamp` |

```scala
case HandoffStarted(targetName, reason, preserveContext, _) =>
  println(s"Handing off to $targetName: $reason")
  println(s"Context preserved: $preserveContext")

case HandoffCompleted(targetName, success, _) =>
  println(s"Handoff to $targetName: ${if (success) "success" else "failed"}")
```

---

## Usage Patterns

### Basic Streaming UI

```scala
agent.runWithEvents(query, tools) { event =>
  event match {
    case TextDelta(text, _) =>
      print(text)
      System.out.flush()

    case AgentCompleted(_, steps, duration, _) =>
      println(s"\n\n✓ Completed in $steps steps (${duration.toMillis}ms)")

    case AgentFailed(error, step, _) =>
      println(s"\n\n✗ Failed at step $step: $error")

    case _ => ()
  }
}
```

### Progress Indicator

```scala
var currentStep = 0

agent.runWithEvents(query, tools) { event =>
  event match {
    case StepStarted(stepNum, _) =>
      currentStep = stepNum
      print(s"\rStep $stepNum...")

    case ToolCallStarted(_, name, _, _) =>
      print(s"\rStep $currentStep: $name...")

    case ToolCallCompleted(_, name, _, _, duration, _) =>
      print(s"\rStep $currentStep: $name ✓ (${duration.toMillis}ms)")

    case AgentCompleted(_, steps, duration, _) =>
      println(s"\rCompleted in $steps steps (${duration.toMillis}ms)      ")

    case _ => ()
  }
}
```

### Event Collection

Collect all events for post-processing:

```scala
import scala.concurrent.duration.Duration

val (state, events) = agent.runCollectingEvents(query, tools)

// Analyze events
val toolCalls = events.collect { case e: ToolCallCompleted => e }
val totalToolTime = toolCalls.map(_.duration).foldLeft(Duration.Zero)(_ + _)

println(s"Total tool execution time: ${totalToolTime.toMillis}ms")
println(s"Tool calls: ${toolCalls.map(_.toolName).mkString(", ")}")
```

### Metrics Collection

```scala
import org.llm4s.agent.streaming.StreamingAccumulator

val accumulator = StreamingAccumulator.create()

agent.runWithEvents(query, tools) { event =>
  accumulator.record(event)

  // Also handle real-time display
  event match {
    case TextDelta(text, _) => print(text)
    case _ => ()
  }
}

// Get metrics after completion
val metrics = accumulator.getMetrics()
println(s"Total tokens: ${metrics.tokenCount}")
println(s"Time to first token: ${metrics.timeToFirstToken}ms")
println(s"Tool calls: ${metrics.toolCallCount}")
println(s"Total duration: ${metrics.totalDuration}ms")
```

---

## Advanced Patterns

### Timeout Handling

```scala
import scala.concurrent.duration._

val result = agent.runWithEvents(
  query = query,
  tools = tools,
  timeout = Some(30.seconds),
  onEvent = { event =>
    event match {
      case TextDelta(text, _) => print(text)
      case AgentFailed(error, _, _) =>
        if (error.contains("timeout")) {
          println("\n⏰ Request timed out")
        }
      case _ => ()
    }
  }
)
```

### Cancellation

```scala
import java.util.concurrent.atomic.AtomicBoolean

val cancelled = new AtomicBoolean(false)

// In another thread or signal handler
def cancel(): Unit = cancelled.set(true)

agent.runWithEvents(
  query = query,
  tools = tools,
  cancellationCheck = () => cancelled.get(),
  onEvent = { event =>
    event match {
      case TextDelta(text, _) =>
        print(text)
      case AgentFailed(error, _, _) if error.contains("cancelled") =>
        println("\n🛑 Cancelled")
      case _ => ()
    }
  }
)
```

### Web Socket Integration

```scala
import org.llm4s.agent.streaming._

def handleWebSocketQuery(query: String, socket: WebSocket): Unit = {
  agent.runWithEvents(query, tools) { event =>
    val message = event match {
      case TextDelta(text, _) =>
        s"""{"type":"text","content":"$text"}"""

      case ToolCallStarted(id, name, _, _) =>
        s"""{"type":"tool_start","id":"$id","name":"$name"}"""

      case ToolCallCompleted(id, name, result, _, duration, _) =>
        s"""{"type":"tool_complete","id":"$id","name":"$name","result":"$result","ms":${duration.toMillis}}"""

      case AgentCompleted(state, steps, duration, _) =>
        s"""{"type":"complete","steps":$steps,"ms":${duration.toMillis}}"""

      case AgentFailed(error, step, _) =>
        s"""{"type":"error","message":"$error","step":$step}"""

      case _ => null
    }

    if (message != null) {
      socket.send(message)
    }
  }
}
```

### React/Frontend Integration

```scala
// Backend endpoint returning Server-Sent Events
def streamQuery(query: String): Source[ServerSentEvent] = {
  Source.fromIterator { () =>
    val events = collection.mutable.Buffer[ServerSentEvent]()

    agent.runWithEvents(query, tools) { event =>
      val sse = event match {
        case TextDelta(text, _) =>
          ServerSentEvent(data = text, eventType = Some("text"))

        case ToolCallStarted(_, name, _, _) =>
          ServerSentEvent(data = name, eventType = Some("tool_start"))

        case AgentCompleted(_, _, _, _) =>
          ServerSentEvent(data = "done", eventType = Some("complete"))

        case _ => null
      }

      if (sse != null) events += sse
    }

    events.iterator
  }
}
```

---

## Event Filtering

### By Type

```scala
agent.runWithEvents(query, tools) { event =>
  // Only handle text and completion events
  event match {
    case e: TextDelta => handleText(e)
    case e: AgentCompleted => handleComplete(e)
    case _ => () // Ignore all other events
  }
}
```

### Custom Filter

```scala
def onlySignificantEvents(event: AgentEvent): Boolean = event match {
  case _: TextDelta => true
  case _: ToolCallCompleted => true
  case _: AgentCompleted => true
  case _: AgentFailed => true
  case _ => false
}

agent.runWithEvents(query, tools) { event =>
  if (onlySignificantEvents(event)) {
    processEvent(event)
  }
}
```

---

## Performance Considerations

### 1. Keep Event Handlers Fast

```scala
// Good - fast handler
agent.runWithEvents(query, tools) { event =>
  event match {
    case TextDelta(text, _) =>
      buffer.append(text)  // Fast operation
    case _ => ()
  }
}

// Bad - slow handler blocks streaming
agent.runWithEvents(query, tools) { event =>
  event match {
    case TextDelta(text, _) =>
      database.insert(text)  // Slow I/O in handler
    case _ => ()
  }
}
```

### 2. Use Async for Heavy Processing

```scala
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

agent.runWithEvents(query, tools) { event =>
  event match {
    case TextDelta(text, _) =>
      // Display immediately
      print(text)

    case e: ToolCallCompleted =>
      // Log asynchronously
      Future {
        database.logToolCall(e)
      }

    case _ => ()
  }
}
```

### 3. Batch Updates for UI

```scala
import java.util.concurrent.atomic.AtomicReference

val textBuffer = new AtomicReference[StringBuilder](new StringBuilder)
var lastRender = System.currentTimeMillis()

agent.runWithEvents(query, tools) { event =>
  event match {
    case TextDelta(text, _) =>
      textBuffer.get().append(text)

      // Batch UI updates every 50ms
      val now = System.currentTimeMillis()
      if (now - lastRender > 50) {
        renderUI(textBuffer.get().toString)
        lastRender = now
      }

    case AgentCompleted(_, _, _, _) =>
      // Final render
      renderUI(textBuffer.get().toString)

    case _ => ()
  }
}
```

---

## Examples

| Example | Description |
|---------|-------------|
| [StreamingAgentExample](/examples/#streaming-examples) | Basic streaming with events |
| [EventCollectionExample](/examples/#streaming-examples) | Collecting and analyzing events |
| [StreamingWithProgressExample](/examples/#streaming-examples) | Progress indicators and metrics |

[Browse all examples →](/examples/)

---

## Next Steps

- [Memory Guide](memory) - Persistent context
- [Handoffs Guide](handoffs) - Agent delegation
- [Guardrails Guide](guardrails) - Input/output validation
