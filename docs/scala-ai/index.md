---
layout: page
title: Scala AI
nav_order: 1.7
has_children: true
permalink: /scala-ai/
description: "Short answers to what Scala and JVM developers ask about LLM4S: a Scala LLM framework, AI agents, RAG, Java and Kotlin, and multi-provider calls."
search_title: "AI and LLMs in Scala and on the JVM"
---

# AI and LLMs in Scala and on the JVM
{: .no_toc }

Five short pages, each answering one question developers ask before they have heard of LLM4S, with a small piece of
compiled code and links into the guides that go deeper.
{: .fs-6 .fw-300 }

- **[An LLM framework for Scala (and the LangChain question)](llm-framework)**: what LLM4S is, how it relates to
  LangChain and LangChain4j, and a first call.
- **[Build AI agents in Scala](agents)**: an agent with a tool, guardrails and streaming.
- **[RAG in Scala](rag)**: ingest, embed, store, retrieve and answer, then evaluate.
- **[An LLM library for Java and Kotlin](java-kotlin)**: the Java facade and the Kotlin coroutine API, with Maven and
  Gradle coordinates.
- **[Call OpenAI, Anthropic, Gemini or Ollama from Scala](providers)**: one client type, the provider chosen by
  configuration, and the full provider list.

Every Scala block on these pages is compiled in CI, and the ones that call a model are also run there against a
scripted model, so they never reach the network (`ScalaAiPagesSpec` in `modules/samples`).
