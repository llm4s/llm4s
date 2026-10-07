# llm4s-openai-compatible

One SDK-free chat client for every endpoint that speaks OpenAI's `/chat/completions`: DeepSeek,
Z.ai, OpenRouter, Mistral, Cohere's compatibility API, and any server you point the generic
`openai-compatible` provider at (Groq, Together, vLLM, LM Studio, ...). Frozen at 1.0, except the
Mistral and Cohere dialects, which are Beta (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % "<version>"
```

Depends on `llm4s-core` only: **no vendor SDK**. It also holds `OpenAIConfig`; `llm4s-openai`
depends on this module for it, never the other way round. The providers register themselves through
`META-INF/services`. Which release carries which module is in the
[installation guide](../../docs/getting-started/installation.md); this README describes `main`.

## Providers

All six are chat providers on `OpenAICompatibleClient`; each differs only by its *dialect* (headers,
role names, reasoning and tool-call decoding).

| Id | Default base URL | Key (shared `llm4s.credentials.<id>.apiKey`) |
|---|---|---|
| `openai-compatible` | none: `baseUrl` is required | none (a section may set its own `apiKey`) |
| `deepseek` | `https://api.deepseek.com` | `DEEPSEEK_API_KEY` |
| `zai` | `https://api.z.ai/api/paas/v4` | `ZAI_API_KEY` |
| `openrouter` | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| `mistral` (Beta) | `https://api.mistral.ai` | `MISTRAL_API_KEY` |
| `cohere` (Beta) | `https://api.cohere.ai/compatibility/v1` | `COHERE_API_KEY` |

## Configuration

A vendor section needs only `provider` and `model` when its variable is set. The key is the
section's own `apiKey`, else the shared `llm4s.credentials.<id>.apiKey` that this module's
`reference.conf` binds to the variable above. `openrouter` also accepts an optional `organization`.

```hocon
llm4s.providers {
  provider = "deepseek-main"       # the default section

  deepseek-main {
    provider = "deepseek"
    model    = "deepseek-chat"
  }
}
```

**The generic provider** needs a `baseUrl` and a `model`; `apiKey` and `headers` are optional (local
servers need no key). It also accepts four provider-specific keys:

| Key | Meaning | Default |
|---|---|---|
| `contextWindow` | the model's window in tokens, a positive whole number | the model registry's, when at least the built-in default, else the default |
| `reserveCompletion` | tokens held back for the reply, a whole number below `contextWindow` | a quarter of the window, up to a cap |
| `registryProvider` | the model-registry provider (e.g. `groq`, `together_ai`, `fireworks_ai`, `xai`, `perplexity`) whose entry gives the window | inferred from the `baseUrl` host for Groq, Together, Fireworks, xAI and Perplexity |
| `streamUsage` | whether a streaming request asks for token usage (`stream_options.include_usage`) | `true`; set `false` for a server that rejects it |

```hocon
llm4s.providers {
  groq-main {
    provider      = "openai-compatible"
    baseUrl       = "https://api.groq.com/openai/v1"
    model         = "openai/gpt-oss-120b"
    apiKey        = ${?GROQ_API_KEY}
    contextWindow = 131072
  }

  local-vllm {
    provider = "openai-compatible"
    baseUrl  = "http://localhost:8000/v1"
    model    = "Qwen/Qwen2.5-7B-Instruct"
  }
}
```

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("deepseek-main")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

## What it supports

- **Streaming and tool calling.** Streamed tool calls arrive split across deltas and are reassembled
  by index; the client reports streamed token usage.
- **Structured output:** the standard dialect sends OpenAI's shapes: `{"type": "json_object"}`, and
  `{"type": "json_schema", "json_schema": {name, strict, schema}}` for a schema. Cohere's dialect
  sends `json_object` with the schema alongside. A server that rejects `json_schema` will reject the
  request.
- **Model listing:** the generic provider, `deepseek`, `openrouter` and `mistral` can list models;
  `zai` and `cohere` cannot.

**A new OpenAI-compatible provider is usually just config** (a generic section) or a dialect and a
descriptor in this module, never another copy of the client; see
[Writing a provider](../../docs/guide/writing-a-provider.md).

## Tests

```bash
sbt openaiCompatible/test   # unit tests, incl. Llm4sOpenAICompatibleModuleSpec (discovery and config round trip)
sbt testSmoke               # the @Cloud tier in modules/it: DeepSeek, OpenRouter, Mistral, Z.ai and
                            # Cohere smoke specs, each needs its own real key
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../docs/guide/providers.md) and [configuration](../../docs/getting-started/configuration.md)
- [`llm4s-openai`](../openai/README.md) - OpenAI, Azure and Requesty on the `openai-java` SDK
