---
layout: page
title: Java and Kotlin
description: "LLM4S from Java and Kotlin: llm4s-java-api, AI services, RAG, Reactor, threading, migration, and Spring Boot, Quarkus, Micronaut and Helidon."
parent: User Guide
nav_order: 400
has_children: true
---

# Java and Kotlin

LLM4S from a JVM language other than Scala: `llm4s-java-api` and the modules built on it.
{: .fs-6 .fw-300 }

## The Java API

- **[Java](java)** - Call a model from Java with `llm4s-java-api`: a client, a conversation and failures you can read without Scala
- **[Java AI services](java-ai-services)** - Declare an annotated interface and let `llm4s-java-api` implement it (experimental)
- **[Java RAG](java-rag)** - Ingest documents and ask questions from Java with `llm4s-java-rag`: answers with their sources
- **[Java Reactor](java-reactor)** - Stream a reply as a Reactor `Flux<String>` with `llm4s-reactor`
- **[Java Threading and Cancellation](java-threading-and-cancellation)** - Which thread a call blocks, sharing one client, virtual threads, interrupts and timeouts
- **[Moving a Java app from LangChain4j or Spring AI](java-migration)** - Each LangChain4j and Spring AI concept in `llm4s-java-api` terms, with compiled before-and-after Java

## JVM frameworks

- **[Spring Boot](spring-boot)** - `llm4s-spring-boot-starter`: properties, an `LLM4STemplate` bean, asynchronous calls and a health indicator
- **[Spring AI](spring-ai)** - Any llm4s client as a Spring AI `ChatModel` or `EmbeddingModel`, and a Spring AI `ChatModel` as an llm4s client
- **[Quarkus](quarkus)** - The `quarkus-llm4s` extension: `JLlmClient` beans from `application.properties`, checked at build time
- **[Micronaut](micronaut)** - A `@Factory` bean for `JLlmClient` configured from `application.yml`, blocking and streamed endpoints and tests
- **[Helidon](helidon)** - Helidon SE 4: the client built from `application.yaml`, routes on virtual threads, a health check and tests

## Kotlin

- **[Kotlin](kotlin)** - `llm4s-kotlin-api`: suspend functions, `Flow` streaming, agents with tools and the Kotlin DSL (experimental)

Building with Maven, Gradle or JBang? See [Build Tools and Playgrounds](../getting-started/build-tools).
