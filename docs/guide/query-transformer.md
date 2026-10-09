---
layout: page
title: Query Transformers
parent: User Guide
nav_order: 19
---

# RAG Query Transformers
{: .no_toc }

Rewrite the user's query before it is embedded and sent to the vector store, improving retrieval quality for vague or conversational questions.
{: .fs-6 .fw-300 }

## Table of contents
{: .text-delta }
1. TOC
{:toc}

## Overview

A RAG pipeline can rewrite the user's query before it is embedded: [`RAGConfig`](https://github.com/llm4s/llm4s/blob/main/modules/rag/src/main/scala/org/llm4s/rag/RAGConfig.scala) accepts a chain of `QueryTransformer`s, and `RAG` applies the chain before retrieval (`QueryTransformer.applyChain`).

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.rag.RAG
import org.llm4s.rag.transform.LLMQueryRewriter

val llmClient: LLMClient = ??? // see the Basic Usage guide

val rag = RAG.builder()
  .withEmbeddings("openai")
  .withQueryTransformer(LLMQueryRewriter(llmClient))
  .build()

// "tell me about that config thing"
// → rewritten to "RAGConfig configuration options and builder pattern"
// → then embedded and searched
val results = rag.query("tell me about that config thing")
```

## Built-in transformers

All built-in transformers live in `org.llm4s.rag.transform`:

| Transformer | Name | What it does |
|-------------|------|--------------|
| `LLMQueryRewriter(llmClient)` | `llm-query-rewriter` | Asks an LLM to rewrite the query into a precise, search-friendly form |
| `LLMQueryRewriter(llmClient, systemPrompt)` | `llm-query-rewriter` | Same, with a custom system prompt |
| `IdentityTransformer()` | `identity` | Returns the query unchanged |

`LLMQueryRewriter` sends the query to the LLM with `temperature = 0.0` and a default system prompt that instructs the model to return only the rewritten query, preserve the original intent, and expand abbreviations. If the LLM call fails, the error is wrapped in a `ProcessingError` with the stage `"query-rewrite"`.

`IdentityTransformer` is a pass-through: it is useful for testing pipeline composition without side effects, or as a default placeholder.

## Adding transformers to a RAG pipeline

`RAG.builder()` returns a `RAGConfig`, which exposes two methods:

- `withQueryTransformer(transformer: QueryTransformer)` — **appends** one transformer to the existing chain
- `withQueryTransformers(transformers: Seq[QueryTransformer])` — **replaces** the whole chain

```scala
import org.llm4s.rag.RAG
import org.llm4s.rag.transform.{IdentityTransformer, LLMQueryRewriter}

val config = RAG.builder()
  .withEmbeddings("openai")
  .withQueryTransformer(LLMQueryRewriter(llmClient))       // appended
  .withQueryTransformer(IdentityTransformer())             // appended after the rewriter
  // alternatively, set the whole chain at once:
  // .withQueryTransformers(Seq(LLMQueryRewriter(llmClient), IdentityTransformer()))
```

## Chaining and error behaviour

Transformers run **sequentially, in the order they were added**, and each transformer receives the output of the previous one:

```scala
val rag = RAG.builder()
  .withEmbeddings("openai")
  .withQueryTransformer(LLMQueryRewriter(llmClient))
  .withQueryTransformer(IdentityTransformer())
  .build()
```

`QueryTransformer.applyChain` folds the chain over the query with `Result[String]` (`Either[LLMError, String]`) and **short-circuits at the first error**: no further transformer runs, and the failed `Result` propagates to `rag.query`. A successful chain passes the fully transformed string to the embedding step.

## Writing your own transformer

A `QueryTransformer` is a trait with two members:

```scala
trait QueryTransformer {
  def transform(query: String): Result[String]
  def name: String
}
```

`name` is used in logging and tracing. The example below expands a known abbreviation deterministically — no LLM call involved:

```scala
import org.llm4s.error.ProcessingError
import org.llm4s.rag.transform.QueryTransformer
import org.llm4s.types.Result

final class AbbreviationExpander(abbreviations: Map[String, String]) extends QueryTransformer {

  override val name: String = "abbreviation-expander"

  override def transform(query: String): Result[String] = {
    val expanded = query.split("\\s+").map { token =>
      abbreviations.getOrElse(token.toLowerCase, token)
    }.mkString(" ")

    if (expanded.trim.isEmpty)
      Left(ProcessingError(name, "Query is empty after expansion"))
    else Right(expanded)
  }
}
```

Using it (a real call, not just a definition):

```scala
val expander = new AbbreviationExpander(Map(
  "k8s"  -> "Kubernetes",
  "pg"   -> "PostgreSQL",
  "fts5" -> "SQLite FTS5"
))

// Right("How do I deploy Kubernetes on PostgreSQL clusters?")
val result: Result[String] = expander.transform("How do I deploy k8s on pg clusters?")
```

Add it to a pipeline like any built-in transformer:

```scala
val rag = RAG.builder()
  .withEmbeddings("openai")
  .withQueryTransformer(expander)
  .build()
```

Keep custom transformers deterministic and cheap when possible — they run on every query, so an LLM-backed transformer adds latency to every retrieval.
