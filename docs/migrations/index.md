---
layout: page
title: Migrations
description: "Step-by-step guides for upgrading between LLM4S versions, including the 0.4.1 to 0.5.0 modularisation and the path to 1.0."
search_title: "Migrate between LLM4S versions: upgrade guides"
nav_order: 11
has_children: true
---

# Migration Guides

Step-by-step guides for upgrading between LLM4S versions.
{: .fs-6 .fw-300 }

| Guide | Summary |
|---|---|
| [0.x → 1.0](0x-to-1x) | Start here: the upgrade path from any 0.x release, with an entry point per release into the [Migration Guide](../reference/migration) |
| [0.6.0 → 0.7.0](0x-to-1x#from-060) | Typed finish reasons, `Thinking`, content-filter and cut-stream errors, `AgentStatus.Truncated`, source-scoped RAG registries |
| [0.5.1 → 0.6.0](0x-to-1x#from-051-to-060) | Growth-safe types, `Secret` credentials, content-free tracing, provider modules no longer brought by the Java API |
| [0.5.0 → 0.5.1](0x-to-1x#from-050-to-051) | Multimodal content parts; harness middleware placement |
| [0.4.1 → 0.5.0](0-4-1-to-0-5-0) | The modularisation release: `llm4s-core` splits into per-concern artifacts, the agent runtime is rewritten, API keys are read from each vendor's variable |
| [v0.2.9 → v0.3.0](v0.2.9-to-v0.3.0) | Replace exception-throwing APIs with type-safe `Result[T]` |

The [Migration Guide](../reference/migration) holds the note for every breaking change since 0.4.1; these pages say
which notes apply to the release you are upgrading from.
