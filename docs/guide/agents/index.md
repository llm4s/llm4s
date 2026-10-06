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
- **Streaming** - Real-time events for responsive UIs (the agent event stream returns in [#1329](https://github.com/llm4s/llm4s/issues/1329))
- **Orchestration** - Multi-agent workflows with DAG execution

## Quick Start

The agent runtime is the `llm4s-agent` module, alongside `llm4s-core` and a provider module:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % llm4sVersion
```

### Basic Agent

An agent is built once - tools, guardrails, handoffs and middleware belong to it - and run by thread.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.agent.Agent

// Create an agent and run a query
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client).build()
  result <- agent.run("What is the capital of France?")
} yield result

result match {
  case Right(r) => println(r.answer.getOrElse(s"Run ended: ${r.status}"))
  case Left(error) => println(s"Error: $error")
}
```

`Agent.builder(id, client)` takes the agent's id (letters, digits, `_` and `-`, up to 52) and an
`LLMClient`. `build()` returns a `Result[Agent]` and refuses an invalid agent: clashing tool names,
a bad or duplicate handoff id, or `withMaxSteps` below 1.

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

// Give the agent its tools when you build it
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client)
    .withTools(new ToolRegistry(Seq(weatherTool)))
    .build()
  result <- agent.run("What's the weather in Paris?")
} yield result
```

`withTools` takes a `ToolRegistry` (an `MCPToolRegistry` too) or a `ToolSet` of `AgentTool`s.

### Multi-Turn Conversations

A conversation is carried by its thread. `continueConversation` runs the next turn on the thread
of a previous result:

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client).build()

  // First turn
  result1 <- agent.run("Tell me about Scala")

  // Follow-up (preserves context)
  result2 <- agent.continueConversation(result1, "How does it compare to Java?")

  // Another follow-up
  result3 <- agent.continueConversation(result2, "What about performance?")
} yield result3
```

To name the thread yourself, use `agent.run(threadId, query)`; `agent.runMultiTurn(first, followUps)`
runs several turns on one thread and stops at the first result that is not `Completed`.

### Handling the Result

`run` returns `Result[AgentResult]`. `Left` is a failure: a provider, tool or middleware error is a
`GraphError`, and a thread that is busy or still has pending work is refused. A run that ended in a
defined way is `Right`, with a status:

```scala
result.map { r =>
  r.status match {
    case AgentStatus.Completed(answer)         => println(answer)
    case AgentStatus.Blocked(guardrail, why)   => println(s"Blocked by $guardrail: $why")
    case AgentStatus.StepLimitReached          => println("Hit the step limit")
    case AgentStatus.Suspended(approvals, qs)  => println("Waiting for a person")
  }
}
```

`AgentResult` also carries `threadId`, `runId`, `activeAgent`, `messages` (the thread's full
history, never a system prompt) and `usage`.

---

## Safety Defaults

- **Agent step limit**: an agent makes at most `maxSteps` model calls per turn, `Agent.DefaultMaxSteps` (50) unless you call `withMaxSteps(n)`. The count is shared by every agent a turn hands off to; at the limit the result is `AgentStatus.StepLimitReached`.
- **HTTPTool methods**: `HttpConfig()` defaults to `GET` and `HEAD` only. Use `HttpConfig.withWriteMethods()` or `HttpConfig().withAllMethods` to allow write methods.

---

## Core Concepts

### Threads and Results

A conversation lives in a thread on a `GraphRuntime` (in memory unless you call `withRuntime`). The
thread holds the messages, the active agent and the usage; it holds no tools, guardrails or agents,
which belong to the `Agent`. `AgentResult` is a value to read, not something to pass back in.

- `agent.start(threadId, query)` returns an `AgentRun` at once, with `await()` and `cancel()`;
  `run` is `start` followed by `await`.
- A run that failed or was cancelled leaves its thread recoverable: `agent.recover(threadId)`
  re-runs only the work that did not finish.
- A run that suspended for approval (`AgentStatus.Suspended`) continues with
  `agent.resume(threadId, answers)`, building each answer with `result.approve(id)`,
  `result.reject(id, reason)`, `result.edit(id, arguments)` or `result.reply(id, value)`.
- A thread stays in the runtime until `agent.forget(threadId)` removes it - one-shot
  `agent.run(query)` threads too, which on the default in-memory runtime means they stay in memory.
  Forget a conversation you will not continue: `agent.run(query).flatMap(r => agent.forget(r.threadId).map(_ => r))`.
  `forget` refuses a thread whose run is still active (`ThreadBusy`) or that belongs to another
  tenant (`TenantMismatch`); an unknown thread is `Right(())`.

