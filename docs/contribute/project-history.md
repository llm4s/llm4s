---
layout: page
title: Project History and Design Notes
parent: Contribute
nav_order: 2
---

# Project history and design notes

These sections were in the repository README until it became a short landing page
([#2322](https://github.com/llm4s/llm4s/issues/2322)). They are kept here as they were: the module history, the
original overview, architecture and roadmap summary, and the longer notes on tool calling, tracing, configuration
loaders and the starter kit. The current user documentation is the [guide](/guide/) and
[getting started](/getting-started/); where a note below and a guide differ, the guide is right.

## Overview

LLM4S provides a simple, robust, and scalable framework for building LLM applications in Scala. While most LLM work is done in Python, we believe that Scala offers a fundamentally better foundation for building reliable, maintainable AI-powered applications.

<p align="center">
  <img src="/image_assets/llm4s-overview.jpeg" alt="LLM4S Overview" width="600"/>
  <br>
  <em></em>
</p>

<br>

## Current Status

LLM4S is under active pre-1.0 development. The latest published release is [v0.5.1](https://github.com/llm4s/llm4s/releases/tag/v0.5.1); [v0.5.0](https://github.com/llm4s/llm4s/releases/tag/v0.5.0) was the first with the split module artifacts. Both target Scala 3.7.1 and JDK 21. Its APIs are usable today but are still being stabilized ahead of the 1.0 compatibility commitment.

The framework already provides multi-provider clients, agents and tool calling, RAG and vector stores, memory, guardrails, tracing and metrics, reliability wrappers, workspace isolation, MCP support, and image and speech APIs.

Since 0.5.0, llm4s ships as separate modules. RAG, knowledge graph, memory, MCP, shared media types, image, and speech have been carved out of `llm4s-core` into focused modules with smaller dependency footprints, and providers register through an SPI. Provider clients have moved into modules of their own - `llm4s-ollama`, `llm4s-gemini`, `llm4s-anthropic`, `llm4s-openai`, `llm4s-openai-compatible` (DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and any OpenAI-compatible endpoint) and `llm4s-voyage` - and `llm4s-core` holds none. Langfuse tracing, the trace collector and store, and `CostTracker` are in `llm4s-observability`, and Prometheus metrics in `llm4s-observability-prometheus`, leaving `llm4s-core` with only the tracing and metrics contracts, console and no-op tracing, and no observability dependency. The agent runtime is in `llm4s-agent` and the built-in tools in `llm4s-agent-tools`, so `llm4s-core` is now a ~19k-line spine, and its API has been cleaned up ahead of the compatibility baseline, which is set at a pre-1.0 release once the designs are final ([#1281](https://github.com/llm4s/llm4s/issues/1281)). Upgrading from 0.4.x? Start with [Upgrading from 0.4.1 to 0.5.0](https://llm4s.org/migrations/0-4-1-to-0-5-0). See the [1.0 scope](https://llm4s.org/reference/v1-scope) and [migration guide](https://github.com/llm4s/llm4s/blob/main/docs/reference/migration.md) for module maturity and upgrade details.

The path to 1.0 is focused on stable API boundaries, provider capability parity and contract tests, Java/Kotlin/Spring/Gradle interoperability, security hardening, deterministic CI, production observability and cost controls, runnable documentation, and maintained reference applications. See the [roadmap](https://llm4s.org/reference/roadmap) for the full stabilization plan.

## Why Scala for LLMs?

- **Type Safety**: Catch errors at compile time, not in production.
- **Functional Programming**: Immutable data and pure functions for predictable, maintainable systems.
- **JVM Ecosystem**: Access to mature, production-grade libraries and tooling.
- **Concurrency**: Advanced models for safe, efficient parallelism.
- **Performance**: JVM speed with functional elegance.
- **Ecosystem Interoperability**: Seamless integration with enterprise JVM systems and cloud-native tooling.

Choosing between JVM libraries? [Comparing LLM4S](https://llm4s.org/guide/comparison) sets LLM4S beside LangChain4j,
Spring AI, Koog and Embabel, with a source and a date for every claim, and
[Migrating from LangChain4j](https://llm4s.org/guide/migrating-from-langchain4j) maps its concepts to LLM4S.

## Features

- **Multi-Provider Support**: Connect to multiple LLM providers, including OpenAI, Anthropic, Azure OpenAI, Google Gemini, DeepSeek, Cohere, Mistral, OpenRouter, Requesty, Z.ai, and Ollama.
- **Execution Environments**: Run LLM-driven operations in secure, containerized or non-containerized setups.
- **Error Handling**: Robust mechanisms to catch, log, and recover from failures gracefully.
- **MCP Support**: Model Context Protocol primitives for richer tool and context integration.
- **Agent Framework**: Build single or multi-agent workflows with standardized interfaces.
- **Multimodal Generation**: Support for text, image, voice, and other LLM modalities.
- **RAG (Retrieval-Augmented Generation)**: Built-in tools for search, embedding, retrieval workflows, and RAGAS evaluation with benchmarking harness.
- **Observability**: Detailed trace logging, monitoring, and analytics for debugging and performance insights.
- **Raw Provider Exchange Logging**: Persist request/response exchanges, including streaming responses for streaming-capable providers, to inspect raw provider payloads during development and debugging.

## Architecture

```text
        ┌───────────────────────────┐
        │    LLM4S API Layer        │
        └──────────┬────────────────┘
                   │
          Multi-Provider Connector
        (OpenAI | Anthropic | DeepSeek | ...)
                   │
         ┌─────────┴─────────┐
         │ Execution Manager │
         └─────────┬─────────┘
                   │
        ┌──────────┴──────────┐
        │   Agent Framework   │
        └──────────┬──────────┘
                   │
      ┌────────────┴────────────┐
      │  RAG Engine + Tooling   │
      └────────────┬────────────┘
                   │
     ┌─────────────┴─────────────┐
     │   Observability Layer     │
     └───────────────────────────┘

```

## Roadmap

Our goal is to build a production-grade, type-safe AI application framework for Scala and the JVM, with multi-provider, multimodal, agentic, RAG, observability, and governance capabilities.

### Detailed Roadmap

**For the current roadmap, see the [LLM4S Roadmap](https://llm4s.org/reference/roadmap).**

The roadmap covers:
- **Current Capability Map**: What exists today and what still needs hardening
- **Production Pillars**: Testing, API stability, provider parity, JVM adoption, security, performance, cost, documentation, and observability
- **2026 Stabilization Phases**: The path from broad pre-1.0 functionality to a production-ready v1.0
- **Reference Applications**: The maintained end-to-end apps needed before v1.0

### High-Level Goals

| Goal | Current state | Next focus |
|------|---------------|------------|
| Single API access to multiple LLM providers | Implemented across many providers | Capability matrix, contract tests, parity for streaming/tools/structured output/timeouts/cost |
| Tooling for LLM applications | Tool calling, built-in tools, RAG, retrieval, evaluation, tracing, metrics, and reliability primitives exist | Stable APIs, security policies, runnable docs, and production examples |
| Agentic framework | Single-agent workflows, handoffs, memory, guardrails, streaming events, async tools, reasoning modes, and serialization exist | API freeze, replay/debugging, maintained reference apps |
| RAG and retrieval | Vector stores, hybrid search, reranking, permission-aware RAG, RAGAS-style evaluation, and benchmarking exist | Cost/latency tracking, deployment patterns, compile-tested golden paths |
| JVM ecosystem adoption | Scala-first path is the main supported API | Java facade, Kotlin coroutine API, Spring Boot starter, Maven/Gradle examples |
| Stable v1.0 platform | Pre-1.0, API stabilizing | MiMa or equivalent compatibility checks, threat model, benchmarks, docs cleanup, release gates |

## Tool Calling

Tool calling is a critical integration - designed to work seamlessly with **multi-provider support** and **agent frameworks**.
We use ScalaMeta to auto-generate tool definitions, support dynamic mapping, and run in **secure execution environments**.

Tools can run:

- In **containerized sandboxes** for isolation and safety.
- In **multi-modal pipelines** where LLMs interact with text, images, and voice.
- With **observability hooks** for trace analysis.

### Tool Signature Generation

Using ScalaMeta to automatically generate tool definitions from Scala methods:

```scala
/** My tool does some funky things with a & b...
 * @param a The first thing
 * @param b The second thing
 */
def myTool(a: Int, b: String): ToolResponse = {
  // Implementation
}
```

ScalaMeta extracts method parameters, types, and documentation to generate OpenAI-compatible tool definitions.

### Tool Call Mapping

Mapping LLM tool call requests to actual method invocations through:

- Code generation
- Reflection-based approaches
- ScalaMeta-based parameter mapping

### Secure Execution

Tools run in a protected Docker container environment to prevent accidental system damage or data leakage.

## Comprehensive Tracing & Observability

Tracing isn’t just for debugging - it’s the backbone of understanding model behavior.LLM4S’s observability layer includes:

- Detailed token usage reporting
- Multi-backend trace output (console and none in `llm4s-core`; Langfuse from `llm4s-observability`, OpenTelemetry from `llm4s-observability-otel`)
- Agent state visualization
- Integration with monitoring dashboards

### Tracing Modes

Configure tracing behavior using the `TRACING_MODE` environment variable. Console output
(the default) and no tracing are built into `llm4s-core`; every other mode comes from a
backend module you add as a dependency:

```bash
# Send traces to Langfuse - needs "org.llm4s" %% "llm4s-observability"
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=pk-lf-your-key
LANGFUSE_SECRET_KEY=sk-lf-your-secret

# Print detailed traces to console with colors and token usage
TRACING_MODE=print

# Disable tracing completely
TRACING_MODE=none
```

### Basic Usage

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.{ ConsoleTracing, Tracing }

// Build the tracer llm4s.tracing.mode selects; fall back to the console tracer
val tracer: Tracing = Llm4sConfig
  .tracing()
  .flatMap(Tracing.fromSettings)
  .fold(_ => new ConsoleTracing(), identity)

// Trace events, completions, and token usage
tracer.traceEvent("Starting LLM operation")
tracer.traceCompletion(completion, completion.model) // prefer the model reported by the API
tracer.traceTokenUsage(tokenUsage, completion.model, "chat-completion")
// An agent built withTracing(tracer) ends each run with a TraceEvent.AgentRunEnded
```

### Usage using starter kit `llm4s.g8`

#### A carefully crafted starter kit to unlock the power of llm4s

> **Note:** The LLM4S template has moved to its own repository for better maintainability and independent versioning.

The llm4s.g8 starter kit helps you quickly create AI-powered applications using llm4s.
It is a starter kit for building AI-powered applications using [llm4s][llm4s] with improved SDK usability and developer ergonomics. You can now spin up a fully working scala project with a single `sbt` command.
The starter kit comes pre-configured with best practices, prompt execution examples, CI, formatting hooks, unit testing, documentation, and cross-platform support.

**Template Repository:** [github.com/llm4s/llm4s.g8](https://github.com/llm4s/llm4s.g8)

Using `sbt`, do:

```
sbt new llm4s/llm4s.g8 \
--name=<your.project.name> \
--package=<your.organization> \
--version=<your.project.version> \
--llm4s_version=0.5.1 \
--scala_version=3.7.1 \
--munit_version=1.1.1 \
--directory=<your.project.name> \
--force
```

to create new project.

Since 0.5.0 `llm4s-core` ships no provider, so the generated `build.sbt` needs `llm4s-core` and at least one provider
module at the same version. The template is being moved to 0.5.x
([llm4s.g8#6](https://github.com/llm4s/llm4s.g8/pull/6)), which adds `llm4s-ollama` and `llm4s-openai` itself; a
template from before that lists `llm4s-core` only (and defaults to llm4s 0.4.0 if you leave out `--llm4s_version`),
so add the provider you use, for example `llm4s-openai` at 0.5.1.

For more information about the template, including compatibility matrix and documentation, visit the [template repository](https://github.com/llm4s/llm4s.g8).
Use the comprehensive [documentation](https://github.com/llm4s/llm4s/blob/main/docs/llm4s-g8-starter-kit.md) to get started with the project using starter kit.

---

## Configuration: Unified Loaders

llm4s exposes a single configuration flow with sensible precedence:

- Precedence: `-D` system properties > `application.conf` (if your app provides it) > `reference.conf` defaults.
- Environment variables are read only where a `${?ENV}` substitution binds them: in a module's `reference.conf`
  (tracing, embeddings, tools, and each provider module's vendor key under `llm4s.credentials`) or in your own
  `application.conf`. No `.env` reader is required, and nothing reads `LLM_MODEL`.

Preferred typed entry points (PureConfig-backed via `Llm4sConfig`):

- Provider / model:
  - `Llm4sConfig.defaultProvider(): Result[ProviderConfig]` – the typed config of the section `llm4s.providers.provider` names.
  - `Llm4sConfig.provider(name: String): Result[ProviderConfig]` – the typed config of the section `llm4s.providers.<name>`.
  - `LLMConnect.getClient(config: ProviderConfig): Result[LLMClient]` – builds a client from a typed config (with a `given ModelRegistryService` from `Llm4sConfig.modelRegistryService()`).
- Tracing:
  - `Llm4sConfig.tracing(): Result[TracingSettings]` – returns typed tracing settings.
  - `Tracing.fromSettings(settings: TracingSettings): Result[Tracing]` – builds the tracer the mode selects; a missing backend is an error.
  - `Tracing.create(settings: TracingSettings): Tracing` – the same, falling back to `NoOpTracing` with an error logged.
- Embeddings:
  - `Llm4sConfig.embeddings(): Result[(String, EmbeddingProviderConfig)]` – returns `(provider, config)` with validation.
  - `EmbeddingClient.from(provider: String, cfg: EmbeddingProviderConfig): Result[EmbeddingClient]` – builds an embeddings client from typed config.

Recommended usage patterns:

- Model name for display: `Llm4sConfig.defaultProvider().map(_.model)` or prefer `completion.model` from API responses.
- Tracing:
  - `Llm4sConfig.tracing().flatMap(Tracing.fromSettings)`, or `.map(Tracing.create)` to fall back to no tracing.
- Workspace (samples): `WorkspaceConfigSupport.load()` to get `workspaceDir`, `imageName`, `hostPort`.
- Embeddings sample (samples): `EmbeddingUiSettings.loadFromEnv`, `EmbeddingTargets.loadFromEnv`, `EmbeddingQuery.loadFromEnv` (sample helpers backed by `Llm4sConfig`).

### Config Keys → Typed Settings

Use these loaders to convert flat keys and HOCON paths into typed, validated settings used by the code:

- LLM provider and model selection
  - Keys: `llm4s.providers.provider` (the default section's name) and `llm4s.providers.<name>.{provider, model, apiKey, baseUrl, ...}`.
    No environment variable is bound: bind your own with `${?VAR}` in `application.conf`
  - Type: `ProviderConfig` (with provider-specific subtypes)
  - Loader: `Llm4sConfig.defaultProvider()` or `Llm4sConfig.provider("name")` + `LLMConnect.getClient(...)`

- Tracing configuration
  - Keys: `llm4s.tracing.mode` | `TRACING_MODE` (`llm4s-core`); `llm4s.tracing.langfuse.*` | `LANGFUSE_URL`, `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_ENV`, `LANGFUSE_RELEASE`, `LANGFUSE_VERSION` (bound by `llm4s-observability`); `llm4s.tracing.opentelemetry.*` | `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT` (bound by `llm4s-observability-otel`)
  - Type: `TracingSettings` (the mode, plus the selected mode's block as `extras`); `LangfuseConfig` via `LangfuseConfigLoader.default()`
  - Loader: `Llm4sConfig.tracing()` → then `Tracing.fromSettings` or `Tracing.create`

- Workspace settings (samples)
  - Keys: `llm4s.workspace.dir` | `WORKSPACE_DIR`, `llm4s.workspace.image` | `WORKSPACE_IMAGE`, `llm4s.workspace.port` | `WORKSPACE_PORT`
  - Type: `WorkspaceSettings`
  - Loader: `WorkspaceConfigSupport.load()`

- Embeddings sample settings (samples only; bound under `llm4s.samples.embeddings` in the samples' `application.conf`)
  - Input paths: `EMBEDDING_INPUT_PATHS` or `EMBEDDING_INPUT_PATH` → `EmbeddingSampleConfig.inputs()` → `EmbeddingTargets.fromInputs(...)`
  - Query: `EMBEDDING_QUERY` → `EmbeddingSampleConfig.inputs()` (or `EmbeddingQuery.loadFromEnv()`) → `EmbeddingQuery`
  - Chunking: `CHUNK_SIZE`, `CHUNK_OVERLAP`, `CHUNKING_ENABLED` → `EmbeddingSampleConfig.chunking()`
  - UI knobs: `MAX_ROWS_PER_FILE`, `TOP_DIMS_PER_ROW`, `GLOBAL_TOPK`, `SHOW_GLOBAL_TOP`, `COLOR`, `TABLE_WIDTH` → `EmbeddingSampleConfig.ui()` → `EmbeddingUiSettings`
  - Non-text stubs: `ENABLE_EXPERIMENTAL_STUBS`, `LLM4S_EMBEDDINGS_LOCAL_{IMAGE,AUDIO,VIDEO}_MODEL` → `EmbeddingSampleConfig.experimentalStubs()`, `.localModels()`

- Embeddings: provider configuration
  - Key: `EMBEDDING_MODEL` / `llm4s.embeddings.model` as `provider/model` (or legacy `EMBEDDING_PROVIDER` / `llm4s.embeddings.provider`)
  - Supported providers: `openai` (`llm4s-openai`), `voyage` (`llm4s-voyage`), `ollama` (`llm4s-ollama`); each from its module once published
  - Type: `(String, EmbeddingProviderConfig)`
  - Loader: `Llm4sConfig.embeddings()`
  - Provider-specific keys:
    - **OpenAI**: `OPENAI_EMBEDDING_BASE_URL`, `OPENAI_EMBEDDING_MODEL`, and `OPENAI_API_KEY` - the same key
      OpenAI chat sections use
    - **Voyage**: `VOYAGE_EMBEDDING_BASE_URL`, `VOYAGE_EMBEDDING_MODEL`, `VOYAGE_API_KEY`
    - **Ollama** (local): `OLLAMA_EMBEDDING_BASE_URL` (default: `http://localhost:11434`), `OLLAMA_EMBEDDING_MODEL`

- Provider API keys and endpoints
  - Keys: `apiKey` and `baseUrl` inside each `llm4s.providers.<name>` section, plus the provider's own
    keys - `organization` (OpenAI, Requesty, OpenRouter), `endpoint` and `apiVersion` (Azure),
    `project` and `location` (Vertex AI). A section without `apiKey` uses its vendor's shared
    `llm4s.credentials.<provider>.apiKey`, which the provider module binds to `OPENAI_API_KEY`,
    `ANTHROPIC_API_KEY` and so on; a section's own `apiKey` wins
  - Type: concrete `ProviderConfig` (e.g., `OpenAIConfig`, `AnthropicConfig`, `AzureConfig`, `OllamaConfig`, `GeminiConfig`, `DeepSeekConfig`, `CohereConfig`)
  - Loader: `Llm4sConfig.defaultProvider()` or `Llm4sConfig.provider("name")`

Tracing

- Configure mode via `llm4s.tracing.mode` (default: `console`). Supported: `console` and `noop` (built into
  `llm4s-core`), `langfuse` (with `llm4s-observability`), `opentelemetry` (with `llm4s-observability-otel`),
  and any mode a `TracingBackend` on the classpath registers.
- Override with env: `TRACING_MODE=langfuse` (or system property `-Dllm4s.tracing.mode=langfuse`).
- Build tracers:
  - Checked: `Llm4sConfig.tracing().flatMap(Tracing.fromSettings)` → `Result[Tracing]`
  - Falling back to no tracing: `Llm4sConfig.tracing().map(Tracing.create)`
  - Low-level: `LangfuseConfigLoader.default().map(LangfuseTracing.from)` (`llm4s-observability`)

Example (no application.conf required):

```
sbt -Dllm4s.providers.provider=openai-main -Dllm4s.providers.openai-main.provider=openai -Dllm4s.providers.openai-main.model=gpt-4o -Dllm4s.providers.openai-main.apiKey=sk-... "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

Or with an `openai-main` section (`provider = "openai"` and a `model`) in
`modules/samples/src/main/resources/application.local.conf` and the samples' `LLM4S_PROVIDER` binding:

```
export LLM4S_PROVIDER=openai-main
export OPENAI_API_KEY=sk-...
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```


[llm4s]: https://github.com/llm4s/llm4s
[Scala 3]: https://dotty.epfl.ch/
