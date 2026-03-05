---
layout: page
title: Examples
nav_order: 4
---

# Example
{: .no_toc }

All examples have been moved to a dedicated repository.
{: .fs-6 .fw-300 }

---

## 📚 Examples Repository

**[→ Visit llm4s-examples on GitHub](https://github.com/llm4s/llm4s-examples)**

All LLM4S examples have been reorganized into a dedicated repository for better discoverability and easier maintenance.

### Why a separate repository?

- **Better organization**: Clear learning path from beginner to advanced
- **Easier discovery**: Examples in their own dedicated repo
- **Independent updates**: Examples update separately from framework
- **Cleaner main repo**: Framework code stays focused
- **Community contributions**: Clear structure for new examples

### What's included?

**Getting Started** (30 minutes)
- Hello World - Your first LLM4S program
- First Completion - Multi-turn conversations
- Configuration - All providers and settings

**Advanced Topics** (2-4 hours)
- Agents - Intelligent decision-making
- Tools - Custom tool creation
- Streaming - Real-time token responses
- Error Handling - Production patterns

**Real-World Integrations**
- RAG - Retrieval-Augmented Generation
- Web APIs - REST API integration
- MCP - Model Context Protocol

### Quick Start

```bash
# Clone the examples repository
git clone https://github.com/llm4s/llm4s-examples
cd llm4s-examples

# Configure your LLM provider
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...

# Run the first example
sbt "helloWorld/runMain org.llm4s.examples.HelloWorld"
```

### Local Development

If you want to modify examples while developing LLM4S:

```bash
# Clone both repositories
git clone https://github.com/llm4s/llm4s
git clone https://github.com/llm4s/llm4s-examples

# Edit llm4s-examples/build.sbt to use local llm4s version:
# libraryDependencies += "org.llm4s" %% "llm4s-core" % "path-to-local-llm4s"

# Or publishLocal from your llm4s clone
cd llm4s
sbt publishLocal

cd ../llm4s-examples
sbt compile
```

---

## Contributing Examples

Want to add a new example? Head over to the [llm4s-examples](https://github.com/llm4s/llm4s-examples) repository and check out the [CONTRIBUTING.md](https://github.com/llm4s/llm4s-examples/blob/main/CONTRIBUTING.md) guide.

Examples should:
- Teach a specific concept
- Be self-contained and runnable
- Include clear documentation
- Follow LLM4S best practices

---

## Archive

The original examples that were built into the main repository have been moved. If you need to reference them, they are available in the [git history](https://github.com/llm4s/llm4s/tree/main/modules/samples) of the main repository.