### Agent Lifecycle

```
Query
  |
  v
input node (input guardrails) ---> Blocked (nothing stored)
  |
  v
model <---------- tool results
  |
  +-- tool calls --> call-tool (parallel, bounded) --> collect --> model
  +-- handoff -----> the target agent's model
  |
  v
finish node (output guardrails) ---> Completed | Blocked (turn removed)
```

### Parallel Tool Calls

The tool calls of one model message run in parallel, bounded by `RunBudgets.maxConcurrency`,
which you set through the `RunConfig` you pass to `run`.

`ToolExecutionStrategy` remains in core as a `ToolRegistry` feature; the agent no longer takes one.

---

## Features

### [Guardrails](guardrails)

Validate inputs and outputs for safety. Guardrails are middleware on the agent:

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._

val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new GuardrailMiddleware(
      input = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
      output = Seq(new JSONValidator())
    )
  )
  .build()
```

A blocked run ends `AgentStatus.Blocked(guardrail, reason)`, the blocked turn removed from the thread, which stays usable.

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

val physics = Agent.builder("physics", client)
  .withSystemPrompt("You are a physicist")

val agent = Agent.builder("triage", client)
  .withHandoffs(Handoff.to("physics", physics, "Physics expertise required"))
  .build()
```

[Learn more about handoffs →](handoffs)

### [Streaming Events](streaming)

The agent event stream (`runWithEvents`, `AgentEvent`) was removed with the move to the graph
runtime; its replacement - subscription to a run's events and token streaming - is
[#1329](https://github.com/llm4s/llm4s/issues/1329).

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

Handle long conversations with `ContextWindowMiddleware`. It prunes what is sent to the model on
each call; the thread keeps its full history:

```scala
import org.llm4s.agent.{ContextWindowConfig, PruningStrategy}
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

val config = ContextWindowConfig(
  maxMessages = Some(20),
  preserveSystemMessage = true,
  minRecentTurns = 2,
  pruningStrategy = PruningStrategy.OldestFirst
)

val agent = Agent.builder("assistant", client)
  .withMiddleware(new ContextWindowMiddleware(config))
  .build()
```

The current turn (the latest user message onward) is never pruned, a tool call is never separated
from its result, and the request always starts with a user message - so a request may exceed the
budget. The system prompt is outside the budget.

**Pruning Strategies:**

| Strategy | Behavior |
|----------|----------|
| `OldestFirst` | Remove oldest messages first (FIFO) |
| `MiddleOut` | Keep first and last messages, remove middle |
| `RecentTurnsOnly(n)` | Keep only the last N conversation turns |
| `Custom(fn)` | User-defined pruning function |

---

## Conversation Persistence

A thread lives in the runtime. To keep a conversation across processes, save the result's messages
and import them as the `history` of a new thread:

```scala
import upickle.default.{read, write}

// Save the messages of a completed run
Files.writeString(path, write(result.messages))

// Later: load them and continue on a new thread
val saved = read[Vector[Message]](Files.readString(path))
val next = agent.run(ThreadId(java.util.UUID.randomUUID().toString), "Continue our conversation", RunConfig(), saved)
```

`history` is accepted only on a thread that does not exist yet, and may not contain system messages
(prompts belong to the agents). Save only runs that completed; `history` that ends mid tool call is
refused.

---

## Reasoning Modes

Enable extended thinking for complex problems:

```scala
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}

val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .withMaxTokens(4096)

// Use with agent
Agent.builder("assistant", client).withCompletionOptions(options).build()
```

Supported by OpenAI o1/o3 and Anthropic Claude models.

---

## Examples

| Example | Description |
|---------|-------------|
| [SingleStepAgentExample](/examples/#single-step) | A run with one tool call |
| [MultiStepAgentExample](/examples/#multi-step) | Complete execution flow |
| [MultiTurnConversationExample](/examples/#multi-turn) | Functional multi-turn API |
| [LongConversationExample](/examples/#long-conversation) | Context window pruning |
| [ConversationPersistenceExample](/examples/#persistence) | Save and resume |
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
- [Typed Agent Runtime §4.13](/design/typed-agent-runtime-design) - How `Agent` runs on the graph runtime
- [Phase 2.1: Streaming](/design/phase-2.1-streaming-events) - Event system

---

## Next Steps

1. **[Guardrails Guide](guardrails)** - Input/output validation
2. **[Memory Guide](memory)** - Persistent context
3. **[Handoffs Guide](handoffs)** - Agent delegation
4. **[Streaming Guide](streaming)** - Real-time events
5. **[Examples Gallery](/examples/)** - Working code samples
