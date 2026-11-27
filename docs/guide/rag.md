---
layout: page
title: RAG and Data
description: "RAG in LLM4S, guide by guide: loading, chunking, embedding, storing, retrieving, permissions and evaluation, plus the Spark, Flink and Kafka guides."
search_title: "RAG in Scala with LLM4S: overview of the guides"
parent: User Guide
nav_order: 300
has_children: true
---

# RAG and data pipelines

Retrieval-augmented generation (RAG) answers a question from your own documents: the documents are split into chunks,
each chunk is embedded as a vector and stored, and a question retrieves the closest chunks for the model to answer
from. This page says which guide covers which part.
{: .fs-6 .fw-300 }

The stores, hybrid search, reranking, chunking, the loaders and the `RAG` pipeline are in the `llm4s-rag` module; the
Elasticsearch and OpenSearch stores and the Redis and Valkey store are separate modules, named in the
[Vector Store](vector-store) guide. New to it? [RAG in Scala](../scala-ai/rag) builds the smallest pipeline first.

## The guides, by stage

| Stage | Guide | What it covers |
|-------|-------|----------------|
| Load | **[Document Loaders](document-loaders)** | Text, files, directories, URLs, a web crawl or S3 as a `DocumentLoader`; composing loaders; `ingest`, `sync` and `refresh`; SSRF protection and robots.txt |
| Embed | **[Embeddings](embeddings)** | Choosing a provider and model, creating an `EmbeddingClient`, embedding documents and queries, and keeping dimensions in step with the store |
| Chunk, store, search | **[Vector Store](vector-store)** | The `VectorStore` API and its backends, metadata filtering, hybrid search (keyword plus vector), reranking and document chunking |
| Follow relationships | **[Knowledge Graph](knowledge-graph)** | Building and querying graphs, extracting relationships from documents, and answering questions with graph context |
| Rewrite the query | **[Query Transformers](rag-query-transformers)** | Rewriting the user's query before it is embedded: `LLMQueryRewriter`, `IdentityTransformer`, chaining, error behaviour and your own transformer |
| Restrict | **[Permission-Based RAG](permission-based-rag)** | Collections, principals and document-level permissions, so that a query retrieves only what the user may read |
| Measure | **[RAG Evaluation](rag-evaluation)** | RAGAS metrics (faithfulness, answer relevancy, context precision and recall) and the benchmarking harness that compares chunking, fusion and embedding strategies |

## Putting the stages together

- **[Modular RAG Example](modular-rag-example)** - A sample with ingestion, retrieval and generation as separate modules
- **[RAG for Enterprise](patterns/rag-enterprise)** - One production pipeline: large document sets, hybrid search with reranking, permissions, evaluation and cost
- **[Java RAG](java-rag)** - Ingest documents and ask questions from Java with `llm4s-java-rag`: answers with their sources
- **[Caching](caching)** - Cache embeddings (exact) and model responses (semantic)
- **[RAG Benchmark Results](../reference/rag-benchmark-results)** - The RAG benchmarking harness and the results it produced

## Data pipelines

Calling a model from a batch or streaming job rather than a request handler:

- **[Spark and Databricks](spark)** - Enrich a DataFrame from a Spark job: a client per partition, `ReliableClient`, errors as data
- **[Apache Flink](flink)** - Enrich a Flink 2.x stream from Scala 3: `AsyncDataStream`, a client per task built in `open()`, dead letters as a side output
- **[Kafka](kafka)** - Enrich a Kafka topic with fs2-kafka: bounded concurrency, retries, a dead-letter topic, offsets committed after the produce
