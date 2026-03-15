# Phase 1.4: Memory System Design

> **Status:** Implementation Complete (Core + SQLite Backend + Vector Store + LLM-Powered Consolidation)
> **Last Updated:** 2026-02-07
> **Related:** Agent Framework Roadmap, CrewAI Feature Parity

## Executive Summary

Phase 1.4 adds a comprehensive memory system to the LLM4S agent framework. The system enables agents to:
- Remember information across conversations
- Track facts about entities (people, organizations, concepts)
- Store and retrieve knowledge from external sources
- Learn and retain user preferences
- Search memories semantically
- Consolidate and summarize memories using LLMs

This brings LLM4S to feature parity with CrewAI's memory capabilities.

## Motivation

### Why Memory Matters

Modern LLM applications require persistent context beyond single conversations:

1. **Personalization**: Remember user preferences across sessions
2. **Entity Tracking**: Maintain facts about people, projects, concepts
3. **Knowledge Retrieval**: Access domain-specific information (RAG)
4. **Learning**: Improve responses based on past interactions
5. **Context Continuity**: Resume conversations seamlessly

### Gap Analysis (vs. CrewAI)

| Feature | CrewAI | LLM4S (Before) | LLM4S (After) |
|---------|--------|----------------|---------------|
| Short-term Memory | ✅ | ❌ | ✅ |
| Long-term Memory | ✅ | ❌ | ✅ |
| Entity Memory | ✅ | ❌ | ✅ |
| Knowledge Storage | ✅ | ❌ | ✅ |
| Semantic Search | ✅ | ❌ | ✅ |
| Memory Consolidation | ✅ | ❌ | ✅ |

## Architecture

### Core Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        MemoryManager                            │
│  - High-level API for agents                                    │
│  - Context formatting                                           │
│  - Entity extraction (planned)                                  │
└─────────────────────────┬───────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                        MemoryStore                              │
│  - Storage backend trait                                        │
│  - CRUD operations                                              │
│  - Search & filtering                                           │
└─────────────────────────┬───────────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
    ┌──────────┐    ┌──────────┐    ┌──────────┐
    │InMemory  │    │SQLite    │    │Vector    │
    │Store     │    │Store     │    │Store     │
    │(done)    │    │(done)    │    │(done)    │
    └──────────┘    └──────────┘    └──────────┘
```

### Memory Types

```scala
sealed trait MemoryType {
  def name: String
}

object MemoryType {
  case object Conversation extends MemoryType  // Chat history
  case object Entity extends MemoryType        // Facts about entities
  case object Knowledge extends MemoryType     // External knowledge
  case object UserFact extends MemoryType      // User preferences
  case object Task extends MemoryType          // Task outcomes
  case class Custom(name: String) extends MemoryType
}
```

### Memory Data Model

```scala
final case class Memory(
  id: MemoryId,
  content: String,
  memoryType: MemoryType,
  metadata: Map[String, String] = Map.empty,
  timestamp: Instant = Instant.now(),
  importance: Option[Double] = None,          // 0.0 to 1.0
  embedding: Option[Array[Float]] = None      // For semantic search
)
```

### Key Design Decisions

1. **Immutable Data Structures**: All operations return new instances (functional style)
2. **Pluggable Backends**: `MemoryStore` trait allows different implementations
3. **Composable Filters**: Filters can be combined with `&&`, `||`, `!` operators
4. **Type-Safe IDs**: `MemoryId`, `EntityId` prevent ID mixups
5. **Metadata-Driven**: Flexible metadata for filtering without schema changes

## API Reference

### MemoryStore Trait

```scala
trait MemoryStore {
  def store(memory: Memory): Result[MemoryStore]
  def get(id: MemoryId): Result[Option[Memory]]
  def recall(filter: MemoryFilter, limit: Int): Result[Seq[Memory]]
  def search(query: String, topK: Int, filter: MemoryFilter): Result[Seq[ScoredMemory]]
  def delete(id: MemoryId): Result[MemoryStore]
  def update(id: MemoryId, fn: Memory => Memory): Result[MemoryStore]
  def count(filter: MemoryFilter): Result[Long]
  def clear(): Result[MemoryStore]
}
```

### MemoryManager Trait

```scala
trait MemoryManager {
  def store: MemoryStore

  // Recording
  def recordMessage(message: Message, conversationId: String, importance: Option[Double]): Result[MemoryManager]
  def recordConversation(messages: Seq[Message], conversationId: String): Result[MemoryManager]
  def recordEntityFact(entityId: EntityId, name: String, fact: String, entityType: String, importance: Option[Double]): Result[MemoryManager]
  def recordUserFact(fact: String, userId: Option[String], importance: Option[Double]): Result[MemoryManager]
  def recordKnowledge(content: String, source: String, metadata: Map[String, String]): Result[MemoryManager]
  def recordTask(description: String, outcome: String, success: Boolean, importance: Option[Double]): Result[MemoryManager]

