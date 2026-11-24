---
layout: page
title: Contribute
description: "How to contribute to LLM4S: find an issue by label, set up the build, follow the coding rules and get a pull request reviewed and merged."
nav_order: 9
has_children: true
---

# Contribute to LLM4S

Welcome! We're glad you're interested in contributing.

**GitHub Issues is our single source of truth.** Browse existing issues below to find something to work on. Please check if an issue is already assigned before starting.

For project priorities and roadmap context, see the [roadmap](../reference/roadmap).

---

## Quick Links by Label

Find issues that match your interests:

- 🟢 **[Good First Issues](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22)** — Great for newcomers

- 📚 **[Documentation](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Adocumentation)** — Docs improvements

- 🐛 **[Bugs](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Abug)** — Bug fixes needed

- ✨ **[Enhancements](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement)** — New features & improvements

-  **[Help Wanted](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3A%22help+wanted%22)** — Community help appreciated

---

## Browse by Area

### Infrastructure & Tooling

- **[Enhancement Issues](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement)** — Build, CI, benchmarks, binary compatibility

### Features

- **[All Feature Requests](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+label%3Aenhancement)**
- **[MCP Integration](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+MCP)** — Model Context Protocol
- **[Embeddings](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+embedding)** — Embedding providers & features
- **[Vector Databases](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+vector+OR+milvus+OR+pinecone)** — Vector store integrations
- **[Observability](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen+observability+OR+tracing)** — Tracing & monitoring

---

## Getting Started

1. **Find an issue** — Use the links above to find something interesting
2. **Comment on the issue** — Let maintainers know you'd like to work on it
3. **Fork and branch** — Create a feature branch from `main`
4. **Follow the guidelines** — See the [Contributing Guide](contributing) for coding standards
5. **Submit a PR** — Reference the issue number in your PR description

Looking for a first task? Convert one documentation page to [checked code blocks](doc-snippets-plan): each is a
small pull request that fixes real documentation bugs.

---

## Contributor Guides

- **[Contributing Guide](contributing)** - How to submit your first PR (read this first)
- **[Code Review Guidelines](review-guidelines)** - Coding standards and patterns expected in PRs
- **[Contributor Testing Guide](testing-guide)** - How to write and run tests in this repository
- **[Test Coverage](test-coverage)** - Coverage tooling and thresholds
- **[Protecting main (merge queue)](merge-queue)** - Why two green PRs can break `main` together, and how maintainers enable the merge queue that prevents it
- **[Release Process](release)** - How releases are cut, and the checklist for each
- **[Verifying Releases](verifying-releases)** - Checking a jar's PGP signature and the build provenance of the jars, SBOMs and container image
- **[Checked Code Blocks](doc-snippets-plan)** - Which documentation pages have compiled code blocks, and how to convert one

The source of these pages is still under `docs/reference/` in the repository (scripts and workflows name those
paths); the site serves them under `/contribute/`, and the old `/reference/` addresses redirect.

---

## All Open Issues

Can't find what you're looking for?

**[Browse all open issues →](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+is%3Aopen)**
