---
layout: page
title: 1.0 Scope
parent: Reference
nav_order: 6
---

# 1.0 Scope

This page states which parts of the LLM4S API are safe to build on ahead of a 1.0 freeze.

Today all of it ships in one `core` Maven artifact of roughly 84k lines — agent runtime, RAG, MCP, speech, image, knowledge graph, and eleven provider clients together. There is no way to tell from the artifact alone which parts of that surface are meant to be a long-term contract and which are still moving fast.

This page describes the **target state** of the in-progress modularisation programme tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126), not the current published artifacts. Nothing below is published under the new module coordinates yet — the tiers and target modules describe where each package is heading, not where it lives today. The latest release tag is `v0.3.4`; the split is planned for `0.4.0`.

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
| `rag`, `vectorstore`, `chunking`, `reranker`, `eval` | `llm4s-rag` | Beta |
| `llmconnect/extractors` + `llmconnect/encoding` (consolidating into `org.llm4s.extract`) | `llm4s-rag` | Beta |
| `agent/memory` | `llm4s-memory` | Beta |
| `mcp` | `llm4s-mcp` | Beta |
| `speech` | `llm4s-speech` | Experimental |
| `imagegeneration`, `imageprocessing` | `llm4s-image` | Experimental |
| `knowledgegraph` | `llm4s-knowledgegraph` | Experimental |

Notes:

- The `llmconnect/provider` directory today also holds shared plumbing (cost estimation, HTTP error mapping, metrics recording, embedding provider trait) alongside the per-provider clients. [Slice 4](https://github.com/llm4s/llm4s/issues/1131) is designing a provider registration SPI to replace the current central-file registration; that design decides where this shared plumbing ends up (most likely `llm4s-core`), so treat its exact home as unsettled until #1131 lands.
- `org.llm4s.extract` is a new package name, not a rename of an existing one — see [Slice 1](https://github.com/llm4s/llm4s/issues/1128) for why the two existing extractors are being consolidated rather than just moved.
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
