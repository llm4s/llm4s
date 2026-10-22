---
layout: page
title: Quarkus
parent: User Guide
nav_order: 37
---

# Using LLM4S with Quarkus
{: .no_toc }

Call a model from a Quarkus application: a CDI producer for the `JLlmClient`, its settings in `application.properties`, and a REST endpoint that keeps the blocking call off the event loop.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

There is no Quarkus extension for llm4s. This page wires the [Java API](java) into Quarkus with plain CDI and
MicroProfile Config, which is all a first application needs. Every snippet is a file of the runnable sample,
[`modules/samples/quarkus-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/quarkus-java), which CI
builds and tests against the library on every pull request.

## Run the sample

You need **JDK 21 or newer** and **Maven 3.9 or newer**. The sample's default provider is a local
[Ollama](../getting-started/ollama-quickstart) server at `http://localhost:11434`, which needs no key:

```bash
ollama pull llama3.2
cd modules/samples/quarkus-java
mvn quarkus:dev -Dllm4s.version=0.5.1
```

Then ask it something:

```bash
curl -X POST -H 'Content-Type: text/plain' -d 'What is Quarkus, in one sentence?' http://localhost:8080/chat
```

The reply is the model's answer as plain text. Without `-Dllm4s.version` the build uses `0.1.0-SNAPSHOT`, the code of
your checkout, which you first publish to your local Maven repository with `sbt publishM2` from the repository root,
after setting the build's version to `0.1.0-SNAPSHOT` as the sample's README shows.

`mvn verify` runs the sample's tests, which need neither Ollama nor a key: they start a stub Ollama server on a
loopback port (see [Testing](#testing)).

## Dependencies

llm4s is Scala 3 only, and Maven has no notion of Scala cross-versions, so the artifact is written with its Scala
binary suffix, `llm4s-java-api_3`. It brings `llm4s-core`, the agent runtime, the provider modules and the Scala 3
library:

```xml
<dependency>
  <groupId>org.llm4s</groupId>
  <artifactId>llm4s-java-api_3</artifactId>
  <version>${llm4s.version}</version>
</dependency>
```

Next to the Quarkus BOM, the sample pins `org.scala-lang:scala3-library_3` to the version llm4s was built with in
`dependencyManagement`, by name: Scala 3 runs on the Scala 2.13 `scala-library`, so never pin the whole
`org.scala-lang` group. The Quarkus BOM pins Jackson to its own version, newer than the one the OpenAI and Anthropic
SDKs ask for; the sample's tests build both clients with it.

Quarkus routes SLF4J to its own log manager (`slf4j-jboss-logmanager`), so llm4s's logs appear in the Quarkus log with
no logging backend added. Do not add `logback-classic`, as a plain Java application would: two SLF4J providers make
SLF4J pick one and warn.

## Configuration

The client's settings are four `llm4s.*` keys in `application.properties`, read by a `@ConfigMapping` interface.
Quarkus maps `baseUrl()` to `llm4s.base-url` and `apiKey()` to `llm4s.api-key`, and each key can also be set as a
system property or an environment variable (`LLM4S_PROVIDER`, `LLM4S_MODEL`, `LLM4S_BASE_URL`, `LLM4S_API_KEY`):

```java
@ConfigMapping(prefix = "llm4s")
public interface Llm4sSettings {

    /** Which client to build: {@code ollama}, {@code openai}, {@code anthropic} or {@code openai-compatible}. */
    @WithDefault("ollama")
    String provider();

    /** The model name, as the provider spells it. */
    String model();

    /** {@code llm4s.base-url}: the provider's endpoint; each provider has a default except {@code openai-compatible}. */
    Optional<String> baseUrl();

    /** {@code llm4s.api-key}: required by {@code openai} and {@code anthropic}, unused by {@code ollama}. */
    Optional<String> apiKey();
}
```

```properties
llm4s.provider=ollama
llm4s.model=llama3.2
```

So `LLM4S_PROVIDER=openai LLM4S_MODEL=gpt-4o-mini LLM4S_API_KEY=sk-... mvn quarkus:dev` runs the same application
against OpenAI. Keep a real key out of `application.properties`: use the environment variable, or a `.env` file that
is not committed.

These keys are the sample's own and are separate from llm4s's HOCON configuration (`application.conf`, the
[Configuration guide](../getting-started/configuration)): the client is built with the Java API's builders, which
take every setting as an argument and read no provider section. An application that already keeps its provider in
`application.conf` can build the client with `Llm4s.createDefaultClient()` in the producer instead.

## The client as a CDI bean

A producer method makes the `JLlmClient` an application-scoped bean, built from the settings, and a disposer method
closes it when the application stops. A setting llm4s refuses, such as a missing key, fails the first injection with
llm4s's message:

```java
@ApplicationScoped
public class LlmClientProducer {

    @Produces
    @ApplicationScoped
    JLlmClient llmClient(Llm4sSettings settings) {
        LlmResult<JLlmClient> client = build(settings);
        if (client.isFailure()) {
            // a missing key or an unknown provider: fail the first injection with llm4s's message
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        return client.get();
    }

    void close(@Disposes JLlmClient client) {
        client.close();
    }

    static LlmResult<JLlmClient> build(Llm4sSettings settings) {
        String baseUrl = settings.baseUrl().orElse(null);
        String apiKey = settings.apiKey().orElse(null);
        return switch (settings.provider()) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(settings.model());
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai" -> {
                var builder = Llm4s.openAi().model(settings.model()).apiKey(apiKey);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "anthropic" -> {
                var builder = Llm4s.anthropic().model(settings.model()).apiKey(apiKey);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(settings.model()).baseUrl(baseUrl);
                yield (apiKey == null ? builder : builder.apiKey(apiKey)).build();
            }
            default -> throw new IllegalStateException("Unknown llm4s.provider '" + settings.provider()
                + "': use ollama, openai, anthropic or openai-compatible");
        };
    }
}
```

`JLlmClient` is a final class with no public constructor, which CDI cannot normally proxy for a normal scope such as
`@ApplicationScoped`. Quarkus's ArC handles it at build time (`quarkus.arc.transform-unproxyable-classes`, on by
default); if you have turned that off, produce the client as `@Singleton` instead, which needs no proxy.

One client serves every request: one `JLlmClient` can be used by many threads at once
([Sharing one client](java-threading-and-cancellation#sharing-one-client-or-agent)), and it holds an HTTP client that
should be built once and closed once.

## Calling it from a REST resource

`JLlmClient.complete` blocks the calling thread until the model answers, which can take seconds. Quarkus REST
picks an endpoint's thread from its signature and may run it on a Vert.x event-loop thread (it does for one returning
a `Uni`), where a blocking call stalls every request that thread serves. `@Blocking` states the choice instead of
leaving it to the signature: the call runs on a worker thread. On JDK 21 `@RunOnVirtualThread` is the alternative,
and each request then gets a virtual thread; the Ollama client does not pin the carrier thread while it waits
([Virtual threads](java-threading-and-cancellation#virtual-threads)).

```java
@Path("/chat")
public class ChatResource {

    @Inject
    JLlmClient client;

    // JLlmClient.complete blocks until the model answers, so it must not run on the Vert.x event loop: @Blocking puts
    // the call on a worker thread (@RunOnVirtualThread would put it on a virtual thread instead).
    @POST
    @Blocking
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Response chat(String question) {
        if (question == null || question.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Send a question as the request body").build();
        }
        LlmResult<String> answer = client.complete(ConversationBuilder.create()
            .system("You are a concise assistant.")
            .user(question)
            .build());
        if (answer.isSuccess()) {
            return Response.ok(answer.get()).build();
        }
        LlmException error = answer.getError();
        return Response.status(statusOf(error)).entity(error.getKind() + ": " + error.getMessage()).build();
    }

    /** A failure is the model's, not the caller's: report it as a gateway error, keeping a rate limit visible. */
    private static int statusOf(LlmException error) {
        return switch (error.getKind()) {
            case RATE_LIMIT -> 429;
            case TIMEOUT -> 504;
            default -> 502;
        };
    }
}
```

A failed call is an `LlmResult` value, not an exception, so the resource reads its
[kind](java#handling-a-failure) and answers with a status: a rate limit stays a 429, a timeout becomes a 504, and
anything else a 502, since the failure is the model's and not the caller's.

## Testing

`@QuarkusTest` starts the application with the real producer and the real client, pointed at a stub. The sample's
`OllamaStub` is a `QuarkusTestResourceLifecycleManager`: it starts a JDK `HttpServer` on a free loopback port that
answers Ollama's `POST /api/chat` with a canned reply, and returns `llm4s.base-url` (with `llm4s.provider` and
`llm4s.model`) as configuration for the test run. The test then calls `/chat` with REST Assured and checks the reply,
the request the stub received, and that a stub failure comes back as a 502; no model and no key are involved.

To test code that uses the client without any HTTP, inject a fake instead: `FakeLlmClient.replying("...").client()`
from `llm4s-java-api` ([Testing your code](java#testing-your-code)) is a `JLlmClient`, which a test producer
annotated `@Alternative @Priority(1)`, or a `@QuarkusTest` with `QuarkusMock.installMockForType`, can supply.

## What is not supported

- **Native image.** `mvn package -Dnative` is not supported: llm4s ships no GraalVM reachability metadata yet. That
  work is the spike in [#1463](https://github.com/llm4s/llm4s/issues/1463). Run the application on the JVM.
- **The agent API from Java.** This page uses `JLlmClient` and `ConversationBuilder` only; the Java agent API is
  still changing.
- **Dev Services, Dev UI, health checks and metrics.** Nothing is registered for you. Add a health check of your own,
  or record metrics with `llm4s-observability-micrometer` over Quarkus's Micrometer registry
  ([Micrometer metrics](observability/micrometer)).

## What an extension would add

A Quarkus extension (`quarkus-llm4s`) would replace the producer and the configuration interface above, and could
add what plain CDI cannot:

- **Build-time wiring**: the `llm4s.*` configuration and the producer generated by the extension, with a
  configuration error reported at build time instead of at first use.
- **Dev Services**: an Ollama container started in dev and test mode when no base URL is configured, as Quarkus does
  for databases.
- **Observability**: a health check calling the provider, and Micrometer metrics and OpenTelemetry traces wired from
  the Quarkus extensions already present.
- **Native image**: the reflection and resource registrations llm4s needs, once
  [#1463](https://github.com/llm4s/llm4s/issues/1463) has found them.
- **A Dev UI card** with the configured provider and a prompt box.

Whether that is worth building depends on feedback from this guide: open an issue or a discussion if you use it.

## Where next

- [Java](java): conversations, completion options, structured output and failures from Java.
- [Java threading and cancellation](java-threading-and-cancellation): which calls block and how to cancel them.
- [Spring Boot](spring-boot): the same client as a Spring Boot starter, with auto-configuration.
- [Maven quick start](../getting-started/maven): the plain Java project this sample builds on.
