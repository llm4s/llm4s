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
| `@Docker` | Postgres/pgvector, Qdrant or Neo4j | `sbt testIntegration` | every PR (service containers) |
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

## Running the containerised tier

```bash
# Start Neo4j (Docker, easiest):
docker run --rm -p 7687:7687 -e NEO4J_AUTH=neo4j/llm4stest neo4j:5

# Start PostgreSQL + pgvector:
docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16

# Start Qdrant:
docker run --rm -p 6333:6333 qdrant/qdrant

export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
export PGVECTOR_USER=postgres PGVECTOR_PASSWORD=postgres
export PGVECTOR_TEST_USER=postgres PGVECTOR_TEST_PASSWORD=postgres
export POSTGRES_TEST_ENABLED=true POSTGRES_PASSWORD=postgres
export QDRANT_TEST_URL=http://localhost:6333
export NEO4J_URI=bolt://localhost:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=llm4stest

sbt testIntegration
```

A suite whose service is missing cancels its tests, which ScalaTest reports as skipped.
Set `LLM4S_IT_STRICT=true` - as every tier's CI job does - to make that a failure instead,
so a service that did not start cannot pass for a suite that did.

## The watsonx assumption probe

`llm4s-watsonx` has never been run against the real service: there was no IBM account behind the project.
`WatsonXAssumptionProbeSpec` (`@Cloud`) is the first run for whoever has one. It checks, one test per detail,
what `WatsonXClient` takes from IBM's public pages and IBM's open-source clients, and prints a table saying
which held.

```bash
export WATSONX_API_KEY=...        # IBM Cloud API key
export WATSONX_PROJECT_ID=...     # or WATSONX_SPACE_ID
export WATSONX_BASE_URL=https://eu-de.ml.cloud.ibm.com   # optional; default https://us-south.ml.cloud.ibm.com
export WATSONX_MODEL=ibm/granite-4-h-small               # optional; must support tool calling
sbt "it/testOnly org.llm4s.llmconnect.smoke.WatsonXAssumptionProbeSpec"
```

Without `WATSONX_API_KEY` and a project or space id every test is cancelled and nothing is sent to IBM. The
cost is a few dozen requests of at most a few hundred tokens each. `sbt testSmoke` runs it with the other
`@Cloud` suites.

**Reading the report.** The last thing the run prints is a table, one row per assumption:

| Result | Meaning | What to do |
|---|---|---|
| `held` | the service behaves as the client assumes | nothing |
| `NOT HELD` | the assumption is wrong; the failed test's message names it and the place in `WatsonXClient` to change | send the table and the message |
| `skipped` | no credentials, or the test was cancelled | run it with an account |

Rows marked `(info)` check things the client does not depend on, to settle the documentation. A `NOT HELD`
there needs no client change.

**What to send back.** The table, and the sanitised bodies of one plain reply, one tool-call reply and one
stream, with the API key, the bearer token and anything you would not publish removed. They replace the
fake-server fixtures in `modules/providers/watsonx/src/test`, which turns what was a guess into a regression
test. Then #1314 can close.

## Environment variables

- Neo4j via `NEO4J_URI`, `NEO4J_USER`, and `NEO4J_PASSWORD`
- PostgreSQL/pgvector via `PGVECTOR_TEST_URL`, `PGVECTOR_TEST_USER`, `PGVECTOR_TEST_PASSWORD`, or the `POSTGRES_*` variables used by memory tests
- Qdrant via `QDRANT_TEST_URL` and `QDRANT_TEST_API_KEY`
- Docker-backed workspace tests via `LLM4S_DOCKER_TESTS=true`; the image tag comes from the build as `LLM4S_WORKSPACE_IMAGE`
- Ollama via a local server at `http://localhost:11434` with `qwen2.5:0.5b` pulled
- Cloud provider smoke tests via credentials such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `DEEPSEEK_API_KEY`, and `COHERE_API_KEY`. Each `@Cloud` suite reads its own variable with `System.getenv` and builds the provider config directly; the provider modules also bind most of them to `llm4s.credentials.<provider>.apiKey` for applications, but these suites do not go through that
