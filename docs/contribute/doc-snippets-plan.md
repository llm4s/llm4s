---
layout: page
title: Checked Code Blocks
parent: Contribute
nav_order: 1
---

# Checked code blocks
{: .no_toc }

Which documentation pages have their Scala compiled on every build, and the order to convert the rest in
([#1477](https://github.com/llm4s/llm4s/issues/1477)).
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## How it works

Every fenced `` ```scala `` block of a page listed in `DocSnippets.pages` (`project/DocSnippets.scala`) is compiled
as a test source of the `samples` module, so `sbt samples/Test/compile`, and with it `sbt test`, fails when a
snippet no longer matches the code. How to add a page, list a block that cannot compile, find a block's hash and
read an error is in [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#checked-code-blocks).

## Converting a page is a newcomer-sized task

Each page below is one small pull request: add the page to `DocSnippets.pages`, run `sbt samples/Test/compile`,
and fix what fails. Most failures are a missing import, and the rest are usually a real documentation bug - a
method that was renamed or a type that moved - which is the point of the exercise. You need no knowledge of the
library beyond reading its source to check what the current API is. A page with more than about 30 blocks can
be split: convert it in two pull requests, listing the blocks you have not looked at yet as `skip` with the reason
`not converted yet`.

Comment on [#1477](https://github.com/llm4s/llm4s/issues/1477) with the page you are taking, so that two people do
not convert the same one.

## Checked pages

| Page | Scala blocks | Status |
|---|---|---|
| `getting-started/configuration.md` | 14 | checked |
| `getting-started/first-example.md` | 23 | checked |
| `getting-started/installation.md` | 34 | checked |
| `getting-started/next-steps.md` | 10 | checked |
| `getting-started/ollama-quickstart.md` | 7 | checked |
| `getting-started/testing-guide.md` | 7 | checked |
| `guide/basic-usage.md` | 14 | checked |
| `guide/error-handling.md` | 12 | checked |
| `guide/providers.md` | 19 | checked |
| `guide/migrating-from-langchain4j.md` | 9 | checked by `ComparisonGuideSpec` |

## To convert, in this order

Getting-started pages first, then the user guide in the order of its navigation (the pages a newcomer reads
first), then the examples, the reference and migration pages, and the API notes. Some guide pages also have a
hand-written spec that copies their code (`PekkoGuideSpec`, `MemoryGuideSpec`, `JsonLibrariesGuideSpec`,
`DerivedSchemasGuideSpec`, `CookbookDocsSpec`, ...); converting them still pays, because the spec checks a copy,
not the page.

| # | Page | Scala blocks |
|---|---|---|
| 1 | `getting-started/scala-cli.md` | 1 |
| 2 | `index.md` | 3 |
| 3 | `guide/vector-store.md` | 41 |
| 4 | `guide/permission-based-rag.md` | 22 |
| 5 | `guide/structured-output.md` | 6 |
| 6 | `guide/image-generation.md` | 3 |
| 7 | `guide/speech.md` | 5 |
| 8 | `guide/builtin-tools.md` | 7 |
| 9 | `guide/context-window-pruning.md` | 24 |
| 10 | `guide/agents/index.md` | 25 |
| 11 | `guide/agents/guardrails.md` | 24 |
| 12 | `guide/agents/memory.md` | 29 |
| 13 | `guide/agents/handoffs.md` | 15 |
| 14 | `guide/agents/streaming.md` | 7 |
| 15 | `guide/agents/durable-checkpointers.md` | 14 |
| 16 | `guide/agents/human-review.md` | 4 |
| 17 | `guide/agents/delegation.md` | 6 |
| 18 | `guide/writing-a-provider.md` | 23 |
| 19 | `guide/cats-effect.md` | 7 |
| 20 | `guide/zio.md` | 7 |
| 21 | `guide/caching.md` | 8 |
| 22 | `guide/json-libraries.md` | 12 |
| 23 | `guide/rag-evaluation.md` | 7 |
| 24 | `guide/java.md` | 1 |
| 25 | `guide/observability/index.md` | 21 |
| 26 | `guide/observability/provider-exchange-logging.md` | 6 |
| 27 | `guide/observability/micrometer.md` | 4 |
| 28 | `guide/observability/enhanced-tracing-use-cases.md` | 14 |
| 29 | `PRODUCTION_DEPLOYMENT.md` | 15 |
| 30 | `guide/rag-query-transformers.md` | 7 |
| 31 | `guide/pekko.md` | 8 |
| 32 | `guide/jev.md` | 6 |
| 33 | `guide/derived-schemas.md` | 8 |
| 34 | `guide/testing-with-the-testkit.md` | 20 |
| 35 | `guide/multimodal.md` | 2 |
| 36 | `guide/stt-domain-model.md` | 28 |
| 37 | `examples/index.md` | 9 |
| 38 | `examples/cookbook/caching.md` | 2 |
| 39 | `examples/cookbook/document-qa.md` | 1 |
| 40 | `examples/cookbook/email-extraction.md` | 1 |
| 41 | `examples/cookbook/fallback.md` | 2 |
| 42 | `examples/cookbook/folder-qa.md` | 2 |
| 43 | `examples/cookbook/guardrails.md` | 1 |
| 44 | `examples/cookbook/judge.md` | 1 |
| 45 | `examples/cookbook/memory.md` | 1 |
| 46 | `examples/cookbook/multi-agent-graph.md` | 1 |
| 47 | `examples/cookbook/streaming.md` | 1 |
| 48 | `examples/cookbook/structured-output.md` | 2 |
| 49 | `examples/cookbook/summarise.md` | 1 |
| 50 | `examples/cookbook/tool-calling.md` | 1 |
| 51 | `reference/migration.md` | 85 |
| 52 | `migrations/0-4-1-to-0-5-0.md` | 4 |
| 53 | `migrations/0x-to-1x.md` | 3 |
| 54 | `migrations/v0.2.9-to-v0.3.0.md` | 10 |
| 55 | `migrations/v0.3.x-stt-domain-expansion.md` | 6 |
| 56 | `reference/testing-guide.md` | 4 |
| 57 | `reference/postgres-memory-store.md` | 4 |
| 58 | `reference/workspace-sandbox.md` | 2 |
| 59 | `reference/dependency-conflicts.md` | 1 |
| 60 | `reference/api-stability.md` | 1 |
| 61 | `reference/contributing.md` | 2 |
| 62 | `reference/contribution-guidelines.md` | 1 |
| 63 | `reference/review-guidelines.md` | 57 |
| 64 | `api/index.md` | 9 |
| 65 | `llm4s-api-spec.md` | 17 |
| 66 | `tool-calling-api-design.md` | 16 |
| 67 | `reliability-guide.md` | 38 |
| 68 | `MODEL_METADATA.md` | 16 |
| 69 | `langfuse-workflow-patterns.md` | 3 |
| 70 | `rag-benchmark-results.md` | 2 |

## Not planned

Design records, implementation plans and the hardware roadmap describe proposals at the time they were written,
not the current API, so their code is not expected to compile. They are listed here so the inventory is complete.

| Page | Scala blocks |
|---|---|
| `design/agent-framework-roadmap.md` | 24 |
| `design/chat-tui-demo-spec.md` | 3 |
| `design/phase-1.1-functional-conversation-management.md` | 23 |
| `design/phase-1.2-guardrails-framework.md` | 34 |
| `design/phase-1.3-handoff-mechanism.md` | 35 |
| `design/phase-1.4-memory-system.md` | 16 |
| `design/phase-2.1-streaming-events.md` | 7 |
| `design/phase-2.2-async-tools.md` | 8 |
| `design/phase-3.2-builtin-tools.md` | 6 |
| `design/phase-4.1-reasoning-modes.md` | 13 |
| `design/phase-4.3-session-serialization.md` | 5 |
| `design/typed-agent-runtime-design.md` | 1 |
| `design/typed-agent-runtime/4.08-stage-0-prototype-agent-middleware.md` | 1 |
| `design/typed-agent-runtime/4.13-stage-1-slice-2-the-agent-loop-on-the-graph-runtime.md` | 1 |
| `design/typed-agent-runtime/4.23-stage-3-slice-c2-tool-permissions-timeouts-locks.md` | 1 |
| `design/typed-agent-runtime/4.24-stage-3-slice-c4-replay-of-external-effects.md` | 1 |
| `design/typed-agent-runtime/4.25-stage-3-slice-c3-backend-spis.md` | 3 |
| `design/typed-agent-runtime/4.26-stage-3-slice-f8a-response-format.md` | 1 |
| `design/typed-agent-runtime/4.32-stage-3-slice-f1-filesystem-tools.md` | 1 |
| `design/typed-agent-runtime/4.40-host-file-backend.md` | 1 |
| `design/web-crawler-loader.md` | 12 |
| `roadmap/llm4s-hardware-design-roadmap.md` | 25 |
| `superpowers/plans/2026-10-04-spiffe-workload-identity.md` | 51 |
| `superpowers/plans/2026-10-05-agent-loop-cutover.md` | 7 |
| `superpowers/plans/2026-10-06-events-and-tracing.md` | 49 |
| `superpowers/plans/2026-10-08-orchestration-removed.md` | 11 |
| `superpowers/plans/2026-10-17-workspace-command-confinement.md` | 20 |
| `superpowers/specs/2026-10-03-run-api-design.md` | 7 |
| `superpowers/specs/2026-10-04-agent-tool-design.md` | 5 |
| `superpowers/specs/2026-10-05-agent-loop-cutover-design.md` | 3 |
| `superpowers/specs/2026-10-06-events-and-tracing-design.md` | 4 |
| `superpowers/specs/2026-10-19-phase-4-subagents-design.md` | 5 |

