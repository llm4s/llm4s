# llm4s-cohere

Cohere embeddings for llm4s (`org.llm4s:llm4s-cohere_3`).

It registers one embedding provider, `cohere`, and supplies embeddings only: there is no chat client
in this module. Cohere's chat client is a different module: a dialect in `llm4s-openai-compatible`. The same `COHERE_API_KEY` serves both, and the reranker in `llm4s-rag`.

## Configuration

| Environment variable | Binds to | Default |
|---|---|---|
| `COHERE_API_KEY` | `llm4s.credentials.cohere.apiKey` | none (required) |
| `COHERE_EMBEDDING_BASE_URL` | `llm4s.embeddings.cohere.baseUrl` | `https://api.cohere.com` |
| `COHERE_EMBEDDING_MODEL` | `llm4s.embeddings.cohere.model` | none |

```hocon
llm4s.embeddings.model = "cohere/embed-english-v3.0"

llm4s.credentials.cohere.apiKey = ${?COHERE_API_KEY}

# optional: a key for embeddings only; it wins over the shared one above
# llm4s.embeddings.cohere.apiKey = ${?COHERE_EMBEDDINGS_API_KEY}
```

`EMBEDDING_MODEL=cohere/embed-english-v3.0` selects the provider and model from the environment. See
[Configuration](../../../docs/getting-started/configuration.md) for the API-key lookup order and the
other embedding providers.

## Queries and documents

Cohere embeds queries and documents differently. The provider maps a request's `InputPurpose` to a `CohereInputType`, sent as `input_type`: `InputPurpose.Document` becomes `SearchDocument` (`search_document`) and `InputPurpose.Query` becomes `SearchQuery` (`search_query`).

```scala
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.llm4s.llmconnect.provider.CohereEmbeddingProvider

val provider = CohereEmbeddingProvider.fromConfig(
  EmbeddingProviderConfig(baseUrl = "https://api.cohere.com", model = "embed-english-v3.0", apiKey = sys.env("COHERE_API_KEY"))
)
val model = EmbeddingModelConfig("embed-english-v3.0", 1024)

val docs  = provider.embed(EmbeddingRequest(Seq("Scala is a JVM language."), model))                  // InputPurpose.Document
val query = provider.embed(EmbeddingRequest(Seq("what runs on the JVM?"), model, InputPurpose.Query)) // InputPurpose.Query
```

`InputPurpose.Document` is the default. For an input type the purpose cannot express (`Classification`, `Clustering`), pass a `CohereInputType` to `CohereEmbeddingProvider.fromConfig(cfg, inputType)`; an explicit input type wins over the purpose.

## Tests

```bash
sbt cohere/test
```