  // Retrieval
  def getRelevantContext(query: String, maxTokens: Int, filter: MemoryFilter): Result[String]
  def getConversationContext(conversationId: String, maxMessages: Int): Result[String]
  def getEntityContext(entityId: EntityId): Result[String]
  def getUserContext(userId: Option[String]): Result[String]

  // Management
  def consolidateMemories(olderThan: Instant, minCount: Int): Result[MemoryManager]
  def extractEntities(text: String, conversationId: Option[String]): Result[MemoryManager]
  def stats: Result[MemoryStats]
}
```

### MemoryFilter

```scala
// Basic filters
MemoryFilter.All                              // Match everything
MemoryFilter.None                             // Match nothing
MemoryFilter.ByType(MemoryType.Conversation)  // By type
MemoryFilter.ByMetadata("key", "value")       // By metadata
MemoryFilter.MinImportance(0.5)               // By importance
MemoryFilter.ContentContains("scala")         // By content

// Time filters
MemoryFilter.after(instant)
MemoryFilter.before(instant)
MemoryFilter.between(start, end)

// Entity/Conversation filters
MemoryFilter.forEntity(entityId)
MemoryFilter.forConversation(conversationId)

// Combinators
filter1 && filter2                            // AND
filter1 || filter2                            // OR
!filter                                       // NOT
MemoryFilter.all(f1, f2, f3)                 // All must match
MemoryFilter.any(f1, f2, f3)                 // Any must match
```

## Usage Examples

### Basic Usage

```scala
import org.llm4s.agent.memory._

// Create a memory manager
val manager = SimpleMemoryManager.empty

// Record a user fact
val updated = manager.recordUserFact(
  "User prefers Scala over Java",
  userId = Some("user123"),
  importance = Some(0.8)
)

// Record entity knowledge
val entityId = EntityId.fromName("Scala")
val withEntity = updated.flatMap(_.recordEntityFact(
  entityId,
  "Scala",
  "Scala is a programming language created in 2004",
  "technology",
  Some(0.9)
))

// Search memories
val results = withEntity.flatMap(_.store.search("programming language", topK = 5))

// Get formatted context for LLM
val context = withEntity.flatMap(_.getRelevantContext("Tell me about Scala"))
```

### With Agent Integration

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Setup
val manager = SimpleMemoryManager.empty
  .recordUserFact("Expert Scala developer", Some("user1"), None)
  .getOrElse(SimpleMemoryManager.empty)

// Get context for prompt
val userContext = manager.getUserContext(Some("user1")).getOrElse("")

// Run agent with context
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  state <- agent.run(
    query = "Help me optimize this code",
    tools = ToolRegistry.empty,
    systemPromptAddition = Some(s"User context:\n$userContext")
  )
  // Record the conversation
  updatedManager <- manager.recordConversation(state.conversation.messages.toSeq, "session-1")
} yield updatedManager
```

### Filtering Memories

```scala
// High-importance user facts from the last week
val filter = MemoryFilter.userFacts &&
             MemoryFilter.important(0.7) &&
             MemoryFilter.after(Instant.now().minus(7, ChronoUnit.DAYS))

// Entity facts OR knowledge, excluding conversations
val filter2 = (MemoryFilter.entities || MemoryFilter.knowledge) && !MemoryFilter.conversations

// Custom filter
val customFilter = MemoryFilter.Custom(m => m.content.length > 100)

// Use with recall
store.recall(filter, limit = 10)
```

## File Locations

### Core Files

| File | Purpose |
|------|---------|
| `modules/core/.../memory/Memory.scala` | Core types (Memory, MemoryId, EntityId, MemoryType) |
| `modules/core/.../memory/MemoryStore.scala` | Storage backend trait |
| `modules/core/.../memory/MemoryManager.scala` | High-level manager trait |
| `modules/core/.../memory/MemoryFilter.scala` | Composable filter predicates |
| `modules/core/.../memory/InMemoryStore.scala` | In-memory implementation |
| `modules/core/.../memory/SQLiteMemoryStore.scala` | SQLite persistent implementation |
| `modules/core/.../memory/VectorMemoryStore.scala` | Vector store with semantic search |
| `modules/core/.../memory/EmbeddingService.scala` | Embedding generation services |
| `modules/core/.../memory/SimpleMemoryManager.scala` | Basic manager implementation |
| `modules/core/.../memory/package.scala` | Package documentation |

### Tests

