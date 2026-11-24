---
layout: page
title: Reference
description: "Reference for LLM4S: contributing, API stability, compatibility and support policy, security, roadmap, and project documentation."
nav_order: 7
has_children: true
---

# Reference Documentation

Technical reference materials for LLM4S development and maintenance.

## Contributing

Start here if you want to contribute to the project.

- **[Contributing Guide](contributing)** - How to submit your first PR (read this first)
- **[Code Review Guidelines](review-guidelines)** - Coding standards and patterns expected in PRs
- **[Contributor Testing Guide](testing-guide)** - How to write and run tests in this repository
- **[Test Coverage](test-coverage)** - Coverage tooling and thresholds
- **[Configuration Boundary](configuration-boundary)** - How configuration is isolated from core code
- **[Scalafix Rules](scalafix)** - Linting rules, and the migration rules that rewrite user code
- **[Protecting main (merge queue)](merge-queue)** - Why two green PRs can break `main` together, and how maintainers enable the merge queue that prevents it
- **[RFCs](../rfcs/)** - How to propose a change to a Frozen module's public API, and the log of decisions
- **[Code of Conduct](https://github.com/llm4s/llm4s/blob/main/CODE_OF_CONDUCT.md)** - Community guidelines

## Releases, stability and upgrades

- **[1.0 Scope](v1-scope)** - Which packages are Frozen, Beta or Experimental, and what is outside 1.0
- **[Compatibility and Deprecation Policy](compatibility-policy)** - What you can rely on when you upgrade, before and after 1.0
- **[API Stability](api-stability)** - How binary compatibility is checked with MiMa, which modules are covered, and how to add a filter
- **[Support Policy](support-policy)** - Which releases get fixes, the supported platforms and end of support
- **[Migration Guide](migration)** - The note for every breaking change since 0.4.1; start from [Migrating from 0.x to 1.0](../migrations/0x-to-1x)
- **[Release Process](release)** - How releases are cut, and the checklist for each
- **[Verifying Releases](verifying-releases)** - Checking a jar's PGP signature and the build provenance of the jars, SBOMs and container image
- **[Compatibility Matrix](compatibility-matrix)** - What each provider supports with LLM4S, and the versions of the stack around it LLM4S is tested with, generated from the code

## Using LLM4S

- **[Troubleshooting / FAQ](troubleshooting)** - Common problems beyond the first run
- **[Doctor](doctor)** - One command that checks your JDK, configuration, API key, provider module and local model server, and says what to fix
- **[Glossary](glossary)** - Definitions of the terms LLM4S uses, each with a link to its guide or type
- **[Model Metadata](model-metadata)** - The model registry: context windows, prices and capabilities, and how they are looked up
- **[Dependency Conflicts](dependency-conflicts)** - Gradle and sbt dependency conflicts and how to resolve them
- **[Postgres Memory Store](postgres-memory-store)** - PostgreSQL-backed agent memory persistence
- **[Workspace Sandbox](workspace-sandbox)** - Configuring the containerised workspace's limits

## Security, licences and evaluation

- **[Security Reference](security)** - Threat model, trust boundaries, known risks and mitigations
- **[SBOM](sbom)** - The CycloneDX SBOM of each published module, and how dependencies are scanned
- **[Licences](licences)** - The licences of LLM4S's dependencies and the policy for new ones
- **[Legal and IP FAQ](legal-faq)** - Licence, copyright, DCO, patents and third-party terms
- **[Enterprise Evaluation](enterprise-evaluation)** - Answers to an open-source intake questionnaire (licence, governance, support, security, supply chain, data protection), each linked to its evidence, with the known gaps
- **[OpenSSF Best Practices](best-practices)** - Each passing criterion of the OpenSSF Best Practices badge, marked Met, Unmet or N/A with its evidence, and the follow-ups

## Performance

- **[Running the JMH Benchmarks](benchmarks)** - How to run the hot-path JMH benchmarks and how regression detection works
- **[Comparative Benchmarks](comparative-benchmarks)** - llm4s, LangChain4j and Spring AI against one local mock server
- **[RAG Benchmark Results](rag-benchmark-results)** - The RAG benchmarking harness and the results it produced

## Roadmap & Planning

- **[Project Roadmap](roadmap)** - Development roadmap, production readiness, and future plans (single source of truth)
- **[Agent Platform Parity](agent-platform-parity)** - The agent runtime against LangGraph Platform and Deep Agents, capability by capability, and where each gap went
- **[Design Documents](https://github.com/llm4s/llm4s/tree/main/docs/design)** - Detailed architecture docs

## External Resources

- **GitHub Repository**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Issue Tracker**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Pull Requests**: [GitHub PRs](https://github.com/llm4s/llm4s/pulls)

## Design Documents

Design documents for the agent framework phases. They describe the agent API before the typed graph runtime
(#1328), which `Agent` now runs on; the [Typed Agent Runtime Design](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime-design.md)
describes the current one.

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

- [Reliable LLM Calling](../guide/reliability) - Retry, circuit breaker, and deadline enforcement
- [Tool Calling API Design](https://github.com/llm4s/llm4s/blob/main/docs/design/tool-calling-api-design.md)
- [Workspace Agent Protocol](https://github.com/llm4s/llm4s/blob/main/docs/design/workspace-agent-protocol.md)

## Community Resources

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8) - Project template

## License

LLM4S is released under the [MIT License](https://github.com/llm4s/llm4s/blob/main/LICENSE).

---

**Questions?** [Ask in Discord](https://discord.gg/4uvTPn6qww) or [open an issue](https://github.com/llm4s/llm4s/issues).
