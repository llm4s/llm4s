# Config Policy Module

This module provides a lightweight governance layer for LLM4S prompt/model configuration.

## What it includes

- `CatalogEnvironment` for tiered policy (dev / staging / prod).
- `ConfigPolicy` DSL with presets (`devSandbox`, `prodSafeDefaults`).
- `ConfigPolicyEngine` for evaluating provider config against policies.
- `CheckPolicies` CLI entrypoint for CI gating.

## Run locally

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev"
```

With an explicit config file (recommended for reproducible checks):

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev --config config/examples/application-policy-smoke.conf"
```

For ad-hoc usage you can rely on environment variables (e.g. `LLM_MODEL`, `OLLAMA_BASE_URL`) with `reference.conf` defaults.

## CI

The workflow runs `CheckPolicies` as a **smoke test**: it proves the CLI and dev policy path work; it does not start a real Ollama server. The smoke config lives at `config/examples/application-policy-smoke.conf`.
