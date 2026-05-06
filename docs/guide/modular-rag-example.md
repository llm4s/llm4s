---
layout: page
title: Modular RAG Example
parent: User Guide
---

# Modular RAG Example

This guide introduces a practical, modular Retrieval-Augmented Generation (RAG) example for LLM4S.

The sample lives in:

- `modules/samples/src/main/scala/org/llm4s/samples/rag/modular/ModularRAGExample.scala`
- `modules/samples/src/main/scala/org/llm4s/samples/rag/modular/RAGModules.scala`

## Architecture

The example intentionally separates the pipeline into modules:

1. `IngestionModule`: document ingestion from filesystem (`.txt`, `.md`, `.pdf`, `.docx`) or direct text.
2. `RetrievalModule`: similarity retrieval (`topK`) from indexed chunks.
3. `GenerationModule`: grounded answer generation from retrieved contexts.

This keeps cross-cutting concerns small and makes each stage easy to evolve or test independently.

## Run

### 1) Retrieval + generation (if LLM provider configured)

```bash
sbt "samples/runMain org.llm4s.samples.rag.modular.ModularRAGExample"
```

If no document path is passed, the sample ingests a small built-in corpus.

### 2) Use your own document directory

```bash
sbt "samples/runMain org.llm4s.samples.rag.modular.ModularRAGExample ./docs \"What are the key reliability patterns?\""
```

The ingestion stage supports file extraction through the core RAG API, including PDF.

## Configuration

The sample resolves embedding and LLM settings from `Llm4sConfig`:

- Embeddings are required.
- LLM is optional. If LLM config is missing, the sample still runs ingestion + retrieval and skips answer generation.

## Why this pattern

This modular RAG layout is useful for real applications because you can:

- swap retrieval strategy without changing ingestion/generation wiring,
- test retrieval quality independently,
- run retrieval-only workflows when generation is disabled or unavailable.
