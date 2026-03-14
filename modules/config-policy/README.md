# Config Policy (policy-as-code for LLM config)

This module provides a policy-as-code validation layer for llm4s provider/model configuration. It is intended for CI and governance: enforce allowed providers, models, token limits, and base URL/region rules.

## Quick start

```bash
# Check current environment config against prod policy (uses Llm4sConfig default source)
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=prod"

# Check an example config file against dev policy (no API keys required)
sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=dev --config=config/examples/application-dev.conf"
```

Exit code 0 = pass, 1 = config load failed or policy violated.

## Policy presets

| Preset       | Use case              | Restrictions |
|-------------|------------------------|---------------|
| `permissive`| No checks              | None          |
| `dev` / `dev-sandbox` | Dev/sandbox   | Allowed providers: openai, anthropic, ollama, gemini, deepseek. Max context 128K, max reserve 8192. |
| `prod` / `prod-safe`  | Production   | Allowed providers and model patterns; max context 128K, max reserve 4096. |

Select with `--env=dev` or `--env=prod` (default).

## Extending policies

- **Programmatic:** Build a `ConfigPolicy` in code and run `ConfigPolicyRunner.check(providerConfig, policy)`.
- **Presets:** Use `ConfigPolicy.preset("dev")` or define your own preset in `ConfigPolicy` and pass it to the runner.
- **Custom rules:** Add new fields to `ConfigPolicy` and corresponding checks in `ConfigPolicyRunner.check`.

Example custom policy:

```scala
val myPolicy = ConfigPolicy.prodSafeDefaults
  .withAllowedProviders("openai", "azure")
  .withRequiredBaseUrlPattern(".*\\.azure\\.com.*")
```

## Example configs

- `config/examples/application-dev.conf` — Ollama (no API key), for CI and local dev.
- `config/examples/application-prod.conf` — OpenAI example; set `OPENAI_API_KEY` in env when running.

## CI

The main CI workflow runs the config policy check against `config/examples/application-dev.conf` with `--env=dev`, so no secrets are required.
