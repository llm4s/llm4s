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

Common commands:

```bash
sbt test           # Run tests for default Scala version
sbt +test          # Run tests for all Scala versions (2.13 and 3.x)
```

To run a specific suite:

```bash
sbt "testOnly *MySpec"
```

To run tests continuously:

```bash
sbt ~test
```

> **Cross-compilation note:** LLM4S supports Scala 2.13 and 3.x. Before submitting a PR, run `sbt +test` to ensure compatibility across both versions.

---

## 9. Tests in CI

* All PRs must pass the full test suite
* Flaky tests will be rejected
* Avoid tests that depend on:

  * Network
  * Time
  * Randomness (unless seeded)

If your test fails in CI but not locally, it is usually a **non-determinism issue**.

---

## 10. When to Add or Update Tests

You should add or update tests when:

* Fixing a bug
* Adding a new feature
* Changing public behavior
* Refactoring critical logic

You usually do **not** need new tests for:

* Documentation-only changes
* Pure formatting or refactors with no behavior change

---

## 11. Finding Where Tests Are Needed

The llm4s project provides **automated coverage analysis tools** to help you identify which parts of the codebase would benefit most from additional tests.

### Using Coverage Gap Analysis

```bash
# Generate coverage report
sbt coverage core/test coverageAggregate

# Analyze coverage gaps
python3 scripts/analyze-coverage.py --local
```

This generates a detailed report showing:
- Files with the lowest test coverage
- Specific uncovered functions
- Module-by-module coverage breakdown
- Actionable recommendations

For detailed instructions, see [Test Coverage Gap Analysis Guide](coverage-gaps-guide.md).

### Starting with High-Impact Areas

The coverage report will show you:

1. **Lowest-coverage files** - These are the highest priority
2. **Uncovered functions** - Specific functions that have no tests
3. **Modules needing attention** - Which subsystems need work most

Pick one low-coverage file or function and write tests for it. This has the most impact and gives reviewers clear wins to see in PRs.

---

## 12. Getting Help

If you're unsure how to test something:

* Ask in the issue or PR
* Mention maintainers
* Look for similar tests in the codebase
* Use the [coverage gap analysis tools](coverage-gaps-guide.md) to find high-impact areas

Clear tests help reviewers help you faster.

---

*This document is contributor-focused and intentionally practical. Suggestions for improvement are welcome.*
