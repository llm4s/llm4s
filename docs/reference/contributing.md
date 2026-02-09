---
layout: page
title: Contributing Guide
nav_order: 1
parent: Reference
---

# Contributing to LLM4S

This guide covers everything you need to know before opening your first pull request. Please read it fully — PRs that ignore these guidelines will be closed.

---

## Before Your First PR

1. **Read this guide** and the [Code Review Guidelines](review-guidelines)
2. **Fork and clone** the repository
3. **Verify your setup** — run `sbt buildAll` and `sbt +test` to confirm everything compiles and passes
4. **Read `CLAUDE.md`** in the repo root — it contains project conventions, architecture notes, and common commands

---

## Picking an Issue

### Start Small

Your first PR should be small — under ~200 lines of changes. Pick a [`good first issue`](https://github.com/llm4s/llm4s/labels/good%20first%20issue) and get familiar with the codebase, the review process, and the project conventions before tackling anything larger.

Good first contributions:
- Add missing unit tests
- Fix a documentation error
- Address a single-file bug fix
- Improve an error message

### Comment Before You Start

Before writing code, **comment on the issue** saying you'd like to work on it. This avoids duplicate effort and lets maintainers give you pointers.

### A PR Does Not Need to Solve the Entire Issue

A PR that fixes one module, one pattern, or one aspect of an issue is perfectly valid — and often preferred. For example, if an issue asks to "replace `sys.env` in all sample files", a PR that fixes 2-3 files is better than one that tries to fix all 8 at once and gets things wrong.

### Complex Issues Need Discussion First

For issues involving architectural decisions, new modules, or cross-cutting changes:

1. **Read the existing design docs** in `docs/design/`
2. **Comment on the issue** with your proposed approach
3. **Wait for maintainer feedback** before writing code
4. Consider **writing a small experiment** or proof-of-concept to validate your approach

Maintainers will not assign major architectural work to contributors without a track record in the project. Build trust with smaller PRs first.

---

## The Contribution Ladder

| Level | What | Examples |
|-------|------|----------|
| **1. Observe** | Read code, comment on issues, review others' PRs | "I think this edge case is missing..." |
| **2. Small fixes** | Docs, tests, single-file changes (<200 lines) | Fix a broken link, add a unit test |
| **3. Features** | After 1-2 merged PRs, take on larger work | Implement a focused feature from an issue |
| **4. Architecture** | Requires design discussion and maintainer sign-off | New module, API redesign, cross-cutting refactor |

---

## Before Submitting Your PR

Run **all** of these locally before pushing:

```bash
sbt scalafmtAll         # Format code
sbt +test               # Tests pass on both Scala 2.13 and 3.x
```

Also check:

```bash
git diff --stat         # Review the size of your changes — keep it focused
git log --oneline       # Are your commit messages clear?
```

If CI fails on your PR, **fix it before requesting review**. Do not ask reviewers to look at a red build.

---

## PR Requirements

### Size

Keep PRs small and focused. **One change, one reason.**

- First PRs: aim for under ~200 lines
- Experienced contributors: still prefer under ~500 lines
- If your change is larger, discuss it in the issue first and consider splitting into a chain of smaller PRs

### Description

Your PR description must explain:

- **What** changed
- **Why** it's needed (link the issue)
- **How** you tested it

A PR with just "Add feature X" and no context will take longer to review and may be closed.

### Commit Messages

Use imperative mood. Explain the *why*, not just the *what*.

```
# Good
Add retry logic for transient 503 errors from OpenAI
Normalize ujson.Null to ujson.Obj() at provider parsing boundaries
Fix resource leak in GeminiClient streaming response handler

# Bad
updated stuff
fix
changes
wip
```

If your PR has a messy commit history, squash it into clean, logical commits before requesting review.

### No Unrelated Changes

Do not bundle unrelated fixes, refactors, or formatting changes into the same PR. If you spot something else that needs fixing, open a separate issue or PR.

A common mistake: creating your branch from another PR's branch instead of `main`. This pulls in unrelated commits. Always branch from `main`:

```bash
git checkout main
git pull origin main
git checkout -b your-branch-name
```

---

## What Gets PRs Closed

PRs will be closed without detailed review if they:

- **Don't compile or pass tests** — run `sbt +test` locally first
- **Include unrelated changes** from stacked branches (rebase onto `main`)
- **Are very large without prior discussion** — start a conversation on the issue first
- **Ignore feedback** from a previous review round
- **Duplicate existing work** — check open PRs before starting
- **Use patterns banned by the project** — `sys.env` in core code, `throw` for control flow, `Any` types (see [Code Review Guidelines](review-guidelines))

Reviewer time is limited. PRs that clearly haven't followed these guidelines waste that time and slow down the entire project.

---

## Code Standards Quick Reference

These are the most common issues found in PR reviews. The full list is in [Code Review Guidelines](review-guidelines).

### Use `Result[A]`, not exceptions

```scala
// Good
def parseConfig(json: String): Result[Config] =
  if (json.isEmpty) Left(ValidationError("Empty JSON"))
  else Right(Config.parse(json))

// Bad
def parseConfig(json: String): Config =
  if (json.isEmpty) throw new IllegalArgumentException("Empty")
  else Config.parse(json)
```

### Use `Llm4sConfig` at the app edge, not `sys.env`

```scala
// Good — in samples/CLI code
val result = for {
  config <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(config)
} yield client

// Bad — anywhere
val apiKey = sys.env.getOrElse("OPENAI_API_KEY", "")
```

See [Configuration Boundary](configuration-boundary) and [Scalafix Rules](scalafix) for details.

### Run scalafmt

```bash
sbt scalafmtAll
```

The project uses `-Werror` (fatal warnings). Formatting issues, unused imports, and deprecation warnings will all fail the build.

### Write tests

Every PR with new code needs tests. See the [Testing Guide](testing-guide) for details.

---

## Development Workflow

### Setup

```bash
# Fork the repo on GitHub, then:
git clone https://github.com/YOUR-USERNAME/llm4s.git
cd llm4s
sbt buildAll              # Verify everything compiles
sbt +test                 # Verify tests pass
```

### Making Changes

```bash
git checkout main
git pull origin main
git checkout -b fix/short-description    # Use a descriptive branch name

# Make your changes...

sbt scalafmtAll           # Format
sbt +test                 # Test both Scala versions
git add <specific-files>  # Stage only your changes
git commit -m "Fix: short description of what and why"
git push origin fix/short-description
```

### Creating the PR

1. Push your branch to your fork
2. Open a PR against `llm4s/llm4s:main`
3. Fill in the PR template — don't skip any sections
4. Verify CI passes
5. Wait for review — don't ping reviewers unless it's been several days

### After Review

- Address all feedback before re-requesting review
- If you disagree with feedback, explain your reasoning — don't silently ignore it
- Push new commits (don't force-push during review — it makes it harder to see what changed)

---

## Getting Help

- **Issue questions**: Comment on the issue
- **Code questions**: Ask in [Discord](https://discord.gg/4uvTPn6qww)
- **Stuck on a test**: Look for similar tests in the codebase — there are patterns for most scenarios
- **Build problems**: Check [Getting Started](../getting-started/installation) for setup instructions

---

## Related Documentation

- [Code Review Guidelines](review-guidelines) — Detailed coding standards with examples
- [Testing Guide](testing-guide) — How to write tests
- [Configuration Boundary](configuration-boundary) — Config architecture
- [Scalafix Rules](scalafix) — Automated code quality enforcement

---

*This guide reflects actual project expectations. PRs that follow it get reviewed faster and merged sooner.*
