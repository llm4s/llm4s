---
layout: page
title: Memory System
nav_order: 3
parent: Agents
grand_parent: User Guide
---

# Memory System
{: .no_toc }

Persistent context and knowledge for agents across conversations.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The LLM4S Memory System provides:

- **Short-term memory** - Conversation context within a session
- **Long-term memory** - Persistent facts and knowledge
- **Semantic search** - Find relevant context using embeddings
- **Entity tracking** - Remember information about people, places, things
- **Multiple backends** - In-memory, SQLite, vector stores

---

## Dependency

{: .note }
> Not yet published. `llm4s-memory` exists in the build as of
> [#1129](https://github.com/llm4s/llm4s/issues/1129) but ships in the next release;
> in `0.4.1` and earlier this code is inside `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-memory" % llm4sVersion
```

The Postgres backend is a separate artifact - see
[Postgres Memory Store](../../reference/postgres-memory-store.md). Package names are unchanged
either way, so `org.llm4s.agent.memory.*` imports keep working.

---

## Quick Start

```scala
import org.llm4s.agent.memory._

// Create a memory manager
val result = for {
  manager <- SimpleMemoryManager.empty

  // Record user facts
  m1 <- manager.recordUserFact(
    content = "Prefers Scala over Java",
    userId = Some("user-123"),
    importance = Some(0.9)
  )

  // Record entity knowledge
  m2 <- m1.recordEntityFact(
    entityId = EntityId("anthropic"),
    content = "AI company that created Claude",
    importance = Some(0.8)
  )

  // Get relevant context for a query
  context <- m2.getRelevantContext("Tell me about Scala programming")
} yield context

result match {
  case Right(ctx) => println(s"Context: $ctx")
  case Left(err) => println(s"Error: $err")
}
```

---

## Memory Types

| Type | Purpose | Example |
|------|---------|---------|
| `Conversation` | Chat history | "User asked about weather" |
| `UserFact` | User preferences/info | "Prefers dark mode" |
| `Entity` | Knowledge about entities | "Paris is capital of France" |
| `Knowledge` | External knowledge | "Scala 3 released in 2021" |
| `Task` | Task outcomes | "Generated report successfully" |
| `Custom` | Application-specific | Any custom memory type |

---

## Memory Manager

The `MemoryManager` is the main interface for working with memory:

```scala
import org.llm4s.agent.memory._

// Create with default in-memory store
val manager = SimpleMemoryManager.empty

// Create with configuration
val configuredManager = SimpleMemoryManager(
  config = MemoryConfig(
    autoRecordMessages = true,
    autoExtractEntities = false,
    defaultImportance = 0.5,
    contextTokenBudget = 2000,
    consolidationEnabled = true
  ),
  store = new InMemoryStore()
)
```

### Configuration Options

| Option | Default | Description |
|--------|---------|-------------|
| `autoRecordMessages` | `true` | Automatically record conversation turns |
| `autoExtractEntities` | `false` | Extract entities from messages via LLM |
| `defaultImportance` | `0.5` | Default importance score (0-1) |
| `contextTokenBudget` | `2000` | Max tokens for context retrieval |
| `consolidationEnabled` | `true` | Enable memory consolidation |

---

## Recording Memory

### User Facts

```scala
// Record a user preference
val result = manager.recordUserFact(
  content = "Prefers functional programming",
  userId = Some("user-123"),
  importance = Some(0.9)
)
```

### Entity Knowledge

```scala
// Record knowledge about an entity
val result = manager.recordEntityFact(
  entityId = EntityId("scala-lang"),
  content = "Scala is a JVM language combining OOP and FP",
  importance = Some(0.8)
)
```

### External Knowledge

```scala
// Record external knowledge
val result = manager.recordKnowledge(
  content = "The latest LLM4S version is 0.5.0",
  source = Some("release-notes"),
  importance = Some(0.7)
)
```

### Task Outcomes

```scala
// Record a task result
val result = manager.recordTask(
  content = "Successfully generated quarterly report",
  taskId = Some("task-456"),
  importance = Some(0.6)
)
```

### Conversation Messages

```scala
import org.llm4s.llmconnect.model._

// Record a conversation turn
val result = manager.recordMessage(
  message = UserMessage("What's the weather in Paris?"),
  conversationId = Some(ConversationId("conv-789"))
)

// Record entire conversation
val result = manager.recordConversation(
  conversation = conversation,
  conversationId = ConversationId("conv-789")
)
```

---

## Retrieving Memory

### Relevant Context

Get context relevant to a query using semantic search:

```scala
val context = manager.getRelevantContext(
  query = "Tell me about Scala programming",
  maxTokens = Some(1000),
  memoryTypes = Some(Set(MemoryType.UserFact, MemoryType.Knowledge))
)
```

### Conversation History

```scala
val history = manager.getConversationContext(
  conversationId = ConversationId("conv-789"),
  limit = Some(10)
)
```

### Entity Context

```scala
val entityInfo = manager.getEntityContext(
  entityId = EntityId("anthropic"),
  limit = Some(5)
)
```

### User Context

```scala
val userInfo = manager.getUserContext(
  userId = "user-123",
  limit = Some(10)
)
```

---

## Memory Stores

### In-Memory Store

Fast but volatile - loses data on restart:

```scala
import org.llm4s.agent.memory.InMemoryStore

val store = new InMemoryStore()
val manager = SimpleMemoryManager(store = store)
```

### SQLite Store

Persistent local storage:

```scala
import org.llm4s.agent.memory.SQLiteMemoryStore

// File-based (persistent)
val store = SQLiteMemoryStore.file("/tmp/memory.db")

// In-memory SQLite (fast, volatile)
val store = SQLiteMemoryStore.inMemory()

val manager = SimpleMemoryManager(store = store)
```

### Vector Store

Semantic search with embeddings:

```scala
import org.llm4s.agent.memory.VectorMemoryStore
import org.llm4s.agent.memory.EmbeddingService

// Create embedding service
val embeddingService = new EmbeddingService(embeddingClient)

// Create vector store
val store = new VectorMemoryStore(embeddingService)

val manager = SimpleMemoryManager(store = store)
```

---

## Memory with Agents

### Injecting Context

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)

  // Get memory manager with existing data
  manager <- loadMemoryManager()

  // Get relevant context for the query
  context <- manager.getRelevantContext(userQuery)

  // Build enhanced system message with context
  systemMessage = s"""You are a helpful assistant.
    |
    |Relevant context from memory:
    |$context""".stripMargin

  // Run agent with context-enhanced system message
  state <- agent.run(
    query = userQuery,
    tools = tools,
    systemMessage = Some(SystemMessage(systemMessage))
  )

  // Record the conversation
  _ <- manager.recordConversation(state.conversation, conversationId)
} yield state
```

### Automatic Recording

```scala
val manager = SimpleMemoryManager(
  config = MemoryConfig(
    autoRecordMessages = true  // Automatically record all messages
  )
)

