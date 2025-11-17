---
layout: page
title: Examples
nav_order: 4
has_children: true
---

# Example Gallery
{: .no_toc }

Explore **50+ working examples** covering all LLM4S features.
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
| [Agent Examples](#agent-examples) | 6 | Multi-turn agents, persistence |
| [Tool Examples](#tool-examples) | 5 | Tool calling, MCP integration |
| [Guardrails Examples](#guardrails-examples) | 5 | Input/output validation, safety |
| [Context Management](#context-management) | 8 | Token windows, compression |
| [Embeddings](#embeddings) | 5 | Vector search, RAG |
| [MCP Examples](#mcp-examples) | 3 | Model Context Protocol |
| [Streaming](#streaming) | 2 | Real-time responses |
| [Other Examples](#other-examples) | 8 | Speech, actions, utilities |

---

## Basic Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/basic/`

### BasicLLMCallingExample {#basic-llm-calling}

**File:** `BasicLLMCallingExample.scala`

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

**File:** `StreamingExample.scala`

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

**File:** `AdvancedStreamingExample.scala`

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

**File:** `BasicLLMCallingWithTrace.scala`

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

**File:** `EnhancedTracingExample.scala`

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

**File:** `LangfuseSampleTraceRunner.scala`

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

**File:** `OllamaExample.scala`

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

**File:** `OllamaStreamingExample.scala`

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

**File:** `AgentLLMCallingExample.scala`

Making LLM calls from within an agent context.

```bash
sbt "samples/runMain org.llm4s.samples.basic.AgentLLMCallingExample"
```

**What it demonstrates:**
- Agent-based LLM calls
- Context management
- Agent state tracking

---

## Agent Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/agent/`

### SingleStepAgentExample {#single-step}

**File:** `SingleStepAgentExample.scala`

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

**File:** `MultiStepAgentExample.scala`

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

**File:** `MultiTurnConversationExample.scala`

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

**File:** `LongConversationExample.scala`

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

**File:** `ConversationPersistenceExample.scala`

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

**File:** `MCPAgentExample.scala`

Agents with Model Context Protocol (MCP) tool integration.

```bash
sbt "samples/runMain org.llm4s.samples.agent.MCPAgentExample"
```

**What it demonstrates:**
- MCP tool integration in agents
- External tool servers
- Protocol fallback handling

---

## Tool Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/toolapi/`

### WeatherToolExample {#weather}

**File:** `WeatherToolExample.scala`

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

### LLMWeatherExample {#llm-weather}

**File:** `LLMWeatherExample.scala`

Using tools with LLM calls - agent automatically calls tools.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.LLMWeatherExample"
```

**What it demonstrates:**
- LLM tool calling
- Automatic tool selection
- Tool result integration
- Natural language to tool execution

---

### MultiToolExample {#multi-tool}

**File:** `MultiToolExample.scala`

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

**File:** `ErrorMessageDemonstration.scala`

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

**File:** `ImprovedErrorMessageDemo.scala`

Enhanced error reporting for better debugging.

```bash
sbt "samples/runMain org.llm4s.samples.toolapi.ImprovedErrorMessageDemo"
```

**What it demonstrates:**
- Detailed error context
- Stack traces
- Debugging information

---

## Guardrails Examples

**Location:** `modules/samples/src/main/scala/org/llm4s/samples/guardrails/`

### BasicInputValidationExample {#basic}

**File:** `BasicInputValidationExample.scala`

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

**File:** `CustomGuardrailExample.scala`

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

**File:** `CompositeGuardrailExample.scala`

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

**File:** `JSONOutputValidationExample.scala`

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

**File:** `MultiTurnToneValidationExample.scala`

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

## Streaming

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

- **[User Guide](/guide/basic-usage)** - Learn concepts in depth
- **[API Reference](/api/llm-client)** - Detailed API documentation
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help and share projects

---

**Found a useful example pattern?** Share it in our [Discord](https://discord.gg/4uvTPn6qww)!
