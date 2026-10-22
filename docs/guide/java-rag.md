---
layout: page
title: Java RAG
parent: User Guide
nav_order: 31
---

# RAG from Java
{: .no_toc }

Ingest documents and ask questions about them from Java with `llm4s-java-rag`: answers come with the chunks they were
drawn from.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What `llm4s-java-rag` is

Retrieval-augmented generation (RAG) answers a question from your own documents: the documents are split into chunks,
each chunk is embedded as a vector, and a question is answered by a model that is given the chunks that match it best.
`llm4s-java-rag` puts the [RAG pipeline](vector-store) of `llm4s-rag` behind one Java class, `JRag`: you hand it the
embedding and chat clients of the [Java facade](java), ingest files or text, and `ask`. Every call returns an
`LlmResult`, as `JLlmClient`'s do, so a failure is a value you check, not an exception.

It is a module of its own, not part of `llm4s-java-api`, because `llm4s-rag` brings document parsing (Tika, POI,
PDFBox), the AWS S3 SDK and the SQLite and Postgres drivers: a Java application that only calls a model does not
receive them. It is **Experimental**: its API may change in any release
([1.0 Scope](../reference/v1-scope)).

{: .note }
> `llm4s-java-rag` is new and is published from the release after {{ site.data.project.latest_release }}. Until then,
> build it from `main` ([Unreleased changes](../getting-started/installation#unreleased-changes-main)).

## Add the dependency

The artifact is `org.llm4s:llm4s-java-rag_3`; it brings `llm4s-java-api` and `llm4s-rag` with it.

**Maven**

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-java-rag_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-java-rag_3:{{ site.data.project.latest_release }}")
}
```

**sbt**

```scala
libraryDependencies += "org.llm4s" %% "llm4s-java-rag" % "{{ site.data.project.latest_release }}"
```

It needs JDK 21, like `llm4s-java-api`.

## Build a pipeline

A pipeline needs an embedding client; to `ask`, a chat client too. With both configured in `application.conf` (see
[Embeddings](java#embeddings) and [Configure a provider](java#configure-a-provider)):

```java
JRag rag = JRag.builder()
    .embeddings(Llm4s.createDefaultEmbeddingClient().get())
    .llm(Llm4s.createDefaultClient().get())
    .build()
    .get();
```

The imports are `org.llm4s.javaapi.rag.*` for `JRag` and its types, and `org.llm4s.javaapi.*` for `Llm4s`,
`LlmResult` and the clients. Any `JEmbeddingClient` and `JLlmClient` will do: one from `Llm4s.createEmbeddingClient`,
from a [builder](java#configure-in-code), or a Scala client wrapped with `Llm4s.wrapClient`. The pipeline embeds every
chunk as a document and every question as a query, with the embedding client's model; keep using the same model for
a store, since vectors from two models cannot be compared.

`build()` sends no request and never throws. An embedding client or store left out, or a setting out of range, is a
failed result of kind `VALIDATION` that names the field.

## Ingest documents

`ingest(Path)` reads a file, or every file directly in a directory whose name ends in `.txt`, `.md`, `.pdf`, `.docx`,
`.json`, `.xml` or `.html`, and returns how many chunks it indexed. `ingestText` indexes text you already have, under an
id you choose. Both take metadata, attached to every chunk:

```java
int chunks = rag.ingest(docs).get();
chunks += rag.ingestText("Refunds are paid within 14 days.", "refund-policy", Map.of("team", "billing")).get();
```

A file's id is its name. Ingesting an id again replaces what was indexed under it, so re-ingest a document after it
changes. `documentCount()` and `chunkCount()` say what this pipeline has indexed.

## Ask a question

`ask` embeds the question, finds the chunks that match it best and asks the model to answer from them alone. The
answer is a `JRagAnswer`: its text, the question, and its `sources()`, best match first:

```java
JRagAnswer answer = rag.ask("How long do refunds take?").get();
System.out.println(answer.answer());
for (JRagSource source : answer.sources()) {
    System.out.println("  " + source.documentId() + " (" + source.score() + "): " + source.content());
}
```

A `JRagSource` has the chunk's `content()`, the `documentId()` it came from, its `score()` and its `metadata()`: what
you attached, plus `docId` and `chunkIndex`, and for a file its name as `source` and its absolute `path`. A score
compares the sources of one search, not of two. `usage()` is the tokens the model call used, when the provider reports
them. With nothing indexed, an answer has no sources and the model is asked anyway, so it says it cannot tell.

A question is answered from at most five chunks; set another number with the builder's `topK`, or for one question
with `ask(question, topK)`. The model is told to answer from the chunks alone and to say when they are not enough;
replace those instructions with the builder's `systemPrompt`.

## Search without a model

`search` returns the chunks `ask` would answer from, without calling a model, so it works on a pipeline built with no
chat client:

```java
List<JRagSource> sources = rag.search("refunds", 3).get();
```

The search is hybrid: a chunk's score fuses its vector similarity to the question with a keyword match, so a rare word
in the question (a product name, an error code) finds its chunk even when the embedding model does not know it.

## Choose a store

Chunks are kept in memory unless you say otherwise, and are lost when the pipeline is closed. `JRag.sqlite` keeps them
in SQLite files, which a later pipeline over the same file reads again:

```java
JRag rag = JRag.builder()
    .embeddings(embedder)
    .llm(client)
    .store(JRag.sqlite(dir.resolve("docs.db")))   // and docs-fts.db beside it
    .chunking(800, 150)
    .topK(5)
    .build()
    .get();
```

The vectors go in `docs.db` and the keyword index in `docs-fts.db`. `chunking(size, overlap)` splits documents into
chunks of about `size` characters, each repeating the last `overlap` characters of the one before; chunks end at a
sentence where they can, and a chunk shorter than 100 characters is merged into its neighbour. The defaults are 800
and 150. Postgres and pgvector, reranking and GraphRAG are available from Scala (see [Vector store](vector-store))
but not yet from Java.

## Close it

`JRag` is `AutoCloseable`: closing it releases its store. It does not close the clients it was built with, which may
serve other pipelines; close those yourself.

```java
try (JRag rag = JRag.builder().embeddings(embedder).llm(client).build().get()) {
    rag.ingest(docs).get();
    System.out.println(rag.ask("How long do refunds take?").get().answer());
}
```

Different documents may be ingested from several threads at once; the same document id from two threads at once is
not supported.

## Handling a failure

Every call blocks the calling thread and never throws. A failure is a failed `LlmResult` whose `getError().getKind()`
says what went wrong, as for the [Java facade](java#handling-a-failure):

```java
LlmResult<JRagAnswer> result = rag.ask("How long do refunds take?");
if (result.isFailure()) {
    LlmException error = result.getError();
    switch (error.getKind()) {
        case VALIDATION -> System.err.println("Fix the call: " + error.getMessage());
        case AUTHENTICATION, CONFIGURATION -> System.err.println("Fix the setup: " + error.getMessage());
        case RATE_LIMIT -> System.err.println("Try again later");
        default -> System.err.println("Failed: " + error.getMessage());
    }
}
```

- `VALIDATION`: a `null` or blank argument, a `topK` below 1, metadata with a `null` key or value, or a path that does
  not exist.
- `CONFIGURATION`: `ask` on a pipeline built without a chat client.
- A failure of the embedding provider or the model has that failure's kind: `AUTHENTICATION`, `RATE_LIMIT`,
  `SERVICE`, `NETWORK`, `TIMEOUT`. A file that cannot be read is `OTHER`.
- Interrupting the blocked thread is `CANCELLED`, with the thread's interrupt flag still set.

## Where next

- [Java](java): the clients a pipeline is built from, and how to read a result.
- [Vector store](vector-store) and [RAG query transformers](rag-query-transformers): the pipeline in Scala, with every
  store and option.
- The sample `org.llm4s.samples.rag.JavaRagExample` in `modules/samples`.
