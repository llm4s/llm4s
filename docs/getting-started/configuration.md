---
layout: page
title: Configuration
parent: Getting Started
nav_order: 3
---

# Configuration Guide
{: .no_toc }

Configure LLM4S for multiple providers, environments, and use cases.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## How configuration is loaded

`Llm4sConfig` reads [Typesafe Config](https://github.com/lightbend/config) through PureConfig,
with this precedence (highest first):

1. **JVM system properties** - `-Dllm4s.providers.provider=claude`
2. **Your `application.conf`** - `src/main/resources/application.conf`, or a file named with
   `-Dconfig.file=/path/to/app.conf` (or `-Dconfig.resource=prod.conf` for another resource)
3. **`reference.conf`** - the defaults shipped in each llm4s module on the classpath

Environment variables are **not** a layer of their own. llm4s reads one only where a `${?VAR}`
substitution names it: in a module's `reference.conf` (tracing, embeddings, tools, and each
provider module's vendor API key - see
[Environment variables llm4s reads](#environment-variables-llm4s-reads)), or in your own
`application.conf`. Each provider module binds its vendor's conventional key variable -
`llm4s-openai` binds `OPENAI_API_KEY`, `llm4s-anthropic` binds `ANTHROPIC_API_KEY` - to a shared
key under `llm4s.credentials` (see [API keys](#api-keys)). Nothing reads `LLM_MODEL`.

`${?VAR}` means "the value of `VAR` if it is set, otherwise leave the key unset". A second line
for the same key overrides the first only when the variable is set, which gives you a default
with an environment override:

```hocon
model = "gpt-4o-mini"
model = ${?OPENAI_MODEL}   # used only when OPENAI_MODEL is set
```

---

## Named provider sections

Chat providers are configured as named sections under `llm4s.providers`. You choose each
section's name; `provider` inside it says which provider it is, and `llm4s.providers.provider`
names the section `Llm4sConfig.defaultProvider()` loads.

**1. Add llm4s and the module for your provider:**

```scala
// build.sbt
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % llm4sVersion,
  "org.llm4s" %% "llm4s-openai" % llm4sVersion  // the module for your provider
)
```

| `provider = ...` | Module |
|---|---|
| `openai`, `azure`, `requesty` | `llm4s-openai` |
| `anthropic` | `llm4s-anthropic` |
| `gemini`, `vertexai` | `llm4s-gemini` |
| `ollama` | `llm4s-ollama` |
| `bedrock` | `llm4s-bedrock` |
| `deepseek`, `zai`, `openrouter`, `mistral`, `cohere`, `openai-compatible` | `llm4s-openai-compatible` |

The provider modules are on `main` but not yet published: `0.4.1` ships every provider inside
`llm4s-core`, so with `0.4.1` the `llm4s-core` dependency alone is enough.

**2. Add a section to `src/main/resources/application.conf`:**

```hocon
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

**3. Set your vendor's API-key variable:**

```bash
export OPENAI_API_KEY=sk-...
```

The section needs no `apiKey` line: `llm4s-openai` binds `OPENAI_API_KEY` to OpenAI's shared
key, `llm4s.credentials.openai.apiKey`, and a section without a key of its own uses that one.
See [API keys](#api-keys) for a second account or a variable of your own.

**4. Load it at the edge of your application:**

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

val result = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client   <- LLMConnect.getClient(providerConfig)
  response <- client.complete(Conversation(Seq(UserMessage("What is Scala?"))))
} yield response.content
```

If `OPENAI_API_KEY` is unset, `defaultProvider()` returns a `ConfigurationError` naming both
places a key can come from:
`apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf`.
This exact configuration is exercised by `DocumentedProviderConfigSpec` in `modules/openai`.

Other `Llm4sConfig` calls read the same sections:

```scala
Llm4sConfig.provider("openai-main")   // any section, by name
Llm4sConfig.defaultProviderName()     // the value of llm4s.providers.provider
Llm4sConfig.providers()               // every section, validated - fails if any one is invalid
Llm4sConfig.providerConfigs()         // every section, each loaded or failed on its own
Llm4sConfig.listModels()              // model discovery for the default section
Llm4sConfig.listModels("openai-main") // ... or for a named one
```

### API keys

Credentials belong to a vendor; sections belong to a use. A client's key is, in order:

1. its own `apiKey` - in its chat section `llm4s.providers.<name>`, its embeddings block
   `llm4s.embeddings.<provider>` or the reranker block `llm4s.rerank.cohere`;
2. otherwise its vendor's shared key, `llm4s.credentials.<provider>.apiKey`, which the provider
   module binds to the vendor's conventional variable;
3. otherwise a `ConfigurationError` naming both.

An alias resolves to its canonical provider's key: a `provider = "google"` section uses
`llm4s.credentials.gemini`. When a key is resolved, llm4s logs at INFO where it came from - for
example `llm4s.providers.openai-main: API key from llm4s.credentials.openai.apiKey` - and never
the value.

| Provider id | Shared key bound to | Module |
|---|---|---|
| `openai` | `OPENAI_API_KEY` | `llm4s-openai` |
| `azure` | `AZURE_OPENAI_API_KEY` | `llm4s-openai` |
| `requesty` | `REQUESTY_API_KEY` | `llm4s-openai` |
| `anthropic` | `ANTHROPIC_API_KEY` | `llm4s-anthropic` |
| `gemini` (alias `google`) | `GOOGLE_API_KEY`, else `GEMINI_API_KEY` | `llm4s-gemini` |
| `deepseek` | `DEEPSEEK_API_KEY` | `llm4s-openai-compatible` |
| `zai` | `ZAI_API_KEY` | `llm4s-openai-compatible` |
| `openrouter` | `OPENROUTER_API_KEY` | `llm4s-openai-compatible` |
| `mistral` | `MISTRAL_API_KEY` | `llm4s-openai-compatible` |
| `cohere` | `COHERE_API_KEY` (chat, reranker and embeddings) | `llm4s-openai-compatible`, `llm4s-rag`, `llm4s-cohere` |
| `voyage` | `VOYAGE_API_KEY` | `llm4s-voyage` |
| `jina` | `JINA_API_KEY` | `llm4s-jina` |
| `jev` | `TYPESAFE_API_KEY` (the Jev decision client; not a chat or embeddings provider) | `llm4s-jev` |

`ollama` takes no key, the generic `openai-compatible` provider has no vendor, `vertexai`
authenticates with OAuth2 (Application Default Credentials, or a service-account file named by
its `apiKey`), and `bedrock` authenticates with AWS credentials (the AWS default credential chain,
a `profile`, or explicit keys), so none of them has a shared key.

A section for a **second account** sets its own key, which wins over the shared one:

```hocon
llm4s.providers {
  openai-main {                          # uses OPENAI_API_KEY
    provider = "openai"
    model    = "gpt-4o-mini"
  }

  openai-batch {                         # billed to another account
    provider = "openai"
    model    = "gpt-4o-mini"
    apiKey   = ${?OPENAI_BATCH_API_KEY}
  }
}
```

The same form reads a variable of your own choosing - `apiKey = ${?MY_TEAM_OPENAI_KEY}`. Note that
if `OPENAI_BATCH_API_KEY` is unset, `openai-batch` falls back to the shared `OPENAI_API_KEY`
and bills the default account. In production, give every section its own `apiKey`; the
config-policy `prod` preset flags any section that does not (see
[Production deployment](../PRODUCTION_DEPLOYMENT)).

### Section keys

| Key | Meaning |
|---|---|
| `provider` | Required. The provider id, e.g. `openai`, `anthropic`, `ollama` (table above) |
| `model` | Required. The model name as the provider spells it, e.g. `gpt-4o-mini` |
| `apiKey` | The API key; required by every cloud provider. Optional in the section when the vendor's shared key is set ([API keys](#api-keys)) |
| `baseUrl` | Overrides the provider's default endpoint; **required** for `ollama` and `openai-compatible` |
| `headers` | Extra HTTP headers; sent by generic `openai-compatible` endpoints and by model listing |
| `organization` | OpenAI, Requesty and OpenRouter: the OpenAI organisation id, sent as `OpenAI-Organization` |
| `endpoint`, `apiVersion` | Azure OpenAI: the resource endpoint (required) and API version |
| `project`, `location` | Vertex AI: the GCP project id (required) and region (default `us-central1`) |
| `region`, `profile`, `accessKeyId`, `secretAccessKey`, `sessionToken` | AWS Bedrock: the AWS region (required, never defaulted); a shared-config profile; or explicit credentials, with a session token for temporary ones. With none of the credential keys the AWS default credential chain is used. `baseUrl` overrides the endpoint |
| `contextWindow`, `reserveCompletion`, `registryProvider`, `streamUsage` | Generic `openai-compatible` endpoints ([details](../guide/providers#openai-compatible-endpoints)) |

The first five keys are shared by every provider. The rest belong to the providers named, which
declare them ([provider-specific keys](../guide/providers#provider-specific-keys)); in a section for
any other provider such a key is ignored with a warning naming it, as a misspelt key is.

Each provider module's `reference.conf` has a commented example section, and the
[provider guide](../guide/providers) covers each provider in detail.

### Examples for other providers

```hocon
llm4s {
  providers {
    provider = "claude"

    claude {                              # key from ANTHROPIC_API_KEY
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
    }

    gemini-main {                         # key from GOOGLE_API_KEY or GEMINI_API_KEY
      provider = "gemini"
      model    = "gemini-2.0-flash"
    }

    azure-main {                          # key from AZURE_OPENAI_API_KEY
      provider   = "azure"
      model      = "gpt-4o"
      endpoint   = ${?AZURE_API_BASE}      # https://<resource>.openai.azure.com
      apiVersion = ${?AZURE_API_VERSION}   # optional
    }

    ollama-local {
      provider = "ollama"
      model    = "llama3.2"
      baseUrl  = "http://localhost:11434"
      baseUrl  = ${?OLLAMA_BASE_URL}       # optional override
    }
  }
}
```

**Only the section you load is validated.** A section with no key available, or
whose provider module is not on the classpath, fails when it is loaded - by
`provider("<name>")`, or by `defaultProvider()` when it is the default - and not otherwise.
With the example above and `ANTHROPIC_API_KEY` alone set, `defaultProvider()` loads `claude`
while `provider("gemini-main")` fails with a `ConfigurationError` naming `GOOGLE_API_KEY`,
`GEMINI_API_KEY` and `gemini-main`'s `apiKey`. The exception is `Llm4sConfig.providers()`, which returns every section and so
validates them all; `Llm4sConfig.providerConfigs()` instead reports each section's error
separately. (Up to 0.4.1 every section was validated on every load.)

---

## Switching providers

Your code calls `Llm4sConfig.defaultProvider()` and does not change. To use a different
provider, point `llm4s.providers.provider` at another section:

- **Edit the file**: `provider = "claude"`.
- **Bind it to a variable of your own**:

  ```hocon
  llm4s.providers {
    provider = "openai-main"
    provider = ${?LLM4S_PROVIDER}   # your binding; any variable name works
  }
  ```

  then `export LLM4S_PROVIDER=claude`.
- **Override it for one run**: `sbt -Dllm4s.providers.provider=claude run`, or
  `java -Dllm4s.providers.provider=claude -jar app.jar`.
- **Load a section by name** instead of the default: `Llm4sConfig.provider("claude")`. This is
  how an application uses several providers at once.

For per-environment setups (local Ollama in development, a cloud provider in production), keep
one file per environment - `application.conf` plus `prod.conf` that starts with
`include "application.conf"` - and pick one with `-Dconfig.resource=prod.conf`. This is a
matter of taste, not a requirement: a section an environment has no key for does no harm
there unless it is loaded.

---

## Running the samples

The repository's samples read `modules/samples/src/main/resources/application.conf`, which
defines one section, `ollama-local`, as the default, and binds three variables of its own:

| Variable | Bound by the samples' `application.conf` to |
|---|---|
| `LLM4S_PROVIDER` | `llm4s.providers.provider` - which section to use |
| `OLLAMA_MODEL` | `llm4s.providers.ollama-local.model` (default `llama3:latest`) |
| `OLLAMA_BASE_URL` | `llm4s.providers.ollama-local.baseUrl` (default `http://localhost:11434`) |

These are the samples' bindings, not the library's: your own application reads them only if
its `application.conf` binds them.

With the default section, the model must be in your Ollama install: run `ollama pull llama3`
once, or name a model you already have (`ollama list`) with `export OLLAMA_MODEL=<model>`.

To run a sample against another provider, add a section to
`modules/samples/src/main/resources/application.local.conf` - git-ignored and included by the
samples' `application.conf` - and select it:

```hocon
# modules/samples/src/main/resources/application.local.conf
llm4s.providers {
  openai-main {
    provider = "openai"
    model    = "gpt-4o-mini"
  }
}
```

```bash
export OPENAI_API_KEY=sk-...
export LLM4S_PROVIDER=openai-main
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

The chat-tui sample is the exception: `ChatTuiConfig` reads `LLM_MODEL=<provider>/<model>` and
the matching API-key variable itself, and falls back to `Llm4sConfig.defaultProvider()` when
`LLM_MODEL` is unset.

---

## Embeddings Configuration

LLM4S supports multiple embedding providers for RAG (Retrieval-Augmented Generation) workflows.

### Unified Format (Recommended)

Embeddings are configured separately from chat providers, under `llm4s.embeddings`. Select
the provider and model with `llm4s.embeddings.model` in `provider/model-name` form, which core's
`reference.conf` binds to `EMBEDDING_MODEL`:

```bash
# OpenAI embeddings (the same OPENAI_API_KEY the chat sections use)
EMBEDDING_MODEL=openai/text-embedding-3-small
OPENAI_API_KEY=sk-...

# Voyage AI embeddings
EMBEDDING_MODEL=voyage/voyage-3
VOYAGE_API_KEY=pa-...

# Jina AI embeddings
EMBEDDING_MODEL=jina/jina-embeddings-v3
JINA_API_KEY=jina_...

# Cohere embeddings
EMBEDDING_MODEL=cohere/embed-english-v3.0
COHERE_API_KEY=...

# Ollama embeddings (local, no API key needed)
EMBEDDING_MODEL=ollama/nomic-embed-text
```

The key follows the same [order](#api-keys) as a chat section's: `llm4s.embeddings.<provider>.apiKey`
if you set it, otherwise the vendor's shared `llm4s.credentials.<provider>.apiKey`. So one
`OPENAI_API_KEY` serves both OpenAI chat and OpenAI embeddings. To bill embeddings to another
account, give the block its own key:

```hocon
llm4s.embeddings.openai.apiKey = ${?OPENAI_EMBEDDINGS_API_KEY}
```

Each embedding provider comes from its module: `openai` from `llm4s-openai`, `voyage` from
`llm4s-voyage`, `jina` from `llm4s-jina`, `cohere` from `llm4s-cohere` and `ollama` from `llm4s-ollama` (in `0.4.1` and earlier, `openai` and `voyage` are
inside `llm4s-core`). Without the module, the provider id fails with an error naming the
registered embedding providers.

Default base URLs are used automatically:
- OpenAI: `https://api.openai.com/v1`
- Voyage: `https://api.voyageai.com/v1`
- Jina: `https://api.jina.ai/v1`
- Cohere: `https://api.cohere.com` (the API root; the provider posts to `/v2/embed`)
- Ollama: `http://localhost:11434`

Override base URLs if needed - each module's `reference.conf` binds a variable for it:
```bash
OPENAI_EMBEDDING_BASE_URL=https://custom.openai.com/v1   # llm4s-openai
VOYAGE_EMBEDDING_BASE_URL=https://custom.voyage.ai/v1    # llm4s-voyage
JINA_EMBEDDING_BASE_URL=https://custom.jina.ai/v1        # llm4s-jina
COHERE_EMBEDDING_BASE_URL=https://custom.cohere.com      # llm4s-cohere
OLLAMA_EMBEDDING_BASE_URL=http://embeddings-host:11434   # llm4s-ollama
```

### Available Models

**OpenAI:**
- `text-embedding-3-small` - Fast, cost-effective (1536 dimensions)
- `text-embedding-3-large` - Higher quality (3072 dimensions)
- `text-embedding-ada-002` - Legacy model (1536 dimensions)

**Voyage AI:**
- `voyage-3` - General purpose
- `voyage-3-large` - Higher quality
- `voyage-code-2` - Code-optimized

**Jina AI:**
- `jina-embeddings-v3` - Multilingual, 8192-token context (1024 dimensions)
- `jina-embeddings-v4` - (2048 dimensions)

Jina embeds queries and documents differently. The provider built from `EMBEDDING_MODEL` sends
the `retrieval.passage` task; to embed queries, build it with the typed `JinaTask` setting:
`JinaEmbeddingProvider.fromConfig(config, JinaTask.RetrievalQuery)`.

**Cohere:**
- `embed-v4.0` - (1536 dimensions)
- `embed-english-v3.0`, `embed-multilingual-v3.0` - (1024 dimensions)
- `embed-english-light-v3.0`, `embed-multilingual-light-v3.0` - (384 dimensions)

Cohere embeds queries and documents differently. The provider built from `EMBEDDING_MODEL` sends
`input_type` `search_document`; to embed queries, build it with the typed `CohereInputType` setting:
`CohereEmbeddingProvider.fromConfig(config, CohereInputType.SearchQuery)`. Texts are sent in
requests of at most 96, Cohere's limit, and the vectors keep the model's default size.

**Ollama (local):**
- `nomic-embed-text` - General purpose (768 dimensions)
- `mxbai-embed-large` - Higher quality (1024 dimensions)
- `all-minilm` - Lightweight (384 dimensions)

**Install Ollama embedding model:**

```bash
ollama pull nomic-embed-text
```

### Legacy Format (Still Supported)

The legacy format using `EMBEDDING_PROVIDER` is still supported for backward compatibility:

```bash
# OpenAI
EMBEDDING_PROVIDER=openai
OPENAI_EMBEDDING_BASE_URL=https://api.openai.com/v1
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_API_KEY=sk-...

# Voyage AI
EMBEDDING_PROVIDER=voyage
VOYAGE_EMBEDDING_BASE_URL=https://api.voyageai.com/v1
VOYAGE_EMBEDDING_MODEL=voyage-3
VOYAGE_API_KEY=pa-...

# Ollama
EMBEDDING_PROVIDER=ollama
OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
```

### Using Embeddings in Code

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.agent.memory.LLMEmbeddingService

// Core logic depends on injected service
class RAGService(embeddingService: LLMEmbeddingService) {
  def processDocuments(docs: Seq[String]): Unit = {
    // Use embeddingService...
  }
}

// Configuration boundary
object RAGApplication extends App {
  val startup = for {
    // 1. Load config
    (provider, cfg) <- Llm4sConfig.embeddings()
    model <- Llm4sConfig.textEmbeddingModel()
    
    // 2. Build dependencies
    client <- EmbeddingClient.from(provider, cfg)
    
    // 3. Create service
    service = LLMEmbeddingService(
      client, 
      EmbeddingModelConfig(model.modelName, model.dimensions)
    )
  } yield new RAGService(service)

  startup.fold(
    err => println(s"Startup failed: $err"),
    service => println("RAG Service started successfully")
  )
}
```

### System Properties (Alternative)

When running via sbt, use system properties for reliable configuration:

```bash
# Using unified format (recommended)
sbt -Dllm4s.embeddings.model=ollama/nomic-embed-text \
    "run"

# Or with legacy format
sbt -Dllm4s.embeddings.provider=ollama \
    -Dllm4s.embeddings.ollama.model=nomic-embed-text \
    "run"
```

---

## Tracing Configuration

### Console Tracing (Development)

```bash
TRACING_MODE=console
```

Output appears in stdout:

```
[TRACE] Completion request: UserMessage(What is Scala?)
[TRACE] Token usage: 150 tokens
[TRACE] Response time: 1.2s
```

### Langfuse (Production)

Langfuse tracing needs the `llm4s-observability` module on the classpath:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability" % "<version>"
```

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_URL=https://cloud.langfuse.com
```

That module's `reference.conf` binds the `LANGFUSE_*` variables under
`llm4s.tracing.langfuse`, so they can equally be set there in `application.conf`. Both keys are
required: without one, `Tracing.fromSettings` returns a `ConfigurationError` naming
`llm4s.tracing.langfuse.publicKey (LANGFUSE_PUBLIC_KEY)` and/or `secretKey`, and
`Tracing.create` logs it and traces nothing. Without the module, `TRACING_MODE=langfuse` gives
an error naming the `llm4s-observability` artifact.

Get keys from [Langfuse](https://langfuse.com):

1. Sign up at langfuse.com
2. Create a project
3. Navigate to Settings → API Keys
4. Copy public and secret keys


### OpenTelemetry

OpenTelemetry provides distributed tracing for observability across your infrastructure.

**Setup:**

```bash
TRACING_MODE=opentelemetry
OTEL_SERVICE_NAME=llm4s-agent
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

OpenTelemetry tracing needs the `llm4s-observability-otel` module on the classpath.

**Settings** (bound in core's `reference.conf`):

| Config key | Variable | Default | Description |
|----------|----------|---------|-------------|
| `llm4s.tracing.mode` | `TRACING_MODE` | `console` | Must be `opentelemetry` |
| `llm4s.tracing.opentelemetry.serviceName` | `OTEL_SERVICE_NAME` | `llm4s-agent` | Service name for trace identification |
| `llm4s.tracing.opentelemetry.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry Collector endpoint |
| `llm4s.tracing.opentelemetry.headers` | (none) | (empty) | Headers sent with every export |

llm4s builds the OTLP exporter itself, so the OpenTelemetry SDK's own
`OTEL_EXPORTER_OTLP_HEADERS` variable is **not** read. Set headers in `application.conf`,
binding secrets as usual:

```hocon
llm4s.tracing.opentelemetry.headers {
  Authorization = ${?OTEL_AUTH_HEADER}   # e.g. "Bearer <token>"
}
```

**Example Configuration:**

```bash
# Local Jaeger (via Docker)
TRACING_MODE=opentelemetry
OTEL_SERVICE_NAME=llm4s-agent
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Cloud Provider with Authentication
TRACING_MODE=opentelemetry
OTEL_SERVICE_NAME=llm4s-runner
OTEL_EXPORTER_OTLP_ENDPOINT=https://otel-collector.example.com:4317
OTEL_AUTH_HEADER="Bearer sk-otel-token"   # bound by the headers block above
```

**Spans Generated:**

```
Span: LLM Completion
  ├─ gen_ai.request.model: gpt-4o
  ├─ gen_ai.usage.input_tokens: 150
  └─ gen_ai.usage.output_tokens: 50

Span: Tool Execution: web_search
  ├─ tool.name: web_search
  ├─ tool.input: "Scala 3 features"
  └─ duration_ms: 1234

Span: Token Usage - completion
  ├─ operation: completion
  ├─ gen_ai.usage.total_tokens: 200
  └─ cost.usd: 0.0042

Span: Agent State Updated
  ├─ status: RUNNING
  ├─ message_count: 5
  └─ log_count: 12
```

**Setting Up Jaeger (Local Development):**

```bash
# Start Jaeger all-in-one
docker run -d \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 14250:14250 \
  -p 9411:9411 \
  jaegertracing/all-in-one:latest

# Configure LLM4S
export TRACING_MODE=opentelemetry
export OTEL_SERVICE_NAME=llm4s-dev
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# View traces at http://localhost:16686
```

**Code Usage:**

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.{ TraceEvent, Tracing }

// Build the tracer that llm4s.tracing.mode selects. fromSettings fails if the mode's
// backend - here llm4s-observability-otel - is not on the classpath; Tracing.create
// would log that and fall back to NoOpTracing instead.
val result = for {
  settings <- Llm4sConfig.tracing()
  tracer   <- Tracing.fromSettings(settings)
  _        <- tracer.traceEvent(TraceEvent.AgentInitialized("query", Vector("tool1")))
  _        <- tracer.traceTokenUsage(usage, "gpt-4o", "completion")
  _         = tracer.shutdown() // always shut down to flush pending spans
} yield ()
```

### Other tracing backends

`console` and `none` are built into `llm4s-core`; every other mode is served by a
`org.llm4s.trace.spi.TracingBackend` that a module registers in
`META-INF/services/org.llm4s.trace.spi.TracingBackend` - `langfuse` by `llm4s-observability`,
`opentelemetry` by `llm4s-observability-otel`. A mode llm4s has no name for, such as
`TRACING_MODE=datadog`, is read as `TracingMode.Named("datadog")` and goes to whichever backend
declares that mode. With no such backend, `Tracing.create` logs an error listing the available
modes and traces nothing. For `langfuse` and `opentelemetry` the error also names the module to
add, since llm4s knows which of its own modules serves them:

```text
Tracing mode 'opentelemetry' is configured but no TracingBackend for it is on the classpath.
Add the 'org.llm4s' %% 'llm4s-observability-otel' dependency. Available modes: console, noop.
```

That hint is only a message: dispatch never consults it, and a third-party mode needs no entry in
it. See the
[observability guide](../guide/observability/index.md#adding-a-tracing-backend) to write one.

### Disable Tracing

```bash
TRACING_MODE=none
```

---

## Environment variables llm4s reads

These are the variables bound by a `${?VAR}` in some llm4s module's `reference.conf`, so they
work without any configuration of your own. Each sets the config key beside it, which you can
also set in `application.conf` or with `-D`.

| Variable | Config key | Bound by |
|---|---|---|
| `TRACING_MODE` | `llm4s.tracing.mode` (`console` - the default - or `none`, both built in; `langfuse`, `opentelemetry`, or the mode of any other `TracingBackend` on the classpath) | `llm4s-core` |
| `LANGFUSE_URL`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_ENV`, `LANGFUSE_RELEASE`, `LANGFUSE_VERSION` | `llm4s.tracing.langfuse.*` | `llm4s-observability` |
| `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT` | `llm4s.tracing.opentelemetry.serviceName`, `.endpoint` | `llm4s-observability-otel` |
| `EMBEDDING_MODEL` (or legacy `EMBEDDING_PROVIDER`) | `llm4s.embeddings.model` (`.provider`) | `llm4s-core` |
| `CHUNK_SIZE`, `CHUNK_OVERLAP`, `CHUNKING_ENABLED` | `llm4s.embeddings.chunking.*` | `llm4s-core` |
| `LLM4S_EXCHANGE_LOGGING_ENABLED`, `LLM4S_EXCHANGE_LOGGING_DIR` | `llm4s.exchangeLogging.*` | `llm4s-core` |
| `LLM4S_MODEL_REGISTRY_RESOURCE`, `LLM4S_MODEL_REGISTRY_FILE`, `LLM4S_MODEL_REGISTRY_URL` | `llm4s.modelRegistry.*` | `llm4s-core` |
| `WORKSPACE_DIR`, `WORKSPACE_IMAGE`, `WORKSPACE_PORT` | `llm4s.workspace.*` | `llm4s-core` |
| `BRAVE_SEARCH_API_KEY`, `EXA_API_KEY` and the other `BRAVE_*`, `EXA_*` variables, `DUCK_DUCK_GO_SEARCH_API_URL` | `llm4s.tools.*` | `llm4s-core` |
| `OPENAI_API_KEY` | `llm4s.credentials.openai.apiKey` (OpenAI chat sections and embeddings) | `llm4s-openai` |
| `AZURE_OPENAI_API_KEY` | `llm4s.credentials.azure.apiKey` | `llm4s-openai` |
| `REQUESTY_API_KEY` | `llm4s.credentials.requesty.apiKey` | `llm4s-openai` |
| `ANTHROPIC_API_KEY` | `llm4s.credentials.anthropic.apiKey` | `llm4s-anthropic` |
| `GOOGLE_API_KEY`, else `GEMINI_API_KEY` | `llm4s.credentials.gemini.apiKey` | `llm4s-gemini` |
| `DEEPSEEK_API_KEY`, `ZAI_API_KEY`, `OPENROUTER_API_KEY`, `MISTRAL_API_KEY` | `llm4s.credentials.<provider>.apiKey` | `llm4s-openai-compatible` |
| `COHERE_API_KEY` | `llm4s.credentials.cohere.apiKey` (Cohere chat, the Cohere reranker and Cohere embeddings) | `llm4s-openai-compatible`, `llm4s-rag`, `llm4s-cohere` |
| `VOYAGE_API_KEY` | `llm4s.credentials.voyage.apiKey` | `llm4s-voyage` |
| `JINA_API_KEY` | `llm4s.credentials.jina.apiKey` | `llm4s-jina` |
| `TYPESAFE_API_KEY` | `llm4s.credentials.jev.apiKey` (the Jev decision client) | `llm4s-jev` |
| `TYPESAFE_BASE_URL`, `TYPESAFE_DEFAULT_MODEL` | `llm4s.jev.baseUrl`, `llm4s.jev.model` | `llm4s-jev` |
| `OPENAI_EMBEDDING_BASE_URL`, `OPENAI_EMBEDDING_MODEL` | `llm4s.embeddings.openai.*` | `llm4s-openai` |
| `VOYAGE_EMBEDDING_BASE_URL`, `VOYAGE_EMBEDDING_MODEL` | `llm4s.embeddings.voyage.*` | `llm4s-voyage` |
| `JINA_EMBEDDING_BASE_URL`, `JINA_EMBEDDING_MODEL` | `llm4s.embeddings.jina.*` | `llm4s-jina` |
| `COHERE_EMBEDDING_BASE_URL`, `COHERE_EMBEDDING_MODEL` | `llm4s.embeddings.cohere.*` | `llm4s-cohere` |
| `OLLAMA_EMBEDDING_BASE_URL`, `OLLAMA_EMBEDDING_MODEL` | `llm4s.embeddings.ollama.*` | `llm4s-ollama` |
| `RERANK_PROVIDER`, `COHERE_RERANK_BASE_URL`, `COHERE_RERANK_MODEL` | `llm4s.rerank.*`, read by `RerankerConfigLoader` | `llm4s-rag` |
| `PGVECTOR_HOST`, `PGVECTOR_PORT`, `PGVECTOR_DATABASE`, `PGVECTOR_USER`, `PGVECTOR_PASSWORD`, `PGVECTOR_TABLE`, ... | `llm4s.rag.permissions.pg.*` | `llm4s-rag` |

**Not read by llm4s** unless your `application.conf` binds them: `LLM_MODEL`, `LLM4S_PROVIDER`,
every chat provider's endpoint and model variable (`AZURE_API_BASE`, `AZURE_API_VERSION`,
`OPENAI_BASE_URL`, `OLLAMA_BASE_URL`, ...), `AZURE_API_KEY` (the Azure key is
`AZURE_OPENAI_API_KEY`, the openai SDK's name), `CO_API_KEY`, anything for the generic
`openai-compatible` provider, and `OTEL_EXPORTER_OTLP_HEADERS`. A shared key sets only `apiKey`:
`baseUrl`, `endpoint`, `model` and `apiVersion` always come from the section. `LLM_MODEL` was
removed with legacy single-provider loading in [#903](https://github.com/llm4s/llm4s/issues/903).

---

## Environment-specific configuration

Keep the provider structure in HOCON and inject secrets from the environment. One
`application.conf` can serve every environment, since only the selected section's variables need
to be set, or you can keep one file per environment:

```hocon
# src/main/resources/application.conf - development
llm4s {
  providers {
    provider = "ollama-local"
    ollama-local {
      provider = "ollama"
      model    = "llama3.2"
      baseUrl  = "http://localhost:11434"
    }
  }
  tracing.mode = "console"
}
```

```hocon
# src/main/resources/prod.conf - production
include "application.conf"

llm4s {
  providers = null                     # drop the development sections
  providers {
    provider = "claude"
    claude {
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
      apiKey   = ${?ANTHROPIC_API_KEY}   # explicit in production: which account this bills
    }
  }
  tracing.mode = "langfuse"            # llm4s-observability; LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY from the environment
}
```

```bash
java -Dconfig.resource=prod.conf -jar app.jar
```

`providers = null` matters: without it the included `ollama-local` section would stay, and be
validated, in production.

---

## Configuration Best Practices

### ✅ DO

1. **Keep secrets in environment variables** - the vendor's own variable (`OPENAI_API_KEY`), or
   one you bind with `apiKey = ${?VAR}` in a section; in production, give each section its own
2. **Keep structure in `application.conf`**: provider sections, models, defaults
3. **Add `.env` files to `.gitignore`** if you use them to export variables
4. **Use different configs** for dev/staging/prod
5. **Validate configuration** at startup

```scala
import org.llm4s.config.Llm4sConfig

object ApplicationBoundary {
  def validateAndStart(): Unit = {
    // Fail fast at startup if config is invalid
    Llm4sConfig.defaultProvider().fold(
      error => {
        Console.err.println(s"FATAL: Configuration error: ${error.formatted}")
        System.exit(1)
      },
      config => {
        println(s"Configuration loaded for model: ${config.model}")
        // startApplication(config)
      }
    )
  }
}
```

### ❌ DON'T

1. **Don't hardcode API keys** in source code or `application.conf`
2. **Don't commit .env files** to version control
3. **Don't use System.getenv() directly** (use Llm4sConfig)
4. **Don't mix production keys** in development
5. **Don't skip validation** - fail fast on bad config

---

## Validation Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService

object ValidateConfig extends App {
  println("Validating LLM4S configuration...")

  // Perform validation at the application edge
  val validationResult = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
  } yield (providerConfig, client)

  validationResult match {
    case Right((config, client)) =>
      println(s"✅ Model: ${config.model}")
      println(s"✅ Provider: ${config.getClass.getSimpleName}")
      println(s"✅ Client ready: ${client.getClass.getSimpleName}")
      println("✅ Configuration valid!")

    case Left(error) =>
      Console.err.println(s"❌ Configuration error: ${error.formatted}")
      System.exit(1)
  }
}
```

---

## Troubleshooting

### Problem: "missing required fields: apiKey"

**Symptoms:**
```
ConfigurationError: Provider 'openai-main' (provider = openai) is missing required fields:
  - apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf
```

**Root causes:**
1. Neither the vendor's variable (`OPENAI_API_KEY`) nor a section `apiKey` is set in this process
2. The provider's module is missing, so nothing binds its variable (see the table in
   [Named provider sections](#named-provider-sections))
3. The section binds its own variable (`apiKey = ${?OTHER_VAR}`) that is unset, and the
   vendor's variable is unset too
4. `.env` file not loaded in the shell that starts the JVM

The error names the section it is about, and only a section being loaded is validated. If it
names a section you did not ask for, check `llm4s.providers.provider` (and any
`-Dllm4s.providers.provider` override): that is the section `defaultProvider()` loads. The one
call that validates every section is `Llm4sConfig.providers()`.

**Debug steps:**
```bash
# Is the vendor's variable set in this shell?
echo $OPENAI_API_KEY

# Does the section set a key of its own? (an apiKey line wins over OPENAI_API_KEY)
grep -n apiKey src/main/resources/application.conf
```

### Problem: "Configured provider '...' was not found"

**Symptoms:**
```
ConfigurationError: Configured provider 'openai-main' was not found
```

**Fix:** `llm4s.providers.provider` (or the name passed to `Llm4sConfig.provider(...)`) must
match a section name exactly. Check for a typo, and for an `LLM4S_PROVIDER`-style override
selecting a section this file does not define. If `llm4s.providers.provider` is absent
altogether, `defaultProvider()` fails with "No default provider configured under
llm4s.providers.provider" - set it, or call `Llm4sConfig.provider("<name>")`.

### Problem: "Unknown provider" / provider not registered

**Symptoms:** an error naming `llm4s.providers.<name>.provider` and listing the registered
providers.

**Fix:** the section's `provider = "..."` id comes from a module that is not on the classpath.
Add it (see the table in [Named provider sections](#named-provider-sections)), or fix the id's
spelling.

### Problem: "Configuration not loading from application.conf"

**Symptoms:** `defaultProvider()` behaves as if your sections were not there.

**Debug:**
```scala
import com.typesafe.config.ConfigFactory

val config = ConfigFactory.load()
println(config.hasPath("llm4s.providers.provider"))    // Should be true
println(config.getString("llm4s.providers.provider"))  // Should name your section
```

**Common causes:**
- application.conf not in `src/main/resources/` (or not on the runtime classpath)
- `-Dconfig.file` / `-Dconfig.resource` pointing somewhere else
- HOCON syntax error (missing quotes, wrong nesting)
- A `-D` system property overriding the key (system properties have the highest precedence)

### Problem: "Provider mismatch"

**Symptoms:**
```
import org.llm4s.error.AuthenticationError

Left(AuthenticationError("Invalid API key"))
```

**Root cause:** the section's own `apiKey` binds a variable holding another provider's key, or
the vendor's variable holds the wrong key. The INFO line `llm4s.providers.<name>: API key from
<path>` says which place the key came from.

```hocon
# ❌ Wrong: an OpenAI section reading the Anthropic key
openai-main {
  provider = "openai"
  model    = "gpt-4o"
  apiKey   = ${?ANTHROPIC_API_KEY}
}
```

### Problem: "Rate limiting / 429 errors"

**Symptoms:**
```
import org.llm4s.error.RateLimitError

Left(RateLimitError("Rate limit exceeded"))
```

**Solutions:**

1. **Add retry logic:**
```scala
import scala.concurrent.duration._
import org.llm4s.error.RateLimitError
import org.llm4s.types.Result

// Note: This example uses Thread.sleep for simplicity.
// In production, prefer scala.concurrent or cats-effect for non-blocking delays.
def retryWithBackoff[A](op: => Result[A], maxRetries: Int = 3): Result[A] = {
  (1 to maxRetries).foldLeft(op) { (result, attempt) =>
    result match {
      case Left(_: RateLimitError) if attempt < maxRetries =>
        Thread.sleep(Math.pow(2, attempt).toLong * 1000)  // Exponential backoff
        op
      case other => other
    }
  }
}
```

2. **Use a cheaper model for testing** - change the section's `model`:
```hocon
openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"   # development; gpt-4o in production
}
```

3. **Switch to Ollama for development** - point `llm4s.providers.provider` at an
   `ollama-local` section (see [Switching providers](#switching-providers)); free, no rate limits.

### Problem: "Slow responses or timeouts"

**Symptoms:** Requests take 30+ seconds or timeout.

**Solutions:**

1. **Request timeouts are not configurable yet.** Each client uses an internal default
   (two minutes for a completion, five for a stream in the OpenAI-compatible clients);
   configurable timeouts are tracked in [#712](https://github.com/llm4s/llm4s/issues/712).

2. **Use streaming for long responses:**
```scala
// Non-streaming: waits for full response
client.complete(Conversation(Seq(UserMessage("Your query"))))

// Streaming: get tokens as they arrive
client.streamComplete(
  Conversation(Seq(UserMessage("Your query")))
) { chunk =>
  chunk.content.foreach(print)
}
```

3. **Reduce response length:**
```scala
client.complete(
  Conversation(Seq(UserMessage("Your query"))),
  CompletionOptions(maxTokens = Some(500))  // Limit response length
)
```

### Problem: "OutOfMemoryError with embeddings"

**Symptoms:** JVM crashes when processing large document collections.

**Solutions:**

1. **Batch embeddings instead of loading all at once:**
```scala
val documents: List[String] = loadDocuments()
val batchSize = 100

val embeddings = documents.grouped(batchSize).flatMap { batch =>
  embedder.embed(batch) match {
    case Right(emb) => emb
    case Left(err) => 
      println(s"Batch failed: $err")
      List.empty
  }
}.toList
```

2. **Increase JVM heap:**
```bash
export SBT_OPTS="-Xmx4G -Xss4M"
sbt run
```

3. **Use a local embedding model (smaller memory footprint):**
```bash
EMBEDDING_MODEL=ollama/nomic-embed-text
```

---

## Next Steps

Configuration complete! Now you can:

1. **[Explore features →](next-steps)** - Dive into agents, tools, and more
2. **[Browse examples →](/examples/)** - See configuration in action
3. **[User guide →](../guide/basic-usage)** - Learn core concepts
4. **[Observability →](../guide/observability)** - Set up tracing

---

## Additional Resources

- **[Llm4sConfig API](/api/#4-explicit-configuration)** - Detailed API documentation
- **[Environment variables llm4s reads](#environment-variables-llm4s-reads)** - Complete list
- **[Production Guide](../PRODUCTION_DEPLOYMENT)** - Production best practices
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help with configuration

---

**Ready to build?** [Explore what's next →](next-steps)
