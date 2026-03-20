# Config Policy Module

This module provides a lightweight governance layer for LLM4S prompt/model configuration.

## What it includes

- Catalog primitives (`CatalogEntry`, `CatalogEnvironment`) for prompt/model registrations.
- `ConfigPolicy` DSL with simple presets (`devSandbox`, `prodSafeDefaults`).
- `ConfigPolicyEngine` for evaluating provider config against policies.
- `CheckPolicies` CLI entrypoint for CI gating.

## Run locally

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev"
```

For CI usage, set provider environment variables as usual (e.g. `LLM_MODEL`, `OPENAI_API_KEY`).

