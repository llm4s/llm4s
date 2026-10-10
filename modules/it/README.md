# llm4s-it

Integration-test module for llm4s.

This module is not published. It exists so the main published artifacts can keep
fast, self-contained unit tests while live integration suites remain available
on demand and in CI.

## Tiers

Every suite here declares, by class annotation, what it needs to run - and therefore
which command and which CI job runs it. `sbt it/itTierCheck` fails the build if a suite
declares no tier or more than one, so a suite cannot end up being run by nothing.

| Tag | Needs | Command | CI |
|---|---|---|---|
| `@Local` | nothing external | `sbt test` | every PR |
| `@Docker` | Postgres/pgvector, Qdrant, Neo4j or the MCP reference server | `sbt testIntegration` | every PR (service containers) |
| `@Workspace` | Docker + a built `workspace-runner` image | `sbt testWorkspace` | pushes to `main` |
| `@Ollama` | a local Ollama with `qwen2.5:0.5b` pulled | `sbt testOllama` | pushes to `main` |
| `@Cloud` | live provider API keys | `sbt testSmoke` | manual `workflow_dispatch` |

```scala
import org.llm4s.it.tags.Docker

@Docker
class PgVectorStoreSpec extends AnyWordSpec with Matchers {
```

Tags come from `org.llm4s.it.tags` (see `src/test/java/org/llm4s/it/tags/`). They are Java
annotations because ScalaTest only honours a whole-suite tag in that form.

`sbt "it/testOnly org.llm4s.vectorstore.PgVectorStoreSpec"` still runs a single suite
whatever tier it is in - the tier filter applies to `test`, not to `testOnly`.

## The `@Cloud` capability contract

