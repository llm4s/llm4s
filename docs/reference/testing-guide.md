---
layout: page
title: Testing Guide
nav_order: 8
parent: Reference
---

# Testing Guide (Contributor-Focused)

This guide explains **how contributors should write, run, and reason about tests in LLM4S**. It is intended for contributors adding new features, fixing bugs, or refactoring existing code.

> This guide focuses on *test design and contributor practices*. For coverage tooling, thresholds, and CI enforcement, see the [Test Coverage (scoverage)](test-coverage) guide.

---

## 1. Scope and Philosophy

This document describes **testing expectations for contributors**. It does **not** define coverage thresholds, sbt configuration, or CI enforcement.

## 2. Testing Philosophy in LLM4S

LLM4S prioritizes:

* **Determinism** – tests must be repeatable and not depend on external LLM APIs
* **Fast feedback** – unit tests should run quickly during development
* **Clear intent** – tests should document *expected behavior*, not implementation details
* **Layered confidence** – different test types exist for different guarantees

---

## 3. Types of Tests

### Unit Tests

**Purpose:** Verify small, isolated pieces of logic

* No network calls
* No real LLM providers
* Use mocks or fakes for providers
* Fast (< milliseconds per test)

**Typical use cases:**

* Prompt construction
* Request/response parsing
* Configuration handling
* Error mapping and retries

### Integration Tests

**Purpose:** Validate interaction between multiple components

* May involve provider abstractions
* Still deterministic
* Often use mocked HTTP or in-memory providers

**Typical use cases:**

* End-to-end request pipelines
* Streaming response handling
* Async workflows

### E2E Tests (Limited)

**Purpose:** Sanity-check real-world behavior

* Used sparingly
* May require environment variables
* Not required for most PRs

> Contributors are **not expected** to add new E2E tests unless explicitly requested by maintainers.

---

## 4. Test Directory Structure

Tests generally mirror the source layout:

```
llm4s/
  modules/
    <module-name>/
      src/
        main/
          scala/
            <package>/
              ...
        test/
          scala/
            <package>/*Spec.scala
```

Conventions:

* Use `*Spec.scala` or `*Test.scala` suffix (prefer `*Spec.scala` for new tests)
* One primary behavior per spec file
* Group related tests using the ScalaTest styles used in this project: `AnyFlatSpec` with `'ClassName' should 'behavior'` / `it should 'behavior'`, and `AnyFunSuite` with `test("behavior") { ... }` blocks

---

## 5. Writing Effective Tests

### Prefer Behavior Over Implementation

Good:

* "returns a structured error when provider times out"

Avoid:

* "calls method X before method Y"

### Keep Tests Focused

* One assertion intent per test
* Avoid large, multi-purpose tests

### Use `Result[A]` for Assertions

LLM4S uses `Result[A]` (an alias for `Either[LLMError, A]`) for error handling. When testing functions that return `Result`:

```scala
result shouldBe Right(expected)           // Success case
result shouldBe a[Left[_, _]]             // Failure case
result.left.value shouldBe a[SomeError]   // Specific error type
```

### Name Tests Clearly

Test names should read like documentation:

```
"fails fast when API key is missing"
```

---

## 6. Mocking LLM Providers

**Never call real LLM APIs in unit or integration tests.**

Recommended approaches:

* Fake provider implementations
* Stubbed responses with fixed outputs
* Deterministic streaming sequences

Mocked providers should:

* Return predictable tokens
* Simulate error cases (timeouts, invalid responses)
* Be cheap to construct

---

## 7. Testing Async and Streaming Code

When testing async logic:

* Await results explicitly
* Avoid sleeps or timing-based assertions
* Prefer futures/promises that complete deterministically

For streaming responses:

* Collect emitted tokens
* Assert on sequence and completion
* Test cancellation and early termination

---

## 8. Running Tests Locally

The project provides a handful of sbt commands that contributors use frequently. The table below summarizes the most common ones and when to use them.

| Task                       | Command                     | When to use it                                                 |
|----------------------------|-----------------------------|----------------------------------------------------------------|
| Compile (current version)  | `sbt compile`               | Quick compile during development or before starting tests      |
| Run tests for a module     | `sbt core/test`             | Execute tests only in a specific sub‑module, e.g. `core`       |
| Run all tests              | `sbt test`                  | Default; runs tests for the current Scala version              |
| Full CI‑like pipeline      | `sbt buildAll`              | Compiles and tests every aggregated module for the current build |
| Format code                | `sbt scalafmtAll`           | Apply project‑wide formatting (required before PRs)            |
| Run an integration tier    | `sbt testIntegration`       | Suites in `modules/it` that need a real service — see section 9 |

