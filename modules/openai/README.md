# llm4s-openai

The OpenAI, Azure OpenAI and Requesty chat providers, plus OpenAI embeddings, for llm4s. The three
chat providers share one client built on OpenAI's `openai-java` SDK. Frozen at 1.0 (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai" % "<version>"
```

Brings in `llm4s-core`, `llm4s-openai-compatible` (which holds `OpenAIConfig`) and the `openai-java`
SDK. If you only need an SDK-free client for OpenAI-style endpoints, use
[`llm4s-openai-compatible`](../openai-compatible/README.md) instead. The providers register
themselves through `META-INF/services`. Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

## Providers

| Kind | Id | Default base URL | Key (shared `llm4s.credentials.<id>.apiKey`) | Section keys |
|---|---|---|---|---|
| Chat | `openai` | `https://api.openai.com/v1` | `OPENAI_API_KEY` | `baseUrl`, optional `organization` |
| Chat | `azure` | none: you give an `endpoint` | `AZURE_OPENAI_API_KEY` | `endpoint` (required), `apiVersion` (default `V2025_01_01_PREVIEW`) |
| Chat | `requesty` | `https://router.requesty.ai/v1` | `REQUESTY_API_KEY` | `baseUrl`, optional `organization` |
| Embeddings | `openai` | `https://api.openai.com/v1` | `OPENAI_API_KEY` | see below |

## Configuration

A chat section needs only `provider` and `model` when the vendor's variable is set. The key is the
section's own `apiKey`, else the shared `llm4s.credentials.<id>.apiKey` this module's
`reference.conf` binds to the variable above:

```hocon
llm4s.providers {
  provider = "openai-main"         # the default section

  openai-main {
    provider = "openai"
    model    = "gpt-4o-mini"
  }

  azure-main {
    provider   = "azure"
    model      = "gpt-4o"
    endpoint   = ${?AZURE_API_BASE}
    apiVersion = ${?AZURE_API_VERSION}
  }
}
```

A section for a second account sets its own `apiKey`, which wins over the shared one.

**Embeddings** read `llm4s.embeddings.openai`, bound by this module to the environment:

| Key | Environment variable |
|---|---|
| `llm4s.embeddings.openai.baseUrl` | `OPENAI_EMBEDDING_BASE_URL` |
| `llm4s.embeddings.openai.model` | `OPENAI_EMBEDDING_MODEL` |

The key is `llm4s.credentials.openai.apiKey` unless that block sets an `apiKey` of its own. Select
OpenAI with `llm4s.embeddings.model` (environment variable `EMBEDDING_MODEL`, in `provider/model`
form, e.g. `openai/text-embedding-3-small`).

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("openai-main")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

## What it supports

- **Streaming and tool calling**, through the `openai-java` SDK.
- **Structured output:** `ResponseFormat.Json` maps to the SDK's `json_object` response format and
  `ResponseFormat.JsonSchema` to `json_schema`.
- **OpenAI model rules:** the o-series and `max_completion_tokens` handling live in this module
  (`OpenAIModelRules`), not in core.
- **Embeddings ignore `EmbeddingRequest.purpose`:** OpenAI's embedding models embed a query and a
  document alike, so the request body carries no `input_type` or `task` for either.
- **Model listing:** the `openai` and `requesty` providers can list models; `azure` cannot.

## Tests

```bash
sbt openai/test      # unit tests, incl. Llm4sOpenAIModuleSpec (discovery and config round trip)
sbt testSmoke        # the @Cloud tier in modules/it, incl. OpenAISmokeSpec: needs a real key
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../docs/guide/providers.md) and [configuration](../../docs/getting-started/configuration.md)
