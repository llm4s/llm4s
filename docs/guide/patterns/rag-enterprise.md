---
layout: page
title: RAG for Enterprise
description: "Production RAG with llm4s-rag: chunking, loading and syncing large document sets, hybrid search with reranking, permissions, evaluation and cost."
search_title: "Enterprise RAG patterns for Scala LLM applications"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 2
---

# RAG for Enterprise
{: .no_toc }

A RAG demo answers from a few files in memory. A production one keeps its indexes in a database, re-indexes what
changed, searches by meaning and by keyword, shows each user only what they may read, and is measured rather than
eyeballed. `llm4s-rag` ships each of these as configuration of one `RAG` pipeline.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## A production pipeline

```scala
import org.llm4s.chunking.ChunkerFactory
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.security.Secret
import org.llm4s.trace.Tracing
import org.llm4s.types.Result

def companyDocs(client: LLMClient, dbPassword: Secret, tracing: Tracing): Result[RAG] = for {
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  selected <- Llm4sConfig.embeddings()               // EMBEDDING_MODEL, e.g. openai/text-embedding-3-small
  (provider, embeddingConfig) = selected
  rag <- RAG
    .builder()
    .withEmbeddings(provider)
    .withPgHybrid("jdbc:postgresql://db:5432/rag", "rag", dbPassword) // vectors and keywords both in PostgreSQL
    .withChunking(ChunkerFactory.Strategy.Markdown, 800, 150)          // target size and overlap, in characters
    .withRRF(60)                                                       // fuse vector and keyword results
    .withTopK(8)
    .withBatchSize(32)                                                 // documents per embedding batch
    .withParallelism(4)                                                // documents processed concurrently
    .withLLM(client)
    .withTracing(tracing)
    .build(_ => Right(embeddingConfig))
} yield rag
```

`withPgHybrid` keeps both the vector index (pgvector) and the keyword index (PostgreSQL full-text search) in the
database; `withPgVector` alone keeps the keyword index in memory, and the default keeps everything in memory. The
[RAG in Scala](../../scala-ai/rag) page builds the minimal pipeline and explains `queryWithAnswer`, streaming answers,
chat history and question condensing.

## Ingesting large document sets

| Need | Component |
|---|---|
| Files, directories, URLs, a crawled site, S3 | `DirectoryLoader`, `UrlLoader`, `WebCrawlerLoader`, `S3Loader`, ... ([Document Loaders](../document-loaders)) |
| Re-index only what changed, remove what was deleted | `rag.sync(loader)`; `rag.refresh(loader)` re-ingests everything |
| Remember what was indexed across restarts | `RAGConfig.withDocumentRegistry` |
| Ingest without blocking | `ingestAsync`, `syncAsync`, `refreshAsync` |
| Chunk by structure, by sentence or by meaning | `ChunkerFactory.Strategy.Markdown`, `Sentence`, `Semantic` or `Simple` |
| Never pay twice to embed the same text | `CachedEmbeddingClient` ([Caching](../caching#3-caching-embeddings)) |

Loaders that fetch URLs refuse private and internal addresses: see
[SSRF protection](../document-loaders#ssrf-protection).

## Retrieval quality

- **Hybrid search** is the default: vector similarity and BM25 keyword search, fused with reciprocal rank fusion
  (`withRRF`) or a weighted score (`withWeightedScore`); `vectorOnly` and `keywordOnly` turn one channel off. See
  [hybrid search](../vector-store#hybrid-search).
- **Reranking** re-orders the fused candidates: `withCohereReranking()` (a cross-encoder) or `withLLMReranking`, with
  `withRerankTopK` candidates. See [reranking](../vector-store#reranking).
- **Query transformers** rewrite a question before retrieval: [Query transformers](../rag-query-transformers).
- **Other stores** - Qdrant, Elasticsearch, OpenSearch, Redis and Valkey - are in the
  [vector store guide](../vector-store).

## Who may see what

Permission-based RAG adds collections with `queryableBy` permissions and documents with `readableBy` permissions, on
PostgreSQL, and filters retrieval by the asking user before the model sees anything. See
[Permission-based RAG](../permission-based-rag). For erasure requests, see
[deleting a user's data](../data-protection#deleting-a-users-data).

## Measuring it

Faithfulness, answer relevancy, context precision and context recall (RAGAS), and a benchmark harness that compares
chunking strategies, fusion strategies and embedding providers on your dataset, are in
[RAG evaluation](../rag-evaluation). Change one setting at a time and compare the scores. In production,
`withTracing` traces each model call the pipeline makes - answering, reranking, rewriting and condensing - with its
token usage tagged by operation ([Production Monitoring](production-monitoring)).

## See also

- [Modular RAG example](../modular-rag-example)
- Cookbook: [answer questions over a folder of files](../../examples/cookbook/folder-qa) and
  [document Q&A](../../examples/cookbook/document-qa), both runnable with no API key
- [`llm4s-java-rag`](../java-rag) for Java applications
