# llm4s-gemini

The Google Gemini API and Vertex AI chat clients for llm4s. Frozen at 1.0 (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-gemini" % "<version>"
```

Depends on `llm4s-core` only: no Google SDK (Vertex AI's OAuth2 is hand-rolled in
`VertexAIAuthProvider`). The providers register themselves through `META-INF/services`. Which
release carries which module is in the [installation guide](../../docs/getting-started/installation.md);
this README describes `main`.

## Providers

| Id | Alias | Endpoint | Credentials |
|---|---|---|---|
| `gemini` | `google` | `https://generativelanguage.googleapis.com/v1beta` (override with `baseUrl`) | an API key |
| `vertexai` | `vertex` | Vertex AI, by `project` and `location` | OAuth2, **no API key** |

Both call Gemini models in the same JSON format. Vertex AI ships here because it differs only in
endpoint and authentication, not in dependencies.

## Configuration

**Gemini API.** A section needs only `provider` and `model` when a key is in the environment. The
key is the section's own `apiKey`, else `llm4s.credentials.gemini.apiKey`, which this module's
`reference.conf` binds to `GOOGLE_API_KEY`, else `GEMINI_API_KEY` (`GOOGLE_API_KEY` wins when both
are set):

```hocon
llm4s.providers.gemini-main {
  provider = "gemini"          # "google" is accepted too
  model    = "gemini-2.0-flash"
}
```

**Vertex AI.** `project` (the GCP project id) is required; `location` defaults to `us-central1`.
`apiKey` is optional and is a **path to a service-account credential file**; without it, Application
Default Credentials are used:

```hocon
llm4s.providers.vertexai-main {
  provider = "vertexai"        # "vertex" is accepted too
  model    = "gemini-2.0-flash"
  project  = "my-gcp-project"
  location = "us-central1"
}
```

The earlier keys `endpoint` and `organization` are still read as deprecated aliases of `project` and
`location`, with a warning; use the new names.

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("gemini-main")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

## What it supports

- **Streaming and tool calling** on both providers.
- **Structured output:** `ResponseFormat.Json` sets `responseMimeType = "application/json"`;
  `ResponseFormat.JsonSchema` also sets `responseSchema`.
- **Model listing:** the `gemini` provider can list models; `vertexai` cannot.

## Tests

```bash
sbt gemini/test      # unit tests, incl. Llm4sGeminiModuleSpec (discovery and config round trip)
sbt testSmoke        # the @Cloud tier in modules/it, incl. GeminiSmokeSpec: needs a real key
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../docs/guide/providers.md) and [configuration](../../docs/getting-started/configuration.md)
