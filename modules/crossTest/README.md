# Cross-Version Testing Strategy

## Overview

This directory contains test projects that verify cross-version compatibility of LLM4S across **Scala 2.13** and **Scala 3.x**. The same test logic lives in both `scala2/` and `scala3/`; only the package name differs (`org.llm4s.sc2` vs `org.llm4s.sc3`). Running both suites ensures behavior is consistent across versions.

**Important:** Cross-test projects use `dependsOn(core)`. They run against the **locally compiled `core`** project in the same build, not against published artifacts. See [Limitations](#limitations) below.

## Test Projects Structure

- `scala2/` – Tests for Scala 2.13 (sbt project: `crossTestScala2`)
- `scala3/` – Tests for Scala 3.x (sbt project: `crossTestScala3`)

## What Is Implemented

1. **Test cases in both Scala versions** – Every cross-test has a counterpart in the other version with identical logic (only package differs).
2. **Positive and negative test cases** – Where applicable, tests include:
   - **Positive:** valid construction, successful execution, expected structure.
   - **Negative:** invalid inputs (e.g. empty apiKey/baseUrl), unknown tool name, unsupported provider; assertions use `intercept` or `Left`/`None` as appropriate.
3. **Version-specific behavior** – `VersionTest` explicitly checks Scala 2 vs 3 syntax (e.g. enum). Other tests document in class-level comments that the same logic runs on both versions and that behavior is intended to be identical (any divergence would fail one version’s run).

## Limitations

- **No verification against published artifacts by default.** The build uses `crossTestScala2.dependsOn(core)` and `crossTestScala3.dependsOn(core)`. So `testCross` runs tests against the **current build’s compiled `core`**, not against a published JAR. Packaging and dependency-resolution issues that only appear when consuming a published artifact are not covered by the default test run.
- **`fullCrossTest`** runs `+publishLocal` then `testCross`. Publishing ensures the repo can publish, but the cross-test projects still resolve `core` via the project dependency; they do not consume the newly published local JAR unless the build is changed to depend on the published module instead of the project.

## Available Commands

### Basic cross-testing (runs against local `core`)

```text
sbt testCross
```

Alias: `;++2.13.16 crossTestScala2/test;++3.7.1 crossTestScala3/test`

- Runs tests for both Scala 2.13 and 3.7.1.
- Uses the locally compiled `core` project (same build).

### Full verification (clean, publish locally, then run cross-tests)

```text
sbt fullCrossTest
```

Alias: `;clean ;crossTestScala2/clean ;crossTestScala3/clean ;+publishLocal ;testCross`

- Cleans and publishes all versions to the local repository, then runs `testCross`.
- Cross-tests still run against the project dependency unless the build is changed to use the published artifact.

## Current Test Coverage

Tests live in:

- `scala2/src/test/scala/org/llm4s/sc2/`
- `scala3/src/test/scala/org/llm4s/sc3/`

### Provider config parsing

- **OllamaConfigCrossTest** – Positive: fromValues, context/reserve, unknown model fallback. Negative: throw if baseUrl empty.
- **OpenAIConfigCrossTest** – Positive: model, baseUrl, organization, context/reserve. Negative: throw if apiKey or baseUrl empty.
- **AzureConfigCrossTest**, **AnthropicConfigCrossTest**, **ZaiConfigCrossTest**, **GeminiConfigCrossTest**, **DeepSeekConfigCrossTest** – Positive: fromValues and defaults. Negative: throw on empty required fields where applicable.

All config tests use pure construction (no env, config files, or network).

### Builtin tools and ToolRegistry

- **BuiltinToolsCrossTest** – Positive only: core, safe(), withFiles(), development(); tool names and exclusions (e.g. safe excludes write/shell).
- **ToolRegistryCrossTest** – Positive: registration, getTool, execute, executeAll, getOpenAITools, getToolDefinitions("openai"|"anthropic"). Negative: unknown tool (None, UnknownFunction), empty registry, unsupported provider in getToolDefinitions (throws).

### Tool schema serialization

- **SchemaCrossTest** – Positive only: StringSchema, IntegerSchema, NumberSchema, BooleanSchema, ObjectSchema, ArraySchema, NullableSchema via toJsonSchema; builtin tool toOpenAITool name/description/parameters preserved.

### Agent / tool integration

- **AgentToolIntegrationCrossTest** – Positive only: Agent.initialize with stub LLMClient and BuiltinTools.core or safe(); conversation, tools, status, initialQuery, systemMessage, schema exposure (no network).

### Other

- **SafeParameterExtractorTest** – Positive and negative extraction and error messages.
- **VersionTest** – Explicit Scala 2 vs 3 check (e.g. enum syntax).

## Adding New Cross-Tests

1. Add the **same** test class in both `scala2/.../sc2/` and `scala3/.../sc3/` with identical logic (only package differs).
2. Use ScalaTest and the same style as existing cross-tests.
3. Prefer behavior-based assertions (e.g. contains, non-empty) over brittle exact counts or order.
4. Add both positive and negative cases where safe (invalid inputs, unsupported options).
5. In the class docstring, note that the same logic runs on both versions and that behavior is intended to be identical.
6. Avoid network, env vars, or real API keys; use stubs or pure construction.

## Future Improvements

- [ ] **Run crossTests against published local JARs** – e.g. make crossTest depend on `"org.llm4s" %% "llm4s-core" % version` from `Resolver.defaultLocal` after `publishLocal`, so that `fullCrossTest` truly validates the published artifact. (Current build uses `dependsOn(core)`.)
- [ ] Add automated binary compatibility checking (e.g. MiMa).
- [ ] Add performance benchmarks across versions.
