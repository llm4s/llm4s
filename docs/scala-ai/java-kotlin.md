---
layout: page
title: An LLM library for Java and Kotlin
parent: Scala AI
nav_order: 4
description: "Use LLM4S from Java and Kotlin: the llm4s-java-api facade, the Kotlin coroutine API, Maven and Gradle coordinates, Spring Boot and Quarkus."
search_title: "An LLM library for Java and Kotlin on the JVM"
---

# An LLM library for Java and Kotlin
{: .no_toc }

LLM4S is written in Scala, and Java and Kotlin code can use it through two entry points: `llm4s-java-api`, a Java
facade whose calls return a checked `LlmResult` instead of throwing, and `llm4s-kotlin-api`, suspend functions and
`Flow`s on top of it. Both call the same providers, tools and agents as the Scala API.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

## Java

Add the facade and the provider module you call. The `_3` suffix is the Scala version the artifacts are built for;
Maven and Gradle need it written out.

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-java-api_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-openai_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

A first call, with the provider configured in `application.conf` as on the
[configuration page](../getting-started/configuration):

```java
import java.io.PrintStream;

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

public final class HelloLlm4s {

    private HelloLlm4s() {}

    public static void main(String[] args) {
        System.exit(run(System.out, System.err));
    }

    static int run(PrintStream out, PrintStream err) {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("What is a monad? Answer in one sentence.");
            answer
                .ifSuccess(text -> out.println(text))
                .ifFailure(error -> err.println("The call failed: " + error.getMessage()));
            return answer.isSuccess() ? 0 : 1;
        }
    }
}
```

The facade also streams replies, returns structured output as a Java record, defines tools with `@Tool` methods or a
lambda builder, runs agents (`JAgent`) and has `completeAsync` and `runAsync` returning a `CompletableFuture`. Some
Scala types still appear in its signatures; the [Java guide](../guide/java) says which. It needs JDK 21.

## Kotlin

`llm4s-kotlin-api` has no `_3` suffix: it is a Kotlin artifact, built with Gradle. It brings `llm4s-java-api_3` and
`kotlinx-coroutines-core`.

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-kotlin-api:{{ site.data.project.latest_release }}")
    implementation("org.llm4s:llm4s-ollama_3:{{ site.data.project.latest_release }}")   // the provider you call
}
```

{: .warning }
> `llm4s-kotlin-api` {{ site.data.project.latest_release }} is not on Maven Central: a release workflow fault left it
> out of the 0.5.0 and 0.5.1 releases. Until a release includes it, build it locally as the
> [installation guide](../getting-started/installation#for-kotlin-coroutines-and-flow) describes.

Each provider has a builder block, and every call is a suspend function:

```kotlin
import org.llm4s.kotlin.Llm4s

suspend fun ask(ollamaUrl: String): String =
    Llm4s.ollama {
        model = "llama3.2"
        baseUrl = ollamaUrl
    }.use { client -> client.complete("What is 2 + 2?") }
```

A failure is thrown as `LLMException`. The Kotlin DSL for tools, completion options and agents is in the
[Java guide's Kotlin section](../guide/java#the-kotlin-dsl). The Kotlin API is Experimental.

## Spring Boot, Quarkus and the rest

- [Spring Boot](../guide/spring-boot): `llm4s-spring-boot-starter` auto-configures a client from `application.properties` and injects an `LLM4STemplate`.
- [Quarkus](../guide/quarkus): a CDI producer for `JLlmClient` and a runnable sample.
- [RAG from Java](../guide/java-rag) (`llm4s-java-rag`) and [Reactor](../guide/java-reactor) (`Flux` of a streamed reply).
- [Moving a Java app from LangChain4j or Spring AI](../guide/java-migration).
- Build files: [Maven](../getting-started/maven) and [Gradle](../getting-started/gradle).

Every code block on this page is compiled and run in CI: the Java is `HelloLlm4s.java`, run by `JavaGuideSpec`, and the
Kotlin is in `WrapClientTest.kt`, run against a local stand-in for Ollama.
