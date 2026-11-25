---
layout: page
title: Migrations
description: "Step-by-step guides for upgrading between LLM4S versions, including the 0.4.1 to 0.5.0 modularisation and the upgrade from 0.x to 1.0."
search_title: "Migrate between LLM4S versions: upgrade guides"
nav_order: 11
has_children: true
---

# Migration Guides

Step-by-step guides for upgrading between LLM4S versions.
{: .fs-6 .fw-300 }

| Guide | Summary |
|---|---|
| [0.x → 1.0](0x-to-1x) | Start here: the upgrade path to 1.0.0 from any 0.x release, with the one-line Scalafix command and an entry point per release into the [Migration Guide](../reference/migration) |
| [0.7.0 → 1.0.0](0x-to-1x#from-070-to-100) | The stability release: an error's `provider` is a `ProviderId`, a cut-off `withOutput` answer ends `Truncated`, Anthropic's `max_tokens` default follows the model; the Scalafix rules are published |
| [0.6.0 → 0.7.0](0x-to-1x#from-060-to-070) | The baseline release: one retry rule and typed errors, typed finish reasons, `Thinking`, `ModelName`/`ProviderId` newtypes, final provider clients, the tiered `llm4s-agent`, permission-only RAG with a `SearchIndex` |
| [0.5.1 → 0.6.0](0x-to-1x#from-051-to-060) | Growth-safe types, `Secret` credentials, content-free tracing, provider modules no longer brought by the Java API |
| [0.5.0 → 0.5.1](0x-to-1x#from-050-to-051) | Multimodal content parts; harness middleware placement |
| [0.4.1 → 0.5.0](0-4-1-to-0-5-0) | The modularisation release: `llm4s-core` splits into per-concern artifacts, the agent runtime is rewritten, API keys are read from each vendor's variable |
| [v0.2.9 → v0.3.0](v0.2.9-to-v0.3.0) | Replace exception-throwing APIs with type-safe `Result[T]` |

The [Migration Guide](../reference/migration) holds the note for every breaking change since 0.4.1; these pages say
which notes apply to the release you are upgrading from.
