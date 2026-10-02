# LLM4S Agent Framework: Deep Agents and LangGraph Gap Analysis

**Review date:** 2026-10-02
**Scope:** Repository agent framework compared with LangGraph and the Deep Agents harness.
**Purpose:** Define the material capability gaps and recommend an updated LLM4S agent model.

## Executive summary

LLM4S has a broad set of agent-related components, but they are currently presented as separate libraries and execution models. The core `Agent` is a tool-calling loop; typed multi-agent work is handled by a separate DAG `PlanRunner`; conversation persistence is provided by assistant session serialization; and context, memory, MCP, workspace isolation, tracing, and metrics live in distinct modules. This is a strong Scala foundation, but it does not yet provide one runtime that makes long-running, stateful work straightforward to checkpoint, interrupt, resume, inspect, and compose.

LangGraph and Deep Agents are related but should not be treated as direct equivalents. **LangGraph is an execution runtime** for stateful graphs, persistence, interrupts, streaming, and fault recovery. **Deep Agents is a higher-level harness** on LangChain building blocks and LangGraph that packages common long-task capabilities: task planning, file-backed context, subagents, skills, memory, summarization, sandbox execution, permissions, and human approval. The official overview describes this layered relationship and these capabilities ([Deep Agents overview](https://docs.langchain.com/oss/python/deepagents/overview); [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence); [LangGraph interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)).

The strategic recommendation is to evolve LLM4S from a primarily **agent loop plus separate DAG orchestration** into a **typed agent runtime and harness**:

1. Keep the current loop as the simplest path for conversational tool use.
2. Add a stateful graph runtime with dynamic routing, loops, subgraphs, checkpointing, and typed interrupts.
3. Assemble reusable harness capabilities as policies/middleware over that runtime: tool permissions and approvals, plans, context offload, skills, memory, and subagent delegation.
4. Make durable execution and explicit state the common foundation, rather than treating session-file serialization as equivalent to resuming a workflow.

This is not a recommendation to reproduce every LangChain feature. LLM4S should retain its differentiators—Scala compile-time types, immutable state, typed errors, provider neutrality, and functional composition—while filling gaps around durable orchestration and developer-facing harness capabilities.

## What exists in LLM4S today

Repository review found the following implemented building blocks:

| Capability | Current LLM4S support | Evidence / boundary |
|---|---|---|
| Agent loop and tool calls | Single-agent loop, sequential or parallel tool execution, step limits, tool retries/timeouts configurable through context | [`Agent.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/Agent.scala), [`AgentStreamingExecutor.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/AgentStreamingExecutor.scala) |
| Handoffs | A specialist handoff can be exposed as a synthetic tool and executed while optionally preserving context | [`Handoff.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/Handoff.scala), [`HandoffExecutor.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/HandoffExecutor.scala) |
| Guardrails | Input/output guardrail composition, built-in validation and safety checks, including prompt injection and PII-related checks | [`guardrails`](../../modules/agent/src/main/scala/org/llm4s/agent/guardrails), [`AgentStreamingExecutor.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/AgentStreamingExecutor.scala) |
| Streaming | Token deltas plus step, tool, handoff, guardrail, completion, and failure events | [`AgentEvent.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/streaming/AgentEvent.scala) |
| Typed multi-agent orchestration | `TypedAgent[I,O]`, typed single-input edges, DAG validation, parallel batches, bounded concurrency, cancellation, retry/timeout/fallback policies. `PlanRunner` inputs/results cross `Map[String, Any]`; `Node.inputType`/`outputType` are placeholders, so whole-plan composition is not compile-time type-safe. | [`DAG.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/orchestration/DAG.scala), [`PlanRunner.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/orchestration/PlanRunner.scala), [`Policies.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/orchestration/Policies.scala) |
| Conversation/session storage | Assistant sessions persist conversation content and metadata, then rebuild the tool registry from supplied tools. This is useful session persistence, not an execution checkpoint. `AgentState` contains a live `ToolRegistry`; handoff status contains a live `Agent` reference and cannot be fully restored. | [`AgentState.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/AgentState.scala), [`SessionState.scala`](../../modules/agent/src/main/scala/org/llm4s/assistant/SessionState.scala), [`SessionManager.scala`](../../modules/agent/src/main/scala/org/llm4s/assistant/SessionManager.scala) |
| Context management | Token counting, windowing, history/tool-output compression, semantic blocks and pruning strategies | [`modules/core/.../context`](../../modules/core/src/main/scala/org/llm4s/context), [`ContextWindowConfig.scala`](../../modules/agent/src/main/scala/org/llm4s/agent/ContextWindowConfig.scala) |
| Long-term memory | Memory abstractions, in-memory/SQLite/vector stores, embeddings, retrieval and consolidation | [`modules/memory`](../../modules/memory/src/main/scala/org/llm4s/agent/memory) |
| Tools and integrations | Typed tool registry, built-in HTTP/search/filesystem/shell tools, MCP client/server integration | [`modules/agent-tools`](../../modules/agent-tools/src/main/scala/org/llm4s/toolapi), [`modules/mcp`](../../modules/mcp/src/main/scala/org/llm4s/mcp) |
| Isolated execution | Workspace client/runner with container and sandbox-oriented interfaces for code tasks | [`modules/workspace`](../../modules/workspace/workspaceShared/src/main/scala/org/llm4s/shared) |
| Observability | Trace events, Langfuse/OpenTelemetry-related integrations and metrics are available outside the core agent loop | [`modules/observability`](../../modules/observability/src/main/scala/org/llm4s/trace) |

The key limitation is **integration and runtime semantics**, rather than a total absence of primitives. The current typed `Plan` is not fully type-safe end to end: individual edges have typed single inputs, but plan inputs/results use `Map[String, Any]` and `Node.inputType`/`outputType` are placeholders. Session serialization restores conversation material with a supplied tool registry, but it is not a general execution checkpoint containing a graph cursor, pending tasks, interrupt payloads, or safe replay metadata; `AgentState` itself retains a live `ToolRegistry`, and handoff status retains a live `Agent` reference. The current streaming API reports what happened; it does not itself provide durable event replay or a resume cursor. Existing memory is useful, but it is application-managed rather than automatically integrated into every run's context lifecycle.

The project targets Scala 3 only and welcomes Scala 3 idioms. No module is frozen yet. Finalise the runtime and module API designs, then enable MiMa for the intended baseline; implement the runtime before 1.0. This leaves room for deliberate API corrections without constraining the new runtime to Scala 2.13 compatibility.

## Reference model: LangGraph plus Deep Agents

LangGraph persistence distinguishes **thread checkpoints** (state needed to continue a particular run/thread) from **stores** (long-lived cross-thread data such as preferences and facts). Checkpoints support continuity, interrupts, time travel, and fault tolerance ([Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)). Its interrupt mechanism pauses at a dynamic point, exposes JSON-serializable input to the caller, persists state, and resumes against the same thread ID ([Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)).

Deep Agents then packages common harness behavior: built-in task decomposition, filesystem tools/backends, context summarization and offload, optional shell/sandbox execution, subagents with isolated context, long-term memory, skills, permissions, and human-in-the-loop approval. Its backend abstraction routes virtual filesystem operations to in-memory, durable, local, sandboxed, or custom storage ([Backends](https://docs.langchain.com/oss/python/deepagents/backends)); delegation supports specialized agents and context isolation ([Subagents](https://docs.langchain.com/oss/python/deepagents/subagents)); skills are progressively loaded instructions and resources ([Skills](https://docs.langchain.com/oss/python/deepagents/skills)).

The important design lesson is the separation of layers:

| Layer | LangChain ecosystem role | LLM4S implication |
|---|---|---|
| Model/tool primitives | LangChain core | Keep provider and `Tool` contracts in core/tool modules. |
| Stateful execution | LangGraph | Add a durable, inspectable graph/runtime abstraction with state transitions and checkpoints. |
| Agent harness | Deep Agents | Compose planning, files, memory, skills, delegation, permissions and approvals as opt-in capabilities. |
| Production service/deployment | LangSmith/Agent Server ecosystem | Keep deployment optional; define APIs that applications can host and operate themselves. |

## Gap analysis

| Area | LLM4S today | LangGraph / Deep Agents comparison | Gap and priority |
|---|---|---|---|
| Unified runtime | Tool loop and static typed DAG are distinct APIs | One runtime handles agent loops, graphs, branching, subgraphs and state updates | **High:** unify execution semantics without removing the simple `Agent.run` API. |
| Dynamic graphs and loops | DAG plans disallow cycles; agent loop is a separate special case | Conditional routing, repeated nodes, graph commands and nested graphs | **High:** support cycles, dynamic edge selection, fan-out/fan-in and subgraphs. |
| Durable checkpointing | Conversation/session save/restore; no common checkpoint store or execution cursor identified | Checkpointer captures per-thread runtime state and supports resume/fault recovery | **High:** add versioned checkpoint store and resume by `threadId`/`runId`. |
| Interrupts and human review | No general interrupt/resume contract found; guardrails and handoffs do not substitute for pausing | Dynamic interrupt can request approval, edits, or structured input and resume later | **High:** first-class typed pause/resume with tool-call review and audit record. |
| Tool authorization | Tool registry and safety-minded tools exist; no generic policy rule set at invocation boundary identified | Declarative permissions can allow, deny or interrupt based on operation/resource | **High:** policy evaluation before side effects, scoped per agent/tool/resource and inherited by subagents. |
| Long-task planning | DAG is developer-authored; no built-in persistent, agent-updated todo plan found | Agent can create/update task plans and revise them during execution | **Medium-high:** optional plan state and planning tool, separate from the graph's control flow. |
| Context workspace/offload | Compression and pruning are strong; filesystem tools exist in agent-tools | Agent can externalize notes and large outputs to a virtual filesystem, then retrieve selected files | **Medium-high:** add an agent-owned, pluggable workspace/files backend and references in context. |
| Subagents and task isolation | Handoffs route control to configured agents; typed DAG agents compose known workflows | Delegate bounded tasks to specialized agents with isolated context and tool/model config | **Medium-high:** add task delegation results and run supervision; preserve handoffs for conversational routing. |
| Skills/progressive instructions | Prompt additions and configuration exist; no Agent Skills-style discovery/loading lifecycle found | Discover skills and load detailed instructions/resources on demand | **Medium:** adopt the Agent Skills `SKILL.md` format and loader; avoid loading every domain instruction up front. |
| Memory lifecycle | Rich memory API and stores; application generally wires retrieval and recording | Thread state and cross-thread store are separate; memory and file backends participate in harness | **Medium:** standardize `ThreadState` vs `MemoryStore`; configurable read/write/consolidation hooks. |
| Streaming and replay | Rich callback events; no durable cursor/event replay contract identified | Runtime streams tokens, state and execution events and can pair them with checkpoints/interrupts | **Medium:** versioned events with run/node/tool IDs and checkpoint sequence; adapters for SSE/FS2/Pekko as appropriate. |
| Runtime limits and scheduling | Step limits, cancellation and bounded parallel DAG execution; no shared run budget/scheduler across nested work identified | Harness/runtime can coordinate delegated work and long-running execution | **Medium:** central budgets for steps, tokens, cost, time, tool calls and concurrency, inherited by child runs. |
| Tracing/evaluation | Tracing and metrics integrations are substantial | LangSmith integrates traces, evaluation and deployment workflow | **Medium:** keep existing traces; add first-class agent evaluation datasets/scorers and run comparison. |
| Configurable middleware | Cross-cutting `AgentContext` and separate guardrails exist | Deep Agents composes model/tool/context middleware and supports profiles | **Medium:** introduce typed middleware/interceptor stages rather than expanding `AgentContext` parameters indefinitely. |

### What is not a gap

- **Type safety:** LLM4S has useful typed input/output agents and single-input edge composition, but the overall plan is not fully typed because it falls back to `Map[String, Any]`. The new graph model should make that boundary explicit and remove `Any` from newly authored workflows.
- **Memory:** LLM4S has a deeper built-in memory subsystem than a minimal agent loop. The gap is automatic runtime integration and memory scope/lifecycle conventions, not basic storage.
- **Observability:** Tracing and metrics are already present. The main opportunity is richer graph/run events, durable correlation, replay, and evaluation workflows.
- **Tools and model choice:** LLM4S already supports multiple provider modules, built-in tools, MCP and sandbox/workspace integration. Prioritize consistent policies and runtime wiring over simply increasing the tool count.
- **Context compression:** Context utilities exist; the gap is a harness-level context strategy that combines compression with durable offload and selective retrieval.

## Proposed updated LLM4S agent model

The detailed design now lives in [Typed Agent Runtime Design](typed-agent-runtime-design.md). Its key decisions are:

- A Scala 3 graph kernel runs nodes in supersteps. Nodes read a committed state snapshot and return typed routes plus updates. `StateKey[A,U]` applies each operation-valued update to its current value in deterministic task/update order; message history uses graph-owned IDs and append/replace/remove operations.
- Use builder-issued node handles, dynamic typed `Send`, command-style update-plus-routing, pending writes for completed parallel branches, typed multi-interrupt resume, subgraph checkpoint namespaces, and optimistic thread versions.
- Use Ox structured concurrency internally on Scala 3/JDK 21. Keep existing Future APIs as adapters; thread interruption is the cancellation contract and provider implementations must preserve/report interruption.
- Keep core `ToolFunction` simple and stateless; add contextual `AgentTool` and `AgentToolSpec` to the agent runtime. Agent state, routing, suspension, and run context stay outside core.
- Put the graph runtime and harness in `llm4s-agent` under `org.llm4s.agent.graph`. Define a `SandboxBackend` SPI there and implement the first adapter in `workspaceClient` to avoid a dependency cycle. Replace `AgentState` and `AgentEvent`; implement `Agent.run` directly on the graph runtime; rebuild `PlanRunner` as typed graph APIs or remove it. Keep SQLite and PostgreSQL checkpointers in separate modules.
- Build the Deep Agents-style harness over that runtime: state-backed virtual filesystem and composite backend, task plans, Agent Skills format, separate editable instruction memory and semantic memory, permissions, wrap-style middleware, preserved input/output and LLM-as-judge guardrails, structured outputs/multimodal data, and isolated typed subagents.
- Pause the whole run at a superstep boundary when a task suspends; allow partial answers on resume; apply new input to the latest completed checkpoint for multi-turn starts, but reject starts while interrupts remain pending.

The implementation sequence finalises contracts and prototypes risky semantics before enabling MiMa, then implements the graph kernel and agent loop together, followed by durable checkpointing/HITL, harness capabilities, subagents, and production adapters. A research workflow and a coding/workspace workflow provide measurable acceptance suites. See the linked design for state/update contracts, typed continuation resume, superstep, join/barrier and pending-write semantics, concurrency/durability modes, runtime-owned tool results, tool and middleware APIs, durable event replay, module placement, the single migration note, and stage exit criteria.

## Source notes

- Repository findings are based on source and documentation in this workspace as of the review date. “Gap” means the behavior was not found as a unified agent-runtime capability; it does not claim that no application can implement it with existing lower-level APIs.
- Deep Agents is evolving quickly; compare concepts and architectural boundaries rather than assuming every optional profile/backend feature is a stable contract. The cited documentation is the primary source for its described behavior.
- LangGraph and Deep Agents are Python/JavaScript ecosystems. The recommendations translate the runtime/harness separation into Scala/LLM4S terms; they are not proposals to embed Python or to use LangGraph as a dependency.
