---
layout: page
title: Configuration in Code
description: "Configure LLM4S from the config your app already has: a Typesafe Config (Play, Pekko, tests), plain values, ciris or zio-config, no application.conf."
search_title: "Configure LLM4S from ciris, zio-config or plain values"
parent: Getting Started
nav_order: 17
---

# Configuration in Code
{: .no_toc }

Use the configuration your application already has instead of llm4s's `application.conf`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What a client needs

`LLMConnect.getClient(config)` needs three things:

- a **`ProviderConfig`**: the provider's model, key, base URL and so on;
- a **`ModelRegistryService`** in scope (`using`): the model catalogue that sizes the context window;
- a **`ProviderRegistry`** in scope: which providers exist. With none given it is
  `ProviderRegistry.default`, every provider module found on the classpath.

`Llm4sConfig` reads all of these from `application.conf`
([Configuration](configuration.md)). Nothing requires it: the same provider can be set up
in any of the four ways below, and they produce the same client.

## 1. `application.conf`

The default. The section and the code that loads it:

```hocon
llm4s.providers {
  provider = "openai-main"
  openai-main { provider = "openai", model = "gpt-4o-mini" }   # the key: OPENAI_API_KEY
}
```

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

val client: Result[LLMClient] = for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  config <- Llm4sConfig.defaultProvider()
  client <- LLMConnect.getClient(config)
} yield client
```

## 2. A `Config` your framework already loaded

Play hands you `Configuration.underlying`, Pekko `ActorSystem.settings.config`, and a test can
parse one per case. `Llm4sConfig.from(config)` reads llm4s's settings from it, with the same
methods as `Llm4sConfig`, and two of them in one JVM never see each other:

```scala
import com.typesafe.config.{ Config, ConfigFactory }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

// Play: configuration.underlying. Pekko: system.settings.config. A test: a string per case.
val appConfig: Config = ConfigFactory.parseString(
  """llm4s.providers {
    |  provider = "openai-main"
    |  openai-main { provider = "openai", model = "gpt-4o-mini", apiKey = "sk-tenant-a" }
    |}""".stripMargin
)

val client: Result[LLMClient] = for {
  settings <- Llm4sConfig.from(appConfig)
  registry <- settings.modelRegistryService()
  given ModelRegistryService = registry
  config <- settings.defaultProvider()
  client <- LLMConnect.getClient(config)
} yield client
```

System properties still override the given config, every module's `reference.conf` still sits
beneath it, and the shared `llm4s.credentials.<id>.apiKey` fallback applies, exactly as for
`application.conf`. See [Using a config your application already has](configuration.md#using-a-config-your-application-already-has).

## 3. Plain values

Every provider config has a `fromValues` that validates its values and returns a `Result`.
`ModelRegistryService.default()` loads the model catalogue bundled with llm4s, reading no
configuration file; with it given, `fromValues` and `getClient` need nothing else:

```scala
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result

def openAiClient(model: String, apiKey: String, baseUrl: String): Result[LLMClient] =
  for {
    registry <- ModelRegistryService.default()
    given ModelRegistryService = registry
    config <- OpenAIConfig.fromValues(model, Secret(apiKey), baseUrl)
    client <- LLMConnect.getClient(config)
  } yield client
```

A `ContextWindowResolver` given in scope is used in place of the registry's, for code that
already builds one.

### Every provider's constructor

The parameters common to the providers come first and in the same order: **model, then API key,
then base URL**. Whatever is optional (has a default) comes after what is required.

| Provider id | Module | In-code constructor |
|---|---|---|
| `openai` | `llm4s-openai` | `OpenAIConfig.fromValues(model, apiKey, baseUrl, organization = None)` |
| `azure` | `llm4s-openai` | `AzureConfig.fromValues(deployment, apiKey, endpoint, apiVersion = DEFAULT_API_VERSION)`; keyless: `AzureConfig.fromTokenProvider(deployment, tokenProvider, endpoint)` |
| `requesty` | `llm4s-openai` | `OpenAIConfig.fromValues(model, apiKey, RequestyProvider.DEFAULT_BASE_URL, providerId = Some(ProviderId("requesty")))` |
| `anthropic` | `llm4s-anthropic` | `AnthropicConfig.fromValues(model, apiKey, baseUrl)` |
| `gemini` | `llm4s-gemini` | `GeminiConfig.fromValues(model, apiKey, baseUrl)` |
| `vertexai` | `llm4s-gemini` | `VertexAIConfig.fromValues(model, projectId, location = DEFAULT_LOCATION)` |
| `ollama` | `llm4s-ollama` | `OllamaConfig.fromValues(model, baseUrl)` |
| `openai-compatible` | `llm4s-openai-compatible` | `OpenAICompatibleConfig.fromValues(model, baseUrl, apiKey = None)`: the key is optional, so it follows `baseUrl` |
| `deepseek` | `llm4s-openai-compatible` | `DeepSeekConfig.fromValues(model, apiKey, baseUrl)` |
| `zai` | `llm4s-openai-compatible` | `ZaiConfig.fromValues(model, apiKey, baseUrl)` |
| `openrouter` | `llm4s-openai-compatible` | `OpenAIConfig.fromValues(model, apiKey, OpenRouterProvider.DEFAULT_BASE_URL)` |
| `mistral` | `llm4s-openai-compatible` | `MistralConfig.fromValues(model, apiKey, baseUrl)` |
| `cohere` | `llm4s-openai-compatible` | `CohereConfig.fromValues(model, apiKey, baseUrl)` |
| `xai` | `llm4s-openai-compatible` | `XaiConfig.fromValues(model, apiKey, baseUrl)` |
| `bedrock` | `llm4s-bedrock` | `BedrockConfig.fromValues(model, region, credentials = None)`: no key; AWS credentials or the default chain |
| `watsonx` | `llm4s-watsonx` | `WatsonXConfig.fromValues(model, apiKey, projectId, spaceId = None, baseUrl = DEFAULT_BASE_URL)` |
| `perplexity` | `llm4s-perplexity` | `PerplexityConfig.fromValues(model, apiKey, baseUrl = DEFAULT_BASE_URL)` |

Each config class has a `DEFAULT_BASE_URL` where the vendor has one. Each module's
`<Name>InCodeConfigSpec` builds its clients this way, with no `application.conf`.

### Embeddings

An embedding provider takes one `EmbeddingProviderConfig` for every vendor (`openai`, `voyage`,
`jina`, `cohere`, `ollama`); name its arguments, since `baseUrl` and `model` are both strings:

```scala
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.Result

