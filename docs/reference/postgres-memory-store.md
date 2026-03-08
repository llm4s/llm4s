---
layout: page
title: Postgres Memory Store
nav_order: 10
parent: Reference
---

# Postgres Memory Store

`PostgresMemoryStore` is a PostgreSQL backed implementation of the `MemoryStore` abstraction used by LLM4S agents. It provides durable persistence for agent memories, including metadata and optional vector embeddings.

---

## Features

- **Durable Persistence**  
  Stores agent memories in PostgreSQL using JDBC and HikariCP.

- **JSONB Metadata**  
  Flexible, schema less metadata stored as `JSONB`, with GIN indexing for efficient filtering.

- **Vector Storage (pgvector)**  
  Automatically enables the `pgvector` extension and stores embeddings using PostgreSQL’s `vector` type.

- **SQL Safety by Design**  
  Strict validation of table names and metadata keys to prevent SQL injection.

- **Explicit Error Handling**  
  All operations return a `Result` type instead of throwing exceptions.

---

## Prerequisites

1. **PostgreSQL**  
   A running PostgreSQL instance.

2. **pgvector Extension Support**  
   The database user must have permission to create extensions.

---

## Configuration

The store is configured using `PostgresMemoryStore.Config`.

### Basic Setup

```scala
import org.llm4s.agent.memory.PostgresMemoryStore

val config = PostgresMemoryStore.Config(
  host = "localhost",
  port = 5432,
  database = "my_app_db",
  user = "postgres",
  password = "secure_password",
  tableName = "agent_memories"
)

// Initializes the connection pool and database schema
// Tables and indexes are created only if they do not exist
val storeResult = PostgresMemoryStore(config)
```
### Configuration Options

| Parameter     | Default            | Description                   |
| ------------- | ------------------ | ----------------------------- |
| `host`        | `"localhost"`      | Database host                 |
| `port`        | `5432`             | Database port                 |
| `database`    | `"postgres"`       | Database name                 |
| `user`        | `"postgres"`       | Database user                 |
| `password`    | `""`               | Database password             |
| `tableName`   | `"agent_memories"` | Table used for memory storage |
| `maxPoolSize` | `10`               | Maximum HikariCP pool size    |

## Security & Validation

### Table Name Validation

Table names are validated eagerly using the following pattern:
```
^[a-zA-Z_][a-zA-Z0-9_]{0,62}$
```


This prevents SQL injection via identifier interpolation and ensures failures occur during configuration rather than at query time.

If validation fails, configuration throws an `IllegalArgumentException`.

---

### Metadata Key Validation

Metadata keys used in filters are validated using:

```
^[a-zA-Z_][a-zA-Z0-9_]*$
```
This prevents unsafe JSON path interpolation.

Invalid keys result in a `ProcessingError`.

---

## Schema Details

On initialization, the store executes the following DDL (if not already present):

```sql
-- Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- Create memory table
CREATE TABLE IF NOT EXISTS agent_memories (
    id TEXT PRIMARY KEY,
    content TEXT NOT NULL,
    memory_type TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    importance DOUBLE PRECISION,
    embedding vector
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_agent_memories_type
  ON agent_memories(memory_type);

CREATE INDEX IF NOT EXISTS idx_agent_memories_created
  ON agent_memories(created_at);

CREATE INDEX IF NOT EXISTS idx_agent_memories_metadata
  ON agent_memories USING GIN(metadata);

-- Optimized index for conversation lookups
CREATE INDEX IF NOT EXISTS idx_agent_memories_conversation
  ON agent_memories ((metadata->>'conversation_id'));

```
## Querying and Filters

`MemoryFilter` values are translated directly into SQL `WHERE` clauses.

### Supported Filters

