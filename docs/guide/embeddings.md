---
layout: page
title: Embeddings
parent: User Guide
nav_order: 21
---

# Embeddings
{: .no_toc }

Turn text into vectors: pick a provider and a model, create an `EmbeddingClient`, and embed documents and queries.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## 1. What embeddings are for

An embedding model maps a text to a vector of numbers so that texts with a similar meaning end up close together.
LLM4S uses them for [RAG](vector-store), for [agent memory](agents/memory) and for the
[embedding cache](caching). Every one of them goes through the same type, `EmbeddingClient` in `llm4s-core`, so
what you learn here applies to all three.

## 2. Choosing a provider and a model

A provider is a module you add as a dependency, and a model is one of the provider's models. The module's
`reference.conf` binds the environment variables for its `llm4s.embeddings.<provider>` block and, where it needs
one, the vendor's key.

| Provider id | Artifact | What it needs | Block variables (`llm4s.embeddings.<id>`) |
|---|---|---|---|
| `openai` | `llm4s-openai` | `OPENAI_API_KEY` | `OPENAI_EMBEDDING_BASE_URL`, `OPENAI_EMBEDDING_MODEL` |
| `ollama` | `llm4s-ollama` | a running Ollama, no key | `OLLAMA_EMBEDDING_BASE_URL`, `OLLAMA_EMBEDDING_MODEL` |
| `voyage` | `llm4s-voyage` | `VOYAGE_API_KEY` | `VOYAGE_EMBEDDING_BASE_URL`, `VOYAGE_EMBEDDING_MODEL` |
| `jina` | `llm4s-jina` | `JINA_API_KEY` | `JINA_EMBEDDING_BASE_URL`, `JINA_EMBEDDING_MODEL` |
| `cohere` | `llm4s-cohere` | `COHERE_API_KEY` | `COHERE_EMBEDDING_BASE_URL`, `COHERE_EMBEDDING_MODEL` |

