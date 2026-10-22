---
layout: page
title: Java Reactor
parent: User Guide
nav_order: 35
---

# Streaming to Reactor from Java
{: .no_toc }

Stream a model's reply as a Reactor `Flux<String>` with `llm4s-reactor`, for Spring WebFlux and other reactive
pipelines.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What `llm4s-reactor` is

The [Java facade](java#streaming-the-reply)'s `JLlmClient.streamAsPublisher` returns the reply's text as a JDK
`java.util.concurrent.Flow.Publisher<String>`. `llm4s-reactor` adapts it to Reactor: `Streams.toFlux` takes a
`JLlmClient` and a query, a conversation, or a conversation and `JCompletionOptions`, and returns a `Flux<String>` you
can compose, return from a WebFlux handler, or `block()` on in a test.

It is a module of its own, not part of `llm4s-java-api`, so that a Java application without Reactor does not receive
`reactor-core`. It is **Experimental**: its API may change in any release ([1.0 Scope](../reference/v1-scope)).

{: .note }
> `llm4s-reactor` is new and is published from the release after {{ site.data.project.latest_release }}. Until then,
> build it from `main` ([Unreleased changes](../getting-started/installation#unreleased-changes-main)).

## Add the dependency

The artifact is `org.llm4s:llm4s-reactor_3`; it brings `llm4s-java-api` and `reactor-core` with it.

**Maven**

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-reactor_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-reactor_3:{{ site.data.project.latest_release }}")
}
```

**sbt**

```scala
libraryDependencies += "org.llm4s" %% "llm4s-reactor" % "{{ site.data.project.latest_release }}"
```

It needs JDK 21, like `llm4s-java-api`.

## Stream a reply

Build a client as the [Java guide](java#configure-a-provider) does, then ask for a `Flux`:

```java
Flux<String> reply = Streams.toFlux(client, "Explain monads in one sentence");
String text = reply
    .doOnNext(System.out::print)
    .collect(Collectors.joining())
    .block(Duration.ofMinutes(1));
```

The `Flux` emits the pieces of the reply's text in order, then completes. It is cold: nothing is sent until it is
subscribed to, and each subscription makes its own provider call, on a thread of its own, so `block()` and a WebFlux
response each start one. A piece with no text is skipped, and tool-call deltas are not streamed.

In a WebFlux controller, return it from a handler that produces `text/event-stream` and each piece reaches the browser
as it arrives.

## A conversation and options

`toFlux` takes the same arguments as `JLlmClient.streamAsPublisher`: a conversation, and the options of a
`JCompletionOptions` builder.

```java
Conversation conversation = ConversationBuilder.create()
    .system("You are a concise assistant.")
    .user("Name three JVM languages.")
    .build();
JCompletionOptions options = JCompletionOptions.builder().temperature(0.2).maxTokens(200).build();
List<String> pieces = Streams.toFlux(client, conversation, options).collectList().block();
```

## Backpressure and stopping early

The `Flux` honours backpressure. A subscriber slower than the model holds the provider's thread rather than losing
text: up to 64 pieces wait for it, and then the model's thread waits too.

Cancelling the subscription - `dispose()`, an operator such as `take` or `timeout` that has what it needs, or a client
that disconnects from a WebFlux response - interrupts the provider call, which is how llm4s providers stop: the call
ends when its next piece arrives after the cancel.

```java
List<String> firstFive = Streams.toFlux(client, "Write a long story").take(5).collectList().block();
```

## Handling a failure

A failed call ends the `Flux` with `onError`, after every piece that streamed before the failure, carrying an
`LlmException` whose `getKind()` says what went wrong, as in the [Java guide](java#handling-a-failure). `toFlux` never
throws: a `null` client, query, conversation or options is a `Flux` that fails on subscription with kind `VALIDATION`.

```java
String text = Streams.toFlux(client, "Hello")
    .collect(Collectors.joining())
    .onErrorResume(LlmException.class, e -> switch (e.getKind()) {
        case RATE_LIMIT -> Mono.just("Busy, try again later");
        default -> Mono.just("Failed: " + e.getMessage());
    })
    .block();
```

## Other reactive libraries

Only Reactor has a module. RxJava 3 (`FlowAdapters` in `org.reactivestreams`), Mutiny
(`Multi.createFrom().publisher(...)`) and Kotlin's `Publisher.asFlow()` adapt `JLlmClient.streamAsPublisher`'s JDK
`Flow.Publisher` themselves, with no llm4s module. Agent event streams are not adapted yet; see
[streaming a turn](agents/streaming#java-and-kotlin).

## Where next

- [Java](java): the client, conversations, options and how to read a failure.
- [Spring Boot](spring-boot): auto-configuration that gives a WebFlux application its `JLlmClient`.
