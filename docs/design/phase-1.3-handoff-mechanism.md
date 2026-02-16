# Phase 1.3: Handoff Mechanism

> **Date:** 2025-01-16
> **Status:** Design Phase
> **Priority:** ⭐⭐⭐⭐ High for Multi-Agent Workflows
> **Effort:** 1-2 weeks
> **Phase:** 1.3 - Core Usability
> **Dependencies:** Phase 1.1 (Functional Conversation Management), Phase 1.2 (Guardrails Framework)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Background & Motivation](#background--motivation)
3. [Design Goals](#design-goals)
4. [Core Concepts](#core-concepts)
5. [Proposed API](#proposed-api)
6. [Implementation Details](#implementation-details)
7. [Integration with Existing Features](#integration-with-existing-features)
8. [Testing Strategy](#testing-strategy)
9. [Documentation Plan](#documentation-plan)
10. [Examples](#examples)
11. [Appendix](#appendix)

---

## Executive Summary

### Problem Statement

llm4s currently supports multi-agent orchestration via **DAG-based planning**, which provides fine-grained control but is **verbose for simple delegation** patterns. Users must:

- **Explicitly construct DAGs** - Even for simple agent-to-agent delegation
- **Define typed edges** - Overhead for straightforward handoffs
- **Manage orchestration** - PlanRunner complexity for basic delegation

**Current State (DAG-based):**
```scala
// Verbose DAG construction for simple delegation
val plan = DAGPlan(
  nodes = Map(
    "generalAgent" -> generalAgent,
    "specialistAgent" -> specialistAgent
  ),
  edges = Seq(
    Edge("generalAgent", "specialistAgent", transformResult)
  )
)

val runner = new PlanRunner()
runner.executePlan(plan, input)
```

**Common Use Case:** A general agent receives a query, determines it requires specialized knowledge, and **hands off** to a specialist agent.

### Solution

Implement a **native handoff mechanism** that provides:

✅ **Declarative delegation** - Express handoffs as simple declarations
✅ **Context preservation** - Automatically transfer conversation history
✅ **LLM-driven handoffs** - Agent decides when to hand off (via tool calls)
✅ **Type-safe** - Compile-time checking of handoff targets
✅ **Composable** - Works with existing guardrails and conversation management
✅ **Simpler than DAGs** - For common delegation patterns

**Proposed State:**
```scala
// Declarative handoff - much simpler!
val generalAgent = new Agent(client)
val specialistAgent = new Agent(client)

generalAgent.run(
  query = "Explain quantum computing",
  tools = tools,
  handoffs = Seq(
    Handoff(
      targetAgent = specialistAgent,
      transferReason = Some("Query requires specialized knowledge"),
      preserveContext = true
    )
  )
)

// Agent can invoke handoff via tool call when it determines
// the query needs specialist knowledge
```

### Design Philosophy Alignment

This design adheres to llm4s core principles:

| Principle | How Handoff Mechanism Achieves It |
|-----------|-----------------------------------|
| **Functional & Immutable** | Handoffs return new AgentState, no mutations |
| **Framework Agnostic** | No dependencies on specific effect systems |
| **Simplicity Over Cleverness** | Clear handoff declaration, explicit control flow |
| **Principle of Least Surprise** | Handoffs work like tool calls - familiar pattern |
| **Type Safety** | Compile-time checking of target agents |

### Key Benefits

1. **Simpler Multi-Agent Patterns** - Reduce boilerplate for common delegation
2. **LLM-Driven Handoffs** - Agent decides when to delegate (not hardcoded)
3. **Context Preservation** - Automatic conversation history transfer
4. **Composable** - Works seamlessly with guardrails and conversation management
5. **Backward Compatible** - DAG orchestration still available for complex flows

### Relationship to DAG Orchestration

**Handoffs are NOT a replacement for DAGs**. They're complementary:

| Pattern | Use Handoff | Use DAG |
|---------|-------------|---------|
| Simple delegation | ✅ Yes | ⚠️ Overkill |
| LLM decides when to delegate | ✅ Yes | ❌ Not suitable |
| Chain of 2-3 agents | ✅ Yes | ⚠️ Verbose |
| Complex workflows | ⚠️ Limited | ✅ Yes |
| Parallel execution | ❌ No | ✅ Yes |
| Conditional routing | ⚠️ Via LLM | ✅ Yes |
| Type-safe dataflow | ⚠️ String-based | ✅ `Edge[A, B]` |

**Rule of Thumb:** Use handoffs for simple delegation; use DAGs for complex orchestration.

---

## Background & Motivation

### Comparison with Other Frameworks

#### OpenAI Agents SDK

**OpenAI Approach:**
```python
# Handoffs as first-class feature
triage_agent = Agent(
    instructions="Route to appropriate specialist",
    handoffs=["sales_agent", "support_agent", "refund_agent"]
)

# Agent can invoke handoff via function call
result = runner.run(triage_agent, "I want a refund")
# Automatically hands off to refund_agent
```

**Features:**
- Native handoff mechanism
- LLM decides when to hand off
- Automatic context transfer
- Simple API (no DAG construction)

#### PydanticAI

**PydanticAI Approach:**
```python
# Graph-based with type hints
@agent.tool
async def delegate_to_specialist(query: str) -> RunResult:
    return await specialist_agent.run(query)

# Agents can call other agents as tools
```

**Features:**
- Agents can invoke other agents
- Type-safe via Pydantic
- Flexible graph construction
- Dependency injection

#### CrewAI

**CrewAI Approach:**
```python
# Hierarchical with manager
crew = Crew(
    agents=[researcher, writer, editor],
    process=Process.hierarchical,
    manager_llm=ChatOpenAI(model="gpt-4")
)

# Manager agent delegates to team members
result = crew.kickoff(task="Write a blog post")
```

**Features:**
- Role-based agents
- Automatic delegation via manager
- Sequential and hierarchical processes
- Very high-level abstraction

### Gap Analysis

| Feature | OpenAI SDK | PydanticAI | CrewAI | llm4s Current | llm4s Proposed |
|---------|------------|------------|--------|---------------|----------------|
| **Simple Delegation** | ✅ Handoffs | ✅ Agent tools | ✅ Crew | ⚠️ DAG only | ✅ Handoffs |
| **LLM-driven Routing** | ✅ Yes | ✅ Yes | ✅ Manager | ❌ No | ✅ Yes |
| **Context Transfer** | ✅ Automatic | ⚠️ Manual | ✅ Automatic | ⚠️ Manual | ✅ Automatic |
| **Type Safety** | ❌ Runtime | ⚠️ Hints | ❌ Runtime | ✅ Compile-time | ✅ Compile-time |
| **Complex Workflows** | ⚠️ Limited | ✅ Graphs | ⚠️ Limited | ✅ DAG | ✅ DAG + Handoffs |
| **Parallel Execution** | ⚠️ Manual | ✅ Async | ❌ Sequential | ✅ DAG | ✅ DAG |

**llm4s Unique Position:**
- **Both handoffs AND DAGs** - Best of both worlds
- **Compile-time type safety** - Catch errors early
- **Functional purity** - No mutable crew state
- **Explicit control flow** - Clear delegation semantics

---

## Design Goals

### Primary Goals

1. **Simplify Common Delegation Patterns** ✅
   - Reduce boilerplate for 2-3 agent workflows
   - Clear, declarative API
   - Less verbose than DAG construction

2. **LLM-Driven Handoff Decisions** ✅
   - Agent determines when to hand off
   - Handoff as a tool call
   - Dynamic routing based on query content

3. **Context Preservation** ✅
   - Automatic conversation history transfer
   - Configurable context preservation
   - System message propagation

4. **Type Safety** ✅
   - Compile-time checking of target agents
   - Prevent invalid handoffs
   - Clear error messages

5. **Composability** ✅
   - Works with guardrails (Phase 1.2)
   - Works with conversation management (Phase 1.1)
   - Works alongside DAG orchestration

### Non-Goals

❌ **Replace DAG orchestration** - Handoffs complement DAGs, don't replace them
❌ **Automatic handoff loops** - Handoffs are one-directional (prevent infinite loops)
❌ **Parallel handoffs** - One handoff target at a time (use DAGs for parallel)
❌ **Hierarchical management** - Not implementing CrewAI-style manager agents (yet)

---

## Core Concepts

### Handoff

A handoff represents a transfer of control from one agent to another:

```scala
/**
 * Represents a handoff to another agent.
 *
 * Handoffs allow an agent to delegate a query to a specialist agent
 * when it determines that the query requires specialized knowledge.
 *
 * @param targetAgent The agent to hand off to
 * @param transferReason Optional reason for the handoff (for logging/tracing)
 * @param preserveContext Whether to transfer conversation history (default: true)
 * @param transferSystemMessage Whether to transfer system message (default: false)
 */
case class Handoff(
  targetAgent: Agent,
  transferReason: Option[String] = None,
  preserveContext: Boolean = true,
  transferSystemMessage: Boolean = false
)
```

**Key Properties:**
- **Immutable** - Handoff is a value, not a mutable reference
- **Explicit target** - Clear which agent receives the handoff
- **Configurable context** - Control what gets transferred
- **Traceable** - Transfer reason for observability

### Handoff as a Tool

The handoff mechanism works by exposing handoffs as **tool calls** that the LLM can invoke:

```scala
// Agent receives available handoffs
agent.run(
  query,
  tools,
  handoffs = Seq(
    Handoff(specialistAgent, Some("For technical questions")),
    Handoff(refundAgent, Some("For refund requests"))
  )
)

// LLM sees handoffs as tools:
// - handoff_to_specialist_agent(reason: String)
// - handoff_to_refund_agent(reason: String)

// LLM can invoke: handoff_to_specialist_agent(reason = "Complex technical query")
```

**Advantages:**
- **LLM decides** - Agent autonomously determines when to hand off
- **Familiar pattern** - Handoffs work like tool calls (reuses existing infrastructure)
- **Observable** - Handoff decisions visible in tool call logs

### Handoff Flow

```
┌─────────────────┐
│  User Query     │
└────────┬────────┘
         │
         ├──→ General Agent.run(query, tools, handoffs)
         │
         │    General Agent processes:
         │    - Analyzes query
         │    - Determines if handoff needed
         │    - Invokes handoff tool if specialized knowledge required
         │
         ├──→ AgentStatus.HandoffRequested(handoff)
         │
         │    Handoff execution:
         │    - Transfer conversation history (if preserveContext = true)
         │    - Optionally transfer system message
         │    - Add handoff metadata to logs
         │
         ├──→ Specialist Agent.run(transferred conversation, specialist tools)
         │
         │    Specialist Agent processes:
         │    - Receives full context
         │    - Processes query with specialized knowledge
         │    - Returns result
         │
         └──→ Return Result[AgentState] with specialist's response
```

### AgentStatus Enhancement

New status type to represent handoff state:

```scala
sealed trait AgentStatus

object AgentStatus {
  case object InProgress extends AgentStatus
  case object WaitingForTools extends AgentStatus

  /**
   * Agent has requested a handoff to another agent.
   *
   * This status indicates that the current agent has determined
   * that the query should be handled by a specialist agent.
   *
   * @param handoff The handoff to execute
   * @param handoffReason The reason provided by the LLM for the handoff
   */
  case class HandoffRequested(
    handoff: Handoff,
    handoffReason: Option[String] = None
  ) extends AgentStatus

  case object Complete extends AgentStatus
  case class Failed(error: String) extends AgentStatus
}
```

---

## Proposed API

### 1. Handoff Case Class

```scala
package org.llm4s.agent

/**
 * Represents a handoff to another agent.
 *
 * Handoffs provide a simpler alternative to DAG-based orchestration
 * for common delegation patterns. The LLM decides when to invoke a handoff
 * by calling a generated handoff tool.
 *
 * Example:
 * ```scala
 * val generalAgent = new Agent(client)
 * val specialistAgent = new Agent(client)
 *
 * generalAgent.run(
 *   "Explain quantum entanglement",
 *   tools,
 *   handoffs = Seq(
 *     Handoff(
 *       targetAgent = specialistAgent,
 *       transferReason = Some("Requires physics expertise"),
 *       preserveContext = true
 *     )
 *   )
 * )
 * ```
 *
 * @param targetAgent The agent to hand off to
 * @param transferReason Optional reason for the handoff (shown to LLM in tool description)
 * @param preserveContext Whether to transfer conversation history (default: true)
 * @param transferSystemMessage Whether to transfer system message (default: false)
 */
case class Handoff(
  targetAgent: Agent,
  transferReason: Option[String] = None,
  preserveContext: Boolean = true,
  transferSystemMessage: Boolean = false
) {
  /**
   * Generate a unique identifier for this handoff.
   * Used for tool naming and logging.
   */
  def handoffId: String = {
    val targetId = targetAgent.hashCode().toString
    s"handoff_to_agent_$targetId"
  }

  /**
   * Generate a human-readable name for this handoff.
   */
  def handoffName: String = {
    transferReason
      .map(reason => s"Handoff: $reason")
      .getOrElse(s"Handoff to agent ${targetAgent.hashCode()}")
  }
}

object Handoff {
  /**
   * Create a simple handoff with default settings.
   */
  def to(targetAgent: Agent): Handoff =
    Handoff(targetAgent, None, preserveContext = true, transferSystemMessage = false)

  /**
   * Create a handoff with a reason.
   */
  def to(targetAgent: Agent, reason: String): Handoff =
    Handoff(targetAgent, Some(reason), preserveContext = true, transferSystemMessage = false)
}
```

### 2. Enhanced AgentStatus

```scala
package org.llm4s.agent

import upickle.default.{ReadWriter => RW, readwriter}

sealed trait AgentStatus

object AgentStatus {
  case object InProgress extends AgentStatus
  case object WaitingForTools extends AgentStatus

  /**
   * Agent has requested a handoff to another agent.
   *
   * @param handoff The handoff to execute
   * @param handoffReason Optional reason provided by the LLM
   */
  case class HandoffRequested(
    handoff: Handoff,
    handoffReason: Option[String] = None
  ) extends AgentStatus

  case object Complete extends AgentStatus
  case class Failed(error: String) extends AgentStatus

  // Serialization (excluding Handoff which contains Agent reference)
  implicit val rw: RW[AgentStatus] = readwriter[ujson.Value].bimap[AgentStatus](
    {
      case InProgress => ujson.Str("InProgress")
      case WaitingForTools => ujson.Str("WaitingForTools")
      case HandoffRequested(handoff, reason) =>
        ujson.Obj(
          "type" -> ujson.Str("HandoffRequested"),
          "handoffId" -> ujson.Str(handoff.handoffId),
          "reason" -> reason.map(ujson.Str.apply).getOrElse(ujson.Null)
        )
      case Complete => ujson.Str("Complete")
      case Failed(error) => ujson.Obj("type" -> ujson.Str("Failed"), "error" -> ujson.Str(error))
    },
    {
      case ujson.Str("InProgress") => InProgress
      case ujson.Str("WaitingForTools") => WaitingForTools
      case ujson.Str("Complete") => Complete
      case obj: ujson.Obj =>
        obj.obj.get("type") match {
          case Some(ujson.Str("HandoffRequested")) =>
            // Note: Cannot fully deserialize handoff (contains Agent reference)
            // This is primarily for trace logging
            Failed("Cannot deserialize HandoffRequested status")
          case Some(ujson.Str("Failed")) =>
            Failed(obj.obj.get("error").map(_.str).getOrElse("Unknown error"))
          case _ => Failed("Unknown status format")
        }
      case _ => Failed("Invalid status format")
    }
  )
}
```

### 3. Enhanced Agent API

```scala
package org.llm4s.agent

import org.llm4s.agent.guardrails.{InputGuardrail, OutputGuardrail}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.{ToolRegistry, ToolFunction}
import org.llm4s.types.Result

class Agent(client: LLMClient) {

  /**
   * Initialize agent state with optional handoffs.
   *
   * Handoffs are exposed to the LLM as tool calls, allowing the agent
   * to autonomously decide when to delegate to a specialist agent.
   *
   * @param query Initial user query
   * @param tools Available tools
   * @param handoffs Available handoffs (default: none)
   * @param systemMessage Optional system message
   * @param debug Enable debug logging
   * @return Initial agent state
   */
  def initialize(
    query: String,
    tools: ToolRegistry,
    handoffs: Seq[Handoff] = Seq.empty,
    systemMessage: Option[SystemMessage] = None,
    debug: Boolean = false
  ): AgentState = {
    // Convert handoffs to tools
    val handoffTools = createHandoffTools(handoffs)

    // Combine regular tools with handoff tools
    val allTools = new ToolRegistry(tools.tools ++ handoffTools)

    // Create initial state
    val conversation = Conversation(Vector(UserMessage(query)))

    AgentState(
      conversation = conversation,
      tools = allTools,
      initialQuery = Some(query),
      status = AgentStatus.InProgress,
      logs = Seq.empty,
      systemMessage = systemMessage
    )
  }

  /**
   * Run agent with optional handoffs.
   *
   * @param query User query
   * @param tools Available tools
   * @param handoffs Available handoffs (default: none)
   * @param inputGuardrails Input validation guardrails
   * @param outputGuardrails Output validation guardrails
   * @param maxSteps Maximum agent steps
   * @param traceLogPath Optional trace log file
   * @param systemMessage Optional system message
   * @param debug Enable debug logging
   * @return Final agent state or error
   */
  def run(
    query: String,
    tools: ToolRegistry,
    handoffs: Seq[Handoff] = Seq.empty,
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    maxSteps: Option[Int] = Some(10),
    traceLogPath: Option[String] = None,
    systemMessage: Option[SystemMessage] = None,
    debug: Boolean = false
  ): Result[AgentState] = {
    for {
      // 1. Validate input
      validatedQuery <- validateInput(query, inputGuardrails)

      // 2. Initialize with handoffs
      initialState = initialize(
        validatedQuery,
        tools,
        handoffs,
        systemMessage,
        debug
      )

      // 3. Run agent (handles handoffs internally)
      finalState <- runWithHandoffs(
        initialState,
        handoffs,
        maxSteps,
        traceLogPath,
        debug
      )

      // 4. Validate output
      validatedState <- validateOutput(finalState, outputGuardrails)
    } yield validatedState
  }

  /**
   * Run agent with handoff support.
   *
   * This method checks for HandoffRequested status and executes handoffs.
   */
  private def runWithHandoffs(
    initialState: AgentState,
    handoffs: Seq[Handoff],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    debug: Boolean
  ): Result[AgentState] = {
    // Run agent normally
    run(initialState, maxSteps, traceLogPath, debug).flatMap { state =>
      state.status match {
        case AgentStatus.HandoffRequested(handoff, reason) =>
          // Execute handoff
          executeHandoff(state, handoff, reason, maxSteps, traceLogPath, debug)

        case _ =>
          // No handoff requested, return state
          Right(state)
      }
    }
  }

  /**
   * Execute a handoff to another agent.
   *
   * @param sourceState The state from the source agent
   * @param handoff The handoff to execute
   * @param reason Optional reason provided by the LLM
   * @param maxSteps Maximum steps for target agent
   * @param traceLogPath Optional trace log file
   * @param debug Enable debug logging
   * @return Result from target agent
   */
  private def executeHandoff(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String],
    maxSteps: Option[Int],
    traceLogPath: Option[String],
    debug: Boolean
  ): Result[AgentState] = {
    // Log handoff
    val logEntry = s"Executing handoff: ${handoff.handoffName}" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    if (debug) {
      logger.info(logEntry)
    }

    // Build target state
    val targetState = buildHandoffState(sourceState, handoff, reason)

    // Run target agent
    handoff.targetAgent.run(targetState, maxSteps, traceLogPath, debug)
  }

  /**
   * Build the initial state for the handoff target agent.
   *
   * @param sourceState State from source agent
   * @param handoff The handoff configuration
   * @param reason Optional handoff reason
   * @return Initial state for target agent
   */
  private def buildHandoffState(
    sourceState: AgentState,
    handoff: Handoff,
    reason: Option[String]
  ): AgentState = {
    // Determine which messages to transfer
    val transferredMessages = if (handoff.preserveContext) {
      sourceState.conversation.messages
    } else {
      // Only transfer the last user message
      sourceState.conversation.messages
        .findLast(_.role == MessageRole.User)
        .toVector
    }

    // Build conversation
    val conversation = Conversation(transferredMessages)

    // Determine system message
    val systemMessage = if (handoff.transferSystemMessage) {
      sourceState.systemMessage
    } else {
      None
    }

    // Build logs
    val handoffLog = s"Received handoff from agent" +
      reason.map(r => s" (Reason: $r)").getOrElse("")

    // Create target state
    AgentState(
      conversation = conversation,
      tools = handoff.targetAgent.defaultTools, // Target agent's tools
      initialQuery = sourceState.initialQuery,
      status = AgentStatus.InProgress,
      logs = Vector(handoffLog),
      systemMessage = systemMessage
    )
  }

  /**
   * Create tool functions for handoffs.
   *
   * Each handoff becomes a tool that the LLM can invoke.
   */
  private def createHandoffTools(handoffs: Seq[Handoff]): Seq[ToolFunction[?, ?]] = {
    handoffs.map { handoff =>
      ToolFunction.define(
        name = handoff.handoffId,
        description = s"Hand off this query to a specialist agent. ${handoff.transferReason.getOrElse("")}",
        parameters = ujson.Obj(
          "type" -> "object",
          "properties" -> ujson.Obj(
            "reason" -> ujson.Obj(
              "type" -> "string",
              "description" -> "The reason for handing off to this agent"
            )
          ),
          "required" -> ujson.Arr("reason")
        )
      ) { (reason: String) =>
        // Mark state as HandoffRequested
        // This will be caught by runWithHandoffs
        // For now, return a marker value
        ujson.Obj("handoff_requested" -> true, "reason" -> reason)
      }
    }
  }

  /**
   * Default tools for this agent (can be overridden).
   */
  protected def defaultTools: ToolRegistry = ToolRegistry.empty
}
```

### 4. Handoff Detection in Agent Loop

```scala
package org.llm4s.agent

/**
 * Enhanced agent step to detect handoff tool calls.
 */
private def runStep(state: AgentState): Result[AgentState] = {
  for {
    // Get completion from LLM
    completion <- client.complete(
      messages = state.toApiConversation.messages,
      model = None,
      options = state.completionOptions
    )

    // Check if completion includes handoff tool call
    handoffRequest <- detectHandoff(completion, state)

    // If handoff detected, mark status; otherwise process normally
    nextState <- handoffRequest match {
      case Some((handoff, reason)) =>
        Right(state.copy(
          status = AgentStatus.HandoffRequested(handoff, Some(reason))
        ))

      case None =>
        // Process completion normally
        processCompletion(state, completion)
    }
  } yield nextState
}

/**
 * Detect if the completion contains a handoff tool call.
 *
 * @param completion LLM completion
 * @param state Current agent state
 * @return Optional handoff and reason
 */
private def detectHandoff(
  completion: Completion,
  state: AgentState
): Result[Option[(Handoff, String)]] = {
  // Extract tool calls from completion
  val toolCalls = completion.message.toolCalls

  // Find handoff tool calls
  val handoffCalls = toolCalls.filter(tc => tc.name.startsWith("handoff_to_agent_"))

  handoffCalls.headOption match {
    case Some(toolCall) =>
      // Parse handoff reason from arguments
      val args = ujson.read(toolCall.arguments)
      val reason = args.obj.get("reason").map(_.str).getOrElse("No reason provided")

      // Find matching handoff by ID
      val handoffId = toolCall.name
      val handoff = findHandoffById(handoffId, state)

      handoff match {
        case Some(h) => Right(Some((h, reason)))
        case None =>
          Left(ValidationError.invalid(
            "handoff",
            s"Handoff tool called but handoff not found: $handoffId"
          ))
      }

    case None =>
      Right(None) // No handoff
  }
}

/**
 * Find a handoff by its ID in the current state.
 */
private def findHandoffById(handoffId: String, state: AgentState): Option[Handoff] = {
  // Handoffs are stored in tools as metadata
  // This is a simplified version - actual implementation tracks handoffs in AgentState
  None // Placeholder - see full implementation in agent/Handoff.scala
}
```

---

## Implementation Details

### Module Structure

```
modules/core/src/main/scala/org/llm4s/agent/
├── Agent.scala                  # Enhanced with handoff support
├── AgentState.scala             # Unchanged
├── AgentStatus.scala            # Enhanced with HandoffRequested
├── Handoff.scala                # NEW: Handoff case class
└── HandoffExecutor.scala        # NEW: Handoff execution logic
```

### Implementation Phases

#### Phase 1: Core Handoff Types (Week 1, Days 1-2)

**Tasks:**
1. Implement `Handoff` case class
2. Add `AgentStatus.HandoffRequested` status
3. Update `AgentStatus` serialization
4. Add tests for handoff types

**Deliverables:**
- `Handoff.scala` with case class and companion object
- Updated `AgentStatus.scala` with new status
- Unit tests for handoff types

#### Phase 2: Handoff Tool Generation (Week 1, Days 3-4)

**Tasks:**
1. Implement `createHandoffTools()` method
2. Generate tool functions from handoffs
3. Add handoff tools to tool registry
4. Test tool generation

**Deliverables:**
- Handoff-to-tool conversion logic
- Tests for tool generation
- Integration with `ToolRegistry`

#### Phase 3: Handoff Detection & Execution (Week 1-2, Days 5-7)

**Tasks:**
1. Implement `detectHandoff()` method
2. Implement `executeHandoff()` method
3. Implement `buildHandoffState()` method
4. Add handoff tracking in agent state
5. Test handoff execution flow

**Deliverables:**
- Handoff detection logic
- Handoff execution logic
- Context preservation
- Integration tests

#### Phase 4: Agent API Integration (Week 2, Days 8-10)

**Tasks:**
1. Update `Agent.initialize()` with handoffs parameter
2. Update `Agent.run()` with handoffs parameter
3. Update `runWithHandoffs()` method
4. Add trace logging for handoffs
5. Integration with guardrails

**Deliverables:**
- Enhanced Agent API
- Handoff logging in trace files
- Integration tests with guardrails

#### Phase 5: Documentation & Examples (Week 2, Days 11-14)

**Tasks:**
1. Write user guide for handoffs
2. Create handoff examples
3. Update CLAUDE.md
4. Add migration guide (DAG to handoffs)
5. Performance testing

**Deliverables:**
- Comprehensive documentation
- 4+ working examples
- Migration guide
- Performance benchmarks

---

## Integration with Existing Features

### Integration with Phase 1.1 (Conversation Management)

Handoffs work seamlessly with multi-turn conversations:

```scala
val generalAgent = new Agent(client)
val specialistAgent = new Agent(client)

val result = for {
  // Turn 1: General agent with handoff capability
  state1 <- generalAgent.run(
    "What is quantum computing?",
    tools,
    handoffs = Seq(Handoff.to(specialistAgent, "Physics expertise"))
  )

  // Turn 2: Continue conversation (might hand off)
  state2 <- generalAgent.continueConversation(
    state1,
    "Explain quantum entanglement in detail",
    handoffs = Seq(Handoff.to(specialistAgent, "Advanced physics"))
  )
  // If query is complex, general agent hands off to specialist
} yield state2
```

### Integration with Phase 1.2 (Guardrails)

Each agent in a handoff can have its own guardrails:

```scala
val generalAgent = new Agent(client)
val specialistAgent = new Agent(client)

// General agent: lenient guardrails
val generalState = generalAgent.run(
  query,
  generalTools,
  handoffs = Seq(Handoff.to(specialistAgent)),
  inputGuardrails = Seq(LengthCheck(1, 10000)),
  outputGuardrails = Seq.empty
)

// Specialist agent: strict guardrails
val specialistState = specialistAgent.run(
  handoffQuery,
  specialistTools,
  inputGuardrails = Seq(LengthCheck(1, 5000), TechnicalTermValidator()),
  outputGuardrails = Seq(JSONValidator(), ToneValidator(Set(Tone.Professional)))
)
```

**Key Point:** Guardrails are **not inherited** across handoffs. Each agent validates independently.

### Integration with DAG Orchestration

Handoffs and DAGs can be used together:

```scala
// Use handoffs for simple delegation
val triageAgent = new Agent(client)
val supportAgent = new Agent(client)
val salesAgent = new Agent(client)

triageAgent.run(
  query,
  tools,
  handoffs = Seq(
    Handoff.to(supportAgent, "Customer support"),
    Handoff.to(salesAgent, "Sales inquiries")
  )
)

// Use DAGs for complex workflows
val plan = DAGPlan(
  nodes = Map(
    "extract" -> extractionAgent,
    "validate" -> validationAgent,
    "transform" -> transformationAgent,
    "load" -> loadAgent
  ),
  edges = Seq(
    Edge("extract", "validate", extractToValidate),
    Edge("validate", "transform", validateToTransform),
    Edge("transform", "load", transformToLoad)
  )
)

val runner = new PlanRunner()
runner.executePlan(plan, input)
```

**When to use what:**
- **Handoffs:** 2-3 agents, LLM-driven routing, simple delegation
- **DAGs:** 4+ agents, parallel execution, complex dataflow, typed transformations

---

## Testing Strategy

### Unit Tests

#### Handoff Tests

```scala
class HandoffSpec extends AnyFlatSpec with Matchers {
  "Handoff" should "create with target agent" in {
    val targetAgent = new Agent(mockClient)
    val handoff = Handoff(targetAgent)

    handoff.targetAgent shouldBe targetAgent
    handoff.preserveContext shouldBe true
    handoff.transferSystemMessage shouldBe false
  }

  it should "generate unique handoff ID" in {
    val agent1 = new Agent(mockClient)
    val agent2 = new Agent(mockClient)

    val handoff1 = Handoff(agent1)
    val handoff2 = Handoff(agent2)

    handoff1.handoffId should not equal handoff2.handoffId
  }

  it should "create simple handoff with companion object" in {
    val targetAgent = new Agent(mockClient)
    val handoff = Handoff.to(targetAgent, "Specialist needed")

    handoff.transferReason shouldBe Some("Specialist needed")
  }
}

class AgentStatusSpec extends AnyFlatSpec with Matchers {
  "AgentStatus.HandoffRequested" should "serialize without target agent" in {
    val targetAgent = new Agent(mockClient)
    val handoff = Handoff(targetAgent, Some("Test handoff"))
    val status = AgentStatus.HandoffRequested(handoff, Some("Complex query"))

    val json = write(status)
    json should include("HandoffRequested")
    json should include("Complex query")
  }
}
```

### Integration Tests

#### Handoff Execution Tests

```scala
class HandoffExecutionSpec extends AnyFlatSpec with Matchers {
  "Agent.run" should "execute handoff when LLM requests it" in {
    val generalAgent = new Agent(mockClient)
    val specialistAgent = new Agent(mockClient)

    // Mock general agent to request handoff
    when(mockClient.complete(*, *))
      .thenReturn(Right(CompletionResponse(
        message = AssistantMessage(
          content = "Handing off to specialist",
          toolCalls = Vector(ToolCall(
            id = "1",
            name = "handoff_to_agent_123",
            arguments = """{"reason": "Needs expertise"}"""
          ))
        )
      )))

    val result = generalAgent.run(
      "Complex quantum physics question",
      tools,
      handoffs = Seq(Handoff.to(specialistAgent, "Physics expert"))
    )

    result.isRight shouldBe true
    // Verify specialist agent was invoked
  }

  it should "preserve context when preserveContext = true" in {
    val generalAgent = new Agent(mockClient)
    val specialistAgent = new Agent(mockClient)

    val sourceState = AgentState(
      conversation = Conversation(Vector(
        UserMessage("Question 1"),
        AssistantMessage("Answer 1"),
        UserMessage("Question 2")
      )),
      tools = ToolRegistry.empty,
      initialQuery = Some("Question 1")
    )

    val handoff = Handoff(specialistAgent, preserveContext = true)

    val targetState = generalAgent.buildHandoffState(
      sourceState,
      handoff,
      Some("Test")
    )

    targetState.conversation.messages.length shouldBe 3
  }

  it should "not preserve context when preserveContext = false" in {
    val generalAgent = new Agent(mockClient)
    val specialistAgent = new Agent(mockClient)

    val sourceState = AgentState(
      conversation = Conversation(Vector(
        UserMessage("Question 1"),
        AssistantMessage("Answer 1"),
        UserMessage("Question 2")
      )),
      tools = ToolRegistry.empty,
      initialQuery = Some("Question 1")
    )

    val handoff = Handoff(specialistAgent, preserveContext = false)

    val targetState = generalAgent.buildHandoffState(
      sourceState,
      handoff,
      Some("Test")
    )

    targetState.conversation.messages.length shouldBe 1
    targetState.conversation.messages.head shouldBe UserMessage("Question 2")
  }
}
```

### End-to-End Tests

```scala
class HandoffE2ESpec extends AnyFlatSpec with Matchers {
  "Multi-agent handoff" should "work end-to-end" in {
    // Real LLM clients (requires API keys)
    val client = Llm4sConfig.provider().flatMap(LLMConnect.getClient).toOption.get

    val generalAgent = new Agent(client)
    val mathAgent = new Agent(client)

    val result = generalAgent.run(
      query = "What is the derivative of x^2 + 3x + 5?",
      tools = ToolRegistry.empty,
      handoffs = Seq(
        Handoff.to(mathAgent, "Mathematical questions requiring calculus")
      ),
      systemMessage = Some(SystemMessage(
        "You are a general assistant. Hand off math questions to the math specialist."
      ))
    )

    result.isRight shouldBe true
    val finalState = result.toOption.get

    // Verify handoff occurred
    finalState.logs should contain("Received handoff from agent")

    // Verify math answer
    finalState.conversation.messages.last.content should include("2x + 3")
  }
}
```

---

## Documentation Plan

### User Guide: Handoff Mechanism

```markdown
# Handoff Mechanism

## Overview

Handoffs provide a simple way for agents to delegate queries to specialist agents.

## Basic Usage

### Simple Handoff

```scala
val generalAgent = new Agent(client)
val specialistAgent = new Agent(client)

generalAgent.run(
  "Explain quantum entanglement",
  tools,
  handoffs = Seq(
    Handoff.to(specialistAgent, "Physics expertise required")
  )
)
```

### Multiple Handoff Options

```scala
val triageAgent = new Agent(client)
val supportAgent = new Agent(client)
val salesAgent = new Agent(client)
val refundAgent = new Agent(client)

triageAgent.run(
  query,
  tools,
  handoffs = Seq(
    Handoff.to(supportAgent, "Customer support questions"),
    Handoff.to(salesAgent, "Sales inquiries"),
    Handoff.to(refundAgent, "Refund requests")
  )
)
// LLM decides which agent to hand off to based on query
```

## Context Preservation

### Transfer Full Context

```scala
Handoff(
  targetAgent = specialistAgent,
  preserveContext = true  // Transfer entire conversation history
)
```

### Transfer Only Last Message

```scala
Handoff(
  targetAgent = specialistAgent,
  preserveContext = false  // Only transfer last user message
)
```

### Transfer System Message

```scala
Handoff(
  targetAgent = specialistAgent,
  transferSystemMessage = true  // Also transfer system message
)
```

## How Handoffs Work

1. **Tool Generation:** Each handoff becomes a tool the LLM can invoke
2. **LLM Decision:** The agent decides when to hand off based on query
3. **Context Transfer:** Conversation history is transferred (if configured)
4. **Execution:** Target agent processes with its own tools/guardrails
5. **Return:** Result from target agent is returned

## Handoffs vs. DAG Orchestration

### Use Handoffs For:

✅ Simple 2-3 agent delegation
✅ LLM-driven routing decisions
✅ Sequential agent chaining
✅ Query classification and routing

### Use DAGs For:

✅ Complex multi-agent workflows (4+ agents)
✅ Parallel agent execution
✅ Type-safe data transformations
✅ Conditional branching
✅ Fine-grained concurrency control

## Best Practices

1. **Clear transfer reasons** - Help the LLM understand when to hand off
2. **Preserve context for continuity** - Unless privacy/performance concerns
3. **Limit handoff options** - 3-5 options max for LLM clarity
4. **Use system messages** - Guide when to hand off
5. **Monitor handoff decisions** - Use trace logs to understand routing

## Common Patterns

### Triage Pattern

```scala
// Route to appropriate specialist
val triageAgent = new Agent(client)

triageAgent.run(
  query,
  tools,
  handoffs = specialists.map { specialist =>
    Handoff.to(specialist.agent, specialist.expertise)
  },
  systemMessage = Some(SystemMessage(
    "Analyze the query and hand off to the appropriate specialist."
  ))
)
```

### Escalation Pattern

```scala
// Escalate complex queries
val basicAgent = new Agent(client)
val advancedAgent = new Agent(client)

basicAgent.run(
  query,
  basicTools,
  handoffs = Seq(
    Handoff.to(advancedAgent, "Complex queries requiring deep expertise")
  ),
  systemMessage = Some(SystemMessage(
    "Try to answer. If too complex, hand off to advanced agent."
  ))
)
```

### Multi-turn Handoff

```scala
// Handoffs work across conversation turns
for {
  state1 <- generalAgent.run(query1, tools, handoffs = handoffs)
  state2 <- generalAgent.continueConversation(
    state1,
    query2,
    handoffs = handoffs  // Can hand off on any turn
  )
} yield state2
```

---

## Examples

### Example 1: Simple Triage Handoff

```scala
package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.toolapi.ToolRegistry

object SimpleTriageHandoffExample {
  def run(client: LLMClient): Unit = {
    val result = for {
      // Create specialized agents
      supportAgent = new Agent(client)
      salesAgent = new Agent(client)
      refundAgent = new Agent(client)

      // Create triage agent
      triageAgent = new Agent(client)

      // Run with handoff options
      finalState <- triageAgent.run(
        query = "I want a refund for my order #12345",
        tools = ToolRegistry.empty,
        handoffs = Seq(
          Handoff.to(supportAgent, "General customer support questions"),
          Handoff.to(salesAgent, "Sales and product inquiries"),
          Handoff.to(refundAgent, "Refund and return requests")
        ),
        systemMessage = Some(SystemMessage(
          """You are a customer service triage agent.
            |Analyze customer queries and hand off to the appropriate specialist:
            |- Support agent for general questions
            |- Sales agent for product inquiries
            |- Refund agent for refunds and returns
            |""".stripMargin
        ))
      )
    } yield finalState

    result match {
      case Right(state) =>
        println(s"✅ Query handled successfully")
        println(s"Status: ${state.status}")
        println(s"Response: ${state.conversation.messages.last.content}")

        if (state.logs.exists(_.contains("handoff"))) {
          println(s"🔄 Handoff occurred:")
          state.logs.filter(_.contains("handoff")).foreach(log => println(s"  - $log"))
        }

      case Left(error) =>
        println(s"❌ Error: ${error.formatted}")
    }
  }
}
```

### Example 2: Math Specialist Handoff

```scala
package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.toolapi.ToolRegistry

object MathSpecialistHandoffExample {
  def run(client: LLMClient): Unit = {
    val result = for {
      // General agent
      generalAgent = new Agent(client)

      // Math specialist
      mathAgent = new Agent(client)

      // Run general agent with math handoff
      finalState <- generalAgent.run(
        query = "What is the integral of 2x + 5 from 0 to 10?",
        tools = ToolRegistry.empty,
        handoffs = Seq(
          Handoff.to(
            mathAgent,
            "Mathematical questions requiring calculus or advanced math"
          )
        ),
        systemMessage = Some(SystemMessage(
          """You are a general assistant.
            |For mathematical questions involving calculus, algebra, or advanced math,
            |hand off to the math specialist.
            |""".stripMargin
        ))
      )
    } yield finalState

    result match {
      case Right(state) =>
        println(s"✅ Math question answered:")
        println(state.conversation.messages.last.content)

      case Left(error) =>
        println(s"❌ Error: ${error.formatted}")
    }
  }
}
```

### Example 3: Context Preservation

```scala
package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.SystemMessage
import org.llm4s.toolapi.ToolRegistry

object ContextPreservationExample {
  def run(client: LLMClient): Unit = {
    val result = for {
      generalAgent = new Agent(client)
      specialistAgent = new Agent(client)

      // Multi-turn conversation with context
      state1 <- generalAgent.run(
        "I'm working on a quantum computing project",
        ToolRegistry.empty
      )

      state2 <- generalAgent.continueConversation(
        state1,
        "Can you explain quantum entanglement?",
        handoffs = Seq(
          Handoff(
            targetAgent = specialistAgent,
            transferReason = Some("Quantum physics expertise"),
            preserveContext = true  // Transfer full conversation
          )
        )
      )
    } yield state2

    result match {
      case Right(state) =>
        println(s"✅ Full conversation context preserved:")
        println(s"Total messages: ${state.conversation.messages.length}")
        println(s"Response: ${state.conversation.messages.last.content}")

      case Left(error) =>
        println(s"❌ Error: ${error.formatted}")
    }
  }
}
```

### Example 4: Handoff with Guardrails

```scala
package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi.ToolRegistry

object HandoffWithGuardrailsExample {
  def run(client: LLMClient): Unit = {
    val result = for {
      generalAgent = new Agent(client)
      technicalAgent = new Agent(client)

      // General agent: lenient guardrails
      finalState <- generalAgent.run(
        query = "Explain how neural networks work",
        tools = ToolRegistry.empty,
        handoffs = Seq(
          Handoff.to(technicalAgent, "Technical AI/ML questions")
        ),
        inputGuardrails = Seq(
          LengthCheck(1, 10000)
        ),
        outputGuardrails = Seq.empty
      )

      // If handed off to technical agent:
      // Technical agent has its own guardrails (not inherited)
      // - Stricter length check
      // - JSON output requirement
      // - Professional tone
    } yield finalState

    result match {
      case Right(state) =>
        println(s"✅ Query processed with appropriate guardrails")
        println(s"Response: ${state.conversation.messages.last.content}")

      case Left(error) =>
        println(s"❌ Guardrail validation failed: ${error.formatted}")
    }
  }
}
```

---

## Appendix

### A. Comparison with DAG Orchestration

#### Example: Same Workflow, Different Approaches

**Handoff Approach (Simpler for Sequential):**
```scala
val agent1 = new Agent(client)
val agent2 = new Agent(client)
val agent3 = new Agent(client)

agent1.run(
  query,
  tools,
  handoffs = Seq(
    Handoff.to(agent2, "Needs processing"),
    Handoff.to(agent3, "Needs finalization")
  )
)
```

**DAG Approach (Better for Parallel/Complex):**
```scala
val plan = DAGPlan(
  nodes = Map(
    "agent1" -> agent1,
    "agent2" -> agent2,
    "agent3" -> agent3
  ),
  edges = Seq(
    Edge("agent1", "agent2", transform1),
    Edge("agent2", "agent3", transform2)
  )
)

new PlanRunner().executePlan(plan, input)
```

**Key Differences:**
- **Handoffs:** LLM decides when to delegate
- **DAGs:** Explicit control flow
- **Handoffs:** Sequential only
- **DAGs:** Parallel execution supported
- **Handoffs:** Context preservation built-in
- **DAGs:** Manual data transformation

### B. Future Enhancements

**Phase 2: Async Handoffs**
```scala
trait AsyncHandoff {
  def executeAsync(state: AgentState): AsyncResult[AgentState]
}
```

**Phase 3: Conditional Handoffs**
```scala
case class ConditionalHandoff(
  targetAgent: Agent,
  condition: String => Boolean,  // LLM output => should hand off?
  transferReason: Option[String]
)
```

**Phase 4: Handoff Chains**
```scala
case class HandoffChain(
  agents: Seq[Agent],
  maxChainLength: Int = 5  // Prevent infinite handoffs
)
```

**Phase 5: Hierarchical Handoffs (Manager Pattern)**
```scala
case class ManagerHandoff(
  manager: Agent,
  team: Seq[Agent],
  delegationStrategy: DelegationStrategy
)
```

### C. Performance Considerations

**Handoff Overhead:**
- Context transfer: O(n) where n = message count
- Tool generation: O(h) where h = handoff count
- Serialization: Minimal (only metadata)

**Optimization Tips:**
1. **Limit context transfer** - Set `preserveContext = false` for large conversations
2. **Limit handoff options** - 3-5 handoffs per agent for LLM clarity
3. **Use system messages** - Guide handoff decisions to reduce token usage
4. **Monitor handoff chains** - Prevent excessive delegation

### D. Migration from DAG to Handoffs

**Before (DAG):**
```scala
val plan = DAGPlan(
  nodes = Map("general" -> generalAgent, "specialist" -> specialistAgent),
  edges = Seq(Edge("general", "specialist", identity))
)
new PlanRunner().executePlan(plan, query)
```

**After (Handoff):**
```scala
generalAgent.run(
  query,
  tools,
  handoffs = Seq(Handoff.to(specialistAgent, "Specialist knowledge"))
)
```

**Benefits:**
- ✅ 50% less code
- ✅ LLM-driven routing
- ✅ Automatic context transfer
- ✅ Clearer intent

**When NOT to migrate:**
- ❌ Parallel agent execution needed
- ❌ Complex conditional routing
- ❌ Type-safe data transformations required
- ❌ 4+ agents in workflow

---

## Conclusion

Phase 1.3 (Handoff Mechanism) provides a **high-value** feature that simplifies multi-agent workflows:

✅ **Simple delegation** - Reduce DAG boilerplate for common patterns
✅ **LLM-driven** - Agent decides when to hand off
✅ **Context preservation** - Automatic history transfer
✅ **Type-safe** - Compile-time checking
✅ **Composable** - Works with guardrails and conversation management
✅ **Complementary** - DAGs still available for complex workflows

**Estimated Timeline:** 1-2 weeks
**Effort:** Low-Medium
**Risk:** Low
**Value:** High (simplifies common multi-agent patterns)

**Next Steps:**
1. Review and approve design document
2. Create implementation branch
3. Implement core handoff types (Week 1, Days 1-2)
4. Implement tool generation (Week 1, Days 3-4)
5. Implement handoff execution (Week 1-2, Days 5-7)
6. Agent API integration (Week 2, Days 8-10)
7. Documentation and examples (Week 2, Days 11-14)

---

**End of Design Document**