val embedder: Result[EmbeddingClient] = for {
  registry <- ModelRegistryService.default()
  given ModelRegistryService = registry
  client <- EmbeddingClient.from(
    "voyage",
    EmbeddingProviderConfig(baseUrl = "https://api.voyageai.com", model = "voyage-3", apiKey = Secret("pa-..."))
  )
} yield client
```

## 4. ciris

A Typelevel service that loads its configuration with [ciris](https://cir.is/) reads the values
there and hands them to `fromValues`. llm4s does not depend on ciris; add it to your own build.

```scala
import cats.effect.IO
import cats.syntax.all.*
import ciris.{ env, ConfigValue, Effect }
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret

final case class LlmSettings(model: String, apiKey: ciris.Secret[String], baseUrl: String)

val settings: ConfigValue[Effect, LlmSettings] = (
  env("OPENAI_MODEL").default("gpt-4o-mini"),
  env("OPENAI_API_KEY").secret,
  env("OPENAI_BASE_URL").default(OpenAIConfig.DEFAULT_BASE_URL)
).parMapN(LlmSettings.apply)

def llmClient(s: LlmSettings): IO[LLMClient] =
  IO.fromEither(
    (for {
      registry <- ModelRegistryService.default()
      given ModelRegistryService = registry
      config <- OpenAIConfig.fromValues(s.model, Secret(s.apiKey.value), s.baseUrl)
      client <- LLMConnect.getClient(config)
    } yield client).leftMap(error => new IllegalStateException(error.message))
  )

val client: IO[LLMClient] = settings.load[IO].flatMap(llmClient)
```

`llm4s-effect`'s `LLMClientIO` wraps the client for `IO`.

## 5. zio-config

A ZIO service describes its configuration with a `zio.Config` and reads it with
[zio-config](https://zio.dev/zio-config/) from any `ConfigProvider`: the environment, a map, HOCON,
YAML. llm4s does not depend on zio-config; add it to your own build.

```scala
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import zio.{ Config, ConfigProvider, ZIO }
import zio.config.*

final case class LlmSettings(model: String, apiKey: Config.Secret, baseUrl: String)

// From the environment: OPENAI_MODEL, OPENAI_API_KEY, OPENAI_BASE_URL.
val descriptor: Config[LlmSettings] =
  (Config.string("model").withDefault("gpt-4o-mini") ++
    Config.secret("api_key") ++
    Config.string("base_url").withDefault(OpenAIConfig.DEFAULT_BASE_URL))
    .nested("openai")
    .map { case (model, apiKey, baseUrl) => LlmSettings(model, apiKey, baseUrl) }

def llmClient(s: LlmSettings): ZIO[Any, Throwable, LLMClient] =
  ZIO
    .fromEither(for {
      registry <- ModelRegistryService.default()
      given ModelRegistryService = registry
      config <- OpenAIConfig.fromValues(s.model, Secret(s.apiKey.stringValue), s.baseUrl)
      client <- LLMConnect.getClient(config)
    } yield client)
    .mapError(error => new IllegalStateException(error.message))

val client: ZIO[Any, Throwable, LLMClient] =
  read(descriptor.from(ConfigProvider.envProvider)).flatMap(llmClient)
```

`llm4s-zio`'s `LLMClientZ` wraps the client for ZIO.

## See also

- [Configuration](configuration.md): `application.conf`, API keys, precedence and every section key.
- [Providers](../guide/providers.md): what each provider supports.
