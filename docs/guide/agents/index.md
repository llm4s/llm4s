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

The agent runtime is the `llm4s-agent` module, alongside `llm4s-core` and a provider module:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % llm4sVersion
```

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
  case Right(thread) => println(thread.answer.getOrElse("(no answer)"))
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
  state2 <- agent.continueConversation(state1, "How does it compare to Java?", tools)

  // Another follow-up
  state3 <- agent.continueConversation(state2, "What about performance?", tools)
} yield state3
```

---

## Safety Defaults

- **Agent step limit**: `Agent.run(...)` defaults to `maxSteps = Some(50)` - at most 50 model calls - to prevent infinite loops. Pass `maxSteps = None` to allow unlimited steps. A turn that hits the limit ends as `ThreadStatus.Failed("Maximum step limit reached")`, and `agent.recover(thread, tools)` takes it further.
- **HTTPTool methods**: `HttpConfig()` defaults to `GET` and `HEAD` only. Use `HttpConfig.withWriteMethods()` or `HttpConfig().withAllMethods` to allow write methods.

---

## Core Concepts

### Agent Thread

The `AgentThread` is an immutable, data-only record of a conversation. It replaces `AgentState` and holds:

- **Conversation history** - All messages exchanged (`messages`)
- **Status** - `ThreadStatus.Completed`, `Failed(error)` or `Suspended(on)`
- **System message** - Instructions for the LLM
- **Completion options** - Temperature, max tokens, etc.
- **Usage** - Tokens and cost of every model call so far

It holds no tools, handoffs, guardrails or clients - those are live objects, supplied again on every call - so a thread serializes with `AgentThread.toJson` and continues in any `Agent`.

```scala
// A thread is immutable - setters return new threads
val edited = thread.withMessages(thread.messages :+ UserMessage("Follow-up question"))
```

### Agent Lifecycle

An `Agent` turn runs on the graph runtime: the model is called, the tool calls it asks for run (in parallel when
`AgentContext.toolExecutionStrategy` says so), and the model is called again until it answers without tool calls.

```
Query --> model call --> tool calls? --yes--> run tools --> model call ...
                              |
                              no
                              v
                          Completed
```

A turn that needs a human decision ends `Suspended`; answer it with `agent.resume(thread, answers, tools)`. A turn that
failed part-way (a recoverable error, the step limit, a cancellation) is continued with `agent.recover(thread, tools)`;
work that completed is not run again.

### Tool Execution Strategies

Control how multiple tool calls are executed:

```scala
import org.llm4s.agent.AgentContext
import org.llm4s.toolapi.ToolExecutionStrategy

// Sequential (default) - one at a time, safest
agent.run(query, tools)

// Parallel - all at once, fastest
agent.run(query, tools, context = AgentContext(toolExecutionStrategy = ToolExecutionStrategy.Parallel))

// Parallel with limit - balance speed and resources
agent.run(
  query,
  tools,
  context = AgentContext(toolExecutionStrategy = ToolExecutionStrategy.ParallelWithLimit(3))
)
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
    Handoff.to("physics", physicsAgent, "Physics expertise required")
  )
)
```

[Learn more about handoffs →](handoffs)

### [Streaming Events](streaming)

Real-time execution feedback. `Agent.runWithEvents` is not available while the agent loop moves onto the graph
runtime; the event stream returns on the runtime's run events (#1329). The page describes the event vocabulary.

[Learn more about streaming →](streaming)

---

## Built-in Tools

LLM4S provides pre-built tools for common tasks, in the `llm4s-agent-tools` module (they work with
plain tool calling through `ToolRegistry` too, without an `Agent`):

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-tools" % llm4sVersion
```

The search tools read their settings with `ToolsConfigLoader` - for example
`ToolsConfigLoader.loadBraveSearchTool()` - from `llm4s.tools.*`, whose `BRAVE_SEARCH_*` and
`EXA_*` bindings ship in that module.

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
// Save the thread to disk (conversation, system message, options, usage - no tools)
AgentThread.saveToFile(thread, "/tmp/conversation.json")

// Load and resume
val result = for {
  loaded  <- AgentThread.loadFromFile("/tmp/conversation.json")
  resumed <- agent.continueConversation(loaded, "Continue our conversation", tools)
} yield resumedState
```

---

## Reasoning Modes

Enable extended thinking for complex problems:

```scala
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}

val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .withMaxTokens(4096)

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
