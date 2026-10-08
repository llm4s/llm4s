---
layout: page
title: Spring Boot
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
| `llmHealthIndicator` | an Actuator `HealthIndicator`, only when Spring Boot Actuator is on the classpath | defining a bean of type `LlmHealthIndicator` |

Set `llm4s.enabled=false` to switch all of them off; the beans are also registered when `llm4s.enabled` is absent. It is built
against Spring Boot 3.3.6.

{: .note }
> **Not published yet.** `llm4s-spring-boot-starter` is not on Maven Central: the latest release, `0.4.1`, is a single
> `llm4s-core` artifact, and the starter is planned to ship with `0.5.0`, together with the Java API it is built on.
> Until then, build both from a checkout of the repository; the
> [`gradle-java` sample](https://github.com/llm4s/llm4s/tree/main/modules/samples/gradle-java) shows the steps for the
> Java API. Which JDK is the minimum is not settled yet: follow
> [#1493](https://github.com/llm4s/llm4s/issues/1493).

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
```

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("org.llm4s:llm4s-spring-boot-starter_3:{{ site.data.project.latest_release }}")
}
```

It brings the Java API, so the [dependency notes of the Java guide](java#add-the-dependency) apply: the provider modules,
Scala's standard library, and no logging backend. For the health indicator, add `spring-boot-starter-actuator` yourself:
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

`llm4s.provider` and `llm4s.model` are required, and so is `llm4s.api-key` for OpenAI and Anthropic. A missing one stops
the application context from starting, with a message that names the property. Put the key in an environment variable or a
secrets store and refer to it with a placeholder, as above; do not commit it.

### Properties

| Property | Default | Meaning |
|---|---|---|
| `llm4s.enabled` | `true` | `false` registers none of the beans |
| `llm4s.provider` | empty | required: `openai`, `anthropic` or `ollama` |
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

### Which providers

The starter supports three providers:

| `llm4s.provider` | API key | Default `llm4s.base-url` |
|---|---|---|
| `openai` | required | `https://api.openai.com/v1` |
| `anthropic` | required | `https://api.anthropic.com` |
| `ollama` | not used | `http://localhost:11434` |

Any other value stops the context with `Unknown provider: '<name>'. Supported: openai, anthropic, ollama`. Supporting every
provider is proposed in [#1467](https://github.com/llm4s/llm4s/issues/1467). Until then, for another provider (Gemini,
Azure, an OpenAI-compatible endpoint and so on) define your own `JLlmClient` bean from an `application.conf`, as the Java guide
describes. The starter's client backs off, and the template and the health indicator use yours; `llm4s.provider` and the other
properties are then not needed:

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

A failed call completes the future exceptionally with the `LlmException`. `reply.cancel(true)` interrupts the provider call. The
default executor is a bounded pool of daemon threads named `llm4s-async-N`, sized by `llm4s.async.*`; when its queue is full the
returned future fails with `RejectedExecutionException` and the call does not block. It is shut down with `shutdownNow` when the
context closes, which interrupts calls still running. A `llm4sTaskExecutor` bean of your own replaces it and must be an
`ExecutorService` whose `submit(..).cancel(true)` interrupts the task. The `ThreadPoolExecutor` pools
(`Executors.newFixedThreadPool`, `newCachedThreadPool`, `newSingleThreadExecutor`, `newScheduledThreadPool`)
do that. A `ForkJoinPool` — including `Executors.newWorkStealingPool()` and the common pool — does not:
its `cancel(true)` reports the task cancelled but never interrupts a running worker, so a provider call
would keep running to completion behind a future that claims to be cancelled.

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

## Health

With Actuator on the classpath the starter adds a health indicator, the bean `llmHealthIndicator`. By default it makes **no
provider call**: it reports `UP` with the details `provider`, `model` and `probe=disabled`, which means "configured", not "reachable". The API key
is never reported.

With `llm4s.health.probe=true` it sends a one-token completion on the executor, waits at most `llm4s.health.probe-timeout`, and
reuses the outcome (a failure too) for `llm4s.health.probe-ttl`, so health polling does not call the provider each time. A
failed or timed-out probe is `DOWN` with `probe=failed` or `probe=timeout` and an `error` message that has the API key and
token-like strings removed. A health check whose thread is interrupted while it waits (Actuator shutting down, say) is
`DOWN` with `probe=cancelled` and the interrupt flag left set; `health()` never throws `InterruptedException`. A probe is a
billed call: keep the TTL generous.

## Testing code that uses the template

There is no supported fake client for tests yet ([#1497](https://github.com/llm4s/llm4s/issues/1497)). What works today: a test
that does not need the model can run with `llm4s.enabled=false`, so no client is built and no key is needed; put the template
behind an interface of your own, which that test replaces. And because the starter's beans back off for a bean of the same
type, a test configuration can supply its own `JLlmClient`.

## Where next

- [Java](java): the client, conversations and failures the template is built on.
- [Providers](providers) and [configuration](../getting-started/configuration): every provider, for the HOCON route.
- The starter's own [README](https://github.com/llm4s/llm4s/blob/main/modules/spring-boot-starter/README.md).
