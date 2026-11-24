---
layout: page
title: Getting Started
description: "Get started with LLM4S: install it, run a first program, configure a provider, and pick a quick start for sbt, Scala CLI, Maven, Gradle, Mill or JBang."
search_title: "Get started with LLM4S: Scala LLM framework for the JVM"
nav_order: 2
has_children: true
---

# Getting Started

Get up and running with LLM4S in minutes.

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/llm4s/llm4s?quickstart=1)
Nothing to install: the [playground](playground) runs the samples in your browser against a small local model.

No API key and nothing to install? **[First answer, no key, no server](local-jlama)** runs a small model inside your
JVM with `llm4s-jlama`: one file, one command.

This section will guide you through the pages below. The sidebar keeps the Scala CLI, Maven, Gradle, Mill and JBang
quick starts, the REPL and the playground together under [Build Tools and Playgrounds](build-tools).

1. **[Installation](installation)** - Set up LLM4S in your project
2. **[First Example](first-example)** - Write your first LLM-powered program
3. **[Configuration](configuration)** - Configure providers and API keys
4. **[Next Steps](next-steps)** - Choose your learning path
5. **[Ollama Quick Start](ollama-quickstart)** - Local LLM development (no API keys needed)
6. **[Gradle Integration](gradle)** - Use LLM4S from a Gradle (Java or Kotlin) build
7. **[Testing Guide](testing-guide)** - Test code that calls a model, with no network and no API key
8. **[Scala CLI Quick Start](scala-cli)** - A first LLM call from a single file, with no sbt project
9. **[Maven Quick Start](maven)** - A complete `pom.xml` that runs a first call from Java with `llm4s-java-api`
10. **[JBang Quick Start](jbang)** - A first LLM call from one Java file, with no build tool
11. **[First-Run Troubleshooting](troubleshooting)** - Symptoms, causes and fixes for the problems a first run most often hits
12. **[Using LLM4S from Scala 2.13](scala-2-13)** - Call LLM4S from a Scala 2.13 project through the TASTy reader (best effort)
13. **[Mill Quick Start](mill)** - A `build.mill` project with a first call and a test that needs no API key
14. **[AI Coding Assistants](../ai-assistants)** - `llms.txt` and a rules file that keep an assistant's llm4s code compiling
15. **[Playground (Codespaces)](playground)** - Try LLM4S in the browser: a GitHub Codespace with a local model, no install and no API key
16. **[Try llm4s in the REPL](repl)** - `ask`, `extract` and `chat()` behind one import, in the Scala CLI REPL, a worksheet or a script
17. **[First Answer, No Key, No Server](local-jlama)** - A small model run inside your JVM by Jlama: no API key, no model server
18. **[Configuration in Code](configuration-in-code)** - Configure LLM4S from a Typesafe `Config` you already have, ciris, zio-config or plain values
19. **From Java, Spring Boot or Kotlin** - the [Java guide](/guide/java), the [Spring Boot starter](/guide/spring-boot)
    and [Kotlin coroutines](installation#for-kotlin-coroutines-and-flow) (`llm4s-kotlin-api`, on Maven Central from 0.6.0)
20. **[Coming from Python?](../guide/for-python-developers)** - OpenAI SDK, LangChain, LlamaIndex and Pydantic code next to the same thing in Scala 3, one Scala concept at a time

The current release is **{{ site.data.project.latest_release }}**, for Scala 3.7.x on JDK 21.

## Quick Start

The fastest way to get started:

```bash
# 1. Use the starter template, on the current release, and move into the project it creates
sbt new llm4s/llm4s.g8 --llm4s_version={{ site.data.project.latest_release }}
cd <your project name>

# 2. Choose the template's OpenAI section and set its key
#    (or skip this step: the default is a local Ollama section, which needs Ollama running and `ollama pull llama3.2`)
export LLM4S_PROVIDER=openai-main
export OPENAI_API_KEY=sk-...

# 3. Run your first program, with a default prompt or your own
sbt run
sbt 'run "Explain what a monad is in Scala"'
```

`llm4s-core` ships no provider client, so the generated `build.sbt` adds `llm4s-ollama` and `llm4s-openai` next to it,
at the same version; add the module of any other provider you use
([Installation](installation#quick-start-with-the-starter-kit) shows how). Its `application.conf` holds
[named provider sections](configuration#named-provider-sections), with the local Ollama one as the default, and the
`LLM4S_PROVIDER` binding picks another. The project also has a logging backend, a `Main` with a small prompt
executor, an MUnit test, scalafmt and a GitHub Actions workflow; the
[template repository](https://github.com/llm4s/llm4s.g8) documents every option.

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

When you have made a first call, [Build a support assistant](../tutorials/support-assistant/) takes one application
from a first call to typed answers, tools and an agent, a part at a time, with exercises a test suite checks.

## Need Help?

If you get stuck:

- Check the [troubleshooting guides](configuration#troubleshooting)
- Ask in [Discord](https://discord.gg/4uvTPn6qww)
- Browse [examples](/examples/)

---

**Ready?** [Start with installation →](installation)
