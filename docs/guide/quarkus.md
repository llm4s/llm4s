---
layout: page
title: Quarkus
description: "Call a model from a Quarkus app with the quarkus-llm4s extension: JLlmClient beans from application.properties, checked at build time, with a readiness check."
search_title: "Use LLM4S with Quarkus: the quarkus-llm4s extension"
parent: User Guide
nav_order: 37
---

# Using LLM4S with Quarkus
{: .no_toc }

Call a model from a Quarkus application with the `quarkus-llm4s` extension: the clients configured in `application.properties` and checked when the application is built, injected as CDI beans, with a readiness check.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

`quarkus-llm4s` ([`modules/quarkus`](https://github.com/llm4s/llm4s/tree/main/modules/quarkus)) is a Quarkus
extension over the [Java API](java). Every snippet on this page is a file of the runnable sample,
[`modules/samples/quarkus-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/quarkus-java), which CI
builds and tests against the extension on every pull request.

**Status.** The extension is new ([#2280](https://github.com/llm4s/llm4s/issues/2280)) and **not yet published**: build
it from this repository as below. It is built and tested against **Quarkus 3.40.1**, in JVM mode only. Dev Services,
metrics, a Dev UI card and native images are not there yet ([What is not supported](#what-is-not-supported)).

## Run the sample

You need **JDK 21 or newer**, **sbt** and **Maven 3.9 or newer**. Install the llm4s modules and the extension in your
local Maven repository, from the root of the repository:

```bash
sbt 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
(cd modules/quarkus && mvn install)
```

The sample's default provider is a local [Ollama](../getting-started/ollama-quickstart) server at
`http://localhost:11434`, which needs no key:

```bash
ollama pull llama3.2
cd modules/samples/quarkus-java
mvn quarkus:dev
curl -X POST -H 'Content-Type: text/plain' -d 'What is Quarkus, in one sentence?' http://localhost:8080/chat
```

The reply is the model's answer as plain text, and `http://localhost:8080/q/health/ready` reports the client.
`mvn verify` runs the sample's tests, which need neither Ollama nor a key: they start a stub Ollama server on a
loopback port (see [Testing](#testing)).

## Dependencies

The extension brings `llm4s-java-api` (and with it `llm4s-core`, the agent runtime and the Scala 3 library) and no
provider module: declare the ones your configuration names, and only those, so no other vendor's SDK reaches the
application ([#1496](https://github.com/llm4s/llm4s/issues/1496)). The provider modules are Scala 3 only, and Maven has
no notion of Scala cross-versions, so each is written with its Scala binary suffix, `_3`:

```xml
<dependency>
  <groupId>org.llm4s</groupId>
  <artifactId>quarkus-llm4s</artifactId>
  <version>${llm4s.version}</version>
</dependency>
<dependency>
  <groupId>org.llm4s</groupId>
  <artifactId>llm4s-ollama_3</artifactId>
  <version>${llm4s.version}</version>
</dependency>
```

The sample also declares `llm4s-openai` and `llm4s-anthropic`, so switching provider is configuration only, and
`quarkus-smallrye-health` for the readiness check. Next to the Quarkus BOM, it pins `org.scala-lang:scala3-library_3`
to the version llm4s was built with in `dependencyManagement`, by name: Scala 3 runs on the Scala 2.13
`scala-library`, so never pin the whole `org.scala-lang` group.

Quarkus routes SLF4J to its own log manager (`slf4j-jboss-logmanager`), so llm4s's logs appear in the Quarkus log with
no logging backend added. Do not add `logback-classic`: two SLF4J providers make SLF4J pick one and warn.

## Configuration

The clients are sections of `llm4s.providers` in `application.properties`, in the
[Spring Boot starter](spring-boot)'s spelling, which mirrors the `llm4s.providers` block of llm4s's own
[configuration](../getting-started/configuration):

```properties
llm4s.providers.provider=main
llm4s.providers.main.provider=ollama
llm4s.providers.main.model=llama3.2
```

- **Sections.** Each section `llm4s.providers.<section>.*` is one client: `provider` (a provider id, such as `ollama`,
  `openai`, `anthropic`, `gemini`, `azure` or `openai-compatible`, whose module is a dependency) and `model` are
  required, and every other key is the provider's own: `base-url`, `api-key`, `headers.*`, `endpoint` for Azure, ...
  (each provider module's README lists them). `kebab-case` names are read as llm4s's `camelCase` (`api-key` is
  `apiKey`); header names stay as written.
- **The default.** `llm4s.providers.provider` names the section whose client is the `@Default` bean. Unset, the only
  section is the default; with several and none named, each client is reachable by name only.
- **Keys.** A section with no `api-key` uses the vendor's shared key, `llm4s.credentials.<provider>.api-key`, and then
  the vendor's conventional environment variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...). Keep a real key out of
  `application.properties`: rely on the variable, or write `api-key=${MY_KEY}`.
- **Embeddings.** `llm4s.embeddings.model=<provider>/<model>` (for example `ollama/nomic-embed-text`) makes a
  `JEmbeddingClient` bean; that provider's settings are `llm4s.embeddings.<provider>.*`
  (`llm4s.embeddings.ollama.base-url`).
- **HTTP.** `llm4s.http.*` sets the proxy and TLS settings, as in HOCON.
- **Profiles and variables.** These are Quarkus properties: `%dev.` and `%test.` profiles and `${...}` expressions work
  as usual, and an environment variable such as `LLM4S_PROVIDERS_MAIN_MODEL` overrides a key that
  `application.properties` declares. A `llm4s.providers` block in an `application.conf` is not read.

### Checked at build time

The sections are fixed when the application is built: each becomes a bean. The build fails, with a message naming the
property, when a section has no `provider` or `model`, when a provider is not on the classpath (the message lists those
that are), when `llm4s.providers.provider` names no section, or when `llm4s.embeddings.model` is not
`provider/model` of an embedding provider on the classpath:

```text
The llm4s configuration is not valid:
  - Provider 'olama' (from llm4s.providers.main.provider) is not registered. Registered providers: anthropic, ollama, openai. ...
```

The values themselves (the model, the key) are read when the application starts, so they can differ per environment.
A client that still cannot be built, such as one whose key is missing, fails the start with llm4s's message.

## The clients as CDI beans

Each section is a `@Singleton` `JLlmClient` bean qualified `@Named("<section>")` (and `@Identifier("<section>")`), the
default section's also `@Default`; the embedding model is a `JEmbeddingClient` bean. They are built when the
application starts and closed when it stops. So `@Inject JLlmClient client` is the default section's client,
`@Inject @Named("fast") JLlmClient fast` the client of `llm4s.providers.fast`, and `@Inject JEmbeddingClient embeddings`
the embedding model's. One client serves every request: a `JLlmClient` can be used by many threads at once
([Sharing one client](java-threading-and-cancellation#sharing-one-client-or-agent)).

Every bean is a default bean: a producer of your own for the same type and qualifiers replaces the extension's, which
is then never built. That is also how a test supplies a fake ([Testing](#testing)).

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

## Health

With `quarkus-smallrye-health` on the classpath, the extension registers a readiness check named `llm4s` for the
default client, at `/q/health/ready`:

| Property | Default | |
|---|---|---|
| `llm4s.health.enabled` | `true` | Register the check (read at build time). |
| `llm4s.health.probe` | `false` | Off, the check is UP with `probe=disabled`: the client was built, nothing is sent. On, it sends a one-token completion: UP with `probe=ok`, or DOWN with `probe=failed` or `timeout` and the provider's error. |
| `llm4s.health.probe-timeout` | `10s` | How long the probe waits. |
| `llm4s.health.probe-ttl` | `60s` | How long a probe's outcome is reused, so frequent checks do not each pay for a call. |

The check's data names the section, the provider and the model; a key-like string in an error is redacted.

## Testing

`@QuarkusTest` starts the application with the extension's real client, pointed at a stub. The sample's `OllamaStub`
is a `QuarkusTestResourceLifecycleManager`: it starts a JDK `HttpServer` on a free loopback port that answers Ollama's
`POST /api/chat` with a canned reply, and returns `llm4s.providers.main.base-url` and `llm4s.providers.main.model` as
configuration for the test run. The test then calls `/chat` with REST Assured and checks the reply, the request the
stub received, that a stub failure comes back as a 502, and that the readiness check is UP; no model and no key are
involved.

To test code that uses the client without any HTTP, produce a fake: `FakeLlmClient.replying("...").client()` from
`llm4s-java-api` ([Testing your code](java#testing-your-code)) is a `JLlmClient`, and a test producer of it replaces the
extension's bean.

## Without the extension

An application that cannot use the extension (it needs a provider setting the extension does not pass, or builds
clients at run time) can wire the Java API into Quarkus with plain CDI: a `@ConfigMapping` interface for its own
settings, and a `@Produces @Singleton JLlmClient` method that builds the client with the Java API's builders
(`Llm4s.ollama()`, `Llm4s.openAi()`, ...) or `Llm4s.createDefaultClient()`, with a `@Disposes` method that closes it.
The sample took that form before the extension; its last version is at commit
[`225a7d77b`](https://github.com/llm4s/llm4s/tree/225a7d77b/modules/samples/quarkus-java).

## What is not supported

- **Native image.** A native build (`-Dnative`) fails at once with a message: llm4s ships no GraalVM reachability
  metadata yet. That work is the spike in [#1463](https://github.com/llm4s/llm4s/issues/1463). Run the application on
  the JVM.
- **Not yet in the extension** ([#2280](https://github.com/llm4s/llm4s/issues/2280)): Dev Services (an Ollama container
  in dev and test mode), Micrometer metrics (meanwhile, record them with `llm4s-observability-micrometer` over Quarkus's
  registry: [Micrometer metrics](observability/micrometer)), a Dev UI card, tracing settings, and publication to Maven
  Central with an entry in `llm4s-bom`.
- **The agent API from Java.** This page uses `JLlmClient` and `ConversationBuilder` only; the Java agent API is
  still changing.

## Where next

- [Java](java): conversations, completion options, structured output and failures from Java.
- [Java threading and cancellation](java-threading-and-cancellation): which calls block and how to cancel them.
- [Spring Boot](spring-boot): the same clients as a Spring Boot starter, with auto-configuration.
- [Maven quick start](../getting-started/maven): the plain Java project this sample builds on.
