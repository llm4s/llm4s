# llm4s-rag

Retrieval-augmented generation for llm4s: document loading and extraction, chunking, embeddings,
vector and keyword stores, hybrid search with fusion, reranking, evaluation, and a `RAG` pipeline
that ties them together. Beta (see [`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)):
it ships alongside the frozen modules on its own version train.

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-rag" % "<version>"
```

Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

**What it pulls in.** `llm4s-core`, `llm4s-media`, `llm4s-knowledgegraph` (for GraphRAG) and
`llm4s-observability` (for the Langfuse RAGAS observer), plus the document and storage libraries
that no longer sit on `llm4s-core`'s classpath: Apache Tika, POI, PDFBox, jsoup, the AWS S3 and STS
clients, the Postgres driver, SQLite and HikariCP. It does **not** depend on an embedding provider:
`RAGConfig.default` names `openai`, but the OpenAI module is a test-only dependency here, so add
the provider module you use (for example `llm4s-openai` or `llm4s-ollama`).

## What it provides

| Area | Types | Notes |
|---|---|---|
| Pipeline | `RAG`, `RAGConfig` | `RAG.build(config, ...)` returns a `Result[RAG]`; `ingest*`, `query`, `queryWithAnswer`, `queryWithGraphRAG`, `queryWithPermissions` |
| Loading and extraction | `FileLoader`, `DirectoryLoader`, `TextLoader`, `UrlLoader`, `WebCrawlerLoader`, `S3Loader`; `TikaDocumentExtractor`, `MediaExtractor` | PDF, Office and plain-text formats go through Tika |
| Chunking | `ChunkerFactory.Strategy`: `Simple`, `Sentence` (default), `Semantic`, `Markdown`; `ChunkingConfig` | default 800-character target and 150 overlap |
| Vector stores | `VectorStoreFactory.Backend`: `SQLite`, `PgVector`, `Qdrant`; in-memory | `RAGConfig` builds in-memory unless you set a path or a Postgres connection |
| Keyword search | `SQLiteKeywordIndex` (FTS5, BM25), `PgKeywordIndex`, in-memory | |
| Hybrid search | `HybridSearcher`, `FusionStrategy`: `RRF(k = 60)` (default), `WeightedScore`, `VectorOnly`, `KeywordOnly` | |
| Reranking | `RerankingStrategy`: `None` (default), `Cohere`, `LLM` | Cohere needs a key, see below |
| Query transforms | `QueryTransformer` | applied before retrieval |
| Permissions | `SearchIndex`, `queryWithPermissions` | permission-aware search over Postgres, see the [permission guide](../../docs/guide/permission-based-rag.md) |
| Evaluation | `RAGASEvaluator`; `Faithfulness`, `AnswerRelevancy`, `ContextPrecision`, `ContextRecall`; `RAGASLangfuseObserver` | |
| GraphRAG | `queryWithGraphRAG`, `GraphRAGConfig` | needs a `GraphStore` from [`llm4s-knowledgegraph`](../knowledgegraph/README.md). The `org.llm4s.knowledgegraph.graphrag` package ships in **this** module, not in the knowledge-graph one |

`org.llm4s.vectorstore.PostgresVectorHelpers` (the pgvector text codec) stays in `llm4s-core`, so
this module and `llm4s-memory-postgres` need not depend on each other.

## Configuration

Embeddings come from the embedding provider you choose; this module adds the reranker and the
permission-aware Postgres settings. Its `reference.conf` binds them to the environment:

| Key | Environment variable |
|---|---|
| `llm4s.credentials.cohere.apiKey` | `COHERE_API_KEY` (shared with the Cohere chat provider) |
| `llm4s.rerank.provider` (`cohere` or `none`) | `RERANK_PROVIDER` |
| `llm4s.rerank.cohere.baseUrl` | `COHERE_RERANK_BASE_URL` |
| `llm4s.rerank.cohere.model` | `COHERE_RERANK_MODEL` |
| `llm4s.rag.permissions.pg.host`, `port`, `database`, `user`, `password` | `PGVECTOR_HOST`, `PGVECTOR_PORT`, `PGVECTOR_DATABASE`, `PGVECTOR_USER`, `PGVECTOR_PASSWORD` |
| `llm4s.rag.permissions.pg.vectorTableName`, `keywordTableName`, `maxPoolSize` | `PGVECTOR_TABLE`, `PGVECTOR_KEYWORD_TABLE`, `PGVECTOR_MAX_POOL_SIZE` |

```hocon
llm4s.rerank {
  provider = "cohere"
  cohere.model = "rerank-english-v3.0"
}
```

Load them at the application edge. The reranker config is a function argument of `RAG.build`
(`resolveRerankerConfig = () => RerankerConfigLoader.default()`). The `SearchIndex.PgConfig` from
`PgSearchIndexConfigLoader.load(...)` builds the permission-aware index, which is attached to the
config: `PgSearchIndex(pgConfig)`, then `initializeSchema()`, then `RAGConfig().withSearchIndex(index)`
(see the permission-based RAG guide). The library does not read the environment elsewhere. The embedding provider and model are chosen with `EMBEDDING_MODEL`
(`provider/model`) and each provider module's own `llm4s.embeddings.<id>` block.

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.rag.{ RAG, RAGConfig }

val hits = for {
  service       <- Llm4sConfig.modelRegistryService()
  embeddingPair <- Llm4sConfig.embeddings()
  (provider, embeddingConfig) = embeddingPair
  rag <- RAG.build(
    RAGConfig().withEmbeddings(provider, embeddingConfig.model).withTopK(3),
    _ => Right(embeddingConfig)
  )(using service)
  _       <- rag.ingestText("Scala is a statically typed language for the JVM.", "doc-1")
  results <- rag.query("What kind of language is Scala?")
} yield {
  rag.close()
  results.map(_.content)
}
```

With no paths set, `RAG` keeps its vector and keyword stores in memory. For a file-backed index use
`RAGConfig().withSQLite("./rag.db")`; for Postgres use `withPgVector(...)` or `withPgHybrid(...)`.
Answer generation needs an LLM: add `withLLM(client)` and call `queryWithAnswer`.

## What it supports, and its limits

- **Re-ingesting a document replaces its chunks.** The new version is written first and the old
  tail removed afterwards, so a document that fails to re-ingest keeps its previous version. The
  write is not atomic across the two stores, so **concurrent re-ingests of the same document id are
  not supported**: serialise them in the caller. Different documents may be ingested concurrently.
- **Chunk ids are built from the document id.** A document's chunks are named `<docId>-chunk-<n>`
  and the stores delete by prefix, so `deleteDocument("a")` also deletes the chunks of a document
  whose id itself looks like `a-chunk-<n>` (for example `a-chunk-1`). Avoid document ids of that
  shape.
- **Invalid chunking or fusion settings throw at construction.** `ChunkingConfig` and
  `WeightedScore` validate in their constructors; for input you do not control, call
  `ChunkingUtils.chunkTextValidated`, which returns a `Left`.
- **No provider is bundled.** The embedding provider you name must be on the classpath; an absent
  one fails `RAG.build` with the registry's "not registered" error.

## Tests

```bash
sbt rag/test                # unit tests, mocked embeddings and in-memory or SQLite stores
sbt testIntegration         # the @Docker tier in modules/it: pgvector, Qdrant and Postgres suites
```

The Postgres and Qdrant suites (`modules/it/src/test/scala/org/llm4s/vectorstore`) need Docker.

## See also

- [Vector stores and RAG](../../docs/guide/vector-store.md) - the stores, fusion and reranking in depth
- [A modular RAG example](../../docs/guide/modular-rag-example.md)
- [RAG evaluation](../../docs/guide/rag-evaluation.md)
- [Permission-based RAG](../../docs/guide/permission-based-rag.md)
- [`llm4s-knowledgegraph`](../knowledgegraph/README.md) - the graph model GraphRAG builds on
