---
layout: page
title: Postgres Memory Store
description: "PostgresMemoryStore for LLM4S agents: durable memories with metadata and optional vector embeddings in PostgreSQL, with configuration and validation."
search_title: "Postgres memory store for LLM agents in Scala"
nav_order: 10
parent: Reference
---

# Postgres Memory Store

`PostgresMemoryStore` is a PostgreSQL backed implementation of the `MemoryStore` abstraction used by LLM4S agents. It provides durable persistence for agent memories, including metadata and optional vector embeddings.

---

## Dependency

```scala
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % llm4sVersion
```

`PostgresMemoryStore` ships apart from the rest of `org.llm4s.agent.memory` because it is the
only store needing HikariCP and a server-side JDBC driver; `llm4s-memory-postgres` brings both,
and `llm4s-memory` with them. The package name is unchanged.

---

## Features

- **Durable Persistence**  
  Stores agent memories in PostgreSQL using JDBC and HikariCP.

- **JSONB Metadata**  
  Flexible, schema less metadata stored as `JSONB`, with GIN indexing for efficient filtering.

- **Vector Storage (pgvector)**  
  Automatically enables the `pgvector` extension and stores embeddings using PostgreSQL’s `vector` type.

- **Embedded on write**  
  Given an `EmbeddingService`, the store embeds every memory it writes without an embedding, so what a
  `MemoryManager` or `SemanticMemoryMiddleware` records is found by `search`.

- **SQL Safety by Design**  
  Table names are validated; every filter value, metadata keys included, is a bound parameter.

- **Explicit Error Handling**  
  Every store operation returns a `Result`; only a `Config` with an invalid value throws, when it is built.

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
import org.llm4s.security.Secret

