---
layout: page
title: RAG in Scala
parent: Scala AI
nav_order: 3
description: "Retrieval-augmented generation in Scala with llm4s-rag: ingest documents, embed, store in memory or pgvector, retrieve, answer with sources and evaluate."
search_title: "RAG in Scala: ingest, embed, retrieve, evaluate"
---

# RAG in Scala
{: .no_toc }

To answer questions over your own documents in Scala, use `llm4s-rag`: one `RAG` pipeline chunks and embeds what you
ingest, stores the vectors, retrieves the passages that match a question and has a model answer from them, with the
passages it used. The vectors live in memory, in SQLite or in PostgreSQL with pgvector, and the quality can be
measured with RAGAS metrics.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

## Add the module

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-rag"    % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-openai" % "{{ site.data.project.latest_release }}"
)
```

The provider module supplies the embedding model as well as the chat model: OpenAI here; Ollama, Voyage AI, Jina AI
and Cohere embeddings have modules of their own ([embeddings](../guide/embeddings)). The embedding model is chosen in
configuration, for example `EMBEDDING_MODEL=openai/text-embedding-3-small`.

## Build a pipeline

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.types.Result

def docsPipeline(client: LLMClient): Result[RAG] = for {
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  embeddingConfig <- Llm4sConfig.embeddings()
  rag <- RAG
    .builder()
    .withEmbeddings(embeddingConfig.providerId.asString)
    .withPgVector()
    .withLLM(client)
    .build(_ => Right(embeddingConfig))
} yield rag
```

`withPgVector()` keeps the vectors in PostgreSQL with the pgvector extension, on `localhost:5432` (its overloads take
a connection string, user, password and table). Leave it out and the pipeline keeps everything in memory, which suits
a test or a demo; `withSQLite(path)` keeps it in a file. `client` is the chat client that writes the answers, built as
on the [first-call page](llm-framework#a-first-call).

## Ingest and ask

```scala
import org.llm4s.rag.{ RAG, RAGAnswerResult }
import org.llm4s.types.Result

def askTheDocs(rag: RAG, question: String): Result[RAGAnswerResult] = for {
  _      <- rag.ingestText("Opaque types hide their representation outside the defining scope.", "opaque-types")
  _      <- rag.ingestText("Given instances supply the values of context parameters.", "givens")
  answer <- rag.queryWithAnswer(question)
} yield answer
```

`queryWithAnswer` returns the answer and the passages it was given (`contexts`), so you can show sources. The answer
is generated at temperature 0.0, so it keeps to the passages; set other options for every answer with
`RAGConfig.withCompletionOptions(CompletionOptions(temperature = Some(0.7), maxTokens = Some(512)))`, or for one call with
`rag.queryWithAnswer(question, options = ...)`.

To stream the answer to a chat UI, `rag.queryWithAnswerStreaming(question)(onContexts, onChunk)` takes two
callbacks: the contexts arrive first, so the sources can be shown at once, then each `StreamedChunk` of the answer as
the model writes it; the call returns the whole answer when the stream ends.
`queryWithPermissionsAndAnswerStreaming` does the same over the permission-filtered contexts.

For a chat, pass the conversation so far as `history`: `rag.queryWithAnswer(question, history = Seq(UserMessage(...),
AssistantMessage(...)))`, and likewise on the permission and streaming variants. The model sees it between the system
prompt and the passages with the question; a `SystemMessage` in it is a `ValidationError`, since the pipeline sends its
own.

A follow-up such as "what about the second one?" retrieves poorly on its own, so a question condenser can turn it into
the standalone question to search with; the answer is still to the question asked, and the condenser runs only when
the history is non-empty. The built-in one asks a model:

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.rag.{ QuestionCondenser, RAGConfig }

def chatConfig(client: LLMClient, smallClient: LLMClient): RAGConfig =
  RAGConfig.default
    .withLLM(client)
    .withQuestionCondenser(QuestionCondenser.llm(smallClient, maxHistoryMessages = 6))
```

It sends the latest `maxHistoryMessages` user and assistant messages (10 by default; tool messages are left out) as a
transcript with the follow-up, under `QuestionCondenser.DefaultSystemPrompt` (override it with `systemPrompt = ...`), at
temperature 0 unless `options` says otherwise. If the call fails the query fails with the client's error (a rate
limit is still a `RateLimitError`); a blank reply searches with the original question. To search with the original
question whenever the condenser fails, wrap it: `QuestionCondenser((q, h) => condenser.condense(q, h).orElse(Right(q)))`. With
`withTracing` its calls are traced and tagged `rag.condense`. Any function `(question, history) => Result[String]` is a
condenser too: `withQuestionCondenser((question, history) => ...)`. Instead
of text, `rag.ingest(path)` reads a file or a whole directory, and `rag.query(question)` returns the passages without
asking the model. Retrieval is hybrid: vector similarity and keyword (BM25) search, fused, with optional reranking by
Cohere or by a model ([vector store guide](../guide/vector-store#hybrid-search)).

## Other stores

Besides the pipeline's own storage, the `VectorStore` API has backends for Qdrant, and, in separate modules,
Elasticsearch, OpenSearch, Redis and Valkey. They are covered in the [vector store guide](../guide/vector-store).

## Evaluate

A RAG pipeline needs measuring, not eyeballing. LLM4S computes the RAGAS metrics (faithfulness, answer relevancy,
context precision and context recall) and has a benchmark harness that compares chunking strategies, fusion
strategies and embedding providers on a dataset: see [RAG evaluation](../guide/rag-evaluation).

## Going further

- [Modular RAG example](../guide/modular-rag-example): a pipeline assembled step by step.
- [Query transformers](../guide/rag-query-transformers): rewrite a question before retrieval.
- [Permission-based RAG](../guide/permission-based-rag): retrieve only what the asking user may see.
- Cookbook: [answer questions over a folder of files](../examples/cookbook/folder-qa) and
  [document Q&A](../examples/cookbook/document-qa), both runnable with no API key.
- From Java: [`llm4s-java-rag`](../guide/java-rag).