### Using the table

* **Single-module work:** run `sbt <module>/test` while iterating on that code.
* **Scala compatibility:** the current supported build is Scala 3.7.1.
* **CI sanity check:** `buildAll` mirrors the local compile/test pipeline and is handy when touching many modules.
* **Formatting:** `scalafmtAll` is cheap and keeps your changes tidy; the pre‑commit hook runs it automatically.

The following commands are still handy in day‑to‑day development:

```bash
sbt "testOnly *MySpec"   # run one suite
sbt ~test                # continuous feedback as you code
```

> **Scala version note:** LLM4S currently builds on Scala 3.7.1. Historical design notes may mention older cross-building plans, but contributors should follow the current `build.sbt` and CI matrix.

---

## 9. Integration Test Tiers (`modules/it`)

Suites that need something the machine may not have - a database, a container image, a local
model server, a paid API key - live in `modules/it`, not in the module they exercise. That
module is deliberately outside the default `sbt test` dependency-free tier, so each suite has
to say which tier does run it.

**A suite declares its tier by annotating the class**, with exactly one tag from
`org.llm4s.it.tags`:

| Tag | Needs | Command | CI |
|---|---|---|---|
| `@Local` | nothing external | `sbt test` | every PR |
| `@Docker` | Postgres/pgvector, Qdrant or Neo4j | `sbt testIntegration` | every PR (service containers) |
| `@Workspace` | Docker + a built `workspace-runner` image | `sbt testWorkspace` | pushes to `main` |
| `@Ollama` | a local Ollama with `qwen2.5:0.5b` pulled | `sbt testOllama` | pushes to `main` |
| `@Cloud` | live provider API keys (real money) | `sbt testSmoke` | manual `workflow_dispatch` |

```scala
import org.llm4s.it.tags.Docker

@Docker
class PgVectorStoreSpec extends AnyWordSpec with Matchers {
```

Tiers are tags rather than name patterns in a `testOnly` argument because a name pattern
fails silently: a suite matching no pattern is run by nothing and reports nothing. `sbt
it/itTierCheck` - part of Quick Checks in CI - fails the build when a suite declares no tier
or more than one.

### Skipping is a local convenience, not a CI outcome

A suite whose dependency is missing cancels its tests, which ScalaTest reports as skipped.
That is right on a laptop and wrong in the CI job that exists to provide that dependency:
there, a skip is indistinguishable from a pass.

So each tier's CI job sets `LLM4S_IT_STRICT=true`, and `Tier.require` turns "not available"
into a failure instead of a cancellation:

```scala
import org.llm4s.it.Tier

Tier.require(pgUrl.isDefined, "PGVECTOR_TEST_URL not set")
```

Use `Tier.require` in place of `assume(...)` for anything the tier's job is meant to supply.
It is also worth running strictly by hand once the services are up locally, to confirm a suite
really executes:

```bash
docker run -d -p 5432:5432 -e POSTGRES_PASSWORD=postgres pgvector/pgvector:pg16
docker run -d -p 6333:6333 qdrant/qdrant
docker run -d -p 7687:7687 -e NEO4J_AUTH=neo4j/llm4stest neo4j:5

export PGVECTOR_TEST_URL=jdbc:postgresql://localhost:5432/postgres
export PGVECTOR_USER=postgres PGVECTOR_PASSWORD=postgres
export PGVECTOR_TEST_USER=postgres PGVECTOR_TEST_PASSWORD=postgres
export POSTGRES_TEST_ENABLED=true POSTGRES_PASSWORD=postgres
export QDRANT_TEST_URL=http://localhost:6333
export NEO4J_URI=bolt://localhost:7687 NEO4J_USER=neo4j NEO4J_PASSWORD=llm4stest
export LLM4S_IT_STRICT=true

sbt testIntegration
```

---

## 10. Tests in CI

* All PRs must pass the full test suite
* Flaky tests will be rejected
* Avoid tests that depend on:

  * Network
  * Time
  * Randomness (unless seeded)

If your test fails in CI but not locally, it is usually a **non-determinism issue**.

---

## 11. When to Add or Update Tests

You should add or update tests when:

* Fixing a bug
* Adding a new feature
* Changing public behavior
* Refactoring critical logic

You usually do **not** need new tests for:

* Documentation-only changes
* Pure formatting or refactors with no behavior change

---

## 12. Getting Help

If you're unsure how to test something:

* Ask in the issue or PR
* Mention maintainers
* Look for similar tests in the codebase

Clear tests help reviewers help you faster.

---

*This document is contributor-focused and intentionally practical. Suggestions for improvement are welcome.*
