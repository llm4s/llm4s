---
layout: page
title: Vector Store
parent: User Guide
nav_order: 3
---

# Vector Store
{: .no_toc }

Low-level vector storage abstraction for RAG and semantic search.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The `VectorStore` trait provides a backend-agnostic interface for storing and searching vector embeddings. This is the foundation layer for building RAG (Retrieval-Augmented Generation) applications.

**Key Features:**
- Backend-agnostic API supporting multiple vector databases
- Type-safe error handling with `Result[A]`
- Metadata filtering with composable DSL
- Batch operations for efficient bulk processing
- Built-in statistics and monitoring

**Current Backends:**
- **SQLite** - File-based or in-memory storage (default)
- **pgvector** - PostgreSQL with pgvector extension (production-ready)
- **Qdrant** - Cloud-native vector database via REST API
- **Elasticsearch** - `dense_vector` kNN search in a cluster you already run, in the separate module `llm4s-vectorstore-elasticsearch` (Experimental); see [Elasticsearch](#elasticsearch)
- **OpenSearch** - `knn_vector` search with the k-NN plugin, in the same module (Experimental); see [OpenSearch](#opensearch)
- **Redis and Valkey** - KNN search over a vector index in the Redis (Redis Stack, Redis 8+) or Valkey (`valkey-search`) you already run, in the separate module `llm4s-vectorstore-redis` (Experimental); see [Redis and Valkey](#redis-and-valkey)

**Keyword Backends (for hybrid search):**
- **SQLite FTS5** - File-based or in-memory BM25 search (default)
- **PostgreSQL** - Native full-text search with tsvector/tsquery (production-ready)

**Planned Backends:**
- Milvus
- Pinecone

---

## Quick Start

### In-Memory Store

```scala
import org.llm4s.vectorstore._

// Create an in-memory store
val store = VectorStoreFactory.inMemory().fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Store a vector
val record = VectorRecord(
  id = "doc-1",
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Hello world"),
  metadata = Map("type" -> "greeting")
)

store.upsert(record)

// Search for similar vectors
val results = store.search(
  queryVector = Array(0.1f, 0.2f, 0.3f),
  topK = 5
)

// A Result: Right(hits), or Left(error)
results.foreach(_.foreach { scored =>
  println(s"${scored.record.id}: ${scored.score}")
})

// Clean up
store.close()
```

### File-Based Store

```scala
import org.llm4s.vectorstore._

// Create a persistent store
val store = VectorStoreFactory.sqlite("/path/to/vectors.db").fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Use the store...

store.close()
```

### PostgreSQL with pgvector

```scala
import org.llm4s.vectorstore._

// Local PostgreSQL with defaults
val store = VectorStoreFactory.pgvector().fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Or with explicit connection settings (a Result, like every factory method)
val store2 = VectorStoreFactory.pgvector(
  connectionString = "jdbc:postgresql://localhost:5432/mydb",
  user = "postgres",
  password = "secret",
  tableName = "embeddings"
)

// Create HNSW index for faster search (optional)
store.asInstanceOf[PgVectorStore].createHnswIndex()

store.close()
```

**Setup:** Requires PostgreSQL with pgvector extension:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

### Sharing your application's connection pool

The factories above create a HikariCP pool and close it with the store. An application that already has a pool - a
doobie `HikariTransactor`, a Play or Slick pool, or a `javax.sql.DataSource` a platform team hands out with credential
rotation, metrics and TLS configured - can pass it instead. Every Postgres store and index takes a plain
`javax.sql.DataSource` that it **does not own**: closing the store leaves the pool open, and the pool's owner closes it.

| Store | Over a caller-owned `DataSource` |
| ----- | -------------------------------- |
| `PgVectorStore` | `PgVectorStore.create(dataSource, tableName)` or `PgVectorStore(dataSource, tableName)` |
| `PgKeywordIndex` | `PgKeywordIndex(dataSource, tableName)` or `PgKeywordIndex(dataSource, tableName, language)` |
| `HybridSearcher` | `HybridSearcher.pgvectorShared(dataSource, vectorTableName, keywordTableName)` (optionally a `FusionStrategy`) |
| `PgSearchIndex` | `PgSearchIndex.fromDataSource(dataSource, vectorTableName)` |
| `PostgresMemoryStore` | `PostgresMemoryStore.fromDataSource(dataSource, tableName, embeddingService)` |

With doobie, the transactor's `kernel` is the `HikariDataSource` behind it. Build the store inside the transactor's
`Resource`, so the pool outlives every store that borrows it:

```scala
import cats.effect.{ IO, Resource }
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import org.llm4s.vectorstore.{ PgVectorStore, VectorRecord }

// The application's pool. Its Resource closes it - no LLM4S store does.
val transactor: Resource[IO, HikariTransactor[IO]] =
  for {
    connectEc <- ExecutionContexts.fixedThreadPool[IO](16)
    xa <- HikariTransactor.newHikariTransactor[IO](
      "org.postgresql.Driver",
      "jdbc:postgresql://localhost:5432/mydb",
      "postgres",
      "secret",
      connectEc
    )
  } yield xa

// The store borrows `xa.kernel`; closing the store leaves the pool open.
val vectorStore: Resource[IO, PgVectorStore] =
  transactor.flatMap { xa =>
    Resource.make(
      IO.blocking(PgVectorStore.create(xa.kernel, "embeddings"))
        .flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.formatted))))
    )(store => IO.blocking(store.close()))
  }

// Store calls are blocking JDBC: run them on the blocking pool.
val program: IO[Long] =
  vectorStore.use { store =>
    IO.blocking {
      for {
        _     <- store.upsert(VectorRecord("doc-1", Array(0.1f, 0.2f, 0.3f), Some("Scala programming")))
        count <- store.count()
      } yield count
    }.flatMap(result => IO.fromEither(result.left.map(e => new RuntimeException(e.formatted))))
  }
```

Every store call is blocking JDBC. From cats-effect code run it with `IO.blocking` (or `Sync[F].blocking`), from ZIO
with `ZIO.attemptBlocking`, so it never occupies a compute thread. Any `DataSource` works, not only HikariCP's: pgjdbc's
`PGSimpleDataSource` opens a connection per call, which is fine for tests and scripts. Hikari stays an implementation
detail of the config-based factories, and no public signature names it.

### Qdrant

```scala
import org.llm4s.vectorstore._

// Local Qdrant (docker run -p 6333:6333 qdrant/qdrant)
val store = VectorStoreFactory.qdrant().fold(
  e => throw new RuntimeException(s"Failed: ${e.formatted}"),
  identity
)

// Or Qdrant Cloud
val cloudStore = VectorStoreFactory.qdrantCloud(
  cloudUrl = "https://your-cluster.qdrant.io",
  apiKey = "your-api-key",
  collectionName = "my_vectors"
)

store.close()
```

### Elasticsearch

`ElasticsearchVectorStore` keeps the vectors in one index of an Elasticsearch cluster, through the REST API and
`llm4s-core`'s HTTP client: no Elasticsearch client library is added. It lives in its own module (Experimental):

```scala
libraryDependencies += "org.llm4s" %% "llm4s-vectorstore-elasticsearch" % llm4sVersion
```

```scala
import org.llm4s.vectorstore.elasticsearch._

// Local single node with security off:
// docker run -p 9200:9200 -e discovery.type=single-node -e xpack.security.enabled=false \
//   docker.elastic.co/elasticsearch/elasticsearch:8.15.3
val local = ElasticsearchVectorStore.local(index = "my-vectors")

// A secured cluster: an API key (its `encoded` value) or a username and password
val encodedKey = "your-encoded-api-key" // from your secret store
val config = ElasticsearchConfig("https://es.example.com:9200")
  .withIndex("my-vectors")
  .withAuth(ElasticsearchAuth.ApiKey(encodedKey))
  .withSimilarity(ElasticsearchSimilarity.Cosine) // or DotProduct, L2Norm, MaxInnerProduct
  .withDimensions(1536)                           // unset: the first record's size
val store = ElasticsearchVectorStore(config)
```

Or from configuration, at the app edge:

```hocon
llm4s.vectorstore.elasticsearch {
  baseUrl    = "https://es.example.com:9200"   # ELASTICSEARCH_URL; default http://localhost:9200
  index      = "my-vectors"                    # ELASTICSEARCH_INDEX; default llm4s-vectors
  apiKey     = ${?ELASTICSEARCH_API_KEY}       # or username / password (ELASTICSEARCH_USERNAME, _PASSWORD)
  similarity = cosine                          # cosine | dot_product | l2_norm | max_inner_product
  dimensions = 1536
  # numCandidates  = 100                       # kNN candidates per shard; default max(100, 10 * topK)
  # refreshOnWrite = true                      # writes wait for a refresh, so a search sees them at once
  # timeout        = 30 seconds
}
```

```scala
import org.llm4s.config.ElasticsearchVectorStoreConfigLoader

val store = for {
  config <- ElasticsearchVectorStoreConfigLoader.default()
  store  <- ElasticsearchVectorStore(config)
} yield store
```

How it maps onto Elasticsearch:

- **Index**: created on the first upsert when it does not exist, with a `dense_vector` field (`embedding`, indexed
  for kNN with the configured similarity and size), `content` as `text`, `id` as `keyword`, and every metadata value
  as a `keyword` under `metadata` (a dynamic template). `clear()` deletes the index; the next upsert creates it again.
  Reads of an index that does not exist yet answer as an empty store.
- **Writes**: `upsert` and `upsertBatch` are one `_bulk` request, and the document `_id` is the record ID, so an
  upsert replaces. A record whose size differs from the configured `dimensions` is refused before anything is sent;
  a record Elasticsearch rejects is reported with its ID and reason. `deleteByPrefix` and `deleteByFilter` are
  `_delete_by_query` and return the number deleted.
- **Search**: an approximate kNN search with the metadata filter inside the `knn` clause, so `topK` results come back
  when that many records match. Elasticsearch's `_score` is clamped to `[0, 1]`: it already lies there for `cosine`
  and `l2_norm` (and for `dot_product` over unit vectors), while `max_inner_product` can score above 1.
- **Filters**: `Equals` is a `term` query, `In` a `terms` query, `HasKey` an `exists` query, `Contains` a `wildcard`
  query (`*substring*`, matched literally), and `And`, `Or` and `Not` are `bool` queries.
- **Errors**: a 401 or 403 is an `AuthenticationError`, a 429 a `RateLimitError`, any other failing status a
  `ServiceError` with the status and the redacted body; configured credentials are masked wherever a body echoes them.

Limits to know: `list` pages with `from` and `size`, so `offset + limit` cannot exceed the index's
`max_result_window` (10,000 by default); `Contains` is a leading-wildcard query, which a cluster with
`search.allow_expensive_queries: false` refuses; a metadata key containing a dot is mapped as a nested object path,
so keys `a` and `a.b` cannot both be used. The base URL must be `https` when credentials are configured, unless the
host is loopback. `ElasticsearchVectorStoreSpec` in `modules/it` (`@Docker` tier, `ELASTICSEARCH_TEST_URL`) runs every
operation against a real cluster.

### OpenSearch

`OpenSearchVectorStore` keeps the vectors in one index of an OpenSearch cluster with the k-NN plugin (bundled with
the standard distribution), through the REST API: no OpenSearch client library is added. It is in the same module as
the Elasticsearch store, `llm4s-vectorstore-elasticsearch` (Experimental), and shares its code: OpenSearch kept
Elasticsearch 7.10's document, `_bulk` and query DSL APIs, so records, writes, reads, deletes, `stats` and every
metadata filter behave as described for [Elasticsearch](#elasticsearch). Only the vector field, the kNN query, the
scores and the authentication differ, and those are what `OpenSearchVectorStore` changes.

```scala
import org.llm4s.vectorstore.opensearch._

// Local single node with the security plugin off:
// docker run -p 9200:9200 -e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true \
//   -e DISABLE_INSTALL_DEMO_CONFIG=true opensearchproject/opensearch:2.19.1
val local = OpenSearchVectorStore.local(index = "my-vectors")

// A secured cluster: a username and password, or a bearer token (JWT)
val password = "your-password" // from your secret store
val config = OpenSearchConfig("https://search.example.com:9200")
  .withIndex("my-vectors")
  .withAuth(OpenSearchAuth.Basic("llm4s", password))
  .withSpaceType(OpenSearchSpaceType.CosineSimil) // or InnerProduct, L2
  .withEngine(OpenSearchEngine.Lucene)            // or Faiss, Nmslib (deprecated)
  .withDimensions(1536)                           // unset: the first record's size
val store = OpenSearchVectorStore(config)
```

Or from configuration, at the app edge:

```hocon
llm4s.vectorstore.opensearch {
  baseUrl   = "https://search.example.com:9200"  # OPENSEARCH_URL; default http://localhost:9200
  index     = "my-vectors"                       # OPENSEARCH_INDEX; default llm4s-vectors
  username  = llm4s                              # OPENSEARCH_USERNAME
  password  = ${?OPENSEARCH_PASSWORD}            # or token = ${?OPENSEARCH_TOKEN}, not both
  spaceType = cosinesimil                        # cosinesimil | innerproduct | l2
  engine    = lucene                             # lucene | faiss | nmslib
  dimensions = 1536
  # m              = 16                          # HNSW links per node; default: the engine's
  # efConstruction = 100                         # default: the engine's
  # numCandidates  = 100                         # the query's k; see below
  # refreshOnWrite = true
  # timeout        = 30 seconds
}
```

```scala
import org.llm4s.config.OpenSearchVectorStoreConfigLoader

val store = for {
  config <- OpenSearchVectorStoreConfigLoader.default()
  store  <- OpenSearchVectorStore(config)
} yield store
```

How it maps onto OpenSearch:

- **Index**: created on the first upsert with `index.knn: true` and a `knn_vector` field (`embedding`) of the
  configured `dimension`, indexed with `method` `hnsw`, the configured `space_type` and `engine`, and `m` and
  `ef_construction` when set. The other fields are mapped as for Elasticsearch.
- **Search**: a `knn` query, `{"knn": {"embedding": {"vector": [...], "k": k}}}`. On Lucene (OpenSearch 2.4+) and
  Faiss (2.9+) the metadata filter goes inside it as `filter` - efficient filtering, so `topK` results come back when
  that many records match, and `k` defaults to `topK`. NMSLIB has no efficient filtering, so there the `knn` query is
  the `must` clause of a `bool` query whose `filter` clause drops non-matching neighbours afterwards; `k` then
  defaults to `max(100, 10 * topK)`, and a selective filter can still return fewer than `topK` - raise
  `numCandidates` (the `k` sent) if it does. `k` is never below `topK` and at most 10,000.
- **Scores**: OpenSearch scores each space differently; the store maps them onto `[0, 1]` like Elasticsearch's.
  `cosinesimil` is already `(1 + cosine) / 2` on every engine and is kept; `innerproduct`'s `dot + 1` (or
  `1 / (1 - dot)` for a negative dot) becomes `(1 + dot) / 2`, which lies in `[0, 1]` for unit vectors and is clamped
  otherwise; `l2`'s `1 / (1 + distance²)` is kept.
- **Authentication**: HTTP basic (the security plugin's internal users, also on Amazon OpenSearch Service with
  fine-grained access control) or `Authorization: Bearer` (its JWT backend). AWS Signature Version 4 (IAM) request
  signing is not supported; use an internal user, or a signing proxy in front of the domain.
- **Errors**: as for Elasticsearch, with `opensearch` as the provider of the error.

Engine notes: Faiss supports `cosinesimil` from OpenSearch 2.19 and normalises the vectors it indexes; NMSLIB is
deprecated by OpenSearch, and a newer cluster may refuse to create an NMSLIB index, so use it only for an index that
already has it. The `knn_vector` dimension limit is 16,000. `OpenSearchVectorStoreSpec` in `modules/it` (`@Docker`
tier, `OPENSEARCH_TEST_URL`) runs every operation against a real cluster, on each engine.

### Redis and Valkey

`RedisVectorStore` keeps the vectors as hashes in a Redis server with the Redis Query Engine (Redis Stack, or Redis 8
and later, where search is built in) or a Valkey server with the `valkey-search` module (the `valkey-bundle` image),
and searches them through a vector index. Redis speaks RESP rather than HTTP, so the module brings a client library,
[Jedis](https://github.com/redis/jedis) (MIT); it stays out of the store's API. It lives in its own module
(Experimental):

```scala
libraryDependencies += "org.llm4s" %% "llm4s-vectorstore-redis" % llm4sVersion
```

```scala
import org.llm4s.vectorstore.redis._

// Local server without authentication:
// docker run -p 6379:6379 redis/redis-stack-server:7.4.0-v8   (or valkey/valkey-bundle:9.0.6)
val local = RedisVectorStore.local(index = "my-vectors")

// A remote server: TLS, an ACL user and a password
val password = "your-password" // from your secret store
val config = RedisConfig("redis.example.com", port = 6380)
  .withTls(true)
  .withUsername("app")
  .withPassword(password)
  .withIndex("my-vectors")
  .withDistanceMetric(RedisDistanceMetric.Cosine) // or InnerProduct, L2
  .withAlgorithm(RedisVectorAlgorithm.Hnsw)       // or Flat
  .withDimensions(1536)                           // unset: the first record's size
val store = RedisVectorStore(config)
```

Or from configuration, at the app edge:

```hocon
llm4s.vectorstore.redis {
  host           = "redis.example.com"   # REDIS_HOST; default localhost
  port           = 6380                  # REDIS_PORT; default 6379
  tls            = true                  # REDIS_TLS; default false
  username       = ${?REDIS_USERNAME}    # unset: the default user
  password       = ${?REDIS_PASSWORD}    # unset: no authentication
  index          = "my-vectors"          # REDIS_VECTOR_INDEX; default llm4s-vectors
  distanceMetric = COSINE                # COSINE | IP | L2
  algorithm      = HNSW                  # HNSW | FLAT
  dimensions     = 1536
  # keyPrefix    = "my-vectors:"         # default "<index>:"
  # database     = 0
  # timeout      = 10 seconds
}
```

```scala
import org.llm4s.config.RedisVectorStoreConfigLoader

val store = for {
  config <- RedisVectorStoreConfigLoader.default()
  store  <- RedisVectorStore(config)
} yield store
```

How it maps onto Redis:

- **Records**: each record is a hash at `<keyPrefix><id>`, holding the ID, the content, the metadata as a JSON object,
  the vector as little-endian `FLOAT32` bytes, a write sequence number, and the metadata again as encoded tags.
  `upsert` and `upsertBatch` are one pipeline of `DEL` and `HSET` per record, so an upsert replaces the whole record.
  A record whose size differs from the index's is refused before anything is sent.
- **Index**: created on the first upsert when it does not exist (`FT.CREATE ... ON HASH PREFIX 1 <keyPrefix>`), with
  the `VECTOR` field (`embedding`: the configured algorithm, distance and size), a sortable `NUMERIC` field for write
  order, and two `TAG` fields for metadata. An index that exists is used as it is. Reads of an index that does not
  exist yet answer as an empty store. `clear()` drops the index and deletes the store's keys; the next upsert creates
  it again.
- **Search**: a KNN query, `(<filter>)=>[KNN k @embedding $BLOB]`, so the filter is a pre-filter and `topK` results come
  back when that many records match. Redis reports a distance; the score is `1 - distance / 2` for `COSINE` and `IP`
  (that is `(1 + cosine) / 2`) and `1 / (1 + distance)` for `L2`, clamped to `[0, 1]`.
- **Filters**: any metadata value can be filtered on; they are all strings. The schema of a search index is fixed and
  metadata keys are not, so every entry is one tag of a `TAG` field, encoded (hex bytes) so that no value can clash
  with the tag separator, case folding, whitespace trimming or query escaping. `Equals` and `In` match entries,
  `HasKey` matches keys, `Contains` is a wildcard query (`w'...'`), and `And`, `Or` and `Not` combine clauses. An
  empty `In` matches nothing and is answered without a query.
- **Deletes**: `delete` and `deleteBatch` are one `DEL`. `deleteByPrefix` walks the store's keys with `SCAN`;
  `deleteByFilter` deletes the matching records a page at a time. Both return the number deleted.
- **Errors**: a connection failure is a `NetworkError` (a `TimeoutError` when the server does not answer in time),
  refused credentials (`NOAUTH`, `WRONGPASS`, `NOPERM`) an `AuthenticationError`, and any other error reply a
  `ProcessingError` with the server's message.

Valkey: the `valkey-search` in Valkey 9.0 (`valkey-bundle:9.0`) answers only vector queries. On it `upsert`, `get`,
`getBatch`, `delete`, `deleteBatch`, `deleteByPrefix`, `clear` and `search` with every filter but `Contains` work (the
index is created without `SORTABLE`, which it refuses), while `count` and `stats` over an index, `list` and
`deleteByFilter` are refused with the server's message, as is `Contains`, which has no wildcard query there.

Limits to know: `Contains` needs the Redis Query Engine 2.6 or later. A wildcard expands to at most
`search-max-prefix-expansions` distinct values (200 by default), so `Contains` over a key with more distinct values than that may miss records. A search reply holds at most
`search-max-search-results` records (10,000 by default), which bounds `topK` and `list`'s `offset + limit`. The store
connects to one standalone server, not a cluster, and the Redis Query Engine indexes only database 0 on most
deployments. `deleteByPrefix` and `clear()` scan the keyspace. Without `tls` the password crosses the network in clear
text: keep that to a trusted network. `RedisVectorStoreSpec` and `ValkeyVectorStoreSpec` in `modules/it` (`@Docker`
tier, `REDIS_TEST_URL` and `VALKEY_TEST_URL`) run every operation against real servers.

---

## Core Concepts

### VectorRecord

A `VectorRecord` represents a single entry in the vector store:

```scala
final case class VectorRecord(
  id: String,                          // Unique identifier
  embedding: Array[Float],             // Vector embedding
  content: Option[String] = None,      // Optional text content
  metadata: Map[String, String] = Map.empty  // Key-value metadata
)
```

**Creating Records:**

```scala
// With explicit ID
val record1 = VectorRecord(
  id = "doc-123",
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Document text"),
  metadata = Map("source" -> "wiki", "lang" -> "en")
)

// With auto-generated ID
val record2 = VectorRecord.create(
  embedding = Array(0.1f, 0.2f, 0.3f),
  content = Some("Another document")
)

// Add metadata fluently
val record3 = VectorRecord("id", Array(1.0f))
  .withMetadata("key1", "value1")
  .withMetadata(Map("key2" -> "value2", "key3" -> "value3"))
```

### ScoredRecord

Search results include similarity scores:

```scala
final case class ScoredRecord(
  record: VectorRecord,
  score: Double  // 0.0 to 1.0, higher is more similar
)
```

---

## Operations

### CRUD Operations

The fragments from here to the end of the page use `store`, a `VectorStore` such as the one in the Quick Start,
`record` and `records` (a `VectorRecord` and a `Seq[VectorRecord]`), `ids` (a `Seq[String]`) and `embeddingVector`,
a query embedding (an `Array[Float]`).

```scala
// Single record operations
store.upsert(record)           // Insert or replace
store.get("doc-id")            // Retrieve by ID
store.delete("doc-id")         // Delete by ID

// Batch operations (more efficient)
store.upsertBatch(records)     // Insert/replace multiple
store.getBatch(ids)            // Retrieve multiple
store.deleteBatch(ids)         // Delete multiple

// Clear all records
store.clear()
```

### Search

```scala
// Basic search
val results = store.search(
  queryVector = embeddingVector,
  topK = 10
)

// Search with metadata filter
val filter = MetadataFilter.Equals("type", "document")
val filtered = store.search(
  queryVector = embeddingVector,
  topK = 10,
  filter = Some(filter)
)
```

### Listing and Pagination

```scala
// List all records
val all = store.list()

// Paginate results
val page1 = store.list(limit = 10, offset = 0)
val page2 = store.list(limit = 10, offset = 10)

// List with filter
val docs = store.list(filter = Some(MetadataFilter.Equals("type", "doc")))
```

### Statistics

```scala
val stats = store.stats()

stats.foreach { s =>
  println(s"Total records: ${s.totalRecords}")
  println(s"Dimensions: ${s.dimensions}")
  println(s"Size: ${s.formattedSize}")
}
```

---

## Metadata Filtering

The `MetadataFilter` DSL allows composing complex filters:

### Basic Filters

```scala
import org.llm4s.vectorstore.MetadataFilter._

// Exact match
val byType = Equals("type", "document")

// Contains substring
val byContent = Contains("summary", "Scala")

// Has key (any value)
val hasAuthor = HasKey("author")

// Value in set
val byLang = In("lang", Set("en", "es", "fr"))
```

### Combining Filters

```scala
// AND - both conditions must match
val andFilter = Equals("type", "doc").and(Equals("lang", "en"))

// OR - either condition can match
val orFilter = Equals("type", "doc").or(Equals("type", "article"))

// NOT - negate a filter
val notFilter = !Equals("archived", "true")

// Complex combinations
val complex = Equals("type", "doc")
  .and(Equals("lang", "en").or(Equals("lang", "es")))
  .and(!Equals("draft", "true"))
```

### Using Filters

With `byType` and `complex` from the filters above:

```scala
// In search
store.search(embeddingVector, topK = 10, filter = Some(byType))

// In list
store.list(filter = Some(complex))

// In count
store.count(filter = Some(byType))

// Delete by filter
store.deleteByFilter(Equals("archived", "true"))
```

---

## Factory Configuration

### Using VectorStoreFactory

`VectorStoreFactory` covers the backends inside `llm4s-rag` (SQLite, pgvector, Qdrant). A backend in a module of its
own, such as [Elasticsearch](#elasticsearch), [OpenSearch](#opensearch) or [Redis](#redis-and-valkey), is built with its own constructor, and any `VectorStore` can be passed
wherever one is taken.

```scala
import org.llm4s.vectorstore._

// In-memory (default)
val memStore = VectorStoreFactory.inMemory()

// File-based SQLite
val fileStore = VectorStoreFactory.sqlite("/path/to/db.sqlite")

// From provider name
val store = VectorStoreFactory.create("sqlite", path = Some("/path/to/db.sqlite"))

// From config object
val config = VectorStoreFactory.Config.sqlite("/path/to/db.sqlite")
val configStore = VectorStoreFactory.create(config)
```

### Configuration Options

```scala
// Default in-memory config
val defaultConfig = VectorStoreFactory.Config.default

// SQLite file config
val sqliteConfig = VectorStoreFactory.Config.sqlite("/path/to/vectors.db")

// In-memory config
val memConfig = VectorStoreFactory.Config.inMemory

// pgvector config
val pgConfig = VectorStoreFactory.Config.pgvector(
  connectionString = "jdbc:postgresql://localhost:5432/postgres",
  tableName = "vectors"
)

// Qdrant config
val qdrantConfig = VectorStoreFactory.Config.qdrant(
  collectionName = "vectors",
  port = 6333
)

// Qdrant Cloud config
val qdrantCloudConfig = VectorStoreFactory.Config.qdrantCloud(
  cloudUrl = "https://xxx.qdrant.io",
  apiKey = "your-api-key",
  collectionName = "vectors"
)

// With options
val withOptions = VectorStoreFactory.Config()
  .withSQLite("/path/to/db.sqlite")
  .withOption("cache_size", "10000")
```

---

## Hybrid Search

Hybrid search combines vector similarity (semantic) with BM25 keyword matching for better retrieval quality. LLM4S provides a `HybridSearcher` that fuses results from both search types.

### Quick Start

```scala
import org.llm4s.vectorstore._

val embedding = Array(0.1f, 0.2f, 0.3f)

val results = for {
  // Create stores
  vectorStore  <- VectorStoreFactory.inMemory()
  keywordIndex <- KeywordIndex.inMemory()

  // Create hybrid searcher
  searcher = HybridSearcher(vectorStore, keywordIndex)

  // Index documents in both stores
  _ <- vectorStore.upsert(VectorRecord("doc-1", embedding, Some("Scala programming language")))
  _ <- keywordIndex.index(KeywordDocument("doc-1", "Scala programming language"))

  // Search with both vector and keyword
  found <- searcher.search(
    queryEmbedding = embedding,
    queryText = "Scala",
    topK = 10
  )
  _ = searcher.close()
} yield found

results.foreach(_.foreach { r =>
  println(s"${r.id}: ${r.score} (vector: ${r.vectorScore}, keyword: ${r.keywordScore})")
})
```

### Fusion Strategies

Choose how to combine vector and keyword scores, given a `searcher: HybridSearcher` and a query `embedding` as in
the Quick Start:

```scala
import org.llm4s.vectorstore.FusionStrategy._

// Reciprocal Rank Fusion (default) - rank-based, robust to score differences
val rrfResults = searcher.search(embedding, "query", strategy = RRF(k = 60))

// Weighted Score - normalized scores with configurable weights
val weightedResults = searcher.search(
  embedding, "query",
  strategy = WeightedScore(vectorWeight = 0.7, keywordWeight = 0.3)
)

// Single-mode search
val vectorOnly = searcher.search(embedding, "query", strategy = VectorOnly)
val keywordOnly = searcher.search(embedding, "query", strategy = KeywordOnly)
```

**When to use each strategy:**

| Strategy | Best For |
|----------|----------|
| RRF (default) | General use, heterogeneous sources |
| WeightedScore | When you know relative importance |
| VectorOnly | Semantic/conceptual queries |
| KeywordOnly | Exact term matching, names, codes |

#### How WeightedScore combines the two channels

Vector similarity and BM25 scores live on different scales, so each channel is first rescaled on its own: its best
hit becomes `1.0`, its weakest hit becomes `0.1`, and everything between is spread linearly. A chunk the channel did
not return at all scores `0.0` for that channel. The final score is the weighted average of the two channel scores,
so it lies in `[0, 1]`. If a channel returned a single hit, or all its hits tie, each of them scores `1.0`.

The `0.1` floor is deliberate. With plain min-max the weakest hit would map to `0.0`, the same as a miss, so a
relevant chunk that happened to rank last in one channel would contribute nothing from it.

```scala
import org.llm4s.vectorstore._

// Only the vector channel counts (vector weight 1.0), and the keyword channel finds nothing.
val hits = for {
  store    <- VectorStoreFactory.inMemory()
  keywords <- KeywordIndex.inMemory()
  _        <- store.upsert(VectorRecord("strong", Array(1f, 0f), Some("strong")))
  _        <- store.upsert(VectorRecord("weak", Array(0.6f, 0.8f), Some("weak")))
  found <- HybridSearcher(store, keywords).search(
    Array(1f, 0f),
    "no keyword matches this",
    topK = 5,
    strategy = FusionStrategy.WeightedScore(vectorWeight = 1.0, keywordWeight = 0.0)
  )
} yield found.map(hit => hit.id -> hit.score)
// Right(Seq("strong" -> 1.0, "weak" -> 0.1)): the weak hit is not 0.0
```

#### Invalid weights

`WeightedScore` needs finite, non-negative weights that are not both zero and whose sum is finite. Anything else
(`WeightedScore(0.0, 0.0)`, a negative, `NaN` or infinite weight) throws `IllegalArgumentException` from the
constructor, as does `RAGConfig.withWeightedScore`, which builds one. That is deliberate: weights written in code are
a programming error, like any `require`-guarded case class, and the chainable `RAGConfig` builders return a
`RAGConfig`, so they cannot return a `Left`.

If the weights come from user input, check them first so a bad value becomes a `Left` instead of an exception.
Letting the constructor do the checking keeps the rules in one place:

```scala
import org.llm4s.types.{ Result, TryOps }
import org.llm4s.vectorstore.FusionStrategy
import scala.util.Try

def weightedScore(vector: Double, keyword: Double): Result[FusionStrategy] =
  Try(FusionStrategy.WeightedScore(vector, keyword)).toResult
```

### BM25 Keyword Index

The `KeywordIndex` provides BM25-scored full-text search using SQLite FTS5:

```scala
import org.llm4s.vectorstore._

val results = for {
  // Create keyword index
  index <- KeywordIndex.inMemory()

  // Index documents
  _ <- index.index(KeywordDocument("doc-1", "Scala is a programming language"))
  _ <- index.index(KeywordDocument("doc-2", "Python is also popular"))

  // Search with highlights
  found <- index.searchWithHighlights("programming", topK = 5)
  _ = index.close()
} yield found

results.foreach(_.foreach { r =>
  println(s"${r.id}: ${r.score}")
  r.highlights.foreach(h => println(s"  ...${h}..."))
})
```

### Factory Methods

Each returns a `Result[HybridSearcher]`:

```scala
import org.llm4s.vectorstore._

// In-memory (development)
val memSearcher = HybridSearcher.inMemory()

// File-based SQLite
val fileSearcher = HybridSearcher.sqlite(
  vectorDbPath = "/path/to/vectors.db",
  keywordDbPath = "/path/to/keywords.db"
)

// From configuration
val config = HybridSearcher.Config()
  .withVectorStore(
    VectorStoreFactory.Config.pgvector(
      connectionString = "jdbc:postgresql://localhost:5432/mydb",
      tableName = "vectors"
    )
  )
  .withRRF(k = 60)

val configSearcher = HybridSearcher(config)
```

### PostgreSQL Native Hybrid Search

For production deployments, you can run fully PostgreSQL-based hybrid search using pgvector for vector similarity and PostgreSQL native full-text search (tsvector/tsquery) for BM25-like keyword matching. This eliminates the need for a separate SQLite database.

**Requirements:** PostgreSQL 16+ with pgvector extension (PostgreSQL 18+ recommended for best performance)

```scala
import org.llm4s.vectorstore._

val embedding = Array(0.1f, 0.2f, 0.3f)

val results = for {
  // Create fully PostgreSQL-based hybrid searcher with shared connection pool
  searcher <- HybridSearcher.pgvectorShared(
    connectionString = "jdbc:postgresql://localhost:5432/mydb",
    user = "postgres",
    password = "secret",
    vectorTableName = "vectors",
    keywordTableName = "documents"
  )

  // Index documents (both vector and keyword stores share the same PostgreSQL database)
  _ <- searcher.vectorStore.upsert(VectorRecord("doc-1", embedding, Some("Scala programming")))
  _ <- searcher.keywordIndex.index(KeywordDocument("doc-1", "Scala programming"))

  // Search using both vector similarity and PostgreSQL full-text search
  found <- searcher.search(embedding, "Scala", topK = 10)
  _ = searcher.close()
} yield found
```

**PostgreSQL Keyword Index:**

The `PgKeywordIndex` uses PostgreSQL native full-text search capabilities:
- **tsvector** for tokenized document representation
- **ts_rank_cd** for BM25-like relevance scoring
- **websearch_to_tsquery** for natural search syntax (supports `"exact phrases"`, `OR`, `-exclude`)
- **ts_headline** for highlighted snippets
- **GIN indexes** for fast full-text and metadata queries

```scala
import org.llm4s.vectorstore._

val filtered = for {
  // Create standalone PostgreSQL keyword index
  keywordIndex <- KeywordIndex.postgres(
    connectionString = "jdbc:postgresql://localhost:5432/mydb",
    user = "postgres",
    password = "secret",
    tableName = "documents"  // Creates documents_keyword table
  )

  // Index with metadata
  _ <- keywordIndex.index(KeywordDocument(
    id = "doc-1",
    content = "PostgreSQL provides powerful full-text search",
    metadata = Map("lang" -> "en", "type" -> "tutorial")
  ))

  // Search with highlights
  highlighted <- keywordIndex.searchWithHighlights("full-text search", topK = 5)
  _ = highlighted.foreach { r =>
    println(s"${r.id}: ${r.score}")
    r.highlights.foreach(h => println(s"  ${h}"))  // Contains <b>highlighted</b> terms
  }

  // Filter by metadata
  found <- keywordIndex.search(
    "PostgreSQL",
    topK = 10,
    filter = Some(MetadataFilter.Equals("lang", "en"))
  )
  _ = keywordIndex.close()
} yield found
```

**RAG Configuration:**

Use `withPgHybrid()` to configure fully PostgreSQL-based hybrid search in the RAG pipeline:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag._

val config = RAGConfig()
  .withEmbeddings("openai")
  .withPgHybrid(
    connectionString = "jdbc:postgresql://localhost:5432/mydb",
    user = "postgres",
    password = "secret",
    vectorTableName = "vectors",
    keywordTableName = "documents"
  )
  .withRRF(60)  // Reciprocal Rank Fusion

val results = for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  // The embedding provider's settings come from llm4s.embeddings
  rag <- RAG.build(config, _ => Llm4sConfig.embeddings().map(_._2))

  // Documents are stored in PostgreSQL for both vector and keyword search
  _     <- rag.ingestText("PostgreSQL hybrid search combines vector and full-text.", "doc-1")
  found <- rag.query("database search")
} yield found
```

**Benefits of PostgreSQL Native Hybrid:**
- Single database for all RAG storage (vectors, keywords, metadata)
- Shared connection pool for efficiency
- Native PostgreSQL transactions and ACID guarantees
- Simplified deployment and backup strategy
- Full-text search with stemming, stop words, and language support

---

## Reranking

Reranking improves retrieval quality by re-scoring initial search results using a more powerful model (like Cohere's cross-encoder). This is particularly useful when you retrieve many candidates and want to refine the ranking.

### Quick Start

```scala
import org.llm4s.vectorstore._
import org.llm4s.reranker._

val embedding = Array(0.1f, 0.2f, 0.3f) // the query's embedding
val reranker  = RerankerFactory.cohere(apiKey = "your-cohere-api-key")

val results = for {
  // Create hybrid searcher (index documents into it as in Hybrid Search)
  searcher <- HybridSearcher.inMemory()

  // Search with reranking
  found <- searcher.searchWithReranking(
    queryEmbedding = embedding,
    queryText = "What is Scala?",
    topK = 5,           // Final results to return
    rerankTopK = 50,    // Candidates to rerank
    reranker = Some(reranker)
  )
} yield found

results.foreach(_.foreach { r =>
  println(s"${r.id}: ${r.score}")
})
```

### Reranker Options

```scala
import org.llm4s.reranker._

// Cohere reranker (recommended for production)
val cohereReranker = RerankerFactory.cohere(
  apiKey = "your-api-key",
  model = "rerank-english-v3.0",  // or rerank-multilingual-v3.0
  baseUrl = "https://api.cohere.com"
)

// Passthrough reranker (no-op, preserves original order)
val passthrough = RerankerFactory.passthrough

// From typed config
val config = RerankProviderConfig(
  apiKey = "your-api-key",
  model = "rerank-english-v3.0",
  baseUrl = "https://api.cohere.com"
)
val fromConfig = RerankerFactory.fromConfig(Some(config))
```

### LLM Reranker

`LLMReranker` uses any configured chat model as the judge, so you can rerank without a Cohere key. Build it directly or through `RerankerFactory.llm`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.reranker.{ LLMReranker, RerankerFactory }

val reranker = for {
  providerCfg <- Llm4sConfig.defaultProvider()
  client      <- LLMConnect.getClient(providerCfg)
} yield LLMReranker(client) // same as RerankerFactory.llm(client)

// Optional knobs (both have defaults):
//   RerankerFactory.llm(client, batchSize = 5, systemPrompt = Some("..."))
```

How it scores:

- **Batches.** Documents go to the model `batchSize` at a time (default `LLMReranker.DEFAULT_BATCH_SIZE` = 10), one model call per batch. A smaller batch means more calls but shorter prompts.
- **1000-character cut.** Each document is truncated to its first 1000 characters, on a code-point boundary, before it goes into the prompt. Put the most relevant text first in long chunks.
- **Scores.** The model must answer with a JSON array of scores. Each score is clamped to `[0, 1]`, and an entry that is not a number scores 0.5. A short array is padded with 0.5; a long one is cut to the batch size.
- **Failed batches.** If a batch's call or parse fails, every document in that batch gets a neutral 0.5 and reranking continues with the next batch. Cancellation is the exception: it is returned as an error.
- **`systemPrompt`.** Replaces the default judging instructions. Keep asking for "ONLY a JSON array of scores" so replies stay parseable.

**LLM vs Cohere:** the LLM reranker reuses the model you already have and needs no extra service, but every batch is a full chat completion. That makes it slower and usually more expensive per query than Cohere's dedicated rerank endpoint, which scores all candidates in one request. Prefer Cohere for latency-sensitive or high-volume retrieval, and the LLM reranker when you can't add another provider or want the judging criteria in your own prompt.

### Reranking in the RAG pipeline

`RAGConfig` selects the strategy with `RerankingStrategy`:

| Strategy | Builder | Notes |
|---|---|---|
| `RerankingStrategy.None` | (default) | Results keep their fused retrieval order |
| `RerankingStrategy.Cohere(model)` | `.withCohereReranking(model = "rerank-english-v3.0")` | Needs a Cohere reranker config |
| `RerankingStrategy.LLM` | `.withLLMReranking` | Needs an LLM client: add `.withLLM(client)` when building RAG |

`.withRerankTopK(k)` sets how many retrieved candidates are reranked.

### Direct Reranking API

```scala
import org.llm4s.reranker._

val reranker = RerankerFactory.cohere(apiKey = "xxx")

val request = RerankRequest(
  query = "What is Scala?",
  documents = Seq(
    "Scala is a programming language",
    "Python is popular for ML",
    "Scala runs on the JVM"
  ),
  topK = Some(2)
)

val response = reranker.rerank(request)
response.foreach { r =>
  r.results.foreach { result =>
    println(s"[${result.index}] ${result.score}: ${result.document}")
  }
}
```

### Configuration

Load the reranker from configuration at the edge of your application with
`RerankerConfigLoader` (in `llm4s-rag`), and hand it to `RAG.build`:

```scala
import org.llm4s.config.{ Llm4sConfig, RerankerConfigLoader }
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.{ RAG, RAGConfig }

val config = RAGConfig().withEmbeddings("openai")

for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  reranker <- RerankerConfigLoader.default()   // Option[RerankProviderConfig]; None when disabled
  rag <- RAG.build(
    config,
    _ => Llm4sConfig.embeddings().map(_._2),
    resolveRerankerConfig = () => Right(reranker)
  )
} yield rag
```

It reads `llm4s.rerank`. The Cohere key is `llm4s.rerank.cohere.apiKey` when set, and otherwise
Cohere's shared `llm4s.credentials.cohere.apiKey`, bound to `COHERE_API_KEY` - the key a Cohere
chat section uses too, so one variable serves both. Set `llm4s.rerank.cohere.apiKey` only to bill
reranking to another account. (Before `RerankerConfigLoader`, nothing read `llm4s.rerank`,
although `reference.conf` bound it.)

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `RERANK_PROVIDER` | Provider: cohere, none | none |
| `COHERE_API_KEY` | Cohere API key, shared with Cohere chat (`llm4s.credentials.cohere.apiKey`) | - |
| `COHERE_RERANK_MODEL` | Model name | rerank-english-v3.0 |
| `COHERE_RERANK_BASE_URL` | API base URL | https://api.cohere.com |

---

## Document Chunking

Document chunking splits text into manageable pieces for embedding and retrieval. LLM4S provides multiple chunking strategies optimized for different content types. In the examples, `documentText` is the text to split.

### Quick Start

```scala
import org.llm4s.chunking._

// Create a sentence-aware chunker (recommended)
val chunker = ChunkerFactory.sentence()

// Chunk a document
val chunks = chunker.chunk(documentText, ChunkingConfig(
  targetSize = 800,   // Target chunk size in characters
  maxSize = 1200,     // Longer sentences are split at whitespace; a single longer word stays whole
  overlap = 150       // Overlap between consecutive chunks
))

chunks.foreach { chunk =>
  println(s"[${chunk.index}] ${chunk.content.take(50)}...")
}
```

### Chunking Strategies

```scala
import org.llm4s.chunking._

// Sentence-aware chunking (recommended for most text)
// Respects sentence boundaries for semantic coherence
val sentenceChunker = ChunkerFactory.sentence()

// Simple character-based chunking
// Fast but may split mid-sentence
val simpleChunker = ChunkerFactory.simple()

// Auto-detect based on content
// Detects markdown and chooses appropriate strategy
val autoChunker = ChunkerFactory.auto(documentText)

// By strategy name
val chunker = ChunkerFactory.create("sentence")  // or "simple"
```

### Configuration Presets

```scala
import org.llm4s.chunking._

// Default: 800 char target, 150 overlap
val defaultConfig = ChunkingConfig.default

// Small chunks: 400 char target, 75 overlap
// Better for precise retrieval
val smallConfig = ChunkingConfig.small

// Large chunks: 1500 char target, 250 overlap
// Better for broader context
val largeConfig = ChunkingConfig.large

// No overlap
val noOverlapConfig = ChunkingConfig.noOverlap

// Custom configuration
val customConfig = ChunkingConfig(
  targetSize = 600,
  maxSize = 900,
  overlap = 100,
  minChunkSize = 50,
  preserveCodeBlocks = true,
  preserveHeadings = true
)
```

### With Source Metadata

```scala
import org.llm4s.chunking._

val chunker = ChunkerFactory.sentence()

// Chunks include source file in metadata
val chunks = chunker.chunkWithSource(
  text = documentText,
  sourceFile = "docs/guide.md",
  config = ChunkingConfig.default
)

chunks.foreach { chunk =>
  println(s"From: ${chunk.metadata.sourceFile.getOrElse("unknown")}")
  println(s"Content: ${chunk.content.take(50)}...")
}
```

### Invalid Chunking Settings

`ChunkingConfig` checks its values when you build it, so a chunker never receives a configuration it cannot handle:

| Rule | Failing example |
|------|-----------------|
| `targetSize` is positive | `ChunkingConfig(targetSize = 0)` |
| `maxSize >= targetSize` | `ChunkingConfig(targetSize = 800, maxSize = 400)` |
| `0 <= overlap < targetSize` | `ChunkingConfig(targetSize = 800, overlap = 800)` |
| `minChunkSize` is not negative | `ChunkingConfig(minChunkSize = -1)` |

A value that breaks a rule throws `IllegalArgumentException` (the message names the rule, for example
`overlap must be >= 0 and < targetSize`). `RAGConfig.withChunking(strategy, size, overlap)` builds a
`ChunkingConfig`, so it throws too. Every configuration that *can* be built is safe: no chunker throws for it, and the
largest overlap allowed (`targetSize - 1`) still moves each chunk one character forward.

When the sizes come from user input, check them first. To get a `Left(ValidationError)` straight from the numbers, use
`ChunkingUtils.chunkTextValidated`; to build a config, let the constructor decide:

```scala
import org.llm4s.chunking.ChunkingConfig
import org.llm4s.llmconnect.utils.ChunkingUtils
import org.llm4s.types.{ Result, TryOps }
import scala.util.Try

val text = "one two three four five six seven eight nine ten"

// Left(ValidationError) with field "overlap" (or "size"), not an exception
val chunks = ChunkingUtils.chunkTextValidated(text, size = 20, overlap = 20)

def chunkingConfig(size: Int, overlap: Int): Result[ChunkingConfig] =
  Try(
    ChunkingConfig(
      targetSize = size,
      maxSize = math.min(size.toLong * 3 / 2, Int.MaxValue.toLong).toInt, // Long: no overflow for a large size
      overlap = overlap
    )
  ).toResult
```

### Chunking Best Practices

| Content Type | Recommended Strategy | Config |
|--------------|---------------------|--------|
| Prose/articles | `sentence` | `default` |
| Technical docs | `sentence` | `large` |
| Code files | `simple` | Custom with no overlap |
| Q&A pairs | `sentence` | `small` |
| Mixed content | `auto` | `default` |

**Tips:**
- Use overlap for context continuity in retrieval
- Smaller chunks improve retrieval precision but may lose context
- Larger chunks preserve context but may dilute relevance
- Sentence chunking prevents mid-thought splits

---

## Integration with RAG Pipeline

### Complete RAG Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ Completion, Conversation, EmbeddingRequest, InputPurpose, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.llm4s.vectorstore._

val documents = Seq(
  "Scala is a programming language",
  "LLM4S provides LLM integration",
  "Vector stores enable semantic search"
)
val query = "What is Scala?"

val response: Result[Completion] = for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry

  // 1. Create embedding client and vector store
  settings        <- Llm4sConfig.embeddings()
  providerId       = settings._1
  embeddingConfig  = settings._2
  dimensions      <- ModelDimensionRegistry.getDimension(providerId, embeddingConfig.model)
  model            = EmbeddingModelConfig(embeddingConfig.model, dimensions)
  embeddingClient <- EmbeddingClient.from(providerId, embeddingConfig)
  vectorStore     <- VectorStoreFactory.inMemory()

  // 2. Ingest documents: one request, one vector per document, in order
  embedded <- embeddingClient.embed(EmbeddingRequest(documents, model))
  records = documents.zip(embedded.embeddings).zipWithIndex.map { case ((doc, vector), idx) =>
    VectorRecord(id = s"doc-$idx", embedding = vector.map(_.toFloat).toArray, content = Some(doc))
  }
  _ <- vectorStore.upsertBatch(records)

  // 3. Query with retrieval
  queried  <- embeddingClient.embed(EmbeddingRequest(Seq(query), model, InputPurpose.Query))
  relevant <- vectorStore.search(queried.embeddings.flatten.map(_.toFloat).toArray, topK = 3)

  // 4. Augment prompt with context
  context = relevant.map(_.record.content.getOrElse("")).mkString("\n")
  prompt = s"""Based on the following context:
$context

Answer this question: $query"""

  // 5. Generate response
  providerConfig <- Llm4sConfig.defaultProvider()
  llm            <- LLMConnect.getClient(providerConfig)
  completion     <- llm.complete(Conversation(Seq(UserMessage(prompt))))
} yield completion
```

---

## Best Practices

### Resource Management

Always close stores when done:

```scala
import org.llm4s.vectorstore._

val count = VectorStoreFactory.inMemory().flatMap { store =>
  // Use scala.util.Using for automatic cleanup
  scala.util.Using.resource(new java.io.Closeable {
    def close(): Unit = store.close()
  }) { _ =>
    store.count() // Use the store
  }
}
```

### Batch Operations

Use batch operations for efficiency:

```scala
// Good - single batch call
store.upsertBatch(records)

// Less efficient - individual calls
records.foreach(store.upsert)
```

### Error Handling

All operations return `Result[A]`:

```scala
store.search(embeddingVector, topK = 10) match {
  case Right(results) =>
    results.foreach(r => println(r.score))
  case Left(error) =>
    println(s"Search failed: ${error.formatted}")
}

// Or use for-comprehension
for {
  results <- store.search(embeddingVector, topK = 10)
  count <- store.count()
} yield (results, count)
```

### Metadata Design

Design metadata for your filtering needs:

```scala
// Good - filterable metadata
VectorRecord(
  id = "doc-1",
  embedding = embeddingVector,
  metadata = Map(
    "type" -> "article",
    "source" -> "wikipedia",
    "lang" -> "en",
    "year" -> "2024"
  )
)

// Then filter efficiently
store.search(
  embeddingVector,
  topK = 10,
  filter = Some(Equals("type", "article").and(Equals("lang", "en")))
)
```

---

## Performance Considerations

### SQLite Backend

The SQLite backend is suitable for:
- Development and testing
- Small to medium datasets (~100K vectors)
- Single-machine deployments
- Scenarios where simplicity is preferred

**Limitations:**
- Vector similarity computed in Scala (not hardware-accelerated)
- All candidate vectors loaded into memory during search
- No built-in sharding or replication

### pgvector Backend

PostgreSQL with pgvector is ideal for:
- Production workloads with existing PostgreSQL infrastructure
- Medium to large datasets (millions of vectors)
- Teams familiar with PostgreSQL operations
- Applications requiring ACID transactions

**Features:**
- HNSW indexing for fast approximate nearest neighbor search
- Connection pooling with HikariCP
- Native vector operations in PostgreSQL
- Excellent SQL tooling and monitoring

**Setup:**
```bash
# Enable pgvector extension
psql -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### Qdrant Backend

Qdrant is recommended for:
- High-performance production workloads
- Large-scale deployments (billions of vectors)
- Cloud-native architectures
- Teams wanting managed vector database service

**Features:**
- Cloud-native architecture with horizontal scaling
- REST and gRPC APIs
- Rich filtering on payload fields
- Snapshot and backup capabilities
- Managed cloud offering available

**Setup:**
```bash
# Local development with Docker
docker run -p 6333:6333 qdrant/qdrant

# Or use Qdrant Cloud for production
```

### Choosing a Backend

| Requirement | Recommended Backend |
|-------------|---------------------|
| Development/testing | SQLite (in-memory) |
| Single-machine production | SQLite (file-based) |
| Existing PostgreSQL | pgvector |
| Existing Elasticsearch or OpenSearch cluster | `ElasticsearchVectorStore` / `OpenSearchVectorStore` |
| Full PostgreSQL hybrid RAG | pgvector + PgKeywordIndex |
| High-scale production | Qdrant |
| Managed service | Qdrant Cloud |

**Note:** For hybrid search with PostgreSQL, use `HybridSearcher.pgvectorShared()` or `RAGConfig.withPgHybrid()` to run both vector and keyword search against a single PostgreSQL database. This provides simplified operations, shared connection pooling, and ACID guarantees.

---

## API Reference

### VectorStore Trait

```scala
trait VectorStore {
  def upsert(record: VectorRecord): Result[Unit]
  def upsertBatch(records: Seq[VectorRecord]): Result[Unit]
  def search(queryVector: Array[Float], topK: Int = 10,
             filter: Option[MetadataFilter] = None): Result[Seq[ScoredRecord]]
  def get(id: String): Result[Option[VectorRecord]]
  def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]]
  def delete(id: String): Result[Unit]
  def deleteBatch(ids: Seq[String]): Result[Unit]
  def deleteByFilter(filter: MetadataFilter): Result[Long]
  def count(filter: Option[MetadataFilter] = None): Result[Long]
  def list(limit: Int = 100, offset: Int = 0,
           filter: Option[MetadataFilter] = None): Result[Seq[VectorRecord]]
  def clear(): Result[Unit]
  def stats(): Result[VectorStoreStats]
  def close(): Unit
}
```

---

## Measuring RAG Quality

Once you have a RAG pipeline, use the evaluation framework to measure and improve retrieval quality.

### Quick Evaluation

Given an `LLMClient` to judge with (`client`), an `EmbeddingClient` (`embedder`) and its `EmbeddingModelConfig`
(`embeddingModel`):

```scala
import org.llm4s.rag.evaluation._

val evaluator = new RAGASEvaluator(client, embedder, embeddingModel)
val sample = EvalSample(
  question = "What is Scala?",
  answer = "Scala is a programming language that runs on the JVM.", // your pipeline's answer
  contexts = Seq("Scala is a programming language", "Scala runs on the JVM"), // the chunks it retrieved
  groundTruth = Some("Scala is a programming language.")
)

val metrics = evaluator.evaluate(sample)
metrics.foreach { m =>
  println(s"RAGAS Score: ${m.ragasScore}")
  m.getMetric("faithfulness").foreach(f => println(s"Faithfulness: ${f.score}"))
}
```

### Benchmarking Different Configurations

Compare chunking strategies, fusion methods, and embedding providers:

```bash
# Compare fusion strategies
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite fusion"

# Compare chunking strategies
sbt "samples/runMain org.llm4s.samples.rag.BenchmarkExample --suite chunking"
```

**See the full [RAG Evaluation Guide](rag-evaluation) for:**
- RAGAS metrics explained (faithfulness, relevancy, precision, recall)
- Running benchmarks programmatically
- Optimization workflow and best practices
- Actual benchmark results and recommendations

---

## Next Steps

- **[RAG Evaluation Guide](rag-evaluation)** - Measure and improve RAG quality
- **[Embeddings Configuration](../getting-started/configuration#embeddings-configuration)** - Configure embedding providers
- **[Examples Gallery](/examples/#embeddings-examples)** - See RAG examples in action
- **[RAG in a Box](https://github.com/llm4s/rag_in_a_box)** - Production-ready RAG server with REST API, admin UI, and deployment options
