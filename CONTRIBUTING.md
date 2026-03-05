# Contributing to LLM4S

Thank you for your interest in contributing to LLM4S!

## Quick Start

1. **Read the docs:**
   - [AGENTS.md](AGENTS.md) - Repository structure, build commands, and testing
   - [CLAUDE.md](CLAUDE.md) - Code conventions, patterns, and guidelines

2. **Open an issue first:**
   - Search [existing issues](https://github.com/llm4s/llm4s/issues) to avoid duplicates
   - Use [issue templates](https://github.com/llm4s/llm4s/issues/new/choose) for bugs, features, or enhancements
   - Wait for maintainer feedback before coding

3. **Fork and setup:**
   ```bash
   # Fork on GitHub, then clone
   git clone https://github.com/YOUR-USERNAME/llm4s.git
   cd llm4s
   git remote add upstream https://github.com/llm4s/llm4s.git
   
   # Install pre-commit hook
   ./hooks/install.sh
   ```

4. **Create a branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

5. **Make your changes:**
   - Follow code conventions in [CLAUDE.md](CLAUDE.md)
   - Use `Result[A]` for errors (not exceptions)
   - Configure at app edge only (see [AGENTS.md](AGENTS.md#configuration-boundary))
   - Write tests mirroring source structure
   - Run `sbt scalafmtAll` before committing

6. **Test thoroughly:**
   ```bash
   sbt scalafmtAll        # Format code
   sbt +compile           # Compile all versions
   sbt +test              # Run all tests
   sbt buildAll           # Full pipeline check
   ```

7. **Submit PR:**
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

## Build Commands

See [AGENTS.md](AGENTS.md#build-test-and-development-commands) for complete list:

```bash
sbt compile           # Compile active Scala version
sbt +compile          # Compile all versions
sbt test              # Run tests
sbt +test             # Run tests all versions
sbt buildAll          # Full pipeline (compile + test all versions)
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

## Contributing Examples

We've moved examples to a dedicated repository to keep the framework focused and examples organized.

**Add new examples here:** https://github.com/llm4s/llm4s-examples

### Contributing to the Framework

When your contribution adds new framework features:

1. Create a corresponding example in [llm4s-examples](https://github.com/llm4s/llm4s-examples)
2. Update API documentation in `docs/`
3. Add tests to ensure framework behavior

See [llm4s-examples CONTRIBUTING.md](https://github.com/llm4s/llm4s-examples/blob/main/CONTRIBUTING.md) for example contribution guidelines.

## Getting Help

- **Issues:** [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Discussions:** [GitHub Discussions](https://github.com/llm4s/llm4s/discussions)
- **Discord:** https://discord.gg/4uvTPn6qww
- **Docs:** [Documentation site](https://llm4s.github.io/llm4s/)

---

**Thank you for contributing!** 🎉