// Messages are recorded automatically when using this manager
```

---

## Semantic Search

### With Embeddings

```scala
import org.llm4s.agent.memory._

// Setup embedding service
val embeddingService = new EmbeddingService(embeddingClient)
val store = new VectorMemoryStore(embeddingService)
val manager = SimpleMemoryManager(store = store)

// Record knowledge
for {
  m1 <- manager.recordKnowledge("Paris is the capital of France")
  m2 <- m1.recordKnowledge("Berlin is the capital of Germany")
  m3 <- m2.recordKnowledge("Rome is the capital of Italy")

  // Semantic search finds relevant memories
  results <- m3.store.search("European capitals", topK = 2)
} yield results
```

### Search with Filters

```scala
val filter = MemoryFilter(
  memoryTypes = Some(Set(MemoryType.Knowledge)),
  minImportance = Some(0.7),
  userId = Some("user-123"),
  entityId = None,
  afterTimestamp = None,
  beforeTimestamp = None
)

val results = store.search(
  query = "programming languages",
  topK = 5,
  filter = Some(filter)
)
```

---

## Memory Consolidation

Automatically summarize and consolidate old memories:

```scala
val manager = SimpleMemoryManager(
  config = MemoryConfig(
    consolidationEnabled = true
  )
)

// Manually trigger consolidation
val result = manager.consolidateMemories(
  olderThan = java.time.Duration.ofDays(7),
  maxToConsolidate = 100
)
```

---

## Entity Extraction

Extract entities from messages using LLM:

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)

  manager = SimpleMemoryManager(
    config = MemoryConfig(
      autoExtractEntities = true
    ),
    llmClient = Some(client)  // Required for entity extraction
  )

  // Record message - entities extracted automatically
  m1 <- manager.recordMessage(
    UserMessage("I work at Anthropic in San Francisco")
  )
  // Entities "Anthropic" and "San Francisco" are automatically extracted
} yield m1
```

