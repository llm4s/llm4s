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
| `prod` / `prod-safe` (`ConfigPolicy.prodSafeDefaults`) | `openai`, `anthropic`, `azure`, `gemini`, `deepseek`, with pinned model patterns; every chat section must set its own `apiKey` |

## Explicit keys in prod

A chat section with no `apiKey` of its own uses its vendor's shared
`llm4s.credentials.<provider>.apiKey` (bound to `OPENAI_API_KEY` and the like by each provider
module). The `prod` preset's `ownApiKey` rule (`ConfigPolicy.withOwnApiKeyRequired`) flags every
such section - not only the default - for providers that require a key, so a section meant for a
second account cannot silently bill the default one. `CheckPolicies` reads each section's key
source with `Llm4sConfig.apiKeySourcesFrom` and applies `ConfigPolicyEngine.checkApiKeySources`.
The `dev` preset does not enable it.

The generic `openai-compatible` provider (from `llm4s-openai-compatible`) can point at any
endpoint, so the dev preset allows it - that is how local servers such as vLLM, LM Studio and
llama.cpp are reached - but the prod preset does not: a prod config naming it fails with
`[allowedProviders] Provider 'openai-compatible' is not allowed`. To use it in production, allow it
explicitly, and pin the endpoints and models it may use:

```scala
val policy = ConfigPolicy.prodSafeDefaults
  .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
  .withAllowedModelPatterns(
    "^openai/gpt-4o(-mini)?$", // ...and the other preset patterns you still need
    "^openai-compatible/openai/gpt-oss-120b$"
  )
  .withRequiredBaseUrlPattern(
    CatalogEnvironment.Prod,
    "^https://(api\\.openai\\.com/v1|api\\.groq\\.com/openai/v1)$"
  )
```

Note that `withAllowedProviders` and `withAllowedModelPatterns` replace the preset's lists rather
than adding to them, so repeat the entries you want to keep. Patterns are unanchored regular
expressions matched against `<provider>/<model>`, so anchor them. The base-URL pattern is one
per environment and is checked against every provider's config in that environment, not only
`openai-compatible`'s, so it must name every endpoint you use. Both presets cap `contextWindow` at
128000. Per-provider recipes are in the
[providers guide](../../docs/guide/providers.md#openai-compatible-endpoints).

## Run locally

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev"
```

With an explicit config file (recommended for reproducible checks):

```bash
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev --config config/examples/application-policy-smoke.conf"
```

`CheckPolicies` evaluates the named provider sections under `llm4s.providers` (see the
[configuration guide](../../docs/getting-started/configuration.md#named-provider-sections)); any
environment variables it sees are the ones those sections bind with `${?VAR}`, plus the vendor
key variables the provider modules bind under `llm4s.credentials`.

For ad-hoc checks without a config file there is the separate env engine, `EnvCheckPolicies`,
which reads `LLM_PROVIDER`, `LLM_MODEL`, `LLM_MAX_TOKENS`, `LLM_REASONING_BUDGET`, `LLM_REGION`
(or `AZURE_REGION`) and `OPENAI_BASE_URL` / `OPENAI_COMPATIBLE_BASE_URL` directly from the
environment. Those variables are this tool's own inputs: the llm4s library does not read them.

```bash
LLM_PROVIDER=openai LLM_MODEL=gpt-4o-mini \
  sbt "configPolicy/runMain org.llm4s.configpolicy.EnvCheckPolicies --env=prod --preset=prod-safe"
```

## CI

The workflow runs `CheckPolicies` as a **smoke test**: it proves the CLI and dev policy path work; it does not start a real Ollama server. The smoke config lives at `config/examples/application-policy-smoke.conf`.
