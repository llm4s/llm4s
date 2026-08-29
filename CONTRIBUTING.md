# Contributing to LLM4S

Thank you for your interest in contributing to LLM4S!

## Code of Conduct

This project follows our [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold these standards.

## Developer Certificate of Origin (DCO)

All commits must be signed off to certify you have the right to submit the code under the project's [MIT license](LICENSE), per the [Developer Certificate of Origin](https://developercertificate.org/). Add the `Signed-off-by` trailer by committing with `-s`:

```bash
git commit -s -m "[FEATURE] Add my change"
```

If you forget, sign off your most recent commit with:

```bash
git commit --amend -s --no-edit
```

Pull requests are checked for DCO sign-off automatically in CI.

## Getting Started as a Contributor

### 1. Prerequisites checklist
- [ ] JDK 21+ installed (`java -version`)
- [ ] sbt 1.9+ installed (`sbt -version`)
- [ ] Git configured with your name and email
- [ ] Fork and clone the repo

### 2. First build
```bash
git clone https://github.com/YOUR-USERNAME/llm4s.git
cd llm4s
git remote add upstream https://github.com/llm4s/llm4s.git   # to sync your fork later
sbt compile       # should succeed in ~3 minutes first time
sbt core/test     # run just the unit tests (no API keys needed)
```

### 3. Install the pre-commit hook
```bash
./hooks/install.sh
```

### 4. Your first change (5-minute exercise)
Pick a [good first issue](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) and follow this workflow:
```bash
git checkout -b my-first-contribution
# make the change
sbt scalafmtAll   # format
sbt core/test     # verify
git commit -m "[TEST] Add tests for XYZ"
git push origin my-first-contribution
# Open PR against main
```

### 5. How to run a specific test
```bash
sbt "core/testOnly *PIIDetectorSpec"
```

### 6. How to run a sample
```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
```

### 7. Where to ask for help
- **Discord:** [Join the community](https://discord.gg/4uvTPn6qww)
- **Issues:** [Open an issue](https://github.com/llm4s/llm4s/issues) for bugs, questions, or feature requests
- **Dev Hour:** We host a contributor dev hour on Discord every Tuesday at 17:00 UTC.

## Pull Request Workflow

1. **Read the docs:**
   - [AGENTS.md](AGENTS.md) - Repository structure, build commands, and testing
   - [CLAUDE.md](CLAUDE.md) - Code conventions, patterns, and guidelines

2. **Open an issue first:**
   - Search [existing issues](https://github.com/llm4s/llm4s/issues) to avoid duplicates
   - Use [issue templates](https://github.com/llm4s/llm4s/issues/new/choose) for bugs, features, or enhancements
   - Wait for maintainer feedback before coding

3. **Make your changes:**
   - Follow code conventions in [CLAUDE.md](CLAUDE.md)
   - Use `Result[A]` for errors (not exceptions)
   - Configure at app edge only (see [AGENTS.md](AGENTS.md#configuration-boundary))
   - Write tests mirroring source structure
   - Run `sbt scalafixAll` to catch boundary and syntax violations early
   - Run `sbt scalafmtAll` before committing

4. **Test thoroughly:**
   ```bash
   sbt scalafixAll        # Run scalafix checks (enforced in CI/compile)
   sbt scalafmtAll        # Format code
   sbt +compile           # Compile all versions
   sbt +test              # Run all tests
   sbt buildAll           # Full pipeline check
   ```

5. **Submit PR:**
   - Write clear title: `[FEATURE]`, `[BUG FIX]`, `[DOCS]`, etc.
   - Describe what changed and why
   - Reference related issues: `Fixes #123`
   - Respond to reviewer feedback

## Code Conventions

See [CLAUDE.md](CLAUDE.md) for detailed guidelines. Key points:

- **Naming:** Types `PascalCase`, values `camelCase`, constants `SCREAMING_SNAKE_CASE`
- **Error handling:** Use `Result[A]`, not exceptions
- **Configuration:** Only at app edge (samples, CLIs, tests) - never in core code
- **Type safety:** Use newtypes for domain values (`ApiKey`, `ModelName`)
- **Immutability:** Prefer immutable data structures

## Testing

See [AGENTS.md](AGENTS.md#testing-guidelines) for details:

- Place tests in `modules/core/src/test/scala/org/llm4s/`
- Name tests with `Spec` suffix
- Use ScalaTest's FlatSpec style
- Test both happy path and error cases
- Maintain 80%+ coverage

### Tests that need a real service

Suites needing a database, a container, a local model server or an API key go in
`modules/it`, and each one must declare which tier runs it by annotating the class with
exactly one tag from `org.llm4s.it.tags`. `sbt it/itTierCheck` (run in CI) fails the build
if a suite declares none, so a new suite cannot end up being run by nothing:

| Tag | Needs | Command |
|---|---|---|
| `@Local` | nothing external | `sbt test` |
| `@Docker` | Postgres/pgvector, Qdrant or Neo4j | `sbt testIntegration` |
| `@Workspace` | Docker + a built `workspace-runner` image | `sbt testWorkspace` |
| `@Ollama` | a local Ollama server | `sbt testOllama` |
| `@Cloud` | live provider API keys | `sbt testSmoke` |

Gate on `Tier.require(...)` rather than `assume(...)`: with `LLM4S_IT_STRICT=true`, which
every tier's CI job sets, an unavailable dependency then fails the build instead of skipping
quietly. Full details in the
[Testing Guide](docs/reference/testing-guide.md#9-integration-test-tiers-modulesit).

## Build Commands

See [AGENTS.md](AGENTS.md#build-test-and-development-commands) for complete list:

```bash
sbt compile           # Compile active Scala version
sbt +compile          # Compile all versions
sbt test              # Run tests
sbt +test             # Run tests all versions
sbt buildAll          # Full pipeline (compile + test all versions)
sbt scalafixAll       # Run Scalafix rules
sbt scalafmtAll       # Format code
```

## Documentation

- **Code:** Add Scaladoc to public APIs with `@param`, `@return`, `@example`
- **Guides:** Add to `docs/guide/` for new features
- **Examples:** Add to `modules/samples/` with runnable code
- **API:** Generated from Scaladoc automatically

## Commit Messages

```
[TYPE] Brief description (50 chars max)

Optional detailed explanation.
- Reference issues: Fixes #123, Relates to #456

BREAKING CHANGE: If applicable
```

Types: `[FEATURE]`, `[BUG FIX]`, `[ENHANCEMENT]`, `[REFACTOR]`, `[DOCS]`, `[TEST]`, `[PERF]`

## Getting Help

- **Issues:** [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Discussions:** [GitHub Discussions](https://github.com/llm4s/llm4s/discussions)
- **Discord:** https://discord.gg/4uvTPn6qww
- **Docs:** [Documentation site](https://llm4s.github.io/llm4s/)

---

**Thank you for contributing!** 🎉
