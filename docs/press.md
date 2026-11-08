---
layout: page
title: Press kit
description: "The LLM4S press kit: logo, a 50-word and a 150-word description, a fact sheet with licence, versions, providers and governance, and who to contact."
search_title: "LLM4S press kit: logo, descriptions and fact sheet"
permalink: /press/
nav_exclude: true
---

# Press kit
{: .no_toc }

Material for newsletter editors, journalists, conference organisers and bloggers who write about LLM4S. You may quote
the descriptions below as they are, or shorten them. Every fact on this page can be checked in the
[repository](https://github.com/llm4s/llm4s); it was last checked on 2026-11-08, for release
`{{ site.data.project.latest_release }}`.

1. TOC
{:toc}

---

## Logo

| Asset | File | Use |
|-------|------|-----|
| Logo, mark and name, on a light background | [llm4s_logo.png]({{ '/image_assets/llm4s_logo.png' | relative_url }}) (576×576 PNG) | Articles, slides, listings |
| Share card | [default.png]({{ '/image_assets/social/default.png' | relative_url }}) (1200×630 PNG) | Link previews, banners |

The logo is the red mark (three slanted bands beside a brain outline with connected nodes) above the black name
**LLM4S**. Write the name as `LLM4S` in running text, and `llm4s` only in code, package and artifact names. Do not
recolour, stretch or redraw the logo.

Vector (SVG) versions of the logo for light and dark backgrounds, and a separate wordmark, are not published yet; until
they are, use the PNG above and ask [the contact below](#contact) for a larger size.

## Descriptions

### 50 words

> LLM4S (Large Language Models for Scala) is an open-source framework for building LLM applications on the JVM. One
> typed client reaches OpenAI, Anthropic, Gemini, Ollama, AWS Bedrock and other providers; its agent runtime has tools,
> guardrails and handoffs. Errors are values, not exceptions. MIT-licensed Scala 3, with Java and Kotlin APIs.

### 150 words

> LLM4S (Large Language Models for Scala) is an open-source framework for building applications on large language
> models, on the JVM. It gives Scala, Java and Kotlin developers one typed client for many providers: OpenAI, Azure
> OpenAI, Anthropic, Google Gemini and Vertex AI, AWS Bedrock, Ollama for local models, and any OpenAI-compatible
> endpoint. Each provider is a separate module, found through a documented provider interface.
>
> On top of the client, LLM4S provides an agent runtime with tools, guardrails, handoffs, streaming and durable
> threads; retrieval-augmented generation over pgvector, Qdrant, SQLite, Elasticsearch, OpenSearch and Redis; a Model
> Context Protocol client and server; and tracing through Langfuse and OpenTelemetry. Failures come back as typed
> values instead of exceptions.
>
> LLM4S is MIT-licensed and developed in public on GitHub since April 2025. It is community-led, maintained by a small
> core team and stewarded by the LLM4S Foundation, which is funded through Open Collective.

## Fact sheet

| | |
|---|---|
| **Name** | LLM4S (Large Language Models for Scala) |
| **What it is** | An open-source library for building LLM applications on the JVM: provider clients, agents, tools, RAG, MCP, tracing |
| **Website** | [llm4s.org](https://llm4s.org) |
| **Source** | [github.com/llm4s/llm4s](https://github.com/llm4s/llm4s) |
| **Licence** | MIT ([LICENSE](https://github.com/llm4s/llm4s/blob/main/LICENSE)) |
| **First release** | 0.1.0, April 2025 |
| **Latest release** | `{{ site.data.project.latest_release }}` ([release notes](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md), [news]({{ '/news/' | relative_url }})) |
| **Maven coordinates** | `org.llm4s` group on Maven Central, for example `org.llm4s:llm4s-core_3` |
| **Language and runtime** | Scala {{ site.data.project.scala_versions | join: ", " }}, JDK {{ site.data.project.java_versions | join: ", " }} or later (bytecode for JDK 21) |
| **Java** | `llm4s-java-api` (a Java facade over the client and agent), a Spring Boot starter and Reactor support |
| **Kotlin** | `llm4s-kotlin-api`: coroutines and a DSL for tools and agents, built with Kotlin 2.0.21 for JVM 21; published from 0.5.2 |
| **Chat providers** | OpenAI, Azure OpenAI, Anthropic, Google Gemini, Vertex AI, AWS Bedrock, Ollama, DeepSeek, Mistral, Cohere, xAI, Perplexity, OpenRouter, Requesty, Z.ai, IBM watsonx.ai (beta), and any OpenAI-compatible endpoint by configuration |
| **Embedding providers** | OpenAI, Voyage AI, Jina AI, Cohere, Ollama |
| **Effect and stream libraries** | cats-effect and fs2, ZIO 2, Apache Pekko Streams |
| **Governance** | Maintainers decide by lazy consensus, majority vote for contested changes; public API changes go through RFCs ([GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md), [MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md)) |
| **Maintainers** | Kannupriya Kalra and Rory Graves ([people]({{ '/people/' | relative_url }})) |
| **Stewardship and funding** | The LLM4S Foundation, funded through [Open Collective](https://opencollective.com/llm4s) ([Foundation]({{ '/foundation/' | relative_url }})) |
| **Community** | [Discord]({{ site.discord_invite }}), [GitHub issues](https://github.com/llm4s/llm4s/issues), Google Summer of Code ([GSoC]({{ '/gsoc/' | relative_url }})) |

## Try it

Readers can try LLM4S without an API key, against a small local model:

- in the browser, in a GitHub Codespace: [Playground]({{ '/getting-started/playground' | relative_url }});
- from one Java file with JBang and Ollama: [JBang quick start]({{ '/getting-started/jbang' | relative_url }});
- from one Scala file with Scala CLI: [Scala CLI quick start]({{ '/getting-started/scala-cli' | relative_url }}), with
  the [Ollama quick start]({{ '/getting-started/ollama-quickstart' | relative_url }}).

## Contact

For interviews, quotes, logo files or corrections, email
[kannupriya@llm4s.org](mailto:kannupriya@llm4s.org?subject=LLM4S%20press) or ask on
[Discord]({{ site.discord_invite }}).