Some starting points: a hosted general-purpose model (`openai/text-embedding-3-small`), a local one with no key
and no data leaving the machine (`ollama/nomic-embed-text`), or a provider whose models distinguish queries from
documents (`voyage`, `jina` and `cohere`, see [section 5](#5-documents-and-queries)). Without the provider's
module on the classpath its id fails with an error naming the embedding providers that are registered.

## 3. Configuring one

Embeddings are configured apart from chat providers. Select the provider and model with
`llm4s.embeddings.model`, in `provider/model` form, which `llm4s-core` binds to `EMBEDDING_MODEL`:

```bash
export EMBEDDING_MODEL=openai/text-embedding-3-small
export OPENAI_API_KEY=sk-...
```

The key, a base URL and a second account's key follow the same rules as for chat providers, described in
[Configuration](../getting-started/configuration#embeddings-configuration); it is not repeated here.

## 4. Embedding some texts

`Llm4sConfig.embeddings()` reads those settings and returns the provider id with its
`EmbeddingProviderConfig`; `EmbeddingClient.from` builds the client from them. An `EmbeddingRequest` carries the
texts and an `EmbeddingModelConfig` naming the model and its dimensions, and the `EmbeddingResponse` holds one
vector per text, in order:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def embedTexts(texts: Seq[String]): Result[EmbeddingResponse] =
  for {
    settings  <- Llm4sConfig.embeddings()
    providerId = settings._1
    config     = settings._2
    registry  <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    dimensions <- ModelDimensionRegistry.getDimension(providerId, config.model)
    client     <- EmbeddingClient.from(providerId, config)
    response   <- client.embed(EmbeddingRequest(texts, EmbeddingModelConfig(config.model, dimensions)))
  } yield response
```

Reading the response:

```scala
import org.llm4s.llmconnect.model.EmbeddingResponse

def describe(response: EmbeddingResponse): String = {
  val vectors = response.embeddings // one Seq[Double] per input text
  val size    = vectors.headOption.map(_.size).getOrElse(0)
  val tokens  = response.usage.map(_.totalTokens.toString).getOrElse("not reported")
  s"${vectors.size} vectors of $size dimensions, tokens: $tokens"
}
```

`usage` and `dim` are optional: a provider that does not report them leaves them `None`.
[`EmbeddingExample`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/embeddingsupport/EmbeddingExample.scala)
runs this against files and prints the vectors.

## 5. Documents and queries

Some embedding models are trained to embed a search query differently from the document it should find. An
`EmbeddingRequest` says which side a text is on with its `purpose`, an `InputPurpose`: `Document` (the default)
or `Query`. Voyage, Jina and Cohere map it onto their own parameter; OpenAI and Ollama embed both alike and
ignore it. The table of what each provider sends is in
[Queries and documents](../getting-started/configuration#queries-and-documents).

Index your chunks as documents and embed the question as a query:

```scala
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, InputPurpose }
import org.llm4s.types.Result

def indexAndSearch(
  client: EmbeddingClient,
  model: EmbeddingModelConfig,
  chunks: Seq[String],
  question: String
): Result[(EmbeddingResponse, EmbeddingResponse)] =
  for {
    indexed <- client.embed(EmbeddingRequest(chunks, model))
    queried <- client.embed(EmbeddingRequest(Seq(question), model, InputPurpose.Query))
  } yield (indexed, queried)
```

A RAG query should be embedded as a query because the vectors in the store were made as documents. A provider
that distinguishes the two aligns them for you only if each side says what it is; a query sent as a document
still gives a vector, and it quietly retrieves worse. The RAG pipeline in `llm4s-rag` already does this.

## 6. Dimensions

Every model produces vectors of one size, and a store is created for one size. `ModelDimensionRegistry`
answers which, from the dimensions each provider declares for its models:

```scala
import org.llm4s.llmconnect.config.ModelDimensionRegistry
import org.llm4s.types.Result

def dimensionsOf(provider: String, model: String): Result[Int] =
  ModelDimensionRegistry.getDimension(provider, model)
```

For a provider or model it does not know, the result is a `Left(ConfigurationError)`, not a guess. When a store
already holds vectors of another size, because the model changed since they were written, the store refuses:
the SQLite vector store, for one, answers a search with a `ProcessingError` that reads
`Dimension mismatch: query vector has N dimensions, but stored vectors have M dimensions`. Embedding with a new
model means re-embedding what is stored. Check the sizes up front rather than at the first search:

```scala
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.EmbeddingResponse
import org.llm4s.types.Result

def checkDimensions(response: EmbeddingResponse, storeDimensions: Int): Result[EmbeddingResponse] =
  response.embeddings.headOption.map(_.size) match {
    case Some(size) if size != storeDimensions =>
      Left(ValidationError("dimensions", s"the model returned $size dimensions, the store expects $storeDimensions"))
    case _ => Right(response)
  }
```

## 7. Errors

Nothing is thrown: `embed` and `EmbeddingClient.from` return a `Result`, so a failure is a `Left` holding an
`LLMError` (see [Error Handling](error-handling)). A failure the provider reports, such as a rejected key or a
refused request, is an `EmbeddingError`, with an optional `code` (typically the HTTP status), a `message` and
the `provider` that produced it. A configuration problem, such as an unset `EMBEDDING_MODEL` or a provider whose
module is missing, is a `ConfigurationError` from `Llm4sConfig.embeddings()` instead (and an unregistered
provider id given to `EmbeddingClient.from` is reported as an `EmbeddingError` whose provider is `config`).

```scala
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.EmbeddingError

def explain(error: LLMError): String =
  error match {
    case EmbeddingError(Some("401"), _, provider) => s"$provider rejected the API key"
    case EmbeddingError(code, message, provider)  => s"$provider failed (${code.getOrElse("no code")}): $message"
    case other                                    => other.formatted
  }
```

Because the errors travel in `Result`, a chain of steps stops at the first one that fails, as in the
for-comprehensions above.

## Next steps

- [Vector Store](vector-store): store the vectors and search them
- [Configuration](../getting-started/configuration#embeddings-configuration): keys, base URLs and the other settings of an embeddings block
- [Writing a Provider](writing-a-provider): add an embedding provider of your own
