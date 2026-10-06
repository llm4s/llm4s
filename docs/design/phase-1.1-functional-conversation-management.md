# Phase 1.1: Functional Conversation Management

> **Superseded in part:** this document describes the pre-#1328 agent API (`new Agent(client)`, `AgentState`, per-run tools and guardrails). `Agent` now runs on the graph runtime and is built with `Agent.builder(...)`; see [typed-agent-runtime-design.md §4.13](typed-agent-runtime-design.md) and the [Stage 1 migration note](../reference/migration.md#stage-1-migration-agent-runtime). Kept as history.

> **Project:** llm4s Agent Framework Enhancement
> **Phase:** 1.1 - Core Usability: Conversation State Management
> **Date:** 2025-11-16
> **Status:** Design Proposal
> **Author:** AI Assistant (Claude)

---

## Table of Contents

1. [Overview](#overview)
2. [Problem Statement](#problem-statement)
3. [Design Principles](#design-principles)
4. [Current State Analysis](#current-state-analysis)
5. [Proposed Design](#proposed-design)
6. [API Examples](#api-examples)
7. [Implementation Plan](#implementation-plan)
8. [Testing Strategy](#testing-strategy)
9. [Migration Guide](#migration-guide)
10. [Future Considerations](#future-considerations)

---

## Overview

### Goals

Design a **functional, immutable, and ergonomic API** for multi-turn agent conversations that:

1. Maintains llm4s's functional programming principles (pure functions, immutable data)
2. Makes multi-turn conversations easy to write and understand
3. Provides automatic context window management
4. Supports conversation persistence without sacrificing purity
5. Eliminates the need for `var` and mutable state in user code

### Non-Goals

- **Mutable session objects** (like OpenAI SDK's `Session`) - violates functional principles
- **Implicit global state** - all state must be explicit
- **Breaking existing API** - should be additive enhancements

---

## Problem Statement

### Current Pain Points

#### 1. **Imperative State Threading in Samples**

Current sample code uses `var` to thread state:

```scala
// From SingleStepAgentExample.scala
var stat = state
while ((stat.status == AgentStatus.InProgress || stat.status == AgentStatus.WaitingForTools) && stepCount < 5) {
  agent.runStep(stat) match {
    case Right(newState) =>
      stat = newState  // ❌ Mutation!
    case Left(error) =>
      stat = stat.withStatus(AgentStatus.Failed(error.toString))
  }
  stepCount += 1
}
```

**Problem:** This is not functional code - uses mutable variables and imperative loops.

#### 2. **No Multi-Turn Conversation API**

There's no clean way to continue a conversation from a previous state:

```scala
// Current: must manually create new state
val state1 = agent.initialize("What's the weather?", tools)
val result1 = agent.run(state1, ...)

// How to continue? No built-in method
val state2 = result1.map { s =>
  s.copy(
    conversation = s.conversation.addMessage(UserMessage("And tomorrow?")),
    userQuery = "And tomorrow?",  // Have to update this too?
    status = AgentStatus.InProgress
  )
}
val result2 = state2.flatMap(agent.run(_, ...))
```

**Problem:** Verbose, error-prone, unclear what fields need updating.

#### 3. **No Context Window Management**

Conversations grow unbounded - no automatic pruning:

```scala
// After many turns, conversation history becomes huge
// No built-in way to prune old messages while preserving important context
```

**Problem:** Will hit token limits, slow down API calls, increase costs.

#### 4. **`userQuery` Field is Ambiguous**

```scala
case class AgentState(
  conversation: Conversation,
  tools: ToolRegistry,
  userQuery: String,  // ❌ What does this mean in multi-turn context?
  ...
)
```

**Problem:** In a multi-turn conversation, there's no single "user query" - there are multiple user messages.

---

## Design Principles

### Functional Programming First

1. **Pure Functions**
   - All agent operations return new immutable states
   - No side effects in core logic (I/O at boundaries only)
   - Referential transparency

2. **Explicit State Flow**
   - State threading is visible in the code
   - No hidden mutable session objects
   - Clear data flow through for-comprehensions

3. **Immutable Data Structures**
   - All state is immutable (already true)
   - State updates via `copy()` or builder patterns
   - Functional collections only

### Ergonomics Second

4. **Reduce Boilerplate**
   - Helper methods for common patterns
   - Smart defaults
   - Method chaining where appropriate

5. **Type Safety**
   - Leverage Scala's type system
   - Compile-time guarantees
   - Avoid stringly-typed APIs

6. **Discoverability**
   - Self-documenting method names
   - Consistent naming conventions
   - Clear documentation

---

## Current State Analysis

### What Works Well

✅ **Immutable Core Data Structures**
- `AgentState` is immutable
- `Conversation` is immutable with good methods
- `Message` types are immutable

✅ **Pure Agent Methods**
- `agent.run(state, ...)` returns `Result[AgentState]` (pure)
- `agent.runStep(state)` returns `Result[AgentState]` (pure)
- `agent.initialize(...)` returns `AgentState` (pure)

✅ **Result-based Error Handling**
- Consistent use of `Result[A]`
- Composable with for-comprehensions
- Clear error types

### What Needs Improvement

❌ **Multi-Turn Conversation Support**
- No continuation API
- Manual state threading required
- Unclear how to add a new turn

❌ **Context Window Management**
- No automatic pruning
- No token counting integration
- No configurable strategies

❌ **State Persistence**
- Partial serialization support
- No persistence helpers
- Unclear how to save/load conversations

❌ **AgentState.userQuery Semantics**
- Ambiguous in multi-turn context
- Not clear if it should be updated
- Seems like a display-only field

---

## Proposed Design

### 1. Conversation Continuation API

#### 1.1 Core Continuation Method

Add to `Agent` class:

```scala
/**
 * Continue an agent conversation with a new user message.
 * This is the functional way to handle multi-turn conversations.
 *
 * @param previousState The previous agent state (must be Complete or Failed)
 * @param newUserMessage The new user message to process
 * @param maxSteps Optional limit on reasoning steps
 * @param traceLogPath Optional path for trace logging
 * @param debug Enable debug logging
 * @return Result containing the new agent state
 */
def continueConversation(
  previousState: AgentState,
  newUserMessage: String,
  maxSteps: Option[Int] = None,
  traceLogPath: Option[String] = None,
  debug: Boolean = false
): Result[AgentState] = {
  // Validate previous state
  previousState.status match {
    case AgentStatus.Complete | AgentStatus.Failed(_) =>
      // Prepare new state by adding user message and resetting status
      val newState = previousState.copy(
        conversation = previousState.conversation.addMessage(UserMessage(newUserMessage)),
        status = AgentStatus.InProgress,
        logs = Seq.empty  // Reset logs for new turn
      )
      // Run from the new state
      run(newState, maxSteps, traceLogPath, debug)

    case AgentStatus.InProgress | AgentStatus.WaitingForTools =>
      Left(ValidationError(
        "Cannot continue from an incomplete conversation. " +
        "Previous state must be Complete or Failed."
      ))
  }
}
```

**Rationale:**
- Pure function - takes state, returns new state
- Explicit validation of previous state
- Clear semantics: only continue from completed turns
- Preserves all agent configuration (tools, system message, completion options)

#### 1.2 Multi-Turn Helper

For running multiple turns in sequence:

```scala
/**
 * Run multiple conversation turns sequentially.
 * Each turn waits for the previous to complete before starting.
 *
 * @param initialQuery The first user message
 * @param followUpQueries Additional user messages
 * @param tools Tool registry for the conversation
 * @param maxStepsPerTurn Optional step limit per turn
 * @param systemPromptAddition Optional system prompt addition
 * @param completionOptions Completion options
 * @param debug Enable debug logging
 * @return Result containing the final agent state after all turns
 */
def runMultiTurn(
  initialQuery: String,
  followUpQueries: Seq[String],
  tools: ToolRegistry,
  maxStepsPerTurn: Option[Int] = None,
  systemPromptAddition: Option[String] = None,
  completionOptions: CompletionOptions = CompletionOptions(),
  debug: Boolean = false
): Result[AgentState] = {
  // Run first turn
  val firstTurn = run(
    initialQuery,
    tools,
    maxStepsPerTurn,
    None,
    systemPromptAddition,
    completionOptions,
    debug
  )

  // Fold over follow-up queries, threading state through
  followUpQueries.foldLeft(firstTurn) { (stateResult, query) =>
    stateResult.flatMap { state =>
      continueConversation(state, query, maxStepsPerTurn, None, debug)
    }
  }
}
```

**Rationale:**
- Functional fold instead of imperative loop
- Composes well with `Result`
- No mutable variables needed

### 2. Context Window Management

#### 2.1 Configuration

```scala
package org.llm4s.agent

/**
 * Configuration for automatic context window management
 */
case class ContextWindowConfig(
  /**
   * Maximum number of tokens to keep in conversation history.
   * When exceeded, pruning will occur based on the strategy.
   */
  maxTokens: Option[Int] = None,

  /**
   * Maximum number of messages to keep (alternative to token-based).
   * When exceeded, oldest messages will be removed.
   */
  maxMessages: Option[Int] = None,

  /**
   * Always keep the system message (recommended)
   */
  preserveSystemMessage: Boolean = true,

  /**
   * Minimum number of recent turns to keep (user + assistant pairs)
   * Even if token limit exceeded, these will be preserved.
   */
  minRecentTurns: Int = 3,

  /**
   * Strategy for pruning messages
   */
  pruningStrategy: PruningStrategy = PruningStrategy.OldestFirst
)

/**
 * Strategies for pruning conversation history
 */
sealed trait PruningStrategy

object PruningStrategy {
  /**
   * Remove oldest messages first (FIFO)
   */
  case object OldestFirst extends PruningStrategy

  /**
   * Remove messages from the middle, keeping start and end
   */
  case object MiddleOut extends PruningStrategy

  /**
   * Keep only the most recent N turns (user+assistant pairs)
   */
  case class RecentTurnsOnly(turns: Int) extends PruningStrategy

  /**
   * Custom pruning function
   */
  case class Custom(fn: Seq[Message] => Seq[Message]) extends PruningStrategy
}
```

#### 2.2 Pruning Implementation

Add to `AgentState` companion object:

```scala
object AgentState {
  /**
   * Prune conversation history based on configuration.
   * Returns a new AgentState with pruned conversation.
   *
   * @param state The current agent state
   * @param config Context window configuration
   * @param tokenCounter Function to count tokens in messages
   * @return New state with pruned conversation
   */
  def pruneConversation(
    state: AgentState,
    config: ContextWindowConfig,
    tokenCounter: Message => Int = defaultTokenCounter
  ): AgentState = {
    val messages = state.conversation.messages

    // Check if pruning is needed
    val needsPruning = (config.maxTokens, config.maxMessages) match {
      case (Some(maxTokens), _) =>
        messages.map(tokenCounter).sum > maxTokens
      case (None, Some(maxMessages)) =>
        messages.length > maxMessages
      case (None, None) =>
        false
    }

    if (!needsPruning) {
      state
    } else {
      val prunedMessages = config.pruningStrategy match {
        case PruningStrategy.OldestFirst =>
          pruneOldestFirst(messages, config, tokenCounter)
        case PruningStrategy.MiddleOut =>
          pruneMiddleOut(messages, config, tokenCounter)
        case PruningStrategy.RecentTurnsOnly(turns) =>
          pruneRecentTurnsOnly(messages, turns, config)
        case PruningStrategy.Custom(fn) =>
          fn(messages)
      }

      state.copy(conversation = Conversation(prunedMessages))
    }
  }

  /**
   * Default token counter (rough estimate: words * 1.3)
   */
  private def defaultTokenCounter(message: Message): Int = {
    val words = message.content.split("\\s+").length
    (words * 1.3).toInt
  }

  private def pruneOldestFirst(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    // Separate system message if needed
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Calculate target based on maxTokens or maxMessages
    val targetCount = config.maxMessages.getOrElse(messages.length - 1)

    // Keep system messages + recent messages up to limit
    val toKeep = if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.takeRight(targetCount - systemMsgs.length)
    } else {
      messages.takeRight(targetCount)
    }

    toKeep
  }

  private def pruneMiddleOut(
    messages: Seq[Message],
    config: ContextWindowConfig,
    tokenCounter: Message => Int
  ): Seq[Message] = {
    val targetCount = config.maxMessages.getOrElse(messages.length / 2)
    val keepStart = targetCount / 2
    val keepEnd = targetCount - keepStart

    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.take(keepStart) ++ otherMsgs.takeRight(keepEnd)
    } else {
      messages.take(keepStart) ++ messages.takeRight(keepEnd)
    }
  }

  private def pruneRecentTurnsOnly(
    messages: Seq[Message],
    turns: Int,
    config: ContextWindowConfig
  ): Seq[Message] = {
    // A turn is a user message + assistant response (+ optional tool messages)
    // Keep the last N complete turns
    val (systemMsgs, otherMsgs) = messages.partition(_.role == MessageRole.System)

    // Group messages into turns (simplified: every user message starts a turn)
    val turnStarts = otherMsgs.zipWithIndex
      .filter(_._1.role == MessageRole.User)
      .map(_._2)

    val keepFromIndex = if (turnStarts.length > turns) {
      turnStarts(turnStarts.length - turns)
    } else {
      0
    }

    if (config.preserveSystemMessage) {
      systemMsgs ++ otherMsgs.drop(keepFromIndex)
    } else {
      otherMsgs.drop(keepFromIndex)
    }
  }
}
```

#### 2.3 Automatic Pruning in Agent

Add optional pruning to continuation:

```scala
def continueConversation(
  previousState: AgentState,
  newUserMessage: String,
  maxSteps: Option[Int] = None,
  traceLogPath: Option[String] = None,
  contextWindowConfig: Option[ContextWindowConfig] = None,  // NEW
  debug: Boolean = false
): Result[AgentState] = {
  previousState.status match {
    case AgentStatus.Complete | AgentStatus.Failed(_) =>
      // Prepare new state
      val stateWithNewMessage = previousState.copy(
        conversation = previousState.conversation.addMessage(UserMessage(newUserMessage)),
        status = AgentStatus.InProgress,
        logs = Seq.empty
      )

      // Optionally prune before running
      val stateToRun = contextWindowConfig match {
        case Some(config) =>
          AgentState.pruneConversation(stateWithNewMessage, config)
        case None =>
          stateWithNewMessage
      }

      run(stateToRun, maxSteps, traceLogPath, debug)

    case _ =>
      Left(ValidationError("Cannot continue from incomplete conversation"))
  }
}
```

### 3. Fix `userQuery` Semantics

#### Option A: Make it Optional

```scala
case class AgentState(
  conversation: Conversation,
  tools: ToolRegistry,
  initialQuery: Option[String] = None,  // Renamed and made optional
  status: AgentStatus = AgentStatus.InProgress,
  logs: Seq[String] = Seq.empty,
  systemMessage: Option[SystemMessage] = None,
  completionOptions: CompletionOptions = CompletionOptions()
)
```

**Rationale:**
- Rename to `initialQuery` makes purpose clear
- Optional because multi-turn conversations don't have a single query
- Keep for backward compatibility and tracing

#### Option B: Remove it Entirely

```scala
case class AgentState(
  conversation: Conversation,
  tools: ToolRegistry,
  // userQuery removed - can be derived from conversation.messages if needed
  status: AgentStatus = AgentStatus.InProgress,
  logs: Seq[String] = Seq.empty,
  systemMessage: Option[SystemMessage] = None,
  completionOptions: CompletionOptions = CompletionOptions()
) {
  /**
   * Get the initial user query (first user message)
   */
  def initialQuery: Option[String] =
    conversation.messages
      .find(_.role == MessageRole.User)
      .map(_.content)
}
```

**Rationale:**
- Cleaner - no redundant data
- Can be derived from conversation
- Breaking change but more correct

**Recommendation:** Use Option A for backward compatibility, consider Option B for v2.0.

### 4. Conversation Persistence Helpers

#### 4.1 Serialization Support

```scala
object AgentState {
  /**
   * Serialize agent state to JSON.
   * Note: ToolRegistry is not serialized (contains function references).
   * Tools must be re-registered when loading state.
   */
  def toJson(state: AgentState): ujson.Value = {
    ujson.Obj(
      "conversation" -> writeJs(state.conversation),
      "initialQuery" -> state.initialQuery.map(ujson.Str).getOrElse(ujson.Null),
      "status" -> writeJs(state.status),
      "logs" -> ujson.Arr(state.logs.map(ujson.Str): _*),
      "systemMessage" -> state.systemMessage.map(msg => ujson.Str(msg.content)).getOrElse(ujson.Null),
      "completionOptions" -> writeJs(state.completionOptions)
      // Note: tools are NOT serialized
    )
  }

  /**
   * Deserialize agent state from JSON.
   * Tools must be provided separately as they cannot be serialized.
   */
  def fromJson(
    json: ujson.Value,
    tools: ToolRegistry
  ): Result[AgentState] = {
    Try {
      AgentState(
        conversation = read[Conversation](json("conversation")),
        tools = tools,  // Provided by caller
        initialQuery = json("initialQuery") match {
          case ujson.Str(q) => Some(q)
          case _ => None
        },
        status = read[AgentStatus](json("status")),
        logs = json("logs").arr.map(_.str).toSeq,
        systemMessage = json("systemMessage") match {
          case ujson.Str(content) => Some(SystemMessage(content))
          case _ => None
        },
        completionOptions = read[CompletionOptions](json("completionOptions"))
      )
    }.toResult
  }

  /**
   * Save state to file (convenience method)
   */
  def saveToFile(state: AgentState, path: String): Result[Unit] = {
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets

    Try {
      val json = toJson(state)
      val jsonStr = write(json, indent = 2)
      Files.write(Paths.get(path), jsonStr.getBytes(StandardCharsets.UTF_8))
    }.toResult
  }

  /**
   * Load state from file (convenience method)
   */
  def loadFromFile(path: String, tools: ToolRegistry): Result[AgentState] = {
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets

    for {
      jsonStr <- Try(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)).toResult
      json <- Try(ujson.read(jsonStr)).toResult
      state <- fromJson(json, tools)
    } yield state
  }
}
```

**Rationale:**
- Pure functions - no side effects in core logic
- `saveToFile`/`loadFromFile` are I/O helpers, clearly separate
- Tools cannot be serialized (contain function references) - must be provided on load
- Uses existing `Result` pattern

---

## API Examples

### Example 1: Multi-Turn Conversation (Functional Style)

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(WeatherTool.tool))
  agent = new Agent(client)

  // Turn 1
  state1 <- agent.run("What's the weather in Paris?", tools)
  _ = println(s"Turn 1: ${state1.conversation.lastMessage.map(_.content)}")

  // Turn 2 - continue from state1
  state2 <- agent.continueConversation(state1, "And what about London?")
  _ = println(s"Turn 2: ${state2.conversation.lastMessage.map(_.content)}")

  // Turn 3 - continue from state2
  state3 <- agent.continueConversation(state2, "Which is warmer?")
  _ = println(s"Turn 3: ${state3.conversation.lastMessage.map(_.content)}")

} yield state3

result.fold(
  error => println(s"Error: $error"),
  finalState => println(s"Conversation completed with ${finalState.conversation.messageCount} messages")
)
```

**No `var`, no mutation, pure functional style!**

### Example 2: Multi-Turn with Helper Method

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(WeatherTool.tool))
  agent = new Agent(client)

  finalState <- agent.runMultiTurn(
    initialQuery = "What's the weather in Paris?",
    followUpQueries = Seq(
      "And what about London?",
      "Which is warmer?"
    ),
    tools = tools
  )

} yield finalState

result.fold(
  error => println(s"Error: $error"),
  state => {
    println(s"Completed ${state.conversation.messageCount} messages")
    state.conversation.filterByRole(MessageRole.Assistant).foreach { msg =>
      println(s"Assistant: ${msg.content}")
    }
  }
)
```

**Even cleaner - single method call!**

### Example 3: Context Window Management

```scala
import org.llm4s.agent.{ContextWindowConfig, PruningStrategy}

val config = ContextWindowConfig(
  maxMessages = Some(20),  // Keep max 20 messages
  preserveSystemMessage = true,
  minRecentTurns = 3,
  pruningStrategy = PruningStrategy.OldestFirst
)

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(...))
  agent = new Agent(client)

  state1 <- agent.run("First question?", tools)
  state2 <- agent.continueConversation(state1, "Second question?", contextWindowConfig = Some(config))
  state3 <- agent.continueConversation(state2, "Third question?", contextWindowConfig = Some(config))
  // ... many more turns ...

} yield state3

// Conversation automatically pruned to stay within limits
```

### Example 4: Conversation Persistence

```scala
import org.llm4s.agent.AgentState

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(...))
  agent = new Agent(client)

  // Start conversation
  state1 <- agent.run("Complex multi-step question?", tools)

  // Save state to file
  _ <- AgentState.saveToFile(state1, "/tmp/conversation-state.json")

  // ... later, in a different session ...

  // Load state from file
  loadedState <- AgentState.loadFromFile("/tmp/conversation-state.json", tools)

  // Continue from loaded state
  state2 <- agent.continueConversation(loadedState, "Follow-up question?")

} yield state2
```

**Pure, no side effects in core logic, I/O clearly separated.**

### Example 5: Manual Step Execution (Functional Style)

Replacing the imperative while-loop:

```scala
import scala.annotation.tailrec

@tailrec
def runStepsUntilComplete(
  agent: Agent,
  state: AgentState,
  maxSteps: Int,
  stepCount: Int = 0
): Result[AgentState] = {
  if (stepCount >= maxSteps) {
    Right(state)  // Reached limit
  } else {
    state.status match {
      case AgentStatus.Complete | AgentStatus.Failed(_) =>
        Right(state)  // Finished

      case AgentStatus.InProgress | AgentStatus.WaitingForTools =>
        agent.runStep(state) match {
          case Right(newState) =>
            println(s"Step ${stepCount + 1}: ${newState.status}")
            runStepsUntilComplete(agent, newState, maxSteps, stepCount + 1)

          case Left(error) =>
            Left(error)
        }
    }
  }
}

// Usage
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(...))
  agent = new Agent(client)
  initialState = agent.initialize("Question?", tools)
  finalState <- runStepsUntilComplete(agent, initialState, maxSteps = 10)
} yield finalState
```

**Tail-recursive, no `var`, pure functional!**

---

## Implementation Plan

### Phase 1: Core Continuation API (Week 1)

**Tasks:**
1. Add `continueConversation` method to `Agent`
2. Add `runMultiTurn` method to `Agent`
3. Update `AgentState` to rename `userQuery` to `initialQuery` (make optional)
4. Write unit tests for continuation logic
5. Update samples to use functional style

**Deliverables:**
- Updated `Agent.scala`
- Updated `AgentState.scala`
- Unit tests in `AgentSpec.scala`
- Updated `SingleStepAgentExample.scala` and `MultiStepAgentExample.scala`

### Phase 2: Context Window Management (Week 2)

**Tasks:**
1. Define `ContextWindowConfig` and `PruningStrategy`
2. Implement `AgentState.pruneConversation`
3. Integrate pruning into `continueConversation`
4. Add integration with token counting (from `context.tokens` module)
5. Write comprehensive tests for pruning strategies
6. Add example demonstrating long conversations

**Deliverables:**
- `ContextWindowConfig.scala`
- Updated `AgentState.scala` with pruning logic
- `PruningSpec.scala` with tests
- `LongConversationExample.scala` sample

### Phase 3: Persistence Helpers (Week 3)

**Tasks:**
1. Implement `AgentState.toJson` / `fromJson`
2. Implement `saveToFile` / `loadFromFile` helpers
3. Add comprehensive serialization tests
4. Document limitations (tools not serialized)
5. Add persistence example

**Deliverables:**
- Updated `AgentState.scala` with serialization
- `AgentStatePersistenceSpec.scala`
- `ConversationPersistenceExample.scala`

### Phase 4: Documentation & Migration (Week 4)

**Tasks:**
1. Update CLAUDE.md with new APIs
2. Write migration guide from old imperative style
3. Update README with multi-turn examples
4. Add ScalaDoc to all new methods
5. Review and merge

**Deliverables:**
- Updated documentation
- Migration guide
- PR ready for review

---

## Testing Strategy

### Unit Tests

```scala
class AgentContinuationSpec extends AnyFlatSpec with Matchers {

  "Agent.continueConversation" should "add user message to previous state" in {
    val mockClient = mock[LLMClient]
    val agent = new Agent(mockClient)
    val tools = new ToolRegistry(Seq.empty)

    val state1 = agent.initialize("First query", tools)
    val completedState = state1.copy(status = AgentStatus.Complete)

    // Mock the run to return immediately
    val result = agent.continueConversation(completedState, "Second query")

    result shouldBe Right(...)
    // Verify conversation has both messages
  }

  it should "fail if previous state is not complete" in {
    val mockClient = mock[LLMClient]
    val agent = new Agent(mockClient)
    val tools = new ToolRegistry(Seq.empty)

    val state = agent.initialize("Query", tools)
    // State is InProgress

    val result = agent.continueConversation(state, "Next query")

    result.isLeft shouldBe true
  }

  "Agent.runMultiTurn" should "execute all turns sequentially" in {
    // Test with mocked client
    // Verify each turn completes before next starts
  }
}

class ContextWindowPruningSpec extends AnyFlatSpec with Matchers {

  "AgentState.pruneConversation" should "keep messages under limit" in {
    val config = ContextWindowConfig(maxMessages = Some(10))
    val messages = (1 to 20).map(i => UserMessage(s"Message $i"))
    val state = AgentState(
      conversation = Conversation(messages),
      tools = new ToolRegistry(Seq.empty)
    )

    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messageCount shouldBe 10
  }

  it should "preserve system message when configured" in {
    val config = ContextWindowConfig(
      maxMessages = Some(5),
      preserveSystemMessage = true
    )
    val messages = Seq(SystemMessage("System")) ++ (1 to 10).map(i => UserMessage(s"Msg $i"))
    val state = AgentState(Conversation(messages), new ToolRegistry(Seq.empty))

    val pruned = AgentState.pruneConversation(state, config)

    pruned.conversation.messages.head.role shouldBe MessageRole.System
    pruned.conversation.messageCount shouldBe 5
  }

  it should "use custom pruning strategy" in {
    val customStrategy = PruningStrategy.Custom { messages =>
      messages.filter(_.role != MessageRole.Tool)  // Remove all tool messages
    }
    val config = ContextWindowConfig(pruningStrategy = customStrategy)

    // Test custom pruning
  }
}

class AgentStatePersistenceSpec extends AnyFlatSpec with Matchers {

  "AgentState.toJson/fromJson" should "round-trip correctly" in {
    val tools = new ToolRegistry(Seq(...))
    val state = AgentState(...)

    val json = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, tools)

    loaded shouldBe Right(state.copy(tools = tools))  // Tools are not serialized
  }

  "AgentState.saveToFile/loadFromFile" should "persist to disk" in {
    val tempFile = Files.createTempFile("agent-state", ".json")
    val tools = new ToolRegistry(Seq(...))
    val state = AgentState(...)

    AgentState.saveToFile(state, tempFile.toString) shouldBe Right(())
    val loaded = AgentState.loadFromFile(tempFile.toString, tools)

    loaded shouldBe Right(state.copy(tools = tools))
  }
}
```

### Integration Tests

```scala
class AgentMultiTurnIntegrationSpec extends AnyFlatSpec with Matchers {

  "Multi-turn conversation" should "work end-to-end with real LLM" in {
    // This test requires API key - mark as integration test
    val result = for {
      providerConfig <- Llm4sConfig.provider()
      client <- LLMConnect.getClient(providerConfig)
      tools = new ToolRegistry(Seq(...))
      agent = new Agent(client)

      state1 <- agent.run("What's 2+2?", tools)
      state2 <- agent.continueConversation(state1, "Now multiply that by 3")

    } yield state2

    result.isRight shouldBe true
    result.foreach { state =>
      state.conversation.messageCount should be > 4
      state.status shouldBe AgentStatus.Complete
    }
  }
}
```

---

## Migration Guide

### From Imperative to Functional Style

#### Old Style (with `var`):

```scala
var state = agent.initialize(query, tools)
var stepCount = 0

while (state.status == AgentStatus.InProgress && stepCount < 10) {
  agent.runStep(state) match {
    case Right(newState) =>
      state = newState
      stepCount += 1
    case Left(error) =>
      state = state.withStatus(AgentStatus.Failed(error.toString))
  }
}
```

#### New Style (functional):

```scala
@tailrec
def runSteps(state: AgentState, remaining: Int): Result[AgentState] = {
  if (remaining == 0 || state.status == AgentStatus.Complete) {
    Right(state)
  } else {
    agent.runStep(state) match {
      case Right(newState) => runSteps(newState, remaining - 1)
      case Left(error) => Left(error)
    }
  }
}

val finalState = runSteps(agent.initialize(query, tools), maxSteps = 10)
```

Or simply use the built-in `agent.run()` which already does this!

#### Old Style (manual multi-turn):

```scala
val state1 = agent.initialize("First query", tools)
val result1 = agent.run(state1, None, None, false)

val state2 = result1.map { s =>
  s.copy(
    conversation = s.conversation.addMessage(UserMessage("Second query")),
    userQuery = "Second query",
    status = AgentStatus.InProgress
  )
}

val result2 = state2.flatMap(s => agent.run(s, None, None, false))
```

#### New Style (continuation API):

```scala
val result = for {
  state1 <- agent.run("First query", tools)
  state2 <- agent.continueConversation(state1, "Second query")
} yield state2
```

### Breaking Changes

1. **`AgentState.userQuery` renamed to `initialQuery` and made optional**
   - **Impact:** Low - mostly internal field
   - **Migration:** Update code that reads `state.userQuery` to `state.initialQuery.getOrElse("")`

2. **None currently** - all changes are additive

---

## Future Considerations

### Potential Enhancements

1. **Conversation Branching**
   ```scala
   // Fork a conversation to explore alternative paths
   def fork(state: AgentState): AgentState
   ```

2. **Conversation Merging**
   ```scala
   // Merge two conversation branches (complex!)
   def merge(state1: AgentState, state2: AgentState): Result[AgentState]
   ```

3. **Conversation Replay**
   ```scala
   // Replay a conversation with different tools or prompts
   def replay(state: AgentState, newTools: ToolRegistry): Result[AgentState]
   ```

4. **Token Counting Integration**
   ```scala
   // Integration with modules/core/src/main/scala/org/llm4s/context/tokens/
   def estimateTokens(state: AgentState): Int
   ```

5. **Automatic Summarization**
   ```scala
   // Use LLM to summarize old parts of conversation before pruning
   def summarizeAndPrune(state: AgentState, config: ContextWindowConfig): AsyncResult[AgentState]
   ```

6. **Conversation Templates**
   ```scala
   // Pre-defined conversation flows
   object ConversationTemplates {
     def questionAnswer: ConversationTemplate
     def debuggingSession: ConversationTemplate
     def researchTask: ConversationTemplate
   }
   ```

---

## Conclusion

This design maintains llm4s's **functional programming principles** while providing a **clean, ergonomic API** for multi-turn conversations:

✅ **Pure Functions** - No mutable state, all operations return new states
✅ **Explicit State Flow** - State threading visible in code
✅ **Immutable Data** - All structures immutable
✅ **Composable** - Works well with `Result` and for-comprehensions
✅ **Type-Safe** - Leverages Scala's type system
✅ **Easy to Use** - Helper methods reduce boilerplate
✅ **Production-Ready** - Context window management, persistence support

The API is **simpler than OpenAI's** (no mutable session objects) while being **more correct** (functional, explicit) and **more powerful** (compile-time safety, composability).

This is the **llm4s way** - functional purity with practical ergonomics.

---

**Next Steps:**
1. Review and approve design
2. Begin Phase 1 implementation
3. Gather feedback from initial users
4. Iterate based on real-world usage
