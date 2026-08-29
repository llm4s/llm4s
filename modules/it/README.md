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

## Environment variables

- Neo4j via `NEO4J_URI`, `NEO4J_USER`, and `NEO4J_PASSWORD`
- PostgreSQL/pgvector via `PGVECTOR_TEST_URL`, `PGVECTOR_TEST_USER`, `PGVECTOR_TEST_PASSWORD`, or the `POSTGRES_*` variables used by memory tests
- Qdrant via `QDRANT_TEST_URL` and `QDRANT_TEST_API_KEY`
- Docker-backed workspace tests via `LLM4S_DOCKER_TESTS=true`; the image tag comes from the build as `LLM4S_WORKSPACE_IMAGE`
- Ollama via a local server at `http://localhost:11434` with `qwen2.5:0.5b` pulled
- Cloud provider smoke tests via credentials such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `DEEPSEEK_API_KEY`, and `COHERE_API_KEY`
