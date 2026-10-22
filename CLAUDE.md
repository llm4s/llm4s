# CLAUDE.md - AI Assistant Guide for LLM4S

## Project Overview

**LLM4S** (Large Language Models for Scala) is a framework for building LLM-powered applications in Scala with:
- Multi-provider support (OpenAI, Anthropic, Azure, Ollama, Google Gemini, IBM watsonx.ai)
- Type-safe design with `Result[A]` error handling
- Agent framework with tools, guardrails, handoffs, and memory
- Scala 3 only (3.7.1). Scala 2.13 support is deferred to post-1.0 — see [#1126](https://github.com/llm4s/llm4s/issues/1126)

**Tech Stack:** Scala 3.7.1, JDK 21, SBT, ScalaTest, Cats, uPickle, Docker

## Core Principles

1. **Use `Result[A]` instead of exceptions** - `type Result[+A] = Either[LLMError, A]`
2. **Use `Llm4sConfig` at the app edge** - Never use `sys.env`, `System.getenv`, or `ConfigSource.default` directly in core code
3. **Use type-safe newtypes** - `ModelName`, `ApiKey`, `ConversationId` etc.
4. **Scala 3 idioms are welcome** - `opaque type`, `using` clauses, `enum` and `extension` are all in use. Do not rewrite them to a Scala 2.13-compatible subset; see [#1127](https://github.com/llm4s/llm4s/issues/1127)

## Status

The modularisation of `modules/core` ([#1126](https://github.com/llm4s/llm4s/issues/1126)) is done; slice 8 ([#1281](https://github.com/llm4s/llm4s/issues/1281)) is the 0.5.0 release (published, no MiMa baseline), a MiMa baseline at a pre-1.0 release once the designs are final, then 1.0. **Nothing is frozen until that baseline is set.** "Frozen module" below means a module in 1.0 Scope's *Frozen at 1.0* tier (those calling `mimaFrozen` in `build.sbt`); it is a target, not a constraint. Never argue for or against a design from "frozen", "the baseline" or "can be added later without breaking": fix a bad API outright, record the break in the CHANGELOG and migration guide, and add no shim or `@deprecated` overload. Coverage floors are in [#1127](https://github.com/llm4s/llm4s/issues/1127); they ratchet upward and are never lowered.

## Architecture rules

**Core is the spine** (`types`, `error`, `config`, `model`, `toolapi`, `context`, `llmconnect`, the `trace`/`metrics` contracts, `util`, `http`, `reliability`, `core/safety`, `security`, `resource`, `syntax`, `identity`) on cats, upickle, slf4j-api, Typesafe Config, pureconfig and jtokkit only.
- No vendor SDK (`com.anthropic`, `com.azure`, `com.openai`), observability, JDBC, parsing or speech dependency in core. `commonSettings` carries no JDBC driver: declare one per module. The build has no third-party resolvers; think hard before adding one.
- Nothing in core imports `org.llm4s.agent` or `org.llm4s.assistant`. Core's trace specs build `TraceEvent.AgentRunEnded` directly.
- A utility with one consuming module lives in that module, not core. A helper only llm4s modules use is `private[llm4s]`.
- Moving code between modules keeps its `org.llm4s.*` package, and its tests, `reference.conf` keys and `modules/it` suite move with it.

**Providers.** A provider is a `ProviderDescriptor` (or `EmbeddingProviderDescriptor`), never an edit to shared files: list it in an `Llm4sProviderModule` declared in `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule`, a `class` with a public no-arg constructor, never an `object`. `ProviderRegistry.of` / `.ofModules` / `.withProvider` do explicit registration.
- **Core ships no provider, no provider list and no main services entry.** Never add one back.
- Each provider module proves itself in an `Llm4s<Name>ModuleSpec` built on `llm4s-provider-testkit`'s `ProviderModuleChecks`: discovery, sole supplier, explicit registration, a config-to-client round trip per descriptor, plus `assertRefusesForeignConfig`, `assertStreams` and `assertCredentialBindings` where they apply.
- **OpenAI-compatible** (`/chat/completions`): first check whether the generic `openai-compatible` provider covers it with config alone; otherwise a dialect (`OpenAICompatibleDialect`) and descriptor in `modules/openai-compatible`, as Mistral and Cohere are. Never another copy of the client. `OpenAIConfig` lives there; `llm4s-openai` depends on it, never the reverse.
- **Anything else**: `modules/providers/<name>`, artifact `llm4s-<name>`, depending only on core. `modules/providers/voyage` is the template, `modules/ollama` the chat plus embeddings example.
- A streamed tool call's continuation deltas carry only `index`; a client must give each its call's id (`OpenAICompatibleClient.StreamToolCalls`), because `StreamingAccumulator` keys calls by id.
- Vendor model rules live in the vendor's module, layered on with `RequestTransformer.adjusted` (`OpenAIModelRules`). The provider-author SPI is public: `docs/guide/writing-a-provider.md`.
- A field only some providers read is that provider's declared extra, never a field of `NamedProviderConfig`. `BuiltinKeys` is `provider`, `model`, `baseUrl`, `apiKey`, `headers`, `timeouts` and `auth`.
- Core tests never use a real provider: use `org.llm4s.testutil.FixtureChatProvider` (id `fixturechat`) and `FixtureEmbeddingProvider` (id `fixtureembedding`), registered by core's **test** `META-INF/services` and visible to every module depending on `core % "test->test"`.
- Test-only dependencies stay test-only: `rag` on `openai`, `image` on `observability-prometheus`.

**Tracing and metrics.** Core keeps only the contract (`Tracing`, `TraceEvent`, `TracingComposer`, `TracingMode` = `Console` | `NoOp` | `Named`, `TracingSettings(mode, extras)`, `MetricsCollector`) and registers no backend. A backend is an `org.llm4s.trace.spi.TracingBackend` (`class`, in `META-INF/services`) that parses its own `llm4s.tracing.<mode>` block from `TracingSettings.extras`. Its module ships that block, the services entry and a `<Name>TracingBackendSpec`. Never add a `TracingMode` case object, a backend field on `TracingSettings` or a backend's `reference.conf` block to core.

**Other placement rules.**
- `llm4s-agent-tools` depends on core only, never on the agent runtime.
- `org.llm4s.media` has no I/O, no content sniffing and no third-party dependency.
- `org.llm4s.vectorstore.PostgresVectorHelpers` stays in core because `llm4s-rag` and `llm4s-memory-postgres` must not depend on each other.

## API rules (frozen modules)

- No speculative public types and no `@deprecated` members: delete, with a migration note.
- A new top-level public type needs `@Stable` (or `@Experimental` for Beta) from `org.llm4s.annotation`; `sbt stabilityTierCheck` enforces it. A companion object is covered by its class.
- Public signatures expose no third-party type (fansi, cats type classes) and no implicit that a wildcard import would pull in.
- **Growth-prone data types** (`CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`, `ModelCapabilities`, `ModelMetadata`, `ProviderConfigSpec`, `EmbeddingConfigSpec`, `ProviderFeatures`, `NamedProviderConfig`, `ReliabilityConfig`, `CircuitBreakerConfig`, `RateLimitConfig`, `ContextConfig`, and any new one that may grow) are `final case class X private (...)` with a companion `apply` carrying the defaults and `with*` setters (an `Option` field's setter takes the value or an `Option`). `.copy` is private and stays private.
  - To add a field: add it to the constructor and, with a default, to `apply`; keep the previous `apply` as an overload without defaults that forwards; add `withX`.
  - A type with an upickle `macroRW` keeps its constructor defaults, because the reader fills missing keys from them.
- **Durations are `FiniteDuration`, points in time `Instant`.** Never a raw `Int`/`Long` with the unit in its name (`timeoutMs`), and never a plain `Duration`, which admits `Duration.Inf`.
  - Wire formats keep their units: convert at the boundary (`.toMillis`; `DurationJson.millisRW` or `WireDurations` for a `macroRW`, with `@upickle.implicits.key` pinning a renamed field).
  - Round up with `DurationRounding` where an API reads `0` as "no timeout".
  - `s"${d}ms"` prints `150 millisecondsms`: use `d.toMillis`.

## Adding a module

Each step below is easy to miss because a missing one does not fail the build.
1. Add a coverage floor (or an explicit disabled policy), a codecov flag and a CI upload in the same commit.
2. Add a migration note in the CHANGELOG and docs.
3. Add the module to the `docs` project's source list in `build.sbt`. Otherwise its Scaladoc is silently never generated.
4. Register it in `hooks/pre-commit`.
5. If it has an integration suite that needs a database, container, model server or API key, put the suite in `modules/it` with exactly one tier tag from `org.llm4s.it.tags`; `sbt it/itTierCheck` enforces this. A store that needs none, such as SQLite, runs its suites in its own module.
6. A provider module also goes into the root aggregate, `samples`, `it`, and `configPolicy` if it has chat providers.

## Repository Structure

```
llm4s/
├── modules/
│   ├── core/                  # Core library (published)
│   ├── rag/                   # RAG, vector stores, chunking, reranking, extraction (published)
│   ├── knowledgegraph/        # Knowledge graph model, storage, query (published)
│   ├── memory/                # Agent memory: managers, in-memory + SQLite stores (published)
│   ├── memory-postgres/       # Agent memory: Postgres/pgvector store (published)
│   ├── mcp/                   # Model Context Protocol client, server, transports (published)
│   ├── media/                 # Shared media vocabulary: MediaType, MediaCategory (published)
│   ├── image/                 # Image generation and vision/processing clients (published)
│   ├── speech/                # Speech-to-text and text-to-speech (published)
│   ├── ollama/                # Ollama chat + embedding provider (published)
│   ├── gemini/                # Gemini API + Vertex AI chat providers (published)
│   ├── anthropic/             # Anthropic Claude chat provider + Anthropic SDK (published)
│   ├── openai/                # OpenAI, Azure, Requesty chat + OpenAI embeddings + openai-java SDK (published)
│   ├── openai-compatible/     # One SDK-free chat-completions client: DeepSeek, Z.ai, OpenRouter, Mistral, Cohere, xAI, generic (published)
│   ├── providers/             # Community provider modules, one `llm4s-<name>` each (published)
│   │   ├── voyage/            # Voyage AI embedding provider
│   │   ├── bedrock/           # AWS Bedrock chat provider + AWS SDK bedrockruntime (Converse, ConverseStream)
│   │   ├── jina/              # Jina AI embedding provider (typed JinaTask)
│   │   ├── jev/               # Jev decision client (TypeSafe System One: typed Noul/Choice/Score questions); not an LLMClient
│   │   ├── cohere/            # Cohere embedding provider, native /v2/embed (typed CohereInputType)
│   │   └── watsonx/           # IBM watsonx.ai chat provider (Beta; never run against the live service)
│   ├── provider-testkit/      # Checks for a provider module's Llm4s<Name>ModuleSpec, for external authors too (published)
│   ├── schema-derivation/     # SchemaOf: a JSON schema derived from a case class (`derives`), for completeStructured (published, experimental)
│   ├── testkit/               # llm4s-testkit: scriptable fake LLMClient for users' own tests (published)
│   ├── observability/         # Langfuse tracing backend, trace collector/model/store, CostTracker (published)
│   ├── observability-prometheus/ # Prometheus MetricsCollector + /metrics endpoint + Prometheus client (published)
│   ├── observability-micrometer/ # Micrometer MetricsCollector writing into a MeterRegistry (published)
│   ├── agent/                 # Agent runtime: Agent, graph runtime, guardrails, handoffs, streaming; assistant (published)
│   ├── agent-tools/           # Built-in tools: core utilities, filesystem, HTTP, shell, web search (published)
│   ├── agent-testkit/         # CheckpointerContract: the suite every Checkpointer store must pass (published)
│   ├── agent-checkpoint-sqlite/ # SqliteCheckpointer: the first durable Checkpointer, one SQLite file (published)
│   ├── agent-checkpoint-postgres/ # PostgresCheckpointer: threads shared across machines, the database's clock (published)
│   ├── agent-memory/          # SemanticMemoryMiddleware: per-tenant recall and replay-safe recording on llm4s-memory (published)
│   ├── bom/                   # llm4s-bom: POM-only bill of materials pinning every published artifact, generated (published)
│   ├── llm4s-effect/          # cats-effect IO / fs2 wrappers over LLMClient and Agent (published)
│   ├── llm4s-zio/             # ZIO 2 / ZIO Streams wrappers over LLMClient and Agent (published)
│   ├── llm4s-pekko/           # Apache Pekko Streams Sources over LLMClient.streamComplete and Agent runs (published)
│   ├── java-api/              # Java facade over the client and agent (published)
│   ├── java-rag/              # Java facade for RAG over llm4s-rag: JRag (published)
│   ├── reactor/               # Reactor Flux over the Java facade's streamed reply: Streams.toFlux (published)
│   ├── spring-boot-starter/   # Spring Boot auto-configuration on java-api (published)
│   ├── kotlin-api/            # Kotlin coroutine API: a separate Gradle build, published from 0.5.2
│   ├── samples/               # Usage examples
│   ├── deploy-service/        # /health and /llm-check service + image for the staged-deployment template (unpublished)
│   ├── workspace/             # Containerized execution
│   ├── config-policy/         # Config policy checks + CLI
│   ├── knowledgegraph-neo4j/  # Neo4j graph store
│   ├── trace-opentelemetry/   # OpenTelemetry tracing backend, `llm4s-observability-otel` (published)
│   ├── benchmarks/            # JMH benchmarks
│   ├── gradle-demo/           # Gradle consumer of llm4s, kept buildable (not published)
│   └── it/                    # Integration tests
├── docs/                # Documentation
├── project/             # SBT config
└── build.sbt
```

**Key paths in `modules/core/src/main/scala/org/llm4s/`:**
- `types/` - Result type, newtypes
- `config/` - Llm4sConfig + typed loaders
- `llmconnect/` - LLM client and providers
- `toolapi/` - Tool calling API (the built-in tools are in `modules/agent-tools`)
- `trace/` - Tracing contract: `Tracing`, `TraceEvent`, `TracingMode`, the `TracingBackend` SPI,
  Console/NoOp (backends live in `modules/observability` and `modules/trace-opentelemetry`)

The agent runtime (`org.llm4s.agent`, `org.llm4s.assistant`) is in `modules/agent/src/main/scala/org/llm4s/`.

## Common Commands

```bash
sbt buildAll           # Clean, compile, test
sbt test               # Run tests
sbt scalafmtAll        # Format code
sbt cov                # Run coverage
sbt frozenDependencyCheck # no frozen module resolves a parsing, speech, cloud-storage, database, backend or foreign-SDK dependency
sbt testIntegration    # modules/it @Docker tier (Postgres/pgvector, Qdrant, Neo4j)
sbt testWorkspace      # modules/it @Workspace tier (needs a built workspace-runner image)
sbt testOllama         # modules/it @Ollama tier
sbt testSmoke          # modules/it @Cloud tier (live API keys)
sbt testPerformance    # tests tagged PerformanceTest (LinearTime scaling checks); sbt test and coverage leave them out
sbt testPlatform       # only the platform-sensitive tests (project/PlatformTests.scala): what CI's Windows job runs
sbt it/itTierCheck     # every suite in modules/it must declare exactly one tier
sbt stabilityTierCheck # every top-level public type of a frozen module is @Stable or @Experimental
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

## Reading files and output efficiently

Every token read stays in context for the rest of the session, so read narrowly.
- **Locate, then read a range.** `grep -n` first, then `sed -n 'a,bp'` or Read with `offset`/`limit`. Never print all of `CHANGELOG.md`, `build.sbt` (1.6k), `docs/reference/migration.md` or a design document.
- **Don't re-read** a file already read in this session unless it has changed since. Keep the line numbers from the first read.
- **CHANGELOG.md: never read it whole.** `[Unreleased]` is the net change since the last release, organised by area; `grep -n '^## \|^### ' CHANGELOG.md | head -20` lists the areas. Grep the area heading and the issue number, then Edit.
  - Add an entry to its area's list, starting **Added**, **Changed**, **Removed** or **Fixed**. Never create a duplicate heading. A breaking change also gets one line under `### Breaking changes`.
  - An entry is 1-2 lines: the user-visible change plus the issue and design-record links. Detail goes in the design records and `docs/reference/migration.md`.
  - A fix or follow-up to something not yet released edits that feature's existing entry (grep its issue number) or adds nothing. It never adds a new entry.
- **Typed agent runtime design:** `docs/design/typed-agent-runtime-design.md` holds the model and roadmap, with one file per slice (§4.2 onwards) under `docs/design/typed-agent-runtime/`. Open only the § you need.
- **Filter command output at the source:**
  - sbt: `... 2>&1 | tail -40`, or `grep -E '\[error\]|\*\*\* FAILED|Tests: |Failed tests'`.
  - gh: `gh pr view N --json title,state,mergeable,statusCheckRollup`, `gh run view ID --log-failed | tail -80`.
  - git: `git diff --stat` before any full diff, then diff file by file.
- **A large output saved to a file:** `grep` or `tail` it. Never Read the whole file back.
- **Subagents get this file automatically.** Don't tell them to read CLAUDE.md; point them at the section, issue or files they need.

## Commits

**Every commit needs a `Signed-off-by` trailer** - commit with `git commit -s`. This is the
[Developer Certificate of Origin](https://developercertificate.org/): the trailer certifies the
committer has the right to submit the code under the project's MIT licence, so it must name a
real person and cannot be added on someone else's behalf. `.github/workflows/dco.yml` and the DCO
app both check it, and both fail the PR over a single commit that lacks it - including a commit
appended to a branch whose earlier commits have it.

Fixing an unsigned commit rewrites history, so it costs a force-push: `git commit --amend -s`
for the most recent one, `git rebase --signoff main` for a branch of them, then
`git push --force-with-lease`. Signing as you go is cheaper than either. See
[CONTRIBUTING.md](CONTRIBUTING.md#developer-certificate-of-origin-dco).

## Configuration and Environment Variables

**Nothing in the library reads `LLM_MODEL`** (removed with legacy single-provider loading in #903).
Chat providers are named sections in the application's `application.conf`;
`llm4s.providers.provider` names the default that `Llm4sConfig.defaultProvider()` loads.
**Credentials belong to a vendor, keyed by provider id; clients belong to a use.** A client's key
is its own `apiKey` (chat section `llm4s.providers.<name>`, embeddings block
`llm4s.embeddings.<id>`, reranker `llm4s.rerank.cohere`), else `llm4s.credentials.<id>.apiKey`,
which each provider module's own `reference.conf` binds to the vendor's conventional variable,
else an error naming both. So with `OPENAI_API_KEY` set a section needs only `provider` and
`model`:

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

- Precedence: `-D` system properties > `application.conf` > each module's `reference.conf`.
  Environment variables are read only through `${?VAR}`.
- The fallback is generic, by canonical provider id (aliases such as `google` use `gemini`'s),
  inside `org.llm4s.config` (`SharedCredentials`); the reranker's is `RerankerConfigLoader` in
  `llm4s-rag`. A provider module declares the variable(s) it binds as `apiKeyEnv` on its
  `ProviderConfigSpec` / `EmbeddingConfigSpec`, used only in the missing-key message; never
  hard-code a variable name in core. `credentials` holds `apiKey` only. The source path is
  logged at INFO, never the value. No block for `openai-compatible` (no vendor), `ollama` (no
  key) or `vertexai` (OAuth2). A section for a second account sets its own `apiKey`; the
  config-policy `prod` preset flags sections that do not (`ownApiKey`).
- **Only the section being loaded is validated** (`ProviderSections.validated`): a section with
  no key available, or whose provider module is absent, fails only a load of that section.
  `Llm4sConfig.providers()` is the exception - it returns every section, so validates them all;
  `providerConfigs()` reports each section's error separately.
- Samples: `modules/samples/src/main/resources/application.conf` defaults to `ollama-local`
  (model `llama3:latest` - `ollama pull llama3` first, or set `OLLAMA_MODEL`) and
  binds `LLM4S_PROVIDER`, `OLLAMA_MODEL` and `OLLAMA_BASE_URL` - the samples' bindings, not the
  library's. Add other sections in the git-ignored `application.local.conf` beside it.
- Things that read `LLM_MODEL` themselves: the chat-tui sample (`ChatTuiConfig`) and the
  config-policy env check (`EnvCheckPolicies`). The `modules/it` `@Cloud` smoke suites read their
  API keys directly.
- `DocumentedProviderConfigSpec` (`modules/openai`) loads the documented config; keep the docs and
  it in step.

Variables that *are* bound by some module's `reference.conf`:

```bash
# Tracing mode (llm4s-core): console (default), none, or a TracingBackend's mode from its module
TRACING_MODE=langfuse                # langfuse (llm4s-observability), opentelemetry | otel (llm4s-observability-otel)
# Langfuse (llm4s-observability) -> llm4s.tracing.langfuse.*; also LANGFUSE_URL, _ENV, _RELEASE, _VERSION
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
# OpenTelemetry (llm4s-observability-otel) -> llm4s.tracing.opentelemetry.*
OTEL_SERVICE_NAME=llm4s-agent        # OTLP headers: llm4s.tracing.opentelemetry.headers, not OTEL_EXPORTER_OTLP_HEADERS
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Vendor keys -> llm4s.credentials.<id>.apiKey (chat sections, embeddings and the reranker share them)
OPENAI_API_KEY=sk-...                # llm4s-openai (openai); also REQUESTY_API_KEY, AZURE_OPENAI_API_KEY
ANTHROPIC_API_KEY=sk-ant-...         # llm4s-anthropic
GOOGLE_API_KEY=...                   # llm4s-gemini (gemini); GEMINI_API_KEY when GOOGLE_API_KEY is unset
DEEPSEEK_API_KEY=...                 # llm4s-openai-compatible; also ZAI_API_KEY, OPENROUTER_API_KEY, MISTRAL_API_KEY, XAI_API_KEY
COHERE_API_KEY=...                   # llm4s-openai-compatible, llm4s-rag and llm4s-cohere (Cohere chat, reranker, embeddings)
VOYAGE_API_KEY=pa-...                # llm4s-voyage
TYPESAFE_API_KEY=...                 # llm4s-jev (Jev decision client, not a provider) -> llm4s.credentials.typesafe.apiKey

# Embeddings (llm4s-core selects; each provider module binds its own block)
EMBEDDING_MODEL=openai/text-embedding-3-small  # provider/model
# OPENAI_EMBEDDING_BASE_URL / VOYAGE_EMBEDDING_BASE_URL / OLLAMA_EMBEDDING_BASE_URL override base URLs

# Speech (llm4s-speech): cloud TTS/STT, "provider/model" like LLM_MODEL
SPEECH_TTS_MODEL=openai/tts-1                  # or openai/tts-1-hd, elevenlabs/<voice-id>, azure/<voice-name>
SPEECH_STT_MODEL=openai/whisper-1              # or azure/en-US
# SPEECH_TTS_VOICE=alloy                       # optional voice override
# Credentials, only for the selected provider: OPENAI_API_KEY, ELEVENLABS_API_KEY,
# AZURE_SPEECH_KEY + AZURE_SPEECH_REGION (see modules/speech reference.conf, docs/guide/speech.md)
```

The full list is in `docs/getting-started/configuration.md#environment-variables-llm4s-reads`.

## Code Conventions

### Error Handling

```scala
// GOOD - Return Result
def loadProviderConfig(): Result[ProviderConfig] = Llm4sConfig.defaultProvider()

// BAD - Don't throw
def parseConfig(): Config = throw new RuntimeException()

// Convert Try to Result
import org.llm4s.types.TryOps
Try("123".toInt).toResult
```

### Configuration

```scala
// GOOD - the section llm4s.providers.provider names, from application.conf
val provider: Result[ProviderConfig] = Llm4sConfig.defaultProvider()
// or a specific named section:
val named: Result[ProviderConfig] = Llm4sConfig.provider("openai-main")

// BAD - llm4s-openai already binds OPENAI_API_KEY to llm4s.credentials.openai.apiKey
val apiKey = sys.env.get("OPENAI_API_KEY")
```

### Naming

- Types: `PascalCase` (`LLMClient`, `CompletionResponse`)
- Values/functions: `camelCase` (`apiKey`, `createClient`)
- Constants: `SCREAMING_SNAKE_CASE` (`DEFAULT_TIMEOUT`)

### Scalafix Rules

**Banned patterns** (enforced via `.scalafix.conf`):
- `ConfigFactory.load()`, `sys.env()`, `System.getenv()` - use `Llm4sConfig` in app/test code
- `try/catch/finally` outside safety packages - use `Result`
- Infix operators - use `list.map(f)` not `list map f`

## Agent Framework

### Basic Agent Usage

An `Agent` is built once, with its tools, guardrails, handoffs and middleware, and run by `ThreadId`.

```scala
for {
  providerConfig  <- Llm4sConfig.defaultProvider()   // llm4s.providers.<default> in application.conf
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
  agent  <- Agent.builder("assistant", client)       // the id is an AgentId
              .withTools(new ToolRegistry(Seq(myTool)))
              .withSystemPrompt("You are a helpful assistant")
              .build()
  result <- agent.run("Query here")                  // Result[AgentResult]
} yield result.answer                                // Some(answer) when status is Completed
```

`AgentResult` carries `threadId`, `runId`, `activeAgent`, `status`, `messages` and `usage`.
`AgentStatus` is `Completed(answer)`, `Blocked(guardrail, reason)`, `StepLimitReached` or
`Suspended(approvals, questions)`; a guardrail block is an outcome, not a `Left`. Provider, tool and
middleware failures are `Left(GraphError...)`. `agent.start(...)` returns an `AgentRun` with
`await()` and `cancel()`; `recover(threadId)` and `resume(threadId, answers)` continue a thread
that failed or suspended.

### Multi-Turn Conversations

```scala
for {
  result1 <- agent.run("First query")
  result2 <- agent.continueConversation(result1, "Follow-up")   // same thread: run(result1.threadId, ...)
} yield result2
```

To restore a saved conversation, `agent.run(ThreadId("saved-1"), query, RunConfig(), history = saved)`
imports the messages into a new thread (no system messages in `history`).

### Built-in Tools

In `llm4s-agent-tools` (`modules/agent-tools`), which depends on core only - not on the agent
runtime. Search tool config comes from `ToolsConfigLoader.load*SearchTool()`.

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

BuiltinTools.core          // DateTime, Calculator, UUID, JSON
BuiltinTools.safe()        // + web search, HTTP
BuiltinTools.withFiles()   // + read-only file access
BuiltinTools.development() // All tools (use with caution)
```

### Guardrails

Guardrails are middleware on the agent, not per-run arguments.

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._

val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new GuardrailMiddleware(
      input = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
      output = Seq(new JSONValidator())
    )
  )
  .build()
```

A block ends the run `AgentStatus.Blocked(guardrail, reason)`; an output block replaces the stored
answer with a refusal. `new ContextWindowMiddleware(config)` prunes what is sent to the model, never
what the thread stores.

Built-in guardrails:
- **Simple validators**: `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`
- **LLM-as-Judge**: `LLMSafetyGuardrail`, `LLMFactualityGuardrail`, `LLMQualityGuardrail`, `LLMToneGuardrail`
- **Composition**: `CompositeGuardrail.all()`, `CompositeGuardrail.any()`, `CompositeGuardrail.sequential()`

### Handoffs

Handoffs are routes inside one graph, by agent id; the handoff id must equal the target builder's id.

```scala
import org.llm4s.agent.Handoff

val physics = Agent.builder("physics", client).withSystemPrompt("You are a physicist")
val agent = Agent.builder("triage", client)
  .withHandoffs(Handoff.to("physics", physics, "Physics expertise required"))
  .build()
// A cycle back to the root: physics.withHandoffs(Handoff.toId("triage", "Not a physics question"))
```

A handoff must be the only tool call in its message. Use handoffs for simple 2-3 agent
delegation. Use a graph (`GraphBuilder`; the `multi-agent-graph` cookbook recipe) for complex parallel workflows.

### Memory

```scala
import org.llm4s.agent.memory._

val manager = SimpleMemoryManager.empty
for {
  m1 <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
  context <- m1.getRelevantContext("Tell me about Scala")
} yield context
```

### Reasoning Modes

```scala
val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .withMaxTokens(4096)

client.complete(conversation, options)
```

### Streaming Events

`AgentBuilder.withStreaming()` streams the model's answer; `agent.stream(threadId, query)(listener)`
delivers every event of the turn as a `StreamEvent`, matched with `AgentEvents` (`TextDelta`,
`ToolCallStarted`, `ToolExecuted`, `ModelCallCompleted`, ...). Durable events carry no message
content; content is live-only. `AgentRun.subscribe` is run-scoped; `AgentIO.stream`/`AgentZ.stream`
wrap it as fs2/ZIO streams. Tracing ends each run with `TraceEvent.AgentRunEnded` (#1329).

## Testing

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MySpec extends AnyFlatSpec with Matchers {
  "Component" should "return success" in {
    MyComponent.process("valid") shouldBe Right(expected)
  }
}
```

**Best practices:** Deterministic, fast (use mocks), isolated, target 80%+ coverage.

A test that measures how cost grows with input size (`LinearTime.assertLinear`, or any timing
ratio) is tagged `taggedAs(PerformanceTest)` (`org.llm4s.testutil`). `sbt test` and the coverage
run exclude the tag - growth is platform-independent, so Windows and coverage instrumentation only
multiplied its cost - and `sbt testPerformance` runs it (CI's ubuntu `Performance tests` job and a
nightly run). Leave one untagged guard per spec: a single ~1 MB worst-case input under
`LinearTime.timed(label, hangGuard)`, generous enough never to flake, so `sbt test` still fails
on a catastrophic (quadratic) regression.

CI's Windows job runs only `sbt testPlatform`: the tests of platform-sensitive behaviour (paths and
separators, permissions, deleting or moving open files, processes and their arguments, SQLite files
and locks, line endings written to files, temp files, the user's home), listed in
`project/PlatformTests.scala` as `wholeModules` (every suite runs) and `suites` (named suites of
otherwise pure modules). Pure JVM logic runs only on ubuntu. **A test of platform-sensitive behaviour
must run on Windows**: put it in a whole module or add its suite to `PlatformTests.suites`. A main
source calling a platform-sensitive API outside the whole modules must be listed, with the suite that
tests it, in `scripts/platform-sensitive-sources.txt`; `scripts/check-platform-tests.sh` (Quick
Checks) fails otherwise, and when a test that checks `os.name` does not run on Windows.

## Adding New Code

### New Sample
1. Create in `modules/samples/src/main/scala/org/llm4s/samples/<category>/`
2. Implement with `extends App`
3. Run with `sbt "samples/runMain org.llm4s.samples.<category>.YourExample"`

### New Provider
See **Providers** under Architecture rules, and **Adding a module**.

### New Tool
1. Define function returning `Result[T]`
2. Register with `ToolRegistry`
3. Add tests and sample

## Resources

- [README.md](README.md) - Getting started
- [docs/examples/index.md](docs/examples/index.md) - Agent examples
- [docs/design/agent-framework-roadmap.md](docs/design/agent-framework-roadmap.md) - Agent framework roadmap
- [docs/design/](docs/design/) - Design documents
- Discord: https://discord.gg/4uvTPn6qww
- Issues: https://github.com/llm4s/llm4s/issues
