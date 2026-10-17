# llm4s-jina

Jina AI embeddings for llm4s (`org.llm4s:llm4s-jina_3`).

It registers one embedding provider, `jina`, and supplies embeddings only: there is no chat client
in this module.

## Configuration

| Environment variable | Binds to | Default |
|---|---|---|
| `JINA_API_KEY` | `llm4s.credentials.jina.apiKey` | none (required) |
| `JINA_EMBEDDING_BASE_URL` | `llm4s.embeddings.jina.baseUrl` | `https://api.jina.ai/v1` |
| `JINA_EMBEDDING_MODEL` | `llm4s.embeddings.jina.model` | none |

```hocon
llm4s.embeddings.model = "jina/jina-embeddings-v3"

llm4s.credentials.jina.apiKey = ${?JINA_API_KEY}

# optional: a key for embeddings only; it wins over the shared one above
# llm4s.embeddings.jina.apiKey = ${?JINA_EMBEDDINGS_API_KEY}
```

`EMBEDDING_MODEL=jina/jina-embeddings-v3` selects the provider and model from the environment. See
[Configuration](../../../docs/getting-started/configuration.md) for the API-key lookup order and the
other embedding providers.

## Queries and documents

Jina embeds queries and documents differently. The provider maps a request's `InputPurpose` to a `JinaTask`, sent as `task`: `InputPurpose.Document` becomes `RetrievalPassage` (`retrieval.passage`) and `InputPurpose.Query` becomes `RetrievalQuery` (`retrieval.query`).

```scala
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, InputPurpose }
import org.llm4s.llmconnect.provider.JinaEmbeddingProvider

val provider = JinaEmbeddingProvider.fromConfig(
  EmbeddingProviderConfig(baseUrl = "https://api.jina.ai/v1", model = "jina-embeddings-v3", apiKey = sys.env("JINA_API_KEY"))
)
val model = EmbeddingModelConfig("jina-embeddings-v3", 1024)

val docs  = provider.embed(EmbeddingRequest(Seq("Scala is a JVM language."), model))                  // InputPurpose.Document
val query = provider.embed(EmbeddingRequest(Seq("what runs on the JVM?"), model, InputPurpose.Query)) // InputPurpose.Query
```

`InputPurpose.Document` is the default. For a task the purpose cannot express (`TextMatching`, `Classification`, `Separation`), pass a `JinaTask` to `JinaEmbeddingProvider.fromConfig(cfg, task)`; an explicit task wins over the purpose of every request.

## Tests

```bash
sbt jina/test
```
