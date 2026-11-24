---
layout: page
title: Checked Code Blocks
description: "Which LLM4S documentation pages have their Scala code blocks compiled on every build, how a block is skipped, and the pages still to convert."
parent: Contribute
nav_order: 8
cites_closed_issues: [1477]
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
| `ai-assistants.md` | 4 | checked |
| `examples/index.md` | 9 | checked |
| `getting-started/configuration-in-code.md` | 6 | checked |
| `getting-started/configuration.md` | 18 | checked |
| `getting-started/first-example.md` | 23 | checked |
| `getting-started/installation.md` | 49 | checked |
| `getting-started/local-jlama.md` | 3 | checked |
| `getting-started/next-steps.md` | 10 | checked |
| `getting-started/ollama-quickstart.md` | 7 | checked |
| `getting-started/repl.md` | 5 | checked |
| `getting-started/testing-guide.md` | 8 | checked |
| `guide/agents/delegation.md` | 6 | checked |
| `guide/agents/durable-checkpointers.md` | 22 | checked |
| `guide/agents/graphs.md` | 6 | checked |
| `guide/agents/guardrails.md` | 27 | checked |
| `guide/agents/handoffs.md` | 15 | checked |
| `guide/agents/human-review.md` | 4 | checked |
| `guide/agents/index.md` | 29 | checked |
| `guide/agents/memory.md` | 29 | checked |
| `guide/agents/middleware.md` | 13 | checked |
| `guide/agents/multiple-machines.md` | 3 | checked |
| `guide/agents/streaming.md` | 9 | checked |
| `guide/basic-usage.md` | 14 | checked |
| `guide/builtin-tools.md` | 7 | checked |
| `guide/caching.md` | 9 | checked |
| `guide/cats-effect.md` | 7 | checked |
| `guide/context-window-pruning.md` | 25 | checked |
| `guide/cost-controls.md` | 6 | checked |
| `guide/custom-tools.md` | 8 | checked |
| `guide/data-protection.md` | 3 | checked |
| `guide/direct-style.md` | 11 | checked |
| `guide/document-loaders.md` | 12 | checked |
| `guide/embeddings.md` | 6 | checked |
| `guide/error-handling.md` | 12 | checked |
| `guide/for-python-developers.md` | 8 | checked |
| `guide/http-transport.md` | 3 | checked |
| `guide/image-generation.md` | 4 | checked |
| `guide/jev.md` | 6 | checked |
| `guide/kafka.md` | 9 | checked |
| `guide/modular-rag-example.md` | 1 | checked |
| `guide/multimodal.md` | 2 | checked |
| `guide/observability/agents.md` | 5 | checked |
| `guide/observability/enhanced-tracing-use-cases.md` | 14 | checked |
| `guide/observability/provider-exchange-logging.md` | 6 | checked |
| `guide/patterns/error-recovery.md` | 1 | checked |
| `guide/patterns/multi-agent-orchestration.md` | 1 | checked |
| `guide/patterns/production-monitoring.md` | 1 | checked |
| `guide/patterns/rag-enterprise.md` | 1 | checked |
| `guide/patterns/scaling-strategies.md` | 1 | checked |
| `guide/patterns/security-best-practices.md` | 2 | checked |
| `guide/permission-based-rag.md` | 23 | checked |
| `guide/prompts.md` | 6 | checked |
| `guide/providers.md` | 23 | checked |
| `guide/rag-evaluation.md` | 9 | checked |
| `guide/rag-query-transformers.md` | 7 | checked |
| `guide/reference-architecture.md` | 4 | checked |
| `guide/reliability.md` | 37 | checked |
| `guide/replacing-a-python-sidecar.md` | 3 | checked |
| `guide/secrets.md` | 3 | checked |
| `guide/speech.md` | 5 | checked |
| `guide/stt-domain-model.md` | 20 | checked |
| `guide/tapir-tools.md` | 5 | checked |
| `guide/vector-store.md` | 51 | checked |
| `guide/zio.md` | 7 | checked |
| `index.md` | 1 | checked |
| `reference/doctor.md` | 2 | checked |
| `reference/model-metadata.md` | 5 | checked |
| `scala-ai/agents.md` | 2 | checked |
| `scala-ai/llm-framework.md` | 3 | checked |
| `scala-ai/providers.md` | 2 | checked |
| `scala-ai/rag.md` | 4 | checked |
| `tutorials/support-assistant/part-1-first-call.md` | 3 | checked |
| `tutorials/support-assistant/part-2-typed-answers.md` | 3 | checked |
| `tutorials/support-assistant/part-3-tools.md` | 3 | checked |
| `tutorials/support-assistant/part-4-help-centre.md` | 4 | checked |
| `tutorials/support-assistant/part-5-safety-memory.md` | 3 | checked |
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
| 2 | `guide/structured-output.md` | 6 |
| 3 | `guide/writing-a-provider.md` | 23 |
| 4 | `guide/json-libraries.md` | 12 |
| 5 | `guide/java.md` | 1 |
| 6 | `guide/observability/index.md` | 21 |
| 7 | `guide/observability/micrometer.md` | 4 |
| 8 | `guide/production-deployment.md` | 15 |
| 9 | `guide/pekko.md` | 8 |
| 10 | `guide/derived-schemas.md` | 8 |
| 11 | `guide/testing-with-the-testkit.md` | 20 |
| 12 | `examples/cookbook/caching.md` | 2 |
| 13 | `examples/cookbook/document-qa.md` | 1 |
| 14 | `examples/cookbook/email-extraction.md` | 1 |
| 15 | `examples/cookbook/fallback.md` | 2 |
| 16 | `examples/cookbook/folder-qa.md` | 2 |
| 17 | `examples/cookbook/guardrails.md` | 1 |
| 18 | `examples/cookbook/judge.md` | 1 |
| 19 | `examples/cookbook/memory.md` | 1 |
| 20 | `examples/cookbook/multi-agent-graph.md` | 1 |
| 21 | `examples/cookbook/streaming.md` | 1 |
| 22 | `examples/cookbook/structured-output.md` | 2 |
| 23 | `examples/cookbook/summarise.md` | 1 |
| 24 | `examples/cookbook/tool-calling.md` | 1 |
| 25 | `reference/migration.md` | 85 |
| 26 | `migrations/0-4-1-to-0-5-0.md` | 4 |
| 27 | `migrations/0x-to-1x.md` | 3 |
| 28 | `migrations/v0.2.9-to-v0.3.0.md` | 10 |
| 29 | `reference/testing-guide.md` | 4 |
| 30 | `reference/postgres-memory-store.md` | 4 |
| 31 | `reference/workspace-sandbox.md` | 2 |
| 32 | `reference/dependency-conflicts.md` | 1 |
| 33 | `reference/api-stability.md` | 1 |
| 34 | `reference/contributing.md` | 2 |
| 35 | `reference/review-guidelines.md` | 57 |
| 36 | `api/index.md` | 9 |
| 37 | `reference/rag-benchmark-results.md` | 2 |

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

