---
layout: page
title: Frameworks and Runtimes
description: "LLM4S with the Scala runtime you already use: plain Result on virtual threads, cats-effect and fs2, ZIO, Apache Pekko Streams, Ox and Jupyter notebooks."
parent: User Guide
nav_order: 500
has_children: true
---

# Frameworks and runtimes

Each page here puts LLM4S into a runtime your application already has.
{: .fs-6 .fw-300 }

- **[Pick your stack](effect-systems)** - One program in plain `Result`, cats-effect, ZIO and Pekko side by side: the semantics every bridge shares, and the differences
- **[cats-effect](cats-effect)** - `LLMClientIO` and `AgentIO` for cats-effect `IO` and fs2 streaming
- **[ZIO](zio)** - `LLMClientZ` and `AgentZ` for ZIO 2 and ZIO Streams
- **[Apache Pekko](pekko)** - `LLMClientPekko` and `AgentPekko` for Pekko Streams
- **[Direct Style and Ox](direct-style)** - Plain `Result` code on virtual threads, and `llm4s-ox` for streamed completions as an Ox `Flow`, fan-out, races and retries
- **[Jupyter Notebooks](notebooks)** - Run LLM4S in Jupyter with the Almond Scala kernel

Elsewhere: Spring Boot, Quarkus, Micronaut and Helidon are under [Java and Kotlin](java-and-kotlin); Spark, Flink and
Kafka are under [RAG and Data](rag); http4s, ZIO HTTP, Tapir and Play servers are in
[Serving an Agent over HTTP](http-reference-apps).
