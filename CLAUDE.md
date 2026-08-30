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
| 3 🚧 | [#1130](https://github.com/llm4s/llm4s/issues/1130) | `llm4s-mcp` ✅, `llm4s-image`, `llm4s-speech` |
| 4 | [#1131](https://github.com/llm4s/llm4s/issues/1131) | provider registration SPI |
| 5 | [#1132](https://github.com/llm4s/llm4s/issues/1132) | provider modules |
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
8. **Never add a provider by editing shared registration files** once slice 4 lands. Before then, note that `NamedProviderLoader`, `ProviderConfig`, `ProviderModelTypes`, `LLMConnect`, `ProviderCapabilities*` and `NamedProviderValidator` are the most contended files in the repo.

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

Slices 0, 1 and 2 have landed and slice 3 is in progress: `modules/rag`,
`modules/knowledgegraph`, `modules/memory`, `modules/memory-postgres` and `modules/mcp` are
carved, so `modules/core` no longer holds `rag`, `vectorstore` (bar `PostgresVectorHelpers`,
see below), `chunking`, `reranker`, `eval`, `knowledgegraph`, `agent/memory` or `mcp`, nor any
Tika/POI/PDFBox/jsoup/AWS, HikariCP, Postgres, SQLite or Java-WebSocket dependency.
`image` and `speech` are still in core, pending the rest of slice 3.
Those three JDBC dependencies also left `commonSettings`, which used to put them on every
module's classpath - declare them per-module if you add database code.

`org.llm4s.vectorstore.PostgresVectorHelpers` is the one file in that package still in core:
it is a pure pgvector text codec shared by `llm4s-rag` and `llm4s-memory-postgres`, which must
not depend on each other.

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

## Environment Variables

```bash
# Required for LLM
LLM_MODEL=openai/gpt-4o              # or anthropic/claude-sonnet-4-5-latest, gemini/gemini-2.0-flash
OPENAI_API_KEY=sk-...                # or ANTHROPIC_API_KEY, GOOGLE_API_KEY

# Optional - Tracing
TRACING_MODE=langfuse                # langfuse, opentelemetry, console, or none
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...

# OpenTelemetry
OTEL_SERVICE_NAME=llm4s-agent
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

# Embeddings - Unified format (recommended)
EMBEDDING_MODEL=openai/text-embedding-3-small  # provider/model format, uses default base URL
OPENAI_API_KEY=sk-...                          # reuses LLM API key

# Embeddings - Voyage (cloud)
EMBEDDING_MODEL=voyage/voyage-3
VOYAGE_API_KEY=pa-...

# Embeddings - Ollama (local, no API key needed)
EMBEDDING_MODEL=ollama/nomic-embed-text        # or mxbai-embed-large, all-minilm

# Optional: Override default base URLs
# OPENAI_EMBEDDING_BASE_URL=https://custom.openai.com/v1
# VOYAGE_EMBEDDING_BASE_URL=https://custom.voyage.ai/v1
# OLLAMA_EMBEDDING_BASE_URL=http://custom-ollama:11434
```

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
// GOOD
val provider: Result[ProviderConfig] = Llm4sConfig.defaultProvider()
// or a specific named provider from config:
val named: Result[ProviderConfig] = Llm4sConfig.provider("openai-main")

// BAD
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
  providerConfig  <- Llm4sConfig.defaultProvider()
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
1. Create config in `llmconnect/config/`
2. Implement client in `llmconnect/provider/`
3. Update `ProviderSelector`
4. Add tests

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
