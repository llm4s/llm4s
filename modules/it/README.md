# llm4s-it

Integration-test module for llm4s.

This module is not published. It exists so the main published artifacts can keep
fast, self-contained unit tests while live integration suites remain available
on demand and in CI.

## Running the Tests

```bash
# Start Neo4j (Docker, easiest):
docker run --rm -p 7687:7687 -e NEO4J_AUTH=neo4j/neo4j neo4j:5

# Start PostgreSQL + pgvector:
docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16

# Start Qdrant:
docker run --rm -p 6333:6333 qdrant/qdrant

# Run the integration suite:
sbt "it/test"
```

The current integration suites use:
- Neo4j via `NEO4J_URI`, `NEO4J_USER`, and `NEO4J_PASSWORD`
- PostgreSQL/pgvector via `PGVECTOR_TEST_URL`, `PGVECTOR_TEST_USER`, `PGVECTOR_TEST_PASSWORD`, or the `POSTGRES_*` variables used by memory tests
- Qdrant via `QDRANT_TEST_URL` and `QDRANT_TEST_API_KEY`
- Docker-backed workspace tests via `LLM4S_DOCKER_TESTS=true`
- Ollama via a local server at `http://localhost:11434` with `qwen2.5:0.5b` pulled
- Cloud provider smoke tests via credentials such as `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, and `DEEPSEEK_API_KEY`
