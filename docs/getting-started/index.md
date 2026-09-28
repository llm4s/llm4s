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
5. **[Ollama Quick Start](ollama-quickstart)** - Local LLM development (no API keys needed)

## Quick Start

The fastest way to get started:

```bash
# 1. Use the starter template
sbt new llm4s/llm4s.g8

# 2. Set the API key - llm4s-openai reads it for the template's openai-main section
export OPENAI_API_KEY=sk-...

# 3. Run your first program
sbt run
```

llm4s reads providers from named sections in `application.conf`, not from variables of its own;
see [Configuration](configuration#named-provider-sections) to add or switch providers.

## What You'll Learn

By the end of this section, you'll:

- ✅ Have LLM4S installed and configured
- ✅ Understand Result-based error handling
- ✅ Know how to make basic LLM calls
- ✅ Be able to configure multiple providers
- ✅ Know where to go next based on your goals

## Prerequisites

Before starting, you should have:

- **JDK 21**
- **Scala 3.7.1**
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
