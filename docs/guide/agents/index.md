---
layout: page
title: Agents
nav_order: 1
parent: User Guide
has_children: true
---

# Agent Framework
{: .no_toc }

Build sophisticated AI agents with tools, guardrails, memory, and multi-agent coordination.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The LLM4S Agent Framework provides a production-ready foundation for building LLM-powered agents with:

- **Tool Calling** - Type-safe tools with automatic schema generation
- **Guardrails** - Input/output validation for safety and quality
- **Memory** - Short and long-term context with semantic search
- **Handoffs** - Agent-to-agent delegation for specialist routing
- **Streaming** - Real-time events for responsive UIs
- **Orchestration** - Multi-agent workflows with DAG execution

## Quick Start

### Basic Agent

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.agent.Agent
import org.llm4s.toolapi.ToolRegistry

// Create an agent and run a query
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  state <- agent.run(
    query = "What is the capital of France?",
    tools = ToolRegistry.empty
  )
} yield state

result match {
  case Right(state) => println(state.lastAssistantMessage)
  case Left(error) => println(s"Error: $error")
}
```

### Agent with Tools

```scala
import org.llm4s.toolapi.{ToolRegistry, ToolFunction}

// Define a tool
def getWeather(location: String): String = {
  s"The weather in $location is sunny, 72F"
}

val weatherTool = ToolFunction(
  name = "get_weather",
  description = "Get current weather for a location",
  function = getWeather _
)

// Run agent with tools
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  tools = new ToolRegistry(Seq(weatherTool))
  state <- agent.run("What's the weather in Paris?", tools)
} yield state
```

### Multi-Turn Conversations

```scala
// Functional multi-turn pattern
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  tools = ToolRegistry.empty

  // First turn
  state1 <- agent.run("Tell me about Scala", tools)

  // Follow-up (preserves context)
  state2 <- agent.continueConversation(state1, "How does it compare to Java?")

  // Another follow-up
  state3 <- agent.continueConversation(state2, "What about performance?")
} yield state3
```

---

## Safety Defaults

- **Agent step limit**: `Agent.run(...)` defaults to `maxSteps = Some(50)` to prevent infinite loops. Pass `maxSteps = None` to allow unlimited steps.
- **HTTPTool methods**: `HttpConfig()` defaults to `GET` and `HEAD` only. Use `HttpConfig.withWriteMethods()` or `HttpConfig().withAllMethods` to allow write methods.

---

## Core Concepts

### Agent State

The `AgentState` is an immutable container that tracks:

- **Conversation history** - All messages exchanged
- **Available tools** - Tools the agent can call
- **Status** - `InProgress`, `WaitingForTools`, `Complete`, `Failed`, or `HandoffRequested`
- **System message** - Instructions for the LLM
- **Completion options** - Temperature, max tokens, etc.

```scala
// Agent state is immutable - operations return new states
val newState = state.addMessage(UserMessage("Follow-up question"))
```

### Agent Lifecycle

```
Initial Query
     |
     v
+----------+     LLM Call      +------------------+
| InProgress| --------------> | WaitingForTools  |
+----------+                  +------------------+
     ^                               |
     |        Tool Execution         |
     +-------------------------------+
     |
     v (no more tool calls)
+----------+
| Complete |
+----------+
```

### Tool Execution Strategies

Control how multiple tool calls are executed:

```scala
import org.llm4s.agent.ToolExecutionStrategy

// Sequential (default) - one at a time, safest
agent.run(query, tools)

// Parallel - all at once, fastest
agent.runWithStrategy(query, tools, ToolExecutionStrategy.Parallel)

// Parallel with limit - balance speed and resources
agent.runWithStrategy(query, tools, ToolExecutionStrategy.ParallelWithLimit(3))
```

---

## Features

### [Guardrails](guardrails)

Validate inputs and outputs for safety:

```scala
import org.llm4s.agent.guardrails.builtin._

agent.run(
  query = "Generate JSON data",
  tools = tools,
  inputGuardrails = Seq(
    new LengthCheck(1, 10000),
    new ProfanityFilter()
  ),
  outputGuardrails = Seq(
    new JSONValidator()
  )
)
```

[Learn more about guardrails →](guardrails)

### [Memory System](memory)

Persistent context across conversations:

```scala
import org.llm4s.agent.memory._

val result = for {
  manager <- SimpleMemoryManager.empty
  m1 <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
  context <- m1.getRelevantContext("programming preferences")
} yield context
```

[Learn more about memory →](memory)

### [Handoffs](handoffs)

Delegate to specialist agents:

```scala
import org.llm4s.agent.Handoff

