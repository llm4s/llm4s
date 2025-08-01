# Langfuse Workflow Patterns: Complete Implementation Guide

## 🎯 **Overview**

This guide documents the correct event sequences for common LLM application patterns.

---

## 🔄 **1. RAG (Retrieval-Augmented Generation) Pipeline**

### **Event Sequence:**

```
1. Trace Creation
2. Document Retrieval Span
3. LLM Generation with Context
4. Final Response Event
```

### **Expected Langfuse Structure:**

```json
{
  "batch": [
    {
      "type": "trace-create",
      "body": {
        "id": "rag-trace-001",
        "name": "RAG Query Processing",
        "input": "What is the capital of France?",
        "userId": "user-123",
        "tags": ["rag", "qa"]
      }
    },
    {
      "type": "span-create", 
      "body": {
        "id": "retrieval-span",
        "traceId": "rag-trace-001",
        "name": "Document Retrieval",
        "input": {"query": "capital France", "top_k": 5},
        "output": {"documents": [...], "scores": [0.95, 0.87]}
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "rag-generation",
        "traceId": "rag-trace-001", 
        "name": "RAG Generation",
        "model": "gpt-4",
        "input": [
          {"role": "system", "content": "Answer based on context"},
          {"role": "user", "content": "Context: [retrieved docs]\n\nQuestion: What is the capital of France?"}
        ],
        "output": {
          "role": "assistant",
          "content": "Based on the context, the capital of France is Paris."
        }
      }
    }
  ]
}
```

### **LLM4S Implementation:**

```scala
// This is automatically handled by the fixed LangfuseTracing.scala
// When you run an agent with RAG capabilities:

val agent = new Agent(client)
val query = "What is the capital of France?"

agent.run(
  query = query,
  tools = ragToolRegistry,
  traceLogPath = Some("rag-example.md")
) match {
  case Right(finalState) =>
    // The fixed tracing will automatically create:
    // 1. Trace with query as input and final answer as output
    // 2. Generation events with proper conversation context
    // 3. Tool execution spans for document retrieval
    // 4. Proper hierarchical structure
}
```

---

## 💬 **2. Chat Conversation Flow**

### **Event Sequence:**

```
1. Trace Creation (Session-level)
2. User Input Event
3. LLM Generation
4. Assistant Response Event
5. [Repeat for conversation turns]
```

### **Expected Langfuse Structure:**

```json
{
  "batch": [
    {
      "type": "trace-create",
      "body": {
        "id": "chat-session-001",
        "name": "Customer Support Chat",
        "input": "Initial user message",
        "sessionId": "session-456",
        "userId": "user-123",
        "tags": ["chat", "support"]
      }
    },
    {
      "type": "event-create",
      "body": {
        "id": "user-input-1",
        "traceId": "chat-session-001",
        "name": "User Input",
        "input": {"role": "user", "content": "I need help with my order"}
      }
    },
    {
      "type": "generation-create", 
      "body": {
        "id": "chat-response-1",
        "traceId": "chat-session-001",
        "name": "Support Response",
        "model": "gpt-4",
        "input": [
          {"role": "system", "content": "You are a helpful support agent"},
          {"role": "user", "content": "I need help with my order"}
        ],
        "output": {
          "role": "assistant",
          "content": "I'd be happy to help you with your order. Could you please provide your order number?"
        }
      }
    }
  ]
}
```

### **LLM4S Implementation:**

```scala
// For multi-turn conversations, the fixed implementation handles this automatically
val conversation = Conversation(Seq(
  SystemMessage("You are a helpful support agent"),
  UserMessage("I need help with my order"),
  AssistantMessage("I'd be happy to help. What's your order number?"),
  UserMessage("Order #12345")
))

// The tracing will capture:
// - Each message as appropriately typed events/generations
// - Full conversation context in generation inputs  
// - Complete responses in generation outputs
```

---

## 🤖 **3. Agentic Loop with Tool Calling**

### **Event Sequence:**

```
1. Trace Creation
2. Initial Planning Generation (with tool_calls)
3. Tool Execution Spans (parallel or sequential)
4. Synthesis Generation (with tool results)
5. Final Response
```