| File | Purpose |
|------|---------|
| `.../memory/MemorySpec.scala` | Memory and ID tests |
| `.../memory/MemoryFilterSpec.scala` | Filter tests |
| `.../memory/InMemoryStoreSpec.scala` | In-memory store tests |
| `.../memory/SQLiteMemoryStoreSpec.scala` | SQLite store tests |
| `.../memory/VectorMemoryStoreSpec.scala` | Vector store tests |
| `.../memory/SimpleMemoryManagerSpec.scala` | Manager tests |

### Samples

| File | Purpose |
|------|---------|
| `.../samples/memory/BasicMemoryExample.scala` | Basic usage |
| `.../samples/memory/ConversationMemoryExample.scala` | Conversation tracking |
| `.../samples/memory/MemoryWithAgentExample.scala` | Agent integration |
| `.../samples/memory/SQLiteMemoryExample.scala` | SQLite persistent storage |
| `.../samples/memory/VectorMemoryExample.scala` | Vector-based semantic search |
| `.../samples/rag/DocumentQAExample.scala` | **Complete RAG pipeline** - Document Q&A with embeddings |

## Configuration

### MemoryStoreConfig

```scala
final case class MemoryStoreConfig(
  maxMemories: Option[Int] = None,           // Limit stored memories
  defaultEmbeddingDimensions: Int = 1536,    // For vector embeddings
  enableAutoCleanup: Boolean = false,        // Auto-remove old memories
  cleanupThreshold: Int = 10000              // Trigger cleanup threshold
)

// Presets
MemoryStoreConfig.default
MemoryStoreConfig.testing
MemoryStoreConfig.production(maxMemories = 100000)
```

### MemoryManagerConfig

```scala
final case class MemoryManagerConfig(
  autoRecordMessages: Boolean = true,        // Auto-record conversations
  autoExtractEntities: Boolean = false,      // Auto-extract entities
  defaultImportance: Double = 0.5,           // Default importance score
  contextTokenBudget: Int = 2000,            // Max context tokens
  consolidationEnabled: Boolean = false      // Enable consolidation
)
```

## SQLite Backend (Phase 1.4.1)

The SQLite backend provides persistent memory storage with the following features:

### Features
- **Persistent Storage**: Memories survive application restarts
- **Full-Text Search**: SQLite FTS5 for efficient content searching
- **Schema Versioning**: Built-in version tracking for future migrations
- **All MemoryFilter Support**: Complete filter implementation via SQL

### Usage

```scala
import org.llm4s.agent.memory._

// Create file-based store
val store = SQLiteMemoryStore("/path/to/memories.db")

// Or in-memory for testing
val testStore = SQLiteMemoryStore.inMemory()

// Use with SimpleMemoryManager
val manager = store.flatMap { s =>
  Right(SimpleMemoryManager(MemoryManagerConfig.default, s))
}

// Remember to close when done
store.foreach(_.close())
```

### Schema

The SQLite schema includes:
- `memories` table with all Memory fields
- Indexes for common queries (type, conversation, entity, timestamp, importance)
- `memories_fts` FTS5 virtual table for full-text search
- `schema_version` table for migrations

### Sample

```bash
sbt "samples/runMain org.llm4s.samples.memory.SQLiteMemoryExample"
```

## Vector Store Backend (Phase 1.4.2)

The Vector Store backend adds semantic search capabilities using embeddings:

### Features
- **Embedding Generation**: Via LLM providers (OpenAI, VoyageAI) or mock service for testing
- **Semantic Search**: Cosine similarity-based similarity search
- **Fallback Support**: Falls back to FTS5 keyword search if vector search fails
- **Batch Embedding**: Efficient bulk embedding with `embedAll()`
- **Stats Monitoring**: Track embedding coverage with `vectorStats()`

### Embedding Services

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig

// For production: Use LLM provider
val embeddingService = for {
  (provider, cfg) <- Llm4sConfig.embeddings()
  client <- EmbeddingClient.from(provider, cfg)
  model <- Llm4sConfig.textEmbeddingModel()
} yield LLMEmbeddingService(client, EmbeddingModelConfig(model.modelName, model.dimensions))

// For testing: Use mock service (deterministic, fast)
val mockService = MockEmbeddingService(dimensions = 1536)
```

### Usage

```scala
import org.llm4s.agent.memory._

// Create with mock embeddings (testing)
val testStore = VectorMemoryStore.inMemory()

// Create with real embeddings (production)
val prodStore = for {
  service <- embeddingService
  store <- VectorMemoryStore("/path/to/db", service, MemoryStoreConfig.default)
} yield store

// Semantic search
store.search("programming with no side effects", topK = 5)

// Batch embed unembedded memories
store.embedAll(batchSize = 100)

