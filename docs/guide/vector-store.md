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

results.foreach { scored =>
  println(s"${scored.record.id}: ${scored.score}")
}

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

// Or with explicit connection settings
val store2 = VectorStoreFactory.pgvector(
  connectionString = "jdbc:postgresql://localhost:5432/mydb",
  user = "postgres",
  password = "secret",
  tableName = "embeddings"
).fold(...)

// Create HNSW index for faster search (optional)
store.asInstanceOf[PgVectorStore].createHnswIndex()

store.close()
```

**Setup:** Requires PostgreSQL with pgvector extension:
```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

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
).fold(...)

store.close()
```

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

```scala
// In search
store.search(queryVector, topK = 10, filter = Some(byType))

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

// Create stores
val vectorStore = VectorStoreFactory.inMemory().getOrElse(???)
val keywordIndex = KeywordIndex.inMemory().getOrElse(???)

// Create hybrid searcher
val searcher = HybridSearcher(vectorStore, keywordIndex)

// Index documents in both stores
val embedding = Array(0.1f, 0.2f, 0.3f)
vectorStore.upsert(VectorRecord("doc-1", embedding, Some("Scala programming language")))
keywordIndex.index(KeywordDocument("doc-1", "Scala programming language"))

// Search with both vector and keyword
val results = searcher.search(
  queryEmbedding = embedding,
  queryText = "Scala",
  topK = 10
)

results.foreach { r =>
  println(s"${r.id}: ${r.score} (vector: ${r.vectorScore}, keyword: ${r.keywordScore})")
}

searcher.close()
```

### Fusion Strategies

Choose how to combine vector and keyword scores:

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

### BM25 Keyword Index

The `KeywordIndex` provides BM25-scored full-text search using SQLite FTS5:

```scala
import org.llm4s.vectorstore._

// Create keyword index
val index = KeywordIndex.inMemory().getOrElse(???)

// Index documents
index.index(KeywordDocument("doc-1", "Scala is a programming language"))
index.index(KeywordDocument("doc-2", "Python is also popular"))

// Search with highlights
val results = index.searchWithHighlights("programming", topK = 5)
results.foreach { r =>
  println(s"${r.id}: ${r.score}")
  r.highlights.foreach(h => println(s"  ...${h}..."))
}

index.close()
```

### Factory Methods

```scala
// In-memory (development)
val memSearcher = HybridSearcher.inMemory().getOrElse(???)

// File-based SQLite
val fileSearcher = HybridSearcher.sqlite(
  vectorDbPath = "/path/to/vectors.db",
  keywordDbPath = "/path/to/keywords.db"
).getOrElse(???)

// From configuration
val config = HybridSearcher.Config()
  .withVectorStore(VectorStoreFactory.Config.pgvector(...))
  .withRRF(k = 60)

val configSearcher = HybridSearcher(config).getOrElse(???)
```

### PostgreSQL Native Hybrid Search

For production deployments, you can run fully PostgreSQL-based hybrid search using pgvector for vector similarity and PostgreSQL native full-text search (tsvector/tsquery) for BM25-like keyword matching. This eliminates the need for a separate SQLite database.

**Requirements:** PostgreSQL 16+ with pgvector extension (PostgreSQL 18+ recommended for best performance)

```scala
import org.llm4s.vectorstore._

// Create fully PostgreSQL-based hybrid searcher with shared connection pool
val searcher = HybridSearcher.pgvectorShared(
  connectionString = "jdbc:postgresql://localhost:5432/mydb",
  user = "postgres",
  password = "secret",
  vectorTableName = "vectors",
  keywordTableName = "documents"
).getOrElse(???)

// Index documents (both vector and keyword stores share the same PostgreSQL database)
val embedding = Array(0.1f, 0.2f, 0.3f)
searcher.vectorStore.upsert(VectorRecord("doc-1", embedding, Some("Scala programming")))
searcher.keywordIndex.index(KeywordDocument("doc-1", "Scala programming"))

// Search using both vector similarity and PostgreSQL full-text search
val results = searcher.search(embedding, "Scala", topK = 10)

searcher.close()
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

// Create standalone PostgreSQL keyword index
val keywordIndex = KeywordIndex.postgres(
  connectionString = "jdbc:postgresql://localhost:5432/mydb",
  user = "postgres",
  password = "secret",
  tableName = "documents"  // Creates documents_keyword table
).getOrElse(???)

// Index with metadata
keywordIndex.index(KeywordDocument(
  id = "doc-1",
  content = "PostgreSQL provides powerful full-text search",
  metadata = Map("lang" -> "en", "type" -> "tutorial")
))

// Search with highlights
val results = keywordIndex.searchWithHighlights("full-text search", topK = 5)
results.foreach { r =>
  println(s"${r.id}: ${r.score}")
  r.highlights.foreach(h => println(s"  ${h}"))  // Contains <b>highlighted</b> terms
}

// Filter by metadata
val filtered = keywordIndex.search(
  "PostgreSQL",
  topK = 10,
  filter = Some(MetadataFilter.Equals("lang", "en"))
)

keywordIndex.close()
```

**RAG Configuration:**

Use `withPgHybrid()` to configure fully PostgreSQL-based hybrid search in the RAG pipeline:

```scala
import org.llm4s.rag._

val config = RAGConfig()
  .withEmbeddings(EmbeddingProvider.OpenAI)
  .withPgHybrid(
    connectionString = "jdbc:postgresql://localhost:5432/mydb",
    user = "postgres",
    password = "secret",
    vectorTableName = "vectors",
    keywordTableName = "documents"
  )
  .withRRF(60)  // Reciprocal Rank Fusion

