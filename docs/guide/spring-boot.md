---
layout: page
title: Spring Boot
description: "Use LLM4S with Spring Boot: add llm4s-spring-boot-starter, configure it in application.properties and inject an LLM4STemplate with a health indicator."
search_title: "Use LLM4S with Spring Boot: LLM client starter"
parent: User Guide
nav_order: 7
---

# Using LLM4S with Spring Boot
{: .no_toc }

Add `llm4s-spring-boot-starter` and inject an `LLM4STemplate`: the client, an executor for asynchronous calls and a health indicator are configured from your `application.properties`.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## What the starter gives you

The starter is Spring Boot auto-configuration on top of the [Java API](java). When it is on the classpath it registers:

| Bean | What it is | Replace it by |
|---|---|---|
| `llm4sClient` | the `JLlmClient`, built from the `llm4s.*` properties; closed when the context closes | defining a bean of type `JLlmClient` |
| `llm4sTaskExecutor` | the `ExecutorService` behind `completeAsync` and the health probe | defining a bean named `llm4sTaskExecutor` |
| `llm4sTemplate` | the `LLM4STemplate` you inject | defining a bean of type `LLM4STemplate` |
| `llm4sTools` | the `Llm4sTools`: every `@Tool` method of your beans (see [An agent with tools](#an-agent-with-tools)) | defining a bean of type `Llm4sTools` |
| `llm4sAgent` | a `JAgent` over the client and those tools, configured by `llm4s.agent.*`; absent with `llm4s.agent.enabled=false` | defining a bean of type `JAgent` |
| `llm4sCheckpointer` | the `Checkpointer` the agent keeps its threads in: an `InMemoryCheckpointer`; absent with `llm4s.agent.enabled=false` | defining a bean of type `Checkpointer` |
| `llm4sEmbeddingClient` | a `JEmbeddingClient`, only when `llm4s.embeddings.model` is set (see [Embeddings](#embeddings)) | defining a bean of type `JEmbeddingClient` |
| `llm4sTracing` | the `Tracing` the agent reports to, only when `llm4s.tracing.mode` is set (see [Tracing](#tracing)) | defining a bean of type `Tracing` |
| `llm4sReactiveTemplate` | a `ReactiveLLM4STemplate`, replies as a Reactor `Flux`, only when Reactor and `llm4s-reactor` are on the classpath (see [Streaming to a browser](#streaming-to-a-browser)) | defining a bean of type `ReactiveLLM4STemplate` |
| `llm4sSseTemplate` | an `SseLLM4STemplate`, replies as a Spring MVC `SseEmitter`, only when Spring MVC is on the classpath | defining a bean of type `SseLLM4STemplate` |
| `llmHealthIndicator` | an Actuator `HealthIndicator`, only when Spring Boot Actuator is on the classpath | defining a bean of type `LlmHealthIndicator` (`LlmBoot4HealthIndicator` on Spring Boot 4) |
| `llm4sMetricsCollector` | a `MicrometerMetrics` over your `MeterRegistry`, only when `llm4s-observability-micrometer` is on the classpath and there is a `MeterRegistry` bean; the client reports every call to it (see [Metrics](#metrics)) | defining a bean of type `MetricsCollector` |

Set `llm4s.enabled=false` to switch all of them off; the beans are also registered when `llm4s.enabled` is absent.

**Spring Boot versions.** One artifact supports Spring Boot 3.x from 3.3 on and Spring Boot 4.x from 4.0 on. It is
built against 3.3.6, and CI runs its whole test suite again against the latest 3.x release (3.5.16), against the 4.0
line (4.0.8) and against the latest 4.x release (4.1.1), so both ends of each range are tested on every change; the
Spring Boot 4 runs test the published classes, not a Boot 4 build of them. The one difference is the health indicator:
Spring Boot 4 moved `HealthIndicator` to `org.springframework.boot.health.contributor`, so there the bean
`llmHealthIndicator` is an `LlmBoot4HealthIndicator`, reporting exactly what `LlmHealthIndicator` reports on 3.x.

{: .note }
> `llm4s-spring-boot-starter` is on Maven Central from `0.5.0` on, together with the Java API it is built on; the
> current release is {{ site.data.project.latest_release }}. It needs **JDK 21**, like the Java API (see
> [Scala and JDK support](/reference/v1-scope#scala-and-jdk-support)); whether some artifacts get a lower floor before
> 1.0 is tracked in [#1493](https://github.com/llm4s/llm4s/issues/1493).

## Add the dependency

The artifact is `org.llm4s:llm4s-spring-boot-starter_3`. The `_3` is the Scala binary version (LLM4S is Scala 3 only), and
Maven and Gradle need it spelled out.

**Maven**

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-spring-boot-starter_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
<!-- and the provider module you call: llm4s-ollama_3, llm4s-anthropic_3, llm4s-gemini_3, ... -->
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-openai_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-spring-boot-starter_3:{{ site.data.project.latest_release }}")
    implementation("org.llm4s:llm4s-openai_3:{{ site.data.project.latest_release }}")   // the provider you call
}
```

It brings the Java API, so the [dependency notes of the Java guide](java#add-the-dependency) apply: no provider module
(declare the ones your configuration names, at the same version), Scala's standard library, and no logging backend. A
provider whose module is missing fails the client with a `CONFIGURATION` error naming the artifact to add. For the health indicator, add `spring-boot-starter-actuator` yourself:
the starter declares it as `provided`, so it is not on your classpath unless you add it.

## Configure it

The starter reads Spring properties under the `llm4s` prefix. It does **not** read the HOCON `application.conf` that the
[Java guide](java#configure-a-provider) uses: here the provider, model and key are properties.

```properties
llm4s.provider=openai
llm4s.model=gpt-4o
llm4s.api-key=${OPENAI_API_KEY}
```

The same in YAML:

```yaml
llm4s:
  provider: openai
  model: gpt-4o
  api-key: ${OPENAI_API_KEY}
```

`llm4s.provider` and `llm4s.model` are required, and so is `llm4s.api-key` for OpenAI and Anthropic. (These flat keys serve
OpenAI, Anthropic and Ollama; every other provider is configured under `llm4s.providers`, see [Any provider](#any-provider).) A missing one stops
the application context from starting, with a message that names the property. Put the key in an environment variable or a
secrets store and refer to it with a placeholder, as above; do not commit it.

### Properties

| Property | Default | Meaning |
|---|---|---|
| `llm4s.enabled` | `true` | `false` registers none of the beans |
| `llm4s.provider` | empty | required without `llm4s.providers.*`: `openai`, `anthropic` or `ollama` |
| `llm4s.model` | empty | required: the model name as the provider spells it |
| `llm4s.api-key` | empty | required for `openai` and `anthropic`; not used by `ollama` |
| `llm4s.base-url` | empty | overrides the provider's default URL (below) |
| `llm4s.organization` | empty | OpenAI only: the organisation id |
| `llm4s.context-window` | `128000` | the model's context window in tokens; must be positive |
| `llm4s.reserve-completion` | `4096` | tokens kept for the reply; at least 0 and less than the context window |
| `llm4s.async.max-threads` | `16` | worker threads of the default executor |
| `llm4s.async.queue-capacity` | `1000` | calls queued before `completeAsync` fails its future |
| `llm4s.health.probe` | `false` | opt in to a real provider call per health check |
| `llm4s.health.probe-ttl` | `60s` | how long a probe result is reused |
| `llm4s.health.probe-timeout` | `10s` | a slower probe is cancelled and reports `DOWN` |
| `llm4s.agent.enabled` | `true` | `false` registers no `llm4sAgent` (the `llm4sTools` bean stays) |
| `llm4s.agent.id` | `assistant` | the agent's id, as its runs and traces name it |
| `llm4s.agent.system-prompt` | empty | sent first on every model call of the agent; none when empty |
| `llm4s.agent.streaming` | `false` | the agent's model calls stream, so `JAgent.stream` carries text deltas |
| `llm4s.agent.max-steps` | `50` | the most model calls and tool rounds one turn may take; then it ends `STEP_LIMIT_REACHED` |

The starter ships Spring Boot configuration metadata (`META-INF/spring-configuration-metadata.json`), so IntelliJ
IDEA and the Spring tools for VS Code and Eclipse complete these keys, show their defaults and flag a misspelt one in
`application.properties` and `application.yml`. Inside a `llm4s.providers.<name>` section they complete nothing: its keys
depend on the provider, and are listed under [Any provider](#any-provider).

### Which providers

The flat `llm4s.provider` key supports three providers:

| `llm4s.provider` | API key | Default `llm4s.base-url` |
|---|---|---|
| `openai` | required | `https://api.openai.com/v1` |
| `anthropic` | required | `https://api.anthropic.com` |
| `ollama` | not used | `http://localhost:11434` |

Any other value stops the context with `Unknown provider: '<name>'. The flat llm4s.provider key supports openai, anthropic
and ollama; configure any other provider under llm4s.providers.<name>`, followed by the providers on the classpath.

### Any provider

Every other provider (Gemini, Azure OpenAI, Mistral, OpenRouter, a self-hosted OpenAI-compatible server, or a provider
module you add) is configured under `llm4s.providers`. These properties mirror the `llm4s.providers` block of llm4s's own
HOCON configuration, so any provider on the classpath works, with the extra keys it declares. `llm4s.providers.provider`
names the section the starter uses:

```properties
llm4s.providers.provider=gemini-main
llm4s.providers.gemini-main.provider=gemini
llm4s.providers.gemini-main.model=gemini-2.0-flash
llm4s.providers.gemini-main.api-key=${GOOGLE_API_KEY}
```

Azure OpenAI takes its resource endpoint and API version as keys of the section:

```properties
llm4s.providers.provider=azure-main
llm4s.providers.azure-main.provider=azure
llm4s.providers.azure-main.model=my-deployment
llm4s.providers.azure-main.api-key=${AZURE_OPENAI_API_KEY}
llm4s.providers.azure-main.endpoint=https://my-resource.openai.azure.com
llm4s.providers.azure-main.api-version=2024-02-01
```

A server that speaks the OpenAI chat-completions format needs only its URL and model; the generic `openai-compatible`
provider also reads `context-window`, `reserve-completion` and `headers.*`:

```properties
llm4s.providers.provider=local
llm4s.providers.local.provider=openai-compatible
llm4s.providers.local.model=my-model
llm4s.providers.local.base-url=http://localhost:8000/v1
llm4s.providers.local.context-window=32000
llm4s.providers.local.headers.X-Team-Id=team-7
```

- `kebab-case` and `snake_case` names in a section are read as the `camelCase` names of the HOCON block (`api-key` is
  `apiKey`, `api-version` is `apiVersion`); section and header names are kept as written.
- A section with no `api-key` uses the vendor's shared `llm4s.credentials.<provider>.api-key`, then the vendor's
  conventional environment variable (`GOOGLE_API_KEY`, `MISTRAL_API_KEY`, ...).
- An `LLM4S_PROVIDERS_*` environment variable cannot add a setting; it only overrides one already declared in a property
  source (Spring's relaxed binding). Pass a secret with a placeholder (`api-key=${MY_KEY}`), or leave
  `api-key` out and set the vendor's environment variable, which reaches the section through `llm4s.credentials`.
- As soon as any `llm4s.providers.*` property is set, the block is used and the flat `llm4s.model`, `llm4s.api-key`, ...
  are ignored. With none, the flat keys are read exactly as before.
- An unknown provider id in a section stops the context with llm4s's own message, which names the registered providers
  and the dependency to add.
- Behind an outbound proxy or a corporate CA, the `llm4s.http.*` properties - `llm4s.http.proxy.url`,
  `.username`, `.password`, `.non-proxy-hosts` (a list), `llm4s.http.tls.trust-store`, `.trust-store-password`,
  `.key-store`, `.key-store-password` - apply to the client in both forms, the flat keys and `llm4s.providers.*`, as
  [`llm4s.http`](../getting-started/configuration#corporate-networks-a-proxy-and-a-custom-ca) does in HOCON. A
  section's own `llm4s.providers.<name>.http.*` overrides them for that section.

You can also define your own `JLlmClient` bean, for example from an `application.conf` as the Java guide describes. The
starter's client backs off, and the template and the health indicator use yours; `llm4s.provider` and the other properties
are then not needed:

```java
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtherProviderConfig {

    @Bean
    JLlmClient llm4sClient() {
        return Llm4s.createDefaultClient().get();
    }
}
```

`get()` throws an `LlmException` when the `application.conf` is wrong, which stops the context with its message.

## Use the template

`LLM4STemplate` is the bean to inject. It wraps the client: `complete` blocks the calling thread and returns the reply text,
`tryComplete` blocks and returns an `LlmResult` instead of throwing, and `completeAsync` does not block.

```java
import org.llm4s.spring.LLM4STemplate;
import org.springframework.stereotype.Service;

@Service
public class SummaryService {

    private final LLM4STemplate llm;

    public SummaryService(LLM4STemplate llm) {
        this.llm = llm;
    }

    public String summarise(String text) {
        return llm.complete("Summarise in one sentence: " + text);
    }
}
```

Each of the three takes a plain `String` or a `Conversation` (built with the Java API's `ConversationBuilder`); `complete` also
has a form that takes `CompletionOptions`.

### When a call fails

`complete` throws `LlmException` when the call fails. `tryComplete` returns the failure as a value:

```java
try {
    String answer = llm.complete("What is 2+2?");
    System.out.println(answer);
} catch (LlmException e) {
    System.err.println(e.getMessage());
}

LlmResult<String> result = llm.tryComplete("What is 2+2?");
```

`LlmException` and `LlmResult` are in `org.llm4s.javaapi` and are described in the [Java guide](java#handling-a-failure).

### Asynchronous calls

`completeAsync` returns a `CompletableFuture<String>` straight away. The blocking provider call runs on the
`llm4sTaskExecutor` bean and the future completes from there:

```java
CompletableFuture<String> reply = llm.completeAsync("Translate to French: " + text);
reply.thenAccept(answer -> System.out.println(answer));
```

It is [`JLlmClient.completeAsync`](java#asynchronous-calls) on that executor. A failed call completes the future
exceptionally with the `LlmException`. `reply.cancel(true)` interrupts the provider call. The
default executor is a bounded pool of daemon threads named `llm4s-async-N`, sized by `llm4s.async.*`; when its queue is full the
returned future fails with an `LlmException` of kind `OTHER` whose cause is the `RejectedExecutionException`, and the call does
not block. It is shut down with `shutdownNow` when the context closes, which interrupts calls still running. A
`llm4sTaskExecutor` bean of your own replaces it and can be any `ExecutorService`, a `ForkJoinPool` included: the future
interrupts the thread running its call itself, rather than relying on the executor's `Future.cancel(true)`.

### A web endpoint

A controller calls the service like any other Spring bean. This one is not compiled with the other snippets, because it needs
`spring-boot-starter-web`, which the starter does not depend on:

```java
// illustrative: needs spring-boot-starter-web, which the starter does not depend on
@RestController
class SummaryController {

    private final SummaryService summaries;

    SummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    @PostMapping("/summaries")
    String summarise(@RequestBody String text) {
        return summaries.summarise(text);
    }
}
```

## An agent with tools

Annotate a public method of any bean with the Java API's `@Tool`, and the starter's agent offers it to the model: the
`llm4sTools` bean collects the `@Tool` methods of every singleton bean, as
[`JTool.fromAnnotated`](java#tools) reads them, and the `llm4sAgent` bean is a `JAgent` over
the client and those tools.

```java
import org.llm4s.javaapi.Param;
import org.llm4s.javaapi.Tool;
import org.springframework.stereotype.Component;

@Component
public class WeatherTools {

    @Tool(description = "The current weather in a city")
    public String weather(@Param(name = "city", description = "City name") String city) {
        return "Sunny in " + city;
    }
}
```

Inject the agent where you need it. `run` blocks until the turn ends; `runAsync` runs the turn on the `llm4sTaskExecutor`.

```java
import org.llm4s.javaapi.JAgent;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

    private final JAgent agent;

    public AssistantService(JAgent agent) {
        this.agent = agent;
    }

    public String ask(String question) {
        return agent.run(question).get().answer().orElse("");
    }
}
```

Its system prompt, id, streaming and step limit are the `llm4s.agent.*` [properties](#properties). It keeps its
threads in the `llm4sCheckpointer` bean, in memory; define a `Checkpointer` bean of your own, such as
`llm4s-agent-checkpoint-sqlite`'s `SqliteCheckpointer` or `llm4s-agent-checkpoint-postgres`'s `PostgresCheckpointer`, and
the agent keeps them there instead.

The application fails to start, naming the bean, when a bean's `@Tool` methods cannot be read (a parameter type a tool
cannot take, say), when two tools have the same name, or when a tool bean is an AOP proxy: the proxy would be bypassed,
and with it advice such as `@Transactional`, so keep tools on a bean that is not proxied. For guardrails, a context
window or several agents, build them with [`Llm4s.agentBuilder`](java#an-agent-with-a-system-prompt-guardrails-and-a-context-window) and inject `Llm4sTools` for the tools.

## Embeddings

Set `llm4s.embeddings.model`, as `provider/model`, and the starter registers a `JEmbeddingClient` bean,
`llm4sEmbeddingClient`. The provider's own keys go under `llm4s.embeddings.<provider>`, as in the HOCON block of the
same name; without an `api-key` there, the client uses `llm4s.credentials.<provider>.api-key` or the vendor's variable,
such as `OPENAI_API_KEY`.

```properties
llm4s.embeddings.model=openai/text-embedding-3-small
llm4s.embeddings.openai.api-key=${OPENAI_API_KEY}
```

```java
import java.util.List;
import org.llm4s.javaapi.JEmbeddingClient;
import org.springframework.stereotype.Service;

@Service
public class SearchIndexer {

    private final JEmbeddingClient embeddings;

    public SearchIndexer(JEmbeddingClient embeddings) {
        this.embeddings = embeddings;
    }

    public List<float[]> vectors(List<String> passages) {
        return embeddings.embed(passages).get().vectors();
    }
}
```

Its `embedAsync` runs on the `llm4sTaskExecutor`. The provider's module must be on the classpath; a missing key or an
unknown provider stops the context from starting, with a message that names what to set.

## Streaming to a browser

Two beans stream a reply as the model writes it, each piece one server-sent event.

With Spring WebFlux, add `llm4s-reactor` and return the `Flux` of the `llm4sReactiveTemplate` bean:

```java
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ChatStreamController {

    private final ReactiveLLM4STemplate llm;

    public ChatStreamController(ReactiveLLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("q") String question) {
        return llm.stream(question);
    }
}
```

With Spring MVC, return the `SseEmitter` of the `llm4sSseTemplate` bean:

```java
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatSseController {

    private final SseLLM4STemplate llm;

    public ChatSseController(SseLLM4STemplate llm) {
        this.llm = llm;
    }

    @GetMapping("/chat/sse")
    public SseEmitter stream(@RequestParam("q") String question) {
        return llm.stream(question);
    }
}
```

Both take a `Conversation` as well as a question. The provider call runs on a thread of its own, not the request's. A
failed call ends the stream with its `LlmException`; a client that disconnects interrupts the provider call. The
templates are classes of their own, not methods of `LLM4STemplate`, so an application without Reactor or Spring MVC
loads none of their types.

## Tracing

Set `llm4s.tracing.mode` and the starter registers a `Tracing` bean, `llm4sTracing`, which the `llm4sAgent` reports its
runs, model calls and tool calls to. The mode is `console`, `none`, or that of a tracing backend on the classpath:
`opentelemetry` with `llm4s-observability-otel`, `langfuse` with `llm4s-observability`. The backend's settings go under
`llm4s.tracing.<mode>`, as in the HOCON block of the same name:

```properties
llm4s.tracing.mode=opentelemetry
llm4s.tracing.opentelemetry.endpoint=http://localhost:4317
llm4s.tracing.opentelemetry.service-name=orders-service
```

A mode no backend on the classpath serves stops the context from starting, naming the module to add. The tracer is shut
down, flushing what it holds, when the context closes.

## Health

With Actuator on the classpath the starter adds a health indicator, the bean `llmHealthIndicator`. By default it makes **no
provider call**: it reports `UP` with the details `provider`, `model` and `probe=disabled`, which means "configured", not "reachable". The API key
is never reported.

With `llm4s.health.probe=true` it sends a one-token completion on the executor, waits at most `llm4s.health.probe-timeout`, and
reuses the outcome (a failure too) for `llm4s.health.probe-ttl`, so health polling does not call the provider each time. A
failed or timed-out probe is `DOWN` with `probe=failed` or `probe=timeout` and an `error` message that has the API key and
token-like strings removed. Every credential of the resolved provider is removed, wherever it was set (a Spring property,
`application.conf`, a `reference.conf` default or an environment variable) and however deep it sits in the provider's
config: Bedrock's access key id, secret key and session token, a workload-identity token, and every `headers` value,
whatever the header is called, along with the token after an auth scheme such as `Bearer`. A health check whose thread is interrupted while it waits (Actuator shutting down, say) is
`DOWN` with `probe=cancelled` and the interrupt flag left set; `health()` never throws `InterruptedException`. A cancelled
probe is not cached: it says something about that thread, not the provider, so the next check probes again. A probe is a
billed call: keep the TTL generous.

## Metrics

Add `llm4s-observability-micrometer` next to `spring-boot-starter-actuator`, and the client records request, duration,
token, cost and error meters (`llm4s.requests`, `llm4s.request.duration`, `llm4s.tokens`, ...) in the application's
`MeterRegistry`, with no code: they are on `/actuator/metrics` and go wherever your registry exports (Prometheus,
Datadog, CloudWatch, OTLP). The starter declares the module `provided`, as it does Actuator, so nothing Micrometer reaches
your classpath unless you add it.

```xml
<dependency>
    <groupId>org.llm4s</groupId>
    <artifactId>llm4s-observability-micrometer_3</artifactId>
    <version>{{ site.data.project.latest_release }}</version>
</dependency>
```

A `MetricsCollector` bean of your own replaces the Micrometer one, and the client reports to every `MetricsCollector` bean
in the context, so a cost tracker can run next to it. The meters, their tags and the matching Prometheus series are in
[Micrometer metrics](observability/micrometer).

## Testing code that uses the template

Because the starter's beans back off for a bean of the same type, a test configuration can supply its own `JLlmClient`:
`FakeLlmClient.replying("...").client()` from `llm4s-java-api` answers from a script and records the prompts it was sent,
with no network or key ([Java: testing your code](java#testing-your-code)). A test that does not need the model can
instead run with `llm4s.enabled=false`, so no client is built and no key is needed.

## Where next

- [Moving a Java app from LangChain4j or Spring AI](java-migration): Spring AI and LangChain4j starter properties and
  beans mapped to this starter, with a compiled controller.
- [Java](java): the client, conversations and failures the template is built on.
- [Providers](providers) and [configuration](../getting-started/configuration): every provider, for the HOCON route.
- The starter's own [README](https://github.com/llm4s/llm4s/blob/main/modules/spring-boot-starter/README.md).