// Check embedding coverage
store.vectorStats  // Returns VectorStoreStats(total, embedded, dimensions)
```

### Environment Variables

For production embedding services:
```bash
export LLM_EMBEDDING_MODEL=openai/text-embedding-3-small
export OPENAI_API_KEY=sk-...
```

Supported models:
- OpenAI: `text-embedding-ada-002`, `text-embedding-3-small`, `text-embedding-3-large`
- VoyageAI: `voyage-2`, `voyage-large-2`, `voyage-code-2`

### Vector Operations

The `VectorOps` utility provides:
```scala
VectorOps.cosineSimilarity(a, b)   // -1.0 to 1.0
VectorOps.euclideanDistance(a, b)  // Distance metric
VectorOps.normalize(v)              // Unit vector
VectorOps.topKBySimilarity(query, candidates, k)  // Top-K search
```

### Sample

```bash
sbt "samples/runMain org.llm4s.samples.memory.VectorMemoryExample"
```

## LLM-Powered Memory Consolidation (Phase 1.4.3)

The LLM Memory Consolidation feature automatically summarizes and consolidates multiple related memories into concise summaries, reducing storage and improving retrieval efficiency.

### Features
- **LLM-Powered Summarization**: Uses LLM to intelligently merge related memories
- **Conversation Grouping**: Groups memories by conversation ID and timestamp
- **Security Protection**: Built-in prompt injection prevention with system rules
- **Output Validation**: Validates consolidated text length and content
- **Strict Mode**: Optional fail-fast behavior for production reliability
- **Binary Compatibility**: Backward-compatible API with companion apply method

### Configuration

```scala
import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Enable consolidation with custom config
val config = MemoryManagerConfig(
  consolidationEnabled = true,
  consolidationConfig = ConsolidationConfig(
    maxMemoriesPerGroup = 50,    // Max memories per consolidation group
    strictMode = false             // Best-effort (false) or fail-fast (true)
  )
)

// Use with LLMMemoryManager
val manager = for {
  providerConfig <- Llm4sConfig.provider()
  client         <- LLMConnect.getClient(providerConfig)
  store          <- VectorMemoryStore.inMemory()
} yield LLMMemoryManager(config, store, client)
```

### How It Works

1. **Grouping**: Groups conversation memories by `conversationId` and sorts by timestamp
2. **LLM Summarization**: Sends grouped memories to LLM with security-focused system prompt
3. **Validation**: Checks output is non-empty and under 2000 characters
4. **Replacement**: Replaces old memories with consolidated summary
5. **Metadata Preservation**: Retains conversation ID, uses max timestamp from group

### Security

Built-in protections against prompt injection:
- System message with 5 explicit security rules
- Output length capping (2000 chars)
- Token limits (maxTokens=500, temperature=0.3)
- Content validation (non-empty check)

### Strict Mode

```scala
// Best-effort mode (default): Log errors, continue operation
ConsolidationConfig.default

// Strict mode: Fail fast on any consolidation error
ConsolidationConfig.strict
```

### Sample

```bash
sbt "samples/runMain org.llm4s.samples.memory.MemoryConsolidationExample"
```

## Future Enhancements

### Phase 1.4.3: Additional LLM-Powered Features
- Automatic entity extraction using LLM
- ~~Memory consolidation/summarization~~ ✅ Done (Phase 1.4.3)
- Importance scoring via LLM

### Phase 1.4.4: Agent Integration
- `MemoryAwareAgent` trait
- Automatic conversation recording
- Context injection into prompts

## Testing

Run memory tests:
```bash
sbt "core/testOnly org.llm4s.agent.memory.*"
```

Run samples:
```bash
sbt "samples/runMain org.llm4s.samples.memory.BasicMemoryExample"
sbt "samples/runMain org.llm4s.samples.memory.ConversationMemoryExample"
sbt "samples/runMain org.llm4s.samples.memory.MemoryWithAgentExample"  # Requires API key
sbt "samples/runMain org.llm4s.samples.memory.SQLiteMemoryExample"
sbt "samples/runMain org.llm4s.samples.memory.VectorMemoryExample"
```

## Summary

Phase 1.4 delivers a functional, composable memory system that:
- ✅ Supports multiple memory types (conversation, entity, knowledge, user fact, task)
- ✅ Provides composable filtering with intuitive operators
- ✅ Includes working in-memory implementation
- ✅ Includes SQLite persistent storage with FTS5 search (Phase 1.4.1)
- ✅ Includes vector store with semantic search (Phase 1.4.2)
- ✅ Includes LLM-powered memory consolidation (Phase 1.4.3)
- ✅ Offers high-level manager API for common patterns
- ✅ Has comprehensive test coverage (130+ tests)
- ✅ Includes usage samples

Future phases will add automatic entity extraction and deeper agent integration.