### **Expected Langfuse Structure:**

```json
{
  "batch": [
    {
      "type": "trace-create",
      "body": {
        "id": "agent-trace-001", 
        "name": "Travel Assistant Task",
        "input": "Book a flight to Paris and check weather",
        "tags": ["agent", "multi-tool"]
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "planning-gen",
        "traceId": "agent-trace-001",
        "name": "Agent Planning",
        "model": "gpt-4",
        "input": [
          {"role": "system", "content": "You are a travel assistant with tools"},
          {"role": "user", "content": "Book a flight to Paris and check weather"}
        ],
        "output": {
          "role": "assistant", 
          "content": "I'll help you with both tasks. Let me check the weather and search for flights.",
          "tool_calls": [
            {"id": "call_1", "function": {"name": "get_weather", "arguments": "{\"city\": \"Paris\"}"}},
            {"id": "call_2", "function": {"name": "search_flights", "arguments": "{\"destination\": \"Paris\"}"}}
          ]
        }
      }
    },
    {
      "type": "span-create",
      "body": {
        "id": "weather-span",
        "traceId": "agent-trace-001",
        "parentObservationId": "planning-gen",
        "name": "Tool: get_weather", 
        "input": {"toolName": "get_weather", "arguments": {"city": "Paris"}},
        "output": {"result": "Paris weather: 15°C, partly cloudy"}
      }
    },
    {
      "type": "span-create",
      "body": {
        "id": "flight-span",
        "traceId": "agent-trace-001", 
        "parentObservationId": "planning-gen",
        "name": "Tool: search_flights",
        "input": {"toolName": "search_flights", "arguments": {"destination": "Paris"}},
        "output": {"result": "Found 3 flights: $299, $315, $289"}
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "synthesis-gen",
        "traceId": "agent-trace-001",
        "name": "Final Response",
        "model": "gpt-4",
        "input": [
          {"role": "system", "content": "You are a travel assistant..."},
          {"role": "user", "content": "Book a flight to Paris and check weather"},
          {"role": "assistant", "content": "I'll help you...", "tool_calls": [...]},
          {"role": "tool", "content": "Paris weather: 15°C...", "tool_call_id": "call_1"},
          {"role": "tool", "content": "Found 3 flights...", "tool_call_id": "call_2"}
        ],
        "output": {
          "role": "assistant",
          "content": "Great! Here's what I found:\n\n**Weather:** Paris is 15°C and partly cloudy.\n\n**Flights:** I found 3 options with prices from $289-$315. Would you like me to help you book one?"
        }
      }
    }
  ]
}
```

### **LLM4S Implementation:**

```scala
// The fixed implementation automatically handles this complex flow
val toolRegistry = new ToolRegistry(Seq(WeatherTool.tool, FlightTool.tool))
val agent = new Agent(client)

agent.run(
  query = "Book a flight to Paris and check weather",
  tools = toolRegistry,
  maxSteps = Some(5),
  traceLogPath = Some("agent-example.md")
) match {
  case Right(finalState) =>
    // Automatically creates:
    // 1. Trace with initial query and final response
    // 2. Generation for initial planning with tool_calls in output
    // 3. Spans for each tool execution with proper parent-child relationships  
    // 4. Generation for synthesis with full conversation context
    // 5. Proper hierarchical structure throughout
}
```

---

## 🧠 **4. Multi-Step Reasoning Chain**

### **Event Sequence:**

```
1. Trace Creation
2. Problem Analysis Generation
3. Step-by-Step Reasoning Spans
4. Synthesis Generation
5. Verification Event (optional)
```

### **Expected Langfuse Structure:**

