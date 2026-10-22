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
6. **[Gradle Integration](gradle)** - Use LLM4S from a Gradle (Java or Kotlin) build
7. **[Scala CLI Quick Start](scala-cli)** - A first LLM call from a single file, with no sbt project
8. **[Maven Quick Start](maven)** - A complete `pom.xml` that runs a first call from Java with `llm4s-java-api`
9. **[JBang Quick Start](jbang)** - A first LLM call from one Java file, with no build tool
10. **From Java, Spring Boot or Kotlin** - the [Java guide](/guide/java), the [Spring Boot starter](/guide/spring-boot)
    and [Kotlin coroutines](installation#for-kotlin-coroutines-and-flow) (`llm4s-kotlin-api`
    {{ site.data.project.latest_release }} is not on Maven Central yet; that section says how to build it)
11. **[First-Run Troubleshooting](troubleshooting)** - Symptoms, causes and fixes for the problems a first run most often hits

The current release is **{{ site.data.project.latest_release }}**, for Scala 3.7.x on JDK 21.

## Quick Start

The fastest way to get started:

```bash
# 1. Use the starter template, on the current release
sbt new llm4s/llm4s.g8 --llm4s_version={{ site.data.project.latest_release }}

# 2. Set the API key - llm4s-openai reads it for the template's openai-main section
export OPENAI_API_KEY=sk-...

# 3. Run your first program
sbt run
```

Check that the generated `build.sbt` lists a provider module (such as `llm4s-openai`) next to `llm4s-core`: since
0.5.0 `llm4s-core` ships none. The template update for 0.5.x ([llm4s.g8#6](https://github.com/llm4s/llm4s.g8/pull/6))
adds `llm4s-ollama` and `llm4s-openai` and defaults to a local Ollama section (choose OpenAI with
`LLM4S_PROVIDER=openai-main`); an older template lists `llm4s-core` only, so add the provider yourself
([Installation](installation#quick-start-with-the-starter-kit) shows how).

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
- **Scala 3.7.x** (llm4s is built with 3.7.1)
- **sbt 1.10.6+**
- An API key from OpenAI, Anthropic, Azure OpenAI or another provider, or [Ollama](ollama-quickstart) installed

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
