---
layout: page
title: Build Tools and Playgrounds
description: "A first LLM4S call from the build tool you use (Scala CLI, Maven, Gradle, Mill or JBang), in the Scala REPL, or in a browser playground with nothing installed."
parent: Getting Started
nav_order: 7
has_children: true
---

# Build tools and playgrounds

[Installation](installation) and [First Example](first-example) use sbt. Each page here makes a first call another
way.
{: .fs-6 .fw-300 }

## Build tools

- **[Scala CLI Quick Start](scala-cli)** - A first LLM call from a single file, with no sbt project
- **[Maven Quick Start](maven)** - A complete `pom.xml` that runs a first call from Java with `llm4s-java-api`
- **[Gradle Integration](gradle)** - Use LLM4S from a Gradle (Java or Kotlin) build
- **[Mill Quick Start](mill)** - A `build.mill` project with a first call and a test that needs no API key
- **[JBang Quick Start](jbang)** - A first LLM call from one Java file, with no build tool

## Without a project

- **[Try llm4s in the REPL](repl)** - `ask`, `extract` and `chat()` behind one import, in the Scala CLI REPL, a worksheet or a script
- **[Playground (Codespaces)](playground)** - Try LLM4S in the browser: a GitHub Codespace with a local model, no install and no API key
