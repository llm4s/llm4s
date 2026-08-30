---
layout: page
title: 1.0 Scope
parent: Reference
nav_order: 6
---

# 1.0 Scope

This page states which parts of the LLM4S API are safe to build on ahead of a 1.0 freeze.

Until 0.4.1 all of it shipped in one `core` Maven artifact of roughly 84k lines — agent runtime, RAG, MCP, speech, image, knowledge graph, and eleven provider clients together. There was no way to tell from the artifact alone which parts of that surface are meant to be a long-term contract and which are still moving fast.

This page describes the **target state** of the in-progress modularisation programme tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126). Most of it is still a destination rather than a description: except where the table says otherwise, a package still ships inside `llm4s-core`, and the target-module column says where it is heading, not where it lives today.

What has actually moved so far, in the build but not yet in a release:

| Slice | Modules carved | Status |
|---|---|---|
| [1](https://github.com/llm4s/llm4s/issues/1128) | `llm4s-rag`, `llm4s-knowledgegraph` | in the build, unpublished |
| [2](https://github.com/llm4s/llm4s/issues/1129) | `llm4s-memory`, `llm4s-memory-postgres` | in the build, unpublished |

The latest release tag is `v0.4.1`, which is still a single `llm4s-core`. The first release to publish separate module artifacts will be the next one.

## Maturity Legend

These definitions are shared with the [Roadmap](roadmap) so the two pages agree.

| Status | Meaning |
|--------|---------|
| **Frozen at 1.0** | Source and binary compatible within the 1.x series once published under its target module. This is the compatibility promise 1.0 makes. |
| **Beta** | Implemented and usable, but API, provider behavior, or docs still need hardening before v1.0. |
| **Experimental** | Useful prototype or advanced feature; expect changes. |
| **Planned** | Roadmap item, design, issue, or PR queue item; not a stable user contract. |

## Package Map

Every top-level package under `modules/core/src/main/scala/org/llm4s/`, its target module, and its tier.

| Package | Target module | Tier |
|---------|---------------|------|
| `types` | `llm4s-core` | Frozen at 1.0 |
| `error` | `llm4s-core` | Frozen at 1.0 |
| `config` | `llm4s-core` | Frozen at 1.0 |
| `model` | `llm4s-core` | Frozen at 1.0 |
| `toolapi` | `llm4s-core` | Frozen at 1.0 |
| `context` | `llm4s-core` | Frozen at 1.0 |
| `util` | `llm4s-core` | Frozen at 1.0 |
| `syntax` | `llm4s-core` | Frozen at 1.0 |
| `identity` | `llm4s-core` | Frozen at 1.0 |
| `resource` | `llm4s-core` | Frozen at 1.0 |
| `core` | `llm4s-core` | Frozen at 1.0 |
| `http` | `llm4s-core` | Frozen at 1.0 |
| `security` | `llm4s-core` | Frozen at 1.0 |
| `llmconnect` (API only — see provider split below) | `llm4s-core` | Frozen at 1.0 |
| `reliability` | `llm4s-core` | Frozen at 1.0 |
| `agent` (excludes `agent/memory`) | `llm4s-agent` | Frozen at 1.0 |
| `assistant` | `llm4s-agent` | Beta |
| `trace` | `llm4s-observability` | Frozen at 1.0 |
| `metrics` | `llm4s-observability` | Frozen at 1.0 |
| `llmconnect/provider` — OpenAI, and the OpenAI-compatible clients (Azure, OpenRouter, Requesty, DeepSeek, Z.ai) | `llm4s-openai` | Frozen at 1.0 |
| `llmconnect/provider` — Anthropic | `llm4s-anthropic` | Frozen at 1.0 |
| `llmconnect/provider` — Gemini (and Vertex AI) | `llm4s-gemini` | Frozen at 1.0 |
| `llmconnect/provider` — Ollama | `llm4s-ollama` | Frozen at 1.0 |
| `llmconnect/provider` — Cohere, Mistral, and other community clients | community provider modules | Beta |
| `rag`, `vectorstore`, `chunking`, `reranker`, `eval` — **carved** | `llm4s-rag` | Beta |
| `extract` (consolidated from `rag/extract` + `llmconnect/extractors`) and `rag/embed` (from `llmconnect/encoding`) — **carved** | `llm4s-rag` | Beta |
| `agent/memory` (excluding `PostgresMemoryStore`) — **carved** | `llm4s-memory` | Beta |
| `agent/memory/PostgresMemoryStore` — **carved** | `llm4s-memory-postgres` | Beta |
| `mcp` | `llm4s-mcp` | Beta |
| `speech` | `llm4s-speech` | Experimental |
| `imagegeneration`, `imageprocessing` | `llm4s-image` | Experimental |
| `knowledgegraph` — **carved** (`knowledgegraph/graphrag` ships in `llm4s-rag`) | `llm4s-knowledgegraph` | Experimental |

Notes:

- The `llmconnect/provider` directory today also holds shared plumbing (cost estimation, HTTP error mapping, metrics recording, embedding provider trait) alongside the per-provider clients. [Slice 4](https://github.com/llm4s/llm4s/issues/1131) is designing a provider registration SPI to replace the current central-file registration; that design decides where this shared plumbing ends up (most likely `llm4s-core`), so treat its exact home as unsettled until #1131 lands.
- Rows marked **carved** already live in their target sbt module. Their package names are unchanged, so this is a build-file change for users, not an import rewrite — with one exception, `org.llm4s.extract`, described in the [migration guide](migration#slice-1-llm4s-rag-and-llm4s-knowledgegraph).
- `org.llm4s.extract` is a new package name, not a rename of an existing one — see [Slice 1](https://github.com/llm4s/llm4s/issues/1128) for why the two extractors were consolidated rather than just moved.
- `org.llm4s.knowledgegraph.graphrag` keeps its package name but ships in `llm4s-rag`, not `llm4s-knowledgegraph`. `GraphRAG` and `vectorstore` referenced each other, which made the two modules inseparable; moving the one file that reaches into `vectorstore` broke the cycle. Package names track the API; module boundaries track the dependency graph, and here they disagree.
- `llm4s-memory` splits in two. `PostgresMemoryStore` was the only file in `agent/memory` that needed a connection pool and a server-side driver, so it ships as `llm4s-memory-postgres`; keeping it with the rest would mean anyone using agent memory at all inherits HikariCP and the Postgres JDBC driver. `llm4s-memory` itself carries only sqlite-jdbc, for the two file-backed stores.
- `org.llm4s.vectorstore.PostgresVectorHelpers` ships in `llm4s-core`, not in `llm4s-rag` with the rest of `org.llm4s.vectorstore`. It is a pure `Array[Float]` ⇄ pgvector-text codec with no JDBC types in it, and it has consumers in two modules that must not depend on each other (`llm4s-rag` and `llm4s-memory-postgres`); the module they share is core. This is the same package-versus-module disagreement as `graphrag`, in the other direction.
- Vertex AI's exact home is unsettled — it is bundled with Gemini here because it is Google's hosting path for Gemini models, but it could end up a separate or community module once #1131 lands.

## What Frozen means

- **Source and binary compatible within 1.x.** Once `0.4.0` publishes the split modules, `mimaPreviousArtifacts` enforces binary compatibility on every Frozen module for all subsequent 1.x releases.
- **Deprecate before removing.** A Frozen API is only removed after a deprecation cycle, never dropped outright in a minor release.
- **Beta and Experimental can move faster.** They may change in a minor release, but a migration note ships with the change in the same release's CHANGELOG.

## Scala and JDK support

1.0 targets **Scala 3 only (3.7.1)**. Scala 2.13 support is deferred to post-1.0 and, if it happens, would target the frozen spine (`llm4s-core`, `llm4s-agent`, `llm4s-observability`, and the frozen provider modules) rather than the full tree. JDK 21 is used in CI.

See [#1126](https://github.com/llm4s/llm4s/issues/1126) for the reasoning behind the Scala-3-only decision.

## Programme status

For current progress against this target structure, see the tracking issue [#1126](https://github.com/llm4s/llm4s/issues/1126) and its slice sub-issues.
