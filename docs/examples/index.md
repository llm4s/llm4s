---
layout: page
title: Examples
nav_order: 4
has_children: true
---

# Example Gallery
{: .no_toc }

Explore **70 working examples** covering all LLM4S features.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Quick Navigation

| Category | Count | Description |
|----------|-------|-------------|
| [Basic Examples](#basic-examples) | 9 | Getting started, streaming, tracing |
| [Agent Examples](#agent-examples) | 8 | Multi-turn agents, persistence, async tools |
| [Tool Examples](#tool-examples) | 7 | Tool calling, built-in tools, parallel execution |
| [Guardrails Examples](#guardrails-examples) | 7 | Input/output validation, LLM-as-Judge |
| [Handoff Examples](#handoff-examples) | 3 | Agent-to-agent delegation |
| [Memory Examples](#memory-examples) | 6 | Short/long-term memory, vector search, RAG |
| [Streaming Examples](#streaming-examples) | 4 | Real-time responses, agent events |
| [Reasoning Examples](#reasoning-examples) | 1 | Extended thinking modes |
| [Context Management](#context-management) | 8 | Token windows, compression |
| [Embeddings](#embeddings) | 5 | Vector search, RAG |
| [RAG in a Box](#rag-in-a-box) | - | Production RAG server (external project) |
| [MCP Examples](#mcp-examples) | 3 | Model Context Protocol |
| [Model Examples](#model-examples) | 1 | Model metadata and capabilities |
| [Other Examples](#other-examples) | 8 | Speech, actions, utilities |

---

## Basic Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/basic/`

### BasicLLMCallingExample {#basic-llm-calling}

**File:** [`BasicLLMCallingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/BasicLLMCallingExample.scala)

Simple multi-turn conversations demonstrating system, user, and assistant messages.

```bash
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

**What it demonstrates:**
- Creating a conversation with multiple message types
- System message for setting assistant behavior
- Multi-turn context with AssistantMessage
- Token usage tracking
- Error handling with Result types

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/BasicLLMCallingExample.scala)

---

### StreamingExample {#streaming}

**File:** [`StreamingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/StreamingExample.scala)

Compare streaming vs non-streaming responses with performance metrics.

```bash
sbt "samples/runMain org.llm4s.samples.basic.StreamingExample"
```

**What it demonstrates:**
- Real-time token-by-token output
- Performance comparison (streaming vs batch)
- Chunk processing
- Measuring response times

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/StreamingExample.scala)

---

### AdvancedStreamingExample

**File:** [`AdvancedStreamingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/AdvancedStreamingExample.scala)

More complex streaming patterns with error handling and state management.

```bash
sbt "samples/runMain org.llm4s.samples.basic.AdvancedStreamingExample"
```

**What it demonstrates:**
- Advanced error handling during streaming
- State management across chunks
- Progress tracking
- Stream termination handling

---

### BasicLLMCallingWithTrace

**File:** [`BasicLLMCallingWithTrace.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/BasicLLMCallingWithTrace.scala)

Basic LLM calls with integrated tracing for observability.

```bash
# Configure tracing
export TRACING_MODE=console
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingWithTrace"
```

**What it demonstrates:**
- Console tracing integration
- Token usage tracking
- Request/response logging
- Performance metrics

---

### EnhancedTracingExample

**File:** [`EnhancedTracingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/EnhancedTracingExample.scala)

Advanced tracing with detailed token usage and agent state tracking.

```bash
export TRACING_MODE=console
sbt "samples/runMain org.llm4s.samples.basic.EnhancedTracingExample"
```

**What it demonstrates:**
- Detailed trace information
- Agent state tracking
- Token usage analysis
- Multi-level tracing

---

### LangfuseSampleTraceRunner

**File:** [`LangfuseSampleTraceRunner.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/LangfuseSampleTraceRunner.scala)

Production-grade tracing with Langfuse backend.

```bash
export TRACING_MODE=langfuse
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
sbt "samples/runMain org.llm4s.samples.basic.LangfuseSampleTraceRunner"
```

**What it demonstrates:**
- Langfuse integration
- Production observability
- Trace persistence
- Analytics dashboard

[Learn more about Langfuse →](https://langfuse.com)

---

### OllamaExample {#ollama}

**File:** [`OllamaExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/OllamaExample.scala)

Using local Ollama models instead of cloud providers.

```bash
# Start Ollama
ollama serve &
ollama pull llama2

# Run example
export LLM_MODEL=ollama/llama2
export OLLAMA_BASE_URL=http://localhost:11434
sbt "samples/runMain org.llm4s.samples.basic.OllamaExample"
```

**What it demonstrates:**
- Local model execution
- No API key required
- Provider flexibility
- Cost-free development

---

### OllamaStreamingExample

**File:** [`OllamaStreamingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/OllamaStreamingExample.scala)

Streaming responses with local Ollama models.

```bash
export LLM_MODEL=ollama/llama2
sbt "samples/runMain org.llm4s.samples.basic.OllamaStreamingExample"
```

**What it demonstrates:**
- Local streaming
- Ollama-specific features
- Performance characteristics

---

### AgentLLMCallingExample

**File:** [`AgentLLMCallingExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/AgentLLMCallingExample.scala)

Making LLM calls from within an agent context.

```bash
sbt "samples/runMain org.llm4s.samples.basic.AgentLLMCallingExample"
```

**What it demonstrates:**
- Agent-based LLM calls
- Context management
- Agent state tracking

---
### ProviderFallbackExample

**File:** [`ProviderFallbackExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/ProviderFallbackExample.scala)

LLM calls with automatic provider fallback for reliability.

```bash
sbt "samples/runMain org.llm4s.samples.basic.ProviderFallbackExample"
```
**What it demonstrates:**
- Multiple provider configurations
- Automatic fallback on failure
- Enhanced reliability

---



## Agent Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/agent/`

### SingleStepAgentExample {#single-step}

**File:** [`SingleStepAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/SingleStepAgentExample.scala)

Step-by-step agent execution with detailed debugging output.

```bash
sbt "samples/runMain org.llm4s.samples.agent.SingleStepAgentExample"
```

**What it demonstrates:**
- Manual control over agent execution
- Debugging agent behavior
- Step-by-step tool calling
- State inspection

**Perfect for:** Understanding how agents work internally

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/SingleStepAgentExample.scala)

---

### MultiStepAgentExample {#multi-step}

**File:** [`MultiStepAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/MultiStepAgentExample.scala)

Complete agent execution from start to finish with automatic tool calling.

```bash
sbt "samples/runMain org.llm4s.samples.agent.MultiStepAgentExample"
```

**What it demonstrates:**
- Automatic agent execution
- Tool calling loop
- Conversation completion
- Final response generation

**Perfect for:** Production-ready agent patterns

---

### MultiTurnConversationExample {#multi-turn}

**File:** [`MultiTurnConversationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/MultiTurnConversationExample.scala)

Functional, immutable multi-turn conversation API (Phase 1.1).

```bash
sbt "samples/runMain org.llm4s.samples.agent.MultiTurnConversationExample"
```

**What it demonstrates:**
- `continueConversation()` pattern
- Immutable state management
- No `var` or mutation
- Clean functional style

**Key code:**
```scala
val state2 = agent.continueConversation(state1, "Follow-up question")
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/MultiTurnConversationExample.scala)

---

### LongConversationExample {#long-conversation}

**File:** [`LongConversationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/LongConversationExample.scala)

Long conversations with automatic context window pruning.

```bash
sbt "samples/runMain org.llm4s.samples.agent.LongConversationExample"
```

**What it demonstrates:**
- `runMultiTurn()` helper method
- Automatic token management
- Context window pruning strategies
- Memory-efficient conversations

**Key code:**
```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.OldestFirst
)
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/LongConversationExample.scala)

---

### ConversationPersistenceExample {#persistence}

**File:** [`ConversationPersistenceExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/ConversationPersistenceExample.scala)

Save and load agent state for resumable conversations.

```bash
sbt "samples/runMain org.llm4s.samples.agent.ConversationPersistenceExample"
```

**What it demonstrates:**
- Saving conversation state to disk
- Loading and resuming conversations
- JSON serialization
- Session management

**Key code:**
```scala
AgentState.saveToFile(state, "/tmp/conversation.json")
val loadedState = AgentState.loadFromFile("/tmp/conversation.json", tools)
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/ConversationPersistenceExample.scala)

---

### MCPAgentExample

**File:** [`MCPAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/MCPAgentExample.scala)

Agents with Model Context Protocol (MCP) tool integration.

```bash
sbt "samples/runMain org.llm4s.samples.agent.MCPAgentExample"
```

**What it demonstrates:**
- MCP tool integration in agents
- External tool servers
- Protocol fallback handling

---

### AsyncToolAgentExample

**File:** [`AsyncToolAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/AsyncToolAgentExample.scala)

Agent with parallel tool execution using different strategies.

```bash
sbt "samples/runMain org.llm4s.samples.agent.AsyncToolAgentExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/AsyncToolAgentExample.scala)

---

### BuiltinToolsAgentExample

**File:** [`BuiltinToolsAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/BuiltinToolsAgentExample.scala)

Agent using built-in tools (DateTime, Calculator, web search, etc.).

```bash
sbt "samples/runMain org.llm4s.samples.agent.BuiltinToolsAgentExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/BuiltinToolsAgentExample.scala)

---

## Tool Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/toolapi/`

### WeatherToolExample {#weather}

**File:** [`WeatherToolExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/WeatherToolExample.scala)

Simple tool definition and execution.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.WeatherToolExample"
```

**What it demonstrates:**
- Basic tool creation with ToolFunction
- Parameter schema definition
- Tool execution
- Return value handling

**Key code:**
```scala
val weatherTool = ToolFunction(
  name = "get_weather",
  description = "Get current weather for a location",
  function = getWeather _
)
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/WeatherToolExample.scala)

---

### MultiToolExample {#multi-tool}

**File:** [`MultiToolExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/MultiToolExample.scala)

Multiple tools with different parameter types.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.MultiToolExample"
```

**What it demonstrates:**
- Calculator tool
- Search tool
- Multiple tools in one registry
- Tool precedence and selection

**Key code:**
```scala
val tools = new ToolRegistry(Seq(calculatorTool, searchTool))
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/MultiToolExample.scala)

---

### ErrorMessageDemonstration

**File:** [`ErrorMessageDemonstration.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/ErrorMessageDemonstration.scala)

Error handling in tool execution with helpful messages.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.ErrorMessageDemonstration"
```

**What it demonstrates:**
- Tool validation errors
- Helpful error messages
- Error recovery patterns

---

### ImprovedErrorMessageDemo

**File:** [`ImprovedErrorMessageDemo.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/ImprovedErrorMessageDemo.scala)

Enhanced error reporting for better debugging.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.ImprovedErrorMessageDemo"
```

**What it demonstrates:**
- Detailed error context
- Stack traces
- Debugging information

---

### BuiltinToolsExample

**File:** [`BuiltinToolsExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/BuiltinToolsExample.scala)

Using the built-in tools library (DateTime, Calculator, UUID, JSON, HTTP, etc.).

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.BuiltinToolsExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/BuiltinToolsExample.scala)

---

### ParallelToolExecutionExample

**File:** [`ParallelToolExecutionExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/ParallelToolExecutionExample.scala)

Executing multiple tool calls in parallel with different strategies.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.ParallelToolExecutionExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/ParallelToolExecutionExample.scala)

---

## Guardrails Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/guardrails/`

### BasicInputValidationExample {#basic}

**File:** [`BasicInputValidationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/BasicInputValidationExample.scala)

Basic input validation with built-in guardrails.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.BasicInputValidationExample"
```

**What it demonstrates:**
- LengthCheck guardrail for input size validation
- ProfanityFilter for content filtering
- Declarative validation before agent processing
- Clear error messages for validation failures

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/BasicInputValidationExample.scala)

---

### CustomGuardrailExample {#custom}

**File:** [`CustomGuardrailExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/CustomGuardrailExample.scala)

Build custom guardrails for application-specific validation.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.CustomGuardrailExample"
```

**What it demonstrates:**
- Implementing custom InputGuardrail trait
- Keyword requirement validation
- Reusable validation logic
- Testing validation success and failure cases

**Key code:**
```scala
class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  def validate(value: String): Result[String] = {
    // Custom validation logic
  }
}
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/CustomGuardrailExample.scala)

---

### CompositeGuardrailExample {#composite}

**File:** [`CompositeGuardrailExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/CompositeGuardrailExample.scala)

Combine multiple guardrails with different composition strategies.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.CompositeGuardrailExample"
```

**What it demonstrates:**
- Sequential composition (all must pass in order)
- All composition (all must pass, run in parallel)
- Any composition (at least one must pass)
- Error accumulation and reporting

**Key code:**
```scala
val allGuardrails = CompositeGuardrail.all(Seq(
  LengthCheck(min = 10, max = 1000),
  ProfanityFilter(),
  customGuardrail
))
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/CompositeGuardrailExample.scala)

---

### JSONOutputValidationExample

**File:** [`JSONOutputValidationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/JSONOutputValidationExample.scala)

Validate LLM outputs are valid JSON.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.JSONOutputValidationExample"
```

**What it demonstrates:**
- Output guardrails (run after LLM response)
- JSON format validation
- Structured output enforcement
- Integration with agent workflows

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/JSONOutputValidationExample.scala)

---

### MultiTurnToneValidationExample

**File:** [`MultiTurnToneValidationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/MultiTurnToneValidationExample.scala)

Validate conversational tone across multiple turns.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.MultiTurnToneValidationExample"
```

**What it demonstrates:**
- ToneValidator for output validation
- Maintaining consistent tone
- Multi-turn conversation with guardrails
- Professional/friendly tone enforcement

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/MultiTurnToneValidationExample.scala)

---

### FactualityGuardrailExample

**File:** [`FactualityGuardrailExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/FactualityGuardrailExample.scala)

LLM-as-Judge guardrail for validating factual accuracy of responses.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.FactualityGuardrailExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/FactualityGuardrailExample.scala)

---

### LLMJudgeGuardrailExample

**File:** [`LLMJudgeGuardrailExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/LLMJudgeGuardrailExample.scala)

Using LLM-as-Judge for content safety, quality, and tone validation.

```bash
sbt "samples/runMain org.llm4s.samples.guardrails.LLMJudgeGuardrailExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/guardrails/LLMJudgeGuardrailExample.scala)

---

## Handoff Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/handoff/`

### SimpleTriageHandoffExample

**File:** [`SimpleTriageHandoffExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/SimpleTriageHandoffExample.scala)

Basic agent-to-agent handoff for routing queries to specialists.

```bash
sbt "samples/runMain org.llm4s.samples.handoff.SimpleTriageHandoffExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/SimpleTriageHandoffExample.scala)

---

### MathSpecialistHandoffExample

**File:** [`MathSpecialistHandoffExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/MathSpecialistHandoffExample.scala)

Handoff to a math specialist agent for complex calculations.

```bash
sbt "samples/runMain org.llm4s.samples.handoff.MathSpecialistHandoffExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/MathSpecialistHandoffExample.scala)

---

### ContextPreservationExample

**File:** [`ContextPreservationExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/ContextPreservationExample.scala)

Preserving conversation context when handing off between agents.

```bash
sbt "samples/runMain org.llm4s.samples.handoff.ContextPreservationExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/handoff/ContextPreservationExample.scala)

---

## Memory Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/memory/`

### BasicMemoryExample

**File:** [`BasicMemoryExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/BasicMemoryExample.scala)

Getting started with the memory system for recording facts and retrieving context.

```bash
sbt "samples/runMain org.llm4s.samples.memory.BasicMemoryExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/BasicMemoryExample.scala)

---

### ConversationMemoryExample

**File:** [`ConversationMemoryExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/ConversationMemoryExample.scala)

Using memory to maintain context across conversation turns.

```bash
sbt "samples/runMain org.llm4s.samples.memory.ConversationMemoryExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/ConversationMemoryExample.scala)

---

### MemoryWithAgentExample

**File:** [`MemoryWithAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/MemoryWithAgentExample.scala)

Integrating memory with agent workflows for personalized responses.

```bash
sbt "samples/runMain org.llm4s.samples.memory.MemoryWithAgentExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/MemoryWithAgentExample.scala)

---

### SQLiteMemoryExample

**File:** [`SQLiteMemoryExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/SQLiteMemoryExample.scala)

Persistent memory storage using SQLite backend.

```bash
sbt "samples/runMain org.llm4s.samples.memory.SQLiteMemoryExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/SQLiteMemoryExample.scala)

---

### VectorMemoryExample

**File:** [`VectorMemoryExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/VectorMemoryExample.scala)

Semantic memory search using embeddings and vector store.

```bash
sbt "samples/runMain org.llm4s.samples.memory.VectorMemoryExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/memory/VectorMemoryExample.scala)

---

### DocumentQAExample (RAG)

**File:** [`DocumentQAExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/rag/DocumentQAExample.scala)

Complete RAG (Retrieval-Augmented Generation) pipeline demonstrating document Q&A with semantic search.

```bash
# With mock embeddings (no API key needed for embeddings)
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
sbt "samples/runMain org.llm4s.samples.rag.DocumentQAExample"

# With real OpenAI embeddings (unified format)
export EMBEDDING_MODEL=openai/text-embedding-3-small
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
sbt "samples/runMain org.llm4s.samples.rag.DocumentQAExample"
```

**What it demonstrates:**
- Document loading and text extraction
- Text chunking with configurable size and overlap
- Embedding generation (mock or real via OpenAI/VoyageAI)
- Vector storage with SQLite backend
- Semantic similarity search
- RAG prompt construction with context
- Answer generation with source citations

**Key code:**
```scala
// Ingest documents
val chunks = ChunkingUtils.chunkText(text, chunkSize = 800, overlap = 150)
store.store(Memory.fromKnowledge(chunk, source = fileName))

// Query with semantic search
val results = store.search(query, topK = 4)
val answer = client.complete(buildRAGPrompt(query, results))
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/rag/DocumentQAExample.scala)

---

## Context Management

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/context/`

All 8 context management examples demonstrate advanced token window management, compression, and optimization strategies.

### ContextPipelineExample {#context-pipeline}

End-to-end context management pipeline with compaction and squeezing.

```bash
sbt "samples/runMain org.llm4s.samples.context.ContextPipelineExample"
```

[View all context examples →](context)

---

## Embeddings

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/embeddingsupport/`

### EmbeddingExample {#embedding-example}

Complete embedding pipeline with similarity search and visualization.

```bash
sbt "samples/runMain org.llm4s.samples.embeddingsupport.EmbeddingExample"
```

**What it demonstrates:**
- Creating embeddings from text
- Similarity scoring
- Vector search
- Result visualization
- Chunking and preprocessing

[View all embedding examples →](embeddings)

---

### S3LoaderExample {#s3-loader}

**File:** [`S3LoaderExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/rag/S3LoaderExample.scala)

Load and ingest documents from AWS S3 buckets with full PDF/DOCX support.

```bash
# First, set AWS credentials
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret

sbt "samples/runMain org.llm4s.samples.rag.S3LoaderExample"
```

**What it demonstrates:**
- Loading documents from S3 buckets
- PDF and DOCX extraction from cloud storage
- Incremental sync with change detection via ETags
- Using S3Loader with RAG.sync()
- LocalStack testing setup

**Key Code:**
```scala
import org.llm4s.rag.loader.s3.S3Loader

// Load all supported documents from S3
val loader = S3Loader(
  bucket = "my-documents",
  prefix = "docs/",
  region = "us-east-1"
)

// Sync with change detection
rag.sync(loader) match {
  case Right(stats) =>
    println(s"Added: ${stats.added}, Updated: ${stats.updated}")
  case Left(err) =>
    println(s"Error: ${err.message}")
}
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/rag/S3LoaderExample.scala)

---

## RAG in a Box

**Repository:** [github.com/llm4s/rag_in_a_box](https://github.com/llm4s/rag_in_a_box)

RAG in a Box is a production-ready RAG server built on the LLM4S framework. It provides a complete solution for document ingestion, semantic search, and AI-powered question answering.

**Key Features:**
- REST API for document management and querying
- Multi-format document support (text, markdown, PDF, URLs)
- Configurable chunking strategies (simple, sentence, markdown, semantic)
- Hybrid search with RRF fusion (vector + keyword)
- Vue.js admin dashboard with document browser and analytics
- Docker Compose and Kubernetes deployment options
- JWT authentication, Prometheus metrics, health checks

**Current Status:**
- 194 backend tests, 8 frontend E2E specs
- Security scanning (OWASP, Anchore)
- Comprehensive documentation

[View RAG in a Box →](https://github.com/llm4s/rag_in_a_box)

---

## MCP Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/mcp/`

### MCPToolExample {#mcp-tool}

Basic MCP tool usage with automatic protocol fallback.

```bash
sbt "samples/runMain org.llm4s.samples.mcp.MCPToolExample"
```

**What it demonstrates:**
- MCP server connection
- Tool discovery
- Protocol handling (stdio, HTTP, SSE)
- Tool execution

[View all MCP examples →](mcp)

---

## Streaming Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/streaming/`

### BasicStreamingExample

Fundamental streaming with chunk processing.

```bash
sbt "samples/runMain org.llm4s.samples.streaming.BasicStreamingExample"
```

### StreamingWithProgressExample

Streaming with real-time progress feedback.

```bash
sbt "samples/runMain org.llm4s.samples.streaming.StreamingWithProgressExample"
```

### StreamingAgentExample

**File:** [`StreamingAgentExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/streaming/StreamingAgentExample.scala)

Agent with real-time event streaming using `runWithEvents()`.

```bash
sbt "samples/runMain org.llm4s.samples.streaming.StreamingAgentExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/streaming/StreamingAgentExample.scala)

### EventCollectionExample

**File:** [`EventCollectionExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/streaming/EventCollectionExample.scala)

Collecting and processing agent execution events.

```bash
sbt "samples/runMain org.llm4s.samples.streaming.EventCollectionExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/streaming/EventCollectionExample.scala)

---

## Reasoning Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/reasoning/`

### ReasoningModesExample

**File:** [`ReasoningModesExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/reasoning/ReasoningModesExample.scala)

Using extended thinking/reasoning modes with OpenAI o1/o3 and Anthropic Claude.

```bash
sbt "samples/runMain org.llm4s.samples.reasoning.ReasoningModesExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/reasoning/ReasoningModesExample.scala)

---

## Model Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/model/`

### ModelMetadataExample

**File:** [`ModelMetadataExample.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/model/ModelMetadataExample.scala)

Querying model capabilities, pricing, and context limits.

```bash
sbt "samples/runMain org.llm4s.samples.model.ModelMetadataExample"
```

[View source →](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/model/ModelMetadataExample.scala)

---

## Other Examples

### Interactive Assistant

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/assistant/`

**AssistantAgentExample** - Interactive terminal assistant with session management.

```bash
sbt "samples/runMain org.llm4s.samples.assistant.AssistantAgentExample"
```

Commands: `/help`, `/new`, `/save`, `/sessions`, `/quit`

---

### Speech

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/`

**SpeechSamples** - Speech-to-text (Vosk, Whisper) and text-to-speech integration.

```bash
sbt "samples/runMain org.llm4s.samples.SpeechSamples"
```

---

### Actions

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/actions/`

**SummarizationExample** - Text summarization workflow.

```bash
sbt "samples/runMain org.llm4s.samples.actions.SummarizationExample"
```

---

## Running Examples

### Basic Run Command

```bash
sbt "samples/runMain <fully-qualified-class-name>"
```

### With Environment Variables

```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

### Browse Source

All examples are in the [samples directory](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples) on GitHub.

---

## Learning Paths

### Beginner Path
1. [BasicLLMCallingExample](#basic-llm-calling)
2. [StreamingExample](#streaming)
3. [WeatherToolExample](#weather)
4. [SingleStepAgentExample](#single-step)

### Intermediate Path
1. [MultiTurnConversationExample](#multi-turn)
2. [MultiToolExample](#multi-tool)
3. [LongConversationExample](#long-conversation)
4. [ConversationPersistenceExample](#persistence)

### Advanced Path
1. [ContextPipelineExample](#context-pipeline)
2. [EmbeddingExample](#embedding-example)
3. [MCPToolExample](#mcp-tool)
4. Interactive Assistant

---

## Next Steps

- **[User Guide](../guide/basic-usage)** - Learn concepts in depth
- **[API Reference](/api/llm-client)** - Detailed API documentation
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help and share projects

---

**Found a useful example pattern?** Share it in our [Discord](https://discord.gg/4uvTPn6qww)!