The OpenAI, Anthropic, Gemini, DeepSeek, OpenRouter, Cohere, Mistral, Z.ai and Bedrock smoke specs
share one contract (`ProviderSmokeContract`, issue #1212), so every chat provider is held to the same
capabilities and a maintainer can see at a glance what each one supports. The checks are in `SmokeChecks`.
Reasoning is checked on OpenAI (`gpt-5-mini`, token count), Anthropic (`claude-haiku-4-5`, thinking text),
DeepSeek (`deepseek-reasoner`) and Z.ai (`glm-4.5-flash`, which thinks by default; its other checks run with
thinking disabled so the small token caps go to the answer). Bedrock declares structured output `n/a`:
`BedrockClient` sends no response format.

| Capability | What it checks |
|---|---|
| system message | a model told to answer with one word, whatever it is asked, does (covers Cohere's `developer` role and the system handling of Anthropic and Gemini) |
| multi-turn history | an assistant turn in the history reaches the model, which answers from it |
| tool call | the model calls a trivial tool, the call carries an id and arguments that fit the tool, the result goes back as a `ToolMessage`, and the final answer carries it |
| streamed tool call | the same, streamed: the arguments reassemble into JSON that fits the tool, and no tool-call chunk arrives without its call's id |
| structured output | a JSON-schema `responseFormat`, with a prompt that does not ask for JSON, gives a reply that is the JSON document itself (a code fence is tolerated, prose is not), with exactly the schema's properties, of its types, and the values asked for |
| usage | reported on `complete`: positive, with a total not below prompt + completion |
| streamed usage | the same on a streamed completion |
| reasoning | on a reasoning model, the answer is not empty and the provider reports thinking: as text where it returns text, as a token count where it only counts |

Each check asserts structure and invariants, never a model's wording, and uses tiny prompts and a few tens of
tokens. The tool-call checks make two requests; the reasoning check runs on a reasoning model with a cap of
1024 to 2048 tokens. The caps bound the cost: a few thousand tokens per provider at most, for the whole contract.

**Reading the matrix.** The end of each spec prints that provider's row, and the JVM prints all rows together on
exit. This output is illustrative, not from a real run:

```
provider   system message  multi-turn history  tool call  streamed tool call  structured output  usage    streamed usage  reasoning
OpenAI     held            held                held       held                held               held     held            held
Gemini     held            held                held       held                FAILED             held     held            n/a

Notes:
  FAILED  Gemini / structured output: [structured output] the reply is not a JSON document, so the response format was not honoured: ...
  n/a     Gemini / reasoning: this spec has no reasoning-capable model configured ...
```

- `held`: the check ran and the provider did what the capability requires.
- `FAILED`: it ran and did not. The message starts with the capability in brackets and says what differed.
- `n/a`: the spec declares the capability does not apply, with the reason. It shows as a cancelled test.
  A capability is never skipped silently.
- `skipped`: the provider's key is not set. Under `LLM4S_IT_STRICT=true` this fails instead, as everywhere in the tier.

**Running it.** `sbt testSmoke` runs every `@Cloud` suite, contract included. To run only the contract for one
provider, select its tests by name, which leaves out the suite's other tests (several of them call the provider
with an invalid key to check the authentication error):

```
OPENAI_API_KEY=... sbt 'it/testOnly org.llm4s.llmconnect.smoke.OpenAISmokeSpec -- -z "capability contract"'
```

**The contract is proven without a provider.** `SmokeContractOfflineSpec` (`@Local`, so part of `sbt test`) runs
the real checks through real clients (`DeepSeekClient`, `OpenRouterClient`, `CohereClient` and `OpenAIClient`)
against a local fake server. Every capability holds for a well-behaved server, and for each way a server can
misbehave (ignoring the system message, forgetting earlier turns, ignoring the tool result, cutting tool-call
arguments off, streaming prose where a tool call belongs, omitting usage, ignoring `response_format`, answering
with the wrong shape or the wrong values, sending no reasoning) exactly the broken capabilities fail, each
naming itself, and the rest still hold. Stub clients cover shapes the real clients prevent, such as a streamed
tool-call chunk without an id.

**What this does not prove.** No live provider has run these checks. The expectations are derived from each
provider's documented behaviour and from the client code, and a first run with real keys may show one is wrong. So
read a failure in this order: the capability's message; whether the model constant in the spec is still served (a
retired model fails every capability with a not-found error: `GeminiSmokeSpec` and `AnthropicSmokeSpec` default to
older models, see #1309); and whether the client really mishandles the case. The structured-output check sends a
`json_schema` response format to every OpenAI-compatible provider, so a provider that accepts only `json_object`
fails *structured output*: that is a client finding, not a test bug.

**Adding a provider** (#1213) means mixing the contract into its spec: mix in `ProviderSmokeContract`, define
`providerLabel`, `apiKeyEnvVar`, `contractKey` and `contractClient(key)`, override `notApplicable` for what does
not apply (and `reasoningSetup` if a reasoning model exists), and call `registerCapabilityContract()` after the
spec's own tests.

## Running the containerised tier

```bash
# Start Neo4j (Docker, easiest):
docker run --rm -p 7687:7687 -e NEO4J_AUTH=neo4j/llm4stest neo4j:5

# Start PostgreSQL + pgvector:
docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16

# Start Qdrant:
docker run --rm -p 6333:6333 qdrant/qdrant

# Start the MCP reference server (Streamable HTTP):
docker run --rm -p 3001:3001 node:22-alpine \
  npx -y @modelcontextprotocol/server-everything@2026.8.31 streamableHttp

export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
export PGVECTOR_USER=postgres PGVECTOR_PASSWORD=postgres
export PGVECTOR_TEST_USER=postgres PGVECTOR_TEST_PASSWORD=postgres
export POSTGRES_TEST_ENABLED=true POSTGRES_PASSWORD=postgres
export QDRANT_TEST_URL=http://localhost:6333
export NEO4J_URI=bolt://localhost:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=llm4stest
export MCP_SERVER_URL=http://localhost:3001/mcp

sbt testIntegration
```

A suite whose service is missing cancels its tests, which ScalaTest reports as skipped.
Set `LLM4S_IT_STRICT=true` - as every tier's CI job does - to make that a failure instead,
so a service that did not start cannot pass for a suite that did.

## Environment variables

- Neo4j via `NEO4J_URI`, `NEO4J_USER`, and `NEO4J_PASSWORD`
- PostgreSQL/pgvector via `PGVECTOR_TEST_URL`, `PGVECTOR_TEST_USER`, `PGVECTOR_TEST_PASSWORD`, or the `POSTGRES_*` variables used by memory tests
- Qdrant via `QDRANT_TEST_URL` and `QDRANT_TEST_API_KEY`
- The MCP reference server (`@modelcontextprotocol/server-everything`, Streamable HTTP) via `MCP_SERVER_URL`
- Docker-backed workspace tests via `LLM4S_DOCKER_TESTS=true`; the image tag comes from the build as `LLM4S_WORKSPACE_IMAGE`
- Ollama via a local server at `http://localhost:11434` with `qwen2.5:0.5b` pulled
- Cloud provider smoke tests via credentials such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `DEEPSEEK_API_KEY`, and `COHERE_API_KEY`. Each `@Cloud` suite reads its own variable with `System.getenv` and builds the provider config directly; the provider modules also bind most of them to `llm4s.credentials.<provider>.apiKey` for applications, but these suites do not go through that
