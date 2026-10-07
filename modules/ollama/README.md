# llm4s-ollama

The [Ollama](https://ollama.com) chat client and embedding provider for llm4s, for models you run
yourself. Frozen at 1.0 (see [`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ollama" % "<version>"
```

Depends on `llm4s-core` only (no vendor SDK). The provider registers itself through
`META-INF/services`, so adding the dependency is enough. Which release carries which module is in
the [installation guide](../../docs/getting-started/installation.md); this README describes `main`.

## Providers

| Kind | Id | Needs | Default |
|---|---|---|---|
| Chat | `ollama` | `baseUrl` and `model`; **no API key** | none: `baseUrl` is required (e.g. `http://localhost:11434`) |
| Embeddings | `ollama` | nothing | base URL `http://localhost:11434`, model `nomic-embed-text` |

## Configuration

Chat providers are named sections of your `application.conf`. A section needs no credentials:

```hocon
llm4s.providers {
  provider = "ollama-local"        # the default section

  ollama-local {
    provider = "ollama"
    model    = "llama3.2"
    baseUrl  = "http://localhost:11434"
  }
}
```

Embeddings read `llm4s.embeddings.ollama`, which this module's `reference.conf` binds to the
environment:

| Key | Environment variable |
|---|---|
| `llm4s.embeddings.ollama.baseUrl` | `OLLAMA_EMBEDDING_BASE_URL` |
| `llm4s.embeddings.ollama.model` | `OLLAMA_EMBEDDING_MODEL` |

Pick Ollama for embeddings with `llm4s.embeddings.model` (environment variable
`EMBEDDING_MODEL`, in `provider/model` form, e.g. `ollama/nomic-embed-text`).

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("ollama-local")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

Pull the model first (`ollama pull llama3.2`); see the
[Ollama quickstart](../../docs/getting-started/ollama-quickstart.md).

## What it supports

- **Streaming:** `streamComplete` streams the server's newline-delimited JSON.
- **Tool calling:** tools go to Ollama's native `tools` field. A model without the tools capability
  makes Ollama answer HTTP 400 (`... does not support tools`); the client reports that as a
  validation error naming the model instead of retrying without the tools.
- **Structured output:** `ResponseFormat.Json` sends `"format": "json"` and
  `ResponseFormat.JsonSchema` sends the schema as `format` (structured outputs, which need
  Ollama 0.5 or later; `name` and `strict` have no Ollama equivalent and are ignored).
- **Embeddings ignore `EmbeddingRequest.purpose`:** Ollama's embedding models embed a query and a
  document alike, so the request carries no `input_type` or `task` for either.
- **Model listing:** the provider can list the models your server has pulled.

## Tests

```bash
sbt ollama/test          # unit tests, incl. Llm4sOllamaModuleSpec (discovery and config round trip)
sbt testOllama           # the @Ollama tier in modules/it: needs a running Ollama server
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../docs/guide/providers.md) and [configuration](../../docs/getting-started/configuration.md)
