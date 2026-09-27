# CLAUDE.md - AI Assistant Guide for LLM4S

## Project Overview

**LLM4S** (Large Language Models for Scala) is a framework for building LLM-powered applications in Scala with:
- Multi-provider support (OpenAI, Anthropic, Azure, Ollama, Google Gemini)
- Type-safe design with `Result[A]` error handling
- Agent framework with tools, guardrails, handoffs, and memory
- Scala 3 only (3.7.1). Scala 2.13 support is deferred to post-1.0 — see [#1126](https://github.com/llm4s/llm4s/issues/1126)

**Tech Stack:** Scala 3.7.1, JDK 21, SBT, ScalaTest, Cats, uPickle, Docker

## Core Principles

1. **Use `Result[A]` instead of exceptions** - `type Result[+A] = Either[LLMError, A]`
2. **Use `Llm4sConfig` at the app edge** - Never use `sys.env`, `System.getenv`, or `ConfigSource.default` directly in core code
3. **Use type-safe newtypes** - `ModelName`, `ApiKey`, `ConversationId` etc.
4. **Scala 3 idioms are welcome** - `opaque type`, `using` clauses, `enum` and `extension` are all in use. Do not rewrite them to a Scala 2.13-compatible subset; see [#1127](https://github.com/llm4s/llm4s/issues/1127)

## Active: modularisation programme (#1126)

`modules/core` is being split into per-concern modules ahead of a 1.0 API freeze. **Before moving, renaming, or adding files under `modules/core`, read [#1126](https://github.com/llm4s/llm4s/issues/1126) and the relevant slice issue.**

Slice order — each is an issue with its own scope and gotchas:

| Slice | Issue | Carves |
|---|---|---|
| 0 ✅ | [#1127](https://github.com/llm4s/llm4s/issues/1127) | build + tracker prerequisites |
| 1 ✅ | [#1128](https://github.com/llm4s/llm4s/issues/1128) | `llm4s-rag`, `llm4s-knowledgegraph` |
| 2 ✅ | [#1129](https://github.com/llm4s/llm4s/issues/1129) | `llm4s-memory`, `llm4s-memory-postgres` |
| 3 ✅ | [#1130](https://github.com/llm4s/llm4s/issues/1130) | `llm4s-mcp`, `llm4s-media`, `llm4s-image`, `llm4s-speech` |
| 4 ✅ | [#1131](https://github.com/llm4s/llm4s/issues/1131) | provider registration SPI |
| 5 🚧 | [#1132](https://github.com/llm4s/llm4s/issues/1132) | provider modules - `llm4s-ollama`, `llm4s-gemini`, `llm4s-anthropic`, `llm4s-openai`, `llm4s-openai-compatible` (incl. Mistral, Cohere), `llm4s-voyage`; core holds no client |
| 6 | [#1133](https://github.com/llm4s/llm4s/issues/1133) | `llm4s-observability`, then 0.4.0 + MiMa |

**Invariants for every carve:**

1. **Keep package names.** Move files between sbt modules without renaming `org.llm4s.*`, so each carve stays source-compatible — users add a dependency, not new imports. The one sanctioned exception is `org.llm4s.extract` in slice 1.
2. **Tests move with their code.** Leaving them behind silently drops coverage in both modules.
3. **`reference.conf` keys move with their code.** HOCON merges across jars; keys left behind become defaults that apply to nothing.
4. **Coverage floor and codecov flag land in the same commit as the carve.** A missing flag makes the moved code untracked rather than failing.
5. **One migration note per slice**, in CHANGELOG and docs.
6. **Integration suites move with their code, and keep a tier.** A suite that needs a
   database, a container, a model server or an API key lives in `modules/it` and declares
   exactly one tier tag from `org.llm4s.it.tags`; `sbt it/itTierCheck` fails the build
   otherwise. Carving code out of `core` without carrying its integration suite - or moving
   the suite and leaving it untagged - removes the only signal the carve has (see
   [#1143](https://github.com/llm4s/llm4s/issues/1143)).
7. **Add the new module to the `docs` project in `build.sbt`.** The published Scaladoc is one
   aggregate API tree built from that project's source list, not from `core` alone. A module
   missing from it does not fail - its API pages are simply never generated, which reads as
   "this API does not exist". Slices 1 and 2 both hit this and it went unnoticed until slice 3;
   `pages.yml` now fails the deploy if a known package is absent, and the ScalaDoc CI job runs
   `docs/doc` on every PR.
8. **A provider is a `ProviderDescriptor`, not an edit to shared files** - since slice 4 PRs 2
   and 3 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Implement
   `org.llm4s.llmconnect.spi.ProviderDescriptor`, list it in an `Llm4sProviderModule`, and
   declare that module in `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` - a
   `class` with a public no-arg constructor, never an `object`. Adding a provider is then adding
   a dependency; `ProviderRegistry.of` / `.ofModules` / `.withProvider` remain for explicit
   registration. Nothing in `llm4s-core` needs editing: `ProviderCapabilities`,
   `ProviderCapabilitiesRegistry` and the twelve `NamedProviderValidators` objects are gone, and
   the dispatch `match` expressions in `LLMConnect` and `NamedProviderLoader` with them.
   **`llm4s-core` ships no provider and holds no provider list**: `BuiltinProviders`,
   `BuiltinProviderModule`, `BuiltinProvidersSpec`, core's main `META-INF/services` entry and
   `ProviderRegistry.builtin` were deleted when the last client left core in slice 5
   ([#1132](https://github.com/llm4s/llm4s/issues/1132)). Never add a provider, a provider
   list or a services entry back to core. Each provider module proves its own registration
   instead, in an `Llm4s<Name>ModuleSpec`: discovered through `ProviderRegistry.discover()`,
   the only module supplying its ids, registrable with `ProviderRegistry.ofModules`, and a
   config-to-client round trip per descriptor - that spec is what replaced the compiler's
   exhaustivity check over the old closed `enum`. `modules/ollama` (chat plus embeddings) and
   `modules/providers/voyage` (embeddings only) are the worked examples: their own
   `Llm4s<Name>Module`, services entry, `reference.conf` block and module spec, and nothing of
   them in core.

Current per-module coverage floors are recorded in [#1127](https://github.com/llm4s/llm4s/issues/1127); floors ratchet upward and are never lowered.

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
│   ├── openai-compatible/     # One SDK-free chat-completions client: DeepSeek, Z.ai, OpenRouter, Mistral, Cohere, generic (published)
│   ├── providers/             # Community provider modules, one `llm4s-<name>` each (published)
│   │   └── voyage/            # Voyage AI embedding provider
│   ├── samples/               # Usage examples
│   ├── workspace/             # Containerized execution
│   ├── config-policy/         # Config policy checks + CLI
│   ├── knowledgegraph-neo4j/  # Neo4j graph store
│   ├── trace-opentelemetry/   # OpenTelemetry tracing
│   ├── benchmarks/            # JMH benchmarks
│   └── it/                    # Integration tests
├── docs/                # Documentation
├── project/             # SBT config
└── build.sbt
```

Slices 0 to 3 have landed. `modules/rag`, `modules/knowledgegraph`, `modules/memory`,
`modules/memory-postgres`, `modules/mcp`, `modules/media`, `modules/image` and `modules/speech`
are carved, so `modules/core` no longer holds `rag`, `vectorstore` (bar `PostgresVectorHelpers`,
see below), `chunking`, `reranker`, `eval`, `knowledgegraph`, `agent/memory`, `mcp`,
`imagegeneration`, `imageprocessing` or `speech`, nor any Tika/POI/PDFBox/jsoup/AWS, HikariCP,
Postgres, SQLite, Java-WebSocket, Vosk or JNA dependency. What remains in core is the agent
runtime, `llmconnect`, `toolapi`, `config`, `trace` and the provider clients - which slices 4
to 6 address.
Those three JDBC dependencies also left `commonSettings`, which used to put them on every
module's classpath - declare them per-module if you add database code. The build now has **no
third-party resolvers at all**: the "Vosk Repository" at alphacephei.com was the last one, and
it went with the speech carve because Vosk publishes to Maven Central and it had never resolved
anything. Think hard before adding one back.

Slice 5 has begun: `modules/ollama` carries the Ollama chat client, embedding provider,
`OllamaConfig`, model lister and its `llm4s.embeddings.ollama` block, so core's tests cannot
use Ollama as a convenient no-key provider any more - use a fixture descriptor, as
`EmbeddingProviderSpiSpec` and `ModelDimensionRegistrySpec` do. `modules/gemini` followed,
carrying both Google providers - the Gemini API and Vertex AI, which only calls Gemini models in
the same JSON format and needs no extra dependency. `modules/anthropic` came third and took the
Anthropic Java SDK with it. `modules/openai` came fourth with the three providers that share
`OpenAIClient` - OpenAI, Azure and Requesty - plus `OpenAIEmbeddingProvider`, `AzureConfig` and
the tool helper, and took the Azure OpenAI SDK: **core now depends on no vendor SDK
(`com.anthropic`, `com.azure`, `com.openai`)**, and must not again. `OpenAIClient` has since
moved from Microsoft's deprecated `com.azure:azure-ai-openai` to OpenAI's `com.openai:openai-java`,
which serves Azure too (`AzureApiKeyCredential`, a forced `AzureUrlPathMode`, `api-version`), so
`AzureToolHelper` became `OpenAIToolHelper`; tests build SDK objects from JSON through
`ObjectMappers.jsonMapper()` (`OpenAISdkFixtures`), and read responses leniently through
`_field().asKnown()`, because the SDK's plain getters throw on a missing field. `modules/openai-compatible`
came fifth and is a **consolidation, not a pure move**: DeepSeek, Z.ai and OpenRouter had three
~400-line copies of one SDK-free chat-completions client, and are now thin subclasses of
`OpenAICompatibleClient`, each with an `OpenAICompatibleDialect` (headers, content encoding,
assistant-content policy, reasoning request, content/thinking/reasoning-token decoding,
tool-call parser - every member defaults to the standard format). The module also holds
`OpenAIConfig` - `llm4s-openai` depends on it for that, never the reverse, which would put the
OpenAI SDK on every OpenAI-compatible user's classpath - and the generic `openai-compatible`
provider, the standard dialect configured entirely from a named section (`baseUrl`, `model`,
optional `apiKey`, `contextWindow`, `reserveCompletion`, `headers`; the last three are fields of
`NamedProviderConfig` that other providers ignore). **A new OpenAI-compatible provider is a
dialect and a descriptor in that module, or just config** - check whether `openai-compatible`
covers it before writing one; never another copy of the client. Mistral and Cohere followed as
dialects: Mistral over its OpenAI-format `/v1/chat/completions` (nine-character tool-call ids,
no empty assistant turns, content-as-chunks with thinking), Cohere over its OpenAI-compatibility
API (`https://api.cohere.ai/compatibility/v1`; `developer` system role, `json_object`+`schema`
response format; a configured native root gets `/compatibility/v1` appended). That gave both
streaming, which they had never had (#925). The dialect hook therefore also has
`sendEmptyAssistantTurns`, `encodeToolCallId`, `systemRole` and `encodeResponseFormat`, and the
base client reports streamed token usage. Community providers that are **not** OpenAI-compatible
live under `modules/providers/<name>`, published as `llm4s-<name>`: Voyage
(`modules/providers/voyage`, `llm4s-voyage`, embeddings only) was the first. With it core held
no client, and `BuiltinProviders` went (invariant 8). `StreamingResponseHandler` (with
`forProvider`, `OpenAIStreamingHandler` and `AnthropicStreamingHandler`) and
`OpenRouterToolCallDeserializer` were deleted with the openai-compatible carve: no client used
them. A streamed tool call is split across deltas that
continuations identify only by `index`; clients must give each continuation its call's id
(`OpenAICompatibleClient.StreamToolCalls`), because `StreamingAccumulator` keys calls by id and
skips a chunk with none. Tests that need an
incidental API-key provider - and never a real one, which would leave core in a later carve -
use `org.llm4s.testutil.FixtureChatProvider` (id `fixturechat`, `FixtureChatConfig`, a canned
no-network client). It lives in core's test sources, is registered by core's **test**
`META-INF/services`, so `ProviderRegistry.default` resolves it in core and in every module
depending on `core % "test->test"`; core's test `application.conf` default is
`fixturechat-main`. Its embedding counterpart is `org.llm4s.testutil.FixtureEmbeddingProvider`
(id `fixtureembedding`, alias, API key, default base URL, env-var names, declared dimensions,
canned vectors), registered the same way; use it wherever a spec needs "some embedding
provider", as core's embedding config, registry and dimension specs do. A spec that builds its own registry passes
it to `ProviderRegistry.of`/`.withProvider`, and a provider spec proving it refuses a foreign
config uses a `FixtureChatConfig`. When a stand-in test checked a real provider's own facts in
passing, those move to that provider's spec (`DeepSeekNamedProviderSpec`, now in
`llm4s-openai-compatible`). Strings that do not reach a client
(`ToolRegistry`'s `"openai"`/`"anthropic"`/`"gemini"` cases, model-registry data, config-policy
allow-lists, secret patterns) stay. `llm4s-rag`'s
`RAGConfig.default` embeds with `openai`, so `rag` has a **test-only** dependency on `openai`;
never make it a compile one - that would put the OpenAI SDK on every RAG user's classpath.

`org.llm4s.vectorstore.PostgresVectorHelpers` is the one file in that package still in core:
it is a pure pgvector text codec shared by `llm4s-rag` and `llm4s-memory-postgres`, which must
not depend on each other.

`modules/media` is not a carve - it is a new module, added mid-slice-3 because `image` and
`speech` could not be split apart cleanly without it. Core had grown three overlapping image
format enumerations (`imagegeneration.ImageFormat`, `imageprocessing.ImageFormat`,
`imageprocessing.MediaType`) and RAG matched on raw MIME prefixes; carving first would have
frozen those copies into separate artifacts. `org.llm4s.media` holds the consolidated
vocabulary and nothing else - **no I/O, no content sniffing, no third-party dependencies**.
Keep it that way: the moment it grows a dependency, every consumer inherits it. Tika-based
sniffing stays in `llm4s-rag` and resolves its result through `MediaType.fromMimeType`.
Core's dependency on it is temporary and leaves with the `llm4s-image` carve.

**Key paths in `modules/core/src/main/scala/org/llm4s/`:**
- `types/` - Result type, newtypes
- `config/` - Llm4sConfig + typed loaders
- `llmconnect/` - LLM client and providers
- `agent/` - Agent framework, guardrails, handoffs (memory lives in `modules/memory`)
- `toolapi/` - Tool calling, built-in tools
- `trace/` - Observability

## Common Commands

```bash
sbt buildAll           # Clean, compile, test
sbt test               # Run tests
sbt scalafmtAll        # Format code
sbt cov                # Run coverage
sbt testIntegration    # modules/it @Docker tier (Postgres/pgvector, Qdrant, Neo4j)
sbt testWorkspace      # modules/it @Workspace tier (needs a built workspace-runner image)
sbt testOllama         # modules/it @Ollama tier
sbt testSmoke          # modules/it @Cloud tier (live API keys)
sbt it/itTierCheck     # every suite in modules/it must declare exactly one tier
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

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

**Nothing in the library reads `LLM_MODEL` or a provider's API-key variable** (removed with legacy
single-provider loading in #903). Chat providers are named sections in the application's
`application.conf`, each binding its own variables with `${?VAR}`; `llm4s.providers.provider`
names the default that `Llm4sConfig.defaultProvider()` loads:

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
      apiKey   = ${?OPENAI_API_KEY}
    }
  }
}
```

- Precedence: `-D` system properties > `application.conf` > each module's `reference.conf`.
  Environment variables are read only through `${?VAR}`.
- **Every section is validated on every load**: a section whose key variable is unset, or whose
  provider module is absent, fails `defaultProvider()` even when it is not the default.
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
# Tracing (llm4s-core)
TRACING_MODE=langfuse                # langfuse, opentelemetry, console (default), or none
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
OTEL_SERVICE_NAME=llm4s-agent        # OTLP headers: llm4s.tracing.opentelemetry.headers, not OTEL_EXPORTER_OTLP_HEADERS
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Embeddings (llm4s-core selects; each provider module binds its own block)
EMBEDDING_MODEL=openai/text-embedding-3-small  # provider/model
VOYAGE_API_KEY=pa-...                          # llm4s-voyage
# OpenAI embeddings' key is NOT bound: add llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}
# OPENAI_EMBEDDING_BASE_URL / VOYAGE_EMBEDDING_BASE_URL / OLLAMA_EMBEDDING_BASE_URL override base URLs
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

// BAD - and a key belongs in the section anyway: apiKey = ${?OPENAI_API_KEY}
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

```scala
for {
  providerConfig  <- Llm4sConfig.defaultProvider()   // llm4s.providers.<default> in application.conf
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
  agent = new Agent(client)
  tools = new ToolRegistry(Seq(myTool))
  state <- agent.run("Query here", tools)
} yield state
```

### Multi-Turn Conversations

```scala
for {
  state1 <- agent.run("First query", tools)
  state2 <- agent.continueConversation(state1, "Follow-up")
} yield state2
```

### Built-in Tools

```scala
import org.llm4s.toolapi.builtin.BuiltinTools

BuiltinTools.core          // DateTime, Calculator, UUID, JSON
BuiltinTools.safe()        // + web search, HTTP
BuiltinTools.withFiles()   // + read-only file access
BuiltinTools.development() // All tools (use with caution)
```

### Guardrails

```scala
import org.llm4s.agent.guardrails.builtin._

agent.run(
  query = "Generate JSON",
  tools = tools,
  inputGuardrails = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
  outputGuardrails = Seq(new JSONValidator())
)
```

Built-in guardrails:
- **Simple validators**: `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator`, `ToneValidator`
- **LLM-as-Judge**: `LLMSafetyGuardrail`, `LLMFactualityGuardrail`, `LLMQualityGuardrail`, `LLMToneGuardrail`
- **Composition**: `CompositeGuardrail.all()`, `CompositeGuardrail.any()`, `CompositeGuardrail.sequence()`

### Handoffs

```scala
import org.llm4s.agent.Handoff

agent.run(
  query = "Complex physics question",
  tools = ToolRegistry.empty,
  handoffs = Seq(Handoff.to(specialistAgent, "Physics expertise required"))
)
```

Use handoffs for simple 2-3 agent delegation. Use DAGs for complex parallel workflows.

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
  .copy(maxTokens = Some(4096))

client.complete(conversation, options)
```

### Streaming Events

```scala
import org.llm4s.agent.streaming._

// Get real-time agent execution events
agent.runWithEvents("Query here", tools) { event =>
  event match {
    case TextDelta(text) => print(text)
    case ToolCallStarted(name, _) => println(s"Calling $name...")
    case ToolCallCompleted(name, result, _) => println(s"$name returned: $result")
    case AgentCompleted(state) => println("Done!")
    case _ => ()
  }
}
```

Event types: `TextDelta`, `TextComplete`, `ToolCallStarted`, `ToolCallCompleted`, `ToolCallFailed`, `AgentStarted`, `StepStarted`, `StepCompleted`, `AgentCompleted`, `AgentFailed`, `InputGuardrailStarted`, `InputGuardrailCompleted`, `OutputGuardrailStarted`, `OutputGuardrailCompleted`, `HandoffStarted`, `HandoffCompleted`

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

## Adding New Code

### New Sample
1. Create in `modules/samples/src/main/scala/org/llm4s/samples/<category>/`
2. Implement with `extends App`
3. Run with `sbt "samples/runMain org.llm4s.samples.<category>.YourExample"`

### New Provider
Follow invariant 8 above: a `ProviderDescriptor` (or `EmbeddingProviderDescriptor`) in a module
of its own, listed in an `Llm4sProviderModule` declared in `META-INF/services`, with an
`Llm4s<Name>ModuleSpec` proving discovery and the round trip. Nothing goes in core. Where:
- **OpenAI-compatible** (speaks `/chat/completions`): first check the generic
  `openai-compatible` provider covers it with config alone; if not, a dialect and descriptor in
  `modules/openai-compatible` (Mistral and Cohere are the examples).
- **Anything else**: `modules/providers/<name>`, artifact `llm4s-<name>`, depending only on
  core; `modules/providers/voyage` is the template, `modules/ollama` for chat plus embeddings.
Wire it into the root and `docs` aggregates and the docs source list, `samples`, `it`, and
`configPolicy` if it has chat providers; add a coverage floor, a codecov flag and a CI upload.

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
