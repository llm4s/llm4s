# LLM4S Documentation

This directory contains the complete documentation for LLM4S, published at [llm4s.org](https://llm4s.org).

## Documentation Structure

### 📘 [Getting Started](https://llm4s.org/getting-started/) ✅ Complete

Beginner-friendly guides:

- **[Installation](getting-started/installation.md)** - Set up LLM4S
- **[First Example](getting-started/first-example.md)** - Your first program
- **[Configuration](getting-started/configuration.md)** - Provider setup
- **[Next Steps](getting-started/next-steps.md)** - Learning paths

### 📖 [User Guide](https://llm4s.org/guide/)

Available guides:
- **[Image Generation](guide/image-generation.md)** - DALL-E and image providers
- **[Speech](guide/speech.md)** - STT and TTS

Other features are documented via examples and design documents.

### 💻 [Examples](https://llm4s.org/examples/) ✅ Complete

69 working code examples covering all features:

| Category | Count |
|----------|-------|
| Basic LLM Calling | 9 |
| Agent Framework | 8 |
| Tool Calling | 7 |
| Guardrails | 7 |
| Handoffs | 3 |
| Memory System | 5 |
| Streaming Events | 4 |
| Context Management | 8 |
| Embeddings | 5 |
| MCP Integration | 3 |
| Other | 10+ |

### 🚀 [Advanced Topics](https://llm4s.org/advanced/)

Links to design documents for production features. See [design/](design/) for detailed technical docs.

### 📚 [API Reference](https://llm4s.org/api/)

- **[Scaladoc](/scaladoc/)** - Auto-generated API documentation
- API design principles and patterns

### 📋 [Reference](https://llm4s.org/reference/) ✅ Complete

- **[Migration Guide](reference/migration.md)** - Upgrade between versions
- **[Scalafix Rules](reference/scalafix.md)** - Code quality
- **[Glossary](reference/glossary.md)** - Definitions of the terms LLM4S uses
- **[Test Coverage](reference/test-coverage.md)** - Testing guidelines
- **[Release Process](reference/release.md)** - How releases work
- **[Roadmap](reference/roadmap.md)** - Single source of truth for project status

### 🌐 [Community](https://llm4s.org/community/)

- Discord, GitHub, starter kit links
- Talks and presentations

### 📐 [Design Documents](design/)

Detailed technical specifications:

| Phase | Topic |
|-------|-------|
| 1.1 | Functional Conversation Management |
| 1.2 | Guardrails Framework |
| 1.3 | Handoff Mechanism |
| 1.4 | Memory System |
| 2.1 | Streaming Events |
| 2.2 | Async Tools |
| 3.2 | Built-in Tools |
| 4.1 | Reasoning Modes |
| 4.3 | Session Serialization |

---

## Building the Documentation Site

The documentation uses **Jekyll** with **just-the-docs** theme on **GitHub Pages**.

### Local Development

```bash
cd docs
bundle install
bundle exec jekyll serve

# View at http://localhost:4000
```

### Configuration

- `_config.yml` - Jekyll configuration
- `_data/project.yml` - Version and project data
- `index.md` - Homepage
- `CNAME` - Custom domain (llm4s.org)

### Share Images and Structured Data

Every page has a 1200x630 share card, the picture a link shows in Discord, Slack, LinkedIn, X or Mastodon. The
landing pages and the top-level docs sections each have their own; every other page uses `default.png`.

- `image_assets/social/template.svg` is the card's design and `image_assets/social/cards.tsv` holds each card's
  heading and two lines of text. After changing either (for a release such as 1.0), regenerate the PNGs and commit
  them:

  ```bash
  scripts/render-social-images.sh            # every card; or name some: scripts/render-social-images.sh guide
  ```

  It needs `rsvg-convert` (librsvg) or Chrome/Chromium (set `CHROME` to its path if it is not found).
- `_config.yml` points the pages at the cards (`image:` in `defaults`, by path) and holds each card's alt text, which
  must say what the card says.
- `_includes/structured-data.html` writes the Schema.org JSON-LD: `SoftwareSourceCode` on the home page (its version
  is `latest_release`), `Organization` on `/foundation/` and a `BreadcrumbList` on each page with a `parent`.
  `_plugins/structured_data.rb` removes jekyll-seo-tag's generic block where it would describe the same page twice.
- `scripts/check-site-share-cards.sh docs/_site` checks the built site; the deploy (`pages.yml`) runs it.

---

## Contributing to Documentation

1. **Small fixes**: Edit directly and submit a PR
2. **New guides**: Discuss in Discord first
3. **Examples**: Add to `modules/samples/` with documentation
4. **API docs**: Update alongside code changes

### Standards

- Clear, concise writing
- Working code examples
- Cross-reference related topics
- Include front matter with nav_order

---

## Quick Links

- **Live Site**: [llm4s.org](https://llm4s.org)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Discord**: [Join us](https://discord.gg/4uvTPn6qww)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)

---

**Questions?** [Join Discord](https://discord.gg/4uvTPn6qww) or [open an issue](https://github.com/llm4s/llm4s/issues)
