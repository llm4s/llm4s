---
layout: page
title: Contribution Guidelines
parent: Reference
nav_order: 7
---

# Contribution Guidelines

Detailed guidelines for contributing to LLM4S with emphasis on code quality, testing, and documentation.

{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What Can You Contribute?

### Code Contributions
- **Features** - New functionality aligned with roadmap
- **Bug Fixes** - Reported issues and edge cases
- **Enhancements** - Improvements to existing features
- **Tests** - Additional test coverage and edge case handling

### Non-Code Contributions
- **Documentation** - Guides, examples, API docs
- **Issue Triage** - Help categorize and organize issues
- **Community** - Answering questions on Discord

---

## The Contribution Workflow

### 1. Find or Create an Issue

**Search first:**
```bash
# GitHub Issues search
https://github.com/llm4s/llm4s/issues?q=is:issue+label:good-first-issue
```

**Good first issues** are marked with `good-first-issue` label.

**Create if not found:**
- For bugs: Use [Bug Report](https://github.com/llm4s/llm4s/issues/new/choose) template
- For features: Use [Feature Request](https://github.com/llm4s/llm4s/issues/new/choose) template
- For improvements: Use [Enhancement](https://github.com/llm4s/llm4s/issues/new/choose) template

**Wait for response:**
- Maintainers will provide guidance
- Confirm approach before coding

### 2. Fork and Setup

```bash
# Fork on GitHub (click button)

# Clone your fork
git clone https://github.com/YOUR-USERNAME/llm4s.git
cd llm4s

# Add upstream
git remote add upstream https://github.com/llm4s/llm4s.git

# Install pre-commit hook
./hooks/install.sh

# Create feature branch
git checkout -b feature/description
```

### 3. Make Your Changes

**Follow conventions:**
- Use `Result[A]` for errors
- Configure at app edge only
- Keep code immutable
- Use type-safe newtypes
- Run `sbt scalafmtAll`

**Write tests:**
- Unit tests for new code
- Edge case coverage
- Cross-version tests if needed

**Update docs:**
- Scaladoc for public APIs
- User guides if needed
- Examples if appropriate

### 4. Test Thoroughly

```bash
# Format code
sbt scalafmtAll

# Compile
sbt +compile

# Run all tests
sbt +test

# Check coverage
sbt coverage test coverageReport

# Cross-compile everything
sbt buildAll
```

**All must pass before submitting PR.**

### 5. Submit Pull Request

**Title format:**
```
[TYPE] Brief description

[FEATURE] Add support for streaming with timeout
[BUG FIX] Fix token counting edge case
[DOCS] Update configuration guide
```

**Include in your PR description:**
- Clear description
- Issue references
- Testing approach
- Documentation updates
- Checklist verification

### 6. Respond to Feedback

- Be responsive to comments
- Ask for clarification if needed
- Push updates to same branch
- Conversation closes PR when satisfied
- Maintainers merge when ready

---

## Code Standards

For detailed conventions (naming, style, import organization), see [CONTRIBUTING.md](../../CONTRIBUTING.md#code-conventions).

**Core principles** - all code must follow:

1. **Error Handling**: Use `Result[A]` composable error handling, not exceptions
2. **Configuration**: Only read config at app edge (main, tests, samples), never in core modules
3. **Type Safety**: Use newtypes for domain values (`ApiKey`, `ModelName`, `ConversationId`)
4. **Immutability**: Prefer immutable data structures and functional updates

See [CONTRIBUTING.md](../../CONTRIBUTING.md#code-conventions) for detailed code pattern examples.

---

## Testing Standards

See [CONTRIBUTING.md](../../CONTRIBUTING.md#testing) for testing requirements and examples.

**Quick checklist:**
- ✅ Place tests in `modules/core/src/test/scala/org/llm4s/` (mirror source structure)
- ✅ Name test files with `Spec` suffix (e.g., `AgentSpec.scala`)
- ✅ Test both happy path and error cases
- ✅ Use ScalaTest's FlatSpec style
- ✅ Mock external dependencies with ScalaMock
- ✅ Maintain 80%+ statement coverage (excluding samples)
- ✅ Run `sbt +test` before submitting PR

---

## Documentation Standards

See [CONTRIBUTING.md](../../CONTRIBUTING.md#documentation) for basic format guidelines.

**For advanced contributions:**

1. **Scaladoc** - Use @param, @return, @example tags; document error cases
2. **Guides** - Add to `docs/guide/` with overview, usage examples, troubleshooting
3. **Examples** - Place in `modules/samples/` with clear comments
4. **API Reference** - Generated from Scaladoc; ensure code examples compile

---

## Release Process

The LLM4S team manages releases. Your contributions will be included automatically.

### Version Numbering

**Pre-release (0.x.y):**
- API may change between versions
- Current stable: 0.1.0-SNAPSHOT

**Stable (1.0.0+):**
- Follows semantic versioning
- MiMa checks for binary compatibility
- Breaking changes require major version bump

---

## Performance Considerations

### JVM Optimization

```scala
// Prefer immutable collections
val list: Seq[Message] = messages :+ newMessage

// Avoid unnecessary object creation
val result = messages.map(_.role)  // Good
val result = messages.map(m => m.role)  // Also good

// Prefer lazy evaluation where appropriate
lazy val config = Llm4sConfig.provider()
```

---

## Getting Help

- **[CONTRIBUTING.md](../../CONTRIBUTING.md)** - Complete contributor guide
- **[AGENTS.md](../../AGENTS.md)** - Repository structure and build commands
- **[CLAUDE.md](../../CLAUDE.md)** - Developer patterns and guidelines
- **Discord:** https://discord.gg/4uvTPn6qww
- **GitHub Discussions:** https://github.com/llm4s/llm4s/discussions

For help, common issues, and build problems, see [CONTRIBUTING.md](../../CONTRIBUTING.md#getting-help).
