# llm4s-voyage

Voyage AI embeddings for llm4s (`org.llm4s:llm4s-voyage_3`).

It registers one embedding provider, `voyage` (alias `voyageai`), and supplies embeddings only: there is no chat client
in this module.

## Configuration

| Environment variable | Binds to | Default |
|---|---|---|
| `VOYAGE_API_KEY` | `llm4s.credentials.voyage.apiKey` | none (required) |
| `VOYAGE_EMBEDDING_BASE_URL` | `llm4s.embeddings.voyage.baseUrl` | `https://api.voyageai.com/v1` |
| `VOYAGE_EMBEDDING_MODEL` | `llm4s.embeddings.voyage.model` | none |

```hocon
llm4s.embeddings.model = "voyage/voyage-3"

llm4s.credentials.voyage.apiKey = ${?VOYAGE_API_KEY}

# optional: a key for embeddings only; it wins over the shared one above
# llm4s.embeddings.voyage.apiKey = ${?VOYAGE_EMBEDDINGS_API_KEY}
```

`EMBEDDING_MODEL=voyage/voyage-3` selects the provider and model from the environment. See
[Configuration](../../../docs/getting-started/configuration.md) for the API-key lookup order and the
other embedding providers.

## Queries and documents

Voyage's models embed a query and a document differently. The provider maps a request's `InputPurpose` to Voyage's `input_type`: `InputPurpose.Document` is sent as `document` and `InputPurpose.Query` as `query`.

```scala
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.llm4s.llmconnect.provider.VoyageAIEmbeddingProvider

val provider = VoyageAIEmbeddingProvider.fromConfig(
  EmbeddingProviderConfig(baseUrl = "https://api.voyageai.com/v1", model = "voyage-3", apiKey = sys.env("VOYAGE_API_KEY"))
)
val model = EmbeddingModelConfig("voyage-3", 1024)

val docs  = provider.embed(EmbeddingRequest(Seq("Scala is a JVM language."), model))                  // InputPurpose.Document
val query = provider.embed(EmbeddingRequest(Seq("what runs on the JVM?"), model, InputPurpose.Query)) // InputPurpose.Query
```

`InputPurpose.Document` is the default. Voyage has no separate typed setting: the purpose is the only control, and it is mapped for every request.

## Tests

```bash
sbt voyage/test
```
