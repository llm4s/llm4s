# llm4s-memory-postgres

The Postgres/pgvector memory store for llm4s: durable agent memory that several processes or hosts
can share, with similarity search done in the database. Beta (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)): it ships alongside the frozen
modules on its own version train. Everything else about agent memory (the model, the filters, the
managers and the in-memory and SQLite stores) is in [`llm4s-memory`](../memory/README.md).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % "<version>"
```

Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

**What it pulls in.** `llm4s-memory` (and with it `llm4s-core`, the SQLite driver and ujson), the
PostgreSQL JDBC driver (`org.postgresql:postgresql`) and the HikariCP connection pool. Those two are
the reason this store is its own module: `llm4s-memory` has neither on its classpath.

## What it provides

| Type | What it is |
|---|---|
| `PostgresMemoryStore` | a `MemoryStore` (and `AutoCloseable`) over one table, with a HikariCP pool and pgvector similarity search |
| `PostgresMemoryStore.Config` | `host`, `port`, `database`, `user`, `password`, `tableName`, `maxPoolSize` |

It is in the `org.llm4s.agent.memory` package, the same as the rest of memory, so adding the
dependency is the only change to your imports.

## Setup

You need a PostgreSQL server where the pgvector extension is available. Opening the store runs, on
one pooled connection:

1. `CREATE EXTENSION IF NOT EXISTS vector`: the database user must be allowed to create extensions
   (or an administrator creates it first).
2. `CREATE TABLE IF NOT EXISTS <tableName>` with these columns: `id TEXT PRIMARY KEY`, `content TEXT`,
   `memory_type TEXT`, `metadata JSONB`, `created_at TIMESTAMPTZ`, `importance DOUBLE PRECISION`,
   `embedding vector` (no fixed dimension) and `version BIGINT`.
3. Indexes on the memory type, the creation time, the `metadata` column (GIN) and
   `metadata->>'conversation_id'`.

For local use, `docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16`
gives a server with the extension installed.

## Configuration

This module ships no `reference.conf`: there are no `llm4s.memory.*` keys and nothing here reads the
environment. Build the `Config` in code, at the application edge, with the password coming from
wherever your application keeps secrets:

| Field | Default |
|---|---|
| `host` | `localhost` |
| `port` | `5432` |
| `database` | `postgres` |
| `user` | `postgres` |
| `password` | empty |
| `tableName` | `agent_memories` |
| `maxPoolSize` | `10` |

`tableName` is put into SQL, so it is validated: it must match `[a-zA-Z_][a-zA-Z0-9_]{0,62}`, and
`Config` **throws an `IllegalArgumentException` at construction** for anything else. Validate a name
you do not control before building the `Config`.

## Minimal example

```scala
import org.llm4s.agent.memory._

def open(embeddings: EmbeddingService) = {
  val config = PostgresMemoryStore.Config(
    host = "localhost",
    port = 5432,
    database = "app",
    user = "app",
    password = sys.props.getOrElse("pg.password", ""),
    tableName = "agent_memories",
    maxPoolSize = 10
  )
  for {
    store   <- PostgresMemoryStore(config, Some(embeddings))
    manager <- SimpleMemoryManager.withStore(store).recordUserFact("Prefers Scala", Some("user-1"))
  } yield (store, manager)
}

// Call open once, at startup, and retain its result for the application's lifetime. At shutdown,
// close that original store - never call open a second time just to close:
//   val opened = open(embeddings)                          // startup
//   opened.foreach { case (store, _) => store.close() }    // shutdown
```

This needs a running server, so the example is compile-checked but was not run. `store.close()` shuts
the connection pool down. The embedding service is optional (`PostgresMemoryStore(config)` builds the
store without one); pass `LLMEmbeddingService(client, model)` for a real provider.

## What it supports, and its limits

- **Search depends on the embedding service.** With one, `search` embeds the query as a query and
  ranks by cosine similarity in the database (`1 - (embedding <=> query)`, clamped to 0..1). Without
  one, `search` returns the `recall` results for the filter, each scored `0.0`: there is no keyword
  search in this store.
- **Some filters are refused, not translated.** `All`, `None`, `ByType`, `ByTypes`, `ByEntity`,
  `ByConversation`, `ByTimeRange`, `MinImportance`, `ByMetadata`, `And`, `Or` and `Not` become SQL.
  `HasMetadata`, `MetadataContains`, `ContentContains` and `Custom` return a `Left`
  (`Unsupported filter`), so an `And` that contains one of them fails whole. A `ByMetadata` key
  must match the store's key pattern or it is refused too.
- **Updates are optimistic.** `update` checks the row's `version`; a concurrent change comes back as
  an `OptimisticLockFailure` for the caller to retry.
- **Batches are all or nothing.** `storeAll` writes the whole batch in one transaction on one pooled
  connection.
- **The `embedding` column has no fixed dimension.** Nothing in the schema stops two embedding
  models writing into the same table; keep one model per table.
- **Setup runs on every open.** The `IF NOT EXISTS` statements make that safe, but the user needs
  the privileges for them on the first open.

## Tests

```bash
sbt memoryPostgres/test        # unit tests with a mock-backed connection, no Docker
sbt testIntegration            # the @Docker tier in modules/it, including PostgresMemoryStoreSpec
```

`PostgresMemoryStoreSpec` (`modules/it/src/test/scala/org/llm4s/agent/memory`) needs a pgvector
server and runs only when `POSTGRES_TEST_ENABLED=true`; otherwise it is skipped. The commands and
variables for starting the services are in [`modules/it/README.md`](../it/README.md). CI sets the
variable in its Integration Tests job.

## See also

- [Postgres memory store reference](../../docs/reference/postgres-memory-store.md) - dependency, schema and the filter-to-SQL table
- [Memory system guide](../../docs/guide/agents/memory.md)
- [`llm4s-memory`](../memory/README.md) - the model, managers and the other stores
