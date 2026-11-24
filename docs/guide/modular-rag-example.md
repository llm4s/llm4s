---
layout: page
title: Modular RAG Example
description: "A modular RAG example for LLM4S: ingestion, retrieval and generation as separate modules, how to run the sample and use your own document folder."
search_title: "Modular RAG example in Scala with LLM4S"
parent: User Guide
---

# Modular RAG Example

This guide introduces a practical, modular Retrieval-Augmented Generation (RAG) example for LLM4S, built on the
`RAG` pipeline of `llm4s-rag`:

```sbt
libraryDependencies += "org.llm4s" %% "llm4s-rag" % "{{ site.data.project.latest_release }}"
```

The sample lives in:

- `modules/samples/src/main/scala/org/llm4s/samples/rag/modular/ModularRAGExample.scala`
- `modules/samples/src/main/scala/org/llm4s/samples/rag/modular/RAGModules.scala`

## Architecture

The example intentionally separates the pipeline into modules:

1. `IngestionModule`: document ingestion from filesystem (`.txt`, `.md`, `.pdf`, `.docx`) or direct text.
2. `RetrievalModule`: similarity retrieval (`topK`) from indexed chunks.
3. `GenerationModule`: grounded answer generation from retrieved contexts.

This keeps cross-cutting concerns small and makes each stage easy to evolve or test independently.

Each stage is a small trait, and its default implementation delegates to one `RAG`:

```scala
import org.llm4s.rag.{ RAG, RAGAnswerResult, RAGSearchResult }
import org.llm4s.types.Result

trait RetrievalModule {
  def retrieve(query: String, topK: Int): Result[Seq[RAGSearchResult]]
}

final class DefaultRetrievalModule(rag: RAG) extends RetrievalModule {
  override def retrieve(query: String, topK: Int): Result[Seq[RAGSearchResult]] =
    rag.query(query, topK = Some(topK))
}

trait GenerationModule {
  def answer(question: String, topK: Int): Result[RAGAnswerResult]
}

final class DefaultGenerationModule(rag: RAG) extends GenerationModule {
  override def answer(question: String, topK: Int): Result[RAGAnswerResult] =
    rag.queryWithAnswer(question, topK = Some(topK))
}
```

A test can replace any one stage with a stub of its trait, and a retrieval-only deployment can leave the
`GenerationModule` out.

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

The ingestion stage extracts files through the `llm4s-rag` API, including PDF and DOCX.

## Configuration

The sample resolves embedding and LLM settings from `Llm4sConfig`:

- Embeddings are required.
- LLM is optional. If LLM config is missing, the sample still runs ingestion + retrieval and skips answer generation.

## Why this pattern

This modular RAG layout is useful for real applications because you can:

- swap retrieval strategy without changing ingestion/generation wiring,
- test retrieval quality independently,
- run retrieval-only workflows when generation is disabled or unavailable.
