# LLM4S Documentation

This directory contains the complete documentation for LLM4S, published at [llm4s.org](https://llm4s.org).

## Documentation Structure

The documentation is organized into the following sections:

### 📘 [Getting Started](https://llm4s.org/getting-started/)

Beginner-friendly guides to get you up and running:

- **[Installation](getting-started/installation.md)** - Set up LLM4S in your project
- **[First Example](getting-started/first-example.md)** - Your first LLM-powered program
- **[Configuration](getting-started/configuration.md)** - Configure providers and API keys
- **[Next Steps](getting-started/next-steps.md)** - Choose your learning path

### 📖 [User Guide](https://llm4s.org/guide/)

Comprehensive guides for all features:

**Core Concepts:**
- `guide/basic-usage.md` - Fundamental concepts and patterns
- `guide/multi-turn.md` - Multi-turn conversation management
- `guide/context-management.md` - Token windows and pruning

**Building with Agents:**
- `guide/agents.md` - Agent framework guide
- `guide/tool-calling.md` - Tool integration

**Advanced Features:**
- `guide/streaming.md` - Real-time streaming
- `guide/embeddings.md` - Vector search and RAG
- `guide/mcp.md` - Model Context Protocol
- `guide/observability.md` - Tracing and monitoring

**Multimodal:**
- `guide/image-generation.md` - Image generation
- `guide/speech.md` - Speech-to-text and text-to-speech

### 💻 [Examples](https://llm4s.org/examples/)

46 working code examples organized by category:

- `examples/index.md` - Complete example gallery
- Examples grouped by: Basic, Agents, Tools, Context, Embeddings, MCP, Streaming

### 🚀 [Advanced Topics](https://llm4s.org/advanced/)

Production-ready features and optimization:

- `advanced/production.md` - Production readiness guide
- `advanced/error-handling.md` - Robust error handling
- `advanced/performance.md` - Optimization techniques
- `advanced/multi-agent.md` - Multi-agent orchestration
- `advanced/workspace.md` - Containerized execution
- `advanced/security.md` - Safety and guardrails

### 📚 [API Reference](https://llm4s.org/api/)

Complete API documentation:

- `api/llm-client.md` - LLMClient API
- `api/core-types.md` - Result, ModelName, etc.
- `api/agent.md` - Agent framework API
- `api/tools.md` - Tool API
- `api/config.md` - Configuration API
- `api/tracing.md` - Tracing API

### 📋 [Reference](https://llm4s.org/reference/)

Technical reference materials:

- `reference/migration.md` - Migration guide
- `reference/scalafix.md` - Scalafix rules
- `reference/test-coverage.md` - Testing guidelines
- `reference/release.md` - Release process
- `reference/roadmap.md` - Development roadmap

### 🌐 [Community](https://llm4s.org/community/)

Community resources and links:

- Discord, GitHub, starter kit
- Talks and presentations
- How to contribute

---

## Legacy Documentation

The following files remain in the root for backward compatibility but are now organized under the new structure:

- `AGENTS.md` → `guide/agents.md`
- `ImageGeneration.md` → `guide/image-generation.md`
- `README_SPEECH.md` → `guide/speech.md`
- `MIGRATION_GUIDE.md` → `reference/migration.md`
- `SCALAFIX.md` → `reference/scalafix.md`
- `TEST_COVERAGE.md` → `reference/test-coverage.md`
- `RELEASE.md` → `reference/release.md`

Technical design documents remain in:
- `design/` - Detailed design documents
- `roadmap/` - Project roadmap

---

## Building the Documentation Site

The documentation is built using **Jekyll** and hosted on **GitHub Pages**.

### Local Development

```bash
# Install Jekyll
gem install jekyll bundler

# Serve locally
cd docs
bundle exec jekyll serve

# View at http://localhost:4000
```

### Configuration

- `_config.yml` - Jekyll configuration
- `index.md` - Homepage
- `CNAME` - Custom domain (llm4s.org)

### Theme

The site uses the **Minima** theme with custom collections for organized content.

---

## Contributing to Documentation

We welcome documentation improvements! To contribute:

1. **Small fixes**: Edit directly and submit a PR
2. **New guides**: Discuss in Discord or open an issue first
3. **Examples**: Add to `examples/` with clear descriptions
4. **API docs**: Update alongside code changes

### Documentation Standards

- **Clear and concise**: Write for developers
- **Code examples**: Always include working code
- **Links**: Cross-reference related topics
- **Format**: Use Markdown with front matter
- **Navigation**: Update nav_order if adding pages

---

## Documentation Principles

1. **User-First**: Written for library users, not contributors
2. **Example-Driven**: Every feature shown with code
3. **Progressive Disclosure**: Start simple, link to advanced
4. **Consistent**: Same structure across all guides
5. **Discoverable**: Clear navigation and cross-linking

---

## Quick Links

- **Live Site**: [llm4s.org](https://llm4s.org)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Discord**: [Join us](https://discord.gg/4uvTPn6qww)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)

---

## Documentation TODO

Future improvements:

- [ ] Complete all User Guide pages (currently have index pages)
- [ ] Add search functionality
- [ ] Create video tutorials
- [ ] Add FAQ section
- [ ] Improve code highlighting
- [ ] Add interactive examples
- [ ] Create PDF version

---

**Questions?** [Join Discord](https://discord.gg/4uvTPn6qww) or [open an issue](https://github.com/llm4s/llm4s/issues)
