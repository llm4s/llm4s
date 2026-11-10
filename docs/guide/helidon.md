---
layout: page
title: Helidon
description: "Call a model from a Helidon SE 4 app with LLM4S: JLlmClient built from application.yaml, blocking and streamed routes on virtual threads and a health check."
search_title: "Use LLM4S with Helidon SE 4: blocking and streamed routes"
parent: User Guide
nav_order: 53
---

# Using LLM4S with Helidon
{: .no_toc }

Call a model from a Helidon SE 4 application: the `JLlmClient` built from `application.yaml`, a blocking and a streamed route on Helidon's virtual threads, a health check, and tests with `FakeLlmClient`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

There is no Helidon module for llm4s. This page wires the [Java API](java) into Helidon SE with its own config and
routing. Every Java, YAML and Gradle snippet is an excerpt of the runnable sample,
[`modules/samples/helidon-java`](https://github.com/llm4s/llm4s/tree/main/modules/samples/helidon-java), which CI
builds and tests against the library on every pull request. Helidon MP is a MicroProfile runtime: there, a CDI
producer as in the [Quarkus guide](quarkus#the-client-as-a-cdi-bean), with `@ConfigProperty` in place of
`@ConfigMapping`, does the same job.

## Run the sample

You need **JDK 21 or newer** and **Gradle 8.11 or newer**. The sample's default provider is a local
[Ollama](../getting-started/ollama-quickstart) server at `http://localhost:11434`, which needs no key:

```bash
ollama pull llama3.2
cd modules/samples/helidon-java
gradle run -Pllm4sVersion=0.5.1
```

Then ask it something, once as a whole reply and once streamed:

```bash
curl -X POST -H 'Content-Type: text/plain' -d 'What is Helidon, in one sentence?' http://localhost:8080/chat
curl -N -X POST -H 'Content-Type: text/plain' -d 'What is Helidon?' http://localhost:8080/chat/stream
curl http://localhost:8080/observe/health
```

Without `-Pllm4sVersion` the build uses `0.1.0-SNAPSHOT`, the code of your checkout, which you first publish to your
local Maven repository with `sbt publishM2` as the sample's README shows. `gradle test` needs neither Ollama nor a
key: the tests serve the routes with a [`FakeLlmClient`](#testing).

## Dependencies

llm4s is Scala 3 only, and the `_3` suffix is part of every artifact name. `llm4s-java-api` brings `llm4s-core`,
`llm4s-agent` and the Scala library, but no provider: declare the provider modules you build clients for. Set
`llm4sVersion` to {{ site.data.project.latest_release }} (or later).

```kotlin
    implementation(platform("io.helidon:helidon-dependencies:$helidonVersion"))
    implementation("io.helidon.webserver:helidon-webserver")
    implementation("io.helidon.webserver:helidon-webserver-sse")
    implementation("io.helidon.webserver.observe:helidon-webserver-observe-health")
    implementation("io.helidon.config:helidon-config-yaml")

    // Scala 3 only, and the _3 suffix is part of the name. The POM brings llm4s-core, llm4s-agent and the Scala
    // library; llm4s-java-api brings no provider, so declare the ones Llm4sSettings builds clients for.
    implementation("org.llm4s:llm4s-java-api_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-ollama_3:$llm4sVersion")
    implementation("org.llm4s:llm4s-openai-compatible_3:$llm4sVersion")
    // llm4s logs through SLF4J; Helidon logs through java.util.logging. This sends llm4s's logs to the same place.
    runtimeOnly("org.slf4j:slf4j-jdk14:2.0.17")
```

The sample builds with Helidon 4.5. Maven works the same way: the `helidon-se` parent POM, and the three llm4s
artifacts above as dependencies.

## Configuration

The client's settings are an `llm4s` node of `application.yaml`:

```yaml
llm4s:
  provider: ollama
  model: llama3.2
  # base-url: http://localhost:11434
  # api-key:
```

A record reads the node and builds the client with the [Java API's builders](java#configure-in-code). Each key can
also come from a system property (`-Dllm4s.model=...`) or an environment variable (`LLM4S_MODEL`, `LLM4S_BASE_URL`,
`LLM4S_API_KEY`), so a key never has to be in the file. llm4s itself does not read `application.yaml`.

```java
public record Llm4sSettings(String provider, String model, String baseUrl, String apiKey) {

    static Llm4sSettings from(Config llm4s) {
        return new Llm4sSettings(
            llm4s.get("provider").asString().orElse("ollama"),
            llm4s.get("model").asString().get(),
            llm4s.get("base-url").asString().orElse(null),
            llm4s.get("api-key").asString().orElse(null));
    }

    /** The client, built with the Java facade's builders: a failed result for a missing base URL. */
    LlmResult<JLlmClient> build() {
        return switch (provider) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(model);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(model).baseUrl(baseUrl);
                yield (apiKey == null ? builder : builder.apiKey(apiKey)).build();
            }
            default -> throw new IllegalStateException(
                "Unknown llm4s.provider '" + provider + "': use ollama or openai-compatible");
        };
    }
}
```

The sample builds `ollama` and `openai-compatible` clients, the second reaching any `/chat/completions` endpoint,
OpenAI's included, by its base URL.

## Starting the server

`main` builds one client, which serves the whole application, and fails at startup with llm4s's message when the
configuration is wrong. `setUp` registers the health check and the routes; the tests call it too:

```java
    public static void main(String[] args) {
        Config config = Config.create();
        Llm4sSettings settings = Llm4sSettings.from(config.get("llm4s"));
        LlmResult<JLlmClient> client = settings.build();
        if (client.isFailure()) {
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        WebServerConfig.Builder builder = WebServer.builder().config(config.get("server"));
        setUp(builder, client.get(), settings);
        WebServer server = builder.build().start();
        System.out.println("Listening on http://localhost:" + server.port() + "/chat");
    }

    /** The application's health check and routes, shared with the tests. */
    static void setUp(WebServerConfig.Builder server, JLlmClient client, Llm4sSettings settings) {
        server.addFeature(ObserveFeature.just(HealthObserver.builder()
                .details(true)
                .addCheck(llm4sCheck(settings))
                .build()))
            .routing(routing -> routing.register("/chat", new ChatService(client)));
    }
```

The routes are an `HttpService`, which owns the client and closes it in `afterStop`, when the server stops:

```java
    @Override
    public void routing(HttpRules rules) {
        rules.post("/", this::chat)
            .post("/stream", this::stream);
    }

    @Override
    public void afterStop() {
        client.close();
    }
```

## Blocking and streamed routes

Helidon SE 4 runs every request on a virtual thread of its own, so a handler may block: no executor, no reactive
type. `JLlmClient.complete` waits for the model without pinning the carrier thread
([Virtual threads](java-threading-and-cancellation#virtual-threads)).

```java
    private void chat(ServerRequest req, ServerResponse res) {
        String question = req.content().as(String.class);
        if (question.isBlank()) {
            res.status(Status.BAD_REQUEST_400).send("Send a question as the request body");
            return;
        }
        LlmResult<String> answer = client.complete(conversation(question).build());
        if (answer.isSuccess()) {
            res.send(answer.get());
            return;
        }
        LlmException error = answer.getError();
        res.status(Status.create(statusOf(error))).send(error.getKind() + ": " + error.getMessage());
    }
```

A failed call is an `LlmResult` value, not an exception, so the handler reads its
[kind](java#handling-a-failure) and answers with a status: a rate limit stays a 429, a timeout becomes a 504, and
anything else a 502.

The streamed route writes server-sent events to an `SseSink`, one per piece of the reply. The listener runs on the
stream's own thread; the request's virtual thread waits for the whole reply before the sink closes and the response
ends:

```java
    private void stream(ServerRequest req, ServerResponse res) {
        String question = req.content().as(String.class);
        try (SseSink sink = res.sink(SseSink.TYPE)) {
            LlmResult<CompletionStream> stream =
                client.stream(conversation(question).build(), piece -> sink.emit(SseEvent.create(piece)));
            if (stream.isSuccess()) {
                stream.get().await();
            }
        }
    }
```

## Health check

`helidon-webserver-observe-health` serves `GET /observe/health`. The sample adds an `llm4s` check that is UP once
the client is built and shows the provider and model. It does not call the model, so a probe costs no tokens and does
not go DOWN when the provider is slow:

```java
            @Override
            public HealthCheckResponse call() {
                return HealthCheckResponse.builder()
                    .status(HealthCheckResponse.Status.UP)
                    .detail("provider", settings.provider())
                    .detail("model", settings.model())
                    .build();
            }
```

## Testing

`FakeLlmClient` from `llm4s-java-api` ([Testing your code](java#testing-your-code)) is a real `JLlmClient` that
answers from a script, with no network and no key. A `@ServerTest` starts the server on a free port with the
application's own `setUp`, given the fake:

```java
    static final FakeLlmClient FAKE = FakeLlmClient.builder()
        .reply("Helidon is a set of Java libraries.")
        .failWith(LlmErrorKind.TIMEOUT, "too slow")
        .reply("Streamed answer")
        .build();
```

```java
    @SetUpServer
    static void server(WebServerConfig.Builder server) {
        Main.setUp(server, FAKE.client(), new Llm4sSettings("ollama", "stub-model", null, null));
    }
```

The test then calls `/chat`, `/chat/stream` and `/observe/health` with the injected `Http1Client` and checks the
replies, the 504 of the scripted timeout and the prompt the fake recorded (`FAKE.prompts()`).

## Pitfalls

- **Blocking calls are fine on Helidon SE 4, not on Helidon SE 3.** Helidon 4's server is built on virtual threads,
  so the blocking `JLlmClient` methods are the natural fit. Helidon 3 ran handlers on a Netty event loop, where the
  same call needs an executor; this page is for Helidon 4 only.
- **Logging.** Helidon logs through `java.util.logging` and llm4s through SLF4J. Without an SLF4J binding llm4s's
  logs are dropped with a warning; `slf4j-jdk14` sends them to the JUL configuration Helidon uses.
- **JDK 21.** llm4s is JDK 21 bytecode, and Helidon 4 needs 21 too.
- **Native image.** Not supported: llm4s ships no GraalVM metadata yet
  ([#1463](https://github.com/llm4s/llm4s/issues/1463)).

## Where next

- [Java](java): conversations, completion options, structured output and failures from Java.
- [Java threading and cancellation](java-threading-and-cancellation): which calls block and how to cancel them.
- [Micronaut](micronaut) and [Quarkus](quarkus): the same client in the other JVM frameworks.
