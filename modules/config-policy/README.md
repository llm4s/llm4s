# Config Policy Module

This module provides a lightweight governance layer for LLM4S prompt/model configuration.

## What it includes

- `CatalogEnvironment` for tiered policy (dev / staging / prod).
- `ConfigPolicy` DSL with presets (`devSandbox`, `prodSafeDefaults`).
- `ConfigPolicyEngine` for evaluating provider config against policies.
- `CheckPolicies` CLI entrypoint for CI gating.

## Presets and the `openai-compatible` provider

| Preset | Allowed providers |
|---|---|
| `dev` / `dev-sandbox` (`ConfigPolicy.devSandbox`) | `openai`, `anthropic`, `ollama`, `gemini`, `deepseek`, `openai-compatible` |
| `prod` / `prod-safe` (`ConfigPolicy.prodSafeDefaults`) | `openai`, `anthropic`, `azure`, `gemini`, `deepseek`, with pinned model patterns |

The generic `openai-compatible` provider (from `llm4s-openai-compatible`) can point at any
endpoint, so the dev preset allows it - that is how local servers such as vLLM, LM Studio and
llama.cpp are reached - but the prod preset does not: a prod config naming it fails with
`[allowedProviders] Provider 'openai-compatible' is not allowed`. To use it in production, allow it
explicitly, and pin the endpoints and models it may use:

```scala
val policy = ConfigPolicy.prodSafeDefaults
  .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
  .withAllowedModelPatterns("openai-compatible/llama-3.3-70b-versatile")
  .withRequiredBaseUrlPattern(CatalogEnvironment.Prod, "https://api\\.groq\\.com/.*")
```

Note that `withAllowedProviders` and `withAllowedModelPatterns` replace the preset's lists rather
than adding to them, so repeat the entries you want to keep.

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
