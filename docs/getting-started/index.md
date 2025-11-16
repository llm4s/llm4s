---
layout: page
title: Getting Started
nav_order: 2
has_children: true
---

# Getting Started

Get up and running with LLM4S in minutes.

This section will guide you through:

1. **[Installation](installation)** - Set up LLM4S in your project
2. **[First Example](first-example)** - Write your first LLM-powered program
3. **[Configuration](configuration)** - Configure providers and API keys
4. **[Next Steps](next-steps)** - Choose your learning path

## Quick Start

The fastest way to get started:

```bash
# 1. Use the starter template
sbt new llm4s/llm4s.g8

# 2. Configure your API key
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...

# 3. Run your first program
sbt run
```

## What You'll Learn

By the end of this section, you'll:

- ✅ Have LLM4S installed and configured
- ✅ Understand Result-based error handling
- ✅ Know how to make basic LLM calls
- ✅ Be able to configure multiple providers
- ✅ Know where to go next based on your goals

## Prerequisites

Before starting, you should have:

- **Java 11+** (JDK 21 recommended)
- **Scala 2.13.16 or 3.7.1**
- **SBT 1.10.6+**
- An API key from OpenAI, Anthropic, Azure OpenAI, or Ollama installed

## Time to Complete

- **Quick Start**: ~5 minutes (with starter kit)
- **Full Tutorial**: ~30 minutes

## Need Help?

If you get stuck:

- Check the [troubleshooting guides](configuration#troubleshooting)
- Ask in [Discord](https://discord.gg/4uvTPn6qww)
- Browse [examples](/examples/)

---

**Ready?** [Start with installation →](installation)
