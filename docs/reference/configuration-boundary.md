---
layout: page
title: Configuration Boundary
description: "LLM4S keeps configuration loading at the application edge: core code never reads configuration directly, and PureConfig access lives in org.llm4s.config."
parent: Reference
---

# Configuration Boundary

We keep configuration loading at the application edge and core code configuration‑agnostic.

- Core main code must not read configuration directly. All PureConfig/env/system property access lives only in `org.llm4s.config`; everywhere else consumes typed settings injected from the application edge.
- `Llm4sConfig` is for edge use (samples, CLIs, tests). Core main sources should not reference it.
- Scalafix enforces this: in `modules/core/src/main/scala`, imports/uses of `Llm4sConfig`, `ConfigSource.default`, `sys.env`, or `System.getenv` are blocked (except inside `org.llm4s.config`). Tests and runnable mains are exempt.
- To pass configuration into core, load it at the edge (e.g., via `Llm4sConfig`) and inject the typed settings into your builders/factories.
