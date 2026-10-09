# llm4s-anthropic

The Anthropic Claude chat provider for llm4s, built on the Anthropic Java SDK. Frozen at 1.0 (see
[`docs/reference/v1-scope.md`](../../docs/reference/v1-scope.md)).

## Install

```scala
libraryDependencies += "org.llm4s" %% "llm4s-anthropic" % "<version>"
```

Brings in `llm4s-core` and the Anthropic Java SDK (`com.anthropic`); core itself depends on no vendor
SDK. The provider registers itself through `META-INF/services`. Which release carries which module
is in the [installation guide](../../docs/getting-started/installation.md); this README describes
`main`.

## Providers

| Kind | Id | Default base URL | Credentials |
|---|---|---|---|
| Chat | `anthropic` | `https://api.anthropic.com` (override with `baseUrl`) | an API key |

## Configuration

A section needs only `provider` and `model` when `ANTHROPIC_API_KEY` is set. The key is the
section's own `apiKey`, else `llm4s.credentials.anthropic.apiKey`, which this module's
`reference.conf` binds to `ANTHROPIC_API_KEY`:

```hocon
llm4s.providers {
  provider = "anthropic-main"      # the default section

  anthropic-main {
    provider = "anthropic"
    model    = "claude-sonnet-4-20250514"
  }
}
```

A section for a second account sets its own key, which wins over the shared one:

```hocon
llm4s.providers.anthropic-team {
  provider = "anthropic"
  model    = "claude-sonnet-4-20250514"
  apiKey   = ${?ANTHROPIC_TEAM_API_KEY}
}
```

Every section also accepts an optional `timeouts { request = 3m, stream = 15m }` block; without it the
client keeps the Anthropic SDK's own timeouts, which bound the whole call, retries included (see
[Timeouts](../../docs/getting-started/configuration.md#timeouts)).

## Minimal example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

val reply = for {
  providerConfig  <- Llm4sConfig.provider("anthropic-main")
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client     <- LLMConnect.getClient(providerConfig)
  completion <- client.complete(Conversation(Seq(UserMessage("Say hello in one word."))))
} yield completion.content
```

## What it supports

- **Streaming and tool calling.** Tool schemas are converted to Anthropic's tool format with the
  OpenAI-only `strict` and `additionalProperties` fields removed.
- **Structured output is a prompt instruction, not an API guarantee.** For `ResponseFormat.Json` or
  `JsonSchema` the client appends "respond with valid JSON only" (and the schema) to the system
  prompt; nothing in the request enforces it, so validate what comes back.
- **Extended thinking:** `CompletionOptions.reasoning` adds a `thinking` block with a token budget
  clamped to `[1024, maxTokens - 1]` (`maxTokens` defaults to 2048, since the API requires it). The
  reply's thinking and redacted-thinking blocks, with their signatures, come back on the message's
  `thinking` and are replayed on later turns.
- **A system prompt is always sent.** If the conversation has no system message the client adds
  `You are Claude, a helpful AI assistant.`
- **Model listing:** the provider can list the models your key can use.

## Tests

```bash
sbt anthropic/test   # unit tests, incl. Llm4sAnthropicModuleSpec (discovery and config round trip)
sbt testSmoke        # the @Cloud tier in modules/it, incl. AnthropicSmokeSpec: needs a real key
```

## See also

- [Writing a provider](../../docs/guide/writing-a-provider.md) - the provider SPI this module implements
- [Providers guide](../../docs/guide/providers.md) and [configuration](../../docs/getting-started/configuration.md)