agent.run(
  query = "Complex physics question",
  tools = tools,
  handoffs = Seq(
    Handoff.to(physicsAgent, "Physics expertise required")
  )
)
```

[Learn more about handoffs →](handoffs)

### [Streaming Events](streaming)

Real-time execution feedback:

```scala
import org.llm4s.agent.streaming._

agent.runWithEvents(query, tools) {
  case TextDelta(text, _) => print(text)
  case ToolCallStarted(_, name, _, _) => println(s"Calling $name...")
  case ToolCallCompleted(_, name, result, _, _, _) => println(s"$name: $result")
  case AgentCompleted(state, steps, ms, _) => println(s"Done in $steps steps")
  case _ => ()
}
```

[Learn more about streaming →](streaming)

---

## Built-in Tools

LLM4S provides pre-built tools for common tasks:

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

// Core tools (always safe)
BuiltinTools.core          // DateTime, Calculator, UUID, JSON

// Safe for most use cases
BuiltinTools.safe()        // + web search, HTTP

// With file access (read-only)
BuiltinTools.withFiles()   // + read-only file access

// All tools (use with caution)
BuiltinTools.development() // All tools including write access
```

**Available tools:**

| Tool | Description |
|------|-------------|
| `DateTimeTool` | Current date/time, timezone conversion |
| `CalculatorTool` | Mathematical calculations |
| `UUIDTool` | Generate unique identifiers |
| `JSONTool` | Parse and format JSON |
| `HTTPTool` | Make HTTP requests |
| `WebSearchTool` | Search the web |
| `FileReadTool` | Read files (with restrictions) |
| `ShellTool` | Execute shell commands (development only) |

---

## Context Window Management

Handle long conversations automatically:

```scala
import org.llm4s.agent.{ContextWindowConfig, PruningStrategy}

val config = ContextWindowConfig(
  maxMessages = Some(20),
  preserveSystemMessage = true,
  minRecentTurns = 2,
  pruningStrategy = PruningStrategy.OldestFirst
)

// Use with runMultiTurn for automatic pruning
val queries = Seq("Question 1", "Question 2", "Question 3")
agent.runMultiTurn(queries, tools, contextConfig = Some(config))
```

**Pruning Strategies:**

| Strategy | Behavior |
|----------|----------|
| `OldestFirst` | Remove oldest messages first (FIFO) |
| `MiddleOut` | Keep first and last messages, remove middle |
| `RecentTurnsOnly(n)` | Keep only the last N conversation turns |
| `Custom(fn)` | User-defined pruning function |

---

## Conversation Persistence

Save and resume conversations:

```scala
// Save state to disk
AgentState.saveToFile(state, "/tmp/conversation.json")

// Load and resume
val result = for {
  loadedState <- AgentState.loadFromFile("/tmp/conversation.json", tools)
  resumedState <- agent.continueConversation(loadedState, "Continue our conversation")
} yield resumedState
```

---

## Reasoning Modes

Enable extended thinking for complex problems:

```scala
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}

val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .copy(maxTokens = Some(4096))

// Use with agent
agent.run(query, tools, completionOptions = Some(options))
```

Supported by OpenAI o1/o3 and Anthropic Claude models.

---

## Examples

| Example | Description |
|---------|-------------|
| [SingleStepAgentExample](/examples/#single-step) | Step-by-step debugging |
| [MultiStepAgentExample](/examples/#multi-step) | Complete execution flow |
| [MultiTurnConversationExample](/examples/#multi-turn) | Functional multi-turn API |
| [LongConversationExample](/examples/#long-conversation) | Context window pruning |
| [ConversationPersistenceExample](/examples/#persistence) | Save and resume |
| [AsyncToolAgentExample](/examples/#agent-examples) | Parallel tool execution |
| [BuiltinToolsAgentExample](/examples/#agent-examples) | Built-in tools |

[Browse all examples →](/examples/)

---

## Design Documents

For in-depth technical details:

- [Agent Framework Roadmap](/design/agent-framework-roadmap) - Strategic direction
- [Phase 1.1: Conversations](/design/phase-1.1-functional-conversation-management) - Conversation API
- [Phase 1.2: Guardrails](/design/phase-1.2-guardrails-framework) - Validation framework
- [Phase 1.3: Handoffs](/design/phase-1.3-handoff-mechanism) - Agent delegation
- [Phase 1.4: Memory](/design/phase-1.4-memory-system) - Memory architecture
- [Phase 2.1: Streaming](/design/phase-2.1-streaming-events) - Event system

---

## Next Steps

1. **[Guardrails Guide](guardrails)** - Input/output validation
2. **[Memory Guide](memory)** - Persistent context
3. **[Handoffs Guide](handoffs)** - Agent delegation
4. **[Streaming Guide](streaming)** - Real-time events
5. **[Examples Gallery](/examples/)** - Working code samples