```json
{
  "batch": [
    {
      "type": "trace-create",
      "body": {
        "id": "reasoning-trace-001",
        "name": "Math Problem Solving", 
        "input": "If I have 15 apples and give away 1/3, then buy 8 more, how many do I have?",
        "tags": ["reasoning", "math"]
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "analysis-gen",
        "traceId": "reasoning-trace-001",
        "name": "Problem Analysis",
        "model": "gpt-4",
        "input": [
          {"role": "system", "content": "Solve step by step"},
          {"role": "user", "content": "If I have 15 apples and give away 1/3, then buy 8 more, how many do I have?"}
        ],
        "output": {
          "role": "assistant", 
          "content": "I need to solve this step by step:\n1. Start with 15 apples\n2. Give away 1/3 of them\n3. Buy 8 more\n\nLet me calculate each step."
        }
      }
    },
    {
      "type": "span-create",
      "body": {
        "id": "step1-span",
        "traceId": "reasoning-trace-001",
        "name": "Calculate 1/3 of 15",
        "input": {"calculation": "15 ÷ 3"},
        "output": {"result": "5 apples to give away"}
      }
    },
    {
      "type": "span-create", 
      "body": {
        "id": "step2-span",
        "traceId": "reasoning-trace-001",
        "name": "Remaining after giving away",
        "input": {"calculation": "15 - 5"},
        "output": {"result": "10 apples remaining"}
      }
    },
    {
      "type": "span-create",
      "body": {
        "id": "step3-span", 
        "traceId": "reasoning-trace-001",
        "name": "Add purchased apples",
        "input": {"calculation": "10 + 8"},
        "output": {"result": "18 apples total"}
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "final-answer-gen",
        "traceId": "reasoning-trace-001",
        "name": "Final Answer",
        "model": "gpt-4", 
        "input": [
          {"role": "system", "content": "Provide the final answer based on calculations"},
          {"role": "user", "content": "Based on the steps: 15 - 5 + 8 = ?"}
        ],
        "output": {
          "role": "assistant",
          "content": "You will have 18 apples total.\n\nBreakdown:\n- Started with: 15 apples\n- Gave away 1/3 (5 apples): 15 - 5 = 10 apples\n- Bought 8 more: 10 + 8 = 18 apples"
        }
      }
    }
  ]
}
```

---

## 🔧 **5. Error Handling and Recovery**

### **Event Sequence with Errors:**

```
1. Trace Creation
2. Initial Generation (fails)
3. Error Event
4. Retry Span
5. Successful Generation
6. Recovery Event
```

### **Expected Langfuse Structure:**

```json
{
  "batch": [
    {
      "type": "trace-create",
      "body": {
        "id": "error-recovery-trace",
        "name": "API Call with Retry",
        "input": "Get user profile for user-123",
        "tags": ["api", "retry", "error-handling"]
      }
    },
    {
      "type": "generation-create",
      "body": {
        "id": "initial-attempt",
        "traceId": "error-recovery-trace",
        "name": "Initial API Call",
        "model": "gpt-4",
        "input": [{"role": "user", "content": "Get user profile for user-123"}],
        "output": {
          "role": "assistant",
          "content": "I'll retrieve the user profile.",
          "tool_calls": [{"id": "call_1", "function": {"name": "get_user", "arguments": "{\"user_id\": \"user-123\"}"}}]
        }
      }
    },
    {
      "type": "event-create",
      "body": {
        "id": "error-event",
        "traceId": "error-recovery-trace",
        "name": "API Error",
        "level": "ERROR",
        "statusMessage": "Connection timeout",
        "input": {"error": "Connection timeout after 30s"},
        "metadata": {
          "errorType": "TimeoutException",
          "attempt": 1,
          "willRetry": true
        }
      }
    },
    {
      "type": "span-create",
      "body": {
        "id": "retry-span",
        "traceId": "error-recovery-trace",
        "name": "Retry Attempt",
        "input": {"attempt": 2, "backoff": "2s"},
        "output": {"status": "success", "data": {"user": "profile data"}}
      }
    },
    {
      "type": "event-create",
      "body": {
        "id": "recovery-event", 
        "traceId": "error-recovery-trace",
        "name": "Recovery Success",
        "input": {"message": "Successfully recovered after 1 retry"},
        "metadata": {"totalAttempts": 2, "recoveryTime": "2.1s"}
      }
    }
  ]
}
```