| Filter          | Scala Example                     | Generated SQL                         |
| --------------- | --------------------------------- | ------------------------------------- |
| All             | `MemoryFilter.All`                | `TRUE`                                |
| None            | `MemoryFilter.None`               | `FALSE`                               |
| By Entity       | `MemoryFilter.ByEntity(id)`       | `metadata->>'entity_id' = ?`          |
| By Conversation | `MemoryFilter.ByConversation(id)` | `metadata->>'conversation_id' = ?`    |
| By Type         | `MemoryFilter.ByType(t)`          | `memory_type = ?`                     |
| By Types        | `MemoryFilter.ByTypes(ts)`        | `memory_type IN (?, ...)`             |
| Time Range      | `MemoryFilter.ByTimeRange(a, b)`  | `created_at >= ? AND created_at <= ?` |
| Importance      | `MemoryFilter.MinImportance(x)`   | `importance >= ?`                     |
| Metadata        | `MemoryFilter.ByMetadata(k, v)`   | `metadata->>'k' = ?`                  |
| And             | `MemoryFilter.And(left, right)`   | `(left_sql AND right_sql)`            |
| Or              | `MemoryFilter.Or(left, right)`    | `(left_sql OR right_sql)`             |
| Not             | `MemoryFilter.Not(filter)`        | `NOT (filter_sql)`                    |

All dynamic values are passed using prepared statements.

### Compound Filter Examples

Compound filters can be nested arbitrarily:

```scala
// Tasks OR Conversations
val orFilter = MemoryFilter.Or(
  MemoryFilter.ByType(MemoryType.Task),
  MemoryFilter.ByType(MemoryType.Conversation)
)
// Generated: (memory_type = ? OR memory_type = ?)

// Important tasks only
val andFilter = MemoryFilter.And(
  MemoryFilter.ByType(MemoryType.Task),
  MemoryFilter.MinImportance(0.8)
)
// Generated: (memory_type = ? AND importance >= ?)

// Exclude low-importance items
val notFilter = MemoryFilter.Not(MemoryFilter.MinImportance(0.5))
// Generated: NOT (importance >= ?)

// Nested: (A OR B) AND NOT C
val nested = MemoryFilter.And(
  MemoryFilter.Or(
    MemoryFilter.ByType(MemoryType.Task),
    MemoryFilter.ByType(MemoryType.Conversation)
  ),
  MemoryFilter.Not(MemoryFilter.MinImportance(0.9))
)
// Generated: ((memory_type = ? OR memory_type = ?) AND NOT (importance >= ?))
```

## Error Handling

The store follows a functional error handling model.

- No public method throws exceptions

- All operations return Result[T]

- Errors are explicit and must be handled by the caller

## Current Limitations

### Semantic Search
Although the schema supports vector embeddings, calling `search(...)` currently returns a `ProcessingError`. This requires integration with an `EmbeddingService`.

---

## Usage Examples

This section shows how a developer would use `PostgresMemoryStore` in a real application after configuration is complete.

### End-to-End Example (Result-based workflow)

```scala
import java.time.Instant
import org.llm4s.agent.memory.{PostgresMemoryStore, Memory, MemoryFilter, MemoryId, MemoryType}

val config = PostgresMemoryStore.Config(
  host = "localhost",
  port = 5432,
  database = "my_app_db",
  user = "postgres",
  password = "secure_password",
  tableName = "agent_memories"
)

val memory = Memory(
  id = MemoryId("memory-1"),
  content = "Scala enables reliable AI systems",
  memoryType = MemoryType.Knowledge,
  metadata = Map("topic" -> "scala"),
  timestamp = Instant.now(),
  importance = Some(0.8),
  embedding = None
)

val result = for {
  store   <- PostgresMemoryStore(config)
  store2  <- store.store(memory)
  results <- store2.recall(MemoryFilter.ByMetadata("topic", "scala"), limit = 5)
  store3  <- store2.update(MemoryId("memory-1"), m => m.copy(content = "Updated memory content"))
  _       <- store3.delete(MemoryId("memory-1"))
} yield results
```

---

## Troubleshooting

| Problem | Cause | Fix |
|--------|------|-----|
| `extension "vector" does not exist` | pgvector not installed or enabled | Run `CREATE EXTENSION vector;` in your database |
| Connection timeout or pool exhaustion | Incorrect DB URL or pool size too small | Check host/port/credentials and `maxPoolSize` config |
| Embedding dimension mismatch | Stored embeddings use a different model size | Use the same embedding model for both storage and queries |
| Table name validation error | Table name does not match allowed pattern | Use only letters, numbers, and underscores, starting with a letter or underscore |
| Metadata key validation errors | Invalid metadata keys in filters | Ensure keys match `^[a-zA-Z_][a-zA-Z0-9_]*$` |