### Manual Extraction

```scala
val entities = manager.extractEntities(
  content = "Claude is an AI assistant created by Anthropic"
)
// Returns: Seq(EntityId("claude"), EntityId("anthropic"))
```

---

## Memory Statistics

```scala
val stats = manager.stats()

println(s"Total memories: ${stats.totalCount}")
println(s"By type: ${stats.countByType}")
println(s"Oldest: ${stats.oldestTimestamp}")
println(s"Newest: ${stats.newestTimestamp}")
```

---

## Persistence Patterns

### Save and Load

```scala
// Using SQLite for persistence
val result = for {
  // Create persistent store
  store <- SQLiteMemoryStore.file("/path/to/memory.db")
  manager = SimpleMemoryManager(store = store)

  // Record memories (automatically persisted)
  m1 <- manager.recordUserFact("User preference", Some("user-1"))

  // On next session, create manager with same store path
  // All memories are automatically loaded
} yield m1
```

### Cross-Session Memory

```scala
object PersistentMemory {
  private val dbPath = "/path/to/memory.db"

  def getManager(): Result[SimpleMemoryManager] = {
    for {
      store <- SQLiteMemoryStore.file(dbPath)
    } yield SimpleMemoryManager(store = store)
  }
}

// Session 1
val result1 = for {
  manager <- PersistentMemory.getManager()
  m <- manager.recordUserFact("Likes Scala", Some("user-1"))
} yield m

// Session 2 (later)
val result2 = for {
  manager <- PersistentMemory.getManager()
  context <- manager.getUserContext("user-1")
  // context includes "Likes Scala" from session 1
} yield context
```

---

## Best Practices

### 1. Set Appropriate Importance

```scala
// High importance - core user preferences
manager.recordUserFact("Primary programming language is Scala", importance = Some(0.9))

// Medium importance - useful but not critical
manager.recordKnowledge("Attended ScalaDays 2024", importance = Some(0.6))

// Low importance - ephemeral information
manager.recordTask("Ran tests at 10am", importance = Some(0.3))
```

### 2. Use Specific Memory Types

```scala
// Don't use generic Knowledge for everything
// Use specific types for better retrieval

// For user preferences
manager.recordUserFact(...)

// For entity-specific information
manager.recordEntityFact(...)

// For external knowledge
manager.recordKnowledge(...)
```

### 3. Manage Context Token Budget

```scala
val manager = SimpleMemoryManager(
  config = MemoryConfig(
    contextTokenBudget = 2000  // Limit context size
  )
)

// Context retrieval respects the budget
val context = manager.getRelevantContext(query)
// Returns <= 2000 tokens of context
```

### 4. Clean Up Old Memories

```scala
// Consolidate old memories periodically
manager.consolidateMemories(
  olderThan = java.time.Duration.ofDays(30),
  maxToConsolidate = 500
)

// Or delete old, low-importance memories
store.deleteMemories(
  filter = MemoryFilter(
    maxImportance = Some(0.3),
    beforeTimestamp = Some(thirtyDaysAgo)
  )
)
```

---

## Examples

| Example | Description |
|---------|-------------|
| [BasicMemoryExample](/examples/#memory-examples) | Getting started with memory |
| [ConversationMemoryExample](/examples/#memory-examples) | Conversation history management |
| [MemoryWithAgentExample](/examples/#memory-examples) | Integrating memory with agents |
| [SQLiteMemoryExample](/examples/#memory-examples) | Persistent SQLite storage |
| [VectorMemoryExample](/examples/#memory-examples) | Semantic search with embeddings |

[Browse all examples →](/examples/)

---

## Next Steps

- [Handoffs Guide](handoffs) - Agent-to-agent delegation
- [Streaming Guide](streaming) - Real-time execution events
- [Guardrails Guide](guardrails) - Input/output validation