val rag = RAG.build(config, resolveProvider).getOrElse(???)

// Documents are stored in PostgreSQL for both vector and keyword search
rag.ingestText("PostgreSQL hybrid search combines vector and full-text.", "doc-1")

val results = rag.query("database search")
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

// Create hybrid searcher and reranker
val searcher = HybridSearcher.inMemory().getOrElse(???)
val reranker = RerankerFactory.cohere(apiKey = "your-cohere-api-key")

// Search with reranking
val results = searcher.searchWithReranking(
  queryEmbedding = embedding,
  queryText = "What is Scala?",
  topK = 5,           // Final results to return
  rerankTopK = 50,    // Candidates to rerank
  reranker = Some(reranker)
)

results.foreach { r =>
  println(s"${r.id}: ${r.score}")
}
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

| Environment Variable | Description | Default |
|---------------------|-------------|---------|
| `RERANK_PROVIDER` | Provider: cohere, none | none |
| `COHERE_API_KEY` | Cohere API key | - |
| `COHERE_RERANK_MODEL` | Model name | rerank-english-v3.0 |
| `COHERE_RERANK_BASE_URL` | API base URL | https://api.cohere.com |

---

## Document Chunking

Document chunking splits text into manageable pieces for embedding and retrieval. LLM4S provides multiple chunking strategies optimized for different content types.

### Quick Start

```scala
import org.llm4s.chunking._

// Create a sentence-aware chunker (recommended)
val chunker = ChunkerFactory.sentence()

// Chunk a document
val chunks = chunker.chunk(documentText, ChunkingConfig(
  targetSize = 800,   // Target chunk size in characters
  maxSize = 1200,     // Hard limit for chunk size
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
import org.llm4s.vectorstore._
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }

// 1. Create embedding client and vector store
val embeddingClient = Llm4sConfig
  .embeddings()
  .flatMap { case (provider, cfg) => EmbeddingClient.from(provider, cfg) }
  .fold(_ => ???, identity)
val vectorStore = VectorStoreFactory.inMemory().getOrElse(???)

// 2. Ingest documents
val documents = Seq(
  "Scala is a programming language",
  "LLM4S provides LLM integration",
  "Vector stores enable semantic search"
)

documents.zipWithIndex.foreach { case (doc, idx) =>
  val embedding = embeddingClient.embed(doc).getOrElse(???)
  vectorStore.upsert(VectorRecord(
    id = s"doc-$idx",
    embedding = embedding,
    content = Some(doc)
  ))
}

// 3. Query with retrieval
val query = "What is Scala?"
val queryEmbedding = embeddingClient.embed(query).getOrElse(???)

val relevant = vectorStore.search(queryEmbedding, topK = 3).getOrElse(Seq.empty)

// 4. Augment prompt with context
val context = relevant.map(_.record.content.getOrElse("")).mkString("\n")
val prompt = s"""Based on the following context:
$context

Answer this question: $query"""

// 5. Generate response
val llm = Llm4sConfig
  .provider()
  .flatMap(LLMConnect.getClient)
  .fold(_ => ???, identity)
val response = llm.complete(prompt)
```

---

## Best Practices

### Resource Management

Always close stores when done:

```scala
val store = VectorStoreFactory.inMemory().getOrElse(???)
// Use scala.util.Using for automatic cleanup
scala.util.Using.resource(new java.io.Closeable {
  def close(): Unit = store.close()
}) { _ =>
  // Use the store
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
store.search(query, topK = 10) match {
  case Right(results) =>
    results.foreach(r => println(r.score))
  case Left(error) =>
    println(s"Search failed: ${error.formatted}")
}

// Or use for-comprehension
for {
  results <- store.search(query, topK = 10)
  count <- store.count()
} yield (results, count)
```

### Metadata Design

Design metadata for your filtering needs:

```scala
// Good - filterable metadata
VectorRecord(
  id = "doc-1",
  embedding = embedding,
  metadata = Map(
    "type" -> "article",
    "source" -> "wikipedia",
    "lang" -> "en",
    "year" -> "2024"
  )
)

// Then filter efficiently
store.search(
  query,
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

```scala
import org.llm4s.rag.evaluation._

val evaluator = new RAGASEvaluator(llmClient)
val sample = EvalSample(
  question = "What is Scala?",
  answer = generatedAnswer,
  contexts = retrievedChunks,
  groundTruth = Some("Scala is a programming language.")
)

val metrics = evaluator.evaluate(sample)
metrics.foreach { m =>
  println(s"RAGAS Score: ${m.ragasScore}")
  println(s"Faithfulness: ${m.faithfulness}")
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

**See the full [RAG Evaluation Guide](rag-evaluation.md) for:**
- RAGAS metrics explained (faithfulness, relevancy, precision, recall)
- Running benchmarks programmatically
- Optimization workflow and best practices
- Actual benchmark results and recommendations

---

## Next Steps

- **[RAG Evaluation Guide](rag-evaluation.md)** - Measure and improve RAG quality
- **[Embeddings Configuration](../getting-started/configuration#embeddings-configuration)** - Configure embedding providers
- **[Examples Gallery](/examples/#embeddings-examples)** - See RAG examples in action
- **[RAG in a Box](https://github.com/llm4s/rag_in_a_box)** - Production-ready RAG server with REST API, admin UI, and deployment options
