# llm4s-memory

Agent memory for llm4s: a memory model, a `MemoryStore` contract with in-memory, SQLite and
vector-search stores, and memory managers that record what an agent learns and hand back the
context that is relevant to a query. Beta (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)): it ships alongside the frozen
modules on its own version train. The Postgres/pgvector store is a separate module,
[`llm4s-memory-postgres`](../memory-postgres/README.md).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-memory" % "<version>"
```

Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

**What it pulls in.** `llm4s-core`, the SQLite JDBC driver (`org.xerial:sqlite-jdbc`) and ujson. It
has no Postgres driver and no connection pool: those are in `llm4s-memory-postgres`.

## What it provides

| Area | Types | Notes |
|---|---|---|
| Memory model | `Memory`, `MemoryId`, `EntityId`, `ScoredMemory`; `MemoryType`: `Conversation`, `Entity`, `Knowledge`, `UserFact`, `Task` | build one with `Memory.fromConversation`, `forEntity`, `fromKnowledge`, `userFact` or `fromTask` |
| Store contract | `MemoryStore`: `store`, `storeAll`, `get`, `recall`, `search`, `getEntityMemories`, `getConversation`, `delete`, `deleteMatching`, `update` | a write returns a `Result[MemoryStore]`: keep using the store you get back |
| Filters | `MemoryFilter`: `All`, `None`, `ByType`, `ByTypes`, `ByMetadata`, `HasMetadata`, `MetadataContains`, `ByEntity`, `ByConversation`, `ByTimeRange`, `MinImportance`, `ContentContains`, `Custom`, `And`, `Or`, `Not` | combine with `&&`, `\|\|` and `!` |
| Stores | `InMemoryStore`, `EmbeddingMemoryStore`, `SQLiteMemoryStore`, `VectorMemoryStore` | see the table below |
| Managers | `MemoryManager`; `SimpleMemoryManager`, `LLMMemoryManager` | `record*` methods, `getRelevantContext`, `getConversationContext`, `getEntityContext`, `getUserContext`, `stats` |
| Embeddings | `EmbeddingService` (`embed`, `embedQuery`, `embedBatch`, `dimensions`); `LLMEmbeddingService`; `MockEmbeddingService` | `LLMEmbeddingService(client, modelConfig)` wraps an embedding client; the mock is for tests |
| Settings | `MemoryStoreConfig`, `MemoryManagerConfig` | plain values with defaults, see Configuration |

## Choosing a store

| Store | Where the data lives | Search | Concurrency |
|---|---|---|---|
| `InMemoryStore` | the JVM heap; lost on exit | keyword | immutable: every write returns a new store |
| `EmbeddingMemoryStore` | wraps an `InMemoryStore` | vector similarity over memories that have embeddings, keyword otherwise | as the store it wraps |
| `SQLiteMemoryStore` | a file, or `":memory:"` | SQLite FTS5 full text, ranked with BM25 | one JDBC connection per instance: not safe for concurrent use |
| `VectorMemoryStore` | a SQLite file with an embedding per memory | cosine similarity of the query embedding with the stored vectors | one connection per instance; separate instances may write the same file |
| `PostgresMemoryStore` | Postgres with pgvector, in [`llm4s-memory-postgres`](../memory-postgres/README.md) | cosine similarity in the database | pooled connections |

Start with `InMemoryStore` for tests and short-lived agents, `SQLiteMemoryStore` for a single process
that must remember across restarts, `VectorMemoryStore` when recall should be by meaning, and Postgres
when several processes or hosts share the memory.

## Configuration

This module ships no `reference.conf`: there are no `llm4s.memory.*` keys and nothing here reads the
environment. Stores and managers are built in code, at the application edge:

- `MemoryStoreConfig(maxMemories, defaultEmbeddingDimensions, enableAutoCleanup, cleanupThreshold)`
  with `MemoryStoreConfig.default`, `.testing` (a 1,000-memory cap) and `.production(maxMemories)`
  (cleanup on, threshold at 90% of the cap).
- `MemoryManagerConfig(autoRecordMessages = true, autoExtractEntities = false, defaultImportance = 0.5,
  contextTokenBudget = 2000, consolidationEnabled = false, consolidationConfig)` for the manager.
  The managers read `defaultImportance`; `LLMMemoryManager.consolidateMemories` also reads
  `consolidationConfig.strictMode` and `consolidationConfig.maxMemoriesPerGroup`.
  `autoRecordMessages`, `autoExtractEntities`,
  `contextTokenBudget` and `consolidationEnabled` are declared but have no effect yet
  ([#1579](https://github.com/llm4s/llm4s/issues/1579)).
- An embedding provider for the semantic stores. `LLMEmbeddingService(client, modelConfig)` wraps any
  llm4s embedding client, so the provider, its model and its API key are configured through the
  provider module you use (`llm4s.embeddings.<id>` and `llm4s.credentials.<id>.apiKey`), not here.

## Minimal example

```scala
import org.llm4s.agent.memory._