val config = PostgresMemoryStore.Config(
  host = "localhost",
  port = 5432,
  database = "my_app_db",
  user = "postgres",
  password = Secret("secure_password"),
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
| `tenant`      | `None`             | The tenant whose rows the store reads and writes ([Tenants](#tenants)) |
| `embeddingDimensions` | `None`     | Every embedding's dimension, if declared: the column is typed `vector(n)` and indexed ([Search](#search)) |

### Sharing your application's connection pool

`PostgresMemoryStore(config)` creates a HikariCP pool and closes it on `close()`. To reuse a pool the application
already has - a doobie `HikariTransactor`'s `kernel`, a Play or Slick pool, or any `javax.sql.DataSource` - use
`fromDataSource`. The store creates its tables if they are absent and **never closes the `DataSource`**: its owner does.

```scala
import javax.sql.DataSource
import org.llm4s.agent.memory.{ MockEmbeddingService, PostgresMemoryStore }

def memories(pool: DataSource) =
  PostgresMemoryStore.fromDataSource(
    dataSource = pool,
    tableName = "agent_memories",
    embeddingService = Some(MockEmbeddingService.default)
  ) // Result[PostgresMemoryStore]; store.close() leaves `pool` open
```

Store calls are blocking JDBC; from effectful code run them on the blocking pool (`IO.blocking`, `Sync[F].blocking`,
`ZIO.attemptBlocking`). The vector-store guide's
[Sharing your application's connection pool](../guide/vector-store.html#sharing-your-applications-connection-pool)
section has a complete doobie example inside a cats-effect `Resource`.

### Tenants

Many tenants share one table: a store belongs to the `tenant` it is built with (`Config.tenant`, or
`fromDataSource(..., tenant = Some("alice"))`), and every statement it runs - `recall`, `search`, `count`, `delete`,
`deleteMatching`, `clear()`, bounds eviction and applied writes - touches only that tenant's rows. `None` is the rows
of no tenant, among them every row written before the store had tenants; a tenant is never the empty string.
Two stores over one table and tenant report the same `storageIdentity`, so `SemanticMemoryMiddleware` refuses a
resolver that gives two tenants the same rows. The identity names the cluster by its `system_identifier`
(`pg_control_system()`), not by the address a connection used, then the database, the schema-qualified table and the
tenant. PostgreSQL grants `pg_control_system()` to PUBLIC by default. Where it has been revoked, `storageIdentity` is a
`Left` naming the missing privilege and the middleware refuses the turn, rather than guess an identity without the
cluster; grant it to the store's role with `GRANT EXECUTE ON FUNCTION pg_control_system() TO <role>`.

## Security & Validation

### Table Name Validation

Table names are validated eagerly using the following pattern:
```
^[a-zA-Z_][a-zA-Z0-9_]{0,54}$
```

At most 55 characters, so the names derived from it (`<table>_applied`, `<table>_fences` and the
`idx_<table>_...` indexes) stay distinct within PostgreSQL's 63-byte identifier limit.


This prevents SQL injection via identifier interpolation and ensures failures occur during configuration rather than at query time.

If validation fails, configuration throws an `IllegalArgumentException`.

---

### Metadata Keys

A metadata key in a filter is passed to PostgreSQL as a bound parameter, never written into the SQL, so any key
works: `user-id`, `a.b` and `x[0]` are ordinary keys.

---

## Schema Details

On initialization, the store executes the following DDL (if not already present):

```sql
-- Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- Create memory table
CREATE TABLE IF NOT EXISTS agent_memories (
    tenant TEXT NOT NULL DEFAULT '',
    id TEXT NOT NULL,
    content TEXT NOT NULL,
    memory_type TEXT NOT NULL,
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL,
    importance DOUBLE PRECISION,
    embedding vector,            -- vector(n) with embeddingDimensions = Some(n)
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant, id)
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

-- Applied writes and fencing tokens of TransactionalMemoryStore, per tenant
CREATE TABLE IF NOT EXISTS agent_memories_applied (
    tenant TEXT NOT NULL DEFAULT '', key TEXT NOT NULL, scope TEXT NOT NULL, outcome TEXT NOT NULL,
    PRIMARY KEY (tenant, key)
);
CREATE TABLE IF NOT EXISTS agent_memories_fences (
    tenant TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL, token BIGINT NOT NULL,
    PRIMARY KEY (tenant, scope)
);
```

A table created before tenants is migrated when a store opens it: each of the three gains the `tenant` column (`''`
for its rows) and a primary key that starts with it, under a table lock so two processes migrate it once. An
up-to-date table is only checked in the catalog, so opening a store never locks it or waits for its readers.

## Querying and Filters

`recall`, `count`, `deleteMatching` and `search` accept every `MemoryFilter` and answer exactly what
`MemoryFilter.matches` answers, as the in-memory, SQLite and vector stores do. SQL narrows the rows and `matches`
decides: a filter SQL can say exactly is evaluated by PostgreSQL alone; for one it cannot (a `Custom` predicate, a
case-insensitive `ContentContains`), the store reads the rows the rest of the filter narrows to and runs `matches` on
them before any limit, count or delete.

### How filters translate

| Filter                                   | SQL                                                               |
| ---------------------------------------- | ----------------------------------------------------------------- |
| `All` / `None`                           | no condition / `1 = 0`                                            |
| `ByEntity(id)`, `ByMetadata(k, v)`       | `metadata @> {k: v}` (uses the GIN index), then `metadata ->> ? = ?` as text |
| `ByConversation(id)`                     | `metadata->>'conversation_id' = ?` (uses its index)               |
| `HasMetadata(k)`                         | `(metadata -> ?) IS NOT NULL`                                     |
| `MetadataContains(k, s)`                 | `strpos(metadata ->> ?, ?) > 0`, a literal substring test         |
| `ByType(t)`, `ByTypes(ts)`               | `memory_type IN (?, ...)`                                         |
| `ByTimeRange(a, b)`                      | `created_at >= ? AND created_at <= ?`                             |
| `MinImportance(x)`                       | `importance >= ?`                                                 |
| `ContentContains(s, caseSensitive=true)` | `strpos(content, ?) > 0`                                          |
| `ContentContains(s)` (ignoring case)     | decided by `matches`: `lower()` follows the database's collation  |
| `Custom(p)`                              | decided by `matches`                                              |
| `And`, `Or`, `Not`                       | composed; `Not` only of a condition that is exact                 |

Metadata values are compared as the text `->>` gives, and read back the same way, so a number, a boolean or a JSON
`null` that another writer stored (`{"count": 3}`) matches `ByMetadata("count", "3")` in SQL and in `matches` alike.
The containment test before it, for the JSON string `v` and, when `v` is the text of one, the number, boolean or
`null`, lets the `GIN(metadata)` index narrow the rows; the text comparison decides.
Every condition is guarded against SQL `NULL`, so `Not(MinImportance(0.5))` keeps the memories that have no
importance, and `Not(ByEntity(e))` those with no entity, as `matches` does.

### Compound Filter Examples

```scala
// Important tasks only
val andFilter = MemoryFilter.And(
  MemoryFilter.ByType(MemoryType.Task),
  MemoryFilter.MinImportance(0.8)
)

// Everything below 0.5 importance, including memories with no importance at all
val notFilter = MemoryFilter.Not(MemoryFilter.MinImportance(0.5))

// Delete old memories that a predicate selects: the store reads the rows older than the cutoff and deletes
// those the predicate accepts
val cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(30))
val stale = MemoryFilter.And(
  MemoryFilter.ByTimeRange(before = Some(cutoff)),
  MemoryFilter.Custom(_.importance.forall(_ < 0.3))
)
```

### Memory types

A built-in type is stored as its name (`task`, `user_fact`, ...) and a custom type as `custom:<name>`, keeping its
case, so `MemoryType.Custom("Report")` reads back as `Custom("Report")` and `Custom("task")` stays apart from
`Task`. Rows written by 1.0.x hold a custom type's bare name; they read back as that custom type, and `ByType`
finds them under either spelling.

## Search

With an `EmbeddingService`, `search` embeds the query and returns the `topK` memories nearest to it among those the
filter accepts; a memory with no embedding is not a candidate. The store embeds what it writes - `store`,
`storeAll`, `replace` and `applyAtomically` embed every memory without an embedding, in batches, before the
transaction; `update` embeds again when it changes the content and leaves the embedding as it was. If embedding
fails, nothing is written.

A memory scores `(1 + cos) / 2` - 1.0 for the query's own direction, 0.5 orthogonal, 0.0 opposite - as in every
memory store. Only embeddings of the query's dimension that are
not all zeros are compared: pgvector cannot compare two dimensions, and a zero vector has no direction. When the
filter admits embedded memories but none of the query's dimension, `search` is a `ConfigurationError`.

Rows written before the store embedded what it stores have no embedding. Embed them once with `embedAll`; after a
change of embedding model, `reembedAll` embeds again every memory whose embedding is not of the new dimension:

```scala
store.embedAll()      // Result[Int]: how many memories were embedded
store.reembedAll(1024) // the same, for every memory without a 1024-dimensional embedding
```

### Declaring the dimension

An untyped `vector` column holds embeddings of any dimension and cannot be indexed, so every search reads every
candidate row. With `embeddingDimensions = Some(n)` (in `Config`, or `fromDataSource(..., embeddingDimensions =
Some(n))`), the column is typed `vector(n)` and, up to 2000 dimensions, carries an HNSW index for cosine distance
(`idx_<table>_embedding`). An older untyped column is converted when the store opens, if every embedding in it has
`n` dimensions; otherwise opening fails and names what it found. A write of another dimension then fails, and a query
of another dimension is a `ConfigurationError`.

To move a typed table to another model, make the column untyped again
(`DROP INDEX idx_<table>_embedding; ALTER TABLE <table> ALTER COLUMN embedding TYPE vector;`), open the store without
`embeddingDimensions` and the new model's service, run `reembedAll(newDimension)`, then reopen it with the new
dimension.

Without an `EmbeddingService`, `search` is a keyword search, scored as `InMemoryStore` scores it: the share of the
query's words and phrases that occur in a memory as whole words. It reads every row the filter narrows to, so on a
large table give the store an embedding service, or a narrow filter.

## Error Handling

Every store operation returns a `Result[T]`; none throws. The exception is building a `Config`: a table name that
fails [validation](#table-name-validation), an empty tenant or a dimension below 1 throws `IllegalArgumentException`
when the `Config` is constructed. `PostgresMemoryStore.fromDataSource` checks the same things and returns a `Left`
instead.

## With `SemanticMemoryMiddleware`

`PostgresMemoryStore` is a `TransactionalMemoryStore`, so `llm4s-agent-memory`'s `SemanticMemoryMiddleware` can
record turns, replay them after a crash without writing twice, and consolidate. Give each tenant a store of its own
tenant over one shared pool, built by the resolver; the middleware refuses two tenants with the same rows:

```scala
val pool: javax.sql.DataSource = ??? // your application's pool
val stores: Option[TenantId] => Result[MemoryStore] = tenant =>
  PostgresMemoryStore.fromDataSource(
    pool,
    embeddingService = Some(embeddingService),
    tenant = tenant.map(_.value),
    embeddingDimensions = Some(1536)
  )
```

A store over a caller-owned pool does not close it, so the middleware releasing an idle tenant's store
(`maxTenants`, `close()`) leaves the pool open. A store built by `PostgresMemoryStore(config)` owns its own pool,
which releasing it closes.

---

## Usage Examples

This section shows how a developer would use `PostgresMemoryStore` in a real application after configuration is complete.

### End-to-End Example (Result-based workflow)

```scala
import java.time.Instant
import org.llm4s.agent.memory.{PostgresMemoryStore, Memory, MemoryFilter, MemoryId, MemoryType}
import org.llm4s.security.Secret

val config = PostgresMemoryStore.Config(
  host = "localhost",
  port = 5432,
  database = "my_app_db",
  user = "postgres",
  password = Secret("secure_password"),
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

### Bulk ingestion

To import many memories at once (a chat history, extracted entity facts, document chunks), use `storeAll`
rather than calling `store` in a loop:

```scala
val imported: Result[MemoryStore] = store.storeAll(memories)
```

`storeAll` writes the whole batch in one transaction on one connection: every memory is stored or, if any row
fails, none is. The rows are sent as JDBC batches of up to 1,000, not one round trip per memory. A memory whose
id appears more than once is stored once, as its last occurrence, and a memory whose id is already in the table
is replaced. Over a data source of your own (`fromDataSource`), pgjdbc's `reWriteBatchedInserts=true` folds each
batch into multi-row inserts.

---

## Troubleshooting

| Problem | Cause | Fix |
|--------|------|-----|
| `extension "vector" does not exist` | pgvector not installed or enabled | Run `CREATE EXTENSION vector;` in your database |
| Connection timeout or pool exhaustion | Incorrect DB URL or pool size too small | Check host/port/credentials and `maxPoolSize` config |
| Embedding dimension mismatch | Stored embeddings use a different model size | Use the same embedding model for both storage and queries, or `reembedAll` with the new one |
| Table name validation error | Table name does not match allowed pattern | Use only letters, numbers, and underscores, starting with a letter or underscore |
| Metadata key validation errors | Invalid metadata keys in filters | Ensure keys match `^[a-zA-Z_][a-zA-Z0-9_]*$` |
