# Learning the LLM4S Agent Implementation

## Reading Order

### Layer 1: Core Abstractions (start here)

1. **`AgentState.scala`** (`modules/core/src/main/scala/org/llm4s/agent/AgentState.scala`)
   - The state machine: `AgentStatus` (InProgress → WaitingForTools → Complete/Failed/HandoffRequested)
   - How `Conversation` + `ToolRegistry` + `SystemMessage` are bundled together
   - Context window pruning strategies (OldestFirst, MiddleOut, AdaptiveWindowing)

2. **`Agent.scala`** (`modules/core/src/main/scala/org/llm4s/agent/Agent.scala`)
   - `initializeSafe` — how system prompt + tools + handoffs are assembled
   - `runStep` — the single state transition (LLM call or tool execution)
   - `run` — the tail-recursive loop driving steps to completion

### Layer 2: Tool Execution & Handoffs

3. **`ToolProcessor.scala`** (`modules/core/src/main/scala/org/llm4s/agent/ToolProcessor.scala`)
   - How tool calls from the LLM are dispatched to actual functions

4. **`Handoff.scala`** + **`HandoffExecutor.scala`** (`modules/core/src/main/scala/org/llm4s/agent/`)
   - Multi-agent delegation

### Layer 3: Memory

5. **`Memory.scala`** (`modules/core/src/main/scala/org/llm4s/agent/memory/Memory.scala`) — Core memory model
6. **`MemoryManager.scala`** (`modules/core/src/main/scala/org/llm4s/agent/memory/MemoryManager.scala`) — Interface for storing/retrieving
7. **`InMemoryStore.scala`** (`modules/core/src/main/scala/org/llm4s/agent/memory/InMemoryStore.scala`) — Simplest implementation, read first
8. **`VectorMemoryStore.scala`** (`modules/core/src/main/scala/org/llm4s/agent/memory/VectorMemoryStore.scala`) — Embedding-based semantic retrieval

### Layer 4: Context Management

9. **`ContextWindowConfig.scala`** (`modules/core/src/main/scala/org/llm4s/agent/ContextWindowConfig.scala`)
   - How the agent decides what to keep/prune from conversation history when approaching token limits

## Key Takeaways

- The agent is a **synchronous state machine** — no IO monad, just `Result[AgentState]` (which is `Either[LLMError, AgentState]`)
- Context = system prompt + conversation history + tool definitions, all assembled in `toApiConversation`
- Memory is separate from context — it's a persistence layer that feeds *into* context when needed
- The `continueConversation` method shows how multi-turn works: append new user message, optionally prune, re-run the loop

---

## Debugging Tests to Learn the Logic Flow

### Start with these 3 tests (in order):

1. **`AgentSpec.scala`** — The main one. Set breakpoints in `Agent.runStep` and debug to watch:
   - `InProgress` → LLM call → `Complete` (no tools)
   - `InProgress` → LLM call → `WaitingForTools` → tool execution → `InProgress` → LLM call → `Complete`

2. **`ToolProcessorSpec.scala`** — How tool calls from the LLM get dispatched to actual Scala functions. Breakpoint in `ToolProcessor.processToolCalls`.

3. **`InMemoryStoreSpec.scala`** — Simplest memory implementation, shows store/retrieve/search patterns.

### Test Fixtures (`TestFixtures.scala`)

The tests mock the LLM using these fake clients:

- `DeterministicFakeLLMClient` — always returns same response (single-turn tests)
- `TwoTurnDeterministicFakeLLMClient` — returns different responses based on conversation state (tool-calling tests)
- `NTurnFakeLLMClient` — rotates through N responses (multi-step tests)

### Suggested Breakpoints

1. `Agent.runStep` → the `state.status match` — watch the state machine
2. `ToolProcessor.processToolCalls` — see how tool args are parsed and dispatched
3. `AgentState.pruneConversation` — see context window management in action

### Run a Single Test

```bash
sbt "core/testOnly org.llm4s.agent.AgentSpec"
```

Or in IntelliJ/Metals, right-click a test and "Debug". The mock clients mean everything runs instantly with no network calls — perfect for stepping through.

Understanding the agent (~2-3 hours):
- Agent.scala (1249 lines) — the core loop
- LLMClient.scala (99 lines) — the trait you'll implement
- LLMConnect.scala (192 lines) — the factory/selector
- ProviderConfig.scala (679 lines) — config model
- Skim one existing provider (e.g. AnthropicClient.scala, 649 lines) as a template

Your Scala/Cats experience means the Result[A] patterns, type classes, and functional error handling will be immediately familiar — no ramp-up there.

Implementing the Bedrock adapter (~3-5 hours):
- BedrockClient.scala — implement LLMClient trait (~300-500 lines, modeled after AnthropicClient)
- Config additions to ProviderConfig and LLMConnect selector
- Bedrock uses AWS SigV4 auth + a slightly different request/response shape than raw Anthropic/OpenAI — that's the main delta
- Streaming support adds complexity (Bedrock uses event-stream encoding)
- Tests (~1-2 hours)

Total estimate: ~1-1.5 days of focused work, including tests. Breakdown:

┌───────────────────────────────────────────────────┬───────┐
│                       Phase                       │ Time  │
├───────────────────────────────────────────────────┼───────┤
│ Read & understand agent loop + provider interface │ 2-3h  │
├───────────────────────────────────────────────────┼───────┤
│ Implement BedrockClient (non-streaming)           │ 2-3h  │
├───────────────────────────────────────────────────┼───────┤
│ Add streaming support                             │ 1-2h  │
├───────────────────────────────────────────────────┼───────┤
│ Config wiring + ProviderSelector update           │ 30min │
├───────────────────────────────────────────────────┼───────┤
│ Tests                                             │ 1-2h  │
└───────────────────────────────────────────────────┴───────┘

The biggest wildcard is Bedrock's auth (SigV4 signing) — if you use the AWS SDK it's straightforward, but if this project prefers raw HTTP (it seems to use sttp/requests),
you'd need to implement or pull in a signing library, which could add a couple hours.