val context = for {
  m1      <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-1"), Some(0.9))
  m2      <- m1.recordKnowledge("Scala 3 has opaque types", "docs")
  context <- m2.getRelevantContext("Scala")
} yield context
// Right("# Retrieved Context\n## Relevant Knowledge\n- Scala 3 has opaque types\n\n## User Preferences\n- Prefers Scala over Java")
```

`SimpleMemoryManager.empty` keeps everything in an `InMemoryStore`. The in-memory store searches by
keyword: it splits the query on whitespace and returns a memory whose text contains one of the resulting
terms as a substring. Asking "What does the user prefer?" would have returned an empty context here,
because its terms are `what`, `does`, `the`, `user` and `prefer?`, and the question mark stays attached to
the last one, so `prefer?` is not found in "Prefers Scala over Java" (see
[#1594](https://github.com/llm4s/llm4s/issues/1594)).

### Remembering across restarts

```scala
import org.llm4s.agent.memory._

val facts = for {
  store    <- SQLiteMemoryStore("memories.db")
  _        <- SimpleMemoryManager.withStore(store).recordUserFact("Lives in Pune", Some("user-1"))
  _        = store.close()
  reopened <- SQLiteMemoryStore("memories.db")
  facts    <- reopened.recall(MemoryFilter.ByType(MemoryType.UserFact))
  _        = reopened.close()
} yield facts.map(_.content)
// Right(List("Lives in Pune"))
```

### Recall by meaning

```scala
import org.llm4s.agent.memory._

val hits = for {
  store <- VectorMemoryStore.inMemory(MockEmbeddingService(dimensions = 8)) // swap in LLMEmbeddingService(client, model)
  s1    <- store.store(Memory.userFact("Prefers Scala", Some("user-1")))
  hits  <- s1.search("What does the user like?", topK = 3)
} yield hits.map(h => (h.memory.content, h.score))
```

The mock produces deterministic vectors from a hash of the text, so its scores say nothing about
meaning; it exists to test the plumbing. Use `VectorMemoryStore(dbPath, embeddingService)` for a file.

## What it supports, and its limits

- **`SimpleMemoryManager` does not consolidate or extract.** Its `consolidateMemories` and
  `extractEntities` return the manager unchanged. Use `LLMMemoryManager(config, store, llmClient)`
  for LLM-powered consolidation and entity extraction.
- **A SQLite or vector store instance is single-threaded.** It holds one JDBC connection. Share one
  between threads only under your own synchronisation, or give each thread its own instance: separate
  instances may write the same file, and SQLite serialises their transactions.
- **Batches are all or nothing.** `storeAll`, `deleteMatching` and opening the store each run in one
  transaction on the SQLite and vector stores. A write waits for the database's write lock up to the
  connection's busy timeout (3 seconds for `SQLiteMemoryStore`, 30 seconds for `VectorMemoryStore`).
- **Filters run in SQL where SQL can say exactly what `matches` says.** The SQL stores narrow the rows
  in SQL and let `MemoryFilter.matches` decide the rest before any limit, count or delete, so a
  `Custom` predicate is always honoured. Case-insensitive text matching uses Java's `toLowerCase`,
  through a function registered on the connection, so it agrees with the in-memory store for
  non-ASCII text.
- **One embedding model per vector store.** If a search query's embedding has a different dimension
  from every stored vector, `search` returns a `ConfigurationError` that names the mismatch instead of
  an empty result. If only some stored vectors differ (the model was changed part way), those memories
  are left out of the results and a warning with their count is logged.
- **Schemas are created on open.** The SQLite stores run `CREATE TABLE IF NOT EXISTS`; the schema
  version is `SQLiteMemoryStore.SchemaVersion`.

## Tests

```bash
sbt memory/test        # unit tests: in-memory and SQLite stores, mock embeddings, no external services
```

Nothing in this module needs Docker. The Postgres store's suite is described in the
[`llm4s-memory-postgres`](../memory-postgres/README.md) README.

## See also

- [Memory system guide](../../docs/guide/agents/memory.md) - memory types, the managers and the stores in depth
- [Postgres memory store](../../docs/reference/postgres-memory-store.md) - the Postgres reference page
- [`llm4s-memory-postgres`](../memory-postgres/README.md) - the durable, shared store
