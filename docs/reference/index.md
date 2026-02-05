---
layout: page
title: Reference
nav_order: 7
has_children: true
---

# Reference Documentation

Technical reference materials for LLM4S development and maintenance.

## Project Documentation

- **[Migration Guide](migration)** - Upgrade between versions
- **[Scalafix Rules](scalafix)** - Linting rules and code quality
- **[Test Coverage](test-coverage)** - Coverage tooling and thresholds
- **[Testing Guide](testing-guide)** - How to write tests (contributor-focused)
- **[Code Review Guidelines](review-guidelines)** - Best practices from PR feedback
- **[Release Process](release)** - How releases are created
- **[Configuration Boundary](configuration-boundary)** - How configuration is isolated from core code

## Roadmap & Planning

- **[Project Roadmap](roadmap)** - Development roadmap, production readiness, and future plans (single source of truth)
- **[Design Documents](https://github.com/llm4s/llm4s/tree/main/docs/design)** - Detailed architecture docs

## Contributing

- **[Contributing Guide](contributing)** - How to contribute to LLM4S
- **[Code of Conduct](https://github.com/llm4s/llm4s/blob/main/CODE_OF_CONDUCT.md)** - Community guidelines

## External Resources

- **GitHub Repository**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Issue Tracker**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Pull Requests**: [GitHub PRs](https://github.com/llm4s/llm4s/pulls)

## Design Documents

Detailed design documents for agent framework phases:

| Document | Description |
|----------|-------------|
| [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md) | Comprehensive feature comparison and roadmap |
| [Phase 1.1: Conversations](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md) | Functional conversation management |
| [Phase 1.2: Guardrails](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md) | Input/output validation framework |
| [Phase 1.3: Handoffs](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md) | Agent-to-agent delegation |
| [Phase 1.4: Memory](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md) | Short/long-term memory |
| [Phase 2.1: Streaming](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md) | Agent lifecycle events |
| [Phase 2.2: Async Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md) | Parallel tool execution |
| [Phase 3.2: Built-in Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md) | Standard tool library |
| [Phase 4.1: Reasoning](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md) | Extended thinking support |
| [Phase 4.3: Serialization](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md) | State persistence |

## API Specifications

- [Tool Calling API Design](https://github.com/llm4s/llm4s/blob/main/docs/tool-calling-api-design.md)
- [Workspace Protocol](https://github.com/llm4s/llm4s/blob/main/docs/workspace-agent-protocol.md)
- [Langfuse Workflow Patterns](https://github.com/llm4s/llm4s/blob/main/docs/langfuse-workflow-patterns.md)

## Community Resources

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8) - Project template

## License

LLM4S is released under the [MIT License](https://github.com/llm4s/llm4s/blob/main/LICENSE).

---

**Questions?** [Ask in Discord](https://discord.gg/4uvTPn6qww) or [open an issue](https://github.com/llm4s/llm4s/issues).
