---
layout: page
title: Micronaut
description: "Call a model from a Micronaut 4 app with LLM4S: a @Factory bean for JLlmClient, settings in application.yml, blocking and streamed endpoints and a health indicator."
search_title: "Use LLM4S with Micronaut: LLM client as a bean"
parent: User Guide
nav_order: 52
---

# Using LLM4S with Micronaut
{: .no_toc }

Call a model from a Micronaut 4 application: a `@Factory` bean for the `JLlmClient`, its settings in `application.yml`, a blocking and a streamed endpoint, a health indicator, and tests with `FakeLlmClient`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

There is no Micronaut module for llm4s. This page wires the [Java API](java) into Micronaut with a factory and
`@ConfigurationProperties`, which is all a first application needs. Every Java, YAML and Gradle snippet is an excerpt
of the runnable sample,
[`modules/samples/micronaut-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/micronaut-java), which CI
builds and tests against the library on every pull request.

## Run the sample

You need **JDK 21 or newer** and **Gradle 8.11 or newer**. The sample's default provider is a local
[Ollama](../getting-started/ollama-quickstart) server at `http://localhost:11434`, which needs no key:

```bash
ollama pull llama3.2
cd modules/samples/micronaut-java
gradle run -Pllm4sVersion=0.5.1
```

Then ask it something, once as a whole reply and once streamed:

```bash
curl -X POST -H 'Content-Type: text/plain' -d 'What is Micronaut, in one sentence?' http://localhost:8080/chat
curl -N -X POST -H 'Content-Type: text/plain' -d 'What is Micronaut?' http://localhost:8080/chat/stream
curl http://localhost:8080/health
```

Without `-Pllm4sVersion` the build uses `0.1.0-SNAPSHOT`, the code of your checkout, which you first publish to your
local Maven repository with `sbt publishM2` as the sample's README shows. `gradle test` needs neither Ollama nor a
key: the tests replace the client with a [`FakeLlmClient`](#testing).

## Dependencies

llm4s is Scala 3 only, and the `_3` suffix is part of every artifact name. `llm4s-java-api` brings `llm4s-core`,
`llm4s-agent` and the Scala library, but no provider: declare the provider modules the factory builds clients for.
Set `llm4sVersion` to {{ site.data.project.latest_release }} (or later).

```kotlin
    // Micronaut's dependency injection is compile-time: its annotation processor writes the bean definitions for this
    // project's classes. It never sees inside the llm4s jars, so the client becomes a bean through a @Factory.
    annotationProcessor(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation(platform("io.micronaut.platform:micronaut-platform:$micronautVersion"))
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    runtimeOnly("org.yaml:snakeyaml")
    runtimeOnly("ch.qos.logback:logback-classic")

    // Scala 3 only, and the _3 suffix is part of the name. The POM brings llm4s-core, llm4s-agent and the Scala
    // library; llm4s-java-api brings no provider, so declare the ones the factory builds clients for.
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-ollama_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai-compatible_3:$llm4sVersion")
```

The sample builds with Micronaut 4.10. Maven works the same way: the Micronaut parent POM, and the three llm4s
artifacts above as dependencies.

## Configuration

The client's settings are an `llm4s` block of `application.yml`:

```yaml
llm4s:
  provider: ollama
  model: llama3.2
  # base-url: http://localhost:11434
  # api-key:
```

Micronaut binds the block to a record at startup. Each key can also come from a system property
(`-Dllm4s.model=...`) or an environment variable (`LLM4S_MODEL`, `LLM4S_BASE_URL`, `LLM4S_API_KEY`), so a key never
has to be in the file:

```java
@ConfigurationProperties("llm4s")
public record Llm4sSettings(
    @Bindable(defaultValue = "ollama") String provider,
    String model,
    @Nullable String baseUrl,
    @Nullable String apiKey) {
}
```

These are the sample's own keys, mapped onto the [Java API's builders](java#configure-in-code) below; llm4s itself
does not read `application.yml`. The sample builds `ollama` and `openai-compatible` clients, the second reaching any
`/chat/completions` endpoint, OpenAI's included, by its base URL.

## The client as a bean

Micronaut computes its beans at compile time from the classes it compiles. The llm4s classes are already compiled,
so Micronaut cannot make a bean of `JLlmClient` by itself: a `@Factory` builds it. `@Bean(preDestroy = "close")`
closes the client, and the connections it holds, when the application context stops.

```java
@Factory
public class LlmClientFactory {

    @Singleton
    @Bean(preDestroy = "close")
    JLlmClient llmClient(Llm4sSettings settings) {
        LlmResult<JLlmClient> client = build(settings);
        if (client.isFailure()) {
            // a missing base URL or an unknown provider: fail the first injection with llm4s's message
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        return client.get();
    }

    static LlmResult<JLlmClient> build(Llm4sSettings settings) {
        return switch (settings.provider()) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(settings.model());
                yield (settings.baseUrl() == null ? builder : builder.baseUrl(settings.baseUrl())).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(settings.model()).baseUrl(settings.baseUrl());
                yield (settings.apiKey() == null ? builder : builder.apiKey(settings.apiKey())).build();
            }
            default -> throw new IllegalStateException(
                "Unknown llm4s.provider '" + settings.provider() + "': use ollama or openai-compatible");
        };
    }
}
```

One client serves the whole application: it is thread-safe, and building one per request would open a connection
pool per request.

## Calling it from a controller

`JLlmClient.complete` blocks until the model answers, which can take seconds. A Micronaut controller runs on a Netty
event-loop thread unless told otherwise, and a blocking call there stalls every request that thread serves.
`@ExecuteOn(TaskExecutors.BLOCKING)` moves the call to the blocking executor, which on JDK 21 gives each request a
virtual thread ([Virtual threads](java-threading-and-cancellation#virtual-threads)).

```java
    @Post(consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_PLAIN)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<String> chat(@Body String question) {
        if (question == null || question.isBlank()) {
            return HttpResponse.badRequest("Send a question as the request body");
        }
        LlmResult<String> answer = client.complete(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build());
        if (answer.isSuccess()) {
            return HttpResponse.ok(answer.get());
        }
        LlmException error = answer.getError();
        return HttpResponse.<String>status(HttpStatus.valueOf(statusOf(error)))
            .body(error.getKind() + ": " + error.getMessage());
    }
```

A failed call is an `LlmResult` value, not an exception, so the controller reads its
[kind](java#handling-a-failure) and answers with a status: a rate limit stays a 429, a timeout becomes a 504, and
anything else a 502.

The streamed endpoint needs no executor. `streamAsPublisher` returns a JDK `Flow.Publisher` that runs the call on a
thread of its own; `FlowAdapters` (from Reactive Streams, which Micronaut already depends on) turns it into the
`Publisher` Micronaut writes out, and each piece of text becomes a server-sent `Event`:

```java
    @Post(value = "/stream", consumes = MediaType.TEXT_PLAIN, produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream(@Body String question) {
        Publisher<String> pieces = FlowAdapters.toPublisher(client.streamAsPublisher(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build()));
        return Publishers.map(pieces, Event::of);
    }
```

Return `Event`s, not bare `String`s: a `Publisher<String>` is written as raw text with no `data:` framing, which a
browser's `EventSource` cannot read.

## Health check

`micronaut-management` serves `GET /health`. The sample adds an `llm4s` entry that is UP once the client is built
and shows the provider and model. It does not call the model, so a probe costs no tokens and does not go DOWN when
the provider is slow:

```java
    @Override
    protected Map<String, String> getHealthInformation() {
        healthStatus = HealthStatus.UP;
        return Map.of("provider", settings.provider(), "model", settings.model());
    }
```

`endpoints.health.details-visible: ANONYMOUS` in `application.yml` shows the details to every caller; restrict it
in production.

## Testing

`FakeLlmClient` from `llm4s-java-api` ([Testing your code](java#testing-your-code)) is a real `JLlmClient` that
answers from a script, with no network and no key. `JLlmClient` is a final class, so `@MockBean`, which proxies the
bean it replaces, cannot replace it; a test factory with `@Replaces` does:

```java
    // JLlmClient is final, so @MockBean (which proxies) cannot replace it: a factory with @Replaces does.
    @Factory
    static class FakeClients {

        @Singleton
        FakeLlmClient fake() {
            return FakeLlmClient.builder()
                .reply("Micronaut is a JVM framework.")
                .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
                .reply("Streamed answer")
                .build();
        }

        @Singleton
        @Replaces(JLlmClient.class)
        JLlmClient client(FakeLlmClient fake) {
            return fake.client();
        }
    }
```

The `@MicronautTest` then calls `/chat`, `/chat/stream` and `/health` over HTTP and checks the replies, the 429 of
the scripted failure and the prompt the fake recorded (`fake.prompts()`).

## Pitfalls

- **Compile-time injection and the Scala jars.** Micronaut's annotation processor sees only your sources. No llm4s
  class is a bean, an introspected type or serializable by Micronaut Serde: build the client in a `@Factory`, and
  map llm4s results to your own records before returning them as JSON.
- **The event loop.** Every blocking `JLlmClient` method needs `@ExecuteOn(TaskExecutors.BLOCKING)`; the streamed
  and `*Async` methods do not.
- **JDK 21.** llm4s is JDK 21 bytecode; compile and run the application on 21 or newer.
- **Vendor SDKs.** `llm4s-openai` and `llm4s-anthropic` bring the vendors' Java SDKs and their Jackson; the Micronaut
  platform BOM may pin a different Jackson. The sample avoids both with `openai-compatible`; if you add them, check
  `gradle dependencies` for the Jackson version that wins.
- **Native image.** Not supported: llm4s ships no GraalVM metadata yet
  ([#1463](https://github.com/llm4s/llm4s/issues/1463)).

## Where next

- [Java](java): conversations, completion options, structured output and failures from Java.
- [Java threading and cancellation](java-threading-and-cancellation): which calls block and how to cancel them.
- [Helidon](helidon) and [Quarkus](quarkus): the same client in the other JVM frameworks.
