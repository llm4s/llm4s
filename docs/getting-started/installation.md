---
layout: page
title: Installation
parent: Getting Started
nav_order: 1
---

# Installation
{: .no_toc }

Get LLM4S up and running in minutes.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Prerequisites

Before installing LLM4S, ensure you have:

- **Java Development Kit (JDK) 21**
- **Scala 3.7.1**
- **SBT 1.10.6** or higher
- An API key from at least one LLM provider (OpenAI, Anthropic, Azure OpenAI, or Ollama)

### Verify Prerequisites

```bash
# Check Java version
java -version  # Should show 21

# Check Scala version
scala -version  # 3.7.1

# Check SBT version
sbt version  # 1.10.6 or higher
```

---

## Add LLM4S to Your Project

{: .warning }
> **Artifact coordinates changed in 0.4.0.** Every published module now carries an
> `llm4s-` prefix (`core` → `llm4s-core`, `workspaceClient` → `llm4s-workspace-client`,
> and so on). Releases up to and including `0.3.4` remain available under the old names.
> See the [migration guide](/reference/migration#artifact-coordinate-rename-v040) for the
> full old → new table.

### SBT

Add LLM4S to your `build.sbt`:

```scala
// Scala 3
libraryDependencies += "org.llm4s" %% "llm4s-core" % "{{ site.data.project.latest_release }}"
ThisBuild / scalaVersion := "3.7.1"
```

### Maven

```xml
<!-- For Scala 3 -->
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-core_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>

```

### Gradle (Kotlin DSL)

Gradle does **not** resolve Scala cross-version suffixes automatically — you must append the `_3` suffix explicitly (LLM4S is Scala 3 only):

```kotlin
// build.gradle.kts
repositories { mavenCentral() }

dependencies {
    implementation("org.llm4s:llm4s-core_3:{{ site.data.project.latest_release }}")
}

// Pin the Scala 3 library (scala3-library_3 only; scala-library stays at 2.13.x)
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.scala-lang" && requested.name == "scala3-library_3") {
            useVersion("3.7.1")
        }
    }
}
```

### Gradle (Groovy DSL)

```groovy
// build.gradle
repositories { mavenCentral() }

dependencies {
    implementation 'org.llm4s:llm4s-core_3:{{ site.data.project.latest_release }}'
}

configurations.all {
    resolutionStrategy.eachDependency { details ->
        if (details.requested.group == 'org.scala-lang' && details.requested.name == 'scala3-library_3') {
            details.useVersion '3.7.1'
        }
    }
}
```

> **Using Spring Boot, Ktor, or encountering dependency conflicts?**
> See the full [Gradle integration guide](gradle) for dependency exclusion recipes
> and the [dependency conflicts reference](/reference/dependency-conflicts).


### Scala CLI

No build at all: declare the dependency in the file.

```scala
//> using scala 3.7.1
//> using dep org.llm4s::llm4s-core:{{ site.data.project.latest_release }}
```

The [Scala CLI quick start](scala-cli) has a complete file that makes a first call.

### Multi-Module Project

If you have a multi-module project:

```scala
lazy val myProject = (project in file("."))
  .settings(
    name := "my-llm-project",
    scalaVersion := "3.7.1",
    libraryDependencies ++= Seq(
      "org.llm4s" %% "llm4s-core" % "{{ site.data.project.latest_release }}"
    )
  )
```

### Unreleased changes (`main`)

No snapshots are published: releases are cut from tags only, and nothing publishes between them. To try
what is on `main`, build it and publish it to your local repository:

```bash
git clone https://github.com/llm4s/llm4s && cd llm4s
sbt publishLocal   # for sbt; use `sbt publishM2` for Maven or Gradle (mavenLocal())
sbt version        # prints the version it published, for example 0.4.1+165-b455b8ea-SNAPSHOT
```

Then depend on that version, for example
`libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.4.1+165-b455b8ea-SNAPSHOT"` (use the one `sbt version`
printed). `main` describes modules and configuration that the latest release does not have yet, so the
rest of this guide matches `main`, not the released artifact, wherever it says a module is not yet published.

---

## Quick Start with the Starter Kit

The fastest way to get started is using the **llm4s.g8** template:

```bash
# Install the template
sbt new llm4s/llm4s.g8

# Follow the prompts
# name [My LLM Project]: my-awesome-agent
# organization [com.example]: com.mycompany
# scala_version [3.7.1]:
# llm4s_version [0.4.0]:

cd my-awesome-agent
sbt run
```

The starter kit includes:

- ✅ Pre-configured SBT build
- ✅ Example agent with tool calling
- ✅ Configuration templates
- ✅ Multi-provider setup
- ✅ Docker configuration for workspace

[View the starter kit →](https://github.com/llm4s/llm4s.g8)

---

## Optional Dependencies

{: .note }
> Additional modules are published separately. The core library includes most functionality.
> Check [Maven Central](https://central.sonatype.com/namespace/org.llm4s) for available artifacts.

### For RAG, vector stores, chunking, reranking and document extraction

{: .note }
> Not yet published. `llm4s-rag` exists in the build as of
> [#1128](https://github.com/llm4s/llm4s/issues/1128) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-rag" % llm4sVersion
```

Brings `llm4s-knowledgegraph` with it, along with Tika, POI, PDFBox, jsoup and the AWS S3
client — the document-extraction and loader dependencies that `llm4s-core` no longer carries.
Package names are unchanged, so existing `org.llm4s.rag.*` imports keep working; see the
[migration note](../reference/migration.md#slice-1-llm4s-rag-and-llm4s-knowledgegraph).

### For agent memory

{: .note }
> Not yet published. `llm4s-memory` and `llm4s-memory-postgres` exist in the build as of
> [#1129](https://github.com/llm4s/llm4s/issues/1129) but ship in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-memory" % llm4sVersion

// only if you store memories in Postgres/pgvector
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % llm4sVersion
```

`llm4s-memory` carries `MemoryStore`, the memory managers, and the in-memory and SQLite-backed
stores; it adds sqlite-jdbc to your classpath and nothing else. `PostgresMemoryStore` lives in
`llm4s-memory-postgres`, which brings HikariCP and the Postgres JDBC driver — the two
dependencies `llm4s-core` no longer carries. Package names are unchanged, so existing
`org.llm4s.agent.memory.*` imports keep working; see the
[migration note](../reference/migration.md#slice-2-llm4s-memory-and-llm4s-memory-postgres).

### For MCP (Model Context Protocol)

{: .note }
> Not yet published. `llm4s-mcp` exists in the build as of
> [#1130](https://github.com/llm4s/llm4s/issues/1130) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-mcp" % llm4sVersion
```

Carries the MCP client, server, transports (stdio, HTTP, SSE) and `MCPToolRegistry`. It adds no
third-party dependency of its own. Package names are unchanged, so existing `org.llm4s.mcp.*`
imports keep working; see the
[migration note](../reference/migration.md#slice-3-llm4s-mcp).

### For speech (STT / TTS)

{: .note }
> Not yet published. `llm4s-speech` exists in the build as of
> [#1130](https://github.com/llm4s/llm4s/issues/1130) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-speech" % llm4sVersion
```

Carries speech-to-text (Vosk for offline recognition, Whisper), text-to-speech (Tacotron 2),
and the audio IO, conversion and validation helpers. **This is the module that brings Vosk**,
a 25 MB dependency that used to sit on every `llm4s-core` user's classpath — which is much of
the point of the split. Package names are unchanged; see the
[migration note](../reference/migration.md#slice-3-llm4s-speech).

### For Ollama (local models)

{: .note }
> Not yet published. `llm4s-ollama` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier Ollama is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-ollama" % llm4sVersion
```

Carries the Ollama chat client, the Ollama embedding provider and Ollama model listing. It adds
no third-party dependency of its own. Adding it is all the registration there is: the module
declares itself to the provider registry, so `provider = "ollama"` and
`EMBEDDING_MODEL=ollama/<model>` resolve with no code change. Without it, both fail with an error
saying `ollama` is not registered and naming the providers that are. Package names are
unchanged; see the
[migration note](../reference/migration.md#slice-5-llm4s-ollama).

### For Gemini and Vertex AI

{: .note }
> Not yet published. `llm4s-gemini` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier Gemini and Vertex AI are still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-gemini" % llm4sVersion
```

Carries both of Google's chat providers: the Gemini API client and model listing, and the
Vertex AI client with its OAuth authentication. It adds no third-party dependency of its own -
Vertex AI authentication is implemented without a Google SDK. Adding it is all the registration
there is: the module declares itself to the provider registry, so `provider = "gemini"` (or
`"google"`) and `provider = "vertexai"` (or `"vertex"`) resolve with no code change. Without it,
they fail with an error saying the provider is not registered and naming the providers that
are. Package names are unchanged; see the
[migration note](../reference/migration.md#slice-5-llm4s-gemini).

### For Anthropic

{: .note }
> Not yet published. `llm4s-anthropic` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier Anthropic is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-anthropic" % llm4sVersion
```

Carries the Anthropic Claude chat client and Anthropic model listing. **This is the module that
brings the Anthropic Java SDK** (`com.anthropic:anthropic-java`), which used to sit on every
`llm4s-core` user's classpath. Adding it is all the registration there is: the module declares
itself to the provider registry, so `provider = "anthropic"` resolves with no code change.
Without it, it fails with an error saying the provider is not registered and naming the
providers that are. Package names are unchanged; see the
[migration note](../reference/migration.md#slice-5-llm4s-anthropic).

### For OpenAI, Azure OpenAI and Requesty

{: .note }
> Not yet published. `llm4s-openai` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier these providers are still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-openai" % llm4sVersion
```

Carries the three providers that share `OpenAIClient` - OpenAI, Azure OpenAI and Requesty - the
OpenAI embedding provider, and OpenAI and Requesty model listing. **This is the module that
brings OpenAI's Java SDK** (`com.openai:openai-java`, with OkHttp, Jackson and the Kotlin
standard library), which serves Azure OpenAI as well. It replaced Microsoft's deprecated
`com.azure:azure-ai-openai`, which used to sit on every `llm4s-core` user's classpath;
`llm4s-core` depends on no vendor SDK. Adding it is
all the registration there is: the module declares itself to the provider registry, so
`provider = "openai"`, `"azure"` and `"requesty"`, and `EMBEDDING_MODEL=openai/<model>`, resolve
with no code change. Without it, they fail with an error saying the provider is not registered
and naming the providers that are.

OpenRouter, DeepSeek and Z.ai speak the OpenAI wire format with no SDK, and are in
`llm4s-openai-compatible` (below), which this module depends on for `OpenAIConfig`.
`llm4s-rag`'s `RAGConfig.default` embeds with `openai`, so a RAG pipeline built from the default
needs this module too, or `.withEmbeddings(...)` naming a provider you do ship. Package names are
unchanged; see the [migration note](../reference/migration.md#slice-5-llm4s-openai).

### For DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and any OpenAI-compatible endpoint

{: .note }
> Not yet published. `llm4s-openai-compatible` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier DeepSeek, Z.ai, OpenRouter, Mistral and Cohere are still inside
> `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

One SDK-free client for the OpenAI `/chat/completions` API, and the providers that run on it:
DeepSeek (`provider = "deepseek"`), Z.ai (`"zai"`), OpenRouter (`"openrouter"`), Mistral
(`"mistral"`), Cohere (`"cohere"`, through Cohere's OpenAI-compatibility API), and a generic
`"openai-compatible"` provider for any other endpoint that speaks the API - Groq, Together,
Fireworks, xAI, NVIDIA NIM, OrcaRouter, a vLLM, LM Studio or llama.cpp server, an internal
gateway - configured with a `baseUrl` and a `model`, and no code. See
[OpenAI-compatible endpoints](../guide/providers.md#openai-compatible-endpoints) for a
configuration recipe per provider. The module
brings no dependency beyond `llm4s-core`, and adding it registers the providers. Package names
are unchanged; see the
[migration note](../reference/migration.md#slice-5-llm4s-openai-compatible), and for Mistral and
Cohere [this one](../reference/migration.md#slice-5-mistral-cohere-and-voyage-leave-core-core-ships-no-provider).

### For AWS Bedrock

{: .note }
> Not yet published. `llm4s-bedrock` exists in the build as of
> [#1008](https://github.com/llm4s/llm4s/issues/1008) but ships in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-bedrock" % llm4sVersion
```

The AWS Bedrock chat provider (`provider = "bedrock"`), over the Converse and ConverseStream APIs,
so one client reaches Claude, Llama, Mistral, Nova and Titan models. It brings the AWS SDK for
Java v2 `bedrockruntime` artifact (Apache-2.0, the same SDK release train `llm4s-rag` uses for S3);
`llm4s-core` gains no dependency. A section needs a `region` and a `model`:

```hocon
llm4s.providers.claude-bedrock {
  provider = "bedrock"
  model    = "anthropic.claude-3-5-sonnet-20241022-v2:0"
  region   = ${?AWS_REGION}
}
```

Credentials come from the AWS default credential chain (environment variables, `~/.aws`, an
instance or task role), or from a `profile`, or from explicit `accessKeyId` / `secretAccessKey`
(plus `sessionToken` for temporary credentials).

### For Voyage AI embeddings

{: .note }
> Not yet published. `llm4s-voyage` exists in the build as of
> [#1132](https://github.com/llm4s/llm4s/issues/1132) but ships in the next release;
> in `0.4.1` and earlier Voyage is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-voyage" % llm4sVersion
```

The Voyage AI embedding provider (`EMBEDDING_MODEL=voyage/<model>`), with its
`llm4s.embeddings.voyage` config block. It is the first community provider module under
`modules/providers/`, and brings no dependency beyond `llm4s-core`.

### For Jina AI embeddings

{: .note }
> Not yet published. `llm4s-jina` exists in the build as of
> [#1028](https://github.com/llm4s/llm4s/issues/1028) but ships in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-jina" % llm4sVersion
```

The Jina AI embedding provider (`EMBEDDING_MODEL=jina/<model>`), with its
`llm4s.embeddings.jina` config block and a typed `JinaTask` (`retrieval.query`,
`retrieval.passage`, ...). A community provider module under `modules/providers/`; it brings no
dependency beyond `llm4s-core`.

### For Cohere embeddings

{: .note }
> Not yet published. `llm4s-cohere` exists in the build but ships in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-cohere" % llm4sVersion
```

The Cohere embedding provider (`EMBEDDING_MODEL=cohere/<model>`), on Cohere's native `/v2/embed`,
with its `llm4s.embeddings.cohere` config block and a typed `CohereInputType` (`search_document`,
`search_query`, ...). Cohere chat is a different module: `llm4s-openai-compatible`. A community
provider module under `modules/providers/`; it brings no dependency beyond `llm4s-core`.
### For IBM watsonx.ai

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-watsonx" % llm4sVersion
```

The IBM watsonx.ai chat provider (`provider = "watsonx"`), brings no dependency beyond
`llm4s-core`. See [IBM watsonx.ai](../guide/providers.md#ibm-watsonxai).

### For Java, Spring Boot, cats-effect and ZIO

{: .note }
> Not yet published. These modules exist in the build as of
> [#934](https://github.com/llm4s/llm4s/issues/934), [#935](https://github.com/llm4s/llm4s/issues/935)
> and [#936](https://github.com/llm4s/llm4s/issues/936) but ship in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-java-api"             % llm4sVersion  // Java facade
libraryDependencies += "org.llm4s" %% "llm4s-spring-boot-starter"  % llm4sVersion  // Spring Boot auto-configuration
libraryDependencies += "org.llm4s" %% "llm4s-effect"               % llm4sVersion  // cats-effect 3 / fs2
libraryDependencies += "org.llm4s" %% "llm4s-zio"                  % llm4sVersion  // ZIO 2 / ZIO Streams
```

`llm4s-java-api` is the Java-friendly facade (a runnable Gradle sample is in
[`modules/samples/gradle-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java)) and the base of the Spring starter, which adds the
`llm4s.*` properties, an `LLM4STemplate` and an Actuator health indicator. `llm4s-effect` and
`llm4s-zio` wrap `LLMClient` and `Agent` for their effect systems. There is also a Kotlin coroutine
API, `modules/kotlin-api`, a separate Gradle build that is not published yet.

### For image generation and vision

{: .note }
> Not yet published. `llm4s-image` exists in the build as of
> [#1130](https://github.com/llm4s/llm4s/issues/1130) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-image" % llm4sVersion
```

Carries image generation (`org.llm4s.imagegeneration` — Stable Diffusion, Stability AI,
Hugging Face, OpenAI) and image processing (`org.llm4s.imageprocessing` — the OpenAI and
Anthropic vision clients plus a local `javax.imageio` processor). It adds no third-party
dependency of its own, and brings `llm4s-media` with it.

Package names are unchanged, so existing `org.llm4s.imagegeneration.*` and
`org.llm4s.imageprocessing.*` imports keep working — but note that image *formats* did change,
in `llm4s-media`. See the [migration note](../reference/migration.md#slice-3-llm4s-image).

### For media types (`MediaType`, `MediaCategory`)

{: .note }
> Not yet published. `llm4s-media` exists in the build as of
> [#1130](https://github.com/llm4s/llm4s/issues/1130) but ships in the next release.

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-media" % llm4sVersion
```

The shared vocabulary the multimodal modules speak: `MediaType` (MIME string, canonical
extension, category, and lookups by extension, path or MIME type) and `MediaCategory`. It has
no dependencies at all and does no I/O — deciding what a file is from its bytes needs Tika and
lives in `llm4s-rag`.

You will usually get it transitively, from `llm4s-core` or `llm4s-rag`; declare it directly
only if you name these types in your own signatures. It replaces three overlapping image-format
types that used to ship in `llm4s-core`, which is a source break — see the
[migration note](../reference/migration.md#slice-3-llm4s-media).

### For agents (`Agent`, guardrails, handoffs, orchestration)

{: .note }
> Not yet published. `llm4s-agent` exists in the build as of
> [#1242](https://github.com/llm4s/llm4s/issues/1242) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % llm4sVersion // same version as llm4s-core
```

The agent runtime - `org.llm4s.agent` (`Agent`, `AgentResult`, guardrails, handoffs,
orchestration) and the console assistant, `org.llm4s.assistant` - lives here.
Agent memory is the separate `llm4s-memory`; the ready-made tools are `llm4s-agent-tools`.
`llm4s-core` keeps what the agent is built on: `LLMClient`, the tool API and the tracing
contract. Package names are unchanged. See the
[migration note](../reference/migration.md#slice-7-llm4s-agent---the-agent-runtime-leaves-core).

### For built-in tools (web search, HTTP, filesystem, shell)

{: .note }
> Not yet published. `llm4s-agent-tools` exists in the build as of
> [#1242](https://github.com/llm4s/llm4s/issues/1242) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-tools" % llm4sVersion // same version as llm4s-core
```

`BuiltinTools` and everything under `org.llm4s.toolapi.builtin` - DateTime, Calculator, UUID,
JSON, file access, HTTP, shell, and the Brave, DuckDuckGo and Exa search tools - live here, with
`ToolsConfigLoader` and the `llm4s.tools` settings the search tools read. `llm4s-core` keeps the
tool API they implement (`ToolFunction`, `ToolRegistry`). Package names are unchanged. See the
[migration note](../reference/migration.md#slice-7-llm4s-agent-tools---the-built-in-tools-leave-core).

### For tracing and metrics (Langfuse, OpenTelemetry, in-process trace store, Prometheus)

{: .note }
> Not yet published. `llm4s-observability` and `llm4s-observability-prometheus` exist in the build as of
> [#1133](https://github.com/llm4s/llm4s/issues/1133) but ships in the next release;
> in `0.4.1` and earlier this code is still inside `llm4s-core`.

```scala
// same version as llm4s-core
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-observability"            % llm4sVersion, // TRACING_MODE=langfuse
  "org.llm4s" %% "llm4s-observability-otel"       % llm4sVersion, // TRACING_MODE=opentelemetry
  "org.llm4s" %% "llm4s-observability-prometheus" % llm4sVersion  // llm4s.metrics.enabled = true
)
```

`llm4s-core` builds only the `console` (default) and `noop` tracers. `llm4s-observability`
registers Langfuse and carries the in-process trace collector and store
(`TraceCollectorTracing`, `org.llm4s.trace.store`, `org.llm4s.trace.model`) and `CostTracker`,
with no third-party dependency; `llm4s-observability-otel` registers OpenTelemetry and brings the
OpenTelemetry SDK. Adding the dependency is all the registration there is, and the
`LANGFUSE_*` / `OTEL_*` variables keep their names. `llm4s-observability-prometheus` carries
`PrometheusMetrics`, the `/metrics` endpoint and `MetricsConfigLoader`, with the Prometheus client
and HTTP server; core keeps only the `MetricsCollector` interface every client accepts. Package
names are unchanged. See the migration notes for
[Langfuse and the collector](../reference/migration.md#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core)
and for [Prometheus](../reference/migration.md#slice-6-llm4s-observability-prometheus---prometheus-leaves-core).

### For Neo4j (knowledge graph store)

```scala
// same version as llm4s-core
libraryDependencies += "org.llm4s" %% "llm4s-knowledgegraph-neo4j" % llm4sVersion
```

`Neo4jGraphStore`, a graph store for `llm4s-knowledgegraph` backed by Neo4j. It depends on `llm4s-core`
and `llm4s-knowledgegraph` and brings the Neo4j driver.

### For provider authors (testing a provider module)

{: .note }
> Not yet published. `llm4s-provider-testkit` exists in the build as of
> [#1133](https://github.com/llm4s/llm4s/issues/1133) but ships in the next release.

```scala
// same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-provider-testkit" % llm4sVersion % Test
```

The checks a provider module's own `Llm4s<Name>ModuleSpec` makes: discovery, sole ownership of its ids,
explicit registration and the config-to-client round trip. See
[Writing a provider](../guide/writing-a-provider#testing).

### For Workspace (Containerized Execution)

```scala
libraryDependencies += "org.llm4s" %% "llm4s-workspace-client" % "{{ site.data.project.latest_release }}"
```

And install Docker:

```bash
# macOS
brew install docker

# Ubuntu/Debian
sudo apt-get install docker.io

# Verify
docker --version
```

`llm4s-workspace-client` brings `llm4s-workspace-shared`, the wire protocol the client and the runner image
speak, so you depend on it directly only if you implement the runner side.

---

## Provider Setup

llm4s reads provider configuration from named sections in your `application.conf`, not from
environment variables of its own. Keep secrets in environment variables and bind them into the
section with `${?VAR}`:

```hocon
# src/main/resources/application.conf
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

```bash
export OPENAI_API_KEY=sk-...
```

The section needs no `apiKey` line: `llm4s-openai` binds `OPENAI_API_KEY` to OpenAI's shared
key, which a section without a key of its own uses (see
[API keys](configuration#api-keys) for a second account). Nothing reads `LLM_MODEL`. The [Configuration guide](configuration#named-provider-sections)
shows sections for every provider, how to [switch between them](configuration#switching-providers),
and which [environment variables llm4s reads](configuration#environment-variables-llm4s-reads)
without any binding.

If you keep your variables in a `.env` file (add it to `.gitignore`!), load it before running:

```bash
source .env
sbt run
```

### Get API Keys

#### OpenAI

1. Go to [platform.openai.com](https://platform.openai.com)
2. Sign up or log in
3. Navigate to **API Keys**
4. Click **Create new secret key**
5. Copy the key (starts with `sk-`)

#### Anthropic

1. Go to [console.anthropic.com](https://console.anthropic.com)
2. Sign up or log in
3. Navigate to **API Keys**
4. Click **Create Key**
5. Copy the key (starts with `sk-ant-`)

#### Azure OpenAI

1. Create an Azure account
2. Navigate to **Azure OpenAI Service**
3. Create a resource
4. Deploy a model (e.g., gpt-4o)
5. Copy the **API Key** and **Endpoint**

#### Ollama (Local)

1. Install Ollama: [ollama.com](https://ollama.com)
2. Pull a model: `ollama pull llama2`
3. Start server: `ollama serve`
4. No API key needed!

---

## Verify Installation

Create a simple test file `VerifyInstall.scala`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

object VerifyInstall extends App {
  println("Testing LLM4S installation...")

  val result = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client   <- LLMConnect.getClient(providerConfig)
    response <- client.complete(Conversation(Seq(UserMessage("Say 'LLM4S is working!'"))))
  } yield response

  result match {
    case Right(completion) =>
      println("✅ Success!")
      println(s"Response: ${completion.content}")
    case Left(error) =>
      println("❌ Error:")
      println(error)
  }
}
```

Run it:

```bash
sbt run
```

Expected output:

```
Testing LLM4S installation...
✅ Success!
Response: LLM4S is working!
```

---

## Troubleshooting

### "missing required fields: apiKey"

**Problem**: the provider section has no API key.

**Solution**:
1. Check the vendor's variable is set in the shell that starts the JVM: `echo $OPENAI_API_KEY`
   (if you use a `.env` file, `source .env` first), and that the provider's module
   (`llm4s-openai`) is a dependency - it is what binds the variable
2. If the section sets its own `apiKey = ${?SOME_VAR}`, check that variable too
3. Check the error names the section you meant to load - only that section is validated, so
   if it names another, `llm4s.providers.provider` points somewhere you did not expect

### "Configured provider '...' was not found" or an unknown provider id

**Problem**: `llm4s.providers.provider` names a section that does not exist, or a section's
`provider = "..."` id is not registered.

**Solution**: make `llm4s.providers.provider` match a section name exactly, and add the module
for the provider id (for example `llm4s-openai` for `provider = "openai"`; see
[Optional Dependencies](#optional-dependencies)). Model names go in the section's `model`
without a provider prefix: `model = "gpt-4o"` for OpenAI, not `openai/gpt-4o`.

### Compilation Errors

**Problem**: Scala version mismatch.

**Solution**:
```bash
# Clean and recompile
sbt clean
sbt compile
```

### Dependency Resolution Issues

**Problem**: Can't resolve LLM4S dependency.

**Solution**:
- For release versions, no additional resolver needed (uses Maven Central)
- For snapshots, add the resolver:
```scala
resolvers += Resolver.sonatypeRepo("snapshots")
```

---

## Next Steps

Now that LLM4S is installed:

1. **[Write your first program →](first-example)** - Create a simple LLM application
2. **[Configure providers →](configuration)** - Set up multiple LLM providers
3. **[Explore examples →](/examples/)** - Browse 69 working examples

---

## Additional Resources

- **GitHub Repository**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)
- **Discord Community**: [Join us](https://discord.gg/4uvTPn6qww)
- **API Reference**: [Core API](/api/)

---

**Installation complete!** Ready to [write your first program →](first-example)
